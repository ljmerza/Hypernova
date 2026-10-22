// One connection to Codex's app-server: JSON-RPC 2.0 (without the `jsonrpc` field) in WebSocket
// text frames over a unix socket (docs/specs/codex-app-server.md).
import WebSocket from 'ws'
import { connect } from 'node:net'

/**
 * A WebSocket over a unix socket. A `ws+unix:` URL would percent-encode the space in macOS's
 * "Application Support", so the socket is handed over as the connection itself.
 */
export function unixWebSocket(socket: string, path = '/rpc'): WebSocket {
  return new WebSocket(`ws://localhost${path}`, { createConnection: () => connect(socket), maxPayload: 64 * 1024 * 1024, handshakeTimeout: 5000 })
}

export type RpcMessage = { id?: string | number; method?: string; params?: any; result?: any; error?: { code: number; message: string; data?: unknown } }
export class RpcError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

export class CodexRpc {
  private ws: WebSocket | null = null
  private next = 0
  private pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>()
  /** A notification (no id). */
  onNotification: (m: RpcMessage) => void = () => {}
  /** A request from the server (an approval, a question): answered with [respond], or left for another client. */
  onRequest: (m: RpcMessage) => void = () => {}
  onClose: () => void = () => {}

  async connect(socket: string): Promise<unknown> {
    const ws = unixWebSocket(socket)
    this.ws = ws
    ws.on('message', (raw) => {
      let m: RpcMessage
      try {
        m = JSON.parse(raw.toString())
      } catch {
        return
      }
      if (m.method && m.id != null) return this.onRequest(m)
      if (m.method) return this.onNotification(m)
      const p = typeof m.id === 'number' ? this.pending.get(m.id) : undefined
      if (!p) return
      clearTimeout(p.timer)
      this.pending.delete(m.id as number)
      if (m.error) p.reject(new RpcError(m.error.code, m.error.message))
      else p.resolve(m.result)
    })
    ws.on('close', () => {
      for (const p of this.pending.values()) {
        clearTimeout(p.timer)
        p.reject(new Error('Codex disconnected'))
      }
      this.pending.clear()
      this.onClose()
    })
    ws.on('error', () => {})
    await new Promise<void>((resolve, reject) => {
      ws.once('open', resolve)
      ws.once('error', reject)
    })
    const init = await this.call('initialize', { clientInfo: { name: 'hypernova', title: 'Hypernova', version: '0.2.0' }, capabilities: { experimentalApi: true } }, 10_000)
    this.send({ method: 'initialized' })
    return init
  }

  get open(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  send(m: RpcMessage): void {
    if (!this.open) throw new Error('Codex is disconnected')
    this.ws!.send(JSON.stringify(m))
  }

  call<T = any>(method: string, params: unknown = {}, timeout = 30_000): Promise<T> {
    const id = ++this.next
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`Codex didn’t answer ${method} in time.`))
      }, timeout)
      this.pending.set(id, { resolve, reject, timer })
      try {
        this.send({ id, method, params })
      } catch (e) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(e as Error)
      }
    })
  }

  respond(id: string | number, result: unknown): void {
    this.send({ id, result })
  }

  close(): void {
    this.ws?.close()
  }
}
