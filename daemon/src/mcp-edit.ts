// Changing an agent's MCP servers from a paired phone (docs/PROTOCOL.md, "An agent's MCP servers"; the threat and the limits:
// docs/SECURITY-MODEL.md). Everything goes through Claude Code's own commands and its own writer where it
// has one: `claude mcp add-json`, `remove`, `login`, `logout`, `reset-project-choices`, and `toggleMcpServer()`
// from a process (mcp-probe.ts). The one file written here is a folder's `.claude/settings.local.json`, for
// approving a project's server, as Claude Code's own dialog does: it has no command for that.
//
// Adding or changing a stdio server is running a program on the server. The server can't see a fingerprint,
// so the phone's check is only a claim (`confirmRun`), as `confirmShell` is for an automation; it's refused without one.

import { dbgProc } from './debug-log.ts'
import { execFile } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { isTable, own, parseTable } from './agent-settings.ts'
import { backupDir, jsonEditPath, readCurrent, textOf, writeAtomic } from './agent-settings-edit.ts'
import { maskArgs, maskText, maskUrl, MASK, folderTrusted, readClaudeConfig, restoreArgs, findConfig, transportOf, configHash, type RawConfig, type Scope } from './mcp-view.ts'
import { childEnv } from './runner.ts'
import { HOME, findClaude, log } from './util.ts'
import { MACHINE } from './platform.ts'

export class McpRefused extends Error {
  status: number
  code: string
  /** More of the refusal, beside its code and message in the answer. */
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    if (extra) this.extra = extra
  }
}

// ── What a form may say ──────────────────────────────────────────────────

/** What Claude Code's own `mcp add` takes as a name. */
const NEW_NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const HEADER_NAME = /^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/
const VALUE_MAX = 8192
const ARGS_MAX = 64
const ENTRIES_MAX = 64
const COMMAND_MAX = 1024
const ENTRY_MAX = 64 * 1024
const CONTROL = /[\u0000-\u0008\u000a-\u001f\u007f]/ // eslint-disable-line no-control-regex

const refuse = (code: string, message: string, status = 400): never => {
  throw new McpRefused(status, code, message)
}

export type PairInput = { name?: unknown; value?: unknown; keep?: unknown }

/** A table of secrets from a form: each a new value, or `keep` for the one on the server, which is never sent to the phone. */
function pairs(list: unknown, nameRule: RegExp, was: unknown, what: string, secrets: string[]): Record<string, string> | undefined {
  if (list === undefined || list === null) return undefined
  if (!Array.isArray(list) || list.length > ENTRIES_MAX) refuse('bad_pairs', `That’s not a list of ${what}.`)
  const out: Record<string, string> = {}
  const old = isTable(was) ? was : {}
  for (const raw of list as PairInput[]) {
    const name = raw?.name
    if (typeof name !== 'string' || !nameRule.test(name)) refuse('bad_pair_name', `A name in ${what} isn’t one that can be used.`)
    const key = name as string
    if (raw.keep === true) {
      const v = own(old, key)
      if (typeof v !== 'string') refuse('nothing_to_keep', `${key} has no value on the server to keep. Give it one.`)
      out[key] = v as string
      continue
    }
    const value = raw.value
    if (typeof value !== 'string' || value.length > VALUE_MAX || CONTROL.test(value)) refuse('bad_pair_value', `The value of ${key} can’t be used.`)
    if ((value as string).length) secrets.push(value as string)
    out[key] = value as string
  }
  return Object.keys(out).length ? out : undefined
}

export type Form = { name: string; config: RawConfig; secrets: string[]; clientSecret: string | null }

/**
 * A form's server as Claude Code stores it, checked here and not only in the app. [original]: the entry being
 * edited, whose values stand for anything the form sends back as kept or masked, and whose other fields carry over.
 */
