// A shared terminal's holder (docs/PROTOCOL.md, "Shared terminals"). One per session: it owns
// claude's PTY, keeps the screen in a headless emulator so anyone can join mid-session, and
// serves clients on a unix socket: the `hn` CLI in a Mac terminal, and the daemon for phones.
// It runs detached, so restarting the daemon (or reopening the window) leaves the session
// running.
//
//   node holder.ts <spec file>
import { createServer, type Socket } from 'node:net'
import { appendFileSync, chmodSync, readFileSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'
import { execFile } from 'node:child_process'
import { LOGS, rollLog, writeJson } from '../util.ts'
import { DATA, Frames, MSG, boxLines, codexProxyPath, frame, isWorking, metaPath, modeOf, msg, registryBusy, sockPath, type HolderSpec, type Role, type TermMeta, type TermState } from './shared.ts'

type Client = { sock: Socket; role: Role; cols: number; rows: number; at: number; ready: boolean; pending: Buffer[] | null; detaching: boolean }

// The spec carries the owner's whole shell environment (keys included): read it and delete it
// before anything else can fail.
const specFile = process.argv[2]!
const spec = JSON.parse(readFileSync(specFile, 'utf8')) as HolderSpec
try {
  unlinkSync(specFile)
} catch {}
process.title = `hypernova-term ${spec.id}`

const LOG = join(LOGS, 'terminals.log')
let rolled = 0
function say(...parts: unknown[]): void {
  const line = parts.map((p) => (typeof p === 'string' ? p : p instanceof Error ? p.stack ?? p.message : JSON.stringify(p))).join(' ')
  try {
    if (!rolled++) rollLog(LOG)
    appendFileSync(LOG, `${new Date().toISOString()} [${spec.id}] ${line}\n`, { mode: 0o600 })
  } catch {}
}

let pty: typeof import('node-pty')
let xterm: typeof import('@xterm/headless')
let serialize: typeof import('@xterm/addon-serialize')
try {
  pty = (await import('node-pty')).default
  xterm = (await import('@xterm/headless')).default
  serialize = (await import('@xterm/addon-serialize')).default
} catch (e) {
  // `hn run` sees the holder die and runs plain claude.
  say('could not load the terminal modules', e)
  process.exit(1)
}
type IPty = ReturnType<typeof pty.spawn>

process.on('uncaughtException', (e) => say('uncaught', e))
process.on('unhandledRejection', (e) => say('unhandled', e))
// No controlling terminal, but be explicit: hang-ups and ^C belong to claude, not to us.
process.on('SIGHUP', () => {})
process.on('SIGINT', () => {})
process.on('SIGTERM', () => end('SIGTERM'))

const clamp = (v: unknown, lo: number, hi: number, d: number) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Math.max(lo, Math.min(hi, Math.floor(Number(v)))) : d)
let cols = clamp(spec.cols, 20, 500, 100)
let rows = clamp(spec.rows, 6, 300, 30)
const term = new xterm.Terminal({ cols, rows, scrollback: 10_000, allowProposedApi: true })
const serializer = new serialize.SerializeAddon()
term.loadAddon(serializer)
// Widths as Claude Code counts them (Bun.stringWidth: an emoji sequence is one wide character), and
// as the phone's page counts them, or a snapshot drawn there shifts its columns. Without the add-on
// (an install that didn't get it) the screen still works by xterm's own widths, and hello says so.
try {
  const { UnicodeGraphemesAddon } = (await import('@xterm/addon-unicode-graphemes')).default
  term.loadAddon(new UnicodeGraphemesAddon())
} catch (e) {
  say('could not load the widths add-on', e)
}

const clients = new Set<Client>()
let child: IPty | null = null
let everStarted = false
let title = ''
let exited: { code: number; signal: number | null; note?: string; fallback?: boolean } | null = null
let lastOutputAt = Date.now()
let lastViewerAt = Date.now()
const createdAt = Date.now()

