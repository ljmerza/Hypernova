// Slash commands and skills for the phone's "/" list: asked of Claude Code itself, per project.
// An SDK process started in the project's folder answers `initialize` with every command it
// knows (Claude Code's own, the user's and the project's commands and skills, plugins', MCP
// prompts), with descriptions and argument hints. No prompt is sent and its hooks are off, so it
// costs no tokens and no session shows up anywhere. A list is cached per folder for a few
// minutes; an older one is served while a fresh one is fetched. Without Claude Code's SDK (Setup
// fetches it, sdk.ts) the ask answers 409 `sdk_missing`, and the model list keeps its built-in one.
import type { SDKControlInitializeResponse } from '@anthropic-ai/claude-agent-sdk'
import { findClaude, log } from './util.ts'
import { getProvider, providerEnv } from './store.ts'
import { childEnv } from './runner.ts'
import { notePlan } from './account.ts'
import { claudeSdk } from './sdk.ts'

export type Command = { name: string; description: string; argumentHint: string; aliases: string[]; builtin: boolean }

const FRESH_MS = 5 * 60_000
const cache = new Map<string, { at: number; commands: Command[] }>()
const inflight = new Map<string, Promise<Command[]>>()
// Claude Code's own settings-like commands, which it records as local commands. Its list marks
// its prompt-type commands and bundled skills (/init, /code-review…) as built-in too, but those
// are recorded like skills, so the list's flag can't say which look a command gets. The phone
// keeps the same set (Protocol.kt, CommandRun).
const LOCAL = new Set([
  'effort', 'model', 'compact', 'clear', 'reset', 'new', 'context', 'usage', 'cost', 'stats', 'config', 'settings', 'fast',
  'rename', 'color', 'theme', 'login', 'logout', 'resume', 'export', 'rewind', 'help', 'status', 'permissions', 'allowed-tools',
  'memory', 'mcp', 'agents', 'hooks', 'doctor', 'add-dir', 'exit', 'quit', 'plugin', 'todos', 'ide', 'upgrade', 'output-style',
  'privacy-settings', 'reload-plugins', 'reload-skills', 'skills', 'autocompact', 'rate-limit-options', 'usage-credits', 'advisor',
])

/** Whether [name] (without its "/") is one of Claude Code's own local commands. */
export function isBuiltinCommand(name: string): boolean {
  return LOCAL.has(name)
}

// Claude Code's own commands that only make sense in a terminal on the Mac, or not at all from a phone.
const HIDDEN = new Set(['heapdump', 'workflow-launch-exec', 'color', 'focus', 'statusline', 'terminal-setup', 'vim', 'ide', 'exit', 'quit'])
const shown = (c: Command) =>
  !c.name.startsWith('__') && !(c.builtin && HIDDEN.has(c.name)) && !/^\((removed|deprecated)\)/i.test(c.description) && !/^renamed to\b/i.test(c.description)

/** The commands Claude Code offers in [cwd] (cached; a stale list comes back at once and refreshes). */
export async function listCommands(cwd: string): Promise<Command[]> {
  const hit = cache.get(cwd)
  if (hit && Date.now() - hit.at < FRESH_MS) return hit.commands
  const fetching = refreshCommands(cwd)
  if (hit) {
    fetching.catch(() => {})
    return hit.commands
  }
  return fetching
}

/** Asks Claude Code in [cwd] now, whatever the cache holds (one ask at a time per folder). */
export function refreshCommands(cwd: string): Promise<Command[]> {
  const fetching = inflight.get(cwd) ?? fetchCommands(cwd).finally(() => inflight.delete(cwd))
  inflight.set(cwd, fetching)
  return fetching
}

// The same answer carries Claude Code's model list (models.ts listens).
const initListeners: ((init: SDKControlInitializeResponse) => void)[] = []
/** [f] hears every `initialize` answer an ask here gets. */
export function onClaudeInit(f: (init: SDKControlInitializeResponse) => void): void {
  initListeners.push(f)
}

async function fetchCommands(cwd: string): Promise<Command[]> {
  const claude = findClaude()
  if (!claude) throw Object.assign(new Error('Claude Code isn’t installed on this Mac.'), { status: 409 })
  // 409 `sdk_missing` (SdkMissingError) while Claude Code's SDK isn't here.
  const { query } = await claudeSdk()
  let release = () => {}
  const idle = (async function* () {
    await new Promise<void>((r) => (release = r))
  })()
  const abort = new AbortController()
  const q = query({
    prompt: idle as AsyncIterable<never>,
    options: {
      cwd,
      settingSources: ['user', 'project', 'local'],
      // Its hooks would tell the owner's own daemon (and anything else listening) about a session that never happens.
      extraArgs: { settings: '{"disableAllHooks":true}' },
      persistSession: false,
      env: childEnv(providerEnv(getProvider(null))),
      pathToClaudeCodeExecutable: claude,
      abortController: abort,
    },
  })
  const timer = setTimeout(() => abort.abort(), 20_000)
  try {
    const init = await q.initializationResult()
    for (const f of initListeners) {
      try {
        f(init)
      } catch {}
    }
    const commands = init.commands
      .map((c) => ({ name: c.name, description: (c.description ?? '').trim(), argumentHint: (c.argumentHint ?? '').trim(), aliases: c.aliases ?? [], builtin: !!c.builtin }))
      .filter(shown)
    cache.set(cwd, { at: Date.now(), commands })
    // The same process knows the plan, for nothing more: its email and organization are left alone.
    try {
      const info = await Promise.race([q.accountInfo(), new Promise<null>((r) => setTimeout(() => r(null), 3000))])
      if (info && (!info.apiProvider || info.apiProvider === 'firstParty')) notePlan(info.subscriptionType)
    } catch {}
    return commands
  } catch (e) {
    log('listing commands failed in', cwd, e)
    throw Object.assign(new Error('Couldn’t get the commands from Claude Code. Try again in a moment.'), { status: 502 })
  } finally {
    clearTimeout(timer)
    release()
    abort.abort()
  }
}
