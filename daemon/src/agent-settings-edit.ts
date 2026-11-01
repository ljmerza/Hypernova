// The one place the daemon edits a settings file: one key at a time, from a fixed list, for a paired
// phone (docs/PROTOCOL.md, "Agent settings"; docs/DECISIONS.md, "The agents' settings, edited"; the
// threat and the limits: docs/SECURITY-MODEL.md). Everything else about these files stays read-only:
// always.ts still leaves the rules to Claude Code.
//
// The server decides. EDITABLE is what can be set, and each key's values are the only ones accepted,
// so a bypass mode, `approval_policy = "never"` and `danger-full-access` can't get here, whatever a
// client sends. A file that already holds one shows it and can move off it, and Undo won't put it back.
//
// A write changes the bytes of one value and nothing else. JSON and TOML alike are edited in place by
// position, never parsed and written back, so hooks, env, comments and order can't change; each edit
// is then parsed again and compared with the old file, and refused if anything else moved.

import { randomBytes, createHash } from 'node:crypto'
import { accessSync, closeSync, constants, existsSync, fchmodSync, fchownSync, fstatSync, fsyncSync, lstatSync, mkdirSync, openSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync, type Stats } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { agentSettings, CLAUDE_SHOWN, CODEX_SHOWN, codexHome, modelIdFits, shown, type AgentSettingsReply, type ShownValue } from './agent-settings.ts'
import { OTHER, scanToml, type TomlStatement, type TomlValue } from './toml-reader.ts'
import { CLAUDE_DIR, log, SUPPORT } from './util.ts'

export type Agent = 'claude' | 'codex'
export type Value = string | boolean

/** What can be set. [of]: the accepted values, tightest first; 'models': Codex's own list, or nothing at all without one. */
type Rule = { agent: Agent; key: string; of: readonly Value[] | 'models' }

export const EDITABLE: readonly Rule[] = [
  // `manual` is `default` under another name; `bypassPermissions` never.
  { agent: 'claude', key: 'permissions.defaultMode', of: ['plan', 'default', 'dontAsk', 'acceptEdits', 'auto'] },
  { agent: 'claude', key: 'showThinkingSummaries', of: [true, false] },
  // Not `rc`: the schema takes it, the docs don't name it, and `claude install rc` saves it as `stable`.
  { agent: 'claude', key: 'autoUpdatesChannel', of: ['latest', 'stable'] },
  { agent: 'codex', key: 'model', of: 'models' },
  { agent: 'codex', key: 'model_reasoning_effort', of: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'] },
  // `never` is too open. Codex 0.164.0 fails to start a thread on `untrusted` ("no longer supported; remove this
  // setting") and reads `on-failure` as `on-request`, so this is what's left: the way off any of them.
  { agent: 'codex', key: 'approval_policy', of: ['on-request'] },
  { agent: 'codex', key: 'sandbox_mode', of: ['read-only', 'workspace-write'] },
  { agent: 'codex', key: 'web_search', of: ['disabled', 'cached', 'indexed', 'live'] },
]

/** Permission keys' values, from least to most allowed. Claude Code's modes are in its own order (2.1.316's table: plan 0; default, dontAsk 1; acceptEdits 2; auto 3; bypass 4). */
const ORDER: Record<string, Record<string, number>> = {
  'permissions.defaultMode': { plan: 0, default: 1, manual: 1, dontAsk: 1, acceptEdits: 2, auto: 3, bypassPermissions: 4 },
  approval_policy: { untrusted: 0, 'on-request': 1, 'on-failure': 1, never: 2 },
  sandbox_mode: { 'read-only': 0, 'workspace-write': 1, 'danger-full-access': 2 },
}
/** What a key not in the file means: Claude Code's schema, and what a scratch Codex 0.164.0 started a thread on. */
const UNSET_MEANS: Record<string, string> = { 'permissions.defaultMode': 'default', approval_policy: 'on-request', sandbox_mode: 'read-only' }

/**
 * Whether going from [from] to [to] allows more without asking: the phone's cue for a confirm and the
 * owner's fingerprint, and the server's flag in its reply and its log. Pure. Anything it can't place
 * counts as loosening. Only the permission keys ever do.
 */
export function loosens(key: string, from: ShownValue, to: Value | null): boolean {
  if (!Object.hasOwn(ORDER, key)) return false
  const order = ORDER[key]!
  const rankOf = (v: Value | null | undefined) => (typeof v === 'string' && Object.hasOwn(order, v) ? order[v]! : null)
  const target = to === null ? rankOf(UNSET_MEANS[key]) : rankOf(to)
  const was = from.state === 'set' ? rankOf(from.value) : from.state === 'unset' ? rankOf(UNSET_MEANS[key]) : null
  if (target === null || was === null) return true
  return target > was
}

/** A refusal: its words are the phone's to show, and never quote a file, a key or a value it was sent. */
export class EditRefused extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}
const changed = () => new EditRefused(409, 'file_changed', 'That file changed on the server since this was read. Nothing was written.')
const shape = () => new EditRefused(409, 'unsupported_shape', 'That setting is written in a way this can’t safely edit. Change it on the server.')
const malformed = () => new EditRefused(409, 'file_malformed', 'That file isn’t valid, so nothing was written. Fix it on the server.')
const failed = () => new EditRefused(500, 'write_failed', 'That couldn’t be written. Nothing changed.')

