// What may be written in the server's debug log and in `hypernova debug bundle` (docs/SECURITY-MODEL.md,
// "The server's debug log"; owner, 2026-11-02). Everything goes through here at the write, so a call site
// can't let a secret or a conversation through by passing it along. Default-deny: a string that isn't
// shaped like an id, a number or a one-word value becomes its size. No state of its own but the live
// secrets it was told about; nothing here writes.
import { maskSecrets } from './normalize.ts'
import { maskText, secretName } from './mcp-view.ts'

export const MASK = '•••'
const LINE_MAX = 1000
const VALUE_MAX = 40
const FLAGS_MAX = 240
/** A field's name: a plain word, so a token or a sentence that lands there goes. */
const FIELD = /^[a-z][A-Za-z]{0,23}$/
/** Every event there is (debug-log.test.ts looks at the calls). Any other is its size, whatever it says. */
export const EVENTS = new Set(
  `on off extended resumed dropped daemon-start daemon-stop request error route open close in out refused dropped ok failed code-shown paired
   cert-issued new state removed received start exit codex-connected codex-disconnected run-begin step-start step-end run-end probe-start
   probe-end check download checksum-ok unpacked install-started uncaught unhandled-rejection`.split(/\s+/),
)

// ── Live secrets ─────────────────────────────────────────────────────────

/** Exact values to take out wherever they turn up: the admin token, the hook secret, a pairing code being shown. */
const live = new Set<string>()
export function knownSecret(v: string | null | undefined): void {
  if (!v || v.length < 8) return
  // The oldest goes first: pairing codes come and go, and each line looks through them all.
  if (live.size >= 32) live.delete(live.values().next().value!)
  live.add(v)
}

// ── Free text ────────────────────────────────────────────────────────────

