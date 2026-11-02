// Codex's MCP servers (docs/PROTOCOL.md, "An agent's MCP servers", "For Codex"; what differs from Claude Code: docs/DECISIONS.md, "Codex's MCP
// servers"). Codex's own CLI reads them (`codex mcp list --json`) and its own app-server does the rest, in a short-lived
// copy on stdio started for the moment: `mcpServerStatus/list` connects them and gives tools with their schemas,
// `config/value/write` writes `config.toml` with Codex's writer, keeping the rest of the file and its comments.
//
// The CLI's JSON holds env and header values, so it's read here and nothing of it leaves unfiltered. There's one
// scope: the user's `config.toml`. A trusted repository's own `.codex/config.toml` isn't read or written.

import { spawn } from 'node:child_process'
import { isDeepStrictEqual } from 'node:util'
import { isTable, own } from './agent-settings.ts'
import { codexBinary, connected } from './codex/client.ts'
import { changesWhatRuns, cliWords, commandLine, formConfig, loginStart, McpRefused, readLast, rememberLast, forgetLast, runCli, serial, type McpUndo } from './mcp-edit.ts'
import { factsOf, type ServerFacts } from './mcp-tools.ts'
import { configHash, maskArgs, maskText, maskUrl, HOME_FOLDER, type RawConfig, type ServerSetup, type Transport } from './mcp-view.ts'
import type { LiveServer, LiveStatus } from './mcp-probe.ts'
import { childEnv } from './runner.ts'
import { HOME, log } from './util.ts'
import { MACHINE } from './platform.ts'


/** One server from `codex mcp list --json`, values and all. It stays in this file. */
type Raw = { name?: unknown; enabled?: unknown; disabled_reason?: unknown; transport?: Record<string, unknown>; startup_timeout_sec?: unknown; tool_timeout_sec?: unknown; auth_status?: unknown; enabled_tools?: unknown; disabled_tools?: unknown }

type Stand = { cli?: (args: string[]) => Promise<{ code: number; stdout: string; stderr: string }>; rpc?: <T>(work: (req: Req) => Promise<T>) => Promise<T> }
type Req = (method: string, params?: unknown) => Promise<any>
let stand: Stand = {}
/** Tests stand in for the binary. */
export const useCodex = (s: Stand | null) => {
  stand = s ?? {}
  liveCache = null
}

function bin(): string {
  const b = codexBinary().path
  if (!b) throw new McpRefused(409, 'no_codex', `Codex isn’t installed on this ${MACHINE}.`)
  return b
}

const cli = (args: string[]) => (stand.cli ? stand.cli(args) : runCli(bin(), args, HOME))

const RPC_MS = 30_000

