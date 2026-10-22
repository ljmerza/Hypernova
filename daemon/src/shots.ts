// "Show me" for Hypernova Lens (docs/PROTOCOL.md, "Show me"): a screenshot of the page a session
// built, taken with headless Chrome on the Mac. Finding that page is most of the work: Claude Code
// usually starts a dev server in the background, and then the transcript says only where its
// output goes, not the address. Only pages on this Mac's loopback are ever captured, never the
// daemon's own: the browser's main frame can't be sent anywhere else, by a transcript, a phone or
// the page itself. Frames inside the page and its images, scripts and other requests aren't held
// to that (docs/PROTOCOL.md, "Show me", Rendering).
import { execFile, spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { closeSync, existsSync, lstatSync, mkdirSync, openSync, readFileSync, readSync, readdirSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { connect } from 'node:net'
import type { Readable, Writable } from 'node:stream'
import { tmpdir } from 'node:os'
import { basename, dirname, join, sep } from 'node:path'
import { API_PORT, HOME, LOCAL_PORT, SUPPORT, log, sleep } from './util.ts'
import type { Ev, ToolEv } from './normalize.ts'
import { COMPANION_PORT, companionPort } from './speech.ts'
import { createPreview } from './services.ts'
import { workPath } from './files.ts'
import { ON_MAC, PLATFORM, chromeCandidates, findTool } from './platform.ts'

export const SHOTS_DIR = join(SUPPORT, 'shots')
/** A screenshot's link works this long… */
const SERVE_MS = 12 * 3600_000
/** …and its file is kept this long. */
const KEEP_MS = 24 * 3600_000
const CHROME_MS = 20_000
export const MAX_PNG = 40 * 1024 * 1024

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

/** Never these: the daemon itself (this one, and the owner's usual ports when this is a test one). */
const daemonPorts = () => new Set([API_PORT, LOCAL_PORT, 47321, 47322])

// ── Addresses ────────────────────────────────────────────────────────────
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

/**
 * A page the phone asked for by address: only http(s) on this Mac's loopback, and never the
 * daemon's own ports.
 */
export function localTarget(raw: string): URL {
  let u: URL
  try {
    u = new URL(raw)
  } catch {
    throw fail(400, 'bad_url', 'That isn’t a page address.')
  }
  const port = Number(u.port || (u.protocol === 'https:' ? 443 : 80))
  if (!/^https?:$/.test(u.protocol) || !LOOPBACK_HOSTS.has(u.hostname) || daemonPorts().has(port)) throw fail(403, 'not_local', 'Only pages this Mac serves on localhost can be shown.')
  u.username = ''
  u.password = ''
  return u
}

/**
 * Whether the page being captured may load [url] in its main frame. Checked on every one, not
 * only the first: a loopback page can redirect, or its script can send the browser anywhere,
 * including the daemon's own ports. The same rule as localTarget, plus [preview]: this capture's
 * own preview (rule 3, on the daemon's port), within its folder and nothing else of the daemon's.
 */
export function mayVisit(url: string, preview: string | null = null): boolean {
  try {
    localTarget(url)
    return true
  } catch {}
  if (!preview) return false
  try {
    // Parsed, so `..` (or `%2e%2e`) can't climb out of the folder.
    const u = new URL(url)
    const p = new URL(preview)
    return u.origin === p.origin && u.pathname.startsWith(p.pathname.replace(/[^/]*$/, ''))
  } catch {
    return false
  }
}

const offMac = (url: string) => {
  let host = url.slice(0, 80)
  try {
    host = new URL(url).host || host
  } catch {}
  return fail(403, 'not_local', `The page went on to ${host}; only pages this Mac serves on localhost can be shown.`)
}

/** What a dev server prints, coloured or not: "➜  Local:   http://localhost:5173/". */
const LOOPBACK_URL = /\bhttps?:\/\/(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?\]):(\d{1,5})(?!\d)(?:\/[^\s"'`<>()[\]{}|\\^]*)?/gi
const ANSI = /\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g

/**
 * The loopback addresses in some output, the last one first (a restarted server prints its new
 * address later). `0.0.0.0` and `[::]` (listening on every address) are read as this Mac, and
 * the daemon's own ports are left out.
 */
export function loopbackUrls(text: string): string[] {
  const out: string[] = []
  for (const m of text.replace(ANSI, '').matchAll(LOOPBACK_URL)) {
    const port = Number(m[1])
    if (!port || port > 65535 || daemonPorts().has(port)) continue
    const url = m[0].replace(/[.,;:!?]+$/, '').replace('://0.0.0.0', '://127.0.0.1').replace('://[::]', '://[::1]')
    if (!out.includes(url)) out.push(url)
  }
  return out.reverse()
}

// ── Is anything serving there now? ───────────────────────────────────────
type Answer = 'http' | 'quiet' | null
/**
 * Connect and ask for the headers of `/`: `http` when an HTTP server answers, `quiet` when it
 * takes the connection but says nothing yet (a dev server compiling its first page), null when
 * nothing listens or something else hangs up (a build daemon on a loopback port, say).
 */
function probe(host: string, port: number, tls: boolean, ms = 800): Promise<Answer> {
  return new Promise((resolve) => {
    const s = connect({ host, port })
    let done = false
    const end = (a: Answer) => {
      if (done) return
      done = true
      clearTimeout(timer)
      s.destroy()
      resolve(a)
    }
    const timer = setTimeout(() => end(s.readyState === 'open' ? 'quiet' : null), ms)
    // An https server would only hang up on plain text: that it listens is all we ask of it.
    s.on('connect', () => (tls ? end('quiet') : s.write(`HEAD / HTTP/1.1\r\nHost: localhost:${port}\r\nConnection: close\r\n\r\n`)))
    s.on('data', (d) => end(/^HTTP\/\d/.test(d.toString('latin1', 0, 8)) ? 'http' : null))
    s.on('error', () => end(null))
    s.on('end', () => end(null))
  })
}

/** `localhost` may be either address (Node 17+ servers often listen on ::1 alone). */
async function probeUrl(url: string): Promise<Answer> {
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return null
  }
  const tls = u.protocol === 'https:'
  const port = Number(u.port || (tls ? 443 : 80))
  const hosts = u.hostname === 'localhost' ? ['127.0.0.1', '::1'] : [u.hostname.replace(/^\[|\]$/g, '')]
  let best: Answer = null
  for (const h of hosts) {
    const a = await probe(h, port, tls)
    if (a === 'http') return a
    best ??= a
  }
  return best
}

// ── Where the session's page is ──────────────────────────────────────────
/** What starts a page, by its command line: the same list as Lens' own (lens/src/lens/controller.ts). */
export const SERVER_CMD = /\b(npm|pnpm|yarn|bun)\s+(run\s+)?(dev|start|serve|preview)\b|\bvite\b|\bnext\s+(dev|start)\b|\bastro\s+dev\b|\bwebpack(-dev-server|\s+serve)\b|http\.server|\bserve\b|\brails\s+s(erver)?\b|\bflask\s+run\b|\buvicorn\b|\bgunicorn\b|\bphp\s+-S\b|\bhugo\s+server\b|\bjekyll\s+serve\b/

/**
 * A background command's ID and output file, from its result: "Command running in background
 * with ID: b8fkrkbas. Output is being written to: /private/tmp/…/tasks/b8fkrkbas.output." (or
 * "…moved to the background (ID: b8fkrkbas). Output is being written to: …" after a timeout).
 */
export function backgroundOf(output: string): { id: string | null; file: string | null } {
  const id = /\bbackground(?: with ID:|\s*\(ID:)\s*([\w-]+)/i.exec(output)?.[1] ?? null
  const file = /written to:\s*(\/\S+?)\.?(?=\s|$)/i.exec(output)?.[1] ?? null
  return { id, file }
}

let tempRoots: string[] | null = null
/** The system temp folders, as real paths (`/tmp` is `/private/tmp` on a Mac). */
function temps(): string[] {
  if (!tempRoots) {
    const set = new Set<string>()
    for (const p of [tmpdir(), '/private/tmp', '/tmp']) {
      try {
        set.add(realpathSync(p))
      } catch {}
    }
    tempRoots = [...set]
  }
  return tempRoots
}

/**
 * The end of a background command's output file, only if it is one: inside the system temp
 * folder, in a `tasks` folder, named `<id>.output`, a regular file of the daemon's own user.
 * Anything else a transcript names reads as empty. Only addresses are taken from it.
 */
export function taskOutputTail(path: string, id: string | null, max = 64 * 1024): string {
  try {
    const real = realpathSync(path)
    const name = basename(real)
    if (!temps().some((t) => real.startsWith(t + sep))) return ''
    if (basename(dirname(real)) !== 'tasks' || !/^[\w-]+\.output$/.test(name) || (id && name !== `${id}.output`)) return ''
    const st = statSync(real)
    if (!st.isFile() || (process.getuid && st.uid !== process.getuid())) return ''
    const len = Math.min(st.size, max)
    const buf = Buffer.alloc(len)
    const fd = openSync(real, 'r')
    try {
      readSync(fd, buf, 0, len, st.size - len)
    } finally {
      closeSync(fd)
    }
    return buf.toString('utf8')
  } catch {
    return ''
  }
}

/** The first of these addresses that something serves now. */
async function firstLive(text: string | null | undefined): Promise<string | null> {
  for (const url of loopbackUrls(String(text ?? ''))) if (await probeUrl(url)) return url
  return null
}

const isTool = (e: Ev): e is ToolEv => e.kind === 'tool'
const commandOf = (e: ToolEv) => String((e.input as { command?: unknown })?.command ?? '')

/**
 * Rule 1: a server command the session ran, newest first, and the address it printed: in its
 * own output (run in the foreground), in the output file its background result names, or, once
 * Claude Code has removed that file, in a later event that read it (a Read of it, a Bash command
 * naming it, BashOutput or TaskOutput with its ID). Addresses Claude only mentions or fetches
 * don't count: a transcript that merely mentioned the daemon's address would send the browser
 * to the daemon.
 */
export async function fromServerCommand(events: Ev[]): Promise<string | null> {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]!
    if (!isTool(e) || e.name !== 'Bash' || !SERVER_CMD.test(commandOf(e))) continue
    const out = String(e.output ?? '')
    const now = await firstLive(out)
    if (now) return now
    const bg = backgroundOf(out)
    if (!bg.id && !bg.file) continue
    if (bg.file) {
      const tail = taskOutputTail(bg.file, bg.id)
      if (tail) {
        const url = await firstLive(tail)
        if (url) return url
        continue
      }
    }
    for (let j = events.length - 1; j > i; j--) {
      const r = events[j]!
      if (!isTool(r) || !r.output) continue
      const input = JSON.stringify(r.input ?? {})
      const reads =
        (bg.file && ((r.name === 'Read' && (r.path === bg.file || (r.input as any)?.file_path === bg.file)) || (r.name === 'Bash' && commandOf(r).includes(bg.file)))) ||
        (bg.id && /Output$/.test(r.name) && input.includes(`"${bg.id}"`))
      if (!reads) continue
      const url = await firstLive(r.output)
      if (url) return url
    }
  }
  return null
}

/** Run a program and take what it printed, whatever its exit code ('' when it can't run). */
function output(cmd: string, args: string[], timeout = 3000): Promise<string> {
  return new Promise((resolve) =>
    execFile(cmd, args, { timeout, maxBuffer: 8 * 1024 * 1024, env: { ...process.env, LC_ALL: 'C' } }, (e, out) => resolve(e && (e as any).killed ? '' : String(out ?? ''))),
  )
}

/** lsof: always there on a Mac; on Linux where the package put it ('' when it isn't installed: no listeners found). */
const LSOF = () => (ON_MAC ? '/usr/sbin/lsof' : (findTool('lsof') ?? ''))

/** `lsof -F` lines (`p<pid>`, `f<fd>`, `n<name>`) as pid → names. */
export function lsofNames(out: string): Map<number, string[]> {
  const byPid = new Map<number, string[]>()
  let pid = 0
  for (const line of out.split('\n')) {
    if (line[0] === 'p') pid = Number(line.slice(1))
    else if (line[0] === 'n' && pid) byPid.set(pid, [...(byPid.get(pid) ?? []), line.slice(1)])
  }
  return byPid
}

/**
 * Rule 2: a server listening now in the session's folder, whoever started it (in the background,
 * in a terminal, in an earlier session): loopback or all-address listeners whose process works in
 * the session's folder or below, newest process first. Answering HTTP comes before only taking
 * the connection.
 */
export async function fromListeningPorts(cwd: string): Promise<string | null> {
  let root: string
  try {
    root = realpathSync(cwd)
  } catch {
    return null
  }
  // Not the home folder or above: every process there would count.
  let home = HOME
  try {
    home = realpathSync(HOME)
  } catch {}
  if (root === '/' || root === home || home.startsWith(root + sep)) return null
  const lsof = LSOF()
  if (!lsof) return null
  const never = new Set([...daemonPorts(), COMPANION_PORT, companionPort()])
  const ports = new Map<number, number[]>()
  for (const [pid, names] of lsofNames(await output(lsof, ['-nP', '-iTCP', '-sTCP:LISTEN', '-Fpn']))) {
    if (pid === process.pid) continue
    for (const n of names) {
      const m = /^(127\.0\.0\.1|\[::1\]|\*|0\.0\.0\.0|\[::\]):(\d+)$/.exec(n)
      const port = m ? Number(m[2]) : 0
      if (port && !never.has(port) && !ports.get(pid)?.includes(port)) ports.set(pid, [...(ports.get(pid) ?? []), port])
    }
  }
  if (!ports.size) return null
  const inside: number[] = []
  for (const [pid, names] of lsofNames(await output(lsof, ['-a', '-p', [...ports.keys()].join(','), '-d', 'cwd', '-Fn']))) {
    let dir = ''
    try {
      dir = realpathSync(names[0] ?? '')
    } catch {
      continue
    }
    if (dir === root || dir.startsWith(root + sep)) inside.push(pid)
  }
  if (!inside.length) return null
  const started = new Map<number, number>()
  for (const line of (await output('/bin/ps', ['-o', 'pid=,lstart=', '-p', inside.join(',')])).split('\n')) {
    const m = /^\s*(\d+)\s+(.+?)\s*$/.exec(line)
    if (m) started.set(Number(m[1]), Date.parse(m[2]!) || 0)
  }
  inside.sort((a, b) => (started.get(b) ?? 0) - (started.get(a) ?? 0) || b - a)
  let quiet: string | null = null
  for (const pid of inside) {
    for (const port of (ports.get(pid) ?? []).sort((a, b) => a - b)) {
      const url = `http://localhost:${port}/`
      const a = await probeUrl(url)
      if (a === 'http') return url
      if (a === 'quiet') quiet ??= url
    }
  }
  return quiet
}

/** Rule 3: the newest .html file the session wrote or edited inside its folder that's still there. */
export function fromHtmlFile(events: Ev[], cwd: string): string | null {
  let root: string
  try {
    root = realpathSync(cwd)
  } catch {
    return null
  }
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]!
    if (!isTool(e) || !['Write', 'Edit', 'MultiEdit'].includes(e.name) || e.status === 'error') continue
    const p = String(e.path ?? (e.input as any)?.file_path ?? '')
    if (!/\.html?$/i.test(p)) continue
    try {
      const real = realpathSync(p)
      if (real.startsWith(root + sep) && statSync(real).isFile()) return real
    } catch {}
  }
  return null
}