// ── Reading, without following a link ────────────────────────────────────

/** A settings file past this isn't read (agent-settings.ts). */
const FILE_MAX = 4 * 1024 * 1024
const FILES: Record<Agent, string> = { claude: 'settings.json', codex: 'config.toml' }
const fileOf = (agent: Agent, dirs: Dirs) => join(agent === 'claude' ? (dirs.claude ?? CLAUDE_DIR) : (dirs.codex ?? codexHome()), FILES[agent])
export type Dirs = { claude?: string; codex?: string; claudeConfig?: string }

const sha256 = (b: Buffer | string) => createHash('sha256').update(b).digest('hex')

type Current = { bytes: Buffer; hash: string; stat: Stats } | null

/**
 * The file's bytes, null when it isn't there. A link is refused, not followed: a write through it would
 * land wherever it points (a dotfiles repository, say), which nobody chose from the phone.
 */
function readCurrent(file: string): Current {
  let first: Stats
  try {
    first = lstatSync(file)
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code
    if (code === 'ENOENT') return null
    if (code === 'ENOTDIR') throw new EditRefused(409, 'no_folder', 'The agent’s settings folder isn’t there on the server.')
    throw new EditRefused(409, 'file_unreadable', 'That file can’t be read on the server. Nothing was written.')
  }
  if (first.isSymbolicLink()) throw new EditRefused(409, 'file_is_link', 'That file is a link on the server, so it isn’t changed from here. Change it there.')
  if (!first.isFile() || first.size > FILE_MAX) throw new EditRefused(409, 'file_unreadable', 'That file can’t be read on the server. Nothing was written.')
  let fd: number | undefined
  try {
    // O_NOFOLLOW: still not a link. O_NONBLOCK: a pipe swapped in can't hang the daemon.
    fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
    const stat = fstatSync(fd)
    if (!stat.isFile() || stat.size > FILE_MAX) throw new Error('not a file')
    const bytes = readFileSync(fd)
    return { bytes, hash: sha256(bytes), stat }
  } catch {
    throw new EditRefused(409, 'file_unreadable', 'That file can’t be read on the server. Nothing was written.')
  } finally {
    if (fd !== undefined) closeSync(fd)
  }
}

/** The bytes as text, if that's what they are: bytes that aren't UTF-8 wouldn't come back as they were. */
function textOf(bytes: Buffer): string | null {
  const text = bytes.toString('utf8')
  return Buffer.from(text, 'utf8').equals(bytes) ? text : null
}

const isTable = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

function parseJson(text: string): Record<string, unknown> | null {
  try {
    const doc = JSON.parse(text)
    return isTable(doc) ? doc : null
  } catch {
    return null
  }
}
function parseToml(text: string): TomlStatement[] | null {
  const out: TomlStatement[] = []
  try {
    scanToml(text, () => {}, (s) => out.push(s))
    return out
  } catch {
    return null
  }
}

