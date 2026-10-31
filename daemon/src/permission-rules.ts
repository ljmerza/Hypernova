// The Claude Code permission rules in effect for one session (docs/PROTOCOL.md, "Rules in effect";
// IDEAS SS5): the allow, ask and deny lists, `defaultMode` and `additionalDirectories` of each
// settings file Claude Code reads for the session's folder, read-only, so the phone can say why a
// call asked or didn't.
//
// Settings files hold secrets too (`env` with tokens, `apiKeyHelper`, hooks, MCP servers). Only
// `permissions.allow|ask|deny` (strings), `permissions.defaultMode` and
// `permissions.additionalDirectories` are ever taken from them, cut to size; never the file, never
// another key, and never a parse error's words (Node's quote the text). The files are fixed: the
// managed policy, the user's in the Claude folder, and the project's two in the session's own
// folder, which the daemon already knows. No path comes from the phone.
//
// Where Claude Code 2.1.314 reads them (its own code, `grep -a` on the binary): the managed policy
// is `managed-settings.json` in `/etc/claude-code` (Linux), `/Library/Application Support/ClaudeCode`
// (macOS), then each `*.json` in `managed-settings.d/` beside it (not dotfiles), A to Z. Its
// server-managed settings, MDM plists and the Windows policy key aren't files here: not shown.
import { closeSync, constants, fstatSync, openSync, readdirSync, readSync } from 'node:fs'
import { join } from 'node:path'
import { CLAUDE_DIR } from './util.ts'

/** Where a list of rules comes from, highest precedence first. */
export type RuleSource = 'managed' | 'session' | 'local' | 'project' | 'user'

/**
 * One source's rules. [state]: `ok`; `missing` (no such file); `unreadable` (not a file, or no
 * permission); `invalid` (not JSON, or not an object); `too_big` (over [FILE_MAX], not read).
 * Only an `ok` source has rules. [clipped]: some were left out or cut ([LIST_MAX], [RULE_MAX]).
 */
export type RuleFile = {
  source: RuleSource
  /** The file, or null for the session's own (from Hypernova: no file). */
  path: string | null
  state: 'ok' | 'missing' | 'unreadable' | 'invalid' | 'too_big'
  allow: string[]
  ask: string[]
  deny: string[]
  defaultMode: string | null
  additionalDirectories: string[]
  clipped: boolean
}

/**
 * The session's rules, highest precedence first: managed, the session's own, project local,
 * project, user. [managedOnly]: the managed policy sets `allowManagedPermissionRulesOnly`, so only
 * its rules apply.
 */
export type RulesInEffect = { sources: RuleFile[]; managedOnly: boolean }

/** A settings file bigger than this isn't read (Claude Code's are a few KB). */
export const FILE_MAX = 512 * 1024
/** Rules kept per list, per source; directories per source. */
export const LIST_MAX = 200
export const DIRS_MAX = 50
/** A rule or a directory longer than this is cut, with "…". */
export const RULE_MAX = 300
/** Managed drop-in files read at most. */
const DROPINS_MAX = 20

let managedDir = process.platform === 'darwin' ? '/Library/Application Support/ClaudeCode' : '/etc/claude-code'
/** Tests: where the managed policy is read from. */
export function useManagedDir(dir: string): void {
  managedDir = dir
}

type Read = { state: 'ok'; value: unknown } | { state: Exclude<RuleFile['state'], 'ok'> }

/**
 * A settings file's JSON, opened without blocking (a FIFO put there never holds the daemon) and
 * checked to be a regular file under [FILE_MAX] on the open file itself, then parsed.
 */