export function formConfig(body: Record<string, unknown>, original: RawConfig | null = null): Form {
  const name = body.name
  if (typeof name !== 'string' || !NEW_NAME.test(name)) refuse('bad_name', 'A name is letters, numbers, dashes and underscores, up to 64.')
  const transport = body.transport
  if (transport !== 'stdio' && transport !== 'http' && transport !== 'sse') refuse('bad_transport', 'A server is stdio, HTTP or SSE.')
  const secrets: string[] = []
  const sameType = original !== null && transportOf(original) === transport
  // What the form doesn't speak of stays: alwaysLoad, a helper, a timeout.
  const kept: RawConfig = sameType ? { ...original } : {}
  for (const k of ['command', 'args', 'env', 'url', 'headers']) delete kept[k]
  const config: RawConfig = { ...kept, type: transport as string }
  if (transport === 'stdio') {
    const command = body.command
    if (typeof command !== 'string' || !command.trim() || command.length > COMMAND_MAX || CONTROL.test(command)) refuse('bad_command', 'That command can’t be used.')
    if (command === MASK || (command as string).includes(MASK)) {
      if (!sameType || typeof own(original!, 'command') !== 'string') refuse('masked_unknown', 'That still has a mask in it that this server didn’t give out.')
      config.command = own(original!, 'command')
    } else config.command = (command as string).trim()
    const args = body.args ?? []
    if (!Array.isArray(args) || args.length > ARGS_MAX || args.some((a) => typeof a !== 'string' || a.length > VALUE_MAX || CONTROL.test(a))) refuse('bad_args', 'Those arguments can’t be used.')
    const was = sameType && Array.isArray(own(original!, 'args')) ? (own(original!, 'args') as unknown[]).filter((a): a is string => typeof a === 'string') : []
    const restored = restoreArgs(args as string[], was)
    if (restored === null) refuse('masked_unknown', 'An argument still has a mask in it that this server didn’t give out.')
    if (restored!.length) config.args = restored
    const env = pairs(body.env, ENV_NAME, sameType ? own(original!, 'env') : null, 'environment variables', secrets)
    if (env) config.env = env
  } else {
    let url = body.url
    if (typeof url !== 'string' || url.length > VALUE_MAX) refuse('bad_url', 'That address can’t be used.')
    if ((url as string).includes(MASK)) {
      const was = sameType ? own(original!, 'url') : null
      if (typeof was !== 'string' || maskUrl(was) !== url) refuse('masked_unknown', 'That address still has a mask in it that this server didn’t give out.')
      url = was
    } else {
      let parsed: URL | null = null
      try {
        parsed = new URL(url as string)
      } catch {}
      // `${VAR}` is Claude Code's to expand, in a URL too, so only a URL with none has to parse.
      if ((!parsed || !['http:', 'https:'].includes(parsed.protocol)) && !(url as string).includes('${')) refuse('bad_url', 'An address starts with http:// or https://.')
      if (CONTROL.test(url as string)) refuse('bad_url', 'That address can’t be used.')
      if (parsed?.password) secrets.push(parsed.password)
    }
    config.url = url
    const headers = pairs(body.headers, HEADER_NAME, sameType ? own(original!, 'headers') : null, 'headers', secrets)
    if (headers) config.headers = headers
  }
  // OAuth only means something for a server with an address.
  let clientSecret: string | null = null
  const oauth = body.oauth
  if (oauth !== undefined && oauth !== null) {
    if (!isTable(oauth) || transport === 'stdio') refuse('bad_oauth', 'Those sign-in settings can’t be used.')
    const was = sameType && isTable(own(original!, 'oauth')) ? (own(original!, 'oauth') as RawConfig) : {}
    const next: RawConfig = { ...was }
    const o = oauth as RawConfig
    if (typeof o.clientId === 'string' && o.clientId.trim()) {
      if (o.clientId.length > 512 || CONTROL.test(o.clientId)) refuse('bad_oauth', 'That client ID can’t be used.')
      next.clientId = o.clientId.trim()
    } else if (o.clientId === '' || o.clientId === null) delete next.clientId
    if (typeof o.callbackPort === 'number') {
      if (!Number.isInteger(o.callbackPort) || o.callbackPort < 1 || o.callbackPort > 65535) refuse('bad_oauth', 'A port is between 1 and 65535.')
      next.callbackPort = o.callbackPort
    } else if (o.callbackPort === null) delete next.callbackPort
    if (Object.keys(next).length) config.oauth = next
    else delete config.oauth
    if (typeof o.clientSecret === 'string' && o.clientSecret) {
      if (o.clientSecret.length > VALUE_MAX || CONTROL.test(o.clientSecret)) refuse('bad_oauth', 'That client secret can’t be used.')
      clientSecret = o.clientSecret
      secrets.push(o.clientSecret)
    }
  }
  if (body.timeout !== undefined && body.timeout !== null) {
    if (typeof body.timeout !== 'number' || !Number.isInteger(body.timeout) || body.timeout < 1000 || body.timeout > 86_400_000) refuse('bad_timeout', 'A timeout is at least a second.')
    config.timeout = body.timeout
  } else if (body.timeout === null) delete config.timeout
  if (JSON.stringify(config).length > ENTRY_MAX) refuse('too_big', 'That server’s settings are too long.')
  return { name: name as string, config, secrets, clientSecret }
}

