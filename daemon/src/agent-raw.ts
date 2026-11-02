// Claude Code's `settings*.json` as raw JSON for a paired phone (docs/PROTOCOL.md, "JSON mode"; docs/DECISIONS.md, "JSON mode";
// the classification and the risk: docs/SECURITY-MODEL.md, 21). The three files only, named by scope: `~/.claude.json` can't
// be asked for. The rules hold here because the server keeps them, whatever text comes back:
//
// - A secret never leaves. Its value is swapped for a placeholder before sending, by position, so everything else in the text is
//   as it is in the file; a placeholder left where it was keeps the stored value, and one anywhere else is refused.
// - Nothing that would turn on bypass. Refused with the key named, unless it was already there and hasn't changed.
// - What runs code or widens a permission needs the fingerprint's claim. Anything not on the short list of plain keys counts,
//   so a key a newer Claude Code adds asks by default.
//
// Codex isn't here: its `config.toml` has inline tables that the TOML reader can't see inside to mask, and its writer
// changes one key at a time, so a whole file can't go through it.

import { randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { guardOf, shownValue, claudeFile, CLAUDE_SCOPES } from './agent-env.ts'
import { isTable } from './agent-settings.ts'
import { backupDir, jsonMembers, jsonValueEnd, pruneBackups, readCurrent, sha256, skipJsonSpace, textOf, writeAtomic, writeBackup, loosens } from './agent-settings-edit.ts'
import { McpRefused, serial } from './mcp-edit.ts'
import { maskArgs, maskText, secretName } from './mcp-view.ts'
import { CLAUDE_DIR, log } from './util.ts'

export const PLACEHOLDER = '<hidden on the server>'
const KEY_PLACEHOLDER = /^<hidden on the server (\d+)>$/
/** Past this a file isn't for a phone's editor. */
const RAW_MAX = 256 * 1024

type Path = readonly (string | number)[]
type Scope = (typeof CLAUDE_SCOPES)[number]

const camel = (key: string) => key.replace(/([a-z0-9])([A-Z])/g, '$1_$2')
/** Commands by name, shown as what they are unless what's in them looks secret. */
const NOT_SECRET_KEYS = new Set(['apiKeyHelper', 'otelHeadersHelper', 'proxyAuthHelper', 'keybindingFlavor', 'disableAuth'])

/** Whether a value at [path] stays on the server. Only strings and numbers can hold one; a flag in a file says nothing. [doc]: for what's beside it in a list, where `--token` says what the next one is. */
function isSecret(path: Path, v: unknown, doc?: unknown): boolean {
  if (typeof v !== 'string' && typeof v !== 'number') return false
  const key = path.at(-1)
  const parent = path.at(-2)
  if (typeof key === 'number' && typeof v === 'string' && doc !== undefined) {
    const list = at(doc, path.slice(0, -1))
    if (Array.isArray(list) && list.every((x) => typeof x === 'string') && maskArgs(list as string[])[key] !== v) return true
  }
  // The top-level env reads as the list does. A server's env and headers leave as names only, whatever they hold.
  if (typeof key === 'string' && parent === 'env' && path.length === 2) return shownValue(key, v).masked
  if (parent === 'env' || parent === 'headers' || parent === 'http_headers') return true
  if (typeof v === 'number') return false
  if (typeof key === 'string' && !NOT_SECRET_KEYS.has(key) && secretName(camel(key))) return true
  return maskText(v) !== v
}

const keySecret = (key: string) => maskText(key) !== key

/** One value or key that's kept back. */
type Span = { from: number; to: number; path: Path; kind: 'value' | 'key'; text?: string }

/** Every string and number, and every key, in [t]'s tree, with its position. [t] has been through JSON.parse. */
function walk(t: string, on: (path: Path, from: number, to: number, kind: 'value' | 'key') => void): void {
  const value = (at: number, path: Path) => {
    const c = t[at]
    if (c === '{') {
      for (const m of jsonMembers(t, at)) {
        on([...path, m.name], m.keyFrom, m.keyTo, 'key')
        value(m.valueFrom, [...path, m.name])
      }
    } else if (c === '[') {
      let i = skipJsonSpace(t, at + 1)
      let n = 0
      while (t[i] !== ']') {
        value(i, [...path, n++])
        i = skipJsonSpace(t, jsonValueEnd(t, i))
        if (t[i] === ',') i = skipJsonSpace(t, i + 1)
      }
    } else on(path, at, jsonValueEnd(t, at), 'value')
  }
  value(skipJsonSpace(t, 0), [])
}

const at = (doc: unknown, path: Path): unknown => {
  let d = doc
  for (const p of path) {
    if (typeof p === 'number') d = Array.isArray(d) ? d[p] : undefined
    else d = isTable(d) && Object.hasOwn(d, p) ? d[p] : undefined
    if (d === undefined) return undefined
  }
  return d
}

const splices = (t: string, spans: readonly { from: number; to: number; text: string }[]) => {
  let out = t
  for (const s of [...spans].sort((a, b) => b.from - a.from)) out = out.slice(0, s.from) + s.text + out.slice(s.to)
  return out
}

/** [t] as the phone may see it, and the secret keys it hid, in order. */
export function maskedText(t: string): { text: string; hiddenKeys: string[]; hidden: number } {
  const doc = JSON.parse(t)
  const spans: { from: number; to: number; text: string }[] = []
  const hiddenKeys: string[] = []
  const under = (path: Path) => path.some((p, i) => i < path.length && typeof p === 'string' && keySecret(p))
  walk(t, (path, from, to, kind) => {
    if (kind === 'key') {
      const key = path.at(-1) as string
      if (!keySecret(key)) return
      hiddenKeys.push(key)
      spans.push({ from, to, text: JSON.stringify(`<hidden on the server ${hiddenKeys.length}>`) })
    } else if (isSecret(path, at(doc, path), doc) || under(path)) spans.push({ from, to, text: JSON.stringify(PLACEHOLDER) })
  })
  return { text: splices(t, spans), hiddenKeys, hidden: spans.length }
}

/** The offset of the first thing in [t] that isn't JSON. JSON.parse doesn't always say, and what it says can quote the file. Called only once it has refused. */
function firstBadOffset(t: string): number {
  let i = 0
  const space = () => {
    while (i < t.length && ' \t\n\r'.includes(t[i]!)) i++
  }
  const fail = (): never => {
    throw i
  }
  const string = () => {
    i++
    while (i < t.length && t[i] !== '"') {
      if (t.charCodeAt(i) < 0x20) fail()
      if (t[i] === '\\') {
        i++
        if (t[i] === 'u') {
          if (!/^[0-9a-fA-F]{4}$/.test(t.slice(i + 1, i + 5))) fail()
          i += 4
        } else if (!'"\\/bfnrt'.includes(t[i] ?? ' ')) fail()
      }
      i++
    }
    if (i >= t.length) fail()
    i++
  }
  const value = (): void => {
    space()
    const c = t[i]
    if (c === '{') {
      i++
      space()
      if (t[i] === '}') return void i++
      for (;;) {
        space()
        if (t[i] !== '"') fail()
        string()
        space()
        if (t[i] !== ':') fail()
        i++
        value()
        space()
        if (t[i] === ',') {
          i++
          continue
        }
        if (t[i] !== '}') fail()
        return void i++
      }
    }
    if (c === '[') {
      i++
      space()
      if (t[i] === ']') return void i++
      for (;;) {
        value()
        space()
        if (t[i] === ',') {
          i++
          continue
        }
        if (t[i] !== ']') fail()
        return void i++
      }
    }
    if (c === '"') return string()
    const word = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(t.slice(i, i + 64))
    if (!word) fail()
    i += word![0].length
  }
  try {
    value()
    space()
    return i
  } catch (at) {
    return at as number
  }
}

/** Where a text stops being JSON, as a line and a column, or null if it doesn't. */
export function parseError(text: string): { line: number; column: number } | null {
  try {
    JSON.parse(text)
    return null
  } catch {
    const before = text.slice(0, firstBadOffset(text))
    return { line: before.split('\n').length, column: before.length - (before.lastIndexOf('\n') + 1) + 1 }
  }
}

const refuse = (status: number, code: string, message: string): never => {
  throw new McpRefused(status, code, message)
}
const shownPath = (path: Path) => path.map((p, i) => (typeof p === 'number' ? `[${p}]` : i ? `.${p}` : p)).join('')

/**
 * The text to write: [sent] with each placeholder back as what's stored at that path, by position, so what the owner
 * typed around it stays as typed. One anywhere else is refused: a secret doesn't move, and three words aren't a value.
 */
export function restored(sent: string, stored: string): { text: string; doc: Record<string, unknown> } {
  const old = JSON.parse(stored)
  const { hiddenKeys } = maskedText(stored)
  const spans: { from: number; to: number; text: string }[] = []
  const real = (path: Path): Path => path.map((p) => {
    const m = typeof p === 'string' ? KEY_PLACEHOLDER.exec(p) : null
    return m ? (hiddenKeys[Number(m[1]) - 1] ?? p) : p
  })
  const doc = JSON.parse(sent)
  walk(sent, (path, from, to, kind) => {
    if (kind === 'key') {
      const key = path.at(-1) as string
      const m = KEY_PLACEHOLDER.exec(key)
      if (!m) return
      const name = hiddenKeys[Number(m[1]) - 1]
      if (!name) refuse(400, 'placeholder_unknown', `${shownPath(path.slice(0, -1))}: a hidden key this server didn’t give out.`)
      spans.push({ from, to, text: JSON.stringify(name) })
    } else if (at(doc, path) === PLACEHOLDER) {
      const was = at(old, real(path))
      const hidden = isSecret(real(path), was, old) || real(path).some((p) => typeof p === 'string' && keySecret(p))
      if (was === undefined || !hidden || (typeof was !== 'string' && typeof was !== 'number')) refuse(400, 'placeholder_unknown', `${shownPath(real(path))}: a hidden value stays where it was. Put it back, or type a new value.`)
      spans.push({ from, to, text: JSON.stringify(was) })
    }
  })
  const text = splices(sent, spans)
  return { text, doc: JSON.parse(text) }
}

// ── What changed, and what each change takes ─────────────────────────────

/**
 * Keys that decide nothing about what runs, what's allowed or where data goes. Everything else asks for the fingerprint,
 * so a key this list has never heard of asks too. docs/SECURITY-MODEL.md, 21, says why each is on which side.
 */
export const PLAIN_KEYS = new Set([
  'theme', 'editorMode', 'keybindingFlavor', 'vimInsertModeRemaps', 'verbose', 'preferredNotifChannel', 'autoCompactEnabled', 'autoCompactWindow', 'precomputeCompactionEnabled', 'idleCompaction', 'switchModelsOnFlag',
  'autoScrollEnabled', 'wheelScrollAccelerationEnabled', 'showTurnDuration', 'showMessageTimestamps', 'terminalProgressBarEnabled', 'todoFeatureEnabled', 'terminalTitleFromRename', 'prefersReducedMotion', 'timeFormat', 'timeZone',
  'tui', 'viewMode', 'defaultView', 'language', 'outputStyle', 'spinnerTipsEnabled', 'spinnerVerbs', 'spinnerTipsOverride', 'syntaxHighlightingDisabled', 'maxProseWidth', 'spellcheck', 'feedbackSurveyRate', 'feedbackDrafts',
  'emojiCompletionEnabled', 'promptSuggestionEnabled', 'showClearContextOnPlanAccept', 'askUserQuestionTimeout', 'dialogExpiry', 'model', 'fallbackModel', 'advisorModel', 'effortLevel', 'maxEffortLevel', 'modelSettings',
  'alwaysThinkingEnabled', 'fastMode', 'fastModePerSessionOptIn', 'ultracode', 'promptCacheTtl', 'subagentPromptCacheTtl', 'cleanupPeriodDays', 'desktopSessionCleanupPeriodDays', 'respectGitignore', 'includeCoAuthoredBy',
  'includeGitInstructions', 'attribution', 'skillListingMaxDescChars', 'skillListingBudgetFraction', 'skillOverrides', 'disableBundledSkills', 'bashEditDiffEnabled', 'bashOutputMaxChars', 'taskOutputMaxChars', 'showThinkingSummaries',
  'autoUpdatesChannel', 'autoMemoryEnabled', 'autoDreamEnabled', 'inputNeededNotifEnabled', 'agentPushNotifEnabled', 'voiceEnabled', 'voice', 'companyAnnouncements', 'footerLinksRegexes', 'workflowSizeGuideline', 'workflowKeywordTriggerEnabled',
  'disableWorkflows', 'disableArtifact', 'enableArtifact', 'enableWorkflows', 'disableAgentView', 'teammateMode', 'fileCheckpointingEnabled', 'autoContinueAtUsageLimit', 'disableDeepLinkRegistration',
])

/** Keys that would switch a permission check off for good, wherever they sit. What a phone can't set. */
const REFUSED_KEY = /dangerous|^skipAutoPermissionPrompt$/i
const MODES = new Set(['plan', 'default', 'manual', 'dontAsk', 'acceptEdits', 'auto'])

export type Change = { path: string; take: 'refused' | 'check' | 'plain'; now: string }

/** A new value in a line, for a confirm sheet: secrets kept back as they are from the phone. */
function words(path: Path, v: unknown): string {
  if (v === undefined) return '(removed)'
  const hide = (p: Path, x: unknown): unknown => {
    if (isSecret(p, x)) return PLACEHOLDER
    if (Array.isArray(x)) {
      const shown = x.every((e) => typeof e === 'string') ? maskArgs(x as string[]) : null
      return x.map((e, i) => (shown && shown[i] !== e ? PLACEHOLDER : hide([...p, i], e)))
    }
    if (isTable(x)) return Object.fromEntries(Object.entries(x).map(([k, e]) => [keySecret(k) ? PLACEHOLDER : k, hide([...p, k], e)]))
    return x
  }
  const text = JSON.stringify(hide(path, v))
  return text.length > 400 ? `${text.slice(0, 399)}…` : text
}

function findRefusedKey(v: unknown, was: unknown, path: Path, out: Change[]): void {
  if (!isTable(v)) return
  for (const [k, x] of Object.entries(v)) {
    const before = isTable(was) ? was[k] : undefined
    if (REFUSED_KEY.test(k) && x !== false && x !== null && !isDeepStrictEqual(x, before)) out.push({ path: shownPath([...path, k]), take: 'refused', now: words([...path, k], x) })
    else findRefusedKey(x, before, [...path, k], out)
  }
}

/** Every change from [old] to [next], each with what it takes. */
export function classify(old: Record<string, unknown>, next: Record<string, unknown>): Change[] {
  const out: Change[] = []
  findRefusedKey(next, old, [], out)
  const refused = new Set(out.map((c) => c.path))
  const add = (path: Path, take: Change['take'], v: unknown) => {
    if (!refused.has(shownPath(path))) out.push({ path: shownPath(path), take, now: words(path, v) })
  }
  for (const key of new Set([...Object.keys(old), ...Object.keys(next)])) {
    const a = old[key]
    const b = next[key]
    if (isDeepStrictEqual(a, b)) continue
    if (key === 'env' && (a === undefined || isTable(a)) && (b === undefined || isTable(b))) {
      const was = (a ?? {}) as Record<string, unknown>
      const now = (b ?? {}) as Record<string, unknown>
      for (const name of new Set([...Object.keys(was), ...Object.keys(now)])) {
        if (isDeepStrictEqual(was[name], now[name])) continue
        const gone = !Object.hasOwn(now, name)
        const guard = guardOf(name)
        add(['env', name], guard === 'refused' ? (gone ? 'plain' : 'refused') : guard === 'guarded' ? 'check' : 'plain', now[name])
      }
    } else if (key === 'permissions' && (a === undefined || isTable(a)) && (b === undefined || isTable(b))) {
      permissionChanges((a ?? {}) as Record<string, unknown>, (b ?? {}) as Record<string, unknown>, add)
    } else if (REFUSED_KEY.test(key) && (b === undefined || b === false)) add([key], 'plain', b)
    else add([key], PLAIN_KEYS.has(key) ? 'plain' : 'check', b)
  }
  return out
}

function permissionChanges(a: Record<string, unknown>, b: Record<string, unknown>, add: (p: Path, t: Change['take'], v: unknown) => void): void {
  for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
    if (isDeepStrictEqual(a[k], b[k])) continue
    if (k === 'defaultMode') {
      const to = b[k]
      if (to === undefined) add(['permissions', k], 'plain', to)
      else if (typeof to !== 'string' || !MODES.has(to)) add(['permissions', k], 'refused', to)
      else {
        const from = a[k]
        const was = from === undefined ? ({ key: k, state: 'unset' } as const) : typeof from === 'string' ? ({ key: k, state: 'set', value: from } as const) : ({ key: k, state: 'unrecognised' } as const)
        add(['permissions', k], loosens('permissions.defaultMode', was, to) ? 'check' : 'plain', to)
      }
    } else if ((k === 'allow' || k === 'additionalDirectories') && list(a[k]) && list(b[k])) {
      // Taking one away only narrows.
      const was = new Set(a[k] as unknown[] ?? [])
      const added = ((b[k] ?? []) as unknown[]).filter((r) => !was.has(r))
      add(['permissions', k], added.length ? 'check' : 'plain', added.length ? added : b[k])
    } else if ((k === 'ask' || k === 'deny') && list(a[k]) && list(b[k])) {
      // Taking one away widens.
      const now = new Set(b[k] as unknown[] ?? [])
      const gone = ((a[k] ?? []) as unknown[]).filter((r) => !now.has(r))
      add(['permissions', k], gone.length ? 'check' : 'plain', gone.length ? { removed: gone } : b[k])
    } else add(['permissions', k], 'check', b[k])
  }
}
const list = (v: unknown) => v === undefined || Array.isArray(v)

