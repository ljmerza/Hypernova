// What workflows need from the computer (docs/PROTOCOL.md, "Automations", "Workflows"): a shell
// step's command, a webhook output's POST, a file output's line, a file watch, a GitHub watch's
// look through gh, and the `hypernova` command a webhook trigger is fired with. Each is a plain
// function the daemon's AutoIO (daemon.ts) hands the engine, so tests can call them on their own.
import { execFile, spawn } from 'node:child_process'
import { closeSync, constants, existsSync, lstatSync, openSync, readdirSync, statSync, watch as fsWatch, writeSync } from 'node:fs'
import { lookup as dnsLookup, type LookupAddress } from 'node:dns'
import { request } from 'node:https'
import { isIP } from 'node:net'
import { basename, dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { HOME, SUPPORT, writeText } from '../util.ts'
import { maskSecrets } from '../normalize.ts'
import { isSecretFile } from '../secretfiles.ts'
import { hostAllowed, hostKey, privateAddress, privateHost } from './outputs.ts'
import { SHELL_KILL_MS, SHELL_OUTPUT_MAX, WEBHOOK_TIMEOUT_MS, bad, type FileEvent, type GhItem, type GithubEvent, type ShellRun, type ShellSpec, type WebhookRequest } from './contract.ts'

// ── Shell steps ───────────────────────────────────────────────────────────
/** Variables a parent Claude Code process sets, and the daemon's own: a step's command doesn't inherit them. */
const SCRUB = /^(?:CLAUDECODE|CLAUDE_CODE_.*|CLAUDE_PID|CLAUDE_EFFORT|ANTHROPIC_BASE_URL|ANTHROPIC_AUTH_TOKEN|ANTHROPIC_MODEL|HYPERNOVA_.*)$/
/** What a step's command keeps of its output while it runs (it's cut to 8 KB, masked, at the end). */
const KEEP = 64 * 1024

/**
 * A shell step's command: `/bin/sh -c` in [cwd], no TTY, stdin empty, in a process group of its
 * own, the environment sessions get plus [env]; past [timeoutMs] its group gets SIGTERM, then
 * SIGKILL 5 s later. Its output (stdout and stderr together) comes back masked, the last 8 KB.
 */
export function runShell(o: ShellSpec): ShellRun {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !SCRUB.test(k)) env[k] = v
  Object.assign(env, o.env, { TERM: 'dumb', NO_COLOR: '1' })
  const child = spawn('/bin/sh', ['-c', o.command], { cwd: o.cwd, env, stdio: ['ignore', 'pipe', 'pipe'], detached: true })
  let out = ''
  const take = (d: Buffer) => {
    out += d.toString('utf8')
    if (out.length > KEEP) out = out.slice(-KEEP)
  }
  child.stdout!.on('data', take)
  child.stderr!.on('data', take)
  let timedOut = false
  let ended = false
  let killTimer: NodeJS.Timeout | null = null
  const group = (sig: NodeJS.Signals) => {
    try {
      if (child.pid) process.kill(-child.pid, sig)
    } catch {}
  }
  const stop = () => {
    // Once it ended its group number is free: a signal then could reach someone else's processes.
    if (ended) return
    group('SIGTERM')
    killTimer ??= setTimeout(() => group('SIGKILL'), SHELL_KILL_MS)
    killTimer.unref()
  }
  const timer = setTimeout(() => {
    timedOut = true
    stop()
  }, o.timeoutMs)
  timer.unref()
  const done = new Promise<{ exit: number | null; output: string; timedOut: boolean }>((resolve) => {
    const end = (exit: number | null, extra = '') => {
      if (ended) return
      ended = true
      clearTimeout(timer)
      if (killTimer) clearTimeout(killTimer)
      // Its group may have children still holding the pipes: they go with it.
      group('SIGKILL')
      resolve({ exit, output: maskSecrets(out + extra).slice(-SHELL_OUTPUT_MAX), timedOut })
    }
    child.on('error', (e) => end(null, `\n${e.message}`))
    child.on('close', (code) => end(code))
  })
  return { done, kill: stop }
}

