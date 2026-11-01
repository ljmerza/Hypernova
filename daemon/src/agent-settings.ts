// What the phone may see of Claude Code's and Codex's user-level settings (docs/PROTOCOL.md, "Agent
// settings"; docs/DECISIONS.md, "The agents' settings, viewed"). Read-only, like the rest of the
// daemon: it never edits a settings file (always.ts).
//
// A strict allow-list. CLAUDE_SHOWN and CODEX_SHOWN are the only values that leave, each checked
// for its type and shape; the summaries are counts and names, the names checked too. Nothing else
// in these files is looked at, so `env`, hook and status line commands, `awsAuthRefresh`,
// marketplace sources, `autoMode.environment`, `skip*` keys and MCP commands, args and env can't
// leave: there's no path out for them, and test/agent-settings.test.ts plants a secret in each.
//
// A file's trouble is a state, never its text: a parse error can quote the file, so none is kept.
//
// The user-level file only. A project's own settings can override what's here, which `scope: "user"`
// says and the screen says in words.
//
// MCP servers aren't kept in that file, so `claude.mcp` also reads `mcpServers` from Claude Code's own
// `~/.claude.json` (user and local scope) and from each project's `.mcp.json` (project scope). That
// file holds the account's details, OAuth data and history, so the same rule holds harder: only the
// keys of those `mcpServers` tables are looked at, and each name is checked before it's sent.

import { constants as fsConstants, readFileSync, statSync } from 'node:fs'
import { open } from 'node:fs/promises'
import { basename, isAbsolute, join, normalize } from 'node:path'
import { maskSecrets } from './normalize.ts'
import { OTHER, scanToml } from './toml-reader.ts'
import { CLAUDE_DIR, HOME } from './util.ts'

/** How a file went: it's there and read, not there (the agent's defaults), not parseable, or not readable. */
export type FileState = 'ok' | 'missing' | 'malformed' | 'unreadable'

/** One shown key: not in the file, in it with a value of the known shape, or in it with anything else (which stays here). */
export type ShownValue =
  | { key: string; state: 'unset' }
  | { key: string; state: 'unrecognised' }
  | { key: string; state: 'set'; value: string | boolean }

export type ClaudeSummary = {
  /** Null where the file has something other than a list. */
  rules: { allow: number | null; ask: number | null; deny: number | null }
  plugins: { name: string; on: boolean | null }[] | null
  mcpServers: string[] | null
  hookEvents: string[] | null
  /** Names not sent: they don't look like a name, might be a secret, or are past the cap. */
  hidden: number
}
export type CodexSummary = { projects: number | null; mcpServers: string[] | null; hidden: number }

export type AgentSettings<S> = { state: FileState; values: ShownValue[]; summary: S | null }
/** One MCP server's name, and where it's set. [project]: the base name of the folder, never its path. */
export type McpName = { name: string; scope: 'user' | 'local' | 'project'; project?: string }
/** Beside the file's state: Claude Code's MCP servers don't depend on settings.json being there. [state] is `~/.claude.json`'s. */
export type ClaudeMcp = { state: FileState; servers: McpName[]; hidden: number; foldersNotRead: number }

export type AgentSettingsReply = { scope: 'user'; claude: AgentSettings<ClaudeSummary> & { mcp: ClaudeMcp }; codex: AgentSettings<CodexSummary> }

/**
 * A shown key: its path in the file (dots go one table deeper) and what's accepted. [of]: one of
 * these words, from the agent's own schema (checked against Claude Code 2.1.316 and Codex 0.164.0).
 * 'id': free text in a model id's shape. Step two adds what's editable here, per key.
 */
type Shown = { key: string; kind: 'boolean' } | { key: string; kind: 'word'; of: readonly string[] } | { key: string; kind: 'id' }

export const CLAUDE_SHOWN: readonly Shown[] = [
  { key: 'effortLevel', kind: 'word', of: ['low', 'medium', 'high', 'xhigh'] },
  { key: 'permissions.defaultMode', kind: 'word', of: ['default', 'manual', 'acceptEdits', 'plan', 'auto', 'dontAsk', 'bypassPermissions'] },
  { key: 'showThinkingSummaries', kind: 'boolean' },
  { key: 'autoDreamEnabled', kind: 'boolean' },
  { key: 'includeCoAuthoredBy', kind: 'boolean' },
  { key: 'autoUpdatesChannel', kind: 'word', of: ['latest', 'stable', 'rc'] },
  { key: 'inputNeededNotifEnabled', kind: 'boolean' },
  { key: 'agentPushNotifEnabled', kind: 'boolean' },
]