/** Refused with the key named; else the claim, whose words list exactly what changes. */
function allowed(old: Record<string, unknown>, next: Record<string, unknown>, body: Record<string, unknown>): Change[] {
  const changes = classify(old, next)
  const stopped = changes.filter((c) => c.take === 'refused')
  if (stopped.length) refuse(403, 'raw_refused', `${stopped.map((c) => c.path).join(', ')} can’t be set from a phone. Change it on the server.`)
  const asks = changes.filter((c) => c.take === 'check')
  if (asks.length && body.confirmRun !== true) refuse(403, 'confirm_run', `These change what runs, what’s allowed or where data goes:\n\n${asks.map((c) => `${c.path}: ${c.now}`).join('\n')}`)
  return changes
}

// ── The routes ───────────────────────────────────────────────────────────

const scopeOf = (v: unknown): Scope => {
  if (v !== 'user' && v !== 'project' && v !== 'local') refuse(400, 'bad_scope', 'That isn’t one of the settings files.')
  return v as Scope
}
const whereOf = (scope: Scope, folder: string) => (scope === 'user' ? '~/.claude/settings.json' : `${folder}/.claude/${scope === 'project' ? 'settings.json' : 'settings.local.json'}`)

function current(file: string) {
  try {
    return readCurrent(file)
  } catch (e) {
    if ((e as { code?: string }).code === 'file_is_link') throw new McpRefused(409, 'file_is_link', 'That file is a link on the server, so it isn’t changed from here. Change it there.')
    throw new McpRefused(409, 'file_unreadable', 'That file can’t be read on the server.')
  }
}

