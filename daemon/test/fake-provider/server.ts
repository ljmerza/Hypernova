// A fake Anthropic-compatible provider for tests (docs/specs/spec-providers.md, §5; docs/DEV-SETUP.md,
// "Fake provider"). Claude Code points ANTHROPIC_BASE_URL at it and gets canned replies, streamed
// the way Anthropic streams them; told to, it answers with another provider's errors (out of
// credits, a rate limit with its reset, a refused key, overloaded…), so switching providers, limits
// and auto-continue can be tried end to end without spending anything. `/or/…` is the same, plus
// OpenRouter's `/v1/key` balance, which the tests set.
//
// It never reaches a real model: nothing is forwarded anywhere. It listens on loopback only, and its
// control API (`/_fake/…`) wants the token in `<dir>/token` (0600). Its log keeps request lines and
// status codes, never a header's value or a body.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdirSync, writeFileSync, chmodSync } from 'node:fs'
import { join } from 'node:path'
import { errorAnswer, matches, parseFault, type Fault } from './faults.ts'

export type Balance = { remaining: number | null; usedToday: number; freeLeft: number; freeLimit: number } | { status: number }
export type LogLine = { at: number; sid: string; method: string; path: string; status: number; ms: number; fault: string | null }
export type Fake = { server: Server; port: number; token: string; url: string; close: () => Promise<void> }

const LOOPBACK = new Set(['127.0.0.1', '::1', 'localhost'])
/** OpenRouter's catalogue as `/or/v1/models` answers it (a few entries in its real shape). */
export const OPENROUTER_MODELS = [
  { id: 'openai/gpt-5', name: 'OpenAI: GPT-5', context_length: 400000, pricing: { prompt: '0.00000125', completion: '0.00001' }, supported_parameters: ['tools', 'tool_choice', 'reasoning'] },
  { id: 'fake/no-tools', name: 'Fake: No Tools', context_length: 8192, pricing: { prompt: '0.0000001', completion: '0.0000002' }, supported_parameters: ['temperature'] },
  { id: 'qwen/qwen3-coder:free', name: 'Qwen: Qwen3 Coder (free)', context_length: 262144, pricing: { prompt: '0', completion: '0' }, supported_parameters: ['tools'] },
  { id: 'anthropic/claude-sonnet-4.5', name: 'Anthropic: Claude Sonnet 4.5', context_length: 1000000, pricing: { prompt: '0.000003', completion: '0.000015' }, supported_parameters: ['tools'] },
]

/** What every canned reply says, so a test (and a person looking at the phone) can tell it's the fake. */
export const CANNED = 'Fake provider here. Nothing went to a model.'

/**
 * Start the fake on [host]:[port] (0: any free port). [dir] keeps the control token. Refuses any
 * address but loopback: it must never answer anything but this Mac.
 */
