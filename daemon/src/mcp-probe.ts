// Asking Claude Code itself about its MCP servers: a process started in a folder, with no prompt and
// hooks off (as the "/" list asks, commands.ts), answers `mcpServerStatus()`, and takes `toggleMcpServer()`
// and `reconnectMcpServer()` through its own writer. No tokens, no session anywhere.
//
// A process under the SDK treats every project server as approved (2.1.316: `non_interactive`), and would
// start a `.mcp.json` nobody has said yes to. So what isn't approved is named in its flag settings as
// rejected: Claude Code won't start it, and the phone says it's waiting (guardArgs, for the "/" list too).
//
// Nothing here reaches a running session: each session holds its own connections, and this process's are its own.

import type { Query } from '@anthropic-ai/claude-agent-sdk'
import { maskSecrets } from './normalize.ts'
import { childEnv } from './runner.ts'
import { claudeSdk } from './sdk.ts'
import { getProvider, providerEnv } from './store.ts'
import { findClaude, log } from './util.ts'
import { MACHINE } from './platform.ts'
import { heldBack, maskText, setupOf, type ServerSetup } from './mcp-view.ts'

export type LiveStatus = 'connected' | 'failed' | 'needs-auth' | 'pending' | 'disabled' | 'waiting' | 'rejected'
export type LiveTool = { name: string; readOnly?: boolean; destructive?: boolean; openWorld?: boolean }
/** One server as a process in that folder sees it. [source]: where it came from, Claude Code's word (user, project, local, plugin, managed, claudeai…). */
export type LiveServer = { name: string; status: LiveStatus; source: string; serverName?: string; version?: string; error?: string; tools: LiveTool[]; setup?: ServerSetup }

/** The process's calls that are used here: a test's stand-in has these. */
export type ProbeQuery = Pick<Query, 'mcpServerStatus' | 'toggleMcpServer' | 'reconnectMcpServer'>
type Runner = <T>(cwd: string, waiting: readonly string[], work: (q: ProbeQuery) => Promise<T>) => Promise<T>

const PROBE_MS = 30_000
const SETTLE_MS = 12_000
const POLL_MS = 250
const ERROR_MAX = 300
const NAME_MAX = 128

/** A name from Claude Code's own list may be `plugin:x:y` or `claude.ai Gmail`, so it's wider than a name a person adds. */
export const LIVE_NAME = /^[\p{L}\p{N}][\p{L}\p{N} _.:@/+-]{0,127}$/u

/**
 * The flags of any process that isn't a person's session, looked up as it starts: hooks off, and no project server that isn't approved.
 * Claude Code still reads a `.mcp.json` these names can't come from (through a link, or past the size read here: measured on 2.1.316),
 * so there it's told to read none: nothing but what's approved may start, and that costs this one process the others' names.
 */
export async function guardArgs(cwd: string, also: readonly string[] = []): Promise<Record<string, string | null>> {
  const held = await heldBack(cwd)
  const names = [...new Set([...also, ...held.waiting, ...held.rejected])]
  return {
    settings: JSON.stringify({ disableAllHooks: true, ...(names.length ? { disabledMcpjsonServers: names } : {}) }),
    ...(held.unread ? { 'strict-mcp-config': null } : {}),
  }
}

const realRunner: Runner = async (cwd, waiting, work) => {
  const claude = findClaude()
  if (!claude) throw Object.assign(new Error(`Claude Code isn’t installed on this ${MACHINE}.`), { status: 409, code: 'no_claude' })
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
      extraArgs: await guardArgs(cwd, waiting),
      persistSession: false,
      env: childEnv(providerEnv(getProvider(null))),
      pathToClaudeCodeExecutable: claude,
      abortController: abort,
    },
  })
  const timer = setTimeout(() => abort.abort(), PROBE_MS)
  try {
    await q.initializationResult()
    return await work(q)
  } finally {
    clearTimeout(timer)
    release()
    abort.abort()
  }
}

let runner: Runner = realRunner
/** Tests put a stand-in here. */
export const useProbe = (r: Runner | null) => {
  runner = r ?? realRunner
  cache.clear()
}

const clip = (s: string, max: number) => (s.length > max ? `${s.slice(0, max - 1)}…` : s)