export type RawUndo = { scope: string; folder: string | null; needsCheck: boolean }
type Last = { file: string; scope: Scope; folderPath: string | null; wrote: string | null; backup: string | null }
const lastFile = () => join(backupDir(), 'raw-last-claude.json')
function readLast(): Last | null {
  try {
    const l = JSON.parse(readFileSync(lastFile(), 'utf8'))
    return isTable(l) && typeof l.file === 'string' ? (l as unknown as Last) : null
  } catch {
    return null
  }
}

function undoOffer(id: (p: string) => string): RawUndo | null {
  const last = readLast()
  if (!last) return null
  try {
    const cur = readCurrent(last.file)
    if ((cur?.hash ?? null) !== last.wrote) return null
    const now = cur ? JSON.parse(cur.bytes.toString('utf8')) : {}
    const before = last.backup ? JSON.parse(readFileSync(join(backupDir(), last.backup), 'utf8')) : {}
    const changes = classify(now, before)
    if (changes.some((c) => c.take === 'refused')) return null
    return { scope: last.scope, folder: last.folderPath ? id(last.folderPath) : null, needsCheck: changes.some((c) => c.take === 'check') }
  } catch {
    return null
  }
}

/** GET /v1/agents/raw: one settings file with its secrets kept back. A file that doesn't parse is sent as a state, not as text: nothing could be masked in it. */
export function rawRead(scopeValue: unknown, folder: string, folderLabel: string, id: (p: string) => string, dir: string = CLAUDE_DIR) {
  const scope = scopeOf(scopeValue)
  const file = claudeFile(scope, folder, dir)
  const base = { agent: 'claude', scope, where: whereOf(scope, folderLabel), placeholder: PLACEHOLDER, undo: undoOffer(id) }
  let cur
  try {
    cur = readCurrent(file)
  } catch (e) {
    return { ...base, state: (e as { code?: string }).code === 'file_is_link' ? 'link' : 'unreadable', hash: null, text: null, hidden: 0 }
  }
  if (!cur) return { ...base, state: 'missing', hash: null, text: '{\n}\n', hidden: 0 }
  if (cur.bytes.length > RAW_MAX) return { ...base, state: 'too_big', hash: cur.hash, text: null, hidden: 0 }
  const text = textOf(cur.bytes)
  if (text === null) return { ...base, state: 'unreadable', hash: cur.hash, text: null, hidden: 0 }
  const bad = parseError(text)
  if (bad) return { ...base, state: 'malformed', hash: cur.hash, text: null, hidden: 0, line: bad.line, column: bad.column }
  const masked = maskedText(text)
  return { ...base, state: 'ok', hash: cur.hash, text: masked.text, hidden: masked.hidden }
}