/** A key's value in the file, by the same rules as what the phone is shown: a word that isn't known never reaches a log or a reply. */
function currentValue(agent: Agent, text: string, key: string): ShownValue {
  if (agent === 'claude') {
    const rule = CLAUDE_SHOWN.find((r) => r.key === key)!
    let at: unknown = parseJson(text) ?? {}
    let found = true
    for (const part of key.split('.')) {
      found = isTable(at) && Object.hasOwn(at, part)
      at = found ? (at as Record<string, unknown>)[part] : undefined
      if (!found) break
    }
    return shown(rule, found, at)
  }
  const rule = CODEX_SHOWN.find((r) => r.key === key)!
  let found = false
  let value: TomlValue | undefined
  for (const s of parseToml(text) ?? []) {
    if (s.kind === 'table' ? s.path[0] === key : (s.table[0] ?? s.key[0]) === key) found = true
    if (s.kind === 'key' && !s.table.length && s.key.length === 1 && s.key[0] === key) value = s.value
  }
  return shown(rule, found, value === OTHER ? undefined : value)
}

// ── The edits: the bytes of one value, and nothing else ──────────────────

const JSON_SPACE = ' \t\n\r'
const skipSpace = (t: string, i: number) => {
  while (i < t.length && JSON_SPACE.includes(t[i]!)) i++
  return i
}
function stringEnd(t: string, i: number): number {
  i++
  for (;;) {
    if (t[i] === '\\') i += 2
    else if (t[i] === '"') return i + 1
    else i++
  }
}
function valueEnd(t: string, i: number): number {
  if (t[i] === '"') return stringEnd(t, i)
  if (t[i] === '{' || t[i] === '[') {
    let depth = 0
    for (;;) {
      const c = t[i]
      if (c === '"') {
        i = stringEnd(t, i)
        continue
      }
      if (c === '{' || c === '[') depth++
      else if (c === '}' || c === ']') {
        depth--
        if (depth === 0) return i + 1
      }
      i++
    }
  }
  while (i < t.length && !JSON_SPACE.includes(t[i]!) && t[i] !== ',' && t[i] !== '}' && t[i] !== ']') i++
  return i
}

type Member = { name: string; keyFrom: number; keyTo: number; valueFrom: number; valueTo: number }
/** The members of the object opening at [open], in text the caller has already had JSON.parse accept. */
function membersOf(t: string, open: number): { members: Member[]; close: number } {
  const members: Member[] = []
  let i = skipSpace(t, open + 1)
  if (t[i] === '}') return { members, close: i }
  for (;;) {
    const keyFrom = i
    const keyTo = stringEnd(t, i)
    const valueFrom = skipSpace(t, skipSpace(t, keyTo) + 1)
    const to = valueEnd(t, valueFrom)
    members.push({ name: JSON.parse(t.slice(keyFrom, keyTo)) as string, keyFrom, keyTo, valueFrom, valueTo: to })
    i = skipSpace(t, to)
    if (t[i] === '}') return { members, close: i }
    i = skipSpace(t, i + 1)
  }
}
/** The space before a member's key: what the next one on that level is given too. */
function leadOf(t: string, keyFrom: number): string {
  let i = keyFrom
  while (i > 0 && JSON_SPACE.includes(t[i - 1]!)) i--
  return t.slice(i, keyFrom)
}
const indentOf = (lead: string) => lead.slice(lead.lastIndexOf('\n') + 1)
const splice = (t: string, from: number, to: number, text: string) => t.slice(0, from) + text + t.slice(to)