// ── Webhook outputs ───────────────────────────────────────────────────────
const privateError = () => Object.assign(new Error('That address is this computer or a private network.'), { code: 'private_address' })
type Resolver = (host: string, o: { all: true; family?: number; hints?: number }, cb: (e: NodeJS.ErrnoException | null, a: LookupAddress[]) => void) => void
/**
 * A DNS lookup that refuses a name with any private address (outputs.ts privateAddress) and hands
 * the connection exactly the addresses it checked: the request goes where it was judged to go, so
 * a name that answers differently a moment later (DNS rebinding) changes nothing. [resolve]: the
 * system's resolver (a test's own).
 */
export function checkedLookup(resolve: Resolver = dnsLookup as unknown as Resolver) {
  return (host: string, opts: { all?: boolean; family?: number; hints?: number }, cb: (...a: any[]) => void): void => {
    resolve(host, { family: opts?.family, hints: opts?.hints, all: true }, (e, list) => {
      if (e) return cb(e)
      if (!list?.length) return cb(Object.assign(new Error(`No address for ${host}.`), { code: 'ENOTFOUND' }))
      if (list.some((a) => privateAddress(a.address))) return cb(privateError())
      if (opts?.all) cb(null, list)
      else cb(null, list[0]!.address, list[0]!.family)
    })
  }
}
/**
 * A webhook output's POST: https only, 10 s at most in all (the lookup included), no redirects
 * followed, a connection of its own, the answer's status taken and its body never read. Never to
 * this computer or a private network, judged after DNS and pinned to the address judged
 * (`private_address`), unless its host is one of [allowHosts] (settings.json
 * `webhookPrivateHosts`). Resolves with the status; a network error or the time running out
 * rejects (the latter with code `timeout`). [ca]: a test's own certificate authority.
 */
export function postWebhook(r: WebhookRequest, o: { ca?: string | Buffer; timeoutMs?: number; allowHosts?: readonly string[]; resolve?: Resolver } = {}): Promise<{ status: number }> {
  return new Promise((resolve, reject) => {
    let u: URL
    try {
      u = new URL(r.url)
    } catch {
      return reject(Object.assign(new Error('That isn’t a web address.'), { code: 'bad_url' }))
    }
    if (u.protocol !== 'https:') return reject(Object.assign(new Error('Only https.'), { code: 'insecure_url' }))
    const open = hostAllowed(o.allowHosts, u.hostname)
    const host = hostKey(u.hostname)
    // An address as written is judged as it stands (no lookup is made for one).
    if (!open && (isIP(host) ? privateAddress(host) : privateHost(host))) return reject(privateError())
    const body = Buffer.from(r.body, 'utf8')
    const headers: Record<string, string> = { 'user-agent': 'Hypernova', 'content-length': String(body.length) }
    for (const [k, v] of Object.entries(r.headers)) headers[k] = v.replace(/[\r\n]/g, ' ')
    const ms = o.timeoutMs ?? WEBHOOK_TIMEOUT_MS
    const req = request(u, { method: 'POST', headers, ca: o.ca, timeout: ms, agent: false, ...(open ? {} : { lookup: checkedLookup(o.resolve) as never }) }, (res) => {
      // Only the status matters: the body is never read (however big), the connection closed.
      resolve({ status: res.statusCode ?? 0 })
      res.destroy()
    })
    const late = () => req.destroy(Object.assign(new Error('No answer in time.'), { code: 'timeout' }))
    const timer = setTimeout(late, ms)
    timer.unref()
    req.on('timeout', late)
    req.on('error', (e) => reject(e))
    req.on('close', () => clearTimeout(timer))
    req.end(body)
  })
}

// ── File outputs ──────────────────────────────────────────────────────────
type FileCheck = { path: string } | { code: 'no_project' | 'outside_projects' | 'secret_file' | 'bad_path' }
/**
 * A file output's path: a file in a folder inside the projects folder ([allowed]: the daemon's
 * path rule), never a secret file, never a folder, never a symbolic link (it could lead anywhere).
 */
