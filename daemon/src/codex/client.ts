// The daemon's one connection to Codex's shared app-server, kept only while the owner uses Codex
// (settings.codex). It reconnects by itself, waiting longer each time (1 s up to a minute).
import { log, writeJson } from '../util.ts'
import { saveSettings, settings } from '../store.ts'
import { CODEX_MIN, atLeast, codexVersion, daemonSocket, findCodex } from './binary.ts'
import { CODEX_STATE, type CodexStateFile } from './launch.ts'
import { CodexRpc, type RpcMessage } from './rpc.ts'

/** The owner uses Codex here: Setup turned it on, or a session was started on it. */
export const codexEnabled = () => settings.codex === true

/** An error a route answers as it is (http.ts reads `status` and `code`). */
export class CodexError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** The installed Codex: where it is, its version, and whether it's one Hypernova can use. */
export function codexBinary(): { path: string | null; version: string | null; ok: boolean } {
  const path = findCodex()
  const version = path ? codexVersion(path) : null
  return { path, version, ok: !!version && atLeast(version, CODEX_MIN) }
}

let conn: CodexRpc | null = null
let connecting: Promise<CodexRpc> | null = null
let socketPath: string | null = null
let backoff = 1000
let retry: NodeJS.Timeout | null = null
/** Why the last connection failed (shown in Setup and on the phone), null while it's fine. */
let problem: string | null = null
export const codexProblem = () => problem
export const codexConnected = () => !!conn?.open
/** The app-server's socket as last reached (a Codex holder's TUI goes there through the holder). */
export const codexSocketPath = () => socketPath

const notes: ((m: RpcMessage) => void)[] = []
const requests: ((m: RpcMessage, c: CodexRpc) => void)[] = []
const joined: ((c: CodexRpc) => void)[] = []
const changes: (() => void)[] = []
/** Every notification from Codex (events.ts, account.ts). */
export const onCodexNotification = (f: (m: RpcMessage) => void) => void notes.push(f)
/** Every request Codex sends (approvals.ts), with the connection it came on, the one to answer on. */
export const onCodexRequest = (f: (m: RpcMessage, c: CodexRpc) => void) => void requests.push(f)
/** A new connection is up (threads are joined again, the account read). */
export const onCodexConnected = (f: (c: CodexRpc) => void) => void joined.push(f)
/** The connection came or went, or Codex was turned on or off. */
export const onCodexChange = (f: () => void) => void changes.push(f)
const turnedOn: (() => void)[] = []
/** The owner just turned Codex on (hooks.ts puts the shell's `codex` in). */
export const onCodexEnabled = (f: () => void) => void turnedOn.push(f)
const each = <T, U = undefined>(fs: ((x: T, y: U) => void)[], x: T, y?: U) => {
  for (const f of fs) {
    try {
      f(x, y as U)
    } catch (e) {
      log('codex handler failed', e)
    }
  }
}

/** The connection if it's up now, else null: for what may wait for the next one (no new attempt). */
export const connected = (): CodexRpc | null => (conn?.open ? conn : null)

/** The connection, made now when there's none (one attempt at a time). Throws a CodexError. */
export function rpc(): Promise<CodexRpc> {
  if (conn?.open) return Promise.resolve(conn)
  if (!codexEnabled()) return Promise.reject(new CodexError(409, 'codex_off', 'Codex is turned off for Hypernova. Turn it on in Setup on your computer.'))
  connecting ??= open().finally(() => {
    connecting = null
  })
  return connecting
}

async function open(): Promise<CodexRpc> {
  const b = codexBinary()
  if (!b.path) throw new CodexError(409, 'no_codex', 'Codex isn’t installed on this computer.')
  if (!b.ok) throw new CodexError(409, 'codex_too_old', `Hypernova needs Codex ${CODEX_MIN} or later (this one is ${b.version ?? 'unknown'}). Run \`codex update\`.`)
  const c = new CodexRpc()
  c.onNotification = (m) => each(notes, m)
  c.onRequest = (m) => each(requests, m, c)
  c.onClose = () => {
    if (conn !== c) return
    conn = null
    log('codex: disconnected')
    writeState(false)
    each(changes, undefined)
    later()
  }
  try {
    socketPath = await daemonSocket(b.path)
    await c.connect(socketPath)
    // Turned off while it connected: it stays off.
    if (!codexEnabled()) {
      c.close()
      throw new CodexError(409, 'codex_off', 'Codex is turned off for Hypernova.')
    }
  } catch (e) {
    if (e instanceof CodexError && e.code === 'codex_off') throw e
    c.close()
    const was = problem
    problem = `Couldn’t reach Codex: ${(e as Error).message}`
    if (was !== problem) {
      log('codex:', problem)
      each(changes, undefined)
    }
    later()
    throw new CodexError(503, 'codex_unavailable', problem)
  }
  conn = c
  backoff = 1000
  problem = null
  log('codex: connected', b.version, socketPath)
  writeState(true)
  each(joined, c)
  each(changes, undefined)
  return c
}

/** Try again later while Codex is on, each wait twice the last (a minute at most). */
function later(): void {
  if (!codexEnabled() || retry) return
  retry = setTimeout(() => {
    retry = null
    rpc().catch(() => {})
  }, backoff)
  backoff = Math.min(60_000, backoff * 2)
}

function writeState(ok: boolean): void {
  // Only a connection says where Codex is: off, nothing is looked up.
  const b = ok ? codexBinary() : { path: null, version: null }
  const s: CodexStateFile = { ok, socket: ok ? socketPath : null, binary: b.path, version: b.version }
  try {
    writeJson(CODEX_STATE, s, 0o600)
  } catch {}
}

/** At start: connect when the owner uses Codex. */
export function startCodex(): void {
  if (codexEnabled()) rpc().catch(() => {})
  else writeState(false)
}

/** Codex on or off for Hypernova (Setup, or the first Codex session from the phone). */
export function setCodexEnabled(on: boolean): void {
  if (codexEnabled() === on) return
  settings.codex = on
  saveSettings()
  if (on) {
    backoff = 1000
    rpc().catch(() => {})
    each(turnedOn, undefined)
  } else {
    if (retry) clearTimeout(retry)
    retry = null
    const c = conn
    conn = null
    c?.close()
    problem = null
    writeState(false)
  }
  each(changes, undefined)
}

/** Tests: drop the connection (the next call makes a new one). */
export function dropCodexForTests(): void {
  const c = conn
  conn = null
  c?.close()
}
