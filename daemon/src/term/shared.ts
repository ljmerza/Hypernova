// Shared terminals: what the holder, the `hn` CLI and the daemon have in common
// (docs/PROTOCOL.md, "Shared terminals").
//
// Every frame on a holder's unix socket is [type u8][length u32 BE][payload]. Type 1 carries
// raw terminal bytes (output to clients, keystrokes from them), type 2 one JSON message.
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { CLAUDE_DIR, SUPPORT } from '../util.ts'

export const DATA = 1
export const MSG = 2
const MAX_FRAME = 32 * 1024 * 1024

export const TERMS = join(SUPPORT, 'terms')
mkdirSync(TERMS, { recursive: true, mode: 0o700 })
// Unix socket paths max out at 104 bytes on macOS; a very long home folder gets a short private dir.
const SOCKS = join(TERMS, 'x'.repeat(16)).length < 100 ? TERMS : shortDir()
function shortDir(): string {
  const d = `/tmp/hypernova-${process.getuid?.() ?? 0}`
  mkdirSync(d, { recursive: true, mode: 0o700 })
  const s = statSync(d)
  if (s.uid !== process.getuid?.() || (s.mode & 0o077) !== 0) throw new Error(`${d} isn't private to this user`)
  return d
}
export const sockPath = (id: string) => join(SOCKS, `${id}.sock`)
/** A Codex holder's socket in front of Codex's app-server, which its TUI connects to (`--remote`). */
export const codexProxyPath = (id: string) => join(SOCKS, `${id}.cx.sock`)

/**
 * The Codex TUI's arguments for a holder: [args] as typed, aimed at the holder's own socket, in
 * [cwd] (with `--remote` the TUI doesn't send its folder: docs/specs/codex-app-server.md, 1.6).
 * `--remote` goes after `resume` or `fork`, before anything else otherwise.
 */
export function codexTuiArgs(id: string, args: string[], cwd: string): string[] {
  const remote = ['--remote', `unix://${codexProxyPath(id)}`]
  const where = args.some((a) => a === '-C' || a === '--cd' || a.startsWith('--cd=')) ? [] : ['--cd', cwd]
  return args[0] === 'resume' || args[0] === 'fork' ? [args[0], ...remote, ...where, ...args.slice(1)] : [...remote, ...where, ...args]
}
export const metaPath = (id: string) => join(TERMS, `${id}.json`)
export const newTermId = () => randomBytes(5).toString('hex')

export type Role = 'tty' | 'phone' | 'control'
/** The Mac terminal app a session was started from, so "Open on Mac" can use the same one. */
export type TerminalApp = { app: string | null; socket?: string | null }

/** What starts a holder. Written to a 0600 file the holder reads and deletes (env can hold keys). */
export type HolderSpec = {
  id: string
  cwd: string
  file: string
  args: string[]
  env: Record<string, string>
  cols: number
  rows: number
  origin: 'terminal' | 'hypernova'
  /** The Claude Code session it starts with, when known (--session-id / --resume). */
  sessionId: string | null
  terminal: TerminalApp
  /** Start claude only once a terminal attaches, so the real terminal answers claude's start-up queries. */
  waitForTty: boolean
  /** Accept the "trust this folder" prompt: the owner picked the folder in Hypernova. */
  autoTrust: boolean
  /**
   * "codex": the Codex TUI, on Codex's shared app-server through this holder's own socket
   * ([codexProxyPath]), which tells the holder the thread it runs. [codexSocket]: the app-server's.
   */
  agent?: 'codex'
  codexSocket?: string
}

/** <id>.json next to the socket: how the daemon and `hn ls` find holders. */
export type TermMeta = {
  v: 1
  id: string
  pid: number
  childPid: number | null
  cwd: string
  args: string[]
  origin: 'terminal' | 'hypernova'
  sessionId: string | null
  createdAt: number
  terminal: TerminalApp
  title: string
  tty: number
  phone: number
  /** "codex" for a Codex TUI, whose [sessionId] is the thread it runs now. Absent for Claude Code. */
  agent?: 'codex'
}

/** Live state the holder sends to every client ([sessionId]: a Codex holder's thread, once known). */
export type TermState = { cols: number; rows: number; title: string; childPid: number | null; tty: number; phone: number; started: boolean; sessionId?: string | null }