/** POST /v1/agents/raw: the whole file, checked, compared, and written all at once. */
export function rawSave(body: Record<string, unknown>, folder: string, dir: string = CLAUDE_DIR) {
  return serial(async () => {
    const scope = scopeOf(body.scope)
    const file = claudeFile(scope, folder, dir)
    if (typeof body.text !== 'string' || body.text.length > RAW_MAX) refuse(400, 'bad_text', 'That’s too long for a settings file.')
    const sent = body.text as string
    const bad = parseError(sent)
    if (bad) refuse(400, 'bad_json', `Line ${bad.line}, column ${bad.column}: that isn’t valid JSON. Nothing was written.`)
    if (!isTable(JSON.parse(sent))) refuse(400, 'not_an_object', 'A settings file is one object, in braces.')
    const cur = current(file)
    if ((cur?.hash ?? null) !== (body.expected ?? null)) refuse(409, 'file_changed', 'That file changed on the server since you opened it. Nothing was written. Open it again to see what’s there.')
    const storedText = cur ? textOf(cur.bytes) : '{}'
    if (storedText === null || parseError(storedText)) refuse(409, 'file_malformed', 'What’s on the server isn’t valid JSON, so it can’t be compared. Fix it there.')
    const old = JSON.parse(storedText as string)
    if (!isTable(old)) refuse(409, 'file_malformed', 'What’s on the server isn’t one object. Fix it there.')
    const { text, doc } = restored(sent, storedText as string)
    const changes = allowed(old as Record<string, unknown>, doc, body)
    if (text === storedText) return { ok: true, changed: false, hash: cur?.hash ?? null, changes: [] }
    let backup: string | null = null
    mkdirSync(join(file, '..'), { recursive: true })
    try {
      writeAtomic(file, Buffer.from(text, 'utf8'), cur?.stat ?? null, () => {
        if (cur) backup = writeBackup('claude', cur.bytes)
        if ((readCurrent(file)?.hash ?? null) !== (body.expected ?? null)) {
          if (backup) rmSync(join(backupDir(), backup), { force: true })
          backup = null
          refuse(409, 'file_changed', 'That file changed on the server just now. Nothing was written.')
        }
      })
    } catch (e) {
      if (backup) rmSync(join(backupDir(), backup), { force: true })
      if (e instanceof McpRefused) throw e
      throw new McpRefused(500, 'write_failed', 'That couldn’t be written. Nothing changed.')
    }
    const hash = sha256(text)
    try {
      mkdirSync(backupDir(), { recursive: true, mode: 0o700 })
      const tmp = `${lastFile()}.${randomBytes(6).toString('hex')}`
      writeFileSync(tmp, JSON.stringify({ file, scope, folderPath: scope === 'user' ? null : folder, wrote: hash, backup } satisfies Last), { mode: 0o600 })
      renameSync(tmp, lastFile())
      pruneBackups('claude')
    } catch {}
    log(`agent settings: raw ${scope}: ${changes.map((c) => c.path).join(', ') || 'formatting'}`)
    return { ok: true, changed: true, hash, changes: changes.map(({ path, take }) => ({ path, take })) }
  })
}