// ── Capturing ────────────────────────────────────────────────────────────
/** Google Chrome, else Chromium (HYPERNOVA_CHROME names another, for tests). */
export function findChrome(): string | null {
  const env = process.env.HYPERNOVA_CHROME
  if (env) return existsSync(env) ? env : null
  // The Mac's apps, or a Linux system's packages (platform.ts).
  return chromeCandidates(PLATFORM, HOME).find((p) => existsSync(p)) ?? null
}

/** The width and height in a PNG's header, or null if it isn't one. */
export function pngSize(b: Buffer): { width: number; height: number } | null {
  if (b.length < 24 || b.toString('latin1', 1, 4) !== 'PNG' || b.toString('latin1', 12, 16) !== 'IHDR') return null
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) }
}

/**
 * Chrome's DevTools protocol over the pipe `--remote-debugging-pipe` opens on its fds 3 and 4,
 * so no port is opened: JSON messages, each ended by a NUL byte.
 */
class Devtools {
  private next = 0
  private waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>()
  private parts: Buffer[] = []
  private size = 0
  private closed = false
  private out: Writable
  onEvent: (m: any) => void = () => {}
  constructor(out: Writable, input: Readable) {
    this.out = out
    input.on('data', (d: Buffer) => {
      let start = 0
      for (let i = d.indexOf(0); i >= 0; i = d.indexOf(0, start)) {
        this.parts.push(d.subarray(start, i))
        const text = Buffer.concat(this.parts).toString('utf8')
        this.parts = []
        this.size = 0
        start = i + 1
        this.handle(text)
      }
      if (start < d.length) {
        this.parts.push(d.subarray(start))
        this.size += d.length - start
        // A picture is at most MAX_PNG, a third more as base64; anything bigger isn't one.
        if (this.size > 2 * MAX_PNG) this.close()
      }
    })
    input.on('close', () => this.close())
    input.on('error', () => this.close())
    out.on('error', () => this.close())
  }
  private handle(text: string): void {
    let m: any
    try {
      m = JSON.parse(text)
    } catch {
      return
    }
    const w = typeof m?.id === 'number' ? this.waiting.get(m.id) : undefined
    if (w) {
      this.waiting.delete(m.id)
      if (m.error) w.reject(new Error(String(m.error.message ?? 'DevTools error')))
      else w.resolve(m.result ?? {})
    } else if (m?.method) this.onEvent(m)
  }
  send(method: string, params: object = {}, sessionId?: string): Promise<any> {
    if (this.closed) return Promise.reject(new Error('Chrome has closed'))
    return new Promise((resolve, reject) => {
      const id = ++this.next
      this.waiting.set(id, { resolve, reject })
      this.out.write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0')
    })
  }
  close(): void {
    if (this.closed) return
    this.closed = true
    for (const w of this.waiting.values()) w.reject(new Error('Chrome has closed'))
    this.waiting.clear()
  }
  get isClosed(): boolean {
    return this.closed
  }
}