export function frame(type: number, payload: Buffer | string): Buffer {
  const p = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : payload
  const h = Buffer.allocUnsafe(5)
  h[0] = type
  h.writeUInt32BE(p.length, 1)
  return Buffer.concat([h, p])
}
export const msg = (o: object) => frame(MSG, JSON.stringify(o))

/** Splits a byte stream into frames. Throws on a frame no holder would send. */
export class Frames {
  private buf: Buffer = Buffer.alloc(0)
  private readonly on: (type: number, payload: Buffer) => void
  constructor(on: (type: number, payload: Buffer) => void) {
    this.on = on
  }
  push(chunk: Buffer): void {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
    while (this.buf.length >= 5) {
      const len = this.buf.readUInt32BE(1)
      if (len > MAX_FRAME) throw new Error('frame too large')
      if (this.buf.length < 5 + len) break
      const type = this.buf[0]!
      const payload = this.buf.subarray(5, 5 + len)
      this.buf = this.buf.subarray(5 + len)
      this.on(type, payload)
    }
  }
}

/** Start a detached holder: its own session and process group, so it outlives its parent. Returns its pid. */
export function spawnHolder(spec: HolderSpec, nodePath = process.execPath): number | null {
  const specFile = join(TERMS, `${spec.id}.spec`)
  writeFileSync(specFile, JSON.stringify(spec), { mode: 0o600 })
  const holder = join(dirname(fileURLToPath(import.meta.url)), 'holder.ts')
  const env: Record<string, string> = {}
  for (const k of ['HOME', 'USER', 'LOGNAME', 'PATH', 'LANG', 'TMPDIR', 'HYPERNOVA_SUPPORT', 'HYPERNOVA_LOGS']) if (process.env[k]) env[k] = process.env[k]!
  const child = spawn(nodePath, ['--disable-warning=ExperimentalWarning', holder, specFile], { detached: true, stdio: 'ignore', cwd: spec.cwd, env })
  child.on('error', () => {})
  child.unref()
  return child.pid ?? null
}

/** Variables a parent claude or an older holder set; a new session must not inherit them. */
const SCRUB = new Set(['CLAUDECODE', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_CODE_CHILD_SESSION', 'CLAUDE_CODE_MESSAGING_SOCKET', 'CLAUDE_CODE_MESSAGING_TOKEN', 'CLAUDE_PID', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_SESSION_ATTENDED', 'CLAUDE_EFFORT', 'CLAUDE_CODE_EXECPATH'])
export function sessionEnv(base: NodeJS.ProcessEnv, id: string, extra: Record<string, string> = {}): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(base)) if (v !== undefined && !SCRUB.has(k) && !k.startsWith('HYPERNOVA_')) env[k] = v
  // Phones render with xterm.js and Macs with any terminal: speak the common dialect.
  Object.assign(env, { TERM: 'xterm-256color', COLORTERM: base.COLORTERM || 'truecolor', LANG: base.LANG || 'en_US.UTF-8' }, extra)
  env.HYPERNOVA_TERM = id
  return env
}

// ── Claude Code's screen ─────────────────────────────────────────────────
const rule = (s: string) => /^\s*─{8,}/.test(s)
/**
 * Where Claude Code's input box is on a screen of plain lines: between two rules, starting
 * with ❯ (or ! in shell mode, after a "!" is typed). Also the lines just above it (the spinner while Claude works) and below it (the
 * owner's status line, if any, then the mode line).
 */
export function boxLines(lines: string[]): { top: number; bottom: number; above: string[]; below: string[] } | null {
  for (let j = lines.length - 1; j > 0; j--) {
    if (!rule(lines[j]!)) continue
    let i = j - 1
    while (i >= 0 && j - i <= 16 && !rule(lines[i]!)) i--
    if (i < 0 || !rule(lines[i]!)) continue
    if (!/^\s*[❯>!]/.test(lines[i + 1] ?? '')) continue
    return { top: i, bottom: j, above: lines.slice(Math.max(0, i - 6), i), below: lines.slice(j + 1, j + 5) }
  }
  return null
}
/** The box's text from plain lines (the empty box's dim suggestion can't be told apart here). */
export function boxText(lines: string[], b: { top: number; bottom: number }): string {
  return lines
    .slice(b.top + 1, b.bottom)
    .map((s, k) => (k === 0 ? s.replace(/^\s*[❯>!]\s?/, '') : s))
    .join('\n')
    .trim()
}
/**
 * Claude shows it's working: a spinner line above the box ("✢ Flambéing… (1m 11s", "· Brewing…",
 * "⎿ Running…") or "esc to interrupt" below. Not while it streams text (then there's no
 * spinner), so this goes with [registryBusy] and recent output.
 */