export const CODEX_SHOWN: readonly Shown[] = [
  { key: 'model', kind: 'id' },
  { key: 'model_reasoning_effort', kind: 'word', of: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'] },
  { key: 'approval_policy', kind: 'word', of: ['untrusted', 'on-failure', 'on-request', 'never'] },
  { key: 'sandbox_mode', kind: 'word', of: ['read-only', 'workspace-write', 'danger-full-access'] },
  { key: 'web_search', kind: 'word', of: ['disabled', 'cached', 'indexed', 'live'] },
  { key: 'personality', kind: 'word', of: ['none', 'friendly', 'pragmatic'] },
]

/** Longest string value sent, and a name's shape: an MCP server, a plugin, a hook event. */
const VALUE_MAX = 80
const MODEL_ID = /^[A-Za-z0-9][A-Za-z0-9._:/[\]@-]*$/
const NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/
/** Names sent per list at most. */
const NAMES_MAX = 200
/** A settings file past this isn't read: nobody's is, and the reply stays small. */
const FILE_MAX = 4 * 1024 * 1024
/** `~/.claude.json` keeps history too, so it can be far bigger than a settings file. Past this it isn't read. */
const CONFIG_MAX = 16 * 1024 * 1024
/** A project's `.mcp.json` past this isn't read. */
const MCP_JSON_MAX = 256 * 1024
/** Project folders whose `.mcp.json` is read per request, and how long the whole walk may take (a dead network mount doesn't hold the reply up). */
const PROJECTS_MAX = 200
const MCP_WALK_MS = 2000
/** A folder's base name that may leave: a folder can have spaces and any letter, but not a path's or a command's characters. */
const FOLDER = /^[\p{L}\p{N}][\p{L}\p{N} _.+-]{0,63}$/u

/** Whether [s] passes: short, and with nothing in it that maskSecrets would hide. */
const clean = (s: string) => s.length <= VALUE_MAX && maskSecrets(s) === s

function shown(rule: Shown, found: boolean, v: unknown): ShownValue {
  const { key } = rule
  if (!found) return { key, state: 'unset' }
  if (rule.kind === 'boolean') return typeof v === 'boolean' ? { key, state: 'set', value: v } : { key, state: 'unrecognised' }
  if (typeof v !== 'string' || !clean(v)) return { key, state: 'unrecognised' }
  const fits = rule.kind === 'word' ? rule.of.includes(v) : MODEL_ID.test(v)
  return fits ? { key, state: 'set', value: v } : { key, state: 'unrecognised' }
}

/** The names of [all] that may leave, up to the cap, and how many didn't. */
function names(all: readonly string[]): { names: string[]; hidden: number } {
  const ok = all.filter((n) => NAME.test(n) && clean(n))
  const sent = ok.slice(0, NAMES_MAX)
  return { names: sent, hidden: all.length - sent.length }
}

/** The file's text, or why not. It follows links (Claude Code's own file may be one) and reads only a regular file, so a pipe can't hang the daemon. */
function read(file: string): { text: string } | { state: FileState } {
  try {
    const st = statSync(file)
    if (!st.isFile() || st.size > FILE_MAX) return { state: 'unreadable' }
    return { text: readFileSync(file, 'utf8') }
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code
    return { state: code === 'ENOENT' || code === 'ENOTDIR' ? 'missing' : 'unreadable' }
  }
}

const isTable = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const own = (o: Record<string, unknown>, k: string) => (Object.hasOwn(o, k) ? o[k] : undefined)

/** Claude Code's `<CLAUDE_DIR>/settings.json`. */
export function claudeSettings(dir: string = CLAUDE_DIR): AgentSettings<ClaudeSummary> {
  const file = read(join(dir, 'settings.json'))
  if ('state' in file) return { state: file.state, values: [], summary: null }
  let doc: unknown
  try {
    doc = JSON.parse(file.text)
  } catch {
    return { state: 'malformed', values: [], summary: null }
  }
  if (!isTable(doc)) return { state: 'malformed', values: [], summary: null }
  const root = doc
  const values = CLAUDE_SHOWN.map((rule) => {
    const [first, second] = rule.key.split('.') as [string, string | undefined]
    if (second === undefined) return shown(rule, Object.hasOwn(root, first), own(root, first))
    const table = own(root, first)
    return shown(rule, isTable(table) && Object.hasOwn(table, second), isTable(table) ? own(table, second) : undefined)
  })

  let hidden = 0
  const permissions = own(root, 'permissions')
  const rule = (k: string): number | null => {
    if (!isTable(permissions)) return permissions === undefined ? 0 : null
    const list = own(permissions, k)
    return list === undefined ? 0 : Array.isArray(list) ? list.length : null
  }
  /** Keys of a table in the file: null when it's something else. Only their names. */
  const keysOf = (k: string): string[] | null => {
    const t = own(root, k)
    return t === undefined ? [] : isTable(t) ? Object.keys(t) : null
  }
  const sendable = (k: string): string[] | null => {
    const keys = keysOf(k)
    if (!keys) return null
    const n = names(keys)
    hidden += n.hidden
    return n.names
  }
  const plugins = (): ClaudeSummary['plugins'] => {
    const t = own(root, 'enabledPlugins')
    if (t === undefined) return []
    if (!isTable(t)) return null
    // Keyed `plugin@marketplace`: the plugin's name only. A value that isn't on or off (the extended form with versions) is null.
    const out: { name: string; on: boolean | null }[] = []
    let seen = 0
    for (const [id, on] of Object.entries(t)) {
      seen++
      const name = id.split('@')[0]!
      if (!NAME.test(name) || !clean(name) || out.length >= NAMES_MAX) continue
      out.push({ name, on: typeof on === 'boolean' ? on : null })
    }
    hidden += seen - out.length
    return out
  }
  const summary: ClaudeSummary = {
    rules: { allow: rule('allow'), ask: rule('ask'), deny: rule('deny') },
    plugins: plugins(),
    mcpServers: sendable('mcpServers'),
    hookEvents: sendable('hooks'),
    hidden: 0,
  }
  summary.hidden = hidden
  return { state: 'ok', values, summary }
}

/** Where Codex keeps its config: `CODEX_HOME` as it is for the daemon, else `~/.codex`, as Codex does. */
export const codexHome = () => process.env.CODEX_HOME || join(HOME, '.codex')

/** Codex's `config.toml`: its top-level keys, and the names of two of its tables. */
export function codexSettings(dir: string = codexHome()): AgentSettings<CodexSummary> {
  const file = read(join(dir, 'config.toml'))
  if ('state' in file) return { state: file.state, values: [], summary: null }
  const top = new Map<string, unknown>()
  const projects = new Set<string>()
  const servers = new Set<string>()
  try {
    scanToml(file.text, (path, v) => {
      const [first, second] = path as [string, string | undefined]
      // A table's header has no value, and one written inline is OTHER: either way not a plain value.
      if (second === undefined) {
        top.set(first, v === undefined ? OTHER : v)
        return
      }
      if (!top.has(first)) top.set(first, OTHER)
      if (first === 'projects') projects.add(second)
      else if (first === 'mcp_servers') servers.add(second)
    })
  } catch {
    return { state: 'malformed', values: [], summary: null }
  }
  // A plain value where a table belongs (`projects = "x"`). A number or an array is OTHER, as a table is: it shows as none.
  const plain = (k: string) => typeof top.get(k) === 'string' || typeof top.get(k) === 'boolean'

  const values = CODEX_SHOWN.map((rule) => {
    const v = top.get(rule.key)
    // A table, an array, a number or a date isn't any of these keys' shapes: unrecognised, not passed through.
    return shown(rule, top.has(rule.key), v === OTHER ? undefined : v)
  })
  const n = names([...servers])
  return {
    state: 'ok',
    values,
    summary: { projects: plain('projects') ? null : projects.size, mcpServers: plain('mcp_servers') ? null : n.names, hidden: n.hidden },
  }
}

/**
 * Where Claude Code keeps `mcpServers`, as its binary finds the file (2.1.316): `<config dir>/.config.json`
 * while that old name is a file with something in it, else `.claude.json` in `CLAUDE_CONFIG_DIR` when
 * that's set and in the home folder when it isn't. So it's `~/.claude.json`, not `~/.claude/.claude.json`,
 * until the config folder is moved.
 */
export function claudeConfigFile(env: NodeJS.ProcessEnv = process.env, home: string = HOME): string {
  const moved = env.CLAUDE_CONFIG_DIR
  try {
    const legacy = join(moved ?? join(home, '.claude'), '.config.json')
    const st = statSync(legacy)
    if (st.isFile() && st.size > 0) return legacy
  } catch {}
  return join(moved || home, '.claude.json')
}

/** A folder's name as the phone may see it: a base name, `~` for the home folder (whose name is the username), or null. */
export function folderName(path: string, home: string = HOME): string | null {
  if (path === home) return '~'
  const base = basename(path)
  return FOLDER.test(base) && clean(base) ? base : null
}

/**
 * A file's text, at most [max] bytes, or why not. It opens first and asks about what it opened, so a
 * pipe can't hang it and nothing can be swapped in between; without [follow], a link is refused.
 */
async function readCapped(file: string, max: number, follow: boolean): Promise<{ text: string } | { state: FileState }> {
  let h: Awaited<ReturnType<typeof open>> | undefined
  try {
    h = await open(file, fsConstants.O_RDONLY | fsConstants.O_NONBLOCK | (follow ? 0 : fsConstants.O_NOFOLLOW))
    const st = await h.stat()
    if (!st.isFile() || st.size > max) return { state: 'unreadable' }
    const buf = Buffer.alloc(st.size)
    const { bytesRead } = await h.read(buf, 0, st.size, 0)
    return { text: buf.toString('utf8', 0, bytesRead) }
  } catch (e) {
    const code = (e as NodeJS.ErrnoException).code
    return { state: code === 'ENOENT' || code === 'ENOTDIR' ? 'missing' : 'unreadable' }
  } finally {
    await h?.close().catch(() => {})
  }
}

/** [work], or null once [ms] have passed. */
async function within<T>(work: Promise<T>, ms: number): Promise<T | null> {
  let timer: NodeJS.Timeout | undefined
  const late = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), Math.max(0, ms))
  })
  try {
    return await Promise.race([work, late])
  } finally {
    clearTimeout(timer)
  }
}

