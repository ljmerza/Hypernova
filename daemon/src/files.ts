// The phone's files (docs/PROTOCOL.md, "Files"): which folders it may browse, the listing, a file
// to view or download, and why not when it can't, in codes it can act on.
//
// What may be browsed: the projects folder; and, when the owner turns on "Browse outside projects"
// (Settings, `browseOutsideProjects`), each session's own folder in the home folder (the home folder
// itself too), read-only. Never a folder outside the home folder (other than the projects folder),
// never the home folder's key places (secretfiles.ts: keys, keychains, mail, browsers' profiles, the
// daemon's state and Claude Code's), and never a file with keys wherever it is. Links are followed
// (realpath) before any of this is decided.
//
// The new-session sheet's folder picker sees less and further ([listFolders]): only folders' names,
// anywhere while that setting is on, so a session can start in any folder the owner picks.
import { constants, existsSync, mkdirSync, readdirSync, realpathSync, statSync } from 'node:fs'
import { open, readdir, stat } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, resolve, sep } from 'node:path'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { CLAUDE_DIR, HOME, SUPPORT, log } from './util.ts'
import { settings, saveSettings } from './store.ts'
import { broadcast, listSessionRecs, view } from './hub.ts'
import { isSecretFile, keyPlacesIn } from './secretfiles.ts'
import { ATTACH_DIR, MAX_ATTACHMENT, attachmentFile } from './attachments.ts'
import { mimeByName, needsSniff, sniff } from './mime.ts'
import { ON_MAC } from './platform.ts'

/** What went wrong, for the phone: an HTTP status, a code from docs/PROTOCOL.md "Files", words, and more fields. */
export class FileError extends Error {
  status: number
  code: string
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}

/** The viewer's limit (the phone loads it whole), and a download's (streamed to a file). */
export const VIEW_MAX = 20 * 1024 * 1024
export const DOWNLOAD_MAX = 500 * 1024 * 1024
/** A listing stops here (folders first): a 50,000-file folder would hold the daemon up. */
export const MAX_ENTRIES = 2000

/**
 * The real path of [p]; for one that doesn't exist (yet, or any more), its nearest existing
 * folder's real path with the rest as written (/var is /private/var on a Mac, gone or not). The
 * native realpath: on a Mac's disk, which ignores case, it gives each name as the disk spells it
 * (~/LIBRARY/keychains is ~/Library/Keychains), so a path can't dodge a check by its case.
 */
function realOr(p: string): string {
  let full = resolve(p)
  let rest = ''
  for (let i = 0; i < 64; i++) {
    try {
      return join(realpathSync.native(full), rest)
    } catch {
      const up = dirname(full)
      if (up === full) break
      rest = rest ? join(basename(full), rest) : basename(full)
      full = up
    }
  }
  return resolve(p)
}
const within = (p: string, root: string) => p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)

/**
 * A folder too broad to browse from the phone as the projects folder, or to serve as a preview's:
 * the home folder or one that holds it ("/", "/Users"), or one holding the daemon's state or
 * Claude Code's (~/Library, ~/.claude). [real] is a real path. A folder inside the state folders is
 * no root either (only the attachments' own).
 */
export function tooBroad(real: string): boolean {
  if (within(realCached(HOME), real)) return true
  for (const x of [SUPPORT, CLAUDE_DIR]) {
    const r = realCached(x)
    if (within(r, real) || within(real, r)) return true
  }
  return false
}

/**
 * Places macOS asks the owner about before the service's node may look inside (Files and Folders,
 * iCloud Drive, other disks), and where a network disk that went away can hang a look: nothing in
 * them is touched unless the phone asks for it, so a session there never brings up a dialog on the
 * Mac by itself.
 */
const GUARDED = ON_MAC ? [...['Desktop', 'Documents', 'Downloads', 'Pictures', 'Movies', 'Music', 'Library/Mobile Documents', 'Library/CloudStorage'].map((d) => join(HOME, d)), '/Volumes'] : []
export const macGuarded = (p: string) => GUARDED.some((g) => within(resolve(p), g))

