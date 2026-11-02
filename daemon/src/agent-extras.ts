// An agent's plugins and skills from a paired phone (docs/PROTOCOL.md, "An agent's plugins" and "An agent's skills"; docs/DECISIONS.md, "Plugins from
// the phone" and "Skills from the phone"). Thin on purpose: each action is the agent's own command or its app-server's call,
// and what comes back is filtered to named fields, because `claude plugin list --json` carries a plugin's whole MCP setup.
//
// Installing a plugin or adding a marketplace is someone else's code on the server, so the phone shows the source
// and asks for the fingerprint; the server sees that claim (`confirmRun`) and refuses without it. Enabling, disabling and
// uninstalling run nothing at that moment (mcp-extras-e2e.test.ts watches a plugin's hook for it).

import type { ServerResponse } from 'node:http'
import { isTable } from './agent-settings.ts'
import { claudeEnv, claudeRemove, claudeSet, claudeUndo, claudeUndoOffer, codexEnv, codexRemove, codexSet, codexUndo } from './agent-env.ts'
import { cliWords, McpRefused, runCli, serial } from './mcp-edit.ts'
import { codexBinary } from './codex/client.ts'
import { withAppServer } from './mcp-codex.ts'
import { askProcess } from './mcp-probe.ts'
import { folderPath, heldBack, type McpDeps, agentOf } from './mcp-routes.ts'
import { claudeConfigFile } from './agent-settings.ts'
import { folderId, HOME_FOLDER, knownFolders, maskText, maskUrl, readClaudeConfig } from './mcp-view.ts'
import { findClaude, HOME, log } from './util.ts'
import { MACHINE } from './platform.ts'

const PLUGIN_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?:@[A-Za-z0-9][A-Za-z0-9._/-]{0,127})?$/
const MARKET_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const SKILL_NAME = /^[\p{L}\p{N}][\p{L}\p{N} _.:/-]{0,127}$/u
/** A marketplace's source: `owner/repo`, a URL or a path. It goes on a command line as one argument, so never anything that reads as an option. */
const SOURCE = /^[^\s\u0000-\u001f-][^\s\u0000-\u001f]{0,1023}$/ // eslint-disable-line no-control-regex
const SCOPES = ['user', 'project', 'local']
const INSTALL_MS = 180_000
const LIST_MS = 60_000
const AVAILABLE_MAX = 100
const TEXT_MAX = 1000

const clip = (s: string, max = TEXT_MAX) => (s.length > max ? `${s.slice(0, max - 1)}…` : s)
const text = (v: unknown, max = TEXT_MAX) => (typeof v === 'string' && v.trim() ? clip(maskText(v.trim()), max) : undefined)
const opt = <K extends string>(k: K, v: unknown, max = TEXT_MAX) => {
  const t = text(v, max)
  return t === undefined ? {} : ({ [k]: t } as Record<K, string>)
}

type Cli = { code: number; stdout: string; stderr: string }
let stand: ((agent: string, args: string[], cwd: string, input?: string) => Promise<Cli>) | null = null
/** Tests stand in for both binaries. */
export const useExtrasCli = (f: typeof stand) => {
  stand = f
  availableCache.clear()
}

function binOf(agent: 'claude' | 'codex'): string {
  const b = agent === 'claude' ? findClaude() : codexBinary().path
  if (!b) throw new McpRefused(409, agent === 'claude' ? 'no_claude' : 'no_codex', `${agent === 'claude' ? 'Claude Code' : 'Codex'} isn’t installed on this ${MACHINE}.`)
  return b
}
const run = (agent: 'claude' | 'codex', args: string[], cwd: string, input?: string, timeout = LIST_MS) => (stand ? stand(agent, args, cwd, input) : runCli(binOf(agent), args, cwd, {}, input, timeout))

/** The JSON a `--json` command printed: everything, or its last line that is an object. */
function jsonOf(out: string): any {
  try {
    return JSON.parse(out)
  } catch {
    for (const line of out.split('\n').reverse()) {
      if (!line.trim().startsWith('{')) continue
      try {
        return JSON.parse(line)
      } catch {}
    }
    return null
  }
}

