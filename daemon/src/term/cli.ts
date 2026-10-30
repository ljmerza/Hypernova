// `hn`: Hypernova's shared terminals from a shell (docs/PROTOCOL.md, "Shared terminals").
//
//   hn run [claude args]   what `claude` does in your shell once Hypernova is connected
//   hn run-codex [args]    what `codex` does there once Codex is on for Hypernova
//   hn attach [id]         open a shared session in this terminal
//   hn ls                  list shared sessions
//
// The `claude` function Hypernova adds to ~/.zshrc calls `hn run`. Exit code 97 means
// "not for me": the function then runs the real claude, so `claude` never breaks.
// In an attached window, Ctrl+\ detaches and leaves the session running.
import { connect, type Socket } from 'node:net'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'
import { CLAUDE_DIR, SUPPORT, findClaude, readJson } from '../util.ts'
import { DATA, Frames, MSG, TERMS, codexTuiArgs, frame, metaPath, msg, newTermId, sessionEnv, sockPath, spawnHolder, type TermMeta, type TerminalApp } from './shared.ts'
import { isHiddenCwd } from './hidden.ts'
import { ON_MAC } from '../platform.ts'
import { CODEX_STATE, codexLaunch, type CodexStateFile } from '../codex/launch.ts'

const PASS = 97
// Subcommands and flags that aren't an interactive session (claude --help, 2.1.296), plus
// whatever the daemon found in the installed claude's own help.
const SUBCOMMANDS = new Set(['agents', 'attach', 'auth', 'auto-mode', 'doctor', 'gateway', 'import', 'install', 'logs', 'mcp', 'plugin', 'plugins', 'purge', 'respawn', 'rm', 'setup-token', 'stop', 'kill', 'ultrareview', 'update', 'upgrade', 'config', 'migrate-installer'])
for (const c of readJson<string[]>(join(SUPPORT, 'claude-commands.json'), [])) SUBCOMMANDS.add(c)
const NOT_INTERACTIVE = new Set(['-p', '--print', '-v', '--version', '-h', '--help', '--bg', '--background'])

function passthrough(args: string[]): boolean {
  if (args[0] && SUBCOMMANDS.has(args[0])) return true
  return args.some((a) => NOT_INTERACTIVE.has(a) || a.startsWith('--output-format') || a.startsWith('--input-format'))
}

function detectTerminal(env: NodeJS.ProcessEnv): TerminalApp {
  if (env.ALACRITTY_SOCKET || env.ALACRITTY_WINDOW_ID || env.ALACRITTY_LOG) return { app: 'alacritty', socket: env.ALACRITTY_SOCKET ?? null }
  if (env.KITTY_WINDOW_ID) return { app: 'kitty' }
  const known: Record<string, string> = { Apple_Terminal: 'terminal', 'iTerm.app': 'iterm', ghostty: 'ghostty', WezTerm: 'wezterm', vscode: 'vscode', WarpTerminal: 'warp' }
  return { app: known[env.TERM_PROGRAM ?? ''] ?? null }
}

/** The session id claude will run under, when the arguments say so. */
function sessionIdFrom(args: string[]): string | null {
  for (let i = 0; i < args.length; i++) {
    const a = args[i]!
    if (a === '--session-id' && args[i + 1]) return args[i + 1]!
    if (a.startsWith('--session-id=')) return a.slice(13)
    if ((a === '-r' || a === '--resume') && /^[0-9a-f-]{36}$/.test(args[i + 1] ?? '') && !args.includes('--fork-session')) return args[i + 1]!
  }
  return null
}