const PEM = /-----BEGIN [A-Z0-9 ]{1,40}-----[\s\S]*?(?:-----END [A-Z0-9 ]{1,40}-----|$)/g
/** A header that's a credential whatever it holds, with an optional scheme before the value. */
const HEADER = /\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token)(["']?\s{0,8}[:=]\s{0,8})(?:(?:Bearer|Basic|Token|Bot|Digest|OAuth|ApiKey)\s{1,8})?("[^"]*"|'[^']*'|\S+)/gi
/** A value after a name that says it's a secret, however plain the value is ("PASSWORD=hunter"). A number isn't one ("tokens=12"). */
const NAMED = /\b([\w.-]{0,40}(?:token|secret|password|passwd|passphrase|api[-_]?key|access[-_]?key|private[-_]?key|client[-_]?key|credential|pairing[-_]?code)[\w.-]{0,40})(["']?\s{0,8}[:=]\s{0,8})("[^"]*"|'[^']*'|[^\s,;&]+)/gi
/** Hypernova's own fire tokens, and any app link or URL: only its scheme and host are kept (a ticket, a webhook's secret and a pairing code all live in the rest). */
const FIRE = /\bhn[a-z]_[A-Za-z0-9]{8,}/g
const URL_ANY = /\b([A-Za-z][A-Za-z0-9+.-]{1,20}:\/\/)(?:[^\s/?#'"<>@]*@)?([^\s/?#'"<>]*)[^\s'"<>)\]]*/g
/** A query on a bare route ("/pair?c=…", "/v1/terms/x/ws?ticket=…"). */
const QUERY = /(?<=[\w/-])\?[^\s'"<>]*=[^\s'"<>]*/g
/** An absolute or home path, not one right after "route=", which [routeOf] made safe. */
const PATH = /(?<!route=)(?<![\w.:/~-])(?:~\/|\/)[\w.@%+-]+(?:\/[\w.@%+-]*)*/g

/** A line with every secret-looking thing gone, and paths: what no line of a log or a bundle may hold. */
export function scrubText(text: string): string {
  let out = text.replace(PEM, '[pem]')
  for (const v of live) if (out.includes(v)) out = out.split(v).join(MASK)
  out = out.replace(FIRE, MASK)
  out = out.replace(URL_ANY, (_m, scheme: string, host: string) => `${scheme}${maskSecrets(host) === host ? host : MASK}/…`)
  out = out.replace(QUERY, `?${MASK}`)
  out = out.replace(HEADER, (_m, name: string, sep: string) => `${name}${sep}${MASK}`)
  out = out.replace(NAMED, (m, name: string, sep: string, value: string) => (/^["']?[0-9][0-9,._]*["']?$/.test(value) ? m : `${name}${sep}${MASK}`))
  return maskSecrets(maskText(out)).replace(PATH, '[path]')
}

// ── Fields ───────────────────────────────────────────────────────────────

/** A field's name that says its value is a secret. */
const SECRET_KEY = /token|secret|passw|pwd|cookie|authoriz|api.?key|private|credential|bearer|ticket|csr|pem|fingerprint|signature|pairing|^(?:code|link|cert|key|auth)$/i
/** A field's name that says its value is the owner's words or where he works: its size only, whatever it looks like. */
const CONTENT_KEY = /prompt|text|reply|answer|message|msg|body|content|summary|args|argv|input|output|command|cmd|transcript|title|^name$|Name$|label|cwd|path|dir$|folder|file|project|url|host$|env|header|stdout|stderr|said|plan|question|screen/i
/** An id: its first 8 characters are enough to find it in the everyday log, which cuts them the same. */
const ID_KEY = /^(?:device|session|sid|run|automation|term|job|step|id|fp|by)$/
const PLAIN = /^[\w.:+%@-]+$/
/** The fields that may hold a word of the daemon's own (a state, a reason, a program's name). In any other a string is only its size. */
const WORD_KEYS = new Set(['method', 'via', 'kind', 'type', 'reason', 'state', 'from', 'to', 'origin', 'hook', 'tool', 'err', 'errno', 'syscall', 'signal', 'ip', 'proto', 'bin', 'sub', 'mode', 'tag', 'version', 'outcome', 'read', 'until', 'platform', 'client', 'agent'])

export type Field = string | number | boolean | null | undefined | readonly unknown[] | object
export type Fields = Record<string, Field>

function fieldText(key: string, v: Field): string | null {
  if (v === null || v === undefined) return null
  if (typeof v === 'number') return Number.isFinite(v) ? String(Math.round(v * 100) / 100) : 'NaN'
  if (typeof v === 'boolean') return String(v)
  if (Buffer.isBuffer(v)) return `[${v.length} bytes]`
  if (Array.isArray(v)) return `[${v.length} items]`
  if (typeof v !== 'string') return '[object]'
  // Nothing to say: a command with no flags, a socket closed with no reason.
  if (v === '') return null
  if (key === 'route') return routeOf(v)
  if (key === 'flags') return v.length <= FLAGS_MAX && /^(?:--?[A-Za-z][\w-]*(?:,|$))*$/.test(v) ? v : `[${v.length} chars]`
  // An error's own words, only when they're V8's (below).
  if (key === 'sub') return v.length <= VALUE_MAX && /^[a-z-]+(?:,[a-z-]+)*$/.test(v) ? v : `[${v.length} chars]`
  if (key === 'says') return safeMessage(v).replace(/\s+/g, '_')
  if (SECRET_KEY.test(key) || secretName(key)) return MASK
  if (CONTENT_KEY.test(key)) return `[${v.length} chars]`
  if (ID_KEY.test(key)) return /^[\w-]+$/.test(v) ? v.slice(0, 8) : `[${v.length} chars]`
  // One of the daemon's own words: one word, and not a key: nothing long and full of digits.
  if (!WORD_KEYS.has(key) || v.length > VALUE_MAX || !PLAIN.test(v) || maskSecrets(v) !== v || (v.length >= 16 && /\d/.test(v) && /^[\w-]+$/.test(v))) return `[${v.length} chars]`
  return v
}

/** `<time> <tag> <event> k=v k=v`, scrubbed: the one way a line gets its shape. */
export function formatLine(at: number, tag: string, event: string, fields?: Fields): string {
  let line = `${new Date(at).toISOString()} ${tag} ${EVENTS.has(event) ? event : `[${event.length} chars]`}`
  if (fields) {
    for (const k of Object.keys(fields)) {
      if (!FIELD.test(k)) continue
      const t = fieldText(k, fields[k])
      if (t !== null) line += ` ${k}=${t}`
    }
  }
  return scrubText(line.length > LINE_MAX ? `${line.slice(0, LINE_MAX)}…` : line).replace(/[\r\n]+/g, ' ')
}

// ── Routes ───────────────────────────────────────────────────────────────

/** The route's own words (docs/PROTOCOL.md). Anything else in a path is an id, or something that must not be written. */
const ROUTE_WORDS = new Set(
  `v1 local hooks p qa pair health snapshot download android Hypernova.apk account add agents allow answer approval approvals attachments
   audio auto-continue automations available awake blocked btw cancel cert changes check claude code codex commands configure continue debug
   detail device devices diff effort enabled env events file files finish fire folders held history install integration interrupt jobs login
   logout mac marketplaces mcp me menu mode model models open paused permissions play plays plugins preview projects prompt provider providers
   prune raw reconnect refresh remove rename reset-choices respawn restart resume retry rewind run runs save search server servers sessions
   settings shot skills speech start state stats status stop switch terminal terms todos toggle token tools undo uninstall update usage voices
   ws statusline battery uninstall list`.split(/\s+/),
)
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * A path as a route: never its query, and of the rest only the route's own words and sessions' UUIDs, cut
 * to 8. So a hook's secret (`/hooks/<secret>`), a preview's token (`/p/<token>/…`), a file's name, an MCP
 * server's or a plugin's are all `:x`.
 */
export function routeOf(path: string): string {
  const bare = path.split(/[?#]/, 1)[0]!
  if (bare.length > 400) return `[${bare.length} chars]`
  const parts = bare.split('/').map((s) => (s === '' || ROUTE_WORDS.has(s) ? s : UUID.test(s) ? s.slice(0, 8) : ':x'))
  // Past a preview's token or a hook's secret the rest is theirs.
  const out: string[] = []
  for (const s of parts) {
    if (s === ':x' && out[out.length - 1] === ':x') continue
    out.push(s)
    if (out.length >= 10) break
  }
  return out.join('/') || '/'
}

// ── Commands ─────────────────────────────────────────────────────────────

/** Sub-commands worth a name. Any other word on a command line is left out: it may be a prompt, a job or a folder. */
const SUB_WORDS = new Set(['agents', 'attach', 'auth', 'status', 'plugin', 'mcp', 'marketplace', 'app-server', 'daemon', 'list', 'add', 'add-json', 'get', 'remove', 'install', 'uninstall', 'enable', 'disable', 'update', 'upgrade', 'configure', 'details', 'prune', 'start', 'version', 'stop', 'rm', 'respawn', 'resume', 'edit-skill-overrides'])

/** A command line's flags only: `--model,--resume`. Never a value, a `--flag=value`'s either, and nothing after `--`. */
export function flagsOf(args: readonly string[]): string {
  const out: string[] = []
  for (const a of args) {
    if (a === '--') break
    const m = /^(--?[A-Za-z][\w-]{0,40})(?:=.*)?$/s.exec(a)
    if (m && !out.includes(m[1]!)) out.push(m[1]!)
  }
  return out.join(',')
}
/** Its leading sub-commands, those on the list: `plugin,install`. */
export function subOf(args: readonly string[]): string {
  const out: string[] = []
  for (const a of args) {
    if (!SUB_WORDS.has(a)) {
      if (a.startsWith('-')) continue
      break
    }
    out.push(a)
    if (out.length >= 3) break
  }
  return out.join(',')
}
/** A program's own name, never where it is. */
export function binOf(file: string): string {
  return file.split('/').pop() ?? file
}

// ── Errors ───────────────────────────────────────────────────────────────

/**
 * Messages V8 words itself, with nothing of ours in them but a name. Any other message may quote what it
 * choked on (a JSON parse error quotes the text, a file error the path, a thrown Error whatever it was
 * given), so it's its size only.
 */
const ENGINE_WORDS = [
  /^Cannot (?:read|set) properties of (?:undefined|null)(?: \((?:reading|setting) '[\w$]{1,60}'\))?$/,
  /^[\w$.()[\] ]{1,80} is not (?:a function|iterable|defined|a constructor)$/,
  /^Assignment to constant variable\.$/,
  /^Maximum call stack size exceeded$/,
  /^Invalid (?:array|string) length$/,
  /^Cannot access '[\w$]{1,60}' before initialization$/,
  /^Cannot destructure property '[\w$]{1,60}' of '[\w$.]{1,60}' as it is (?:undefined|null)\.$/,
]
export function safeMessage(message: string): string {
  return ENGINE_WORDS.some((re) => re.test(message)) ? message : `[${message.length} chars]`
}

const FRAME = /^\s*at\s+(?:(.*?)\s+\()?((?:async )?[^()\s]+?)(:\d+:\d+)?\)?$/
/** A stack's frame as the daemon's own file and line; a file outside it is `[path]`. Null for a line that isn't a frame. */
export function frameOf(line: string): string | null {
  const m = FRAME.exec(line)
  if (!m) return null
  const fn = m[1] && /^[\w$.<>\s[\]]{1,80}$/.test(m[1]) ? `${m[1]} ` : ''
  const file = m[2]!
  const own = /(?:^|\/)daemon\/((?:src|test|node_modules)\/.*)$/.exec(file.replace(/^file:\/\//, ''))?.[1]
  const dep = /\/(node_modules\/.*)$/.exec(file)?.[1]
  const where = file.startsWith('node:') ? file : (own ?? dep ?? '[path]')
  return `    at ${fn}${where}${m[3] ?? ''}`
}

/** What an error may say: `name=… code=… syscall=…` for the line, and its stack's frames. */
export function errorParts(e: unknown): { fields: Fields; frames: string[] } {
  if (!(e instanceof Error)) return { fields: { type: typeof e }, frames: [] }
  const x = e as NodeJS.ErrnoException
  const frames = (e.stack ?? '').split('\n').flatMap((l) => frameOf(l) ?? []).slice(0, 30)
  // "ETIMEDOUT", "ERR_STREAM_DESTROYED", "bad_value": a code is never free text.
  const code = typeof x.code === 'string' && /^[A-Za-z][\w-]{0,40}$/.test(x.code) ? x.code : undefined
  return { fields: { err: /^[A-Za-z]\w{0,40}$/.test(e.name) ? e.name : 'Error', errno: code, syscall: x.syscall, says: e.message }, frames }
}

// ── The bundle ───────────────────────────────────────────────────────────

const DEBUG_LINE = /^\d{4}-\d\d-\d\dT[\d:.]+Z [a-z]+ (?:[a-z][a-z-]*|\[\d+ chars\])(?: [a-z][A-Za-z]*=(?:\[[^\]]*\]|\S+))*$/
/**
 * A line of debug.log, for the bundle. It was scrubbed when written; it's scrubbed again, and one not in
 * the writer's own shape (another version's, or a file changed by hand) is left out.
 */
export function bundleDebugLine(line: string): string {
  const s = scrubText(line)
  if (DEBUG_LINE.test(s)) return s
  const frame = frameOf(line)
  return frame ?? `[a line left out, ${line.length} chars]`
}

const HEAD = /^(\d{4}-\d\d-\d\dT[\d:.]+Z )?(.*)$/s
/** A number, a time or a size, a version, an IP and port. */
const NUMBERISH = /^v?\d[\d.,:_%]*(?:KB|MB|GB|ms|s|m|h)?$/
const HEX8 = /^[0-9a-f]{8}$/
const CORE = /^([(["'“‘[{]*)(.*?)([)\]"'”’}.,;:!?…]*)$/su

/**
 * A line of the everyday log, for the bundle. daemon.log was never written with sharing in mind: it names
 * phones, folders, attachments, what Claude Code said on stderr. So only the daemon's own words stay, the
 * ones in its log() lines ([words]), and numbers, short ids and masks: the rest is `…`. A prompt quoted in
 * a line can't get through, whatever it says, and a line this version doesn't know reads as gaps.
 */
export function bundleEverydayLine(line: string, words: ReadonlySet<string>): string {
  const frame = frameOf(line)
  if (frame) return frame
  const [, time = '', rest = ''] = HEAD.exec(scrubText(line)) ?? []
  const out: string[] = []
  for (const token of rest.split(/\s+/).filter(Boolean)) {
    const [, open = '', core = '', close = ''] = CORE.exec(token) ?? []
    let kept: string | null = null
    if (words.has(core.toLowerCase()) || NUMBERISH.test(core) || HEX8.test(core) || core === MASK || core === 'path' || /^\d+$/.test(core)) kept = `${open}${core}${close}`
    else if (UUID.test(core)) kept = `${open}${core.slice(0, 8)}${close}`
    if (kept === null) {
      if (out[out.length - 1] !== '…') out.push('…')
    } else out.push(kept)
  }
  return `${time}${out.join(' ')}`
}

/** The words of the daemon's own log() lines, out of its source: what [bundleEverydayLine] lets stay. */
export function logWords(sources: Iterable<string>): Set<string> {
  const words = new Set<string>(['linux', 'darwin'])
  for (const src of sources) {
    for (const m of src.matchAll(/(?<![\w.])(?:log|say|note|logLine)\((.*)$/gm)) {
      // Only what's quoted in the call: not a variable's name, not what a ${…} holds.
      for (const q of m[1]!.replace(/\$\{[^}]*\}/g, ' ').matchAll(/'((?:[^'\\]|\\.)*)'|`((?:[^`\\]|\\.)*)`|"((?:[^"\\]|\\.)*)"/g)) {
        for (const w of (q[1] ?? q[2] ?? q[3] ?? '').matchAll(/[A-Za-z][A-Za-z’'-]*/g)) words.add(w[0].toLowerCase())
      }
    }
  }
  return words
}