export function checkOutputFile(p: string, allowed: (path: string) => string | null): FileCheck {
  const raw = p.startsWith('~/') ? join(HOME, p.slice(2)) : p
  if (!raw.startsWith('/') || raw.length > 1024 || raw.endsWith('/') || raw.includes('\0')) return { code: 'bad_path' }
  const dir = allowed(dirname(raw))
  if (!dir) return existsSync(dirname(raw)) ? { code: 'outside_projects' } : { code: 'no_project' }
  try {
    if (!statSync(dir).isDirectory()) return { code: 'bad_path' }
  } catch {
    return { code: 'no_project' }
  }
  const full = join(dir, basename(raw))
  if (isSecretFile(full)) return { code: 'secret_file' }
  try {
    const st = lstatSync(full)
    if (st.isSymbolicLink() || !st.isFile()) return { code: 'bad_path' }
  } catch {}
  return { path: full }
}
/** A file output's line, after [path] is checked again (things move). */
export function appendOutput(path: string, line: string, allowed: (path: string) => string | null): void {
  const c = checkOutputFile(path, allowed)
  if ('code' in c) throw bad(c.code, c.code === 'secret_file' ? 'That file holds secrets.' : c.code === 'outside_projects' ? 'That file is outside your projects folder.' : 'That file’s folder is gone.')
  // Never through a link put there since it was checked.
  const fd = openSync(c.path, constants.O_WRONLY | constants.O_APPEND | constants.O_CREAT | constants.O_NOFOLLOW, 0o644)
  try {
    writeSync(fd, line)
  } finally {
    closeSync(fd)
  }
}

// ── File watches ──────────────────────────────────────────────────────────
const SKIP = new Set(['.git', 'node_modules'])
/** The files under [root] now (relative), for telling a new file from a changed one; 20 000 at most. */
function filesUnder(root: string): Set<string> {
  const out = new Set<string>()
  const walk = (dir: string) => {
    if (out.size >= 20_000) return
    let names: import('node:fs').Dirent[]
    try {
      names = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of names) {
      if (SKIP.has(e.name)) continue
      const full = join(dir, e.name)
      if (e.isDirectory()) walk(full)
      else out.add(relative(root, full).split(sep).join('/'))
    }
  }
  walk(root)
  return out
}
/**
 * A recursive watch on [root] (FSEvents, inotify): [on] for each file created, changed or deleted,
 * its path relative to [root]. A rename that leaves a file there is a new file unless it was
 * there before (an editor's save replaces the file). Returns the stop.
 */
export function watchFolder(root: string, on: (rel: string, event: FileEvent) => void): () => void {
  const known = filesUnder(root)
  const w = fsWatch(root, { recursive: true }, (type, name) => {
    if (!name) return
    const rel = String(name).split(sep).join('/')
    if (rel.split('/').some((p) => SKIP.has(p))) return
    let isFile = false
    try {
      isFile = statSync(join(root, rel)).isFile()
    } catch {}
    if (type === 'change') {
      if (!isFile) return
      known.add(rel)
      return on(rel, 'changed')
    }
    if (isFile) {
      const was = known.has(rel)
      known.add(rel)
      return on(rel, was ? 'changed' : 'created')
    }
    if (known.delete(rel)) on(rel, 'deleted')
  })
  w.on('error', () => {})
  return () => w.close()
}

// ── GitHub watches ────────────────────────────────────────────────────────
const run = (cmd: string, args: string[], cwd?: string) =>
  new Promise<string>((resolve, reject) =>
    execFile(cmd, args, { cwd, timeout: 30_000, maxBuffer: 8 * 1024 * 1024, env: { ...process.env, GH_PROMPT_DISABLED: '1', NO_COLOR: '1', GH_NO_UPDATE_NOTIFIER: '1' } }, (e, out) => (e ? reject(e) : resolve(out))),
  )
const firstLine = (s: unknown) => String(s ?? '').split('\n').map((x) => x.trim()).find(Boolean)?.slice(0, 140) ?? ''
const FAILED = new Set(['FAILURE', 'ERROR', 'TIMED_OUT', 'CANCELLED', 'ACTION_REQUIRED', 'STARTUP_FAILURE'])

/** gh is installed and signed in. */
export async function ghReady(): Promise<boolean> {
  try {
    await run('gh', ['auth', 'status'])
    return true
  } catch {
    return false
  }
}
/** The folder's git remote is on GitHub. */
export async function githubRepo(path: string): Promise<boolean> {
  try {
    return /github\.com[:/]/.test(await run('git', ['-C', path, 'remote', '-v']))
  } catch {
    return false
  }
}
/**
 * What a GitHub watch sees now, one item per thing that can be new: pull requests open in the
 * repository; reviews and comments, not yours, on your open pull requests; failed checks on their
 * newest commits. Null when gh is missing, signed out, or failed.
 */