/** [t] with [path] (one or two keys) set to [value]. A duplicate key's last one is the one JSON.parse takes, so that's the one. */
export function jsonEdit(t: string, path: readonly string[], value: Value): string {
  const v = JSON.stringify(value)
  const before = parseJson(t)
  if (!before) throw malformed()

  const eol = t.includes('\r\n') ? '\r\n' : '\n'
  /** Adds [name] to the object at [open]: [lead] and [colon] from its last member, or as the level above writes them. */
  const add = (open: number, names: readonly string[], aboveLead: string, aboveColon: string): string => {
    const { members, close } = membersOf(t, open)
    const last = members.at(-1)
    const lead = last ? leadOf(t, last.keyFrom) : aboveLead.includes('\n') ? aboveLead + (indentOf(aboveLead) || '  ') : ''
    const colon = last ? t.slice(last.keyTo, last.valueFrom) : aboveColon
    // A level that has to be made is laid out as the one it goes in.
    const unit = indentOf(lead) || '  '
    const inner = lead.includes('\n') ? lead + unit : ''
    const shut = lead.includes('\n') ? lead : ''
    const body = names.length === 1 ? v : `{${inner}${JSON.stringify(names[1])}${colon}${v}${shut}}`
    const member = `${JSON.stringify(names[0])}${colon}${body}`
    if (last) return splice(t, last.valueTo, last.valueTo, `,${lead}${member}`)
    // An empty object: only the space inside it changes.
    return splice(t, open + 1, close, `${lead}${member}${aboveLead.includes('\n') ? aboveLead : ''}`)
  }

  const root = skipSpace(t, 0)
  const top = membersOf(t, root).members
  const first = top.filter((m) => m.name === path[0]).at(-1)
  let next: string
  if (path.length === 1) {
    if (!first) next = add(root, path, eol, ': ')
    else {
      if (t[first.valueFrom] === '{' || t[first.valueFrom] === '[') throw shape()
      next = splice(t, first.valueFrom, first.valueTo, v)
    }
  } else if (!first) {
    next = add(root, path, eol, ': ')
  } else {
    if (t[first.valueFrom] !== '{') throw shape()
    const m = membersOf(t, first.valueFrom).members.filter((x) => x.name === path[1]).at(-1)
    if (!m) next = add(first.valueFrom, [path[1]!], leadOf(t, first.keyFrom), t.slice(first.keyTo, first.valueFrom))
    else {
      if (t[m.valueFrom] === '{' || t[m.valueFrom] === '[') throw shape()
      next = splice(t, m.valueFrom, m.valueTo, v)
    }
  }

  // What came out is what was in, with the one key changed. Otherwise it isn't written.
  const want = structuredClone(before)
  if (path.length === 1) want[path[0]!] = value
  else want[path[0]!] = { ...(want[path[0]!] as Record<string, unknown> | undefined), [path[1]!]: value }
  if (!isDeepStrictEqual(parseJson(next), want)) throw shape()
  return next
}

/**
 * [t] with the top-level [key] set to [value], by [toml-reader.ts]'s positions: that value's bytes, or
 * a new line at the end of the top-level section, before the first table. Refused (`unsupported_shape`)
 * unless it's a plain string or boolean on one line, directly at the top.
 */
export function tomlEdit(t: string, key: string, value: string): string {
  const statements = parseToml(t)
  if (!statements) throw malformed()
  const quoted = JSON.stringify(value)
  let target: Extract<TomlStatement, { kind: 'key' }> | undefined
  let topEnd = t.charCodeAt(0) === 0xfeff ? 1 : 0
  for (const s of statements) {
    if (s.kind === 'table') {
      if (s.path[0] === key) throw shape()
      continue
    }
    if (s.table.length) {
      continue
    }
    topEnd = s.lineEnd
    if (s.key[0] !== key) continue
    if (s.key.length !== 1) throw shape()
    target = s
  }
  let next: string
  if (target) {
    if (target.value === OTHER || t.slice(target.valueFrom, target.valueTo).includes('\n')) throw shape()
    next = splice(t, target.valueFrom, target.valueTo, quoted)
  } else {
    const eol = t.includes('\r\n') ? '\r\n' : '\n'
    const open = topEnd > 0 && t[topEnd - 1] !== '\n' && t.charCodeAt(topEnd - 1) !== 0xfeff
    next = splice(t, topEnd, topEnd, `${open ? eol : ''}${key} = ${quoted}${eol}`)
  }

  // Every other statement is as it was, in the same order.
  const entries = (text: string) => {
    const out: [string[], TomlValue | undefined][] = []
    scanToml(text, (path, v) => out.push([path, v]))
    return out
  }
  const isTarget = ([path]: [string[], unknown]) => path.length === 1 && path[0] === key
  let after: ReturnType<typeof entries>
  try {
    after = entries(next)
  } catch {
    throw shape()
  }
  if (!isDeepStrictEqual(after.filter((e) => !isTarget(e)), entries(t).filter((e) => !isTarget(e)))) throw shape()
  if (!after.some((e) => isTarget(e) && e[1] === value)) throw shape()
  return next
}