/** A change's answer, in a line: a refusal says why, cleaned of anything that looks secret. */
function outcome(r: Cli, fallback: string): { message?: string; raw: any } {
  const j = jsonOf(r.stdout)
  if (r.code !== 0 || (j && typeof j.outcome === 'string' && j.outcome !== 'ok')) {
    if (j?.shownCommand) return { raw: j }
    throw new McpRefused(502, 'cli_failed', text(j?.message, 300) ?? cliWords(r) ?? fallback)
  }
  return { raw: j, ...opt('message', j?.message, 400) }
}

const scopeArg = (v: unknown) => {
  if (typeof v !== 'string' || !SCOPES.includes(v)) throw new McpRefused(400, 'bad_scope', 'That isn’t a place a plugin can go.')
  return v
}
const pluginArg = (v: unknown) => {
  if (typeof v !== 'string' || !PLUGIN_ID.test(v)) throw new McpRefused(400, 'bad_plugin', 'That isn’t a plugin’s name.')
  return v
}
const marketArg = (v: unknown) => {
  if (typeof v !== 'string' || !MARKET_NAME.test(v)) throw new McpRefused(400, 'bad_marketplace', 'That isn’t a marketplace’s name.')
  return v
}
const confirmed = (b: Record<string, unknown>, what: string) => {
  if (b.confirmRun !== true) throw new McpRefused(403, 'confirm_run', `${what} runs someone else’s code on the server. Confirm it with your fingerprint or screen lock.`)
}

async function foldersOf(deps: McpDeps) {
  const { root } = await readClaudeConfig(claudeConfigFile())
  return [...knownFolders(root, deps.folders())].map(([id, f]) => ({ id, name: f.name }))
}

// ── Plugins: Claude Code ─────────────────────────────────────────────────

function claudePluginRow(p: any) {
  const id = typeof p?.id === 'string' ? p.id : ''
  if (!PLUGIN_ID.test(id)) return []
  const [name, marketplace] = id.split('@')
  return [{
    id,
    name,
    ...opt('marketplace', marketplace),
    ...opt('version', p.version, 80),
    scope: typeof p.scope === 'string' ? clip(p.scope, 32) : 'user',
    enabled: p.enabled === true,
    hasConfig: p.hasUserConfig === true,
    // Names only: its `mcpServers` carries commands and env.
    mcpServers: isTable(p.mcpServers) ? Object.keys(p.mcpServers).flatMap((n) => text(n, 128) ?? []) : [],
    ...opt('problem', p.errors?.[0]?.message ?? p.error, 300),
  }]
}

function marketRow(m: any) {
  const name = typeof m?.name === 'string' ? m.name : ''
  if (!MARKET_NAME.test(name)) return []
  const where = m.repo ?? m.url ?? m.path ?? m.root ?? m.marketplaceSource?.source
  const type = m.source ?? m.marketplaceSource?.sourceType
  return [{ name, ...opt('type', type, 40), ...(typeof where === 'string' ? { where: clip(maskUrl(where)) } : {}) }]
}

async function claudePlugins(folder: string, deps: McpDeps) {
  const list = await run('claude', ['plugin', 'list', '--json'], folder)
  if (list.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(list) ?? 'Claude Code didn’t list its plugins.')
  const markets = await run('claude', ['plugin', 'marketplace', 'list', '--json'], folder)
  const plugins = jsonOf(list.stdout)
  const m = jsonOf(markets.stdout)
  return { agent: 'claude', plugins: (Array.isArray(plugins) ? plugins : []).flatMap(claudePluginRow), marketplaces: (Array.isArray(m) ? m : []).flatMap(marketRow), scopes: SCOPES, folders: await foldersOf(deps) }
}

const AVAILABLE_FRESH = 5 * 60_000
const availableCache = new Map<string, { at: number; rows: any[] }>()

