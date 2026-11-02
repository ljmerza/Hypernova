// The phone's MCP routes (docs/PROTOCOL.md, "Routes: agents' MCP servers"). A paired phone only, each of them.

import type { ServerResponse } from 'node:http'
import { claudeConfigFile, isTable, own, repoRoot } from './agent-settings.ts'
import { TalkFailed, talkTo, type TalkTrouble } from './mcp-client.ts'
import { factsOf, type ServerFacts } from './mcp-tools.ts'
import { codexAdd, codexEdit, codexList, codexLogin, codexLogout, codexReconnect, codexRemove, codexServer, codexStatus, codexToggle, codexTools, codexUndo } from './mcp-codex.ts'
import { addServer, editServer, loginCancel, loginFinish, loginStart, logout, McpRefused, removeServer, resetChoices, scopeOf, setApproval, undoLast, undoOffer } from './mcp-edit.ts'
import { forgetLive, LIVE_NAME, liveServers, reconnectServer, toggleServer, type LiveServer } from './mcp-probe.ts'
import { approvalOf, claudeList, configHash, findConfig, folderId, HOME_FOLDER, knownFolders, projectServers, readClaudeConfig, setupOf, whereOf, type Scope } from './mcp-view.ts'
import { dirname } from 'node:path'

export { McpRefused }

export type McpDeps = { folders: () => string[]; body: () => Promise<any>; changed: (agent: 'claude' | 'codex') => void }

const SCOPES: readonly string[] = ['user', 'local', 'project', 'settings']
const LIVE_SCOPES: readonly string[] = ['plugin', 'managed', 'enterprise', 'claudeai', 'dynamic', 'agent', 'unknown']

export function agentOf(v: string | null | undefined): 'claude' | 'codex' {
  if (v === 'claude' || v === 'codex') return v
  throw new McpRefused(400, 'bad_agent', 'That isn’t an agent with MCP servers here.')
}

/** A folder's path from its id: one the daemon knows, or a parent of one (a session's `.mcp.json` can sit above it). */
export async function folderPath(id: string | null | undefined, deps: McpDeps): Promise<string> {
  const { root } = await readClaudeConfig(claudeConfigFile())
  const known = knownFolders(root, deps.folders())
  const want = id || HOME_FOLDER
  const hit = known.get(want)
  if (hit) return hit.path
  for (const { path } of known.values()) {
    for (let d = dirname(path), i = 0; i < 64 && dirname(d) !== d; d = dirname(d), i++) if (folderId(d) === want) return d
  }
  throw new McpRefused(404, 'no_folder', 'That folder isn’t one this server knows.')
}

/** A folder's project servers that mustn't start, and those it has turned away. */
export async function heldBack(folder: string): Promise<{ waiting: string[]; rejected: string[] }> {
  const { root } = await readClaudeConfig(claudeConfigFile())
  const { servers } = await projectServers(folder)
  const waiting: string[] = []
  const rejected: string[] = []
  for (const name of servers.keys()) {
    const a = approvalOf(name, folder, root)
    if (a === 'pending') waiting.push(name)
    else if (a === 'rejected') rejected.push(name)
  }
  return { waiting, rejected }
}