const viewers = (role?: Role) => [...clients].filter((c) => (role ? c.role === role : c.role !== 'control')).length
const codex = spec.agent === 'codex'
/** The Codex thread the TUI runs now (it changes on /new and /resume in the TUI), once known. */
let threadId: string | null = codex ? spec.sessionId : null
const state = (): TermState => ({ cols, rows, title, childPid: child?.pid ?? null, tty: viewers('tty'), phone: viewers('phone'), started: !!child, ...(codex ? { sessionId: threadId } : {}) })

term.onTitleChange((t) => {
  title = t.replace(/^[^\w/~.]+\s*/u, '').trim() // "✳ Claude Code" → "Claude Code"
  broadcastState()
})
// Answers to claude's terminal queries (device attributes, cursor position): only when no
// real terminal is attached to answer them itself.
term.onData((d) => {
  if (!viewers() && child) child.write(d)
})

// ── Clients ──────────────────────────────────────────────────────────────
function write(c: Client, f: Buffer): void {
  if (c.sock.destroyed) return
  // A client that stopped reading (32 MB behind) is dropped; it can attach again.
  if (c.sock.writableLength > 32 * 1024 * 1024) {
    say('dropping a client that stopped reading')
    c.sock.destroy()
    return
  }
  c.sock.write(f)
}
/** Live output, in order: queued behind a snapshot that's still being made. */
function deliver(c: Client, f: Buffer): void {
  if (c.pending) c.pending.push(f)
  else if (c.ready) write(c, f)
}

function broadcastState(): void {
  const f = msg({ t: 'state', ...state() })
  for (const c of clients) deliver(c, f)
  saveMetaSoon()
}

/**
 * The PTY takes the size of whoever used it last, like tmux: a Mac window when it attaches,
 * resizes or types; a phone when it types (or attaches with no Mac window open). A phone that
 * is only watching follows the Mac's size instead of taking it.
 */
function resizeToActive(): void {
  let best: Client | null = null
  for (const c of clients) if (c.role !== 'control' && c.cols > 0 && c.at > 0 && (!best || c.at > best.at)) best = c
  if (!best || (best.cols === cols && best.rows === rows)) return
  cols = best.cols
  rows = best.rows
  term.resize(cols, rows)
  try {
    child?.resize(cols, rows)
  } catch {}
  broadcastState()
}

// A session started in a Mac terminal ends when its last window closes, as plain claude
// would, unless a phone has it open or the window detached on purpose (Ctrl+\ in `hn`).
const CLOSE_GRACE_MS = 30_000
let closeTimer: NodeJS.Timeout | null = null
function windowClosed(c: Client): void {
  if (spec.origin !== 'terminal' || c.role !== 'tty' || c.detaching || viewers()) return
  if (closeTimer) clearTimeout(closeTimer)
  closeTimer = setTimeout(() => {
    closeTimer = null
    if (!viewers()) end('its terminal window closed')
  }, CLOSE_GRACE_MS)
}

function onClient(sock: Socket): void {
  const c: Client = { sock, role: 'control', cols: 0, rows: 0, at: 0, ready: false, pending: null, detaching: false }
  const frames = new Frames((type, p) => onFrame(c, type, p))
  sock.on('data', (b: Buffer) => {
    try {
      frames.push(b)
    } catch (e) {
      say('bad client stream', e)
      sock.destroy()
    }
  })
  sock.on('close', () => {
    if (!clients.delete(c)) return
    if (c.role !== 'control') lastViewerAt = Date.now()
    windowClosed(c)
    resizeToActive()
    broadcastState()
  })
  sock.on('error', () => {})
}

