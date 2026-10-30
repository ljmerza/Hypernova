// Usage feed (status line files), projects, files and artifact previews.
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, readdirSync, statSync, unlinkSync, createReadStream, realpathSync } from 'node:fs'
import { basename, join, resolve, sep } from 'node:path'
import { execFileSync } from 'node:child_process'
import { request as httpRequest, type IncomingMessage, type ServerResponse } from 'node:http'
import { API_PORT, LOCAL_PORT, SUPPORT, log, randomToken } from './util.ts'
import { attachmentFile } from './attachments.ts'
import { isSubscription, settings } from './store.ts'
import { broadcast, getSession, noteStatusEffort, noteStatusModel, offerUsage, projectsFromSessions } from './hub.ts'
import { FileError, allowedPath, keyPlace, tooBroad } from './files.ts'
import { isSecretFile } from './secretfiles.ts'
import { mimeByName, needsSniff, sniff } from './mime.ts'
import { requestCheck } from './account.ts'

// ── Usage from the status line tap ───────────────────────────────────────
const STATUS_DIR = join(SUPPORT, 'statusline')
/**
 * Claude Code puts the plan's limits in the status line only while it's signed in with one. When
 * they're gone from every session on the built-in provider that wrote one lately, for half an
 * hour, the login or the plan may have ended: worth a check (once in that half hour).
 */
const LIMITS_GONE_MS = 30 * 60_000
let limitsSeenAt = 0
let goneCheckAt = 0
/**
 * When each session's limits were last true. A session's numbers only change when it talks to
 * Anthropic, but idle sessions keep repainting their status line with the old ones, so its file's
 * time says nothing: its API time going up does. First seen (after a restart), its transcript's
 * last write stands in.
 */
const limitsAsOf = new Map<string, { api: number; at: number }>()
export function pollStatusLine(): void {
  let files: string[] = []
  try {
    files = readdirSync(STATUS_DIR)
  } catch {
    return
  }
  let fresh = 0
  let withLimits = 0
  for (const f of files) {
    const p = join(STATUS_DIR, f)
    try {
      const s = statSync(p)
      if (f.includes('.json.') && Date.now() - s.mtimeMs > 60_000) {
        unlinkSync(p)
        continue
      }
      if (!f.endsWith('.json') || Date.now() - s.mtimeMs > 6 * 3600_000) continue
      const o = JSON.parse(readFileSync(p, 'utf8'))
      // A /model in the terminal shows here first (hub.ts).
      noteStatusModel(String(o.session_id ?? ''), o.model?.id, s.mtimeMs)
      // And its effort (its default's level too, and a /effort typed in the terminal).
      noteStatusEffort(String(o.session_id ?? ''), o.effort?.level, s.mtimeMs)
      const rl = o.rate_limits
      const has = !!(rl && (rl.five_hour || rl.seven_day))
      if (Date.now() - s.mtimeMs < 10 * 60_000 && isSubscription(getSession(String(o.session_id ?? ''))?.providerId)) {
        fresh++
        if (has) withLimits++
      }
      if (has) limitsSeenAt = Math.max(limitsSeenAt, s.mtimeMs)
      if (!rl) continue
      const conv = (w: any) => (w && w.used_percentage != null ? { pct: Number(w.used_percentage), resetsAt: Number(w.resets_at) * 1000 } : null)
      const sid = String(o.session_id ?? f)
      const api = Number(o.cost?.total_api_duration_ms)
      const prev = limitsAsOf.get(sid)
      let at = prev?.at ?? 0
      if (!prev) {
        try {
          at = statSync(String(o.transcript_path)).mtimeMs
        } catch {
          at = s.mtimeMs
        }
      } else if (Number.isFinite(api) && api > prev.api) at = s.mtimeMs
      limitsAsOf.set(sid, { api: Number.isFinite(api) ? api : (prev?.api ?? 0), at })
      offerUsage(conv(rl.five_hour), conv(rl.seven_day), at)
    } catch {}
  }
  const now = Date.now()
  if (fresh && !withLimits && limitsSeenAt && now - limitsSeenAt > LIMITS_GONE_MS && now - goneCheckAt > LIMITS_GONE_MS) {
    goneCheckAt = now
    void requestCheck()
  }
}

