// What the phone may see of Claude Code's and Codex's user-level settings (docs/PROTOCOL.md, "Agent
// settings"; docs/DECISIONS.md, "The agents' settings, viewed"). This file only reads; the one way a
// value changes is agent-settings-edit.ts, from its own shorter list.
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

import { readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
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
export type AgentSettingsReply = { scope: 'user'; claude: AgentSettings<ClaudeSummary>; codex: AgentSettings<CodexSummary> }

/**
 * A shown key: its path in the file (dots go one table deeper) and what's accepted. [of]: one of
 * these words, from the agent's own schema (checked against Claude Code 2.1.316 and Codex 0.164.0).
 * 'id': free text in a model id's shape. What may be set is a list of its own: agent-settings-edit.ts.
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

/** Whether [s] passes: short, and with nothing in it that maskSecrets would hide. */
const clean = (s: string) => s.length <= VALUE_MAX && maskSecrets(s) === s
/** Whether [s] could be shown as a model's id. */
export const modelIdFits = (s: string) => clean(s) && MODEL_ID.test(s)

export function shown(rule: Shown, found: boolean, v: unknown): ShownValue {
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

/** GET /v1/agents/settings. It doesn't throw: what's wrong with a file is that agent's state. */
export function agentSettings(dirs: { claude?: string; codex?: string } = {}): AgentSettingsReply {
  return { scope: 'user', claude: claudeSettings(dirs.claude), codex: codexSettings(dirs.codex) }
}