/** A short-lived app-server of Codex's own, for one piece of work. */
export function withAppServer<T>(work: (req: Req) => Promise<T>): Promise<T> {
  if (stand.rpc) return stand.rpc(work)
  return new Promise<T>((resolve, reject) => {
    const child = spawn(bin(), ['app-server'], { cwd: HOME, env: childEnv({}), stdio: ['pipe', 'pipe', 'ignore'] })
    let next = 1
    let buf = ''
    const waiting = new Map<number, { resolve: (v: any) => void; reject: (e: unknown) => void }>()
    const stop = () => {
      child.kill('SIGTERM')
      const hard = setTimeout(() => child.kill('SIGKILL'), 1000)
      hard.unref?.()
      child.once('exit', () => clearTimeout(hard))
    }
    const timer = setTimeout(() => {
      stop()
      reject(new McpRefused(504, 'too_slow', 'Codex took too long to answer.'))
    }, RPC_MS)
    const fail = () => {
      for (const w of waiting.values()) w.reject(new McpRefused(502, 'codex_failed', 'Codex stopped before it answered.'))
      waiting.clear()
    }
    child.on('error', fail)
    child.on('exit', fail)
    child.stdin.on('error', () => {})
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (d: string) => {
      buf += d
      for (let at = buf.indexOf('\n'); at >= 0; at = buf.indexOf('\n')) {
        const line = buf.slice(0, at)
        buf = buf.slice(at + 1)
        let m: any
        try {
          m = JSON.parse(line)
        } catch {
          continue
        }
        const w = typeof m?.id === 'number' ? waiting.get(m.id) : undefined
        if (!w) continue
        waiting.delete(m.id)
        // Its message can quote the file.
        if (m.error) w.reject(new McpRefused(502, 'codex_refused', typeof m.error?.message === 'string' ? maskText(m.error.message).slice(0, 240) : 'Codex refused that.'))
        else w.resolve(m.result)
      }
    })
    const req: Req = (method, params) =>
      new Promise((res, rej) => {
        const id = next++
        waiting.set(id, { resolve: res, reject: rej })
        child.stdin.write(`${JSON.stringify({ id, method, ...(params === undefined ? {} : { params }) })}\n`)
      })
    req('initialize', { clientInfo: { name: 'hypernova', version: '1' }, capabilities: { experimentalApi: true } })
      .then(() => {
        child.stdin.write(`${JSON.stringify({ method: 'initialized' })}\n`)
        return work(req)
      })
      .then(resolve, reject)
      .finally(() => {
        clearTimeout(timer)
        stop()
      })
  })
}

const NAME = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/
const SHOWN_NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
const VAR = /^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/
const namesIn = (t: unknown) => (isTable(t) ? Object.keys(t).filter((k) => VAR.test(k) && maskText(k) === k) : [])
const listIn = (t: unknown) => (Array.isArray(t) ? t.filter((k): k is string => typeof k === 'string' && VAR.test(k)) : [])

async function readAll(): Promise<Raw[]> {
  const r = await cli(['mcp', 'list', '--json'])
  if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t list its servers.')
  try {
    const all = JSON.parse(r.stdout)
    return Array.isArray(all) ? all : []
  } catch {
    throw new McpRefused(502, 'cli_failed', 'Codex’s answer couldn’t be read.')
  }
}

const transportWord = (raw: Raw): Transport | null => (raw.transport?.type === 'stdio' ? 'stdio' : raw.transport?.type === 'streamable_http' ? 'http' : null)

/** What's kept in `config.toml` for a server, values and all: what an edit starts from and Undo puts back. */
function tableOf(raw: Raw): RawConfig {
  const t = raw.transport ?? {}
  const out: RawConfig = {}
  const put = (k: string, v: unknown) => {
    if (v !== null && v !== undefined && !(Array.isArray(v) && !v.length) && !(isTable(v) && !Object.keys(v).length)) out[k] = v
  }
  if (t.type === 'stdio') {
    put('command', t.command)
    put('args', t.args)
    put('env', t.env)
    put('env_vars', t.env_vars)
    put('cwd', t.cwd)
  } else {
    put('url', t.url)
    put('bearer_token_env_var', t.bearer_token_env_var)
    put('http_headers', t.http_headers)
    put('env_http_headers', t.env_http_headers)
    put('http_headers_helper', t.http_headers_helper)
  }
  if (raw.enabled === false) out.enabled = false
  put('startup_timeout_sec', raw.startup_timeout_sec)
  put('tool_timeout_sec', raw.tool_timeout_sec)
  put('enabled_tools', raw.enabled_tools)
  put('disabled_tools', raw.disabled_tools)
  return out
}

/** The same table in the shape the form's checks know. */
function formShape(table: RawConfig): RawConfig {
  if (typeof table.command === 'string') return { type: 'stdio', command: table.command, args: table.args ?? [], ...(table.env ? { env: table.env } : {}) }
  return { type: 'http', url: table.url, ...(table.http_headers ? { headers: table.http_headers } : {}) }
}