/** Real paths of roots, kept a moment: every path asked about checks every session's folder. */
const realCache = new Map<string, { at: number; v: string }>()
function realCached(p: string): string {
  const now = Date.now()
  const c = realCache.get(p)
  if (c && now - c.at < 10_000) return c.v
  const v = macGuarded(p) ? resolve(p) : realOr(p)
  if (realCache.size > 2000) realCache.clear()
  realCache.set(p, { at: now, v })
  return v
}

/** Settings → Browse outside projects: sessions' folders in the home folder open to the phone. Off unless the owner turns it on. */
export const browseOutside = (): boolean => settings.browseOutsideProjects === true

let keyCache: { at: number; v: string[] } | null = null
/**
 * The home folder's key places (secretfiles.ts), the daemon's state and Claude Code's: as written
 * and as real paths (a link may move one, and the home folder may be a link itself), in lower case,
 * since a Mac's disk ignores case. Kept a moment.
 */
function keyPlaces(): string[] {
  const now = Date.now()
  if (keyCache && now - keyCache.at < 10_000) return keyCache.v
  const all = new Set<string>()
  const homes = new Set([resolve(HOME), realCached(HOME)])
  for (const home of homes) {
    for (const p of [...keyPlacesIn(home), SUPPORT, CLAUDE_DIR]) {
      all.add(resolve(p).toLowerCase())
      all.add(realCached(p).toLowerCase())
    }
  }
  keyCache = { at: now, v: [...all] }
  return keyCache.v
}

/** Whether [real] (a real path) is one of the key places or inside one (their case ignored). */
export function keyPlace(real: string): boolean {
  const r = real.toLowerCase()
  return keyPlaces().some((k) => within(r, k))
}

/** The projects folder as a real path, when it's one the phone may browse. */
function projectsReal(): string | null {
  const p = settings.projectsRoot
  if (!p) return null
  const real = realCached(p)
  return tooBroad(real) ? null : real
}

/**
 * Where a folder stands for the phone ([real], a real path): inside the projects folder (always
 * browsable); in the home folder, the home folder itself too (a session's folder there is browsable
 * while "Browse outside projects" is on); or anywhere else, a key place among them (never).
 */
export function reach(real: string): 'projects' | 'home' | 'none' {
  if (keyPlace(real)) return 'none'
  const projects = projectsReal()
  if (projects && within(real, projects)) return 'projects'
  // A folder macOS guards is taken as written (never looked at), so the home folder as written counts too.
  return within(real, realCached(HOME)) || within(real, resolve(HOME)) ? 'home' : 'none'
}

/**
 * The folders the phone may browse, as real paths: the projects folder, and while "Browse outside
 * projects" is on ([outside]), each session's own folder in the home folder.
 */
export function roots(outside = browseOutside()): string[] {
  const r = new Set<string>()
  const projects = projectsReal()
  if (projects) r.add(projects)
  if (outside) {
    for (const s of listSessionRecs()) {
      if (!s.cwd) continue
      const real = realCached(s.cwd)
      if (reach(real) === 'home') r.add(real)
    }
  }
  return [...r]
}

/**
 * What the phone may do with [p]: its real path, or why not: `locked_folder` (a key place, or
 * inside one), `outside_projects` (outside every root; [setting] true when turning "Browse outside
 * projects" on would open it). A path that doesn't exist yet is taken as written.
 */
export function checkPath(p: string): { ok: true; full: string } | { ok: false; code: 'outside_projects' | 'locked_folder'; setting: boolean } {
  if (!p) return { ok: false, code: 'outside_projects', setting: false }
  const full = realOr(p)
  if (keyPlace(full)) return { ok: false, code: 'locked_folder', setting: false }
  for (const root of roots()) if (within(full, root)) return { ok: true, full }
  return { ok: false, code: 'outside_projects', setting: !browseOutside() && roots(true).some((root) => within(full, root)) }
}

/** The real path of [p] when the phone may open it ([checkPath]), else null. */
export function allowedPath(p: string): string | null {
  const c = checkPath(p)
  return c.ok ? c.full : null
}