async function run(args: string[]): Promise<void> {
  if (!process.stdin.isTTY || !process.stdout.isTTY || passthrough(args)) process.exit(PASS)
  // The shell function passes the claude it would have run, so both paths run the same binary.
  const file = process.env.HYPERNOVA_CLAUDE || findClaude()
  if (!file) process.exit(PASS)
  const id = newTermId()
  let pid: number | null = null
  try {
    pid = spawnHolder({
      id,
      cwd: process.cwd(),
      file,
      args,
      env: sessionEnv(process.env, id),
      cols: process.stdout.columns || 100,
      rows: process.stdout.rows || 30,
      origin: 'terminal',
      sessionId: sessionIdFrom(args),
      terminal: detectTerminal(process.env),
      waitForTty: true,
      autoTrust: false,
    })
  } catch {
    process.exit(PASS)
  }
  // Nothing has touched this terminal yet: if the holder doesn't come up, plain claude runs instead.
  const sock = await connectWithin(id, 4000, pid)
  if (!sock) process.exit(PASS)
  await attach(sock, id, false)
}

/**
 * What `codex` does in a shell once Codex is on for Hypernova: its TUI in a shared terminal, on
 * Codex's own app-server through the holder. Anything that isn't a TUI start, or a Hypernova
 * that can't reach Codex right now, runs plain codex (exit code 97).
 */
async function runCodex(args: string[]): Promise<void> {
  const launch = codexLaunch(args)
  if (!process.stdin.isTTY || !process.stdout.isTTY || !launch) process.exit(PASS)
  const st = readJson<CodexStateFile | null>(CODEX_STATE, null)
  // The shell function passes the codex it would have run.
  const file = process.env.HYPERNOVA_CODEX || st?.binary
  if (!st?.ok || !st.socket || !file || !existsSync(st.socket)) process.exit(PASS)
  const id = newTermId()
  let pid: number | null = null
  try {
    pid = spawnHolder({
      id,
      cwd: process.cwd(),
      file,
      args: codexTuiArgs(id, args, process.cwd()),
      env: sessionEnv(process.env, id),
      cols: process.stdout.columns || 100,
      rows: process.stdout.rows || 30,
      origin: 'terminal',
      sessionId: launch.resume,
      terminal: detectTerminal(process.env),
      waitForTty: true,
      autoTrust: false,
      agent: 'codex',
      codexSocket: st.socket,
    })
  } catch {
    process.exit(PASS)
  }
  const sock = await connectWithin(id, 4000, pid)
  if (!sock) process.exit(PASS)
  await attach(sock, id, false)
}

function connectOnce(id: string): Promise<Socket | null> {
  return new Promise((resolve) => {
    const s = connect(sockPath(id))
    s.once('connect', () => resolve(s))
    s.once('error', () => resolve(null))
  })
}
const alive = (pid: number) => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}
async function connectWithin(id: string, ms: number, pid: number | null): Promise<Socket | null> {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    if (pid && Date.now() - t0 > 150 && !alive(pid)) return null
    if (existsSync(sockPath(id))) {
      const s = await connectOnce(id)
      if (s) return s
    }
    await new Promise((r) => setTimeout(r, 40))
  }
  return null
}

// Modes claude may have switched on (keyboard protocol, paste, focus and mouse reports, hidden
// cursor). Claude switches them off when it exits; anything else leaving this window must too.
const RESET = '\x1b[<u\x1b[>4;0m\x1b[?2004l\x1b[?1004l\x1b[?1000l\x1b[?1002l\x1b[?1003l\x1b[?1006l\x1b[?25h\x1b[0m'
// Ctrl+\ as a plain byte, or as the kitty keyboard protocol sends it.
const DETACH = [Buffer.from([0x1c]), Buffer.from('\x1b[92;5u')]