/** A JSON file's object, or its state. An empty file is an empty object, as Claude Code takes an empty `.mcp.json`. Never a parse error: it can quote the file. */
function parseTable(text: string): Record<string, unknown> | null {
  if (!text.trim()) return {}
  try {
    const doc: unknown = JSON.parse(text.replace(/^\ufeff/, ''))
    return isTable(doc) ? doc : null
  } catch {
    return null
  }
}

/** The keys of [table]'s `mcpServers`. Only their names: the values aren't looked at. */
const serverNames = (table: unknown): string[] => {
  const t = isTable(table) ? own(table, 'mcpServers') : undefined
  return isTable(t) ? Object.keys(t) : []
}

/**
 * Whether to read a project's `.mcp.json`: its folder is a clean absolute path, and not one of the
 * worktrees under `.claude/worktrees`, each a copy of the repository's own file.
 */
const projectFolder = (path: string) => isAbsolute(path) && normalize(path) === path && !path.includes('/.claude/worktrees/')

/**
 * `~/.claude.json`, as far as MCP goes. The whole file is read per request and nothing of it is kept;
 * [settingsNames] are the names already taken from settings.json, listed as the user's. Each project
 * in the file's `projects` has its local servers and its `.mcp.json`; a project's trust isn't asked.
 */
