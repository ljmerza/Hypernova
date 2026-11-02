// What the phone may see of an MCP server (docs/PROTOCOL.md, "An agent's MCP servers", "What may leave"): where it's defined, how it's set
// up, never a secret. `env` and `headers` leave as names only. Values inside arguments and URLs that look
// like a token or a key are masked, and a form that sends a masked value back keeps the one on the server.
//
// `~/.claude.json` stays closed to the phone's file viewer (secretfiles.ts). Of that file only `mcpServers`
// and a project's own MCP keys are looked at here, and its account details and history never are.

import { createHash, createHmac, randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, isAbsolute, join, normalize } from 'node:path'
import { claudeConfigFile, folderName, isTable, MCP_CONFIG_MAX, MCP_JSON_MAX, MCP_NAME, nameIsClean, own, parseTable, projectFolder, readCapped, repoRoot, within, type FileState } from './agent-settings.ts'
import { maskSecrets } from './normalize.ts'
import { CLAUDE_DIR, HOME } from './util.ts'

export const MASK = '•••'

// ── Masking ──────────────────────────────────────────────────────────────

/** A flag's or a variable's or a query parameter's name that says its value is a secret. Whole words only: `monkey` isn't one. */
const SECRET_WORD = /(?:^|[-_.])(?:token|tokens|secret|secrets|password|passwd|passphrase|pwd|pass|auth|authorization|apikey|api[-_]?key|access[-_]?key|private[-_]?key|client[-_]?secret|key|keys|credential|credentials|bearer|sig|signature|cookie|session[-_]?id)(?:$|[-_.])/i
export const secretName = (name: string) => SECRET_WORD.test(name.replace(/^-+/, ''))
const HEADER_FLAG = /^(?:--headers?|-H)$/
const SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//
const HEADER_SHAPED = /^([A-Za-z0-9_-]+):\s*(.+)$/