/** Whether going from [before] to [after] changes what would run on the server: the thing the fingerprint is for. */
export function changesWhatRuns(before: unknown, after: unknown): boolean {
  if (transportOf(after) !== 'stdio') return false
  if (transportOf(before) !== 'stdio' || !isTable(before) || !isTable(after)) return true
  return !isDeepStrictEqual([own(before, 'command'), own(before, 'args') ?? [], own(before, 'env') ?? {}], [own(after, 'command'), own(after, 'args') ?? [], own(after, 'env') ?? {}])
}

/** A stdio server's command line as the confirm sheet shows it: in full, with what looks secret masked. */
export function commandLine(config: unknown): string | null {
  if (!isTable(config) || typeof own(config, 'command') !== 'string') return null
  const args = own(config, 'args')
  return [maskText(own(config, 'command') as string), ...maskArgs(Array.isArray(args) ? args.filter((a): a is string => typeof a === 'string') : [])].join(' ')
}

// ── Claude Code's own commands ───────────────────────────────────────────

const CLI_MS = 60_000
type CliResult = { code: number; stdout: string; stderr: string }

/** Tests can stand in for the binary. */
let cliHook: ((args: string[], cwd: string, env: Record<string, string>) => Promise<CliResult>) | null = null
export const useCli = (f: typeof cliHook) => {
  cliHook = f
}

export function runCli(bin: string, args: string[], cwd: string, env: Record<string, string> = {}, input?: string, timeout: number = CLI_MS): Promise<CliResult> {
  return new Promise((resolve) => {
    const started = dbgProc(bin, args)
    const child = execFile(bin, args, { cwd, env: childEnv(env), timeout, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      const code = err ? (typeof (err as NodeJS.ErrnoException).code === 'number' ? ((err as NodeJS.ErrnoException).code as unknown as number) : 1) : 0
      started(code)
      resolve({ code, stdout: String(stdout), stderr: String(stderr) })
    })
    if (input !== undefined) child.stdin?.end(input)
    else child.stdin?.end()
  })
}

const claudeBin = () => {
  const bin = findClaude()
  if (!bin) throw new McpRefused(409, 'no_claude', `Claude Code isn’t installed on this ${MACHINE}.`)
  return bin
}

async function claude(args: string[], cwd: string, env: Record<string, string> = {}): Promise<CliResult> {
  if (cliHook) return cliHook(args, cwd, env)
  return runCli(claudeBin(), args, cwd, env)
}

/** What a failed command said, in a line, with anything it was given and anything that looks like a secret gone. Its output is never kept otherwise: `claude mcp get` prints env values. */
export function cliWords(r: CliResult, secrets: readonly string[] = []): string | null {
  // eslint-disable-next-line no-control-regex
  const lines = `${r.stderr}\n${r.stdout}`.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, '').split('\n').map((l) => l.trim()).filter(Boolean)
  const line = lines.find((l) => /error|fail|already|invalid|not found|unknown|couldn|cannot|can’t|can't/i.test(l)) ?? lines[0]
  if (!line) return null
  let out = line
  for (const s of secrets) if (s) out = out.split(s).join(MASK)
  out = maskText(out)
  return out.length > 240 ? `${out.slice(0, 239)}…` : out
}