function onFrame(c: Client, type: number, p: Buffer): void {
  if (type === DATA) {
    if (c.role === 'control' || !child) return
    child.write(p)
    c.at = Date.now()
    resizeToActive()
    if (p.includes(0x1a) || p.includes('122;5u')) watchSuspend()
    return
  }
  if (type !== MSG) return
  let m: any
  try {
    m = JSON.parse(p.toString('utf8'))
  } catch {
    return
  }
  switch (m?.t) {
    case 'hello': {
      if (clients.has(c)) return
      c.role = m.role === 'tty' || m.role === 'phone' ? m.role : 'control'
      c.cols = clamp(m.cols, 20, 500, 0)
      c.rows = clamp(m.rows, 6, 300, 0)
      // A phone joining a session that's open on the Mac watches at the Mac's size.
      c.at = c.role === 'tty' || (c.role === 'phone' && !viewers('tty')) ? Date.now() : 0
      clients.add(c)
      // caps: what this holder can do (the daemon does it itself for holders started before).
      write(c, msg({ t: 'hello', v: 1, id: spec.id, caps: ['replace', 'mode', 'queued'], unicode: term.unicode.activeVersion, ...state() }))
      if (c.role === 'control') {
        c.ready = true
      } else {
        lastViewerAt = Date.now()
        if (closeTimer) {
          clearTimeout(closeTimer)
          closeTimer = null
        }
        resizeToActive()
        if (m.snapshot) {
          // Everything written to the emulator so far is in the snapshot; anything newer waits behind it.
          c.pending = []
          term.write('', () => {
            write(c, msg({ t: 'snapshot', data: serializer.serialize({ scrollback: c.role === 'phone' ? 2000 : 5000 }) }))
            for (const f of c.pending ?? []) write(c, f)
            c.pending = null
            c.ready = true
          })
        } else c.ready = true
      }
      if (c.role === 'tty') start()
      if (exited) deliver(c, msg({ t: 'exit', ...exited }))
      broadcastState()
      return
    }
    case 'resize':
      c.cols = clamp(m.cols, 20, 500, c.cols)
      c.rows = clamp(m.rows, 6, 300, c.rows)
      c.at = Date.now()
      resizeToActive()
      return
    case 'detach':
      c.detaching = true
      return
    case 'paste': {
      // One at a time: two prompts at once would both find the box empty and paste into it together.
      const text = String(m.text ?? '')
      const replace = m.replace === true
      typing = typing.then(() => typeText(text, replace)).then((r) => write(c, msg({ t: 'pasted', id: m.id ?? null, ...r })))
      return
    }
    case 'mode': {
      const want = String(m.mode ?? '')
      typing = typing.then(() => switchMode(want)).then((r) => write(c, msg({ t: 'moded', id: m.id ?? null, ...r })))
      return
    }
    case 'keys':
      if (child && typeof m.data === 'string') child.write(m.data)
      return
    case 'kill':
      end('asked')
      return
    case 'move': {
      // The conversation goes on in another session (the agent switched, switch.ts): windows and
      // phones follow it to that session's holder ([to]), or read [text] when there's none.
      const f = msg({ t: 'moved', to: typeof m.to === 'string' ? m.to : null, text: typeof m.text === 'string' ? m.text : null })
      for (const x of clients) if (x.role !== 'control') deliver(x, f)
      setTimeout(() => end('its conversation moved to another session'), 300)
      return
    }
    case 'screen':
      write(c, msg({ t: 'screen', text: screenText(), title }))
      return
  }
}

// ── The child ────────────────────────────────────────────────────────────
let waitTimer: NodeJS.Timeout | null = null
function start(): void {
  if (child || exited) return
  if (waitTimer) clearTimeout(waitTimer)
  try {
    child = pty.spawn(spec.file, spec.args, { name: 'xterm-256color', cols, rows, cwd: spec.cwd, env: spec.env })
  } catch (e) {
    say('spawn failed', e)
    // Nothing was drawn yet: `hn run` falls back to plain claude.
    finish(127, null, `Hypernova couldn't start Claude Code: ${(e as Error).message}`, true)
    return
  }
  everStarted = true
  say('started', spec.file, JSON.stringify(spec.args.map((a) => (a.length > 60 ? a.slice(0, 60) + '…' : a))), 'pid', child.pid, 'in', spec.cwd)
  child.onData((d) => {
    lastOutputAt = Date.now()
    term.write(d)
    const f = frame(DATA, Buffer.from(d, 'utf8'))
    for (const c of clients) if (c.role !== 'control') deliver(c, f)
  })
  child.onExit(({ exitCode, signal }) => finish(exitCode, signal ?? null))
  if (spec.autoTrust) void (codex ? acceptCodexTrust() : acceptTrust())
  broadcastState()
}

