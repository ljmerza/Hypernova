// The daemon's own short talk with an MCP server, for one thing: what its tools are. Claude Code's
// `mcpServerStatus()` gives a tool's name and annotations and no more (2.1.316 sends no description and no
// schema, whatever its types say), so "see a tool" needs `tools/list` from the server itself. It's
// started the way Claude Code starts it, asked, and stopped. Tools are never called.
//
// Only for a server the owner already has switched on (mcp-manage.ts checks approval and disabled first).
// What comes back is someone else's text: capped and masked before it leaves (mcp-tools.ts).

import { dbgProc } from './debug-log.ts'
import { spawn, type ChildProcess } from 'node:child_process'
import { childEnv } from './runner.ts'
import { HOME } from './util.ts'

export type RawFacts = { serverInfo?: { name?: unknown; version?: unknown }; instructions?: unknown; tools: unknown[]; prompts: unknown[]; resources: unknown[] }

/** Why a talk didn't finish. Never the server's own words: a failure's text can quote a URL or a key. */
export type TalkTrouble = 'needs_auth' | 'failed' | 'too_slow' | 'no_direct'
export class TalkFailed extends Error {
  why: TalkTrouble
  constructor(why: TalkTrouble) {
    super(why)
    this.why = why
  }
}

const TALK_MS = 15_000
const PAGES_MAX = 10
const LINE_MAX = 8 * 1024 * 1024
const PROTOCOL = '2025-06-18'

/** `${VAR}` and `${VAR:-default}` as Claude Code expands them in a server's command, args, env, URL and headers. A variable that isn't there stays as written. */
export function expandVars(s: string, env: Record<string, string | undefined> = process.env): string {
  return s.replace(/\$\{([^}:]+)(?::-([^}]*))?\}/g, (m, name: string, fallback: string | undefined) => env[name] ?? fallback ?? m)
}

type Wire = { request(method: string, params: unknown): Promise<any>; notify(method: string, params: unknown): void; close(): void }

function stdioWire(command: string, args: string[], env: Record<string, string>, cwd: string, stop: Promise<never>): Wire {
  let child: ChildProcess
  try {
    child = spawn(command, args, { cwd, env: { ...childEnv({}), ...env }, stdio: ['pipe', 'pipe', 'ignore'] })
    const started = dbgProc(command, args, { via: 'mcp' })
    child.once('exit', (code, signal) => started(code, signal))
  } catch {
    throw new TalkFailed('failed')
  }
  let nextId = 1
  const waiting = new Map<number, { resolve: (v: any) => void; reject: (e: unknown) => void }>()
  const failAll = () => {
    for (const w of waiting.values()) w.reject(new TalkFailed('failed'))
    waiting.clear()
  }
  child.on('error', failAll)
  child.on('exit', failAll)
  child.stdin!.on('error', () => {})
  let buf = ''
  child.stdout!.setEncoding('utf8')
  child.stdout!.on('data', (d: string) => {
    buf += d
    if (buf.length > LINE_MAX) {
      child.kill('SIGKILL')
      return
    }
    for (let at = buf.indexOf('\n'); at >= 0; at = buf.indexOf('\n')) {
      const line = buf.slice(0, at).trim()
      buf = buf.slice(at + 1)
      if (!line) continue
      let m: any
      try {
        m = JSON.parse(line)
      } catch {
        continue
      }
      const w = typeof m?.id === 'number' ? waiting.get(m.id) : undefined
      if (!w) continue
      waiting.delete(m.id)
      if (m.error) w.reject(new TalkFailed('failed'))
      else w.resolve(m.result)
    }
  })
  const send = (o: unknown) => {
    try {
      child.stdin!.write(`${JSON.stringify(o)}\n`)
    } catch {}
  }
  void stop.catch(() => {})
  return {
    request: (method, params) =>
      Promise.race([
        stop,
        new Promise<any>((resolve, reject) => {
          const id = nextId++
          waiting.set(id, { resolve, reject })
          send({ jsonrpc: '2.0', id, method, params })
        }),
      ]),
    notify: (method, params) => send({ jsonrpc: '2.0', method, params }),
    close: () => {
      try {
        child.stdin!.end()
      } catch {}
      child.kill('SIGTERM')
      const hard = setTimeout(() => child.kill('SIGKILL'), 1000)
      hard.unref?.()
      child.once('exit', () => clearTimeout(hard))
    },
  }
}