const failed = (r: CliResult, secrets: readonly string[], fallback: string) => new McpRefused(502, 'cli_failed', cliWords(r, secrets) ?? fallback)

// ── Undo ─────────────────────────────────────────────────────────────────

type LastOp = 'add' | 'remove' | 'edit'
/** The last change, whole: an entry's secrets are in it, so it's kept as a settings backup is, 0600 in a 0700 folder. */
type Last = { op: LastOp; name: string; scope: Scope; folder: string | null; before: RawConfig | null; after: RawConfig | null }
export type McpUndo = { op: LastOp; name: string; scope: Scope; folder: string | null; needsCheck: boolean; command: string | null }

const lastFile = (agent: string) => join(backupDir(), `mcp-last-${agent}.json`)

function saveLast(agent: string, last: Last): void {
  mkdirSync(backupDir(), { recursive: true, mode: 0o700 })
  const tmp = `${lastFile(agent)}.${randomBytes(6).toString('hex')}`
  writeFileSync(tmp, JSON.stringify(last), { mode: 0o600 })
  renameSync(tmp, lastFile(agent))
}
export function readLast(agent: string): Last | null {
  try {
    const l = JSON.parse(readFileSync(lastFile(agent), 'utf8'))
    return isTable(l) && typeof l.op === 'string' && typeof l.name === 'string' ? (l as unknown as Last) : null
  } catch {
    return null
  }
}
const dropLast = (agent: string) => rmSync(lastFile(agent), { force: true })
export const rememberLast = saveLast
export const forgetLast = dropLast

/** What Undo would do, if what's there is still what the last change left. */
export async function undoOffer(id: (path: string | null) => string | null): Promise<McpUndo | null> {
  const last = readLast('claude')
  if (!last) return null
  const now = await findConfig(last.name, last.scope, last.folder)
  if (!isDeepStrictEqual(now?.config ?? null, last.after)) return null
  return { op: last.op, name: last.name, scope: last.scope, folder: id(last.folder), needsCheck: last.before !== null && changesWhatRuns(last.after, last.before), command: last.before ? commandLine(last.before) : null }
}

// ── The changes ──────────────────────────────────────────────────────────

/** One write at a time: a remove and an add must not interleave with another phone's. */
let queue: Promise<unknown> = Promise.resolve()
export function serial<T>(work: () => Promise<T>): Promise<T> {
  const run = queue.then(work, work)
  queue = run.catch(() => {})
  return run
}

const cwdFor = (scope: Scope, folder: string | null) => (scope === 'local' || scope === 'project' ? folder! : HOME)

async function addJson(name: string, scope: Scope, folder: string | null, config: RawConfig, clientSecret: string | null, secrets: readonly string[]): Promise<void> {
  const env: Record<string, string> = clientSecret ? { MCP_CLIENT_SECRET: clientSecret } : {}
  const args = ['mcp', 'add-json', '--scope', scope, ...(clientSecret ? ['--client-secret'] : []), name, JSON.stringify(config)]
  const r = await claude(args, cwdFor(scope, folder), env)
  if (r.code !== 0) throw failed(r, secrets, 'Claude Code didn’t add that server.')
}
async function removeOne(name: string, scope: Scope, folder: string | null): Promise<void> {
  const r = await claude(['mcp', 'remove', '--scope', scope, name], cwdFor(scope, folder))
  if (r.code !== 0) throw failed(r, [], 'Claude Code didn’t remove that server.')
  if (await findConfig(name, scope, folder)) throw new McpRefused(502, 'not_saved', 'Claude Code said it removed that server, but it’s still in its file. Try again.')
}