// ── Codex ────────────────────────────────────────────────────────────────
/**
 * Codex's TUI talks to the app-server through this holder's own socket: every frame passes as it
 * is, and the answers to its thread/start, thread/resume and thread/fork say which thread it runs
 * (its temporary threads, which write titles, don't count: docs/specs/codex-app-server.md, 1.6).
 */
let closeProxy: (() => void) | null = null
async function startCodexProxy(): Promise<void> {
  const { default: WebSocket, WebSocketServer } = await import('ws')
  const { createServer: createHttp } = await import('node:http')
  const { unixWebSocket } = await import('../codex/rpc.ts')
  const path = codexProxyPath(spec.id)
  const http = createHttp()
  const wss = new WebSocketServer({ server: http, maxPayload: 64 * 1024 * 1024 })
  wss.on('connection', (down) => {
    const up = unixWebSocket(spec.codexSocket!)
    const held: string[] = []
    const asked = new Map<unknown, string>()
    down.on('message', (raw) => {
      const s = raw.toString()
      try {
        const m = JSON.parse(s)
        if (m?.method && m.id != null) asked.set(m.id, m.method)
      } catch {}
      if (up.readyState === WebSocket.OPEN) up.send(s)
      else held.push(s)
    })
    up.on('open', () => {
      for (const s of held.splice(0)) up.send(s)
    })
    up.on('message', (raw) => {
      const s = raw.toString()
      try {
        const m = JSON.parse(s)
        if (m?.id != null && !m.method) {
          const method = asked.get(m.id)
          asked.delete(m.id)
          if (/^thread\/(start|resume|fork)$/.test(method ?? '') && !String(m.id).startsWith('temporary-') && typeof m.result?.thread?.id === 'string') onThread(m.result.thread.id)
        }
      } catch {}
      if (down.readyState === WebSocket.OPEN) down.send(s)
    })
    const close = () => {
      up.terminate()
      down.terminate()
    }
    up.on('close', close)
    up.on('error', close)
    down.on('close', close)
    down.on('error', close)
  })
  try {
    unlinkSync(path)
  } catch {}
  await new Promise<void>((resolve, reject) => {
    http.once('error', reject)
    http.listen(path, () => {
      chmodSync(path, 0o600)
      resolve()
    })
  })
  closeProxy = () => {
    wss.close()
    http.close()
    try {
      unlinkSync(path)
    } catch {}
  }
}
function onThread(id: string): void {
  if (id === threadId) return
  threadId = id
  say('codex thread', id)
  broadcastState()
  saveMeta()
}

/** The owner picked this folder in Hypernova: say yes to Codex's "Trust this folder?". */
async function acceptCodexTrust(): Promise<void> {
  const t0 = Date.now()
  while (Date.now() - t0 < 25_000 && child && !exited) {
    await sleep(300)
    if (!/Trust this folder\?/.test(screenText())) continue
    await sleep(600)
    if (/›\s*1\.\s*Trust and continue/.test(screenText())) {
      child.write('\r')
      say('accepted the codex folder trust prompt')
    } else say('could not accept the codex folder trust prompt')
    return
  }
}

// ── Typing a prompt for the phone ────────────────────────────────────────
/** `queued`: Claude was working, so Claude Code queued the prompt rather than starting a turn. */
type Typed = { ok: boolean; reason?: 'not_started' | 'no_input' | 'busy_input' | 'not_seen' | 'not_sent'; text?: string; queued?: boolean }
const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))
let typing: Promise<void> = Promise.resolve()

