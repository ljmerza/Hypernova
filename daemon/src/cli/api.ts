// The running daemon, as the `hypernova` command reaches it: its local API on 127.0.0.1 with the
// admin token from <support>/admin-token, the way the Mac app does (docs/PROTOCOL.md, "Mac app
// local API"). Every call has a short timeout and says plainly when the daemon isn't there.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Cli } from './context.ts'

export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

const token = (cli: Cli): string | null => {
  try {
    return readFileSync(join(cli.support, 'admin-token'), 'utf8').trim() || null
  } catch {
    return null
  }
}

/** A call to the local API; throws ApiError (status 0 when nothing answered). */
export async function local<T = any>(cli: Cli, method: string, path: string, body?: unknown, timeoutMs = 10_000): Promise<T> {
  const t = token(cli)
  let res: Response
  try {
    res = await fetch(`http://127.0.0.1:${cli.localPort}${path}`, {
      method,
      headers: { ...(t ? { authorization: `Bearer ${t}` } : {}), ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    })
  } catch {
    throw new ApiError(0, 'not_running', `The daemon isn’t answering on 127.0.0.1:${cli.localPort}.`)
  }
  const text = await res.text()
  let json: any = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {}
  if (!res.ok) throw new ApiError(res.status, json?.error?.code ?? 'error', json?.error?.message ?? `The daemon answered ${res.status}.`)
  return json as T
}

/**
 * A text body to the local API ([bearer]: a token of its own, such as an automation's fire token;
 * else the admin token): its status and JSON answer, or ApiError status 0 when nothing answered.
 */
export async function localText(cli: Cli, path: string, text: string, bearer: string | null = null, timeoutMs = 15_000): Promise<{ status: number; json: any }> {
  const t = bearer ?? token(cli)
  let res: Response
  try {
    res = await fetch(`http://127.0.0.1:${cli.localPort}${path}`, {
      method: 'POST',
      headers: { ...(t ? { authorization: `Bearer ${t}` } : {}), 'content-type': 'text/plain; charset=utf-8' },
      body: text,
      signal: AbortSignal.timeout(timeoutMs),
    })
  } catch {
    throw new ApiError(0, 'not_running', `The daemon isn’t answering on 127.0.0.1:${cli.localPort}.`)
  }
  const body = await res.text()
  let json: any = null
  try {
    json = body ? JSON.parse(body) : null
  } catch {}
  return { status: res.status, json }
}

/** `GET /v1/health` on a port: the daemon's name, or null when nothing answers there. */
export async function health(port: number, timeoutMs = 3000): Promise<{ ok: boolean; protocol: number; name: string } | null> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/v1/health`, { signal: AbortSignal.timeout(timeoutMs) })
    return res.ok ? ((await res.json()) as any) : null
  } catch {
    return null
  }
}

/** Waits up to [ms] for the daemon to answer on both of its loopback ports. */
export async function waitHealthy(cli: Cli, ms: number): Promise<boolean> {
  const until = Date.now() + ms
  while (Date.now() < until) {
    if ((await health(cli.apiPort, 1000)) && (await health(cli.localPort, 1000))) return true
    await new Promise((r) => setTimeout(r, 300))
  }
  return false
}
