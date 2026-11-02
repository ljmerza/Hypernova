// The phone's MCP routes (docs/specs/spec-mcp-manage.md). A paired phone only, each of them.

import type { ServerResponse } from 'node:http'
import { claudeConfigFile } from './agent-settings.ts'
import { LIVE_NAME, liveServers, type LiveServer } from './mcp-probe.ts'
import { approvalOf, claudeList, configHash, findConfig, folderId, HOME_FOLDER, knownFolders, projectServers, readClaudeConfig, setupOf, whereOf, type Scope } from './mcp-view.ts'
import { dirname } from 'node:path'

/** Something for the phone to show: its own words, never a file's or a command's. */
export class McpRefused extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export type McpDeps = { folders: () => string[] }

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

const param = (url: URL, k: string) => url.searchParams.get(k)

const send = (res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, body: unknown) => json(res, 200, body)

/** Handles [path] if it's one of these. A refusal is thrown; http.ts answers it as its own. */
export async function mcpRoute(path: string, m: string, url: URL, res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, deps: McpDeps): Promise<boolean> {
  if (path === '/v1/agents/mcp' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') throw new McpRefused(409, 'not_yet', 'Codex’s MCP servers aren’t available here yet.')
    send(res, json, await claudeList(deps.folders()))
    return true
  }
  if (path === '/v1/agents/mcp/status' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') throw new McpRefused(409, 'not_yet', 'Codex’s MCP servers aren’t available here yet.')
    const id = param(url, 'folder') || HOME_FOLDER
    const folder = await folderPath(id, deps)
    const held = await heldBack(folder)
    const servers: LiveServer[] = await liveServers(folder, held.waiting, held.rejected, param(url, 'fresh') === '1')
    send(res, json, { agent, folder: id, servers })
    return true
  }
  if (path === '/v1/agents/mcp/server' && m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    if (agent === 'codex') throw new McpRefused(409, 'not_yet', 'Codex’s MCP servers aren’t available here yet.')
    send(res, json, await claudeServer(param(url, 'name') ?? '', param(url, 'scope') ?? '', param(url, 'folder'), deps))
    return true
  }
  return false
}