export async function startFake(o: { port?: number; host?: string; dir: string; token?: string }): Promise<Fake> {
  const host = o.host ?? '127.0.0.1'
  if (!LOOPBACK.has(host)) throw new Error(`the fake provider only listens on loopback, not ${host}`)
  mkdirSync(o.dir, { recursive: true, mode: 0o700 })
  const token = o.token ?? randomBytes(18).toString('base64url')
  const tokenPath = join(o.dir, 'token')
  writeFileSync(tokenPath, token + '\n', { mode: 0o600 })
  chmodSync(tokenPath, 0o600)

  let faults: Fault[] = []
  let seq = 0
  let reply = CANNED
  let balance: Balance = { remaining: 4.2, usedToday: 0.31, freeLeft: 37, freeLimit: 50 }
  const log: LogLine[] = []
  const note = (l: LogLine) => {
    log.push(l)
    if (log.length > 200) log.splice(0, log.length - 200)
  }

  const server = createServer((req, res) => {
    const t0 = Date.now()
    const url = new URL(req.url ?? '/', 'http://fake')
    const sid = headerOf(req, 'x-claude-code-session-id')
    let fault: string | null = null
    // The control API's own calls aren't the provider's traffic.
    if (url.pathname.startsWith('/_fake/')) {
      void route(req, res, url, sid, () => {}).catch(() => send(res, 500, { error: 'fake_failed' }))
      return
    }
    res.on('finish', () => note({ at: t0, sid: sid.slice(0, 8), method: req.method ?? '', path: url.pathname, status: res.statusCode, ms: Date.now() - t0, fault }))
    res.on('close', () => {
      // A cut stream never "finishes": still a line in the log.
      if (!res.writableFinished) note({ at: t0, sid: sid.slice(0, 8), method: req.method ?? '', path: url.pathname, status: res.statusCode, ms: Date.now() - t0, fault })
    })
    void route(req, res, url, sid, (id) => (fault = id)).catch(() => {
      if (!res.headersSent) send(res, 500, { error: 'fake_failed' })
      else res.destroy()
    })
  })

  async function route(req: IncomingMessage, res: ServerResponse, url: URL, sid: string, mark: (id: string) => void): Promise<void> {
    const path = url.pathname
    // ── Control ──
    if (path.startsWith('/_fake/')) {
      if (!tokenOk(headerOf(req, 'x-fake-token'), token)) return send(res, 401, { error: 'token' })
      const body = req.method === 'POST' ? await readJson(req) : {}
      if (path === '/_fake/faults' && req.method === 'POST') {
        const f = parseFault(body, `f${++seq}`)
        if (!f) return send(res, 400, { error: 'bad_fault' })
        faults.push(f)
        return send(res, 200, { fault: f })
      }
      if (path === '/_fake/faults' && req.method === 'GET') return send(res, 200, { faults, log })
      if (path === '/_fake/faults' && req.method === 'DELETE') {
        faults = []
        return send(res, 200, { ok: true })
      }
      const one = /^\/_fake\/faults\/([\w-]+)$/.exec(path)
      if (one && req.method === 'DELETE') {
        faults = faults.filter((f) => f.id !== one[1])
        return send(res, 200, { ok: true })
      }
      if (path === '/_fake/balance' && req.method === 'POST') {
        if (typeof body.status === 'number') balance = { status: body.status }
        else
          balance = {
            remaining: typeof body.remaining === 'number' ? body.remaining : null,
            usedToday: typeof body.usedToday === 'number' ? body.usedToday : 0,
            freeLeft: typeof body.freeLeft === 'number' ? body.freeLeft : 50,
            freeLimit: typeof body.freeLimit === 'number' ? body.freeLimit : 50,
          }
        return send(res, 200, { balance })
      }
      if (path === '/_fake/reply' && req.method === 'POST') {
        reply = typeof body.text === 'string' && body.text ? body.text.slice(0, 2000) : CANNED
        return send(res, 200, { reply })
      }
      return send(res, 404, { error: 'no_route' })
    }

    // ── The provider ──
    // `/or/…` is the same provider, dressed as OpenRouter (its balance lives there).
    const p = path.startsWith('/or/') ? path.slice(3) : path
    if (p === '/api/hello' || p === '/') return send(res, 200, { ok: true })
    if (p === '/v1/key' && req.method === 'GET') {
      if (!headerOf(req, 'authorization').startsWith('Bearer ')) return send(res, 401, { error: { code: 401, message: 'No auth credentials found' } })
      if ('status' in balance) return send(res, balance.status, { error: { code: balance.status, message: 'User not found.' } })
      const b = balance
      return send(res, 200, {
        data: {
          label: 'fake-or-key',
          limit: b.remaining == null ? null : 10,
          limit_remaining: b.remaining,
          limit_reset: null,
          usage: 1.23,
          usage_daily: b.usedToday,
          usage_weekly: b.usedToday,
          usage_monthly: b.usedToday,
          is_free_tier: false,
          free_model_daily_requests: { used: b.freeLimit - b.freeLeft, limit: b.freeLimit, remaining: b.freeLeft },
          budgets: [],
          effective_budget: null,
        },
      })
    }
    // Dressed as OpenRouter, its catalogue's shape: a model that calls tools, one that can't, a free one.
    if (p === '/v1/models' && req.method === 'GET' && path.startsWith('/or/')) return send(res, 200, { data: OPENROUTER_MODELS })
    if (p === '/v1/models' && req.method === 'GET') return send(res, 200, { data: [{ id: 'fake-model', type: 'model', display_name: 'Fake model' }] })
    if (p === '/v1/messages/count_tokens' && req.method === 'POST') {
      await readJson(req)
      return send(res, 200, { input_tokens: 1000 })
    }
    if (p !== '/v1/messages' || req.method !== 'POST') return send(res, 404, { type: 'error', error: { type: 'not_found_error', message: 'Not found' } })

    const body = await readJson(req)
    const now = Date.now()
    const f = faults.find((x) => matches(x, sid, now)) ?? null
    if (f) {
      f.used++
      mark(f.id)
      const e = errorAnswer(f, now)
      if (e) {
        res.writeHead(e.status, e.headers)
        return void res.end(e.body)
      }
      if (f.kind === 'slow') await new Promise((r) => setTimeout(r, f.ms ?? 40_000))
    }
    const model = typeof body.model === 'string' ? body.model : 'fake-model'
    if (body.stream !== true) return send(res, 200, message(model, reply))
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' })
    const events = stream(model, reply)
    if (f?.kind === 'cut') {
      // Some of the reply, then the connection drops mid-stream.
      const text = events.join('')
      res.write(text.slice(0, Math.min(text.length - 1, f.bytes ?? 600)))
      await new Promise((r) => setTimeout(r, 100))
      return void res.destroy()
    }
    if (f?.kind === 'error_event') {
      res.write(events[0])
      res.write(`event: error\ndata: ${JSON.stringify({ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } })}\n\n`)
      return void res.end()
    }
    for (const e of events) {
      res.write(e)
      await new Promise((r) => setTimeout(r, 15))
    }
    res.end()
  }

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(o.port ?? 0, host, () => resolve())
  })
  const addr = server.address()
  const port = typeof addr === 'object' && addr ? addr.port : 0
  return {
    server,
    port,
    token,
    url: `http://${host}:${port}`,
    close: () => new Promise((r) => server.close(() => r())),
  }
}

