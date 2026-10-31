// "What changed" (docs/PROTOCOL.md, "What changed"): the files a session's folder has changed
// since the last commit, each with its +/- counts, and one file's diff as unified text. Read-only:
// git status and git diff, nothing written. The phone's Commit button is a prompt to Claude, so
// the daemon never commits.
//
// What's safe to show: only files inside the session's folder (real path, so a symlink can't lead
// out), never a file secretfiles.ts blocks (a project's .env), and a diff capped in size.
import { spawn } from 'node:child_process'
import { closeSync, fstatSync, lstatSync, openSync, readSync, realpathSync } from 'node:fs'
import { basename, isAbsolute, relative, resolve, sep } from 'node:path'
import { isSecretFile } from './secretfiles.ts'
import { MACHINE } from './platform.ts'

export type ChangedFile = {
  /** Relative to the session's folder, with "/". */
  path: string
  status: 'M' | 'A' | 'D'
  /** Not tracked by git yet (a file Claude just made). */
  untracked: boolean
  /** Lines added and removed; null for a picture, a binary or a file too big to count. */
  add: number | null
  del: number | null
  binary: boolean
  /** This session edited it with its own tools (Edit, Write...). */
  touched: boolean
}
export type Changes = {
  /** False: the folder isn't in a git repository, so there is nothing to compare with. */
  git: boolean
  branch: string | null
  /** "session": only what this session's edit tools touched; "tree": everything changed in the folder. */
  scope: 'session' | 'tree'
  files: ChangedFile[]
  /** Changed files this session didn't touch, left out of a "session" list. */
  others: number
  /** Credential files (.env...) that changed: counted, never listed. */
  hidden: number
  /** More files than the list holds (300). */
  more: boolean
  totalAdd: number
  totalDel: number
  /** The branch's upstream ("origin/main"); null with none, on a detached HEAD, or when git was too slow. */
  upstream: string | null
  /** Commits ahead of and behind the upstream; null without one, when it's gone, or when git was too slow. */
  ahead: number | null
  behind: number | null
  /** Files with uncommitted changes in the folder, whatever the scope (untracked and credential files too). */
  dirty: number
  /** The linked worktree's name (git's own: its folder under .git/worktrees) when the folder is in one; null in the main checkout. */
  worktree: string | null
}
export type FileDiff = { path: string; diff: string; truncated: boolean; binary: boolean }

const MAX_FILES = 300
/** A diff longer than this is cut (the phone has to draw it). */
export const MAX_DIFF = 256 * 1024
/** Counting lines of a new file reads at most this much; bigger files show no counts. */
const COUNT_MAX = 1024 * 1024
const EMPTY_TREE = '4b825dc642cb6eb9a060e54bf8d69288fbee4904'
const GIT_TIMEOUT = 15_000

type Out = { code: number | null; out: string; cut: boolean }

/**
 * Runs git, collecting at most [cap] bytes of output (then stops it). Never prompts, never
 * touches the index (optional locks off), ignores the user's pager, diff tools and textconv.
 */