function setupOf(raw: Raw): ServerSetup & { bearerEnv?: string; cwd?: string; toolsOn?: string[]; toolsOff?: string[]; startupTimeout?: number; toolTimeout?: number } {
  const t = raw.transport ?? {}
  const transport = transportWord(raw)
  const out: ReturnType<typeof setupOf> = { transport, envNames: [], headerNames: [], hidden: 0 }
  if (typeof t.command === 'string') out.command = maskText(t.command)
  if (Array.isArray(t.args)) out.args = maskArgs(t.args.filter((a): a is string => typeof a === 'string'))
  if (typeof t.url === 'string') out.url = maskUrl(t.url)
  out.envNames = [...namesIn(t.env), ...listIn(t.env_vars)]
  out.headerNames = [...namesIn(t.http_headers), ...namesIn(t.env_http_headers)]
  if (typeof t.bearer_token_env_var === 'string' && VAR.test(t.bearer_token_env_var)) out.bearerEnv = t.bearer_token_env_var
  if (typeof t.cwd === 'string') out.cwd = maskText(t.cwd)
  if (typeof t.http_headers_helper === 'string') out.headersHelper = maskText(t.http_headers_helper)
  const tools = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').map(maskText) : undefined)
  const on = tools(raw.enabled_tools)
  const off = tools(raw.disabled_tools)
  if (on) out.toolsOn = on
  if (off) out.toolsOff = off
  if (typeof raw.startup_timeout_sec === 'number') out.startupTimeout = raw.startup_timeout_sec
  if (typeof raw.tool_timeout_sec === 'number') {
    out.toolTimeout = raw.tool_timeout_sec
    out.timeout = Math.round(raw.tool_timeout_sec * 1000)
  }
  return out
}

const authWord = (raw: Raw) => (typeof raw.auth_status === 'string' ? raw.auth_status : undefined)

const undoFor = async (all: Raw[]): Promise<McpUndo | null> => {
  const last = readLast('codex')
  if (!last) return null
  const now = all.find((r) => r.name === last.name)
  if (!isDeepStrictEqual(now ? tableOf(now) : null, last.after)) return null
  return { op: last.op, name: last.name, scope: 'user', folder: null, needsCheck: last.before !== null && changesWhatRuns(last.after, last.before), command: last.before ? commandLine(last.before) : null }
}

/** GET /v1/agents/mcp?agent=codex. Nothing is started. */
export async function codexList() {
  const all = await readAll()
  let hidden = 0
  const servers = all.flatMap((raw) => {
    if (typeof raw.name !== 'string' || !SHOWN_NAME.test(raw.name) || maskText(raw.name) !== raw.name) {
      hidden++
      return []
    }
    const s = setupOf(raw)
    const base = s.command ? /[^/]+$/.exec(s.command)?.[0] : undefined
    let host: string | undefined
    try {
      host = s.url ? new URL(s.url).hostname : undefined
    } catch {}
    const summary = base ?? host
    return [{ name: raw.name, scope: 'user', transport: s.transport, ...(summary ? { summary } : {}), enabled: raw.enabled !== false }]
  })
  return { agent: 'codex', state: 'ok', servers, folders: [{ id: HOME_FOLDER, name: '~' }], hidden, foldersNotRead: 0, scopes: ['user'], undo: await undoFor(all) }
}

/** GET /v1/agents/mcp/server?agent=codex. */
export async function codexServer(name: string) {
  const raw = (await readAll()).find((r) => r.name === name)
  if (!raw) {
    const plugin = (await rawStatus()).find((s) => s.name === name && s.pluginId)
    if (!plugin) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    return { agent: 'codex', name, scope: 'plugin', folder: HOME_FOLDER, transport: plugin.httpOrigin ? 'http' : null, ...(plugin.httpOrigin ? { url: maskUrl(String(plugin.httpOrigin)) } : {}), envNames: [], headerNames: [], hidden: 0, editable: false, enabled: true }
  }
  const setup = setupOf(raw)
  const auth = authWord(raw)
  return { agent: 'codex', name, scope: 'user', where: '~/.codex/config.toml', ...setup, enabled: raw.enabled !== false, ...(typeof raw.disabled_reason === 'string' ? { disabledReason: maskText(raw.disabled_reason) } : {}), ...(auth ? { authStatus: auth } : {}), hash: configHash(tableOf(raw)), editable: setup.transport !== null }
}