/** How long a page may take to load before it's captured as it is (an image that never comes, say). */
const LOAD_MS = 8000
/** After loading, a page draws itself (an app fetches its data): until its requests pause, within these. */
const SETTLE_MIN_MS = 400
const SETTLE_QUIET_MS = 500
const SETTLE_MAX_MS = 3000

/**
 * One Chrome at a time: they share a profile folder, and a second Chrome on the same profile
 * hands its work to the first and quits.
 */
let chromeQueue: Promise<unknown> = Promise.resolve()

/**
 * Render [url] at width × height into `<support>/shots/<token>.png` with headless Chrome, in a
 * profile of its own (never the owner's cookies) and a context of its own (never another
 * capture's), and read the page's title. Driven over the DevTools protocol rather than
 * `--screenshot`, which never captures a page that keeps a request open (a dev server's
 * live-reload poll) and captures an app before it has drawn itself. [preview]: [url] is this
 * capture's own preview on the daemon's port (see mayVisit).
 */
export function render(url: string, width: number, height: number, preview = false): Promise<{ token: string; width: number; height: number; title: string }> {
  const chrome = findChrome()
  if (!chrome) return Promise.reject(fail(501, 'no_browser', 'Install Google Chrome on the Mac to take screenshots.'))
  const run = chromeQueue.then(() => renderNow(chrome, url, width, height, preview ? url : null))
  chromeQueue = run.catch(() => {})
  return run
}