function readSettings(path: string): Read {
  let fd: number
  try {
    fd = openSync(path, constants.O_RDONLY | constants.O_NONBLOCK)
  } catch (e) {
    return { state: (e as NodeJS.ErrnoException).code === 'ENOENT' || (e as NodeJS.ErrnoException).code === 'ENOTDIR' ? 'missing' : 'unreadable' }
  }
  try {
    const st = fstatSync(fd)
    if (!st.isFile()) return { state: 'unreadable' }
    if (st.size > FILE_MAX) return { state: 'too_big' }
    const buf = Buffer.alloc(FILE_MAX + 1)
    let n = 0
    for (let got = -1; got !== 0 && n <= FILE_MAX; n += got) got = readSync(fd, buf, n, FILE_MAX + 1 - n, null)
    if (n > FILE_MAX) return { state: 'too_big' }
    try {
      return { state: 'ok', value: JSON.parse(buf.subarray(0, n).toString('utf8')) }
    } catch {
      return { state: 'invalid' }
    }
  } catch {
    return { state: 'unreadable' }
  } finally {
    closeSync(fd)
  }
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** A list's strings, cut to [max] and each to [RULE_MAX]; what else it holds is skipped. */
function strings(v: unknown, max: number, cut: { clipped: boolean }): string[] {
  if (!Array.isArray(v)) return []
  const out: string[] = []
  for (const x of v) {
    if (typeof x !== 'string' || !x.trim()) continue
    if (out.length === max) {
      cut.clipped = true
      break
    }
    const t = x.trim()
    if (t.length > RULE_MAX) cut.clipped = true
    out.push(t.length > RULE_MAX ? t.slice(0, RULE_MAX - 1) + '…' : t)
  }
  return out
}

/** A source with nothing in it, in [state]. */
const empty = (source: RuleSource, path: string | null, state: RuleFile['state']): RuleFile => ({ source, path, state, allow: [], ask: [], deny: [], defaultMode: null, additionalDirectories: [], clipped: false })

/** Only `permissions`' five keys, as [RuleFile] has them; nothing else of [value] is looked at. */
export function rulesOf(source: RuleSource, path: string | null, value: unknown): RuleFile {
  if (!isObject(value)) return empty(source, path, 'invalid')
  const p = isObject(value.permissions) ? value.permissions : {}
  const cut = { clipped: false }
  const mode = typeof p.defaultMode === 'string' && /^[A-Za-z]{1,32}$/.test(p.defaultMode) ? p.defaultMode : null
  return {
    source,
    path,
    state: 'ok',
    allow: strings(p.allow, LIST_MAX, cut),
    ask: strings(p.ask, LIST_MAX, cut),
    deny: strings(p.deny, LIST_MAX, cut),
    defaultMode: mode,
    additionalDirectories: strings(p.additionalDirectories, DIRS_MAX, cut),
    clipped: cut.clipped,
  }
}

function fileRules(source: RuleSource, path: string): { file: RuleFile; managedOnly: boolean } {
  const r = readSettings(path)
  if (r.state !== 'ok') return { file: empty(source, path, r.state), managedOnly: false }
  const file = rulesOf(source, path, r.value)
  // Only the managed policy's word counts; at the top or under permissions (unsure which Claude
  // Code reads: both are taken), and only a plain true.
  const v = r.value as Record<string, unknown>
  const only = source === 'managed' && file.state === 'ok' && (v.allowManagedPermissionRulesOnly === true || (isObject(v.permissions) && v.permissions.allowManagedPermissionRulesOnly === true))
  return { file, managedOnly: only }
}

/**
 * The rules for a session in [cwd]: the managed policy (only when it has a file there), then the
 * session's own [session] rules (only when Hypernova knows some), then the project's
 * `settings.local.json` and `settings.json`, then the user's. Read fresh each time (they're small).
 */
export function rulesInEffect(cwd: string, session: { allow: string[] } = { allow: [] }, claudeDir: string = CLAUDE_DIR): RulesInEffect {
  const sources: RuleFile[] = []
  let managedOnly = false
  const base = fileRules('managed', join(managedDir, 'managed-settings.json'))
  if (base.file.state !== 'missing') sources.push(base.file)
  managedOnly ||= base.managedOnly
  const dropDir = join(managedDir, 'managed-settings.d')
  let names: string[] = []
  try {
    names = readdirSync(dropDir).filter((n) => n.endsWith('.json') && !n.startsWith('.')).sort()
  } catch {}
  for (const n of names.slice(0, DROPINS_MAX)) {
    const d = fileRules('managed', join(dropDir, n))
    sources.push(d.file)
    managedOnly ||= d.managedOnly
  }
  if (session.allow.length) {
    const cut = { clipped: false }
    sources.push({ ...empty('session', null, 'ok'), allow: strings([...new Set(session.allow)], LIST_MAX, cut), clipped: cut.clipped })
  }
  sources.push(fileRules('local', join(cwd, '.claude', 'settings.local.json')).file)
  sources.push(fileRules('project', join(cwd, '.claude', 'settings.json')).file)
  sources.push(fileRules('user', join(claudeDir, 'settings.json')).file)
  return { sources, managedOnly }
}