// ── Status and tools, from a short-lived app-server ──────────────────────

const FRESH_MS = 20_000
let liveCache: { at: number; data: any[] } | null = null
let liveAsk: Promise<any[]> | null = null
export const forgetCodexLive = () => {
  liveCache = null
}

async function rawStatus(fresh = false): Promise<any[]> {
  if (!fresh && liveCache && Date.now() - liveCache.at < FRESH_MS) return liveCache.data
  liveAsk ??= withAppServer(async (req) => {
    const out: any[] = []
    let cursor: string | undefined
    for (let i = 0; i < 20; i++) {
      const r = await req('mcpServerStatus/list', { limit: 100, ...(cursor ? { cursor } : {}) })
      if (Array.isArray(r?.data)) out.push(...r.data)
      cursor = typeof r?.nextCursor === 'string' ? r.nextCursor : undefined
      if (!cursor) break
    }
    return out
  })
    .then((data) => {
      liveCache = { at: Date.now(), data }
      return data
    })
    .finally(() => {
      liveAsk = null
    })
  return liveAsk
}

function liveOf(s: any, enabled: boolean): LiveServer | null {
  if (typeof s?.name !== 'string' || !SHOWN_NAME.test(s.name)) return null
  const tools = isTable(s.tools) ? Object.values(s.tools) : []
  const status: LiveStatus = !enabled ? 'disabled' : s.toolsError ? (s.authStatus === 'notLoggedIn' ? 'needs-auth' : 'failed') : s.authStatus === 'notLoggedIn' ? 'needs-auth' : s.serverInfo || tools.length ? 'connected' : 'failed'
  const info = isTable(s.serverInfo) ? s.serverInfo : {}
  const error = typeof s.toolsError === 'string' ? maskText(s.toolsError).slice(0, 300) : undefined
  return {
    name: s.name,
    status,
    source: s.pluginId ? 'plugin' : 'user',
    ...(typeof info.name === 'string' ? { serverName: maskText(info.name) } : {}),
    ...(typeof info.version === 'string' ? { version: maskText(info.version) } : {}),
    ...(error && status === 'failed' ? { error } : {}),
    tools: factsOf({ tools, prompts: [], resources: [] }).tools.map((t) => ({ name: t.name, ...(t.annotations.readOnly ? { readOnly: true } : {}), ...(t.annotations.destructive ? { destructive: true } : {}), ...(t.annotations.openWorld ? { openWorld: true } : {}) })),
  }
}

/** GET /v1/agents/mcp/status?agent=codex. */
export async function codexStatus(fresh = false) {
  const all = await readAll()
  const off = new Set(all.filter((r) => r.enabled === false).map((r) => r.name))
  const data = await rawStatus(fresh)
  const servers = data.flatMap((s) => liveOf(s, !off.has(s?.name)) ?? [])
  // A server Codex won't start isn't in its answer.
  for (const r of all) if (typeof r.name === 'string' && !servers.some((s) => s.name === r.name) && SHOWN_NAME.test(r.name)) servers.push({ name: r.name, status: r.enabled === false ? 'disabled' : 'failed', source: 'user', tools: [] })
  return { agent: 'codex', folder: HOME_FOLDER, servers }
}