/**
 * Where sessions work, for the daemon's own uses: automations' folders (automations.ts; an owner
 * decision of its own, LATER L73). The projects folder and each session's own folder that isn't too broad, as before the browsing
 * setting, whatever it says; never a key place. The phone's browsing goes by [allowedPath].
 */
export function workPath(p: string): string | null {
  if (!p) return null
  const full = realOr(p)
  if (keyPlace(full)) return null
  const projects = projectsReal()
  if (projects && within(full, projects)) return full
  for (const s of listSessionRecs()) {
    if (!s.cwd) continue
    const real = realCached(s.cwd)
    if (!tooBroad(real) && within(full, real)) return full
  }
  return null
}

/** What a session's folder is to the phone: [browsable] now, and [outsideProjects] (in the home folder, where the setting opens it). */
export type FolderView = { browsable: boolean; outsideProjects: boolean }
const NOT_SHOWN: FolderView = { browsable: false, outsideProjects: false }
const browsed = new Map<string, { at: number; v: FolderView }>()
/**
 * Whether the phone can browse a session's folder ([cwd]): an existing folder inside the projects
 * folder, or in the home folder while "Browse outside projects" is on ([reach]). Kept 10 s (every
 * session message asks), and forgotten when the setting changes. One in a place macOS guards isn't
 * looked at (that would ask the owner on the Mac): it counts as there, and the listing says
 * `mac_privacy` if macOS keeps the service out.
 */
export function folderView(cwd: string): FolderView {
  const now = Date.now()
  const c = browsed.get(cwd)
  if (c && now - c.at < 10_000) return c.v
  let v = NOT_SHOWN
  try {
    let real: string | null = null
    if (macGuarded(cwd)) real = resolve(cwd)
    else {
      const r = realpathSync.native(cwd)
      if (statSync(r).isDirectory()) real = r
    }
    if (real) {
      const where = reach(real)
      v = { browsable: where === 'projects' || (where === 'home' && browseOutside()), outsideProjects: where === 'home' }
    }
  } catch {}
  if (browsed.size > 2000) browsed.clear()
  browsed.set(cwd, { at: now, v })
  return v
}
export const browsable = (cwd: string): boolean => folderView(cwd).browsable

/**
 * Turns "Browse outside projects" on or off: saved, and said at once to every phone and the Mac
 * (`{t:"files"}`), with each session whose `browsable` it changes.
 */
export function setBrowseOutside(on: boolean): boolean {
  if (browseOutside() === on) return on
  const before = new Map(listSessionRecs().map((r) => [r.id, folderView(r.cwd).browsable]))
  settings.browseOutsideProjects = on
  saveSettings()
  browsed.clear()
  log('browse outside projects:', on ? 'on' : 'off')
  broadcast({ t: 'files', files: filesState() })
  for (const r of listSessionRecs()) if (folderView(r.cwd).browsable !== before.get(r.id)) broadcast({ t: 'session', session: view(r) })
  return on
}

/** The files settings, as the snapshot (`mac.files`), `GET /v1/mac/files` and the Mac's status say them. */
export const filesState = () => ({ browseOutsideProjects: browseOutside() })

const outside = (setting = false) =>
  new FileError(403, 'outside_projects', 'That’s outside the folders the phone can open: your projects folder, and with Browse outside projects on, your sessions’ own folders.', {
    projectsRoot: settings.projectsRoot,
    ...(setting ? { setting: 'browseOutsideProjects' } : {}),
  })
const lockedFolder = () => new FileError(403, 'locked_folder', 'Keys, passwords and private data live there, so it stays on the Mac.')

/** [checkPath], or the error that says why not. */
function checked(p: string): string {
  const c = checkPath(p)
  if (c.ok) return c.full
  throw c.code === 'locked_folder' ? lockedFolder() : outside(c.setting)
}