/** The JSON-RPC messages in a stream of server-sent events, as they come. */
async function* sseMessages(body: ReadableStream<Uint8Array>): AsyncGenerator<{ event: string; data: string }> {
  const decoder = new TextDecoder()
  let buf = ''
  let event = 'message'
  let data: string[] = []
  const reader = body.getReader()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) return
      buf += decoder.decode(value, { stream: true })
      if (buf.length > LINE_MAX) throw new TalkFailed('failed')
      for (let at = buf.indexOf('\n'); at >= 0; at = buf.indexOf('\n')) {
        const line = buf.slice(0, at).replace(/\r$/, '')
        buf = buf.slice(at + 1)
        if (line === '') {
          if (data.length) yield { event, data: data.join('\n') }
          event = 'message'
          data = []
        } else if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
      }
    }
  } finally {
    await reader.cancel().catch(() => {})
  }
}

const authTrouble = (status: number) => (status === 401 || status === 403 ? new TalkFailed('needs_auth') : new TalkFailed('failed'))

/** Streamable HTTP: each request a POST whose answer is JSON or a stream with the answer in it. */
function httpWire(url: string, headers: Record<string, string>, signal: AbortSignal): Wire {
  let nextId = 1
  let session: string | null = null
  const post = async (o: unknown) => {
    const res = await fetch(url, {
      method: 'POST',
      redirect: 'error',
      signal,
      headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...headers, ...(session ? { 'mcp-session-id': session } : {}) },
      body: JSON.stringify(o),
    })
    session = res.headers.get('mcp-session-id') ?? session
    return res
  }
  return {
    request: async (method, params) => {
      const id = nextId++
      const res = await post({ jsonrpc: '2.0', id, method, params })
      if (!res.ok) throw authTrouble(res.status)
      const type = res.headers.get('content-type') ?? ''
      if (type.includes('text/event-stream') && res.body) {
        for await (const m of sseMessages(res.body)) {
          let j: any
          try {
            j = JSON.parse(m.data)
          } catch {
            continue
          }
          if (j?.id !== id) continue
          if (j.error) throw new TalkFailed('failed')
          return j.result
        }
        throw new TalkFailed('failed')
      }
      const j: any = await res.json()
      if (j?.error) throw new TalkFailed('failed')
      return j?.result
    },
    notify: (method, params) => void post({ jsonrpc: '2.0', method, params }).catch(() => {}),
    close: () => {},
  }
}

/** The older SSE transport: a GET stream that names where to POST, and answers on that stream. */
async function sseWire(url: string, headers: Record<string, string>, signal: AbortSignal): Promise<Wire> {
  const res = await fetch(url, { method: 'GET', redirect: 'error', signal, headers: { accept: 'text/event-stream', ...headers } })
  if (!res.ok || !res.body) throw authTrouble(res.status)
  const stream = sseMessages(res.body)
  const first = await stream.next()
  if (first.done || first.value.event !== 'endpoint') throw new TalkFailed('failed')
  const target = new URL(first.value.data, url)
  if (target.origin !== new URL(url).origin) throw new TalkFailed('failed')
  let nextId = 1
  const waiting = new Map<number, { resolve: (v: any) => void; reject: (e: unknown) => void }>()
  void (async () => {
    try {
      for await (const m of stream) {
        let j: any
        try {
          j = JSON.parse(m.data)
        } catch {
          continue
        }
        const w = typeof j?.id === 'number' ? waiting.get(j.id) : undefined
        if (!w) continue
        waiting.delete(j.id)
        if (j.error) w.reject(new TalkFailed('failed'))
        else w.resolve(j.result)
      }
    } catch {}
    for (const w of waiting.values()) w.reject(new TalkFailed('failed'))
  })()
  const post = (o: unknown) => fetch(target, { method: 'POST', redirect: 'error', signal, headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(o) })
  return {
    request: (method, params) =>
      new Promise((resolve, reject) => {
        const id = nextId++
        waiting.set(id, { resolve, reject })
        post({ jsonrpc: '2.0', id, method, params })
          .then((r) => {
            if (!r.ok) {
              waiting.delete(id)
              reject(authTrouble(r.status))
            }
          })
          .catch(reject)
      }),
    notify: (method, params) => void post({ jsonrpc: '2.0', method, params }).catch(() => {}),
    close: () => {},
  }
}

