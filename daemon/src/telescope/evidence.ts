// What a session's transcript says it was building, source by source, and when it last said so:
// the telescope looks at the newest first. Only what the session did counts (commands it ran,
// files it wrote or read), never what Claude merely wrote about. Pure: the files themselves are
// checked where they're shown (files.ts), the devices where they're captured.
import { isAbsolute, resolve } from 'node:path'
import type { Ev, ToolEv } from '../normalize.ts'
import { SERVER_CMD } from '../shots.ts'
import { PICTURE_NAME, type PictureClue } from './files.ts'
import { validSerial } from './android.ts'
import type { Source } from './common.ts'

export type Clue =
  | { source: 'web'; at: number }
  | { source: 'emulator'; at: number; device: string | null }
  | { source: 'simulator'; at: number }
  | { source: 'window'; at: number }
  | ({ source: 'image' } & PictureClue)

/**
 * Where a program is run as the command itself, not merely named (`grep -r adb`, `npm i electron`):
 * the start of the line or after `;`, `&&`, `|`, `(` or `$(`, maybe behind sudo/exec/env or a
 * variable (`ANDROID_SERIAL=… adb`), maybe by its path (`$ANDROID_HOME/platform-tools/adb`).
 */
const AS_COMMAND = String.raw`(?:^|[;&|(\n]|\$\()\s*(?:(?:sudo|exec|time|env|nohup)\s+)?(?:[A-Za-z_]\w*=\S*\s+)*["']?(?:[^\s"']*/)?`
/** Commands that put an app on an Android emulator or phone. */
export const ANDROID_CMD = new RegExp(String.raw`${AS_COMMAND}adb["']?(?=\s|$)|` + /\bemulator\s+(?:-avd\b|@)|\bgradlew\b[^\n|;&]*?\b(?:install\w*|connected\w*Test\w*)\b|\bflutter\s+(?:run|install)\b|\breact-native\s+run-android\b|\bexpo\s+run:android\b|\bcap\s+run\s+android\b|\bmaestro\s+test\b/i.source, 'i')
/** …on the iOS Simulator. */
export const IOS_CMD = /\bsimctl\b|\bopen\s+-a\s+["']?Simulator\b|\bxcodebuild\b[^\n]*(?:iphonesimulator|iOS Simulator)|\breact-native\s+run-ios\b|\bexpo\s+run:ios\b|\bcap\s+run\s+ios\b|\bflutter\s+run\b[^\n]*\s-d\s+["']?(?:ios|iphone)/i
/** …a Mac app: one it builds and runs from its folder, or opens. */
export const MAC_APP_CMD = new RegExp(/\bopen\s+(?:-[a-zA-Z]+\s+)*["']?[^\s"']+\.app\b|\bswift\s+run\b|(?:^|[\s;&|(])(?:\.\/)?(?:[\w.-]+\/)*\.build\/(?:[\w-]+\/)?(?:debug|release)\/[\w.-]+|\bxcodebuild\b(?![^\n]*(?:iphone|iOS|watchOS|tvOS|visionOS))|\bflutter\s+run\b[^\n]*\s-d\s+["']?macos\b|\btauri\s+dev\b/i.source + String.raw`|${AS_COMMAND}electron(?:\s|$)|\bnpx\s+electron\b|\belectron-forge\s+start\b`, 'i')
/** …a page in a browser on this Mac (as well as the servers SERVER_CMD knows). */
const WEB_CMD = /\bopen\s+["']?https?:\/\/(?:localhost|127\.0\.0\.1|\[::1\])|\bflutter\s+run\b[^\n]*\s-d\s+["']?(?:chrome|web-server|edge)\b/i

/** The device a command names: `adb -s …`, `ANDROID_SERIAL=…`, `flutter run -d …`, `--deviceId …`. */
export function deviceIn(command: string): string | null {
  const unquote = (s: string | undefined) => (s ?? '').replace(/^["']|["']$/g, '')
  const m = /\badb\s+(?:-[a-zA-Z]+\s+)*-s\s+(\S+)/.exec(command) ?? /\bANDROID_SERIAL=(\S+)/.exec(command) ?? /\bflutter\s+(?:run|install)\b[^\n]*?\s-d\s+(\S+)/.exec(command) ?? /--device(?:Id|-id)?[= ](\S+)/.exec(command)
  const id = unquote(m?.[1])
  return validSerial(id) ? id : null
}

/**
 * File names that look like pictures in a command line or its output: `out/chart.png`, `"./a b.pdf"`
 * no. A comma only separates names (it isn't part of one), and each part has a length cap: a name may
 * start after every comma, and with commas inside names too, data a session prints (`0.1,0.2,…`) made
 * every comma scan to the end of the output, on the daemon's event loop.
 */
const PATHS = /(?:^|[\s"'`=(:,[])((?:~\/|\.{1,2}\/|\/)?(?:[\w@%+.-]{1,255}\/){0,32}[\w@%+-][\w@%+.-]{0,254}\.(?:png|jpe?g|gif|webp|svg|pdf|tiff?|bmp|heic|heif))(?=$|[\s"'`),:;\]])/gim

/** Picture files a command names or printed, as paths (relative ones resolved in the session's folder). */
export function picturesIn(text: string, cwd: string): string[] {
  const out: string[] = []
  for (const m of text.matchAll(PATHS)) {
    const p = m[1]!
    const full = p.startsWith('~/') ? p : isAbsolute(p) ? p : resolve(cwd, p)
    if (!out.includes(full)) out.push(full)
  }
  // The last ones named are the likeliest result ("Saved to out/chart.png"); a few are plenty.
  return out.slice(-12)
}

/** A PDF page a Read asked for: `pages: "3"` or `"3-5"`. */
const pageOf = (input: Record<string, unknown>): number | null => {
  const n = Number(/^\s*(\d{1,5})/.exec(String(input?.pages ?? ''))?.[1])
  return Number.isInteger(n) && n > 0 ? n : null
}

const isTool = (e: Ev): e is ToolEv => e.kind === 'tool'
const commandOf = (e: ToolEv) => String((e.input as { command?: unknown })?.command ?? '')

/** How far back the telescope looks: what a session builds shows in its recent work. */
const NEWEST = 600

/**
 * Each event's clues, kept: a watching client asks every few seconds, and a finished event doesn't
 * change. Keyed by what can still change while a command runs (its status and output).
 */
const memo = new WeakMap<Ev, { key: string; clues: Clue[] }>()

/** Every clue in the transcript, newest first. */
export function clues(events: Ev[], cwd: string): Clue[] {
  const out: Clue[] = []
  for (let i = events.length - 1; i >= Math.max(0, events.length - NEWEST); i--) {
    const e = events[i]!
    if (!isTool(e)) continue
    const key = `${cwd}\0${e.status}\0${String(e.output ?? '').length}`
    let m = memo.get(e)
    if (!m || m.key !== key) memo.set(e, (m = { key, clues: eventClues(e, cwd) }))
    out.push(...m.clues)
  }
  return out
}

/** What one tool event points at. */
function eventClues(e: ToolEv, cwd: string): Clue[] {
  const out: Clue[] = []
  const at = e.ts
  const file = String(e.path ?? (e.input as any)?.file_path ?? (e.input as any)?.notebook_path ?? '')
  if (['Write', 'Edit', 'MultiEdit', 'NotebookEdit'].includes(e.name)) {
    if (e.status === 'error' || !file) return out
    if (/\.html?$/i.test(file)) out.push({ source: 'web', at })
    else if (PICTURE_NAME.test(file)) out.push({ source: 'image', at, path: file, page: null, wrote: true })
    return out
  }
  if (e.name === 'Read' && PICTURE_NAME.test(file)) {
    out.push({ source: 'image', at, path: file, page: pageOf(e.input), wrote: false })
    return out
  }
  if (e.name !== 'Bash') return out
  const cmd = commandOf(e)
  if (SERVER_CMD.test(cmd) || WEB_CMD.test(cmd)) out.push({ source: 'web', at })
  if (ANDROID_CMD.test(cmd) && !IOS_CMD.test(cmd) && !/\bflutter\s+run\b[^\n]*\s-d\s+["']?(?:macos|chrome|web-server|edge)\b/i.test(cmd)) out.push({ source: 'emulator', at, device: deviceIn(cmd) })
  if (IOS_CMD.test(cmd)) out.push({ source: 'simulator', at })
  if (MAC_APP_CMD.test(cmd)) out.push({ source: 'window', at })
  for (const path of picturesIn(`${cmd}\n${String(e.output ?? '').slice(-16 * 1024)}`, cwd)) out.push({ source: 'image', at, path, page: null, wrote: false })
  return out
}

/** The device the session used last, if its commands named one. */
export function usedDevice(list: Clue[]): string | null {
  for (const c of list) if (c.source === 'emulator' && c.device) return c.device
  return null
}

/**
 * The order to look in when nothing is named: each source the transcript points at, newest first;
 * then a page (a server may listen in the session's folder without the transcript saying so); then
 * the terminal, which is there for command-line work whenever nothing else is.
 */
export function plan(list: Clue[]): Source[] {
  const order: Source[] = []
  for (const c of [...list].sort((a, b) => b.at - a.at)) if (!order.includes(c.source)) order.push(c.source)
  if (!order.includes('web')) order.push('web')
  order.push('terminal')
  return order
}