export async function ghLook(repo: string, events: GithubEvent[]): Promise<GhItem[] | null> {
  try {
    const items: GhItem[] = []
    if (events.includes('pr_opened')) {
      const prs = JSON.parse(await run('gh', ['pr', 'list', '--state', 'open', '--limit', '50', '--json', 'number,title,author'], repo)) as any[]
      for (const p of prs) items.push({ key: `pr:${p.number}`, event: 'pr_opened', line: maskSecrets(`PR #${p.number} opened by ${p.author?.login ?? 'someone'}: ${firstLine(p.title)}`) })
    }
    if (events.includes('review_comment') || events.includes('checks_failed')) {
      const me = (await run('gh', ['api', 'user', '--jq', '.login'], repo)).trim()
      const mine = JSON.parse(await run('gh', ['pr', 'list', '--author', '@me', '--state', 'open', '--limit', '30', '--json', 'number,title,reviews,comments,statusCheckRollup,headRefOid'], repo)) as any[]
      for (const p of mine) {
        if (events.includes('review_comment')) {
          for (const r of p.reviews ?? []) {
            if (!r?.id || r.author?.login === me) continue
            const said = firstLine(r.body)
            items.push({ key: `rv:${r.id}`, event: 'review_comment', line: maskSecrets(`PR #${p.number}: ${r.author?.login ?? 'someone'} reviewed (${String(r.state ?? 'commented').toLowerCase().replace(/_/g, ' ')})${said ? `: ${said}` : ''}`) })
          }
          for (const c of p.comments ?? []) {
            if (!c?.id || c.author?.login === me) continue
            items.push({ key: `cm:${c.id}`, event: 'review_comment', line: maskSecrets(`PR #${p.number}: ${c.author?.login ?? 'someone'} commented: ${firstLine(c.body)}`) })
          }
        }
        if (events.includes('checks_failed')) {
          for (const c of p.statusCheckRollup ?? []) {
            if (!FAILED.has(String(c?.conclusion ?? c?.state ?? '').toUpperCase())) continue
            const name = c.name ?? c.context ?? 'a check'
            items.push({ key: `ck:${p.number}:${p.headRefOid ?? ''}:${name}`, event: 'checks_failed', line: maskSecrets(`PR #${p.number}: ${name} failed`) })
          }
        }
      }
    }
    return items
  } catch {
    return null
  }
}

// ── The `hypernova` command ───────────────────────────────────────────────
/** `<support>/bin/hypernova`: the command a webhook trigger is fired with on this computer. */
export const HYPERNOVA_BIN = join(SUPPORT, 'bin', 'hypernova')
const q = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`
/**
 * Write `<support>/bin/hypernova` for this daemon's Node and sources, so `hypernova automation fire`
 * works on a Mac too (Linux has its installed launcher as well). A daemon on its own state folder
 * or ports (development) hands them on.
 */
export function writeHypernovaBin(): void {
  const cli = join(dirname(fileURLToPath(import.meta.url)), '..', 'cli', 'hypernova.ts')
  const env = ['HYPERNOVA_SUPPORT', 'HYPERNOVA_LOGS', 'HYPERNOVA_LOCAL_PORT', 'HYPERNOVA_API_PORT'].filter((k) => process.env[k]).map((k) => `export ${k}=${q(process.env[k]!)}\n`).join('')
  writeText(
    HYPERNOVA_BIN,
    `#!/bin/sh\n# Hypernova's command (written by the Hypernova daemon): hypernova automation fire <id>.\n${env}N=${q(process.execPath)}\nC=${q(cli)}\n[ -x "$N" ] && [ -f "$C" ] || { echo "hypernova: Hypernova moved; open it again on this computer." >&2; exit 1; }\nexec "$N" --disable-warning=ExperimentalWarning "$C" "$@"\n`,
    0o755,
  )
}
/**
 * The line to copy for firing [id] here, its path single-quoted when it needs quoting: pasted into
 * a shell, nothing in the path ($, a backquote, a quote) is read as shell syntax.
 */
export function fireCommand(id: string, bin = HYPERNOVA_BIN): string {
  return `${/[^\w/.-]/.test(bin) ? q(bin) : bin} automation fire ${id}`
}