/** The file's next text with [key] at [value]; [text] null: a file to create, with that one key. */
function edited(agent: Agent, text: string | null, key: string, value: Value): string {
  if (agent === 'claude') return jsonEdit(text ?? '{}\n', key.split('.'), value)
  return tomlEdit(text ?? '', key, String(value))
}

// ── Backups and the write ────────────────────────────────────────────────

/** Backups kept per agent. Each is a whole settings file, so not many, and only here: never next to it. */
export const BACKUPS_KEPT = 20
export const backupDir = () => join(SUPPORT, 'settings-backups')
const lastFile = (agent: Agent) => join(backupDir(), `last-${agent}.json`)

/** A backup of [bytes], mode 0600, in a folder of mode 0700. Its name: `<agent>-<timestamp>`, the next free one. */
function writeBackup(agent: Agent, bytes: Buffer): string {
  mkdirSync(backupDir(), { recursive: true, mode: 0o700 })
  for (let at = Date.now(); ; at++) {
    const name = `${agent}-${at}`
    try {
      writeFileSync(join(backupDir(), name), bytes, { mode: 0o600, flag: 'wx' })
      return name
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e
    }
  }
}

function pruneBackups(agent: Agent): void {
  const mine = new RegExp(`^${agent}-(\\d+)$`)
  const all = readdirSync(backupDir())
    .map((n) => ({ n, at: Number(mine.exec(n)?.[1] ?? NaN) }))
    .filter((b) => !Number.isNaN(b.at))
    .sort((a, b) => b.at - a.at)
  for (const b of all.slice(BACKUPS_KEPT)) rmSync(join(backupDir(), b.n), { force: true })
}

/**
 * [bytes] into [file] all at once: a temporary file in the same folder, its data on disk, then a
 * rename, so a reader sees the old file or the new one. It takes the old file's mode and owner; a new
 * file is 0600. [lastLook] runs just before the rename and may refuse.
 */
function writeAtomic(file: string, bytes: Buffer, like: Stats | null, lastLook: () => void): void {
  const tmp = join(dirname(file), `.${basename(file)}.hn-${randomBytes(6).toString('hex')}`)
  let fd: number | undefined
  try {
    fd = openSync(tmp, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600)
    writeFileSync(fd, bytes)
    if (like) {
      if (like.uid !== process.getuid?.() || like.gid !== process.getgid?.()) fchownSync(fd, like.uid, like.gid)
      fchmodSync(fd, like.mode & 0o7777)
    }
    fsyncSync(fd)
    closeSync(fd)
    fd = undefined
    lastLook()
    renameSync(tmp, file)
    syncFolder(dirname(file))
  } catch (e) {
    if (fd !== undefined) closeSync(fd)
    rmSync(tmp, { force: true })
    throw e
  }
}
function syncFolder(dir: string): void {
  let fd: number | undefined
  try {
    fd = openSync(dir, constants.O_RDONLY)
    fsyncSync(fd)
  } catch {
    // The rename stands; some filesystems refuse this.
  } finally {
    if (fd !== undefined) closeSync(fd)
  }
}

/** A value's words in the log: only what the phone could be shown. */
const inLog = (v: ShownValue) => (v.state === 'set' ? String(v.value) : v.state === 'unset' ? '(not set)' : '(unrecognised)')

/** What the last change wrote, for Undo. Its values have been checked, as the phone's are. */
type Last = { wrote: string; backup: string | null; key: string; before: ShownValue; after: Value }

function readLast(agent: Agent): Last | null {
  try {
    const l = JSON.parse(readFileSync(lastFile(agent), 'utf8'))
    return isTable(l) && typeof l.wrote === 'string' && typeof l.key === 'string' && (l.backup === null || typeof l.backup === 'string') && isTable(l.before) ? (l as unknown as Last) : null
  } catch {
    return null
  }
}
function saveLast(agent: Agent, last: Last): void {
  const file = lastFile(agent)
  const tmp = `${file}.${randomBytes(6).toString('hex')}`
  writeFileSync(tmp, JSON.stringify(last), { mode: 0o600 })
  renameSync(tmp, file)
}