/** POST /v1/agents/raw/undo: the file as it was, under the same rules, since going back is a change too. */
export function rawUndo(body: Record<string, unknown>) {
  return serial(async () => {
    const last = readLast()
    if (!last) refuse(409, 'nothing_to_undo', 'There’s no change here to undo.')
    const { file, wrote, backup } = last!
    const cur = current(file)
    if ((cur?.hash ?? null) !== wrote) refuse(409, 'file_changed', 'That file changed on the server since. Nothing was written.')
    const now = cur ? JSON.parse(cur.bytes.toString('utf8')) : {}
    const bytes = backup ? readFileSync(join(backupDir(), backup)) : null
    const before = bytes ? JSON.parse(bytes.toString('utf8')) : {}
    const changes = classify(now, before)
    if (changes.some((c) => c.take === 'refused')) refuse(409, 'undo_refused', 'What was there before can’t be set from a phone. Its backup is on the server.')
    const asks = changes.filter((c) => c.take === 'check')
    if (asks.length && body.confirmRun !== true) refuse(403, 'confirm_run', `Putting this back changes what runs, what’s allowed or where data goes:\n\n${asks.map((c) => `${c.path}: ${c.now}`).join('\n')}`)
    let hash: string | null = null
    if (!bytes) rmSync(file, { force: true })
    else {
      hash = sha256(bytes)
      writeAtomic(file, bytes, cur?.stat ?? null, () => {
        if ((readCurrent(file)?.hash ?? null) !== wrote) refuse(409, 'file_changed', 'That file changed on the server just now. Nothing was written.')
      })
    }
    rmSync(lastFile(), { force: true })
    log(`agent settings: raw ${last!.scope} undone`)
    return { ok: true, scope: last!.scope, hash }
  })
}