/**
 * Chrome's "Preload pages" turned off in the capture profile, which the throwaway contexts
 * inherit. A page's speculation rules fetch other pages (and prerender them) outside anything
 * the Fetch check sees, and a page fetched that way is then shown without being asked about:
 * the daemon's own included. Written before Chrome starts, keeping whatever else Chrome saved there.
 */
export function noPreloading(profile: string): void {
  const file = join(profile, 'Default', 'Preferences')
  let prefs: any = {}
  try {
    prefs = JSON.parse(readFileSync(file, 'utf8'))
  } catch {}
  const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
  if (!isObject(prefs)) prefs = {}
  const net = isObject(prefs.net) ? prefs.net : {}
  // 2: never (chrome://settings/performance, "Preload pages").
  if (net.network_prediction_options === 2) return
  try {
    mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
    writeFileSync(file, JSON.stringify({ ...prefs, net: { ...net, network_prediction_options: 2 } }), { mode: 0o600 })
  } catch {}
}

async function renderNow(chrome: string, url: string, width: number, height: number, preview: string | null) {
  mkdirSync(SHOTS_DIR, { recursive: true, mode: 0o700 })
  const profile = join(SHOTS_DIR, 'chrome')
  noPreloading(profile)
  const args = [
    '--headless=new',
    '--remote-debugging-pipe',
    '--disable-gpu',
    '--hide-scrollbars',
    `--user-data-dir=${profile}`,
    // Its own profile and nothing else: no keychain prompt, no first-run pages, no calls home.
    '--use-mock-keychain',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--disable-sync',
    '--disable-background-networking',
    '--disable-component-update',
    '--mute-audio',
    '--disk-cache-size=33554432',
    // Exactly the pixels asked for, on a Retina Mac too.
    '--force-device-scale-factor=1',
    `--window-size=${width},${height}`,
    // Chrome won't start as root with its sandbox (a VPS run as root, a container): only then is it
    // turned off, for pages from this machine's own loopback in a profile that holds nothing.
    ...(!ON_MAC && process.getuid?.() === 0 ? ['--no-sandbox'] : []),
    'about:blank',
  ]
  // Its own process group, so a page that hangs takes Chrome's helpers down with it.
  const child = spawn(chrome, args, { detached: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] })
  let running = !!child.pid
  const exited = new Promise<void>((r) => {
    child.once('exit', () => r())
    child.once('error', () => r())
  }).then(() => {
    running = false
  })
  // Only while it runs: once it has exited, its group's number may belong to someone else.
  const killAll = () => {
    if (!running) return
    try {
      process.kill(-child.pid!, 'SIGKILL')
    } catch {}
  }
  const timer = setTimeout(killAll, CHROME_MS)
  const dt = new Devtools(child.stdio[3] as Writable, child.stdio[4] as Readable)
  const wait = async (until: () => boolean, ms: number) => {
    for (const end = Date.now() + ms; !until() && !dt.isClosed && Date.now() < end; ) await sleep(50)
  }
  let context: string | null = null
  /** The first main-frame address mayVisit refused. */
  let blocked: string | null = null
  try {
    // A throwaway context for each capture, kept in memory: cookies, storage and service workers
    // don't carry over to the next one (another project on the same port, say, or the preview
    // cookie reaching every later page on 127.0.0.1).
    context = String((await dt.send('Target.createBrowserContext', { disposeOnDetach: true }))?.browserContextId ?? '') || null
    if (!context) throw new Error('no browser context')
    // A page can start a download without anyone clicking; it would land in the owner's Downloads.
    await dt.send('Browser.setDownloadBehavior', { behavior: 'deny', browserContextId: context })
    const { targetId } = await dt.send('Target.createTarget', { url: 'about:blank', browserContextId: context })
    const { sessionId } = await dt.send('Target.attachToTarget', { targetId, flatten: true })
    let loaded = false
    let lastRequest = Date.now()
    /** Where the main frame is now. */
    let mainUrl = ''
    dt.onEvent = (m) => {
      if (m.sessionId !== sessionId) return
      if (m.method === 'Page.loadEventFired') loaded = true
      else if (/^Network\.(requestWillBeSent|loadingFinished|loadingFailed)$/.test(m.method)) lastRequest = Date.now()
      // An alert() or confirm() would hold the page (and the picture) until the time runs out.
      else if (m.method === 'Page.javascriptDialogOpening') dt.send('Page.handleJavaScriptDialog', { accept: true }, sessionId).catch(() => {})
      else if (m.method === 'Page.frameNavigated' && !m.params?.frame?.parentId) mainUrl = String(m.params?.frame?.url ?? '')
      else if (m.method === 'Fetch.requestPaused') {
        // Every document request stops here, redirects included. A page's main frame has its
        // target's ID; frames inside it, like its images and scripts, are its own business. One
        // that names no frame at all is checked like the main frame: when unsure, refuse.
        const { requestId, request, frameId } = m.params ?? {}
        const url = String(request?.url ?? '')
        if ((frameId === targetId || !frameId) && !mayVisit(url, preview)) {
          blocked ??= url
          dt.send('Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }, sessionId).catch(() => {})
        } else dt.send('Fetch.continueRequest', { requestId }, sessionId).catch(() => {})
      }
    }
    await dt.send('Page.enable', {}, sessionId)
    await dt.send('Network.enable', {}, sessionId)
    await dt.send('Fetch.enable', { patterns: [{ resourceType: 'Document', requestStage: 'Request' }] }, sessionId)
    // Dev servers on https often use a certificate of their own; this profile holds nothing to
    // protect, and only loopback pages get this far.
    await dt.send('Security.setIgnoreCertificateErrors', { ignore: true }, sessionId).catch(() => {})
    await dt.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false }, sessionId)
    const nav = await dt.send('Page.navigate', { url }, sessionId)
    if (blocked) throw offMac(blocked)
    if (nav?.errorText) throw fail(502, 'shot_failed', `The page didn’t load (${String(nav.errorText).replace(/^net::/, '')}).`)
    await wait(() => loaded || !!blocked, LOAD_MS)
    const settled = Date.now()
    await wait(() => !!blocked || (Date.now() - settled >= SETTLE_MIN_MS && Date.now() - lastRequest >= SETTLE_QUIET_MS), SETTLE_MAX_MS)
    // Also where the page ended up, should anything have reached it some other way.
    if (blocked || (/^https?:/i.test(mainUrl) && !mayVisit(mainUrl, preview))) throw offMac(blocked ?? mainUrl)
    const title = await dt.send('Runtime.evaluate', { expression: 'document.title', returnByValue: true }, sessionId).catch(() => null)
    const shot = await dt.send('Page.captureScreenshot', { format: 'png' }, sessionId)
    // A script that tried to leave while the picture was being taken: refused all the same, so
    // the answer says where it went rather than showing Chrome's "blocked" page.
    if (blocked) throw offMac(blocked)
    const bytes = Buffer.from(String(shot?.data ?? ''), 'base64')
    const size = pngSize(bytes)
    if (!size || bytes.length > MAX_PNG) throw new Error('not a picture')
    const token = randomBytes(12).toString('hex')
    writeFileSync(join(SHOTS_DIR, `${token}.png`), bytes, { mode: 0o600 })
    return { token, ...size, title: String(title?.result?.value ?? '').replace(/\s+/g, ' ').trim().slice(0, 200) }
  } catch (e: any) {
    if (e?.status) throw e
    // Chrome closing on a page that was leaving (the time ran out, say) is still that page leaving.
    if (blocked) throw offMac(blocked)
    throw fail(502, 'shot_failed', 'Chrome couldn’t draw that page.')
  } finally {
    clearTimeout(timer)
    // Closing Chrome takes the context with it as well; disposing of it first doesn't rely on that.
    if (context) await Promise.race([dt.send('Target.disposeBrowserContext', { browserContextId: context }).catch(() => {}), sleep(1000)])
    dt.send('Browser.close').catch(() => {})
    await Promise.race([exited, sleep(3000)])
    killAll()
  }
}