/** A file system error as the phone's code: gone, not a folder, blocked by macOS' privacy, unreadable. */
function fsError(e: any, what: 'folder' | 'file'): FileError {
  switch (e?.code) {
    case 'ENOENT':
    case 'ELOOP': // a link put where a checked file was
      return new FileError(404, 'not_found', what === 'folder' ? 'That folder isn’t on the Mac any more.' : 'That file isn’t on the Mac any more.')
    case 'ENOTDIR':
      return new FileError(400, 'not_a_dir', 'That’s a file, not a folder.')
    case 'EISDIR':
      return new FileError(400, 'is_dir', 'That’s a folder, not a file.')
    case 'EPERM':
      // A LaunchAgent's node under macOS' Files and Folders protection (Documents, Desktop, iCloud Drive, an external disk).
      if (ON_MAC) return new FileError(403, 'mac_privacy', `macOS doesn’t let Hypernova open this ${what}. Allow it on the Mac: System Settings → Privacy & Security → Files and Folders (or Full Disk Access) → node.`)
      return new FileError(403, 'unreadable', `Hypernova isn’t allowed to open this ${what} on this computer.`)
    case 'EACCES':
      return new FileError(403, 'unreadable', `Hypernova isn’t allowed to open this ${what} on this computer.`)
    default:
      return new FileError(500, 'error', `Couldn’t open that ${what}.`)
  }
}

/**
 * Whether a session can start in folder [full]: null when it opens, else why not. macOS asks the
 * owner on the Mac before a LaunchAgent's node may open Documents, Desktop, iCloud Drive or an
 * external disk; a look that doesn't come back within 3 s is that question, unanswered.
 */
export async function folderOpens(full: string, ms = 3000): Promise<{ code: string; message: string } | null> {
  let timer: NodeJS.Timeout | undefined
  const late = new Promise<'late'>((r) => {
    timer = setTimeout(() => r('late'), ms)
  })
  try {
    const got = await Promise.race([readdir(full).then(() => null), late])
    if (got === 'late') return { code: 'mac_privacy', message: 'The Mac is asking whether Hypernova may open that folder. Allow it on the Mac, then try again.' }
    return null
  } catch (e: any) {
    if (e?.code === 'ENOENT' || e?.code === 'ENOTDIR') return null // said as before: the session's own start fails
    const f = fsError(e, 'folder')
    return { code: f.code, message: f.message }
  } finally {
    clearTimeout(timer)
  }
}

export type FileEntry = {
  name: string
  path: string
  kind: 'dir' | 'file'
  size: number
  mtime: number
  mime: string | null
  /**
   * It's listed but can't be opened: a file with keys (`secret`), a link that leads outside the
   * folders the phone may open (`outside`), or a key place such as ~/.ssh or ~/Library/Keychains, or
   * a folder of keys anywhere (`folder`).
   */
  locked?: 'secret' | 'outside' | 'folder'
}
export type Listing = { path: string; entries: FileEntry[]; truncated: boolean; total: number }

const collator = new Intl.Collator(undefined, { numeric: false })

/** The first bytes of a file with no extension: text, or a binary to download. */
async function sniffFile(path: string): Promise<string | null> {
  let fh: Awaited<ReturnType<typeof open>> | null = null
  try {
    fh = await open(path, 'r')
    const buf = Buffer.alloc(8192)
    const { bytesRead } = await fh.read(buf, 0, buf.length, 0)
    return sniff(buf.subarray(0, bytesRead))
  } catch {
    return null
  } finally {
    await fh?.close().catch(() => {})
  }
}

/**
 * A folder's entries, folders first, at most [MAX_ENTRIES] (`truncated`, `total` say how many
 * there are). Read without holding the daemon up: the names at once off the main thread, each
 * entry's details in batches.
 */