/** A whole reply, for `stream: false`. */
function message(model: string, text: string) {
  return {
    id: `msg_fake_${randomBytes(6).toString('hex')}`,
    type: 'message',
    role: 'assistant',
    model,
    content: [{ type: 'text', text }],
    stop_reason: 'end_turn',
    stop_sequence: null,
    usage: { input_tokens: 12, output_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
  }
}

/** The same reply as Anthropic's server-sent events, one string per event. */
function stream(model: string, text: string): string[] {
  const ev = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
  const m = message(model, '')
  const words = text.split(/(?<= )/)
  return [
    ev('message_start', { type: 'message_start', message: { ...m, content: [], stop_reason: null, usage: { ...m.usage, output_tokens: 1 } } }),
    ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }),
    ev('ping', { type: 'ping' }),
    ...words.map((w) => ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: w } })),
    ev('content_block_stop', { type: 'content_block_stop', index: 0 }),
    ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: words.length } }),
    ev('message_stop', { type: 'message_stop' }),
  ]
}

function headerOf(req: IncomingMessage, name: string): string {
  const v = req.headers[name]
  return Array.isArray(v) ? (v[0] ?? '') : (v ?? '')
}

function tokenOk(given: string, token: string): boolean {
  const a = Buffer.from(given)
  const b = Buffer.from(token)
  return a.length === b.length && timingSafeEqual(a, b)
}

async function readJson(req: IncomingMessage): Promise<Record<string, any>> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const c of req) {
    size += (c as Buffer).length
    if (size > 8 << 20) break
    chunks.push(c as Buffer)
  }
  try {
    const o = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}

function send(res: ServerResponse, status: number, body: unknown): void {
  if (res.headersSent) return void res.end()
  res.writeHead(status, { 'content-type': 'application/json' })
  res.end(JSON.stringify(body))
}