function screenLines() {
  const b = term.buffer.active
  const out: { s: string; line: ReturnType<typeof b.getLine> }[] = []
  for (let i = b.baseY; i < b.baseY + term.rows; i++) {
    const line = b.getLine(i)
    out.push({ s: line?.translateToString(true) ?? '', line })
  }
  return out
}
function screenText(): string {
  return screenLines()
    .map((l) => l.s)
    .join('\n')
}
/**
 * The screen with the lines the terminal wrapped joined back, as claude wrote them. A line that
 * wraps keeps its trailing space, where the break fell between two words.
 */
function screenUnwrapped(): string {
  const lines = screenLines()
  const out: string[] = []
  lines.forEach(({ s, line }, i) => {
    const text = lines[i + 1]?.line?.isWrapped ? (line?.translateToString(false) ?? s) : s
    if (line?.isWrapped && out.length) out[out.length - 1] += text
    else out.push(text)
  })
  return out.join('\n')
}

/**
 * The text in Claude Code's input box (between two rules, starting with ❯), or null when no
 * input box is showing. Dim text is the empty box's suggestion, not input.
 */
function inputBox(): string | null {
  return findBox()?.text ?? null
}

/** The input box: its text, and the lines above (the spinner) and below it (status line, mode). */
function findBox(): { text: string; above: string[]; below: string[] } | null {
  const lines = screenLines()
  const at = boxLines(lines.map((l) => l.s))
  if (!at) return null
  {
    const body = lines.slice(at.top + 1, at.bottom)
    const text = body
      .map((l, k) => {
        let s = ''
        for (let x = 0; l.line && x < l.line.length; x++) {
          const cell = l.line.getCell(x)
          if (!cell) break
          s += cell.isDim() ? ' ' : cell.getChars() || ' '
        }
        return k === 0 ? s.replace(/^\s*[❯>!]\s?/, '') : s
      })
      .join('\n')
      .trim()
    return { text, above: at.above, below: at.below }
  }
}