export async function claudeMcp(settingsNames: readonly string[] = [], file: string = claudeConfigFile()): Promise<ClaudeMcp> {
  const servers = new Map<string, McpName>()
  let hidden = 0
  const add = (name: string, scope: McpName['scope'], project?: string) => {
    if (!NAME.test(name) || !clean(name)) {
      hidden++
      return
    }
    const key = `${scope}\0${project ?? ''}\0${name}`
    if (servers.has(key)) return
    if (servers.size >= NAMES_MAX) {
      hidden++
      return
    }
    servers.set(key, project === undefined ? { name, scope } : { name, scope, project })
  }
  const inFolder = (names: string[], scope: McpName['scope'], path: string) => {
    const folder = folderName(path)
    if (folder === null) hidden += names.length
    else for (const n of names) add(n, scope, folder)
  }
  for (const n of settingsNames) add(n, 'user')

  const read = await readCapped(file, CONFIG_MAX, true)
  let state: FileState = 'ok'
  let foldersNotRead = 0
  if ('state' in read) state = read.state
  else {
    const root = parseTable(read.text)
    if (!root) state = 'malformed'
    else {
      for (const n of serverNames(root)) add(n, 'user')
      const raw = own(root, 'projects')
      const projects = isTable(raw) ? Object.keys(raw) : []
      if (isTable(raw)) for (const path of projects) inFolder(serverNames(own(raw, path)), 'local', path)
      const end = Date.now() + MCP_WALK_MS
      let walked = 0
      for (const path of projects) {
        if (!projectFolder(path)) continue
        const left = end - Date.now()
        if (walked >= PROJECTS_MAX || left <= 0) {
          foldersNotRead++
          continue
        }
        walked++
        const mcp = await within(readCapped(join(path, '.mcp.json'), MCP_JSON_MAX, false), left)
        if (mcp === null) foldersNotRead++
        else if ('text' in mcp) inFolder(serverNames(parseTable(mcp.text)), 'project', path)
      }
    }
  }
  return { state, servers: [...servers.values()], hidden, foldersNotRead }
}

/** GET /v1/agents/settings. It doesn't throw: what's wrong with a file is that agent's state. */
export async function agentSettings(dirs: { claude?: string; codex?: string; claudeConfig?: string } = {}): Promise<AgentSettingsReply> {
  const claude = claudeSettings(dirs.claude)
  return { scope: 'user', claude: { ...claude, mcp: await claudeMcp(claude.summary?.mcpServers ?? [], dirs.claudeConfig) }, codex: codexSettings(dirs.codex) }
}