async function allAvailable(agent: 'claude' | 'codex', folder: string): Promise<any[]> {
  const hit = availableCache.get(`${agent}\0${folder}`)
  if (hit && Date.now() - hit.at < AVAILABLE_FRESH) return hit.rows
  const r = await run(agent, ['plugin', 'list', '--available', '--json'], folder, undefined, INSTALL_MS)
  if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'That list couldn’t be read.')
  const j = jsonOf(r.stdout)
  const rows = Array.isArray(j?.available) ? j.available : []
  availableCache.set(`${agent}\0${folder}`, { at: Date.now(), rows })
  return rows
}

/** Where a plugin comes from, as words for the confirm: a path, a repository, a package, whatever kind its marketplace names. */
function sourceWords(s: unknown): string | undefined {
  if (typeof s === 'string') return clip(maskUrl(s))
  if (!isTable(s)) return undefined
  const parts = ['source', 'type', 'repo', 'url', 'path', 'package', 'ref', 'version'].flatMap((k) => (typeof s[k] === 'string' ? [maskUrl(s[k] as string)] : []))
  return parts.length ? clip(parts.join(' · ')) : undefined
}

async function availablePlugins(agent: 'claude' | 'codex', folder: string, q: string, market: string | null) {
  const rows = await allAvailable(agent, folder)
  const needle = q.trim().toLowerCase()
  const hits = rows.filter((p) => (!market || p.marketplaceName === market) && (!needle || `${p.pluginId ?? ''} ${p.description ?? ''} ${p.interface?.shortDescription ?? ''}`.toLowerCase().includes(needle)))
  return {
    agent,
    total: hits.length,
    plugins: hits.slice(0, AVAILABLE_MAX).flatMap((p) => {
      const id = typeof p?.pluginId === 'string' ? p.pluginId : typeof p?.id === 'string' ? p.id : ''
      if (!PLUGIN_ID.test(id)) return []
      return [{ id, ...opt('description', p.description ?? p.interface?.shortDescription, 400), ...opt('marketplace', p.marketplaceName ?? id.split('@')[1]), ...opt('version', p.version ?? p.localVersion, 80), ...(sourceWords(p.source) ? { source: sourceWords(p.source) } : {}), ...(sourceWords(p.marketplaceSource) ? { marketplaceSource: sourceWords(p.marketplaceSource) } : {}) }]
    }),
  }
}

/** `claude plugin details` is text: its inventory lines read into lists. */
export function parseDetails(out: string) {
  const parts: Record<string, string[]> = { skills: [], agents: [], hooks: [], mcpServers: [], lspServers: [], commands: [] }
  const keys: Record<string, string> = { Skills: 'skills', Agents: 'agents', Hooks: 'hooks', 'MCP servers': 'mcpServers', 'LSP servers': 'lspServers', Commands: 'commands' }
  let tokens: number | undefined
  let description: string | undefined
  for (const raw of out.split('\n')) {
    const line = raw.trim()
    const m = /^(Skills|Agents|Hooks|MCP servers|LSP servers|Commands)\s*\((\d+)\)\s*(.*)$/.exec(line)
    if (m) parts[keys[m[1]!]!] = m[3]!.replace(/\s{2,}\(.*\)\s*$/, '').split(',').flatMap((n) => text(n, 128) ?? [])
    const t = /^Always-on:\s*~?(\d+)\s*tok/.exec(line)
    if (t) tokens = Number(t[1])
    const d = /^Description:\s*(.+)$/.exec(line)
    if (d) description = text(d[1], 600)
  }
  return { ...parts, ...(tokens === undefined ? {} : { alwaysOnTokens: tokens }), ...(description ? { description } : {}) }
}

/** A plugin's options: a sensitive one is write-only, and says only that it's set. */
function optionsOf(j: any) {
  const schema = isTable(j?.schema) ? j.schema : {}
  const configured = new Set(Array.isArray(j?.configured) ? j.configured : [])
  return Object.entries(schema).flatMap(([key, raw]) => {
    if (!/^[A-Za-z0-9_.-]{1,128}$/.test(key) || !isTable(raw)) return []
    const sensitive = raw.sensitive === true
    const value = !sensitive && isTable(j?.inputs) ? j.inputs[key] : undefined
    return [{ key, sensitive, set: configured.has(key), ...opt('title', raw.title, 120), ...opt('description', raw.description, 400), ...(typeof value === 'string' && value ? opt('value', value) : {}) }]
  })
}