export const isWorking = (b: { above: string[]; below: string[] }) =>
  b.above.some((l) => /^\s*[^\p{L}\p{N}\s]\s+[\p{L}\- ]+…/u.test(l)) || b.below.some((l) => /esc to interrupt/i.test(l))
/** Claude Code's own word on it: its session registry says "busy" while it works. */
export function registryBusy(childPid: number | null | undefined): boolean {
  if (!childPid) return false
  try {
    return JSON.parse(readFileSync(join(CLAUDE_DIR, 'sessions', `${childPid}.json`), 'utf8'))?.status === 'busy'
  } catch {
    return false
  }
}
/** The permission mode as the line under the box shows it; Shift+Tab cycles through them. */
const MODES: [RegExp, string][] = [
  [/manual mode on/i, 'default'],
  [/accept edits on/i, 'acceptEdits'],
  [/plan mode on/i, 'plan'],
  [/auto mode on/i, 'auto'],
  [/bypass permissions on/i, 'bypassPermissions'],
]
export const modeOf = (below: string[]) => {
  const s = below.join('\n')
  return MODES.find(([re]) => re.test(s))?.[1] ?? null
}

// ── Menus ────────────────────────────────────────────────────────────────
export type MenuOption = { label: string; detail: string | null; current: boolean }
/** A menu or dialog Claude Code shows in place of its input box, read off the screen. */
export type Menu = {
  /** Its title and options, hashed: an answer names the menu it was for. */
  id: string
  title: string | null
  /** The lines between the title and the options (a wrapped paragraph is one entry). */
  body: string[]
  options: MenuOption[]
  /** The option Enter picks now. */
  cursor: number
  /** It scrolls: there are options the screen doesn't show. */
  more: boolean
  /** Esc closes it. */
  cancel: boolean
  /** The footer's second way to pick ("Enter to set as default · s to use this session only"). */
  alt: { enter: string; key: string; label: string } | null
  /** Claude Code's own permission dialog ([Permission]); null for any other menu. */
  permission: Permission | null
}
/**
 * Claude Code's own permission dialog, the one it shows when no hook answered (an ask rule over
 * auto mode, a hook that handed it back): its question ("Do you want to proceed?") last above
 * options that start with Yes and have a No. [tool] is the dialog's title ("Bash command", "Edit
 * file"); [command] what it would run or change, between its dashed rules, the "│ " margin taken
 * off and lines the screen wrapped joined again (null when it shows none).
 */
export type Permission = { tool: string | null; command: string | null; question: string }
/** The question over a permission dialog's options. */
const ASKS = /^Do you want to\b.*\?$/
const MARKS = ' ❯›↓↑'
const dashed = (s: string) => /^\s*[╌┄]{8,}\s*$/.test(s)
const framed = (s: string) => /^\s*[╭╰│┌└]/.test(s)
const moreLine = (s: string) => /^\s*(…\s*\+\d+|[↓↑]?\s*\d+ more\b)/.test(s)
const numOf = (s: string) => Number(/^(\d+)[.)]\s/.exec(s)?.[1] ?? NaN)

/** The line wrapped into the next one: the next one's first word wouldn't have fitted after it (dialogs keep a margin). */
const wraps = (line: string, next: string, width: number) => line.trimEnd().length + 1 + (/\S+/.exec(next)?.[0].length ?? 0) > width - 4
/** Lines as paragraphs: a line that wrapped goes on in the next one. */
function paragraphs(lines: string[], width: number): string[] {
  const out: string[] = []
  let prev: string | null = null
  for (const l of lines) {
    if (!l.trim() || dashed(l) || framed(l)) {
      prev = null
      continue
    }
    const t = l.trim().replace(/\s{2,}/g, ' ')
    if (prev !== null && out.length && wraps(prev, l, width)) out[out.length - 1] += ' ' + t
    else out.push(t)
    prev = l
  }
  return out
}