// ── Projects ─────────────────────────────────────────────────────────────
export type Project = { path: string; name: string; lastActivityAt: number | null; sessionCount: number }
export function listProjects(): Project[] {
  const out = new Map<string, Project>()
  for (const [path, p] of projectsFromSessions()) {
    if (path === process.env.HOME) continue
    out.set(path, { path, name: basename(path), lastActivityAt: p.lastActivityAt, sessionCount: p.count })
  }
  try {
    for (const d of readdirSync(settings.projectsRoot, { withFileTypes: true })) {
      if (!d.isDirectory() || d.name.startsWith('.')) continue
      const path = join(settings.projectsRoot, d.name)
      if (!out.has(path)) {
        let m: number | null = null
        try {
          m = statSync(path).mtimeMs
        } catch {}
        out.set(path, { path, name: d.name, lastActivityAt: m, sessionCount: 0 })
      }
    }
  } catch {}
  return [...out.values()].sort((a, b) => (b.lastActivityAt ?? 0) - (a.lastActivityAt ?? 0))
}

export function createProject(name: string, git: boolean): Project {
  const clean = name.trim().replace(/[\/\\:]/g, '-').replace(/^\.+/, '')
  if (!clean) throw Object.assign(new Error('Give the project a name.'), { status: 400 })
  const path = join(settings.projectsRoot, clean)
  if (!existsSync(path)) {
    mkdirSync(path, { recursive: true })
    if (git) {
      try {
        execFileSync('git', ['init', '-q'], { cwd: path, timeout: 10000 })
      } catch {}
    }
    log('project created', path)
  }
  const p = { path, name: clean, lastActivityAt: Date.now(), sessionCount: 0 }
  broadcast({ t: 'projects', projects: listProjects() })
  return p
}

// ── Files (files.ts) ─────────────────────────────────────────────────────
// Kept here too for the callers from before the files moved (automations.ts).
export { allowedPath } from './files.ts'

/** Serve a file as it is (a preview's, the checklist's screenshots); a folder serves its index.html. */
export function sendFile(res: ServerResponse, full: string, maxBytes = 20 * 1024 * 1024): void {
  let s
  try {
    s = statSync(full)
  } catch {
    res.writeHead(404).end()
    return
  }
  if (s.isDirectory()) {
    const idx = join(full, 'index.html')
    if (existsSync(idx)) return sendFile(res, idx, maxBytes)
    res.writeHead(404).end()
    return
  }
  if (s.size > maxBytes) {
    res.writeHead(413, { 'content-type': 'application/json' }).end(JSON.stringify({ error: { code: 'too_large', message: 'That file is too large to open on the phone.', size: s.size, max: maxBytes } }))
    return
  }
  const type = mimeByName(full) ?? (needsSniff(full) ? sniffSync(full) : 'application/octet-stream')
  res.writeHead(200, { 'content-type': type, 'content-length': s.size, 'cache-control': 'no-store' })
  const stream = createReadStream(full)
  // Removed or unreadable mid-way: end the answer rather than leave it hanging.
  stream.on('error', () => res.destroy())
  stream.pipe(res)
}

function sniffSync(full: string): string {
  let fd: number | null = null
  try {
    fd = openSync(full, 'r')
    const buf = Buffer.alloc(8192)
    return sniff(buf.subarray(0, readSync(fd, buf, 0, buf.length, 0)))
  } catch {
    return 'application/octet-stream'
  } finally {
    if (fd != null) closeSync(fd)
  }
}

// ── Previews: signed URLs a WebView can load without headers ─────────────
type Preview = { kind: 'dir'; root: string; entry: string } | { kind: 'port'; port: number }
const previews = new Map<string, { p: Preview; exp: number }>()
/**
 * A preview of a file or a dev server. [allowed]: where a file may be, the phone's browsing rule
 * (files.ts allowedPath) unless the caller says otherwise.
 */