/**
 * Whether Undo may put [before] back. Only what could be set here (or nothing at all): a bypass mode
 * this change moved off isn't restored from the phone, nor a word this server doesn't know, which
 * could be one. Both stay on the server's backup to restore by hand.
 */
function restorable(rule: Rule, before: ShownValue): boolean {
  if (before.state === 'unset') return true
  if (before.state !== 'set') return false
  // `default` by its other name.
  if (rule.key === 'permissions.defaultMode' && before.value === 'manual') return true
  return rule.of === 'models' ? typeof before.value === 'string' && modelIdFits(before.value) : rule.of.includes(before.value)
}

export type UndoOffer = { key: string; to: Value | null; loosens: boolean }

function undoOffer(agent: Agent, hash: string | null): UndoOffer | null {
  if (hash === null) return null
  const last = readLast(agent)
  if (!last || last.wrote !== hash) return null
  const rule = EDITABLE.find((r) => r.agent === agent && r.key === last.key)
  if (!rule || !restorable(rule, last.before)) return null
  const to = last.before.state === 'set' ? last.before.value : null
  return { key: last.key, to, loosens: loosens(last.key, { key: last.key, state: 'set', value: last.after }, to) }
}

// ── The routes ───────────────────────────────────────────────────────────

/** One of Codex's models, as the daemon knows them (`GET /v1/codex/models`). Null: no list right now. */
export type ModelList = { value: string; name: string }[] | null

/** A value the phone may pick for a key, and whether picking it now loosens a permission. */
export type Choice = { value: Value; loosens: boolean; label?: string }
export type Editable = { key: string; choices: Choice[] }
/** Why a file's keys can't be edited though it reads fine. */
export type WhyNot = 'link' | 'read_only' | 'no_folder' | 'unreadable' | null
export type EditFields = { hash: string | null; editable: Editable[]; undo: UndoOffer | null; whyNot: WhyNot }

function fieldsOf(agent: Agent, dirs: Dirs, models: ModelList): EditFields {
  const file = fileOf(agent, dirs)
  const none = (hash: string | null, whyNot: WhyNot): EditFields => ({ hash, editable: [], undo: null, whyNot })
  let cur: Current
  try {
    cur = readCurrent(file)
  } catch (e) {
    const code = e instanceof EditRefused ? e.code : ''
    return none(null, code === 'file_is_link' ? 'link' : code === 'no_folder' ? 'no_folder' : 'unreadable')
  }
  if (!cur && !existsSync(dirname(file))) return none(null, 'no_folder')
  const hash = cur?.hash ?? null
  const text = cur ? textOf(cur.bytes) : null
  if (cur && (text === null || !(agent === 'claude' ? parseJson(text) : parseToml(text)))) return none(hash, null)
  try {
    accessSync(cur ? file : dirname(file), constants.W_OK)
    if (cur) accessSync(dirname(file), constants.W_OK)
  } catch {
    return none(hash, 'read_only')
  }
  const editable: Editable[] = []
  for (const rule of EDITABLE) {
    if (rule.agent !== agent) continue
    const before = text === null ? ({ key: rule.key, state: 'unset' } as ShownValue) : currentValue(agent, text, rule.key)
    const list: { value: Value; label?: string }[] = rule.of === 'models' ? (models ?? []).filter((m) => modelIdFits(m.value)).map((m) => ({ value: m.value, label: m.name })) : rule.of.map((value) => ({ value }))
    // No list, or a key in a shape that can't be edited in place: it stays as shown.
    if (!list.length) continue
    try {
      edited(agent, text, rule.key, list[0]!.value)
    } catch {
      continue
    }
    editable.push({ key: rule.key, choices: list.map((c) => ({ ...c, loosens: loosens(rule.key, before, c.value) })) })
  }
  return { hash, editable, undo: undoOffer(agent, hash), whyNot: null }
}