/**
 * The menu on a screen of plain lines, when Claude Code shows one instead of its input box: a
 * list with the ❯ cursor on one option (numbered or not), the text above it back to the rule
 * that opens the dialog, and a footer that says what Enter and Esc do. Null when the input box
 * is up, or nothing on screen reads as a menu.
 */
export function menuOf(lines: string[]): Menu | null {
  if (boxLines(lines)) return null
  let c = -1
  for (let i = lines.length - 1; i >= 0 && c < 0; i--) if (/^\s*❯\s+\S/.test(lines[i]!)) c = i
  if (c < 0) return null
  const width = Math.max(80, ...lines.filter(rule).map((l) => l.trimEnd().length))
  const cl = lines[c]!
  const m = cl.indexOf('❯')
  const t = m + 1 + /^\s*/.exec(cl.slice(m + 1))![0].length
  // An option: blank up to the cursor's column, a mark or blank there, its text where the cursor's is.
  const isOpt = (s: string) => s.length > t && !s.slice(0, m).trim() && MARKS.includes(s[m]!) && !s.slice(m + 1, t).trim() && s[t] !== ' '
  // A line indented deeper goes with the option above it (a description, or a long label wrapped).
  const isCont = (s: string) => !!s.trim() && !s.slice(0, t + 1).trim() && !moreLine(s)
  let a = c
  while (a > 0 && (isOpt(lines[a - 1]!) || isCont(lines[a - 1]!))) a--
  while (!isOpt(lines[a]!)) a++
  // A numbered list may start above a rule ("5. Chat about this" sits under one): go back to 1.
  const first = numOf(lines[a]!.slice(t))
  if (first > 1)
    for (let i = a - 1; i >= Math.max(0, a - 40); i--)
      if (isOpt(lines[i]!) && numOf(lines[i]!.slice(t)) === 1) {
        a = i
        break
      }
  type E = { i: number; text: string; last: string; cont: string[] }
  const es: E[] = []
  let more = false
  let end = a
  for (let i = a; i < lines.length; ) {
    const s = lines[i]!
    if (isOpt(s)) {
      if (moreLine(s.slice(t)) || moreLine(s)) more = true
      else es.push({ i, text: s.slice(t).trimEnd(), last: s, cont: [] })
      if ('↓↑'.includes(s[m]!)) more = true
      end = i++
      continue
    }
    if (isCont(s) && es.length) {
      const e = es[es.length - 1]!
      // A label that wrapped goes on; anything else under it describes it.
      const wrapped = wraps(e.last, s, width)
      if (wrapped && !e.cont.length) e.text += ' ' + s.trim()
      else if (wrapped) e.cont[e.cont.length - 1] += ' ' + s.trim()
      else e.cont.push(s.trim())
      e.last = s
      end = i++
      continue
    }
    if (moreLine(s)) {
      more = true
      end = i++
      continue
    }
    // Numbered options go on across a blank line or a rule, to the next number only.
    const last = numOf(es[es.length - 1]?.text ?? '')
    let j = i
    while (j < lines.length && j - i < 2 && (!lines[j]!.trim() || rule(lines[j]!))) j++
    if (j > i && lines[j] !== undefined && isOpt(lines[j]!) && numOf(lines[j]!.slice(t)) === last + 1) {
      i = j
      continue
    }
    break
  }
  const cursor = es.findIndex((e) => e.i === c)
  if (es.length < 2 || cursor < 0) return null
  // The footer: what Enter and Esc do. Without one, numbered options under the dialog's rule
  // will do ("Switch model?"); anything else isn't a menu (a prompt echo in the transcript, say).
  const footer = lines.slice(end + 1, end + 7).find((l) => /\b(enter|esc)\b[^·]*\bto\b/i.test(l)) ?? ''
  const numbered = es.every((e, k) => numOf(e.text) === k + 1)
  if (!footer && !(numbered && lines.slice(Math.max(0, a - 30), a).some(rule) && !lines.slice(a).some(rule))) return null
  const keys = footer.split('·').map((p) => /^\s*(\S+) to (.+?)\s*$/.exec(p)).filter((x): x is RegExpExecArray => !!x)
  const enter = keys.find((k) => /^enter$/i.test(k[1]!))?.[2] ?? null
  const key = keys.find((k) => /^[a-z0-9]$/i.test(k[1]!))
  // The title: from the rule that opens the dialog down to the options.
  let top = a - 1
  while (top >= 0 && a - top <= 30 && !rule(lines[top]!)) top--
  // With several asks open at once, Claude Code counts them at the right of the title row ("1 of
  // 3"; a subagent's dialog says whose after the title, "Bash command · from the Explore agent"):
  // not the title's, and a row that full would read as one that wrapped into the next.
  const lead = lines.slice(top + 1, a).findIndex((l) => l.trim())
  const region = lines.slice(top + 1, a).map((l, k) => (k === lead ? l.replace(/(\S)\s{2,}\d+ of \d+\s*$/, '$1') : l))
  let head = paragraphs(region, width)
  const title = head[0] && head[0].length <= 80 ? head.shift()! : null
  const options = es.map((e) => {
    let text = e.text.replace(/^\d+[.)]\s+/, '')
    const current = /\s[✔✓]($|\s)/.test(' ' + text)
    text = text.replace(/\s[✔✓](?=$|\s)/, '')
    const parts = text.split(/\s{2,}/).map((p) => p.trim()).filter(Boolean)
    const detail = [...parts.slice(1), ...e.cont].join(' · ')
    return { label: parts[0] ?? text.trim(), detail: detail || null, current }
  })
  const question = head.length ? head[head.length - 1]! : title
  let permission: Permission | null = null
  if (question && ASKS.test(question) && /^yes\b/i.test(options[0]!.label) && options.some((o) => /^no\b/i.test(o.label))) {
    const block = commandBlock(region, width)
    permission = { tool: title === question ? null : title, command: block?.text ?? null, question }
    // What it would run is the command, not the body.
    if (block) {
      head = paragraphs([...region.slice(0, block.from), ...region.slice(block.to + 1)], width)
      if (title !== null && head[0] === title) head.shift()
    }
  }
  // A permission dialog's id names its command too: Allow on the phone never answers the next one.
  const id = createHash('sha1')
    .update(JSON.stringify([title, options.map((o) => o.label), ...(permission?.command ? [permission.command] : [])]))
    .digest('hex')
    .slice(0, 12)
  return {
    id,
    title,
    body: head.slice(0, 12).map((l) => (l.length > 400 ? l.slice(0, 399) + '…' : l)),
    options,
    cursor,
    more,
    cancel: /\besc to (cancel|exit|close|go back|dismiss)/i.test(footer),
    alt: enter && key ? { enter, key: key[1]!, label: key[2]! } : null,
    permission,
  }
}

/**
 * The text between the first and the last dashed rule of a dialog's lines (a permission dialog's
 * command or change): the "│ " margin off, a line the screen wrapped joined to the next with a
 * space, other lines kept as lines. [from] and [to] are the two rules' indexes. Null without two.
 */
function commandBlock(region: string[], width: number): { text: string; from: number; to: number } | null {
  const from = region.findIndex(dashed)
  const to = region.findLastIndex(dashed)
  if (from < 0 || to <= from) return null
  const raw = region.slice(from + 1, to).filter((l) => !dashed(l) && l.trim())
  if (!raw.length) return null
  const bar = raw.every((l) => /^\s*│/.test(l))
  const texts = raw.map((l) => (bar ? l.replace(/^\s*│ ?/, '') : l).trimEnd())
  const margin = Math.min(...texts.map((t) => /^\s*/.exec(t)![0].length))
  let text = ''
  texts.forEach((t, k) => {
    if (k === 0) text = t.slice(margin)
    else if (wraps(raw[k - 1]!, t, width)) text += ' ' + t.trim()
    else text += '\n' + t.slice(margin)
  })
  return { text: text.length > 4000 ? text.slice(0, 3999) + '…' : text, from, to }
}