async function claudeDetail(id: string, folder: string) {
  const d = await run('claude', ['plugin', 'details', id], folder)
  if (d.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(d) ?? 'Claude Code didn’t say what that plugin brings.')
  const c = await run('claude', ['plugin', 'configure', id, '--json'], folder)
  return { agent: 'claude', id, ...parseDetails(d.stdout), options: c.code === 0 ? optionsOf(jsonOf(c.stdout)) : [] }
}

// ── Plugins: Codex ───────────────────────────────────────────────────────

async function codexPlugins(deps: McpDeps) {
  const list = await run('codex', ['plugin', 'list', '--json'], HOME)
  if (list.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(list) ?? 'Codex didn’t list its plugins.')
  const markets = await run('codex', ['plugin', 'marketplace', 'list', '--json'], HOME)
  const j = jsonOf(list.stdout)
  const m = jsonOf(markets.stdout)
  void deps
  return {
    agent: 'codex',
    plugins: (Array.isArray(j?.installed) ? j.installed : []).flatMap((p: any) => {
      const id = typeof p?.pluginId === 'string' ? p.pluginId : ''
      if (!PLUGIN_ID.test(id)) return []
      return [{ id, name: text(p.name, 128) ?? id, ...opt('marketplace', p.marketplaceName), ...opt('version', p.version, 80), scope: 'user', enabled: p.enabled === true, hasConfig: false, mcpServers: [], ...(sourceWords(p.source) ? { source: sourceWords(p.source) } : {}) }]
    }),
    marketplaces: (Array.isArray(m?.marketplaces) ? m.marketplaces : []).flatMap(marketRow),
    scopes: ['user'],
    folders: [{ id: HOME_FOLDER, name: '~' }],
  }
}

async function codexDetail(id: string) {
  const [pluginName, marketplace] = id.split('@')
  return withAppServer(async (req) => {
    const list = await req('plugin/list', {})
    const market = (Array.isArray(list?.marketplaces) ? list.marketplaces : []).find((x: any) => x?.name === marketplace)
    if (!market?.path) throw new McpRefused(404, 'no_plugin', 'That plugin isn’t there any more.')
    const r = await req('plugin/read', { marketplacePath: market.path, pluginName })
    const p = r?.plugin ?? r ?? {}
    const names = (v: unknown, k = 'name') => (Array.isArray(v) ? v.flatMap((x) => text(typeof x === 'string' ? x : isTable(x) ? x[k] : null, 128) ?? []) : [])
    return { agent: 'codex', id, skills: names(p.skills), agents: [], hooks: names(p.hooks, 'eventName'), mcpServers: names(p.mcpServers), lspServers: [], commands: [], apps: names(p.apps), ...opt('description', p.description ?? p.summary?.interface?.shortDescription, 600), options: [] }
  })
}

// ── Changes ──────────────────────────────────────────────────────────────

/** A plugin that installs by running a command needs that command shown and accepted: Claude Code reports it, and takes its hash back. Only reasoned about: no such plugin was to hand. */
const needsAccept = (j: any) => {
  const c = j?.shownCommand
  if (!c) return null
  return { ok: false, needsAccept: { command: text(c.command ?? c.text ?? '', 2000) ?? '', sha256: typeof c.sha256 === 'string' ? c.sha256 : '' } }
}
const acceptArgs = (b: Record<string, unknown>) => {
  if (b.acceptCommand === undefined) return []
  if (typeof b.acceptCommand !== 'string' || !/^[0-9a-f]{64}$/.test(b.acceptCommand)) throw new McpRefused(400, 'bad_accept', 'That isn’t the hash of a command it showed.')
  return ['--accept-command', b.acceptCommand]
}