/** GET /v1/agents/mcp/server for Claude Code: where it's defined, how it's set up. Never a secret. */
export async function claudeServer(name: string, scope: string, folderIdValue: string | null, deps: McpDeps) {
  if (!LIVE_NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That isn’t a server’s name.')
  if (LIVE_SCOPES.includes(scope)) {
    const folder = await folderPath(folderIdValue, deps)
    const held = await heldBack(folder)
    const live = (await liveServers(folder, held.waiting, held.rejected)).find((s) => s.name === name)
    if (!live) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
    const setup = live.setup ?? setupOf(null)
    return { agent: 'claude', name, scope, folder: folderIdValue ?? HOME_FOLDER, ...setup, source: live.source, editable: false }
  }
  if (!SCOPES.includes(scope)) throw new McpRefused(400, 'bad_scope', 'That isn’t a place a server is kept.')
  const needsFolder = scope === 'local' || scope === 'project'
  const folder = needsFolder ? await folderPath(folderIdValue, deps) : null
  const found = await findConfig(name, scope as Scope, folder)
  if (!found) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
  const { root } = await readClaudeConfig(claudeConfigFile())
  const project = folder === null ? undefined : (knownFolders(root, [...deps.folders(), folder]).get(folderId(folder))?.name ?? undefined)
  const setup = setupOf(found.config)
  return {
    agent: 'claude',
    name,
    scope,
    ...(needsFolder ? { folder: folderIdValue ?? HOME_FOLDER, ...(project ? { project } : {}) } : {}),
    where: whereOf(scope as Scope, project),
    ...setup,
    ...(scope === 'project' && folder ? { approval: approvalOf(name, folder, root) } : {}),
    hash: configHash(found.config),
    // settings.json's servers are in a file Claude Code's own commands don't change.
    editable: scope !== 'settings' && setup.transport !== null && setup.transport !== 'ws',
  }
}

/** Whether Claude Code has [name] switched off in [folder]: its project entry's `disabledMcpServers`. */
export async function isDisabled(root: Record<string, unknown> | null, folder: string, name: string): Promise<boolean> {
  const projects = root ? own(root, 'projects') : undefined
  if (!isTable(projects)) return false
  for (const key of [folder, await repoRoot(folder).catch(() => null)]) {
    const entry = key ? own(projects, key) : undefined
    const list = isTable(entry) ? own(entry, 'disabledMcpServers') : undefined
    if (Array.isArray(list) && list.includes(name)) return true
  }
  return false
}
type ToolsWhyNot = 'approval' | 'disabled' | 'no_direct' | TalkTrouble
const TOOLS_FRESH_MS = 60_000
const toolsCache = new Map<string, { at: number; facts: ServerFacts }>()
/** Tests start from nothing. */
export const forgetTools = () => toolsCache.clear()

/**
 * GET /v1/agents/mcp/tools for Claude Code: a server's tools in full, from the server (mcp-client.ts). Never for one
 * that's off or that nobody approved: seeing its tools isn't a way round that. A plugin's or an organization's isn't
 * reachable from here (its env isn't in any file this reads), so the status's names are all there is.
 */
export async function claudeTools(name: string, scope: string, id: string | null, deps: McpDeps): Promise<{ agent: 'claude'; name: string; whyNot?: ToolsWhyNot } & Partial<ServerFacts>> {
  const none = (whyNot: ToolsWhyNot) => ({ agent: 'claude' as const, name, whyNot, tools: [], prompts: [], resources: [], moreTools: 0 })
  if (!LIVE_NAME.test(name)) throw new McpRefused(400, 'bad_name', 'That isn’t a server’s name.')
  if (LIVE_SCOPES.includes(scope)) return none('no_direct')
  if (!SCOPES.includes(scope)) throw new McpRefused(400, 'bad_scope', 'That isn’t a place a server is kept.')
  const folder = await folderPath(id, deps)
  const found = await findConfig(name, scope as Scope, scope === 'local' || scope === 'project' ? folder : null)
  if (!found) throw new McpRefused(404, 'no_server', 'That server isn’t there any more.')
  const { root } = await readClaudeConfig(claudeConfigFile())
  if (scope === 'project' && approvalOf(name, folder, root) !== 'approved') return none('approval')
  if (await isDisabled(root, folder, name)) return none('disabled')
  if (!isTable(found.config)) return none('no_direct')
  const key = `${name}\0${folder}\0${configHash(found.config)}`
  const hit = toolsCache.get(key)
  if (hit && Date.now() - hit.at < TOOLS_FRESH_MS) return { agent: 'claude', name, ...hit.facts }
  try {
    const facts = factsOf(await talkTo(found.config, folder))
    toolsCache.set(key, { at: Date.now(), facts })
    return { agent: 'claude', name, ...facts }
  } catch (e) {
    return none(e instanceof TalkFailed ? e.why : 'failed')
  }
}

const param = (url: URL, k: string) => url.searchParams.get(k)

const send = (res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, body: unknown) => json(res, 200, body)

/** Handles [path] if it's one of these. A refusal is thrown; http.ts answers it as its own. */
export async function mcpRoute(path: string, m: string, url: URL, res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, deps: McpDeps): Promise<boolean> {
  if (path === '/v1/agents/mcp' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') {
      send(res, json, await codexList())
      return true
    }
    const list = await claudeList(deps.folders())
    const { root } = await readClaudeConfig(claudeConfigFile())
    const ids = (p: string | null) => (p === null ? null : folderId(p))
    void root
    send(res, json, { ...list, scopes: ['user', 'project', 'local'], undo: await undoOffer(ids) })
    return true
  }
  if (path === '/v1/agents/mcp/status' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') {
      send(res, json, await codexStatus(param(url, 'fresh') === '1'))
      return true
    }
    const id = param(url, 'folder') || HOME_FOLDER
    const folder = await folderPath(id, deps)
    const held = await heldBack(folder)
    const servers: LiveServer[] = await liveServers(folder, held.waiting, held.rejected, param(url, 'fresh') === '1')
    send(res, json, { agent, folder: id, servers })
    return true
  }
  if (path === '/v1/agents/mcp/server' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') {
      send(res, json, await codexServer(param(url, 'name') ?? ''))
      return true
    }
    send(res, json, await claudeServer(param(url, 'name') ?? '', param(url, 'scope') ?? '', param(url, 'folder'), deps))
    return true
  }
  if (path === '/v1/agents/mcp/tools' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') {
      send(res, json, await codexTools(param(url, 'name') ?? ''))
      return true
    }
    send(res, json, await claudeTools(param(url, 'name') ?? '', param(url, 'scope') ?? '', param(url, 'folder'), deps))
    return true
  }
  return writeRoute(path, m, res, json, deps)
}