function liveOf(raw: any): LiveServer | null {
  const name = typeof raw?.name === 'string' ? raw.name : ''
  if (!LIVE_NAME.test(name) || maskSecrets(name) !== name) return null
  const known: LiveStatus[] = ['connected', 'failed', 'needs-auth', 'pending', 'disabled']
  const status: LiveStatus = known.includes(raw.status) ? raw.status : 'failed'
  const tools: LiveTool[] = (Array.isArray(raw.tools) ? raw.tools : []).flatMap((t: any) => {
    const n = typeof t?.name === 'string' ? t.name : ''
    if (!n) return []
    const a = t.annotations ?? {}
    return [{ name: clip(maskSecrets(n), NAME_MAX), ...(a.readOnly ? { readOnly: true } : {}), ...(a.destructive ? { destructive: true } : {}), ...(a.openWorld ? { openWorld: true } : {}) }]
  })
  const source = typeof raw.source === 'string' ? raw.source : typeof raw.scope === 'string' ? raw.scope : 'unknown'
  const info = raw.serverInfo ?? {}
  return {
    name,
    status,
    source: clip(source, 32),
    ...(typeof info.name === 'string' ? { serverName: clip(maskSecrets(info.name), NAME_MAX) } : {}),
    ...(typeof info.version === 'string' ? { version: clip(maskSecrets(info.version), NAME_MAX) } : {}),
    // A connection's error can quote its URL or command.
    ...(typeof raw.error === 'string' && raw.error ? { error: clip(maskText(raw.error), ERROR_MAX) } : {}),
    tools,
    // Only for what the files here don't describe (a plugin's, an organization's): command, args and URL, masked. No env or headers come in this answer.
    ...(raw.config && !['user', 'project', 'local'].includes(source) ? { setup: setupOf(raw.config) } : {}),
  }
}

/** Waits out a server that's still connecting, for as long as a phone will. */
export async function settled(q: Pick<ProbeQuery, 'mcpServerStatus'>, ms: number = SETTLE_MS): Promise<any[]> {
  const end = Date.now() + ms
  for (;;) {
    const s: any[] = await q.mcpServerStatus()
    if (!s.some((x) => x?.status === 'pending') || Date.now() >= end) return s
    await new Promise((r) => setTimeout(r, POLL_MS))
  }
}

const FRESH_MS = 20_000
const cache = new Map<string, { at: number; servers: LiveServer[]; waiting: string }>()
const inflight = new Map<string, Promise<LiveServer[]>>()

/**
 * What a process in [folder] sees: every server, from wherever. [waiting] and [rejected]: the folder's project
 * servers not to start, listed by their state. Kept a moment per folder, since each ask starts them all.
 */
export async function liveServers(folder: string, waiting: readonly string[], rejected: readonly string[], fresh = false): Promise<LiveServer[]> {
  const key = [...waiting].sort().join('\0')
  const hit = cache.get(folder)
  if (!fresh && hit && hit.waiting === key && Date.now() - hit.at < FRESH_MS) return withHeld(hit.servers, waiting, rejected)
  const asking =
    inflight.get(folder) ??
    runner(folder, waiting, async (q) => (await settled(q)).flatMap((raw) => liveOf(raw) ?? []))
      .then((servers) => {
        cache.set(folder, { at: Date.now(), servers, waiting: key })
        return servers
      })
      .finally(() => inflight.delete(folder))
  inflight.set(folder, asking)
  return withHeld(await asking, waiting, rejected)
}

/** The servers not started, with their reason: Claude Code's list doesn't have them. */
function withHeld(servers: LiveServer[], waiting: readonly string[], rejected: readonly string[]): LiveServer[] {
  const held = new Set([...waiting, ...rejected])
  const out = servers.filter((s) => !held.has(s.name))
  for (const name of waiting) out.push({ name, status: 'waiting', source: 'project', tools: [] })
  for (const name of rejected) out.push({ name, status: 'rejected', source: 'project', tools: [] })
  return out
}

/** Any other question for a process in [folder], under the same care. */
export const askProcess = <T>(folder: string, waiting: readonly string[], work: (q: ProbeQuery) => Promise<T>) => runner(folder, waiting, work)

/** Whether to read them again next time: something changed that a process would see. */
export const forgetLive = (folder?: string) => (folder ? cache.delete(folder) : cache.clear())

/** Switch [name] on or off for [folder], through Claude Code's own writer (`disabledMcpServers` of that project's entry). */
export async function toggleServer(folder: string, waiting: readonly string[], name: string, enabled: boolean, waitMs = 8000): Promise<void> {
  await runner(folder, waiting, async (q) => {
    await settled(q, 3000)
    // A busy machine can answer this before the new process has taken the server in, so a server
    // it doesn't know yet gets a few more tries; one that's really gone still fails after them.
    const until = Date.now() + waitMs
    for (;;) {
      try {
        await q.toggleMcpServer(name, enabled)
        return
      } catch (e) {
        if (!/server not found/i.test(String((e as Error)?.message ?? e)) || Date.now() >= until) throw e
        await new Promise((r) => setTimeout(r, 250))
      }
    }
  })
  cache.delete(folder)
}

/** A fresh connection to [name] from a new process: whether it works now, and its state. Running sessions keep their own. */
export async function reconnectServer(folder: string, waiting: readonly string[], name: string): Promise<LiveServer | null> {
  const r = await runner(folder, waiting, async (q) => {
    await settled(q)
    try {
      await q.reconnectMcpServer(name)
    } catch (e) {
      log('mcp reconnect: did not connect', name)
      void e
    }
    return (await settled(q)).flatMap((raw) => liveOf(raw) ?? [])
  })
  cache.delete(folder)
  return r.find((s) => s.name === name) ?? null
}