type Route = (b: Record<string, unknown>, deps: McpDeps) => Promise<unknown>

const whereFor = async (b: Record<string, unknown>, deps: McpDeps) => (b.scope === 'user' ? HOME : folderPath(typeof b.folder === 'string' ? b.folder : null, deps))

const claudeRoutes: Record<string, Route> = {
  '/install': async (b, deps) => {
    const id = pluginArg(b.plugin)
    const scope = scopeArg(b.scope)
    confirmed(b, 'Installing a plugin')
    const r = await run('claude', ['plugin', 'install', '--json', '--scope', scope, ...acceptArgs(b), id], await whereFor(b, deps), undefined, INSTALL_MS)
    const o = outcome(r, 'Claude Code didn’t install that plugin.')
    log(`plugins: installed ${id} (${scope})`)
    return needsAccept(o.raw) ?? { ok: true, id, ...(o.message ? { message: o.message } : {}) }
  },
  '/uninstall': async (b, deps) => {
    const id = pluginArg(b.plugin)
    const scope = scopeArg(b.scope)
    const r = await run('claude', ['plugin', 'uninstall', '--json', '--scope', scope, ...(b.keepData === true ? ['--keep-data'] : []), id], await whereFor(b, deps), undefined, INSTALL_MS)
    const o = outcome(r, 'Claude Code didn’t uninstall that plugin.')
    log(`plugins: uninstalled ${id} (${scope})`)
    return { ok: true, id, ...(o.message ? { message: o.message } : {}) }
  },
  '/toggle': async (b, deps) => {
    const id = pluginArg(b.plugin)
    const scope = scopeArg(b.scope)
    if (typeof b.enabled !== 'boolean') throw new McpRefused(400, 'bad_request', 'Say on or off.')
    const o = outcome(await run('claude', ['plugin', b.enabled ? 'enable' : 'disable', '--json', '--scope', scope, id], await whereFor(b, deps)), 'Claude Code didn’t change that plugin.')
    return { ok: true, id, enabled: b.enabled, ...(o.message ? { message: o.message } : {}) }
  },
  '/update': async (b, deps) => {
    const id = pluginArg(b.plugin)
    const scope = typeof b.scope === 'string' ? scopeArg(b.scope) : 'user'
    if (b.acceptCommand !== undefined) confirmed(b, 'That command')
    const o = outcome(await run('claude', ['plugin', 'update', '--json', '--scope', scope, ...acceptArgs(b), id], await whereFor({ ...b, scope }, deps), undefined, INSTALL_MS), 'Claude Code didn’t update that plugin.')
    log(`plugins: updated ${id}`)
    return needsAccept(o.raw) ?? { ok: true, id, ...(o.message ? { message: o.message } : {}), ...opt('newVersion', o.raw?.newVersion, 80) }
  },
  '/configure': async (b, deps) => {
    const id = pluginArg(b.plugin)
    if (!isTable(b.values) || !Object.keys(b.values).length) throw new McpRefused(400, 'bad_values', 'Say which options to set.')
    for (const [k, v] of Object.entries(b.values)) {
      // eslint-disable-next-line no-control-regex
      if (!/^[A-Za-z0-9_.-]{1,128}$/.test(k) || typeof v !== 'string' || v.length > 8192 || /[\u0000-\u001f\u007f]/.test(v)) throw new McpRefused(400, 'bad_values', 'An option’s value has to be one line of text.')
    }
    const r = await run('claude', ['plugin', 'configure', id, '--values-stdin', '--json'], await whereFor({ ...b, scope: 'project' }, deps), JSON.stringify(b.values))
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r, Object.values(b.values) as string[]) ?? 'Claude Code didn’t save those options.')
    return { ok: true, id }
  },
  '/prune': async (b, deps) => {
    const scope = scopeArg(b.scope)
    const r = await run('claude', ['plugin', 'prune', '--scope', scope, '--yes'], await whereFor(b, deps))
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Claude Code didn’t tidy them up.')
    return { ok: true, ...opt('message', r.stdout.trim().split('\n').at(-1), 300) }
  },
  '/marketplaces/add': async (b, deps) => {
    if (typeof b.source !== 'string' || !SOURCE.test(b.source.trim())) throw new McpRefused(400, 'bad_source', 'A source is owner/repo, a URL or a path.')
    const scope = typeof b.scope === 'string' ? scopeArg(b.scope) : 'user'
    confirmed(b, 'Adding a marketplace')
    const o = outcome(await run('claude', ['plugin', 'marketplace', 'add', '--json', '--scope', scope, b.source.trim()], await whereFor({ ...b, scope }, deps), undefined, INSTALL_MS), 'Claude Code didn’t add that marketplace.')
    availableCache.clear()
    log(`plugins: added a marketplace (${scope})`)
    return { ok: true, ...opt('name', o.raw?.marketplace, 128), ...(o.message ? { message: o.message } : {}) }
  },
  '/marketplaces/remove': async (b, deps) => {
    const name = marketArg(b.name)
    const o = outcome(await run('claude', ['plugin', 'marketplace', 'remove', '--json', name], await whereFor({ ...b, scope: b.folder ? 'project' : 'user' }, deps)), 'Claude Code didn’t remove that marketplace.')
    availableCache.clear()
    return { ok: true, name, ...(o.message ? { message: o.message } : {}) }
  },
  '/marketplaces/update': async (b, deps) => {
    const name = b.name === undefined || b.name === null ? null : marketArg(b.name)
    const r = await run('claude', ['plugin', 'marketplace', 'update', ...(name ? ['--json', name] : [])], await whereFor({ ...b, scope: b.folder ? 'project' : 'user' }, deps), undefined, INSTALL_MS)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Claude Code didn’t update that marketplace.')
    availableCache.clear()
    return { ok: true }
  },
}