// Shell mode shows a typed "!" as the box's prompt, not as text, so compare without it.
const squash = (s: string) => s.replace(/\s+/g, '').replace(/^!/, '')
/** The last prompt typed for the phone (squashed): what's left of it in the box is ours to clear. */
let lastTyped = ''
const ours = (box: string) => !!lastTyped && (squash(box) === lastTyped || (/^\[Pasted text #\d+[^\]]*\]$/.test(box.trim()) && lastTyped.length > 200))

/**
 * Empty the input box: Ctrl+C clears it while Claude is idle (never pressed on an empty box,
 * never while Claude works: that would stop it). Idle means the screen has settled, Claude
 * Code's registry doesn't say busy, and no spinner shows.
 */
async function clearBox(): Promise<boolean> {
  for (let i = 0; i < 15 && Date.now() - lastOutputAt < 800; i++) await sleep(100)
  const b = findBox()
  if (!child || !b?.text || Date.now() - lastOutputAt < 800 || registryBusy(child.pid) || isWorking(b)) return false
  child.write('\x03')
  for (let i = 0; i < 15; i++) {
    await sleep(100)
    const t = inputBox()
    if (t === '') return true
    if (t === null) return false
  }
  return false
}

/**
 * Type a prompt the way a paste arrives, then press Enter, but only into Claude Code's own,
 * empty input box, and Enter only once the text is visibly there. A dialog (a permission
 * question, a menu) replaces the input box, so Enter never lands on one. Sent means the box
 * emptied: a menu that opened over it (file suggestions) would have taken the Enter.
 *
 * Text already in the box stops it, with that text in the answer, unless it's what's left of
 * the phone's own last prompt (Claude Code puts an interrupted prompt back in the box) or the
 * owner chose to replace it; then it's cleared first, while Claude is idle.
 *
 * While Claude works the box is still there, under the spinner: Enter queues the prompt and
 * empties the box, and the answer says it was queued.
 */
async function typeText(text: string, replace = false): Promise<Typed> {
  if (!child) return { ok: false, reason: 'not_started' }
  const box = findBox()
  if (box === null) return { ok: false, reason: 'no_input' }
  const queued = registryBusy(child.pid) || isWorking(box)
  if (box.text && !((replace || ours(box.text)) && (await clearBox()))) return { ok: false, reason: 'busy_input', text: box.text.slice(0, 500) }
  let clean = text.replace(/\x1b\[20[01]~/g, '').replace(/\r\n?/g, '\n')
  // Ending on an @-mention opens file suggestions; a space after it keeps them closed.
  if (/(^|\s)@\S*$/.test(clean)) clean += ' '
  lastTyped = squash(clean)
  if (term.modes.bracketedPasteMode) child.write(`\x1b[200~${clean}\x1b[201~`)
  else child.write(clean.replace(/\n/g, ' '))
  const want = squash(clean).slice(0, 20)
  const shows = (box: string | null) => box !== null && (squash(box).startsWith(want) || /\[Pasted text/.test(box))
  for (let i = 0; i < 20; i++) {
    await sleep(100)
    if (!child) return { ok: false, reason: 'not_started' }
    if (!shows(inputBox())) continue
    child.write('\r')
    // A prompt with many files can take Claude Code a few seconds to take in.
    for (let j = 0; j < 60; j++) {
      await sleep(100)
      if (!child || !shows(inputBox())) return { ok: true, queued }
    }
    return { ok: false, reason: 'not_sent' }
  }
  return { ok: false, reason: 'not_seen' }
}

/**
 * Switch the permission mode from the phone: Shift+Tab until the footer shows it (a cycle at
 * most). A mode the session can't reach (bypass, unless started with it) leaves it as it was.
 */
async function switchMode(want: string): Promise<{ ok: boolean; mode?: string | null; reason?: string }> {
  if (!child) return { ok: false, reason: 'not_started' }
  const first = findBox()
  if (!first) return { ok: false, reason: 'no_input' }
  const start = modeOf(first.below)
  const cycleTo = async (target: string | null): Promise<boolean> => {
    for (let i = 0; i < 6; i++) {
      const b = findBox()
      if (!b || !child) return false
      const now = modeOf(b.below)
      if (now === target) return true
      child.write('\x1b[Z')
      for (let k = 0; k < 12; k++) {
        await sleep(80)
        const f = findBox()
        if (f && modeOf(f.below) !== now) break
      }
    }
    return false
  }
  if (await cycleTo(want)) return { ok: true, mode: want }
  if (start) await cycleTo(start)
  return { ok: false, reason: findBox() ? 'unavailable' : 'no_input', mode: modeOf(findBox()?.below ?? []) }
}

/** The owner picked this folder in Hypernova: say yes to Claude Code's "trust this folder?". */
async function acceptTrust(): Promise<void> {
  const t0 = Date.now()
  while (Date.now() - t0 < 25_000 && child && !exited) {
    await sleep(300)
    if (!/Yes, I trust this folder/.test(screenText())) continue
    await sleep(900) // the prompt draws before it listens
    for (let i = 0; i < 6 && child && !exited; i++) {
      if (/❯\s*Yes, I trust this folder/.test(screenText())) {
        child.write('\r')
        say('accepted the folder trust prompt')
        return
      }
      child.write('\x1b[B')
      await sleep(400)
    }
    say('could not accept the folder trust prompt')
    return
  }
}

/** ^Z would stop claude where nobody can `fg` it: wake it straight back up. */
function watchSuspend(): void {
  for (const ms of [300, 1200]) {
    setTimeout(() => {
      if (!child) return
      const pid = child.pid
      execFile('/bin/ps', ['-o', 'stat=', '-p', String(pid)], (e, out) => {
        if (!e && out.trim().startsWith('T')) {
          try {
            process.kill(pid, 'SIGCONT')
          } catch {}
        }
      })
    }, ms)
  }
}

function end(why: string): void {
  if (!child || exited) return shutdown()
  say('ending:', why)
  const pid = child.pid
  try {
    child.kill('SIGHUP')
  } catch {}
  setTimeout(() => {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {}
  }, 4000).unref()
}

function finish(code: number, signal: number | null, note?: string, fallback = false): void {
  if (exited) return
  const e = (exited = { code, signal, ...(note ? { note } : {}), ...(fallback && !everStarted ? { fallback: true } : {}) })
  child = null
  say('exited', code, signal ?? '')
  // Once the emulator has drawn claude's last output, control clients also get the screen as it
  // ended (`text`, wrapped lines joined): a command like `claude auth login` says how it went just
  // before it exits, too late for anyone polling the screen.
  let sent = false
  const send = () => {
    if (sent) return
    sent = true
    const f = msg({ t: 'exit', ...e })
    const last = msg({ t: 'exit', ...e, text: screenUnwrapped() })
    for (const c of clients) deliver(c, c.role === 'control' ? last : f)
    // Give clients a moment to read the last output and the exit.
    setTimeout(shutdown, 400)
  }
  term.write('', send)
  setTimeout(send, 1000)
}

let shuttingDown = false
function shutdown(): void {
  if (shuttingDown) return
  shuttingDown = true
  for (const c of clients) c.sock.end()
  server.close()
  closeProxy?.()
  for (const p of [sockPath(spec.id), metaPath(spec.id)]) {
    try {
      unlinkSync(p)
    } catch {}
  }
  setTimeout(() => process.exit(0), 200)
}

// ── Meta file ────────────────────────────────────────────────────────────
function saveMeta(): void {
  if (shuttingDown) return
  const m: TermMeta = {
    v: 1,
    id: spec.id,
    pid: process.pid,
    childPid: child?.pid ?? null,
    cwd: spec.cwd,
    args: spec.args,
    origin: spec.origin,
    sessionId: codex ? threadId : spec.sessionId,
    createdAt,
    terminal: spec.terminal,
    title,
    tty: viewers('tty'),
    phone: viewers('phone'),
    ...(codex ? { agent: 'codex' as const } : {}),
  }
  try {
    writeJson(metaPath(spec.id), m)
  } catch (e) {
    say('meta write failed', e)
  }
}
let metaTimer: NodeJS.Timeout | null = null
function saveMetaSoon(): void {
  if (metaTimer) return
  metaTimer = setTimeout(() => {
    metaTimer = null
    saveMeta()
  }, 250)
}

// Nobody has looked for 12 hours and it has been quiet for 2: nobody is coming back. End it
// (the conversation stays in History).
setInterval(() => {
  if (viewers()) lastViewerAt = Date.now()
  else if (child && Date.now() - lastViewerAt > 12 * 3600_000 && Date.now() - lastOutputAt > 2 * 3600_000) end('detached and idle for 12 hours')
}, 60_000).unref()

const server = createServer(onClient)
server.on('error', (e) => {
  say('socket failed', e)
  process.exit(1)
})
try {
  unlinkSync(sockPath(spec.id))
} catch {}
// Codex's TUI needs its socket before it starts; without it, `hn run-codex` runs plain codex.
if (codex) {
  try {
    if (!spec.codexSocket) throw new Error('no app-server socket')
    await startCodexProxy()
  } catch (e) {
    say('could not start the codex socket', e)
    process.exit(1)
  }
}
server.listen(sockPath(spec.id), () => {
  chmodSync(sockPath(spec.id), 0o600)
  saveMeta()
  if (!spec.waitForTty) start()
  // `hn run` connects within a moment; if it never does, don't leave a session nobody started.
  else
    waitTimer = setTimeout(() => {
      if (!child) {
        say('no terminal attached; giving up')
        shutdown()
      }
    }, 6000)
})