function attach(first: Socket, firstId: string, reattach: boolean): Promise<void> {
  const out = process.stdout
  const inp = process.stdin
  let raw = false
  let done = false
  // The conversation can move to another holder (the agent switched): the window follows it.
  let sock = first
  let id = firstId
  let moved = false
  const restore = () => {
    if (raw) {
      try {
        inp.setRawMode(false)
      } catch {}
      raw = false
    }
  }
  const quit = (code: number, note?: string, reset = false) => {
    if (done) return
    done = true
    if (reset) out.write(RESET)
    restore()
    if (note) out.write(note)
    process.exit(code)
  }
  const follow = async (to: string | null, text: string | null) => {
    if (!to) return quit(0, text ? `\r\n\x1b[2m[hypernova] ${text}\x1b[0m\r\n` : undefined, true)
    const next = await connectWithin(to, 4000, null)
    if (!next) return quit(1, `\r\n\x1b[2m[hypernova] This conversation moved to another session. Open it from ${ON_MAC ? 'the menu bar or your phone' : 'your phone'}, or \`hn attach ${to}\`.\x1b[0m\r\n`, true)
    const old = sock
    sock = next
    id = to
    old.removeAllListeners()
    old.destroy()
    // The other agent draws on a clean screen, with its own modes.
    out.write(RESET)
    bind(next)
  }
  const bind = (s: Socket) => {
    const frames = new Frames((type, p) => {
      if (s !== sock || moved) return
      if (type === DATA) out.write(p)
      else if (type === MSG) {
        let m: any
        try {
          m = JSON.parse(p.toString('utf8'))
        } catch {
          return
        }
        if (m.t === 'snapshot') {
          // Reopening a session, or one that moved here: start from a clean screen, then draw it as it is now.
          if (reattach || s !== first) out.write('\x1b[H\x1b[2J\x1b[3J')
          out.write(m.data)
        } else if (m.t === 'moved') {
          moved = true
          void follow(typeof m.to === 'string' ? m.to : null, typeof m.text === 'string' ? m.text : null).finally(() => {
            moved = false
          })
        } else if (m.t === 'exit') {
          // Claude never started (nothing was drawn): the shell function runs plain claude.
          if (m.fallback && !reattach && s === first) quit(PASS)
          else quit(typeof m.code === 'number' ? m.code : 0, m.note ? `\r\n${m.note}\r\n` : undefined, !!m.note)
        }
      }
    })
    s.on('data', (b: Buffer) => {
      try {
        frames.push(b)
      } catch {
        s.destroy()
      }
    })
    s.on('close', () => !moved && s === sock && quit(1, `\r\n\x1b[2m[hypernova] Lost the connection to this session. If it's still running, \`hn attach ${id}\` opens it again.\x1b[0m\r\n`, true))
    s.on('error', () => {})
    s.write(msg({ t: 'hello', v: 1, role: 'tty', cols: out.columns || 100, rows: out.rows || 30, snapshot: true }))
  }
  bind(first)
  inp.setRawMode(true)
  raw = true
  inp.resume()
  let detached = false
  inp.on('data', (b: Buffer) => {
    if (detached) return
    const at = DETACH.map((d) => b.indexOf(d)).filter((i) => i >= 0)
    if (!at.length) return void sock.write(frame(DATA, b))
    detached = true
    const i = Math.min(...at)
    if (i > 0) sock.write(frame(DATA, b.subarray(0, i)))
    // A Linux server has no menu bar to open it from: only the phone and `hn attach` there.
    const from = ON_MAC ? 'the menu bar or your phone' : 'your phone'
    const bye = () => quit(0, `\r\n\x1b[2m[hypernova] Detached. The session keeps running: open it from ${from}, or \`hn attach ${id}\`.\x1b[0m\r\n`, true)
    // The holder must hear "detach" before the socket closes, or it treats this as a closed window.
    sock.write(msg({ t: 'detach' }), () => {
      sock.end()
      bye()
    })
    setTimeout(bye, 500)
  })
  out.on('resize', () => sock.write(msg({ t: 'resize', cols: out.columns, rows: out.rows })))
  // The window closed: the holder ends a session started here a little later, unless a phone has it open.
  process.on('SIGHUP', () => quit(0))
  process.on('SIGTERM', () => quit(0, undefined, true))
  process.on('exit', restore)
  return new Promise<void>(() => {})
}