const codexRoutes: Record<string, Route> = {
  '/install': async (b) => {
    const id = pluginArg(b.plugin)
    if (!id.includes('@')) throw new McpRefused(400, 'bad_plugin', 'Codex needs plugin@marketplace.')
    confirmed(b, 'Installing a plugin')
    const r = await run('codex', ['plugin', 'add', id, '--json'], HOME, undefined, INSTALL_MS)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t install that plugin.')
    log(`plugins: installed ${id} in codex`)
    return { ok: true, id }
  },
  '/uninstall': async (b) => {
    const id = pluginArg(b.plugin)
    const r = await run('codex', ['plugin', 'remove', id, '--json'], HOME, undefined, INSTALL_MS)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t remove that plugin.')
    return { ok: true, id }
  },
  '/toggle': async (b) => {
    const id = pluginArg(b.plugin)
    if (typeof b.enabled !== 'boolean') throw new McpRefused(400, 'bad_request', 'Say on or off.')
    if (id.includes('.')) throw new McpRefused(409, 'not_for_codex', 'That name can’t be switched from here.')
    await withAppServer((req) => req('config/value/write', { keyPath: `plugins.${id}.enabled`, value: b.enabled, mergeStrategy: 'upsert' }))
    return { ok: true, id, enabled: b.enabled }
  },
  '/marketplaces/add': async (b) => {
    if (typeof b.source !== 'string' || !SOURCE.test(b.source.trim())) throw new McpRefused(400, 'bad_source', 'A source is owner/repo, a URL or a path.')
    confirmed(b, 'Adding a marketplace')
    const r = await run('codex', ['plugin', 'marketplace', 'add', b.source.trim()], HOME, undefined, INSTALL_MS)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t add that marketplace.')
    availableCache.clear()
    log('plugins: added a marketplace to codex')
    return { ok: true }
  },
  '/marketplaces/remove': async (b) => {
    const name = marketArg(b.name)
    const r = await run('codex', ['plugin', 'marketplace', 'remove', name], HOME)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t remove that marketplace.')
    availableCache.clear()
    return { ok: true, name }
  },
  '/marketplaces/update': async (b) => {
    const name = b.name === undefined || b.name === null ? null : marketArg(b.name)
    const r = await run('codex', ['plugin', 'marketplace', 'upgrade', ...(name ? [name] : [])], HOME, undefined, INSTALL_MS)
    if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Codex didn’t refresh that marketplace.')
    availableCache.clear()
    return { ok: true }
  },
}