export function createPreview(body: { path?: string; port?: number }, base: string, allowed: (p: string) => string | null = allowedPath): { url: string } {
  const token = randomToken(18)
  if (body.port) {
    const port = Number(body.port)
    if (!Number.isInteger(port) || port <= 0 || port >= 65536 || port === API_PORT || port === API_PORT + 1 || port === LOCAL_PORT) throw Object.assign(new Error('Not a valid port.'), { status: 400 })
    previews.set(token, { p: { kind: 'port', port }, exp: Date.now() + 12 * 3600_000 })
    return { url: `${base}/p/${token}/` }
  }
  // An HTML file attached from the phone opens as a page too.
  const attached = body.path ? attachmentFile(body.path) : null
  const full = body.path ? (allowed(body.path) ?? attached) : null
  if (!full) throw new FileError(403, 'outside_projects', 'That file is outside the folders the phone can open.')
  if (!existsSync(full)) throw new FileError(404, 'not_found', 'That file isn’t on the Mac any more.')
  if (!attached && isSecretFile(full)) throw new FileError(403, 'secret_file', 'Files with keys and passwords stay on the Mac.')
  const isDir = statSync(full).isDirectory()
  const root = isDir ? full : resolve(full, '..')
  const entry = isDir ? '' : basename(full)
  // The link serves everything in [root] to whoever holds it: never the home folder, or a folder
  // holding the daemon's state or Claude Code's (a page in ~ would serve ~/.claude with it).
  let rootReal = root
  try {
    rootReal = realpathSync(root)
  } catch {}
  if (!attached && tooBroad(rootReal)) throw new FileError(403, 'outside_projects', 'A page in that folder can’t be opened on the phone: the folder holds more than your project.')
  previews.set(token, { p: { kind: 'dir', root, entry }, exp: Date.now() + 12 * 3600_000 })
  return { url: `${base}/p/${token}/${encodeURIComponent(entry)}` }
}

function previewFor(token: string): Preview | null {
  if (previews.size > 200) for (const [k, x] of previews) if (x.exp < Date.now()) previews.delete(k)
  const v = previews.get(token)
  if (!v || v.exp < Date.now()) return null
  return v.p
}

/** Serve /p/<token>/<rest>, or any other path when the request carries the preview cookie. */
export function servePreview(req: IncomingMessage, res: ServerResponse, token: string, rest: string, setCookie: boolean): boolean {
  const p = previewFor(token)
  if (!p) return false
  const headers: Record<string, string> = setCookie ? { 'set-cookie': `hn_preview=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=43200` } : {}
  if (p.kind === 'dir') {
    let rel = ''
    try {
      rel = decodeURIComponent(rest.split('?')[0] ?? '')
    } catch {}
    const full = resolve(p.root, rel || p.entry || 'index.html')
    const realOf = (x: string) => {
      try {
        return realpathSync(x)
      } catch {
        return ''
      }
    }
    let real = realOf(full)
    // A folder is its index.html, checked as itself: a link there (a repository can carry one) must
    // not lead out of the folder, to a key, or anywhere the checks below keep closed.
    try {
      if (real && statSync(real).isDirectory()) real = realOf(join(real, 'index.html'))
    } catch {
      real = ''
    }
    const rootReal = realOf(p.root) || p.root
    // Follow symlinks before checking, and never serve dotfiles (.env, .git), files with keys
    // (credentials.json, server.key, *.pem) or the home folder's key places: the link needs no token.
    const inside = real && real.startsWith(rootReal + sep)
    const dotted = real.slice(rootReal.length).split(sep).some((seg) => seg.startsWith('.'))
    if (!inside || dotted || isSecretFile(real) || keyPlace(real)) {
      res.writeHead(404, headers).end('Not found')
      return true
    }
    for (const [k, v] of Object.entries(headers)) res.setHeader(k, v)
    // The path checked, not the one asked for: a link swapped in meanwhile isn't followed again.
    sendFile(res, real, 200 * 1024 * 1024)
    return true
  }
  const fwd: Record<string, string | string[]> = {}
  for (const [k, v] of Object.entries(req.headers)) if (v != null && !['host', 'authorization', 'cookie'].includes(k)) fwd[k] = v
  fwd.host = `127.0.0.1:${p.port}`
  const up = httpRequest({ host: '127.0.0.1', port: p.port, method: req.method, path: '/' + rest, headers: fwd }, (r) => {
    res.writeHead(r.statusCode ?? 502, { ...r.headers, ...headers })
    r.pipe(res)
  })
  res.on('close', () => up.destroy())
  up.on('error', () => {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain', ...headers })
    res.end(`Nothing is running on port ${p.port} on the Mac right now.`)
  })
  req.pipe(up)
  return true
}