function git(cwd: string, args: string[], cap = 8 * 1024 * 1024, timeout = GIT_TIMEOUT): Promise<Out> {
  return new Promise((done) => {
    const p = spawn('git', ['--no-optional-locks', '-c', 'core.quotepath=off', '-c', 'core.fsmonitor=false', ...args], {
      cwd,
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_PAGER: 'cat', GIT_EXTERNAL_DIFF: '', LC_ALL: 'C' },
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    const chunks: Buffer[] = []
    let size = 0
    let cut = false
    const timer = setTimeout(() => p.kill('SIGKILL'), timeout)
    p.stdout.on('data', (c: Buffer) => {
      if (cut) return
      size += c.length
      if (size > cap) {
        chunks.push(c.subarray(0, c.length - (size - cap)))
        cut = true
        p.kill('SIGKILL')
      } else chunks.push(c)
    })
    p.on('error', () => {
      clearTimeout(timer)
      done({ code: null, out: '', cut: false })
    })
    p.on('close', (code) => {
      clearTimeout(timer)
      done({ code, out: Buffer.concat(chunks).toString('utf8'), cut })
    })
  })
}

const real = (p: string): string | null => {
  try {
    return realpathSync(p)
  } catch {
    return null
  }
}

/** [full] is [root] or inside it. */
const inside = (root: string, full: string) => full === root || full.startsWith(root + sep)

/** First 8 KB hold a NUL byte: git's own test for a binary. */
function looksBinary(buf: Buffer): boolean {
  return buf.subarray(0, 8192).includes(0)
}

/** Lines of a new file, or null when it is binary, a link, or too big to bother. */
function countLines(full: string): { lines: number | null; binary: boolean } {
  try {
    const st = lstatSync(full)
    if (!st.isFile()) return { lines: null, binary: false }
    if (st.size > COUNT_MAX) return { lines: null, binary: false }
    const fd = openSync(full, 'r')
    try {
      const buf = Buffer.alloc(fstatSync(fd).size)
      const n = readSync(fd, buf, 0, buf.length, 0)
      const b = buf.subarray(0, n)
      if (looksBinary(b)) return { lines: null, binary: true }
      let lines = 0
      for (const c of b) if (c === 10) lines++
      if (n && b[n - 1] !== 10) lines++
      return { lines, binary: false }
    } finally {
      closeSync(fd)
    }
  } catch {
    return { lines: null, binary: false }
  }
}

/** The repository's top folder and what to compare with (HEAD, or the empty tree before a first commit). */
async function repoOf(cwd: string): Promise<{ top: string; base: string; branch: string | null } | null> {
  const top = await git(cwd, ['rev-parse', '--show-toplevel'])
  if (top.code !== 0 || !top.out.trim()) return null
  const head = await git(cwd, ['rev-parse', '--verify', '-q', 'HEAD'])
  const br = await git(cwd, ['symbolic-ref', '--short', '-q', 'HEAD'])
  return { top: real(top.out.trim()) ?? top.out.trim(), base: head.code === 0 ? 'HEAD' : EMPTY_TREE, branch: br.code === 0 ? br.out.trim() || null : null }
}

/** The git line beside the changes: the branch's upstream, how far apart they are, and the worktree. */
type GitLine = Pick<Changes, 'upstream' | 'ahead' | 'behind' | 'worktree'>

/**
 * The git line's own commands answer within this (ms), or what they'd say is left out: the changes
 * still come. Tests shorten it.
 */
export const gitLineTimeout = { ms: 3000 }
/** A git line is kept this long per folder and branch: the session sheet asks each time it opens. */
const LINE_TTL = 5000
const lines = new Map<string, { at: number; line: GitLine }>()

/**
 * [root]'s upstream with its ahead/behind counts (`for-each-ref`'s `upstream:track`: "[ahead 2,
 * behind 1]", "[gone]", nothing when even), and the linked worktree it is in, if any (its git dir
 * isn't the common one). Read-only, local only: nothing is fetched, so the counts are as of the
 * last fetch.
 */
async function lineOf(root: string, branch: string | null): Promise<GitLine> {
  const key = `${root}\0${branch ?? ''}`
  const hit = lines.get(key)
  if (hit && Date.now() - hit.at < LINE_TTL) return hit.line
  const ms = gitLineTimeout.ms
  const [dirs, track] = await Promise.all([
    git(root, ['rev-parse', '--git-dir', '--git-common-dir'], 64 * 1024, ms),
    branch ? git(root, ['for-each-ref', '--format=%(upstream:short)%00%(upstream:track)', `refs/heads/${branch}`], 64 * 1024, ms) : null,
  ])
  const line: GitLine = { upstream: null, ahead: null, behind: null, worktree: null }
  const [gitDir, commonDir] = dirs.code === 0 ? dirs.out.split('\n').map((l) => l.trim()) : []
  if (gitDir && commonDir) {
    const own = resolve(root, gitDir)
    const common = resolve(root, commonDir)
    if ((real(own) ?? own) !== (real(common) ?? common)) line.worktree = basename(own)
  }
  const [up, tr] = track?.code === 0 ? track.out.replace(/\n$/, '').split('\0') : []
  if (up) {
    line.upstream = up
    if (tr !== '[gone]') {
      line.ahead = Number(/ahead (\d+)/.exec(tr ?? '')?.[1] ?? 0)
      line.behind = Number(/behind (\d+)/.exec(tr ?? '')?.[1] ?? 0)
    }
  }
  lines.set(key, { at: Date.now(), line })
  return line
}

/** One status entry: a path in the repo and how it changed. */
type Entry = { rel: string; status: 'M' | 'A' | 'D'; untracked: boolean }

function parseStatus(out: string): { x: string; y: string; file: string }[] {
  // "XY path\0" each; --no-renames means no second path.
  const res: { x: string; y: string; file: string }[] = []
  for (const part of out.split('\0')) {
    if (part.length < 4) continue
    res.push({ x: part[0]!, y: part[1]!, file: part.slice(3) })
  }
  return res
}

function statusOf(x: string, y: string): 'M' | 'A' | 'D' {
  if (x === 'D' || y === 'D') return 'D'
  if (x === 'A' || y === 'A') return 'A'
  return 'M'
}

/** "12\t3\tpath\0" lines; "-\t-\tpath" for binaries. */
function parseNumstat(out: string): Map<string, { add: number | null; del: number | null }> {
  const m = new Map<string, { add: number | null; del: number | null }>()
  for (const part of out.split('\0')) {
    const t = /^(-|\d+)\t(-|\d+)\t(.*)$/s.exec(part)
    if (t) m.set(t[3]!, { add: t[1] === '-' ? null : Number(t[1]), del: t[2] === '-' ? null : Number(t[2]) })
  }
  return m
}

/**
 * The files changed in [cwd] since the last commit. [touched] are the absolute paths this session's
 * edit tools wrote: with any, the list is those files that really changed (and `others` counts the
 * rest, `all` brings them in); without, the whole working tree of the folder.
 */
export async function changesOf(cwd: string, touchedPaths: Iterable<string>, all = false): Promise<Changes> {
  const empty: Changes = {
    git: false, branch: null, scope: 'tree', files: [], others: 0, hidden: 0, more: false, totalAdd: 0, totalDel: 0,
    upstream: null, ahead: null, behind: null, dirty: 0, worktree: null,
  }
  const root = real(cwd)
  if (!root) return empty
  const repo = await repoOf(root)
  if (!repo) return empty
  const gitLine = lineOf(root, repo.branch)

  // "." limits both commands to the session's folder, whatever the repository holds.
  const st = await git(root, ['status', '--porcelain=v1', '-z', '-uall', '--no-renames', '--', '.'])
  const nums = await git(root, ['diff', repo.base, '--numstat', '-z', '--no-renames', '--no-ext-diff', '--no-textconv', '--', '.'])
  const counts = parseNumstat(nums.out)

  const touched = new Set<string>()
  for (const p of touchedPaths) {
    const full = real(p) ?? resolve(p)
    if (inside(root, full)) touched.add(relative(root, full).split(sep).join('/'))
  }

  const entries: Entry[] = []
  let hidden = 0
  for (const s of parseStatus(st.out)) {
    const full = resolve(repo.top, s.file)
    // A folder git can't descend into (a nested repository) comes as "dir/": not a file.
    if (s.file.endsWith('/')) continue
    const rel = relative(root, full).split(sep).join('/')
    if (!rel || rel.startsWith('..') || isAbsolute(rel)) continue
    if (isSecretFile(full)) {
      hidden++
      continue
    }
    const untracked = s.x === '?' && s.y === '?'
    // An ignored file shows as "!!" only with --ignored; skipped anyway.
    if (s.x === '!') continue
    entries.push({ rel, status: untracked ? 'A' : statusOf(s.x, s.y), untracked })
  }

  const wasTouched = (e: Entry) => touched.has(e.rel)
  const anyTouched = entries.some(wasTouched)
  const scope: Changes['scope'] = touched.size > 0 && anyTouched && !all ? 'session' : 'tree'
  const shown = scope === 'session' ? entries.filter(wasTouched) : entries
  const others = scope === 'session' ? entries.length - shown.length : 0

  // Touched files first, then by path.
  shown.sort((a, b) => Number(wasTouched(b)) - Number(wasTouched(a)) || a.rel.localeCompare(b.rel))
  const more = shown.length > MAX_FILES
  const files: ChangedFile[] = []
  for (const e of shown.slice(0, MAX_FILES)) {
    // git's numstat names the path from the repository's top, like status.
    const key = relative(repo.top, resolve(root, e.rel)).split(sep).join('/')
    let add: number | null = null
    let del: number | null = null
    let binary = false
    if (e.untracked) {
      const c = countLines(resolve(root, e.rel))
      add = c.lines
      del = c.lines === null ? null : 0
      binary = c.binary
    } else {
      const n = counts.get(key)
      if (n) {
        add = n.add
        del = n.del
        binary = n.add === null
      }
    }
    files.push({ path: e.rel, status: e.status, untracked: e.untracked, add, del, binary, touched: wasTouched(e) })
  }
  return {
    git: true,
    branch: repo.branch,
    scope,
    files,
    others,
    hidden,
    more,
    totalAdd: files.reduce((n, f) => n + (f.add ?? 0), 0),
    totalDel: files.reduce((n, f) => n + (f.del ?? 0), 0),
    ...(await gitLine),
    dirty: entries.length + hidden,
  }
}

export class ChangeError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/**
 * One changed file's diff against the last commit, unified, for the phone to colour. The path must be
 * one of the files that changed (not any file in the folder), inside the folder, and not a secret.
 */
export async function diffOf(cwd: string, rel: string): Promise<FileDiff> {
  const root = real(cwd)
  if (!root) throw new ChangeError(404, 'no_folder', 'That folder is gone.')
  if (!rel || rel.includes('\0') || isAbsolute(rel)) throw new ChangeError(400, 'bad_path', 'That is not a file of this session.')
  const full = resolve(root, rel)
  // A symlink in the path can lead out of the folder: check where the file really is (a deleted
  // file has no real path, so its folder's).
  const anchor = real(full) ?? real(resolve(full, '..'))
  if (!anchor || !inside(root, anchor) || !inside(root, full)) throw new ChangeError(403, 'outside', 'That file is outside the session’s folder.')
  if (isSecretFile(full) || isSecretFile(anchor)) throw new ChangeError(403, 'secret', `That file holds credentials; it stays on the ${MACHINE}.`)
  const repo = await repoOf(root)
  if (!repo) throw new ChangeError(404, 'no_git', 'This folder is not a git repository.')
  const key = relative(repo.top, full).split(sep).join('/')
  const st = await git(root, ['status', '--porcelain=v1', '-z', '-uall', '--no-renames', '--', key])
  const entry = parseStatus(st.out).find((e) => e.file === key)
  if (!entry) throw new ChangeError(404, 'unchanged', 'That file has no changes.')

  const untracked = entry.x === '?' && entry.y === '?'
  let r: Out
  if (untracked) {
    // A symlink would show its target's path only, but never read through it.
    r = await git(root, ['diff', '--no-index', '--no-color', '--no-ext-diff', '--no-textconv', '-U3', '--', '/dev/null', rel], MAX_DIFF + 1)
  } else {
    r = await git(root, ['diff', repo.base, '--no-color', '--no-ext-diff', '--no-textconv', '--no-renames', '-U3', '--', key], MAX_DIFF + 1)
  }
  // `diff --no-index` exits 1 when the files differ; only a failure to run is an error.
  if (r.code === null && !r.cut) throw new ChangeError(500, 'git_failed', 'Could not read the changes.')
  let diff = r.out
  const binary = /^Binary files .* differ$/m.test(diff) || /^GIT binary patch$/m.test(diff)
  const truncated = r.cut || Buffer.byteLength(diff) > MAX_DIFF
  if (truncated) {
    diff = Buffer.from(diff).subarray(0, MAX_DIFF).toString('utf8')
    // Whole lines only.
    diff = diff.slice(0, diff.lastIndexOf('\n') + 1)
  }
  return { path: rel, diff, truncated, binary }
}