// ── Skills ───────────────────────────────────────────────────────────────

export const SKILL_STATES = ['on', 'name-only', 'user-invocable-only', 'off'] as const

/** The rows of Claude Code's own `/skills` menu, from a process in that folder. It says what it says of a host drawing that menu; the request isn't in the SDK's types, so a missing answer is a state, not a crash. */
async function claudeSkills(folder: string, deps: McpDeps) {
  const held = await heldBack(folder)
  const r: any = await askProcess(folder, held.waiting, async (q) => (q as unknown as { request(r: unknown): Promise<any> }).request({ subtype: 'get_skills_dialog' }))
  const rows: any[] = r?.response?.skills ?? r?.skills ?? []
  return {
    agent: 'claude',
    folder: folderId(folder),
    states: SKILL_STATES,
    folders: await foldersOf(deps),
    skills: rows.flatMap((s) => {
      const name = typeof s?.name === 'string' ? s.name : ''
      if (!SKILL_NAME.test(name)) return []
      return [{ name, ...opt('displayName', s.display_name, 128), ...opt('description', s.description), ...opt('source', s.source, 40), state: SKILL_STATES.includes(s.state) ? s.state : 'on', ...(typeof s.tokens === 'number' ? { tokens: s.tokens } : {}), ...opt('lockedBy', s.locked_by, 40) }]
    }),
  }
}

const codexScope = (s: unknown) => (s === 'repo' ? 'project' : s === 'system' ? 'built-in' : s === 'admin' ? 'managed' : 'user')

async function codexSkills() {
  const r = await withAppServer((req) => req('skills/list', { cwds: [HOME], forceReload: true }))
  const rows: any[] = r?.data?.[0]?.skills ?? []
  return {
    agent: 'codex',
    folder: HOME_FOLDER,
    states: ['on', 'off'],
    folders: [{ id: HOME_FOLDER, name: '~' }],
    skills: rows.flatMap((s) => {
      const name = typeof s?.name === 'string' ? s.name : ''
      if (!SKILL_NAME.test(name)) return []
      const source = s.pluginId ? 'plugin' : codexScope(s.scope)
      return [{ name, ...opt('displayName', s.interface?.displayName, 128), ...opt('description', s.shortDescription ?? s.interface?.shortDescription ?? s.description), source, state: s.enabled === false ? 'off' : 'on' }]
    }),
  }
}

async function setSkill(agent: 'claude' | 'codex', b: Record<string, unknown>, deps: McpDeps) {
  const name = b.name
  if (typeof name !== 'string' || !SKILL_NAME.test(name)) throw new McpRefused(400, 'bad_skill', 'That isn’t a skill’s name.')
  if (agent === 'codex') {
    if (b.state !== 'on' && b.state !== 'off') throw new McpRefused(400, 'bad_state', 'For Codex a skill is on or off.')
    await withAppServer((req) => req('skills/config/write', { name, enabled: b.state === 'on' }))
    return { ok: true, name, state: b.state }
  }
  if (!SKILL_STATES.includes(b.state as never)) throw new McpRefused(400, 'bad_state', 'That isn’t a state for a skill.')
  const folder = await folderPath(typeof b.folder === 'string' ? b.folder : null, deps)
  const r = await run('claude', ['edit-skill-overrides', '--json'], folder, JSON.stringify({ name, state: b.state }))
  if (r.code !== 0) throw new McpRefused(502, 'cli_failed', cliWords(r) ?? 'Claude Code didn’t change that skill.')
  return { ok: true, name, state: b.state }
}

// ── Routes ───────────────────────────────────────────────────────────────

const param = (url: URL, k: string) => url.searchParams.get(k)