export async function listDir(p: string): Promise<Listing> {
  const full = checked(p)
  // Inside .ssh, .aws, .gnupg and the like: even the names stay on the Mac.
  if (isSecretFile(join(full, '_'))) throw new FileError(403, 'secret_file', 'Folders with keys stay on the Mac.')
  let dirents
  try {
    dirents = await readdir(full, { withFileTypes: true })
  } catch (e) {
    throw fsError(e, 'folder')
  }
  const all = dirents.filter((d) => d.name !== '.DS_Store')
  // Folders first by what the name says (a link is looked at below), then by name.
  all.sort((a, b) => (a.isDirectory() === b.isDirectory() ? collator.compare(a.name, b.name) : a.isDirectory() ? -1 : 1))
  const shown = all.slice(0, MAX_ENTRIES)
  const entries: FileEntry[] = []
  for (let i = 0; i < shown.length; i += 64) {
    entries.push(...(await Promise.all(shown.slice(i, i + 64).map((d) => entryOf(full, d)))))
  }
  // A link to a folder is a folder: in its place among them.
  entries.sort((a, b) => (a.kind === b.kind ? collator.compare(a.name, b.name) : a.kind === 'dir' ? -1 : 1))
  return { path: full, entries, truncated: all.length > shown.length, total: all.length }
}

async function entryOf(dir: string, d: import('node:fs').Dirent): Promise<FileEntry> {
  const path = join(dir, d.name)
  let size = 0
  let mtime = 0
  let isDir = d.isDirectory()
  try {
    const s = await stat(path)
    size = s.size
    mtime = s.mtimeMs
    isDir = s.isDirectory()
  } catch {}
  const e: FileEntry = { name: d.name, path, kind: isDir ? 'dir' : 'file', size, mtime, mime: null }
  if (!isDir) e.mime = mimeByName(path) ?? (needsSniff(path) && size > 0 ? await sniffFile(path) : null)
  // A link is judged by where it leads; anything else by where it is.
  const c = d.isSymbolicLink() ? checkPath(path) : null
  const real = c?.ok ? c.full : path
  const keys = (x: string) => isSecretFile(isDir ? join(x, '_') : x)
  if (c && !c.ok && c.code === 'outside_projects') e.locked = 'outside'
  else if ((c && !c.ok) || keyPlace(real) || keys(path) || keys(real)) e.locked = isDir ? 'folder' : 'secret'
  return e
}

/** A folder in the new-session sheet's folder picker: its name, and its path in the folder listed. */
export type FolderEntry = { name: string; path: string }
/** A folder's own folders ([listFolders]): [parent] null at `/`; [home] says where `~` is, as a real path. */
export type Folders = { path: string; parent: string | null; home: string; folders: FolderEntry[]; truncated: boolean; total: number }

/**
 * The folders directly in [p], for the new-session sheet's folder picker (docs/PROTOCOL.md,
 * "Folders"): names only, never a file, by name. [p]: a full path, `~` or `~/…` (none: the home
 * folder). In the projects folder always; anywhere else while "Browse outside projects" is on (owner,
 * 2026-10-29: "choose a folder anywhere"). Never a key place or a folder of keys, asked for or as a
 * row. Dot-folders only with [hidden]. At most [MAX_ENTRIES].
 */
export async function listFolders(p: string | null, hidden = false): Promise<Folders> {
  const asked = !p || p === '~' ? HOME : p.startsWith('~/') ? join(HOME, p.slice(2)) : p
  if (!isAbsolute(asked)) throw new FileError(400, 'bad_path', 'Type a full path, starting with / or ~.')
  let full: string
  try {
    full = realpathSync.native(asked)
    if (!statSync(full).isDirectory()) throw new FileError(400, 'not_a_dir', 'That’s a file, not a folder.')
  } catch (e) {
    if (e instanceof FileError) throw e
    if ((e as any)?.code === 'ENOENT') throw new FileError(404, 'not_found', 'There’s no folder there.')
    throw fsError(e, 'folder')
  }
  if (keyPlace(full) || isSecretFile(join(full, '_'))) throw lockedFolder()
  if (reach(full) !== 'projects' && !browseOutside()) {
    throw new FileError(403, 'outside_projects', 'Folders outside your projects folder show once Browse outside projects is on in Settings.', {
      projectsRoot: settings.projectsRoot,
      setting: 'browseOutsideProjects',
    })
  }
  let dirents
  try {
    dirents = await readdir(full, { withFileTypes: true })
  } catch (e) {
    throw fsError(e, 'folder')
  }
  const named = dirents.filter((d) => (hidden || !d.name.startsWith('.')) && (d.isDirectory() || d.isSymbolicLink()))
  const kept: FolderEntry[] = []
  for (let i = 0; i < named.length; i += 64) {
    const batch = await Promise.all(
      named.slice(i, i + 64).map(async (d) => {
        const path = join(full, d.name)
        // A link is a folder when it leads to one, and judged by where it leads too.
        let real = path
        if (d.isSymbolicLink()) {
          try {
            if (!(await stat(path)).isDirectory()) return null
          } catch {
            return null
          }
          real = realOr(path)
        }
        const keys = (x: string) => keyPlace(x) || isSecretFile(join(x, '_'))
        return keys(path) || keys(real) ? null : { name: d.name, path }
      }),
    )
    for (const f of batch) if (f) kept.push(f)
  }
  kept.sort((a, b) => collator.compare(a.name, b.name))
  return { path: full, parent: full === sep ? null : dirname(full), home: realCached(HOME), folders: kept.slice(0, MAX_ENTRIES), truncated: kept.length > MAX_ENTRIES, total: kept.length }
}