/** A screenshot's file while its link works, else null (an unknown, malformed or expired token). */
export function shotPath(token: string): string | null {
  if (!/^[0-9a-f]{24}$/.test(token)) return null
  const p = join(SHOTS_DIR, `${token}.png`)
  try {
    const st = lstatSync(p)
    return st.isFile() && Date.now() - st.mtimeMs <= SERVE_MS ? p : null
  } catch {
    return null
  }
}

/**
 * Keep a picture taken some other way (Telescope: an emulator, the terminal, a file) as a
 * screenshot, served and removed like the rest: its token.
 */
export function saveShot(png: Buffer): string {
  mkdirSync(SHOTS_DIR, { recursive: true, mode: 0o700 })
  const token = randomBytes(12).toString('hex')
  writeFileSync(join(SHOTS_DIR, `${token}.png`), png, { mode: 0o600 })
  return token
}

/** Remove screenshots older than a day (Chrome's profile folder stays). */
export function reapShots(): void {
  let files: string[] = []
  try {
    files = readdirSync(SHOTS_DIR)
  } catch {
    return
  }
  let n = 0
  for (const f of files) {
    if (!/^[0-9a-f]{24}\.png$/.test(f)) continue
    try {
      if (Date.now() - statSync(join(SHOTS_DIR, f)).mtimeMs > KEEP_MS) {
        rmSync(join(SHOTS_DIR, f), { force: true })
        n++
      }
    } catch {}
  }
  if (n) log(`removed ${n} old screenshot${n === 1 ? '' : 's'}`)
  // Telescope's scratch files (telescope/common.ts), left by a daemon that stopped mid-capture.
  try {
    for (const f of readdirSync(join(SHOTS_DIR, 'tmp'))) {
      const p = join(SHOTS_DIR, 'tmp', f)
      if (Date.now() - statSync(p).mtimeMs > 10 * 60_000) rmSync(p, { force: true })
    }
  } catch {}
}