/** GET /v1/agents/env: the rows, the files they come from, and what Undo would do. */
async function envList(agent: 'claude' | 'codex', id: string | null, deps: McpDeps) {
  if (agent === 'codex') return { ...(await codexEnv()), folder: HOME_FOLDER, folders: [{ id: HOME_FOLDER, name: '~' }] }
  const folder = await folderPath(id, deps)
  const folders = await foldersOf(deps)
  const label = folders.find((f) => f.id === folderId(folder))?.name ?? '~'
  return { ...(await claudeEnv(folder, label)), folder: folderId(folder), folders, undo: claudeUndoOffer((p) => (p === null ? null : folderId(p))) }
}

export async function extrasRoute(path: string, m: string, url: URL, res: ServerResponse, json: (res: ServerResponse, status: number, body: unknown) => void, deps: McpDeps): Promise<boolean> {
  const folderParam = async () => folderPath(param(url, 'folder'), deps)
  if (path === '/v1/agents/env' && m === 'GET') {
    json(res, 200, await envList(agentOf(param(url, 'agent')), param(url, 'folder'), deps))
    return true
  }
  if (path === '/v1/agents/env' || path === '/v1/agents/env/remove' || path === '/v1/agents/env/undo') {
    if (m !== 'POST') return false
    const b = await deps.body()
    if (!isTable(b)) throw new McpRefused(400, 'bad_request', 'Send what to change.')
    const agent = agentOf(typeof b.agent === 'string' ? b.agent : null)
    const folder = agent === 'claude' && path !== '/v1/agents/env/undo' ? await folderPath(typeof b.folder === 'string' ? b.folder : null, deps) : HOME
    let out: unknown
    if (path === '/v1/agents/env') out = agent === 'codex' ? await codexSet(b) : await claudeSet(b, folder)
    else if (path === '/v1/agents/env/remove') out = agent === 'codex' ? await codexRemove(b) : await claudeRemove(b, folder)
    else out = agent === 'codex' ? await codexUndo(b) : await claudeUndo(b)
    deps.changed(agent)
    json(res, 200, out)
    return true
  }
  if (m === 'GET') {
    const agent = agentOf(param(url, 'agent'))
    let body: unknown
    if (path === '/v1/agents/plugins') body = agent === 'codex' ? await codexPlugins(deps) : await claudePlugins(await folderParam(), deps)
    else if (path === '/v1/agents/plugins/available') body = await availablePlugins(agent, agent === 'codex' ? HOME : await folderParam(), param(url, 'q') ?? '', param(url, 'marketplace'))
    else if (path === '/v1/agents/plugins/detail') {
      const id = pluginArg(param(url, 'plugin'))
      body = agent === 'codex' ? await codexDetail(id) : await claudeDetail(id, await folderParam())
    } else if (path === '/v1/agents/skills') body = agent === 'codex' ? await codexSkills() : await claudeSkills(await folderParam(), deps)
    else return false
    json(res, 200, body)
    return true
  }
  if (m !== 'POST') return false
  const isSkill = path === '/v1/agents/skills'
  const sub = path.startsWith('/v1/agents/plugins/') ? path.slice('/v1/agents/plugins'.length) : null
  if (!isSkill && !sub) return false
  const b = await deps.body()
  if (!isTable(b)) throw new McpRefused(400, 'bad_request', 'Send what to change.')
  const agent = agentOf(typeof b.agent === 'string' ? b.agent : null)
  let out: unknown
  if (isSkill) out = await serial(() => setSkill(agent, b, deps))
  else {
    const route = (agent === 'codex' ? codexRoutes : claudeRoutes)[sub!]
    if (!route) throw new McpRefused(agent === 'codex' && claudeRoutes[sub!] ? 409 : 404, agent === 'codex' && claudeRoutes[sub!] ? 'not_for_codex' : 'not_found', agent === 'codex' && claudeRoutes[sub!] ? 'Codex has no such thing.' : 'Unknown endpoint.')
    out = await serial(() => route(b, deps))
  }
  deps.changed(agent)
  json(res, 200, out)
  return true
}