/**
 * The file the phone may read at [p] (a project's, or an attachment of its own), checked: its
 * real path, size, type and whether it's an attachment. Throws a [FileError].
 */
export async function fileFor(p: string, max: number | null): Promise<{ full: string; size: number; mtime: number; mime: string; attachment: boolean }> {
  const attached = p ? attachmentFile(p) : null
  if (!attached && p && resolve(p).startsWith(ATTACH_DIR + sep)) throw new FileError(404, 'attachment_gone', 'This attachment is no longer on the Mac (they’re kept for 30 days).')
  const full = attached ?? checked(p)
  let s
  try {
    s = await stat(full)
  } catch (e) {
    throw fsError(e, 'file')
  }
  if (s.isDirectory()) throw new FileError(400, 'is_dir', 'That’s a folder, not a file.')
  if (!attached && isSecretFile(full)) throw new FileError(403, 'secret_file', 'Files with keys and passwords stay on the Mac.')
  const limit = max ?? (attached ? MAX_ATTACHMENT : VIEW_MAX)
  if (s.size > limit) throw new FileError(413, 'too_large', `That file is ${mb(s.size)}; the phone ${max === DOWNLOAD_MAX ? 'downloads' : 'opens'} files up to ${mb(limit)}.`, { size: s.size, max: limit })
  const mime = mimeByName(full) ?? (needsSniff(full) ? ((await sniffFile(full)) ?? 'application/octet-stream') : 'application/octet-stream')
  return { full, size: s.size, mtime: s.mtimeMs, mime, attachment: !!attached }
}

const mb = (n: number) => (n >= 1024 * 1024 ? `${Math.round(n / (1024 * 1024))} MB` : `${Math.max(1, Math.round(n / 1024))} KB`)

/**
 * `attachment; filename="…"; filename*=UTF-8''…` (RFC 6266 and 5987): the plain name for old
 * clients, with anything but printable ASCII as "_", and the whole name in UTF-8.
 */