/** GET /v1/agents/mcp/tools?agent=codex: Codex's own answer has tools in full, so no talk of our own. */
export async function codexTools(name: string): Promise<{ agent: 'codex'; name: string; whyNot?: string } & Partial<ServerFacts>> {
  const none = (whyNot: string) => ({ agent: 'codex' as const, name, whyNot, tools: [], prompts: [], resources: [], moreTools: 0 })
  const raw = (await readAll()).find((r) => r.name === name)
  if (raw?.enabled === false) return none('disabled')
  const s = (await rawStatus()).find((x) => x?.name === name)
  if (!s) return none('failed')
  if (s.authStatus === 'notLoggedIn') return none('needs_auth')
  if (s.toolsError) return none('failed')
  return { agent: 'codex', name, ...factsOf({ serverInfo: s.serverInfo, tools: isTable(s.tools) ? Object.values(s.tools) : [], prompts: [], resources: Array.isArray(s.resources) ? s.resources : [] }) }
}

// ── Changes ──────────────────────────────────────────────────────────────

/** A server's table, through Codex's own writer; null takes it out. A thread already running is told to read them again if the daemon has a connection to Codex. */
async function writeTable(name: string, value: RawConfig | null): Promise<void> {
  await withAppServer((req) => req('config/value/write', { keyPath: `mcp_servers.${name}`, value, mergeStrategy: 'replace' }))
  await reloadShared()
  liveCache = null
}
async function reloadShared(): Promise<void> {
  try {
    await connected()?.call('config/mcpServer/reload')
  } catch (e) {
    log('codex mcp: reload after a change failed', (e as Error)?.message ?? '')
  }
}

/** The table a form means, over what [before] already has that the form doesn't speak of. */
function tableFromForm(body: Record<string, unknown>, before: RawConfig | null) {
  if (body.transport === 'sse') throw new McpRefused(400, 'bad_transport', 'Codex takes stdio or streamable HTTP servers.')
  const form = formConfig(body, before ? formShape(before) : null)
  const sameType = before !== null && (typeof before.command === 'string') === (body.transport === 'stdio')
  const kept: RawConfig = {}
  if (before) for (const k of ['enabled', 'startup_timeout_sec', 'tool_timeout_sec', 'enabled_tools', 'disabled_tools']) if (before[k] !== undefined) kept[k] = before[k]
  if (sameType && before) for (const k of ['env_vars', 'cwd', 'env_http_headers', 'http_headers_helper', 'bearer_token_env_var']) if (before[k] !== undefined) kept[k] = before[k]
  const out: RawConfig = { ...kept }
  if (body.transport === 'stdio') {
    out.command = form.config.command
    if (form.config.args) out.args = form.config.args
    if (form.config.env) out.env = form.config.env
  } else {
    out.url = form.config.url
    if (form.config.headers) out.http_headers = form.config.headers
    if (body.bearerEnv === null || body.bearerEnv === '') delete out.bearer_token_env_var
    else if (body.bearerEnv !== undefined) {
      if (typeof body.bearerEnv !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(body.bearerEnv)) throw new McpRefused(400, 'bad_bearer', 'That isn’t an environment variable’s name.')
      out.bearer_token_env_var = body.bearerEnv
    }
  }
  return { name: form.name, table: out }
}

const check = (needed: boolean, body: Record<string, unknown>, words: string) => {
  if (needed && body.confirmRun !== true) throw new McpRefused(403, 'confirm_run', words)
}

export function codexAdd(body: Record<string, unknown>) {
  return serial(async () => {
    const { name, table } = tableFromForm(body, null)
    if (!NAME.test(name)) throw new McpRefused(400, 'bad_name', 'A name is letters, numbers, dashes and underscores, up to 64.')
    check(changesWhatRuns(null, table), body, 'Adding this runs a program on the server. Confirm it with your fingerprint or screen lock.')
    if ((await readAll()).some((r) => r.name === name)) throw new McpRefused(409, 'exists', 'There’s already a server of that name.')
    await writeTable(name, table)
    const after = (await readAll()).find((r) => r.name === name)
    if (!after) throw new McpRefused(502, 'codex_failed', 'Codex didn’t save that server.')
    rememberLast('codex', { op: 'add', name, scope: 'user', folder: null, before: null, after: tableOf(after) })
    log(`mcp: added ${name} to codex`)
    return { ok: true, name, scope: 'user', hash: configHash(tableOf(after)) }
  })
}