export type EditableReply = { scope: 'user'; claude: AgentSettingsReply['claude'] & EditFields; codex: AgentSettingsReply['codex'] & EditFields }

/**
 * GET /v1/agents/settings: what's shown, with what each file can do. The hash is read first: if the
 * file changes between the two, the phone's `expected` is older than what it saw and gets a 409,
 * never a write over something it didn't see.
 */
export async function settingsReply(models: ModelList, dirs: Dirs = {}): Promise<EditableReply> {
  const claude = fieldsOf('claude', dirs, models)
  const codex = fieldsOf('codex', dirs, models)
  const view = await agentSettings(dirs)
  return { ...view, claude: { ...view.claude, ...claude }, codex: { ...view.codex, ...codex } }
}

export type Change = { ok: true; agent: Agent; key: string; value: Value; changed: boolean; hash: string; loosened: boolean; undo: UndoOffer | null }

/** The checks that need no file: anything off the list stops here. */
function checked(body: unknown): { rule: Rule; agent: Agent; key: string; value: Value; expected: string | null } {
  if (!isTable(body)) throw new EditRefused(400, 'bad_request', 'Send an agent, a key, a value and what you expect the file to be.')
  const { agent, key, value, expected } = body
  if (agent !== 'claude' && agent !== 'codex') throw new EditRefused(400, 'bad_agent', 'That isn’t an agent with settings here.')
  const rule = typeof key === 'string' ? EDITABLE.find((r) => r.agent === agent && r.key === key) : undefined
  if (!rule) throw new EditRefused(403, 'key_not_editable', 'That setting can’t be changed from a phone.')
  if (typeof value !== 'string' && typeof value !== 'boolean') throw new EditRefused(403, 'value_not_allowed', 'That value can’t be chosen from a phone.')
  if (rule.of !== 'models' && !rule.of.includes(value)) throw new EditRefused(403, 'value_not_allowed', 'That value can’t be chosen from a phone.')
  if (expected !== null && typeof expected !== 'string') throw new EditRefused(400, 'bad_expected', 'Say which version of the file this is for.')
  return { rule, agent, key: rule.key, value, expected }
}

/**
 * PUT /v1/agents/settings. Everything on the file happens in one synchronous stretch, so two phones
 * can't interleave here; another program can only be caught by [hash] looked at again last thing.
 * [testHooks]: a test's way into that last moment.
 */
export async function changeSetting(body: unknown, models: () => Promise<ModelList>, dirs: Dirs = {}, testHooks: { beforeRename?: () => void } = {}): Promise<Change> {
  const { rule, agent, key, value, expected } = checked(body)
  if (rule.of === 'models') {
    const list = await models().catch(() => null)
    if (!list) throw new EditRefused(409, 'models_unavailable', 'Codex’s models aren’t known right now, so this can’t be changed.')
    if (typeof value !== 'string' || !modelIdFits(value) || !list.some((m) => m.value === value)) throw new EditRefused(403, 'value_not_allowed', 'That value can’t be chosen from a phone.')
  }

  const file = fileOf(agent, dirs)
  const cur = readCurrent(file)
  if ((cur?.hash ?? null) !== expected) throw changed()
  if (!cur && !existsSync(dirname(file))) throw new EditRefused(409, 'no_folder', 'The agent’s settings folder isn’t there on the server.')
  const text = cur ? textOf(cur.bytes) : null
  if (cur && text === null) throw shape()
  if (text !== null && !(agent === 'claude' ? parseJson(text) : parseToml(text))) throw malformed()
  // A rename would replace a read-only file without a word: whoever made it so meant it.
  if (cur) {
    try {
      accessSync(file, constants.W_OK)
    } catch {
      throw new EditRefused(409, 'file_read_only', 'That file is read-only on the server. Nothing was written.')
    }
  }

  const before: ShownValue = text === null ? { key, state: 'unset' } : currentValue(agent, text, key)
  const loosened = loosens(key, before, value)
  if (before.state === 'set' && before.value === value && cur) {
    return { ok: true, agent, key, value, changed: false, hash: cur.hash, loosened: false, undo: undoOffer(agent, cur.hash) }
  }
  const next = Buffer.from(edited(agent, text, key, value), 'utf8')
  const wrote = sha256(next)

  let backup = null as string | null
  try {
    writeAtomic(file, next, cur?.stat ?? null, () => {
      if (cur) backup = writeBackup(agent, cur.bytes)
      testHooks.beforeRename?.()
      // Last thing before the rename: anyone else's write since the read stands, and this one doesn't happen.
      if ((readCurrent(file)?.hash ?? null) !== expected) {
        if (backup) rmSync(join(backupDir(), backup), { force: true })
        backup = null
        throw changed()
      }
    })
  } catch (e) {
    if (backup) rmSync(join(backupDir(), backup), { force: true })
    if (e instanceof EditRefused) throw e
    log('agent settings: write failed', agent, key, (e as NodeJS.ErrnoException).code ?? 'error')
    throw failed()
  }
  // The file is written, whatever comes next: a failure here only costs the Undo.
  try {
    mkdirSync(backupDir(), { recursive: true, mode: 0o700 })
    saveLast(agent, { wrote, backup, key, before, after: value })
    pruneBackups(agent)
  } catch (e) {
    log('agent settings: backup bookkeeping failed', agent, (e as NodeJS.ErrnoException).code ?? 'error')
  }
  log(`agent settings: ${agent} ${key} ${inLog(before)} -> ${String(value)}${loosened ? ' (loosens)' : ''}`)
  return { ok: true, agent, key, value, changed: true, hash: wrote, loosened, undo: undoOffer(agent, wrote) }
}