/** The routes that change something. Each answers once it's done, and tells the other phones. */
async function writeRoute(path: string, m: string, res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, deps: McpDeps): Promise<boolean> {
  const routes: Record<string, (b: Record<string, unknown>) => Promise<unknown>> = {
    'POST /v1/agents/mcp/servers': async (b) => addServer(b, scopeOf(b.scope), await folderOf(b, deps)),
    'PUT /v1/agents/mcp/servers': async (b) => editServer(b, scopeOf(b.scope), await folderOf(b, deps)),
    'POST /v1/agents/mcp/servers/remove': async (b) => removeServer(b, scopeOf(b.scope), await folderOf(b, deps)),
    'POST /v1/agents/mcp/undo': (b) => undoLast(b),
    'POST /v1/agents/mcp/toggle': async (b) => {
      const name = liveName(b)
      if (typeof b.enabled !== 'boolean') throw new McpRefused(400, 'bad_request', 'Say on or off.')
      const folder = await folderPath(b.folder as string | undefined, deps)
      const held = await heldBack(folder)
      if (held.waiting.includes(name) || held.rejected.includes(name)) throw new McpRefused(409, 'needs_approval', 'Approve this one first: it isn’t running to switch off or on.')
      await toggleServer(folder, held.waiting, name, b.enabled)
      return { ok: true, name, enabled: b.enabled }
    },
    'POST /v1/agents/mcp/reconnect': async (b) => {
      const name = liveName(b)
      const folder = await folderPath(b.folder as string | undefined, deps)
      const held = await heldBack(folder)
      if (held.waiting.includes(name) || held.rejected.includes(name)) throw new McpRefused(409, 'needs_approval', 'Approve this one first.')
      return { ok: true, server: await reconnectServer(folder, held.waiting, name) }
    },
    'POST /v1/agents/mcp/approval': async (b) => setApproval(b, await folderPath(b.folder as string | undefined, deps)),
    'POST /v1/agents/mcp/reset-choices': async (b) => resetChoices(await folderPath(b.folder as string | undefined, deps)),
    'POST /v1/agents/mcp/login': async (b) => loginStart(liveName(b), await folderPath(b.folder as string | undefined, deps)),
    'POST /v1/agents/mcp/login/finish': (b) => loginFinish(b),
    'POST /v1/agents/mcp/login/cancel': async (b) => loginCancel(b),
    'POST /v1/agents/mcp/logout': async (b) => logout(liveName(b), await folderPath(b.folder as string | undefined, deps)),
  }
  const route = routes[`${m} ${path}`]
  if (!route) return false
  const b = await deps.body()
  if (!isTable(b)) throw new McpRefused(400, 'bad_request', 'Send what to change.')
  const agent = agentOf(typeof b.agent === 'string' ? b.agent : null)
  const out = agent === 'codex' ? await codexRoute(path, m, b) : await route(b)
  forgetLive()
  forgetTools()
  deps.changed(agent)
  json(res, 200, out)
  return true
}

/** The same routes for Codex: one scope, no folders, no approvals. */
async function codexRoute(path: string, m: string, b: Record<string, unknown>): Promise<unknown> {
  switch (`${m} ${path}`) {
    case 'POST /v1/agents/mcp/servers':
      return codexAdd(b)
    case 'PUT /v1/agents/mcp/servers':
      return codexEdit(b)
    case 'POST /v1/agents/mcp/servers/remove':
      return codexRemove(b)
    case 'POST /v1/agents/mcp/undo':
      return codexUndo(b)
    case 'POST /v1/agents/mcp/toggle':
      if (typeof b.enabled !== 'boolean') throw new McpRefused(400, 'bad_request', 'Say on or off.')
      return codexToggle(liveName(b), b.enabled)
    case 'POST /v1/agents/mcp/reconnect':
      return codexReconnect(liveName(b))
    case 'POST /v1/agents/mcp/login':
      return codexLogin(liveName(b))
    case 'POST /v1/agents/mcp/login/finish':
      return loginFinish(b)
    case 'POST /v1/agents/mcp/login/cancel':
      return loginCancel(b)
    case 'POST /v1/agents/mcp/logout':
      return codexLogout(liveName(b))
    default:
      throw new McpRefused(409, 'not_for_codex', 'Codex has no such thing: its servers have no approvals, and no folders.')
  }
}

const liveName = (b: Record<string, unknown>) => {
  if (typeof b.name !== 'string' || !LIVE_NAME.test(b.name)) throw new McpRefused(400, 'bad_name', 'That isn’t a server’s name.')
  return b.name
}
const folderOf = async (b: Record<string, unknown>, deps: McpDeps) => (b.scope === 'user' ? null : folderPath(typeof b.folder === 'string' ? b.folder : null, deps))

