// An agent's environment variables from a paired phone (docs/PROTOCOL.md, "An agent's environment variables";
// docs/DECISIONS.md, "Environment variables from the phone"; the threat: docs/SECURITY-MODEL.md).
//
// Claude Code: the `env` table of `settings.json`, in each scope. Codex: `shell_environment_policy.set`, the only
// environment its config sets, which reaches the commands it runs and not Codex itself or its MCP servers.
//
// A value that looks secret, or sits under a name that says so, never leaves: the row says it's masked, and a new one is
// write-only. What changes what runs or where data goes needs the owner's fingerprint, on the server too (`confirmRun`).
// What would turn on bypass, or tell Claude Code a folder is trusted, can't be set from a phone at all, only taken away.

import { randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { isTable, own, parseTable } from './agent-settings.ts'
import { backupDir, jsonEditPath, pruneBackups, readCurrent, sha256, textOf, writeAtomic, writeBackup, type Agent } from './agent-settings-edit.ts'
import { McpRefused, serial } from './mcp-edit.ts'
import { withAppServer } from './mcp-codex.ts'
import { MASK, maskText, maskUrl, secretName } from './mcp-view.ts'
import { CLAUDE_DIR, log } from './util.ts'

// ── What a name is ───────────────────────────────────────────────────────

export const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const VALUE_MAX = 8192
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/

/**
 * Never set from a phone. Each of these makes Claude Code skip a permission or a trust check: found in 2.1.316's binary,
 * where IS_SANDBOX and CLAUDE_CODE_SANDBOXED tell it the machine is sandboxed, so bypass runs as root and every folder counts as trusted.
 */
const REFUSED_NAMES = new Set([
  'IS_SANDBOX',
  'CLAUDE_CODE_SANDBOXED',
  'CLAUDE_CODE_EVEN_MORE_DANGEROUSLY_SKIP_PERMISSIONS',
  'CLAUDE_CODE_IGNORE_PERSISTED_TRUST',
  'CLAUDE_CODE_RELAUNCH_HOME_TRUST',
  'CLAUDE_CODE_AUTO_MODE_EXTERNAL_PERMISSIONS',
  'CLAUDE_CODE_DISABLE_ULTRAREVIEW_TRUST_CHECK',
  'CLAUDE_BG_WORKSPACE_TRUSTED',
  'CLAUDE_BG_SESSION_PERMISSION_RULES',
  'CLAUDE_CHROME_PERMISSION_MODE',
  'CLAUDE_CODE_REMOTE_TOOLS_HOST_ALLOWS_UNATTENDED',
])
const REFUSED_SHAPE = /BYPASS|DANGEROUS|YOLO|SKIP_PERMISSION|FULL_ACCESS|NO_SANDBOX|UNSAFE/

/** Change what runs or how it's loaded. */
const RUN_NAMES = new Set(['PATH', 'HOME', 'ENV', 'BASH_ENV', 'ZDOTDIR', 'SHELL', 'IFS', 'PROMPT_COMMAND', 'NODE_OPTIONS', 'NODE_PATH', 'PYTHONPATH', 'PYTHONHOME', 'PYTHONSTARTUP', 'PERL5LIB', 'PERL5OPT', 'RUBYLIB', 'RUBYOPT', 'GIT_SSH', 'GIT_SSH_COMMAND', 'GIT_EXEC_PATH', 'GIT_ASKPASS', 'SSH_ASKPASS', 'EDITOR', 'VISUAL', 'PAGER', 'BROWSER', 'CLAUDE_CONFIG_DIR', 'CODEX_HOME', 'CODEX_SQLITE_HOME', 'CLAUDE_BIN', 'CODEX_EXEC_SERVER_URL'])
/** Change where data goes, whose account it is, or what's trusted on the way there. */
const DATA_NAMES = new Set(['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'NODE_TLS_REJECT_UNAUTHORIZED', 'NODE_EXTRA_CA_CERTS', 'NODE_USE_ENV_PROXY', 'NODE_USE_SYSTEM_CA', 'SSL_CERT_FILE', 'SSL_CERT_DIR', 'CURL_CA_BUNDLE', 'REQUESTS_CA_BUNDLE', 'CODEX_CA_CERTIFICATE', 'ANTHROPIC_CUSTOM_HEADERS', 'ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN', 'OPENAI_API_KEY', 'CODEX_API_KEY', 'AWS_CONFIG_FILE', 'AWS_SHARED_CREDENTIALS_FILE', 'GOOGLE_APPLICATION_CREDENTIALS', 'CLAUDE_CODE_ENABLE_TELEMETRY'])
const GUARDED_SHAPES: readonly RegExp[] = [
  /^LD_/, // the dynamic linker
  /^DYLD_/,
  /^GIT_CONFIG/,
  /(^|_)BASE_URL$/, // an API's address
  /_ENDPOINT(_|$)/,
  /(^|_)PROXY(_|$)/,
  /_CA_(BUNDLE|CERTS?)$/,
  /^AWS_ENDPOINT_URL/,
  /^OTEL_/, // telemetry: where it goes and whether prompts go with it
  /^CLAUDE_CODE_USE_/, // which provider
  /^CLAUDE_CODE_SKIP_.*_AUTH$/,
  /^CLAUDE_CODE_API_/,
  /^CLAUDE_CODE_SHELL/,
  /^CLAUDE_CODE_GIT_BASH_PATH$/,
  /^CLAUDE_CODE_PLUGIN_.*(DIR|DIRS|URL)$/,
  /^CLAUDE_CODE_MANAGED_SETTINGS_PATH$/,
  /^CLAUDE_CODE_(CLIENT_CERT|CERT_STORE)/,
  /^CLAUDE_CODE_MCP_ALLOWLIST_ENV$/,
  /^CLAUDE_CODE_REMOTE_/,
  /^CLAUDE_CODE_DISABLE_.*(RM|DENY|TRUST|PERMISSION|SANDBOX)/, // a check switched off
  /^CLAUDE_TEST_/,
  /^MCP_PROXY_/,
  /HELPER/, // apiKeyHelper's relatives: commands that fetch credentials
  /_TOKEN_FILE(_|$)/,
  /_FILE_DESCRIPTOR$/,
  /^HYPERNOVA_/,
]

export type Guard = 'refused' | 'guarded' | 'plain'
/** Which it is. Claude Code reads names in capitals, so that's how they're compared. */
export function guardOf(name: string): Guard {
  const n = name.toUpperCase()
  if (REFUSED_NAMES.has(n) || REFUSED_SHAPE.test(n)) return 'refused'
  if (RUN_NAMES.has(n) || DATA_NAMES.has(n) || GUARDED_SHAPES.some((r) => r.test(n))) return 'guarded'
  return 'plain'
}

// ── What a value is ──────────────────────────────────────────────────────

const FLAGS: readonly (readonly [string, string])[] = [['1', '0'], ['true', 'false'], ['True', 'False'], ['TRUE', 'FALSE'], ['yes', 'no'], ['Yes', 'No'], ['YES', 'NO'], ['on', 'off'], ['On', 'Off'], ['ON', 'OFF']]
type Stored = string | number | boolean

/** A flag's two forms, if [v] is one: the file keeps the one it has. */
const pairOf = (v: Stored): readonly [Stored, Stored] | null => {
  if (typeof v === 'boolean') return [true, false]
  if (typeof v === 'number') return v === 0 || v === 1 ? [1, 0] : null
  return FLAGS.find((p) => p.includes(v)) ?? null
}
const isOn = (v: Stored, pair: readonly [Stored, Stored]) => v === pair[0]

/** A value as it may leave. A count under a name like MAX_TOKENS isn't a secret, nor is a flag under any name. */
export function shownValue(name: string, v: Stored): { masked: boolean; value?: string } {
  const text = String(v)
  if (pairOf(v)) return { masked: false, value: text }
  if (/_TOKENS$/.test(name.toUpperCase()) && /^\d{1,9}$/.test(text)) return { masked: false, value: text }
  if (secretName(name)) return { masked: true }
  const clean = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(text) ? maskUrl(text) : maskText(text)
  if (clean === text) return text.length > 512 ? { masked: true } : { masked: false, value: text }
  // Only the secret part masked, where that leaves anything worth reading.
  return clean.replaceAll(MASK, '').trim() ? { masked: true, value: clean.slice(0, 512) } : { masked: true }
}

const nameSendable = (n: string) => ENV_NAME.test(n) && maskText(n) === n

export type EnvRow = { name: string; scope: string; masked: boolean; value?: string; flag?: boolean; effective: boolean; overriddenBy?: string; guarded: boolean; refused: boolean }
export type EnvFile = { scope: string; where: string; state: 'ok' | 'missing' | 'malformed' | 'link' | 'unreadable'; hash: string | null }
export type EnvUndo = { name: string; scope: string; folder: string | null; needsCheck: boolean; change: string }

function rowOf(name: string, scope: string, v: Stored): EnvRow {
  const guard = guardOf(name)
  const pair = pairOf(v)
  return { name, scope, ...shownValue(name, v), ...(pair ? { flag: isOn(v, pair) } : {}), effective: true, guarded: guard === 'guarded', refused: guard === 'refused' }
}

const storable = (v: unknown): v is Stored => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'

// ── Files: Claude Code ───────────────────────────────────────────────────

/** Claude Code's scopes, weakest first: a later one wins a name (measured on 2.1.316: local over project over user, and any of them over the shell). */
export const CLAUDE_SCOPES = ['user', 'project', 'local'] as const
type ClaudeScope = (typeof CLAUDE_SCOPES)[number]

export function claudeFile(scope: ClaudeScope, folder: string, dir: string = CLAUDE_DIR): string {
  if (scope === 'user') return join(dir, 'settings.json')
  return join(folder, '.claude', scope === 'project' ? 'settings.json' : 'settings.local.json')
}
const same = (a: string, b: string) => {
  try {
    return realpathSync(a) === realpathSync(b)
  } catch {
    return a === b
  }
}
/** Where a file is, in words: a folder's base name, never its path. */
function whereOf(scope: ClaudeScope, folderName: string): string {
  if (scope === 'user') return '~/.claude/settings.json'
  return `${folderName}/.claude/${scope === 'project' ? 'settings.json' : 'settings.local.json'}`
}

function readFile(file: string): { state: EnvFile['state']; hash: string | null; env: Record<string, unknown> } {
  let cur
  try {
    cur = readCurrent(file)
  } catch (e) {
    return { state: (e as McpRefused).code === 'file_is_link' ? 'link' : 'unreadable', hash: null, env: {} }
  }
  if (!cur) return { state: 'missing', hash: null, env: {} }
  const text = textOf(cur.bytes)
  const doc = text === null ? null : parseTable(text)
  if (!doc) return { state: 'malformed', hash: cur.hash, env: {} }
  const env = own(doc, 'env')
  return { state: 'ok', hash: cur.hash, env: isTable(env) ? env : {} }
}

export async function claudeEnv(folder: string, folderLabel: string, dir: string = CLAUDE_DIR) {
  const files: EnvFile[] = []
  const rows: EnvRow[] = []
  let hidden = 0
  for (const scope of CLAUDE_SCOPES) {
    const file = claudeFile(scope, folder, dir)
    // In the home folder, a project's file is the user's own.
    if (scope === 'project' && same(file, claudeFile('user', folder, dir))) continue
    const r = readFile(file)
    files.push({ scope, where: whereOf(scope, folderLabel), state: r.state, hash: r.hash })
    for (const [name, v] of Object.entries(r.env)) {
      if (!nameSendable(name) || !storable(v)) {
        hidden++
        continue
      }
      rows.push(rowOf(name, scope, v))
    }
  }
  // Compared as Claude Code does, in capitals.
  const winner = new Map<string, string>()
  for (const scope of CLAUDE_SCOPES) for (const row of rows) if (row.scope === scope) winner.set(row.name.toUpperCase(), scope)
  for (const row of rows) {
    const top = winner.get(row.name.toUpperCase())!
    if (top !== row.scope) {
      row.effective = false
      row.overriddenBy = top
    }
  }
  return { agent: 'claude' as const, files, vars: rows, hidden }
}

// ── Undo ─────────────────────────────────────────────────────────────────

/** The last change. For Claude Code a whole file's backup; for Codex the value before. Kept as settings backups are. */
type Last = { agent: Agent; file?: string; scope: string; folderPath: string | null; name: string; wrote: string | null; backup: string | null; before: Stored | null; existed: boolean; after: Stored | null; afterHash?: string }
const lastFile = (agent: Agent) => join(backupDir(), `env-last-${agent}.json`)
function saveLast(last: Last): void {
  mkdirSync(backupDir(), { recursive: true, mode: 0o700 })
  const tmp = `${lastFile(last.agent)}.${randomBytes(6).toString('hex')}`
  writeFileSync(tmp, JSON.stringify(last), { mode: 0o600 })
  renameSync(tmp, lastFile(last.agent))
}
function readLast(agent: Agent): Last | null {
  try {
    const l = JSON.parse(readFileSync(lastFile(agent), 'utf8'))
    return isTable(l) && typeof l.name === 'string' ? (l as unknown as Last) : null
  } catch {
    return null
  }
}

const words = (name: string, v: Stored | null) => (v === null ? '(not set)' : (shownValue(name, v).value ?? MASK))
export const changeWords = (name: string, from: Stored | null, to: Stored | null) => `${name}: ${words(name, from)} → ${words(name, to)}`

function offerFrom(last: Last | null, currentHash: string | null, id: (p: string | null) => string | null): EnvUndo | null {
  if (!last || last.wrote !== currentHash) return null
  // What would put a refused one back stays on the server's backup.
  if (last.before !== null && guardOf(last.name) === 'refused') return null
  return { name: last.name, scope: last.scope, folder: id(last.folderPath), needsCheck: guardOf(last.name) === 'guarded', change: changeWords(last.name, last.after, last.before) }
}

export function claudeUndoOffer(id: (p: string | null) => string | null): EnvUndo | null {
  const last = readLast('claude')
  if (!last?.file) return null
  try {
    return offerFrom(last, readCurrent(last.file)?.hash ?? null, id)
  } catch {
    return null
  }
}

// ── The change ───────────────────────────────────────────────────────────

const refuse = (status: number, code: string, message: string): never => {
  throw new McpRefused(status, code, message)
}

/** What a request wants of [name]: its checks, the same for both agents. */
function wanted(body: Record<string, unknown>, removing: boolean): { name: string; value?: string; flip: boolean } {
  const name = body.name
  if (typeof name !== 'string' || !ENV_NAME.test(name)) refuse(400, 'bad_name', 'A name is letters, numbers and underscores, and doesn’t start with a number.')
  if (removing) return { name: name as string, flip: false }
  if (body.flip === true) return { name: name as string, flip: true }
  const value = body.value
  if (typeof value !== 'string' || value.length > VALUE_MAX || CONTROL.test(value)) refuse(400, 'bad_value', 'That value can’t be used: it has to be one line of text.')
  if ((value as string).includes(MASK)) refuse(400, 'masked_value', 'That still has a mask in it. Type the new value, or leave it as it is.')
  return { name: name as string, value: value as string, flip: false }
}

/** The fingerprint's claim and the outright refusal, whoever's asking. [off]: taking the variable away, which is always allowed for a refused one. */
function allowed(name: string, off: boolean, body: Record<string, unknown>, from: Stored | null, to: Stored | null): void {
  const guard = guardOf(name)
  if (guard === 'refused' && !off) refuse(403, 'env_refused', `${name} would turn off a permission or trust check, so it can’t be set from a phone. Set it on the server.`)
  if (guard === 'guarded' && !off && body.confirmRun !== true) refuse(403, 'confirm_run', `${changeWords(name, from, to)} changes what runs or where data goes. Confirm it with your fingerprint or screen lock.`)
  if (guard === 'guarded' && off && body.confirmRun !== true) refuse(403, 'confirm_run', `Taking ${name} away changes what runs or where data goes. Confirm it with your fingerprint or screen lock.`)
}

const stale = () => new McpRefused(409, 'file_changed', 'That file changed on the server since this was read. Nothing was written.')

/** A name as written in the file: Claude Code reads `foo` as FOO, so a change finds the row it means. */
const claudeScopeOf = (v: unknown): ClaudeScope => {
  if (v !== 'user' && v !== 'project' && v !== 'local') refuse(400, 'bad_scope', 'That isn’t a place a variable can go.')
  return v as ClaudeScope
}

function writeClaude(file: string, scope: string, folderPath: string | null, name: string, to: Stored | null, cur: ReturnType<typeof readCurrent>, from: Stored | null, expected: unknown): string | null {
  const text = cur ? textOf(cur.bytes) : null
  if (cur && (text === null || !parseTable(text))) refuse(409, 'file_malformed', 'That file isn’t valid, so nothing was written. Fix it on the server.')
  const next = jsonEditPath(text ?? '{}\n', ['env', name], to)
  const wrote = sha256(next)
  let backup: string | null = null
  if (to === null && !cur) return null
  mkdirSync(join(file, '..'), { recursive: true })
  try {
    writeAtomic(file, Buffer.from(next, 'utf8'), cur?.stat ?? null, () => {
      if (cur) backup = writeBackup('claude', cur.bytes)
      if ((readCurrent(file)?.hash ?? null) !== (expected ?? null)) {
        if (backup) rmSync(join(backupDir(), backup), { force: true })
        backup = null
        throw stale()
      }
    })
  } catch (e) {
    if (backup) rmSync(join(backupDir(), backup), { force: true })
    if (e instanceof McpRefused) throw e
    throw new McpRefused(500, 'write_failed', 'That couldn’t be written. Nothing changed.')
  }
  try {
    saveLast({ agent: 'claude', file, scope, folderPath, name, wrote, backup, before: from, existed: !!cur, after: to })
    pruneBackups('claude')
  } catch {}
  log(`agent env: claude ${name} (${scope}) ${guardOf(name) === 'guarded' ? 'guarded, ' : ''}${to === null ? 'removed' : 'set'}`)
  return wrote
}

export function claudeSet(body: Record<string, unknown>, folder: string, dir: string = CLAUDE_DIR) {
  return serial(async () => {
    const want = wanted(body, false)
    const scope = claudeScopeOf(body.scope)
    const file = claudeFile(scope, folder, dir)
    const cur = readChecked(file)
    if ((cur?.hash ?? null) !== (body.expected ?? null)) throw stale()
    const env = cur ? envOf(cur.bytes) : {}
    const from = storable(own(env, want.name)) ? (own(env, want.name) as Stored) : null
    let to: Stored
    if (want.flip) {
      const pair = from === null ? null : pairOf(from)
      if (!pair) refuse(409, 'not_a_flag', `${want.name} isn’t on or off. Give it a value instead.`)
      to = isOn(from as Stored, pair!) ? pair![1] : pair![0]
    } else to = want.value as string
    allowed(want.name, false, body, from, to)
    const hash = writeClaude(file, scope, scope === 'user' ? null : folder, want.name, to, cur, from, body.expected)
    return { ok: true, name: want.name, scope, hash, ...rowBits(want.name, to) }
  })
}

export function claudeRemove(body: Record<string, unknown>, folder: string, dir: string = CLAUDE_DIR) {
  return serial(async () => {
    const want = wanted(body, true)
    const scope = claudeScopeOf(body.scope)
    const file = claudeFile(scope, folder, dir)
    const cur = readChecked(file)
    if ((cur?.hash ?? null) !== (body.expected ?? null)) throw stale()
    const env = cur ? envOf(cur.bytes) : {}
    if (!Object.hasOwn(env, want.name)) refuse(404, 'no_variable', 'That variable isn’t there any more.')
    const from = storable(own(env, want.name)) ? (own(env, want.name) as Stored) : null
    if (guardOf(want.name) !== 'refused') allowed(want.name, true, body, from, null)
    const hash = writeClaude(file, scope, scope === 'user' ? null : folder, want.name, null, cur, from, body.expected)
    return { ok: true, name: want.name, scope, hash }
  })
}

const rowBits = (name: string, v: Stored) => {
  const pair = pairOf(v)
  return { ...shownValue(name, v), ...(pair ? { flag: isOn(v, pair) } : {}) }
}

function readChecked(file: string) {
  try {
    return readCurrent(file)
  } catch (e) {
    if ((e as { code?: string }).code === 'file_is_link') throw new McpRefused(409, 'file_is_link', 'That file is a link on the server, so it isn’t changed from here. Change it there.')
    throw new McpRefused(409, 'file_unreadable', 'That file can’t be read on the server. Nothing was written.')
  }
}
const envOf = (bytes: Buffer) => {
  const text = textOf(bytes)
  const doc = text === null ? null : parseTable(text)
  if (!doc) throw new McpRefused(409, 'file_malformed', 'That file isn’t valid, so nothing was written. Fix it on the server.')
  const env = own(doc, 'env')
  if (env !== undefined && !isTable(env)) throw new McpRefused(409, 'unsupported_shape', 'That file’s env isn’t a table, so it isn’t changed from here.')
  return isTable(env) ? env : {}
}

export function claudeUndo(body: Record<string, unknown>) {
  return serial(async () => {
    const last = readLast('claude')
    if (!last?.file) refuse(409, 'nothing_to_undo', 'There’s no change here to undo.')
    const file = last!.file!
    const cur = readChecked(file)
    if ((cur?.hash ?? null) !== last!.wrote) throw stale()
    if (last!.before !== null && guardOf(last!.name) === 'refused') refuse(409, 'undo_refused', 'What was there before can’t be set from a phone. Its backup is on the server.')
    if (guardOf(last!.name) === 'guarded' && body.confirmRun !== true) refuse(403, 'confirm_run', 'Putting this back changes what runs or where data goes. Confirm it with your fingerprint or screen lock.')
    let hash: string | null = null
    if (last!.backup === null) {
      rmSync(file, { force: true })
    } else {
      const bytes = readFileSync(join(backupDir(), last!.backup))
      hash = sha256(bytes)
      writeAtomic(file, bytes, cur?.stat ?? null, () => {
        if ((readCurrent(file)?.hash ?? null) !== last!.wrote) throw stale()
      })
    }
    rmSync(lastFile('claude'), { force: true })
    log(`agent env: claude ${last!.name} (${last!.scope}) undone`)
    return { ok: true, name: last!.name, scope: last!.scope, hash }
  })
}

// ── Codex ────────────────────────────────────────────────────────────────

const SET_PATH = (name: string) => `shell_environment_policy.set.${name}`
const setOf = (config: unknown): Record<string, unknown> => {
  const policy = isTable(config) ? own(config, 'shell_environment_policy') : undefined
  const set = isTable(policy) ? own(policy, 'set') : undefined
  return isTable(set) ? set : {}
}

/** Codex's own read: its user layer is the file, its version what a write is checked against. */
async function codexRead(req: (m: string, p?: unknown) => Promise<any>) {
  const r = await req('config/read', { includeLayers: true })
  const layers: any[] = Array.isArray(r?.layers) ? r.layers : []
  const user = layers.find((l) => l?.name?.type === 'user')
  return { merged: setOf(r?.config), user: setOf(user?.config), version: typeof user?.version === 'string' ? user.version : null }
}

export async function codexEnv() {
  const { merged, user, version } = await withAppServer(codexRead)
  let hidden = 0
  const rows: EnvRow[] = []
  for (const [name, v] of Object.entries(user)) {
    if (!nameSendable(name) || !storable(v)) {
      hidden++
      continue
    }
    const row = rowOf(name, 'user', v)
    // A system or managed layer can hold the same name.
    if (own(merged, name) !== v) {
      row.effective = false
      row.overriddenBy = 'system'
    }
    rows.push(row)
  }
  const last = readLast('codex')
  return { agent: 'codex' as const, files: [{ scope: 'user', where: '~/.codex/config.toml', state: 'ok' as const, hash: version }], vars: rows, hidden, undo: offerFrom(last, version, () => null) }
}

async function codexWrite(req: (m: string, p?: unknown) => Promise<any>, name: string, to: Stored | null, version: string | null): Promise<string | null> {
  try {
    const r = await req('config/value/write', { keyPath: SET_PATH(name), value: to, mergeStrategy: to === null ? 'replace' : 'upsert', ...(version ? { expectedVersion: version } : {}) })
    return typeof r?.version === 'string' ? r.version : null
  } catch (e) {
    if (e instanceof McpRefused && /modified since/i.test(e.message)) throw stale()
    throw e
  }
}

export function codexSet(body: Record<string, unknown>) {
  return serial(async () => {
    const want = wanted(body, false)
    return withAppServer(async (req) => {
      const { user, version } = await codexRead(req)
      if ((body.expected ?? null) !== version) throw stale()
      const from = storable(own(user, want.name)) ? (own(user, want.name) as Stored) : null
      let to: Stored
      if (want.flip) {
        const pair = from === null ? null : pairOf(from)
        if (!pair) refuse(409, 'not_a_flag', `${want.name} isn’t on or off. Give it a value instead.`)
        to = isOn(from as Stored, pair!) ? pair![1] : pair![0]
      } else to = want.value as string
      // Codex's table holds strings.
      to = String(to)
      allowed(want.name, false, body, from, to)
      const hash = await codexWrite(req, want.name, to, version)
      saveLast({ agent: 'codex', scope: 'user', folderPath: null, name: want.name, wrote: hash, backup: null, before: from, existed: from !== null, after: to })
      log(`agent env: codex ${want.name} ${guardOf(want.name) === 'guarded' ? 'guarded, ' : ''}set`)
      return { ok: true, name: want.name, scope: 'user', hash, ...rowBits(want.name, to) }
    })
  })
}

export function codexRemove(body: Record<string, unknown>) {
  return serial(async () => {
    const want = wanted(body, true)
    return withAppServer(async (req) => {
      const { user, version } = await codexRead(req)
      if ((body.expected ?? null) !== version) throw stale()
      if (!Object.hasOwn(user, want.name)) refuse(404, 'no_variable', 'That variable isn’t there any more.')
      const from = storable(own(user, want.name)) ? (own(user, want.name) as Stored) : null
      if (guardOf(want.name) !== 'refused') allowed(want.name, true, body, from, null)
      const hash = await codexWrite(req, want.name, null, version)
      saveLast({ agent: 'codex', scope: 'user', folderPath: null, name: want.name, wrote: hash, backup: null, before: from, existed: true, after: null })
      log(`agent env: codex ${want.name} removed`)
      return { ok: true, name: want.name, scope: 'user', hash }
    })
  })
}

export function codexUndo(body: Record<string, unknown>) {
  return serial(async () => {
    const last = readLast('codex')
    if (!last) refuse(409, 'nothing_to_undo', 'There’s no change here to undo.')
    return withAppServer(async (req) => {
      const { version } = await codexRead(req)
      if (version !== last!.wrote) throw stale()
      if (last!.before !== null && guardOf(last!.name) === 'refused') refuse(409, 'undo_refused', 'What was there before can’t be set from a phone.')
      if (guardOf(last!.name) === 'guarded' && body.confirmRun !== true) refuse(403, 'confirm_run', 'Putting this back changes what runs or where data goes. Confirm it with your fingerprint or screen lock.')
      const hash = await codexWrite(req, last!.name, last!.before, version)
      rmSync(lastFile('codex'), { force: true })
      return { ok: true, name: last!.name, scope: 'user', hash }
    })
  })
}