// ── ls / attach ──────────────────────────────────────────────────────────
/** Where a session's own terminal window is: on the Mac, or on a Linux server in a terminal (over SSH). */
const HERE = ON_MAC ? 'on the Mac' : 'in a terminal'
type Row = TermMeta & { sessionId: string | null }
function sessions(): Row[] {
  const out: Row[] = []
  let files: string[] = []
  try {
    files = readdirSync(TERMS).filter((f) => f.endsWith('.json'))
  } catch {}
  for (const f of files) {
    const m = readJson<TermMeta | null>(join(TERMS, f), null)
    // The daemon's own (the usage probe): nothing to attach to (hidden.ts).
    if (!m?.pid || isHiddenCwd(m.cwd)) continue
    try {
      process.kill(m.pid, 0)
    } catch {
      continue
    }
    // Claude Code's own registry follows /clear and /resume inside the terminal (a Codex holder says itself).
    const reg = m.childPid && m.agent !== 'codex' ? readJson<{ sessionId?: string } | null>(join(CLAUDE_DIR, 'sessions', `${m.childPid}.json`), null) : null
    out.push({ ...m, sessionId: reg?.sessionId ?? m.sessionId })
  }
  return out.sort((a, b) => b.createdAt - a.createdAt)
}

function ago(t: number): string {
  const s = Math.max(0, (Date.now() - t) / 1000)
  return s < 90 ? `${Math.round(s)}s` : s < 5400 ? `${Math.round(s / 60)}m` : s < 129600 ? `${Math.round(s / 3600)}h` : `${Math.round(s / 86400)}d`
}

function list(): void {
  const rows = sessions()
  if (!rows.length) {
    process.stdout.write('No shared sessions are running.\n')
    return
  }
  for (const r of rows) {
    const where = [r.tty ? `open here ${HERE}` : `not open ${HERE}`, r.phone ? 'on your phone' : ''].filter(Boolean).join(', ')
    const agent = r.agent === 'codex' ? 'codex  ' : ''
    process.stdout.write(`${r.id}  ${agent}${basename(r.cwd).padEnd(20)} ${ago(r.createdAt).padStart(4)}  ${where}${r.sessionId ? `  (session ${r.sessionId.slice(0, 8)})` : ''}\n`)
  }
}

async function attachCmd(query: string | undefined): Promise<void> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    process.stderr.write('hn attach needs a terminal.\n')
    process.exit(2)
  }
  const rows = sessions()
  const q = query?.toLowerCase()
  const hits = q
    ? rows.filter((r) => r.id.startsWith(q) || r.sessionId?.startsWith(q) || basename(r.cwd).toLowerCase() === q)
    : rows.filter((r) => !r.tty)
  if (hits.length !== 1) {
    if (!rows.length) process.stderr.write('No shared sessions are running.\n')
    else {
      process.stderr.write(hits.length ? 'More than one session matches; pick one:\n' : q ? `No shared session matches “${query}”. Running:\n` : 'Pick a session:\n')
      for (const r of hits.length ? hits : rows) process.stderr.write(`  hn attach ${r.id}   ${basename(r.cwd)}${r.tty ? ` (open ${HERE})` : ''}\n`)
    }
    process.exit(1)
  }
  const id = hits[0]!.id
  if (process.env.HYPERNOVA_TERM === id) {
    process.stderr.write("You're already in that session.\n")
    process.exit(1)
  }
  const sock = existsSync(metaPath(id)) ? await connectOnce(id) : null
  if (!sock) {
    process.stderr.write('That session just ended.\n')
    process.exit(1)
  }
  await attach(sock, id, true)
}

// Dispatch last: everything above (constants included) is initialized by now.
const [cmd, ...rest] = process.argv.slice(2)
switch (cmd) {
  case 'run':
    await run(rest)
    break
  case 'run-codex':
    await runCodex(rest)
    break
  case 'attach':
  case 'a':
    await attachCmd(rest[0])
    break
  case 'ls':
  case 'list':
    list()
    break
  default:
    process.stdout.write(`hn: Hypernova's shared terminals

  hn ls             list shared sessions
  hn attach [id]    open one in this terminal (id, session id, or project name)

In your shell, \`claude\` starts a shared session by itself; \`command claude\` skips sharing.
With Codex on for Hypernova, \`codex\` does the same (\`command codex\` skips it).
Closing its window ends it about 30 seconds later, unless your phone has it open.
Ctrl+\\ detaches instead: it keeps running for the phone and \`hn attach\`.
`)
}