export function codexEdit(body: Record<string, unknown>) {
  return serial(async () => {
    const raw = (await readAll()).find((r) => r.name === body.name)
    if (!raw) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    const before = tableOf(raw)
    if (body.expected !== configHash(before)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since this was read. Nothing was written.')
    const { name, table } = tableFromForm(body, before)
    if (!NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That name can’t be changed from here.')
    check(changesWhatRuns(before, table), body, 'This changes what runs on the server. Confirm it with your fingerprint or screen lock.')
    await writeTable(name, table)
    const after = (await readAll()).find((r) => r.name === name)
    if (!after) throw new McpRefused(502, 'codex_failed', 'Codex didn’t save that server.')
    rememberLast('codex', { op: 'edit', name, scope: 'user', folder: null, before, after: tableOf(after) })
    log(`mcp: changed ${name} in codex`)
    return { ok: true, name, scope: 'user', hash: configHash(tableOf(after)) }
  })
}

export function codexRemove(body: Record<string, unknown>) {
  return serial(async () => {
    const raw = (await readAll()).find((r) => r.name === body.name)
    if (!raw) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    const before = tableOf(raw)
    if (body.expected !== configHash(before)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since this was read. Nothing was written.')
    const name = body.name as string
    if (!NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That one can’t be changed from here.')
    await writeTable(name, null)
    rememberLast('codex', { op: 'remove', name, scope: 'user', folder: null, before, after: null })
    log(`mcp: removed ${name} from codex`)
    return { ok: true, name, scope: 'user', hash: null }
  })
}

export function codexUndo(body: Record<string, unknown>) {
  return serial(async () => {
    const last = readLast('codex')
    if (!last) throw new McpRefused(409, 'nothing_to_undo', 'There’s no change here to undo.')
    const now = (await readAll()).find((r) => r.name === last.name)
    if (!isDeepStrictEqual(now ? tableOf(now) : null, last.after)) throw new McpRefused(409, 'file_changed', 'That server changed on the server since. Nothing was written.')
    check(last.before !== null && changesWhatRuns(last.after, last.before), body, 'Putting this back runs a program on the server. Confirm it with your fingerprint or screen lock.')
    await writeTable(last.name, last.before)
    forgetLast('codex')
    log(`mcp: undid the ${last.op} of ${last.name} in codex`)
    return { ok: true, name: last.name, scope: 'user', op: last.op }
  })
}

/** On or off: Codex's `enabled` key, for every project, since it has no per-folder list. */
export function codexToggle(name: string, enabled: boolean) {
  return serial(async () => {
    if (!NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That one can’t be changed from here.')
    if (!(await readAll()).some((r) => r.name === name)) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    await withAppServer((req) => req('config/value/write', { keyPath: `mcp_servers.${name}.enabled`, value: enabled, mergeStrategy: 'upsert' }))
    await reloadShared()
    liveCache = null
    return { ok: true, name, enabled }
  })
}

/** Codex's own reload for the daemon's connection, if there is one, then a fresh look from a new process. */
export async function codexReconnect(name: string) {
  await reloadShared()
  const status = await codexStatus(true)
  return { ok: true, server: status.servers.find((s) => s.name === name) ?? null }
}

export const codexLogin = (name: string) => loginStart(name, HOME, { bin: bin(), args: ['mcp', 'login', name, '--no-browser'] })

export async function codexLogout(name: string) {
  const r = await cli(['mcp', 'logout', name])
  if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t sign out of that server.')
  liveCache = null
  return { ok: true }
}