export function contentDisposition(name: string, kind: 'attachment' | 'inline' = 'attachment'): string {
  const ascii = name.replace(/[^\x20-\x7e]|["\\]/g, '_')
  const star = encodeURIComponent(name).replace(/['()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`)
  return `${kind}; filename="${ascii}"; filename*=UTF-8''${star}`
}

/** A single `bytes=` range of a file [size] long, or null when there's none, 'bad' when it can't be met. */
export function parseByteRange(h: string | undefined, size: number): { start: number; end: number } | null | 'bad' {
  if (!h) return null
  const m = /^bytes=(\d*)-(\d*)$/.exec(h.trim())
  // Several ranges, or another unit: the whole file (allowed by RFC 9110).
  if (!m) return null
  const [, a, b] = m
  if (!a && !b) return 'bad'
  let start: number
  let end: number
  if (!a) {
    // The last n bytes.
    const n = Number(b)
    if (!n) return 'bad'
    start = Math.max(0, size - n)
    end = size - 1
  } else {
    start = Number(a)
    end = b ? Math.min(Number(b), size - 1) : size - 1
  }
  if (start >= size || end < start) return 'bad'
  return { start, end }
}

/**
 * Stream a checked file ([fileFor]) to the phone: the whole of it, or the range it asks for (a
 * download carrying on after a dropped connection). [download]: saved under its own name, not
 * shown. A file removed or unreadable mid-way ends the response rather than leaving it hanging.
 */
export async function sendChecked(req: IncomingMessage, res: ServerResponse, f: { full: string; size: number; mtime: number; mime: string; attachment?: boolean }, download: boolean, name = basename(f.full)): Promise<void> {
  // Opened before the answer starts: a file that can't be read gets its code, not a cut-off body.
  // A file the phone browsed to was checked at its real path, so it isn't a link: one put there
  // since the check (pointing at a key) is refused, not followed.
  let fh: Awaited<ReturnType<typeof open>>
  try {
    fh = await open(f.full, f.attachment === false ? constants.O_RDONLY | constants.O_NOFOLLOW : 'r')
  } catch (e) {
    throw fsError(e, 'file')
  }
  const etag = `"${f.size.toString(16)}-${Math.floor(f.mtime).toString(16)}"`
  const headers: Record<string, string | number> = {
    'content-type': f.mime,
    'cache-control': 'no-store',
    'accept-ranges': 'bytes',
    etag,
    'last-modified': new Date(f.mtime).toUTCString(),
    'content-disposition': contentDisposition(name, download ? 'attachment' : 'inline'),
  }
  // A resume only from the same file: one changed since gets the whole of it again.
  const ifRange = req.headers['if-range']
  const range = ifRange && ifRange !== etag && ifRange !== headers['last-modified'] ? null : parseByteRange(req.headers.range, f.size)
  if (range === 'bad') {
    await fh.close()
    res.writeHead(416, { 'content-range': `bytes */${f.size}`, 'cache-control': 'no-store' }).end()
    return
  }
  const start = range?.start ?? 0
  const end = range?.end ?? f.size - 1
  const length = f.size === 0 ? 0 : end - start + 1
  if (range) headers['content-range'] = `bytes ${start}-${end}/${f.size}`
  headers['content-length'] = length
  res.writeHead(range ? 206 : 200, headers)
  if (req.method === 'HEAD' || length === 0) {
    await fh.close()
    res.end()
    return
  }
  const stream = fh.createReadStream({ start, end, autoClose: true })
  stream.on('error', (e) => {
    log('file send failed', f.full, (e as Error).message)
    res.destroy()
  })
  res.on('close', () => stream.destroy())
  stream.pipe(res)
}

/**
 * A folder for new projects on first run: the projects folder as set when it exists; else (it's
 * the default ~/Projects, never made) the usual places where people keep code, the one with the
 * most git repositories in it; else ~/Projects, made now. Never a folder macOS guards (Documents,
 * Desktop): looking there would ask the owner for access out of the blue.
 */
export function ensureProjectsRoot(): string {
  const current = settings.projectsRoot
  if (current && (macGuarded(current) || existsSync(current))) return current
  const fallback = join(HOME, 'Projects')
  if (current && current !== fallback) return current
  const names = ['Developer', 'Programming', 'Projects', 'code', 'Code', 'src', 'dev', 'repos', 'GitHub', 'git', 'workspace', ...(ON_MAC ? [] : ['Documents/GitHub'])]
  let best: { path: string; repos: number } | null = null
  for (const n of names) {
    const path = join(HOME, n)
    let repos = 0
    try {
      if (!statSync(path).isDirectory()) continue
      for (const d of readdirSync(path, { withFileTypes: true })) if (d.isDirectory() && existsSync(join(path, d.name, '.git'))) repos++
    } catch {
      continue
    }
    if (repos > (best?.repos ?? 0)) best = { path, repos }
  }
  const pick = best?.path ?? fallback
  if (!best) {
    try {
      mkdirSync(fallback, { recursive: true })
    } catch {}
  }
  if (pick !== current) {
    settings.projectsRoot = pick
    saveSettings()
  }
  log('projects folder:', pick, best ? `(${best.repos} repositories)` : '(made)')
  return pick
}