// ── The route ────────────────────────────────────────────────────────────
export type Shot = { url: string; width: number; height: number; title: string; source: 'url'; pageUrl: string | null; path?: string; at: number }

/** Sessions being captured: one at a time each. */
const busy = new Set<string>()

/** A width or height: 320–2560, else the default. */
export const dimension = (v: unknown, d: number) => (v == null || v === '' || !Number.isFinite(Number(v)) ? d : Math.min(2560, Math.max(320, Math.round(Number(v)))))

/**
 * The page to capture: the address the phone named (a loopback page that answers now), else the
 * one the session started (a server it ran, one listening in its folder, an HTML file it wrote,
 * through a preview on the daemon's own port so its relative links work). `path` names that file.
 */
export async function findPage(s: { cwd: string }, events: Ev[], url?: unknown): Promise<{ target: string; pageUrl: string | null; path: string | null }> {
  if (typeof url === 'string' && url.trim()) {
    const u = localTarget(url.trim())
    const target = u.toString()
    if (!(await probeUrl(target))) throw fail(502, 'shot_failed', `Nothing answers at ${u.host} on the Mac.`)
    return { target, pageUrl: target, path: null }
  }
  const page = (await fromServerCommand(events)) ?? (await fromListeningPorts(s.cwd))
  if (page) return { target: page, pageUrl: page, path: null }
  const path = fromHtmlFile(events, s.cwd)
  if (path) {
    try {
      return { target: createPreview({ path }, `http://127.0.0.1:${API_PORT}`, workPath).url, pageUrl: null, path }
    } catch {}
  }
  throw fail(404, 'nothing_to_show', 'This session hasn’t started a page or app to show.')
}

/**
 * POST /v1/sessions/{id}/screenshot: the page the phone named, else the one the session started
 * (a server it ran, one listening in its folder, an HTML file it wrote), captured. The picture is
 * served at `url` for 12 hours without the bearer header (an <img> or a canvas can't send one).
 * Lens before Telescope asks this; Telescope's web source does the same (telescope/index.ts).
 */
export async function screenshot(s: { id: string; cwd: string; title: string }, events: Ev[], body: any): Promise<Shot> {
  if (busy.has(s.id)) throw fail(409, 'busy', 'A screenshot of this session is already being taken.')
  busy.add(s.id)
  try {
    const width = dimension(body?.width, 1280)
    const height = dimension(body?.height, 800)
    const { target, pageUrl, path } = await findPage(s, events, body?.url)
    const shot = await render(target, width, height, !!path)
    reapShots()
    return { url: `/v1/shots/${shot.token}.png`, width: shot.width, height: shot.height, title: shot.title || s.title, source: 'url', pageUrl, ...(path ? { path } : {}), at: Date.now() }
  } finally {
    busy.delete(s.id)
  }
}