async function listAll(wire: Wire, method: string, key: string): Promise<unknown[]> {
  const out: unknown[] = []
  let cursor: unknown
  for (let i = 0; i < PAGES_MAX; i++) {
    const r = await wire.request(method, cursor ? { cursor } : {})
    if (Array.isArray(r?.[key])) out.push(...r[key])
    cursor = r?.nextCursor
    if (typeof cursor !== 'string' || !cursor) break
  }
  return out
}

/** A server's own account of itself. [config] is its stored entry, secrets and all: they go to the server and nowhere else. */
export async function talkTo(config: Record<string, unknown>, cwd: string = HOME, timeoutMs: number = TALK_MS): Promise<RawFacts> {
  const abort = new AbortController()
  const stopped = new Promise<never>((_, reject) => {
    abort.signal.addEventListener('abort', () => reject(new TalkFailed('too_slow')), { once: true })
  })
  void stopped.catch(() => {})
  const timer = setTimeout(() => abort.abort(), timeoutMs)
  timer.unref?.()
  let wire: Wire | undefined
  const strings = (t: unknown) => Object.fromEntries(Object.entries((t && typeof t === 'object' ? t : {}) as Record<string, unknown>).flatMap(([k, v]) => (typeof v === 'string' ? [[k, expandVars(v)]] : [])))
  try {
    const type = config.type ?? 'stdio'
    if (type === 'stdio') {
      if (typeof config.command !== 'string') throw new TalkFailed('failed')
      const args = Array.isArray(config.args) ? config.args.filter((a): a is string => typeof a === 'string').map((a) => expandVars(a)) : []
      wire = stdioWire(expandVars(config.command), args, strings(config.env), cwd, stopped)
    } else if ((type === 'http' || type === 'sse') && typeof config.url === 'string' && !config.headersHelper) {
      const url = expandVars(config.url)
      wire = type === 'http' ? httpWire(url, strings(config.headers), abort.signal) : await Promise.race([sseWire(url, strings(config.headers), abort.signal), stopped])
    } else throw new TalkFailed('no_direct')
    const go = async (w: Wire): Promise<RawFacts> => {
      const init = await w.request('initialize', { protocolVersion: PROTOCOL, capabilities: {}, clientInfo: { name: 'hypernova', version: '1' } })
      w.notify('notifications/initialized', {})
      const caps = init?.capabilities ?? {}
      const tools = caps.tools === undefined ? [] : await listAll(w, 'tools/list', 'tools')
      const prompts = caps.prompts === undefined ? [] : await listAll(w, 'prompts/list', 'prompts').catch(() => [])
      const resources = caps.resources === undefined ? [] : await listAll(w, 'resources/list', 'resources').catch(() => [])
      return { serverInfo: init?.serverInfo, instructions: init?.instructions, tools, prompts, resources }
    }
    return await Promise.race([go(wire), stopped])
  } catch (e) {
    if (e instanceof TalkFailed) throw e
    if (abort.signal.aborted) throw new TalkFailed('too_slow')
    throw new TalkFailed('failed')
  } finally {
    clearTimeout(timer)
    wire?.close()
    abort.abort()
  }
}