/** A URL with its credentials and any secret-named query parameter masked, and anything else that looks like one. */
export function maskUrl(url: string): string {
  let out = url.replace(/^([A-Za-z][A-Za-z0-9+.-]*:\/\/)[^/?#@]+@/, `$1${MASK}@`)
  out = out.replace(/([?&;])([^=&;#]+)=([^&;#]*)/g, (m, sep: string, name: string, value: string) => {
    let decoded = name
    try {
      decoded = decodeURIComponent(name)
    } catch {}
    return secretName(decoded) && value ? `${sep}${name}=${MASK}` : m
  })
  out = out.replace(/#.*$/, (fragment) => (/[=]/.test(fragment) ? `#${MASK}` : fragment))
  return maskSecrets(out)
}

/** Free text that may quote a command or a URL (a connection's error, a tool's description): each URL and each secret-named flag in it masked. */
export function maskText(text: string): string {
  const urls = text.replace(/[A-Za-z][A-Za-z0-9+.-]*:\/\/[^\s'"<>)\]]+/g, (u) => maskUrl(u))
  const flags = urls.replace(/(--?[A-Za-z0-9_.-]+)([= ])(\S+)/g, (m, flag: string, sep: string, value: string) => (secretName(flag) && !value.startsWith('-') ? `${flag}${sep}${MASK}` : m))
  return maskSecrets(flags)
}

/** One value on its own, with no flag before it to say what it is. */
function maskOne(arg: string): string {
  const eq = /^(-{0,2}[A-Za-z0-9_.-]+)=(.*)$/s.exec(arg)
  if (eq && eq[2] && secretName(eq[1]!)) return `${eq[1]}=${MASK}`
  if (eq && eq[2] && SCHEME.test(eq[2])) return `${eq[1]}=${maskUrl(eq[2])}`
  if (SCHEME.test(arg)) return maskUrl(arg)
  if (/^bearer\s+\S/i.test(arg) || /^basic\s+\S/i.test(arg)) return `${arg.split(/\s+/)[0]} ${MASK}`
  const header = HEADER_SHAPED.exec(arg)
  if (header && secretName(header[1]!)) return `${header[1]}: ${MASK}`
  return maskSecrets(arg)
}

/** Arguments as the phone may see them: the same count in the same order, each masked where it holds a secret. */
export function maskArgs(args: readonly string[]): string[] {
  return args.map((arg, i) => {
    const prev = i > 0 ? args[i - 1]! : ''
    if (HEADER_FLAG.test(prev)) {
      const h = HEADER_SHAPED.exec(arg)
      return h ? `${h[1]}: ${MASK}` : MASK
    }
    if (prev.startsWith('-') && !prev.includes('=') && secretName(prev) && !arg.startsWith('-')) return MASK
    return maskOne(arg)
  })
}

/**
 * [next] with what the phone sent back still masked swapped for the real values. Null: it holds a mask
 * this server never gave out, which would be written as three dots otherwise.
 */
export function restoreArgs(next: readonly string[], original: readonly string[]): string[] | null {
  const masked = maskArgs(original)
  const used = new Set<number>()
  const out: string[] = []
  for (const arg of next) {
    if (!arg.includes(MASK)) {
      out.push(arg)
      continue
    }
    const at = masked.findIndex((m, i) => m === arg && !used.has(i))
    if (at < 0) return null
    used.add(at)
    out.push(original[at]!)
  }
  return out
}

// ── A config, as stored and as shown ─────────────────────────────────────

export type RawConfig = Record<string, unknown>
export type Transport = 'stdio' | 'http' | 'sse' | 'ws'

export const transportOf = (v: unknown): Transport | null => {
  if (!isTable(v)) return null
  const type = own(v, 'type')
  if (type === 'stdio' || (type === undefined && typeof own(v, 'command') === 'string')) return 'stdio'
  return type === 'http' || type === 'sse' || type === 'ws' ? type : null
}

/** How long a value the phone is shown may be. A longer one is cut, so a form can't take it for the whole value. */
const SHOWN_MAX = 2048
const VAR_NAME = /^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/
const cut = (s: string) => (s.length > SHOWN_MAX ? `${s.slice(0, SHOWN_MAX)}…` : s)

/** The names of a table of secrets, the ones that may leave: a name that itself looks like a secret stays. */
function namesOf(t: unknown): { names: string[]; hidden: number } {
  if (!isTable(t)) return { names: [], hidden: 0 }
  const all = Object.keys(t)
  const names = all.filter((k) => VAR_NAME.test(k) && maskSecrets(k) === k).slice(0, 200)
  return { names, hidden: all.length - names.length }
}

/** How a server is set up, in what may leave. */
export type ServerSetup = {
  transport: Transport | null
  command?: string
  args?: string[]
  url?: string
  envNames: string[]
  headerNames: string[]
  /** A command that makes its headers: it runs on the server, and it's shown like an argument. */
  headersHelper?: string
  oauth?: { clientId?: string; callbackPort?: number }
  timeout?: number
  alwaysLoad?: boolean
  hidden: number
}

export function setupOf(v: unknown): ServerSetup {
  const transport = transportOf(v)
  const out: ServerSetup = { transport, envNames: [], headerNames: [], hidden: 0 }
  if (!isTable(v)) return out
  const command = own(v, 'command')
  if (typeof command === 'string') out.command = cut(maskSecrets(command))
  const args = own(v, 'args')
  if (Array.isArray(args)) out.args = maskArgs(args.filter((a): a is string => typeof a === 'string')).map(cut)
  const url = own(v, 'url')
  if (typeof url === 'string') out.url = cut(maskUrl(url))
  const env = namesOf(own(v, 'env'))
  const headers = namesOf(own(v, 'headers'))
  out.envNames = env.names
  out.headerNames = headers.names
  out.hidden = env.hidden + headers.hidden
  const helper = own(v, 'headersHelper')
  if (typeof helper === 'string') out.headersHelper = cut(maskOne(helper))
  const oauth = own(v, 'oauth')
  if (isTable(oauth)) {
    const clientId = own(oauth, 'clientId')
    const callbackPort = own(oauth, 'callbackPort')
    out.oauth = {
      ...(typeof clientId === 'string' ? { clientId: cut(maskSecrets(clientId)) } : {}),
      ...(typeof callbackPort === 'number' ? { callbackPort } : {}),
    }
  }
  const timeout = own(v, 'timeout')
  if (typeof timeout === 'number') out.timeout = timeout
  if (own(v, 'alwaysLoad') === true) out.alwaysLoad = true
  return out
}

const PER_RUN = randomBytes(32)
/**
 * What the phone sends back to say which version it saw. Keyed with a value that never leaves this
 * process, unlike a settings file's plain hash: an entry is short, and a plain hash of a short secret could be guessed from.
 */
export const configHash = (v: unknown) => createHmac('sha256', PER_RUN).update(JSON.stringify(v)).digest('hex').slice(0, 32)

// ── Folders ──────────────────────────────────────────────────────────────

/** A folder on the wire: an id and a base name. The path stays here, as it does in the settings reply. */
export type Folder = { id: string; name: string }
export const HOME_FOLDER = 'home'
export const folderId = (path: string, home: string = HOME) => (path === home ? HOME_FOLDER : createHash('sha256').update(`hn-folder\0${path}`).digest('hex').slice(0, 16))

/** Claude Code's `~/.claude.json`, or why not. Read per request, nothing of it kept. */
export async function readClaudeConfig(file: string = claudeConfigFile()): Promise<{ root: Record<string, unknown> | null; state: FileState }> {
  const read = await readCapped(file, MCP_CONFIG_MAX, true)
  if ('state' in read) return { root: null, state: read.state }
  const root = parseTable(read.text)
  return root ? { root, state: 'ok' } : { root: null, state: 'malformed' }
}

/** The folders a server can be added to or looked at from: the home folder, Claude Code's own projects, and those the daemon knows. */
export function knownFolders(root: Record<string, unknown> | null, others: readonly string[] = [], home: string = HOME): Map<string, { path: string; name: string }> {
  const out = new Map<string, { path: string; name: string }>()
  const add = (path: string) => {
    if (!isAbsolute(path) || normalize(path) !== path) return
    const name = folderName(path, home)
    if (name !== null) out.set(folderId(path, home), { path, name })
  }
  add(home)
  const raw = root ? own(root, 'projects') : undefined
  if (isTable(raw)) for (const p of Object.keys(raw)) if (projectFolder(p)) add(p)
  for (const p of others) add(p)
  return out
}

// ── The list ─────────────────────────────────────────────────────────────

export type Scope = 'user' | 'local' | 'project' | 'settings'
export type Approval = 'approved' | 'pending' | 'rejected'
/** One server in the list: [folder] and [project] for local and project scope, [summary] a command's base name or a host. */
export type McpEntry = { name: string; scope: Scope; folder?: string; project?: string; transport: Transport | null; summary?: string; approval?: Approval }
export type McpList = { agent: 'claude'; state: FileState; servers: McpEntry[]; folders: Folder[]; hidden: number; foldersNotRead: number }

const LIST_MAX = 400
const WALK_MS = 2000
const PARENTS_MAX = 64
const BASE = /[^/]+$/

function summaryOf(v: unknown): string | undefined {
  if (!isTable(v)) return undefined
  const command = own(v, 'command')
  if (typeof command === 'string') {
    const base = BASE.exec(command)?.[0]
    return base && maskSecrets(base) === base ? base : undefined
  }
  const url = own(v, 'url')
  if (typeof url !== 'string') return undefined
  try {
    const host = new URL(url).hostname
    return maskSecrets(host) === host ? host : undefined
  } catch {
    return undefined
  }
}

/** A settings file's table, or an empty one. Only the keys asked for are looked at by anyone holding it. */
function settingsTable(file: string): Record<string, unknown> {
  try {
    return parseTable(readFileSync(file, 'utf8')) ?? {}
  } catch {
    return {}
  }
}

const listHas = (t: Record<string, unknown>, key: string, name: string) => {
  const l = own(t, key)
  return Array.isArray(l) && l.includes(name)
}

/** Whether Claude Code would read a folder's own settings: it, or a folder above it, has had its trust dialog accepted. */
export function folderTrusted(root: Record<string, unknown> | null, folder: string): boolean {
  const projects = root ? own(root, 'projects') : undefined
  if (!isTable(projects)) return false
  for (let d = folder, i = 0; i < PARENTS_MAX; i++) {
    const entry = own(projects, d)
    if (isTable(entry) && own(entry, 'hasTrustDialogAccepted') === true) return true
    const up = dirname(d)
    if (up === d) return false
    d = up
  }
  return false
}

/**
 * A project server's approval, as Claude Code's own check in 2.1.316 (`claude mcp list` says the same):
 * rejected anywhere wins; the user's own settings count at once; a folder's own settings only once the folder is trusted.
 */
export function approvalOf(name: string, folder: string, root: Record<string, unknown> | null, dirs: { claude?: string } = {}): Approval {
  const user = settingsTable(join(dirs.claude ?? CLAUDE_DIR, 'settings.json'))
  const project = settingsTable(join(folder, '.claude', 'settings.json'))
  const local = settingsTable(join(folder, '.claude', 'settings.local.json'))
  if ([user, project, local].some((t) => listHas(t, 'disabledMcpjsonServers', name))) return 'rejected'
  if (own(user, 'enableAllProjectMcpServers') === true || listHas(user, 'enabledMcpjsonServers', name)) return 'approved'
  if (!folderTrusted(root, folder)) return 'pending'
  for (const t of [project, local]) if (own(t, 'enableAllProjectMcpServers') === true || listHas(t, 'enabledMcpjsonServers', name)) return 'approved'
  return 'pending'
}

/** The `.mcp.json` in [folder] and each parent, nearest last so its entry wins a name, as Claude Code reads them. */
export async function projectServers(folder: string, left: () => number = () => WALK_MS): Promise<{ servers: Map<string, { config: unknown; from: string }>; notRead: number }> {
  const servers = new Map<string, { config: unknown; from: string }>()
  let notRead = 0
  const chain: string[] = []
  for (let d = normalize(folder); dirname(d) !== d && chain.length < PARENTS_MAX; d = dirname(d)) chain.push(d)
  for (const dir of chain.reverse()) {
    if (left() <= 0) {
      notRead++
      continue
    }
    const read = await within(readCapped(join(dir, '.mcp.json'), MCP_JSON_MAX, false), left())
    if (read === null) {
      notRead++
      continue
    }
    if (!('text' in read)) continue
    const table = parseTable(read.text)
    const t = table ? own(table, 'mcpServers') : undefined
    if (isTable(t)) for (const name of Object.keys(t)) servers.set(name, { config: own(t, name), from: dir })
  }
  return { servers, notRead }
}

/** GET /v1/agents/mcp, for Claude Code: every server in every scope a file says, with what shows on its row. Nothing is started. */
export async function claudeList(others: readonly string[] = [], file: string = claudeConfigFile(), dirs: { claude?: string } = {}): Promise<McpList> {
  const { root, state } = await readClaudeConfig(file)
  const folders = knownFolders(root, others)
  const servers: McpEntry[] = []
  let hidden = 0
  const add = (e: McpEntry) => {
    if (!MCP_NAME.test(e.name) || !nameIsClean(e.name) || servers.length >= LIST_MAX) hidden++
    else servers.push(e)
  }
  const table = (t: unknown) => (isTable(t) ? t : {})
  const user = table(root ? own(root, 'mcpServers') : undefined)
  for (const name of Object.keys(user)) add({ name, scope: 'user', transport: transportOf(own(user, name)), ...optSummary(own(user, name)) })
  const fromSettings = table(own(settingsTable(join(dirs.claude ?? CLAUDE_DIR, 'settings.json')), 'mcpServers'))
  for (const name of Object.keys(fromSettings)) add({ name, scope: 'settings', transport: transportOf(own(fromSettings, name)), ...optSummary(own(fromSettings, name)) })

  const projects = table(root ? own(root, 'projects') : undefined)
  for (const path of Object.keys(projects)) {
    const local = table(own(table(own(projects, path)), 'mcpServers'))
    for (const name of Object.keys(local)) {
      const project = folderName(path)
      if (project === null) hidden++
      else add({ name, scope: 'local', folder: folderId(path), project, transport: transportOf(own(local, name)), ...optSummary(own(local, name)) })
    }
  }
  const end = Date.now() + WALK_MS
  let foldersNotRead = 0
  for (const { path, name: project } of folders.values()) {
    if (Date.now() >= end) {
      foldersNotRead++
      continue
    }
    const read = await within(readCapped(join(path, '.mcp.json'), MCP_JSON_MAX, false), end - Date.now())
    if (read === null) foldersNotRead++
    if (read === null || !('text' in read)) continue
    const parsed = parseTable(read.text)
    const t = table(parsed ? own(parsed, 'mcpServers') : undefined)
    for (const name of Object.keys(t)) add({ name, scope: 'project', folder: folderId(path), project, transport: transportOf(own(t, name)), approval: approvalOf(name, path, root, dirs), ...optSummary(own(t, name)) })
  }
  return { agent: 'claude', state, servers, folders: [...folders].map(([id, f]) => ({ id, name: f.name })), hidden, foldersNotRead }
}
const optSummary = (v: unknown) => {
  const summary = summaryOf(v)
  return summary === undefined ? {} : { summary }
}

/** Where a server is defined: words for a place, never a path beyond the folder's base name. */
export function whereOf(scope: Scope, project?: string): string {
  if (scope === 'user') return '~/.claude.json'
  if (scope === 'settings') return '~/.claude/settings.json'
  if (scope === 'local') return `~/.claude.json, under ${project ?? 'its folder'}`
  return `${project ?? 'its folder'}/.mcp.json`
}

/** One server's stored entry, by scope: null when it isn't there. [path] is where a CLI should run for it. */
export async function findConfig(name: string, scope: Scope, folder: string | null, file: string = claudeConfigFile(), dirs: { claude?: string } = {}): Promise<{ config: unknown; path: string | null } | null> {
  if (!MCP_NAME.test(name)) return null
  const { root } = await readClaudeConfig(file)
  if (scope === 'user') {
    const t = root ? own(root, 'mcpServers') : undefined
    return isTable(t) && Object.hasOwn(t, name) ? { config: own(t, name), path: null } : null
  }
  if (scope === 'settings') {
    const t = own(settingsTable(join(dirs.claude ?? CLAUDE_DIR, 'settings.json')), 'mcpServers')
    return isTable(t) && Object.hasOwn(t, name) ? { config: own(t, name), path: null } : null
  }
  if (!folder) return null
  if (scope === 'local') {
    const projects = root ? own(root, 'projects') : undefined
    const keys = [folder, await repoRoot(folder).catch(() => null)]
    for (const key of keys) {
      if (!key || !isTable(projects)) continue
      const entry = own(projects, key)
      const t = isTable(entry) ? own(entry, 'mcpServers') : undefined
      if (isTable(t) && Object.hasOwn(t, name)) return { config: own(t, name), path: key }
    }
    return null
  }
  const read = await readCapped(join(folder, '.mcp.json'), MCP_JSON_MAX, false)
  if (!('text' in read)) return null
  const parsed = parseTable(read.text)
  const t = parsed ? own(parsed, 'mcpServers') : undefined
  return isTable(t) && Object.hasOwn(t, name) ? { config: own(t, name), path: folder } : null
}