export type Undone = { ok: true; agent: Agent; key: string; hash: string | null; loosened: boolean }

/** POST /v1/agents/settings/undo: the last change, if the file is still what it wrote. One step. */
export function undoChange(body: unknown, dirs: Dirs = {}): Undone {
  if (!isTable(body)) throw new EditRefused(400, 'bad_request', 'Send an agent and what you expect the file to be.')
  const { agent, expected } = body
  if (agent !== 'claude' && agent !== 'codex') throw new EditRefused(400, 'bad_agent', 'That isn’t an agent with settings here.')
  if (typeof expected !== 'string') throw new EditRefused(400, 'bad_expected', 'Say which version of the file this is for.')
  const last = readLast(agent)
  if (!last) throw new EditRefused(409, 'nothing_to_undo', 'There’s no change here to undo.')
  const file = fileOf(agent, dirs)
  const cur = readCurrent(file)
  if (!cur || cur.hash !== expected || cur.hash !== last.wrote) throw changed()
  const rule = EDITABLE.find((r) => r.agent === agent && r.key === last.key)
  if (!rule || !restorable(rule, last.before)) throw new EditRefused(409, 'undo_refused', 'What was there before can’t be set from a phone. Its backup is on the server.')
  try {
    accessSync(file, constants.W_OK)
  } catch {
    throw new EditRefused(409, 'file_read_only', 'That file is read-only on the server. Nothing was written.')
  }

  const to = last.before.state === 'set' ? last.before.value : null
  const loosened = loosens(last.key, { key: last.key, state: 'set', value: last.after }, to)
  let hash: string | null = null
  try {
    if (last.backup === null) {
      // The change made the file: it goes as it came.
      rmSync(file)
      syncFolder(dirname(file))
    } else {
      const bytes = readFileSync(join(backupDir(), basename(last.backup)))
      hash = sha256(bytes)
      writeAtomic(file, bytes, cur.stat, () => {
        if (readCurrent(file)?.hash !== last.wrote) throw changed()
      })
    }
  } catch (e) {
    if (e instanceof EditRefused) throw e
    const code = (e as NodeJS.ErrnoException).code
    if (code === 'ENOENT') throw new EditRefused(409, 'nothing_to_undo', 'There’s no change here to undo.')
    log('agent settings: undo failed', agent, code ?? 'error')
    throw failed()
  }
  rmSync(lastFile(agent), { force: true })
  log(`agent settings: ${agent} ${last.key} ${String(last.after)} -> ${inLog(last.before)} (undo${loosened ? ', loosens' : ''})`)
  return { ok: true, agent, key: last.key, hash, loosened }
}
