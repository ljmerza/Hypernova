// A stand-in for Codex's shared app-server on a scratch unix socket (docs/specs/codex-app-server.md):
// it keeps what clients asked, answers as 0.163.0 did, plays a turn out by itself, and can push
// notifications and requests to every connection, as the real one does to clients on a thread.
import { createServer, type Server } from 'node:http'
import { chmodSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { WebSocketServer, type WebSocket } from 'ws'

export type Call = { method: string; params: any; ws: WebSocket }
type Handler = (params: any, ws: WebSocket) => unknown

/** A script answering `--version` as the installed Codex would ([version]). */
export function fakeCodexBin(path: string, version = '0.163.0'): string {
  writeFileSync(path, `#!/bin/sh\n[ "$1" = "--version" ] && echo "codex-cli ${version}" && exit 0\nexit 0\n`)
  chmodSync(path, 0o755)
  return path
}

export class FakeCodex {
  calls: Call[] = []
  /** Answers clients sent to the server's own requests, by request id. */
  answers = new Map<number | string, unknown>()
  /** Threads with nothing written down yet: thread/resume refuses them, as the real one does. */
  empty = new Set<string>()
  /** turn/start plays its turn out (user item, a streamed reply, the end) unless this is false. */
  autoTurn = true
  /** The reply each turn streams. */
  reply = 'OK'
  /** Turns running now, by thread. */
  running = new Map<string, string>()
  /** Turns played per thread (their tokens add up). */
  private tokens = new Map<string, number>()
  private server: Server | null = null
  private wss: WebSocketServer | null = null
  private clients = new Set<WebSocket>()
  private waiters: { test: (c: Call) => boolean; resolve: (c: Call) => void }[] = []
  private nextReq = 1000
  handlers: Record<string, Handler> = {
    initialize: () => ({ userAgent: 'fake-codex/0.163.0', platformOs: 'macos' }),
    'account/read': () => ({ account: { type: 'chatgpt', email: 'owner@example.com', planType: 'plus' }, requiresOpenaiAuth: true }),
    'account/rateLimits/read': () => ({ rateLimits: { primary: { usedPercent: 12, windowDurationMins: 300, resetsAt: Math.floor(Date.now() / 1000) + 3600 }, secondary: { usedPercent: 40, windowDurationMins: 10080, resetsAt: Math.floor(Date.now() / 1000) + 86400 }, rateLimitReachedType: null } }),
    'model/list': () => ({ data: [{ model: 'gpt-test', displayName: 'GPT-Test', description: 'The default', hidden: false, isDefault: true, supportedReasoningEfforts: [{ reasoningEffort: 'low' }, { reasoningEffort: 'high' }], defaultReasoningEffort: 'low' }, { model: 'gpt-other', displayName: 'GPT-Other', hidden: false, isDefault: false, supportedReasoningEfforts: [{ reasoningEffort: 'medium' }], defaultReasoningEffort: 'medium' }], nextCursor: null }),
    'thread/start': (p) => {
      const id = randomUUID()
      this.empty.add(id)
      return { thread: { id, path: null, status: { type: 'idle' } }, model: p?.model ?? 'gpt-test', reasoningEffort: 'low' }
    },
    'thread/inject_items': (p) => {
      this.empty.delete(p.threadId)
      return {}
    },
    'thread/resume': (p) => {
      if (this.empty.has(p.threadId)) throw { code: -32600, message: `no rollout found for thread id ${p.threadId}` }
      return { thread: { id: p.threadId, status: { type: this.running.has(p.threadId) ? 'active' : 'idle' } } }
    },
    'thread/read': (p) => ({ thread: { id: p.threadId, turns: [] } }),
    'thread/unsubscribe': () => ({ status: 'unsubscribed' }),
    'turn/start': (p) => {
      this.empty.delete(p.threadId)
      const turn = randomUUID()
      this.running.set(p.threadId, turn)
      if (this.autoTurn) void this.play(p.threadId, turn, p)
      return { turn: { id: turn, status: 'inProgress', items: [] } }
    },
    'turn/interrupt': (p) => {
      this.end(p.threadId, 'interrupted')
      return {}
    },
  }

  async start(socket: string): Promise<void> {
    const server = createServer()
    const wss = new WebSocketServer({ server })
    wss.on('connection', (ws) => {
      this.clients.add(ws)
      ws.on('close', () => this.clients.delete(ws))
      ws.on('message', (raw) => this.onMessage(ws, raw.toString()))
    })
    await new Promise<void>((resolve) => server.listen(socket, resolve))
    this.server = server
    this.wss = wss
  }

  private onMessage(ws: WebSocket, text: string): void {
    const m = JSON.parse(text)
    if (m.method === undefined && m.id != null) {
      this.answers.set(m.id, m.result ?? m.error)
      return
    }
    if (!m.method || m.id == null) return
    const call = { method: m.method, params: m.params, ws }
    this.calls.push(call)
    this.waiters = this.waiters.filter((w) => !(w.test(call) && (w.resolve(call), true)))
    const h = this.handlers[m.method]
    try {
      const result = h ? h(m.params, ws) : {}
      ws.send(JSON.stringify({ id: m.id, result }))
    } catch (e: any) {
      ws.send(JSON.stringify({ id: m.id, error: { code: e?.code ?? -32603, message: e?.message ?? String(e) } }))
    }
  }

  /** A notification to every connection. */
  notify(method: string, params: unknown): void {
    for (const ws of this.clients) ws.send(JSON.stringify({ method, params }))
  }
  /** A request to every connection, as an approval goes to each client on its thread. Returns its id. */
  request(method: string, params: unknown): number {
    const id = this.nextReq++
    for (const ws of this.clients) ws.send(JSON.stringify({ id, method, params }))
    return id
  }
  /** The next call of [method] (one already made counts when [n] says how many to have seen). */
  waitFor(method: string, test: (p: any) => boolean = () => true, ms = 5000): Promise<Call> {
    const seen = this.calls.find((c) => c.method === method && test(c.params))
    if (seen) return Promise.resolve(seen)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no ${method} in ${ms} ms`)), ms)
      this.waiters.push({ test: (c) => c.method === method && test(c.params), resolve: (c) => (clearTimeout(timer), resolve(c)) })
    })
  }
  count(method: string): number {
    return this.calls.filter((c) => c.method === method).length
  }

  /** A turn as 0.163.0 plays it: started, the user's item, a streamed reply, the end. */
  private async play(threadId: string, turn: string, p: any): Promise<void> {
    const tick = () => new Promise((r) => setTimeout(r, 30))
    await tick()
    this.notify('turn/started', { threadId, turn: { id: turn, status: 'inProgress', items: [] } })
    const text = (p.input ?? []).filter((i: any) => i.type === 'text').map((i: any) => i.text).join('\n')
    const user = { type: 'userMessage', id: randomUUID(), clientId: p.clientUserMessageId ?? null, content: [{ type: 'text', text, text_elements: [] }] }
    this.notify('item/started', { threadId, turnId: turn, item: user })
    this.notify('item/completed', { threadId, turnId: turn, item: user })
    const msg = `msg_${randomUUID().slice(0, 8)}`
    this.notify('item/started', { threadId, turnId: turn, item: { type: 'agentMessage', id: msg, text: '', phase: 'final_answer' } })
    await tick()
    this.notify('item/agentMessage/delta', { threadId, turnId: turn, itemId: msg, delta: this.reply })
    await tick()
    if (this.running.get(threadId) !== turn) return
    this.notify('item/completed', { threadId, turnId: turn, item: { type: 'agentMessage', id: msg, text: this.reply, phase: 'final_answer' } })
    // Codex counts tokens for the whole thread: each turn adds 1000 in (200 of them cached) and 50 out.
    const t = (this.tokens.get(threadId) ?? 0) + 1
    this.tokens.set(threadId, t)
    const total = { totalTokens: 1050 * t, inputTokens: 1000 * t, cachedInputTokens: 200 * t, cacheWriteInputTokens: 0, outputTokens: 50 * t, reasoningOutputTokens: 0 }
    this.notify('thread/tokenUsage/updated', { threadId, turnId: turn, tokenUsage: { total, last: { ...total, inputTokens: 1000 }, modelContextWindow: 100000 } })
    this.end(threadId, 'completed')
  }
  /** The thread's running turn ends ([status]: completed, interrupted, failed). */
  end(threadId: string, status: string): void {
    const turn = this.running.get(threadId)
    if (!turn) return
    this.running.delete(threadId)
    this.notify('turn/completed', { threadId, turn: { id: turn, status, items: [], error: status === 'failed' ? { message: 'It broke.' } : null, durationMs: 60 } })
  }

  async close(): Promise<void> {
    for (const ws of this.clients) ws.terminate()
    this.wss?.close()
    await new Promise((r) => this.server?.close(r) ?? r(null))
  }
}