/** `.mcp.json` is the folder's own, and a link would write elsewhere. */
function projectFileOk(scope: Scope, folder: string | null): void {
  if (scope !== 'project') return
  try {
    readCurrent(join(folder!, '.mcp.json'))
  } catch {
    throw new McpRefused(409, 'file_is_link', 'That folder’s .mcp.json is a link or can’t be read, so it isn’t changed from here.')
  }
}

const SCOPES: readonly Scope[] = ['user', 'local', 'project']
export const scopeOf = (v: unknown): Scope => {
  if (typeof v !== 'string' || !SCOPES.includes(v as Scope)) refuse('bad_scope', 'That isn’t a place a server can go.')
  return v as Scope
}

export type Changed = { ok: true; name: string; scope: Scope; hash: string | null }

/** POST /v1/agents/mcp/servers: a new server, in the scope and folder asked. */
export function addServer(body: Record<string, unknown>, scope: Scope, folder: string | null): Promise<Changed> {
  return serial(async () => {
    const form = formConfig(body)
    if (changesWhatRuns(null, form.config) && body.confirmRun !== true) refuse('confirm_run', 'Adding this runs a program on the server. Confirm it with your fingerprint or screen lock.', 403)
    projectFileOk(scope, folder)
    if (await findConfig(form.name, scope, folder)) refuse('exists', 'There’s already a server of that name there.', 409)
    await addJson(form.name, scope, folder, form.config, form.clientSecret, form.secrets)
    const after = await findConfig(form.name, scope, folder)
    if (!after) throw new McpRefused(502, 'cli_failed', 'Claude Code didn’t save that server.')
    saveLast('claude', { op: 'add', name: form.name, scope, folder, before: null, after: after.config as RawConfig })
    log(`mcp: added ${form.name} (${scope}, ${String(body.transport)})`)
    return { ok: true, name: form.name, scope, hash: configHash(after.config) }
  })
}

/**
 * PUT /v1/agents/mcp/servers. Claude Code has no edit: it's a remove and an add, in its own commands, and if
 * the add fails the old entry goes back. Against [expected], the version the phone read.
 */
export function editServer(body: Record<string, unknown>, scope: Scope, folder: string | null): Promise<Changed> {
  return serial(async () => {
    const found = typeof body.name === 'string' ? await findConfig(body.name, scope, folder) : null
    if (!found || !isTable(found.config)) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    if (body.expected !== configHash(found.config)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since this was read. Nothing was written.')
    const form = formConfig(body, found.config)
    if (changesWhatRuns(found.config, form.config) && body.confirmRun !== true) refuse('confirm_run', 'This changes what runs on the server. Confirm it with your fingerprint or screen lock.', 403)
    projectFileOk(scope, folder)
    if (isDeepStrictEqual(found.config, form.config) && !form.clientSecret) return { ok: true, name: form.name, scope, hash: configHash(found.config) }
    await removeOne(form.name, scope, folder)
    try {
      await addJson(form.name, scope, folder, form.config, form.clientSecret, form.secrets)
    } catch (e) {
      await addJson(form.name, scope, folder, found.config, null, []).catch(() => log(`mcp: edit of ${form.name} failed and the old entry didn’t go back`))
      throw e
    }
    const after = await findConfig(form.name, scope, folder)
    if (!after) throw new McpRefused(502, 'cli_failed', 'Claude Code didn’t save that server.')
    saveLast('claude', { op: 'edit', name: form.name, scope, folder, before: found.config, after: after.config as RawConfig })
    log(`mcp: changed ${form.name} (${scope})`)
    return { ok: true, name: form.name, scope, hash: configHash(after.config) }
  })
}

/** POST /v1/agents/mcp/servers/remove. No fingerprint: it stops something running, and Undo can put it back. */
export function removeServer(body: Record<string, unknown>, scope: Scope, folder: string | null): Promise<Changed> {
  return serial(async () => {
    const found = typeof body.name === 'string' ? await findConfig(body.name, scope, folder) : null
    if (!found) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    if (body.expected !== configHash(found.config)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since this was read. Nothing was written.')
    projectFileOk(scope, folder)
    await removeOne(body.name as string, scope, folder)
    saveLast('claude', { op: 'remove', name: body.name as string, scope, folder, before: found.config as RawConfig, after: null })
    log(`mcp: removed ${String(body.name)} (${scope})`)
    return { ok: true, name: body.name as string, scope, hash: null }
  })
}

/** POST /v1/agents/mcp/undo: the last add, edit or remove, one step, if nothing's changed that entry since. */
export function undoLast(body: Record<string, unknown>): Promise<{ ok: true; name: string; scope: Scope; op: LastOp }> {
  return serial(async () => {
    const last = readLast('claude')
    if (!last) throw new McpRefused(409, 'nothing_to_undo', 'There’s no change here to undo.')
    const now = await findConfig(last.name, last.scope, last.folder)
    if (!isDeepStrictEqual(now?.config ?? null, last.after)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since. Nothing was written.')
    if (last.before !== null && changesWhatRuns(last.after, last.before) && body.confirmRun !== true) refuse('confirm_run', 'Putting this back runs a program on the server. Confirm it with your fingerprint or screen lock.', 403)
    if (last.after !== null) await removeOne(last.name, last.scope, last.folder)
    if (last.before !== null) {
      await addJson(last.name, last.scope, last.folder, last.before, null, [])
      if (!(await findConfig(last.name, last.scope, last.folder))) throw new McpRefused(502, 'not_saved', 'Claude Code didn’t save that server. Try again.')
    }
    dropLast('claude')
    log(`mcp: undid the ${last.op} of ${last.name} (${last.scope})`)
    return { ok: true, name: last.name, scope: last.scope, op: last.op }
  })
}

// ── Approving a project's servers ────────────────────────────────────────

/** A list in a folder's `.claude/settings.local.json`, changed in place, nothing else in the file moved. */
function setInList(text: string, key: string, name: string, present: boolean): string {
  const doc = parseTable(text) ?? {}
  const was = Array.isArray(doc[key]) ? (doc[key] as unknown[]).filter((v): v is string => typeof v === 'string') : []
  const next = present ? [...new Set([...was, name])] : was.filter((v) => v !== name)
  if (isDeepStrictEqual(next, was) && (present || !Object.hasOwn(doc, key))) return text
  return jsonEditPath(text, [key], next.length || present ? next : null)
}

/**
 * POST /v1/agents/mcp/approval: Yes or No to a project's server, written where Claude Code's own dialog writes it.
 * Yes runs what `.mcp.json` says from then on, so it needs the claim of a check. Claude Code doesn't read a
 * folder's own approvals until the folder is trusted, and trusting a folder isn't for this route to do:
 * that's the first thing a session there asks, and it covers more than servers.
 */
export function setApproval(body: Record<string, unknown>, folder: string): Promise<{ ok: true; name: string; approval: 'approved' | 'rejected' }> {
  return serial(async () => {
    const name = body.name
    if (typeof name !== 'string' || !NEW_NAME.test(name)) refuse('bad_name', 'That isn’t a server’s name.')
    const yes = body.approved === true
    if (!yes && body.approved !== false) refuse('bad_request', 'Say yes or no.')
    if (!(await findConfig(name as string, 'project', folder))) throw new McpRefused(404, 'no_server', 'That server isn’t in this folder’s .mcp.json.')
    if (yes && body.confirmRun !== true) refuse('confirm_run', 'Approving this runs a program on the server. Confirm it with your fingerprint or screen lock.', 403)
    const { root } = await readClaudeConfig()
    if (yes && !folderTrusted(root, folder)) refuse('not_trusted', 'Claude Code hasn’t been told to trust this folder yet, so it wouldn’t read this. Start a session there and accept its question first.', 409)
    const dir = join(folder, '.claude')
    const file = join(dir, 'settings.local.json')
    let cur
    try {
      cur = readCurrent(file)
    } catch {
      throw new McpRefused(409, 'file_is_link', 'That folder’s settings.local.json is a link or can’t be read, so it isn’t changed from here.')
    }
    const text = cur ? textOf(cur.bytes) : null
    if (cur && (text === null || !parseTable(text))) throw new McpRefused(409, 'file_malformed', 'That folder’s settings.local.json isn’t valid. Fix it on the server.')
    let next = setInList(text ?? '{}\n', yes ? 'disabledMcpjsonServers' : 'enabledMcpjsonServers', name as string, false)
    next = setInList(next, yes ? 'enabledMcpjsonServers' : 'disabledMcpjsonServers', name as string, true)
    mkdirSync(dir, { recursive: true })
    try {
      writeAtomic(file, Buffer.from(next, 'utf8'), cur?.stat ?? null, () => {
        if ((readCurrent(file)?.hash ?? null) !== (cur?.hash ?? null)) throw new McpRefused(409, 'file_changed', 'That file changed on the server just now. Nothing was written.')
      })
    } catch (e) {
      if (e instanceof McpRefused) throw e
      throw new McpRefused(500, 'write_failed', 'That couldn’t be written. Nothing changed.')
    }
    log(`mcp: ${yes ? 'approved' : 'turned down'} ${String(name)} in a folder's .mcp.json`)
    return { ok: true, name: name as string, approval: yes ? 'approved' : 'rejected' }
  })
}

/** POST /v1/agents/mcp/reset-choices: `claude mcp reset-project-choices` in that folder. Every approval and No there goes; each server asks again. */
export function resetChoices(folder: string): Promise<{ ok: true }> {
  return serial(async () => {
    const r = await claude(['mcp', 'reset-project-choices'], folder)
    if (r.code !== 0) throw failed(r, [], 'Claude Code didn’t reset them.')
    return { ok: true }
  })
}

// ── Signing in ───────────────────────────────────────────────────────────

/**
 * `claude mcp login <name> --no-browser` needs a terminal to take the pasted address (2.1.316: it gives up otherwise), so
 * it runs in one nobody sees, wide enough that its URL doesn't wrap. The phone opens the sign-in page; the browser
 * ends at localhost, which on a phone is nothing; the owner pastes that address and it goes in as if typed.
 */
type Login = { id: string; write: (s: string) => void; kill: () => void; output: () => string; done: Promise<number>; at: number; name: string }
const logins = new Map<string, Login>()
const LOGIN_TTL = 10 * 60_000
const LOGIN_FIRST_MS = 30_000
const LOGIN_DONE_MS = 30_000
const URL_IN_TEXT = /https?:\/\/[^\s'"<>]+/
// eslint-disable-next-line no-control-regex
const strip = (s: string) => s.replace(/\u001b\[[0-9;?]*[A-Za-z]/g, '').replace(/\r/g, '')

type PtySpawn = (file: string, args: string[], opts: { cwd: string; env: Record<string, string>; cols: number; rows: number; name: string }) => { onData(f: (d: string) => void): void; onExit(f: (e: { exitCode: number }) => void): void; write(s: string): void; kill(): void }
let ptyHook: PtySpawn | null = null
/** Tests can stand in for the terminal. */
export const usePty = (f: PtySpawn | null) => {
  ptyHook = f
}

async function loginTerminal(bin: string, args: string[], cwd: string): Promise<Login> {
  let spawnPty = ptyHook
  if (!spawnPty) {
    try {
      const pty = (await import('node-pty')).default
      spawnPty = pty.spawn as unknown as PtySpawn
    } catch {
      throw new McpRefused(409, 'no_terminal', 'This server can’t run a terminal for the sign-in. Run the command on the server instead.')
    }
  }
  const child = spawnPty(bin, args, { cwd, env: childEnv({}), cols: 2000, rows: 50, name: 'xterm-256color' })
  let out = ''
  child.onData((d) => {
    if (out.length < 256 * 1024) out += d
  })
  const done = new Promise<number>((resolve) => child.onExit((e) => resolve(e.exitCode)))
  return { id: randomBytes(12).toString('hex'), write: (s) => child.write(s), kill: () => child.kill(), output: () => strip(out), done, at: Date.now(), name: '' }
}

function reapLogins(): void {
  for (const [id, l] of logins) {
    if (Date.now() - l.at > LOGIN_TTL) {
      l.kill()
      logins.delete(id)
    }
  }
}

export type LoginStart = { ok: true; done: boolean; login?: string; url?: string; words?: string }

/** POST /v1/agents/mcp/login: starts it, and answers with the page to open, or with what was said if there's no sign-in to do. */
export async function loginStart(name: string, folder: string, cmd?: { bin: string; args: string[] }): Promise<LoginStart> {
  reapLogins()
  const bin = cmd?.bin ?? claudeBin()
  const login = await loginTerminal(bin, cmd?.args ?? ['mcp', 'login', name, '--no-browser'], folder)
  login.name = name
  const end = Date.now() + LOGIN_FIRST_MS
  let exited: number | null = null
  void login.done.then((c) => (exited = c))
  for (;;) {
    const url = URL_IN_TEXT.exec(login.output())?.[0]
    if (url) {
      logins.set(login.id, login)
      return { ok: true, done: false, login: login.id, url }
    }
    if (exited !== null) {
      const words = cliWords({ code: exited, stdout: login.output(), stderr: '' })
      if (exited === 0) return { ok: true, done: true, ...(words ? { words } : {}) }
      throw new McpRefused(502, 'cli_failed', words ?? 'The sign-in didn’t start.')
    }
    if (Date.now() >= end) {
      login.kill()
      throw new McpRefused(504, 'too_slow', 'The sign-in didn’t start in time.')
    }
    await new Promise((r) => setTimeout(r, 100))
  }
}

/** POST /v1/agents/mcp/login/finish: the address the browser ended at, typed into the sign-in that's waiting. */
export async function loginFinish(body: Record<string, unknown>): Promise<{ ok: true; words?: string }> {
  const id = body.login
  const login = typeof id === 'string' ? logins.get(id) : undefined
  if (!login) throw new McpRefused(404, 'no_login', 'That sign-in has ended. Start it again.')
  const address = body.address
  let parsed: URL | null = null
  try {
    parsed = typeof address === 'string' && address.length <= 8192 && !CONTROL.test(address) ? new URL(address.trim()) : null
  } catch {}
  // Only what a sign-in ends at: this goes into a terminal.
  if (!parsed || !['http:', 'https:'].includes(parsed.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname)) refuse('bad_address', 'That isn’t the address the sign-in ended at. It starts with http://localhost.')
  login.write(`${(address as string).trim()}\r`)
  const code = await Promise.race([login.done, new Promise<null>((r) => setTimeout(() => r(null), LOGIN_DONE_MS))])
  if (code === null) {
    const words = cliWords({ code: 1, stdout: login.output().split('\n').slice(-3).join('\n'), stderr: '' })
    throw new McpRefused(409, 'still_waiting', words ?? 'It’s still waiting. Check the address and try again.')
  }
  logins.delete(login.id)
  if (code !== 0) throw new McpRefused(502, 'cli_failed', cliWords({ code, stdout: login.output(), stderr: '' }) ?? 'The sign-in didn’t finish.')
  return { ok: true }
}

/** POST /v1/agents/mcp/login/cancel. */
export function loginCancel(body: Record<string, unknown>): { ok: true } {
  const id = body.login
  const login = typeof id === 'string' ? logins.get(id) : undefined
  login?.kill()
  if (login) logins.delete(login.id)
  return { ok: true }
}

/** POST /v1/agents/mcp/logout: `claude mcp logout`, its stored sign-in gone. */
export function logout(name: string, folder: string): Promise<{ ok: true }> {
  return serial(async () => {
    const r = await claude(['mcp', 'logout', name], folder)
    if (r.code !== 0) throw failed(r, [], 'Claude Code didn’t sign out of that server.')
    return { ok: true }
  })
}
