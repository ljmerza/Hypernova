// Hidden shared terminals: ones the daemon starts for its own use (the usage probe,
// usage-probe.ts), which no session list, History or `hn ls` shows.
//
// They're known by the folder they run in, which carries a marker file, rather than by a list
// the daemon keeps in memory: Claude Code's registry and its transcripts name only the folder,
// a daemon restarted mid-probe has forgotten its list, and a test daemon's probe shows up in
// every other daemon's registry poll too.
import { existsSync, lstatSync, mkdirSync, realpathSync, rmdirSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'

export const HIDDEN_MARK = '.hypernova-hidden'

/** Make [dir] (0700) with its marker, for a hidden terminal to run in. Returns [dir]. */
export function hiddenDir(dir: string): string {
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const mark = join(dir, HIDDEN_MARK)
  if (!existsSync(mark)) writeFileSync(mark, 'Hypernova runs Claude Code here for itself (the usage probe). Nothing here is yours.\n', { mode: 0o600 })
  return dir
}

/** A terminal or a Claude Code session working in [cwd] is one of the daemon's hidden ones. */
export function isHiddenCwd(cwd: string | null | undefined): boolean {
  if (!cwd) return false
  try {
    return existsSync(join(cwd, HIDDEN_MARK))
  } catch {
    return false
  }
}

/** Claude Code's folder name for a working folder: everything but letters and digits becomes "-". */
export const encodeProjectDir = (cwd: string): string => cwd.replace(/[^a-zA-Z0-9]/g, '-')
const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** The folders under [configDir]/projects a transcript of a session run in [cwd] can be in (the path as given, and its real path). */
export function transcriptDirs(configDir: string, cwd: string): string[] {
  const names = new Set([encodeProjectDir(cwd)])
  try {
    names.add(encodeProjectDir(realpathSync(cwd)))
  } catch {}
  return [...names].map((n) => join(configDir, 'projects', n))
}

/**
 * Delete the one transcript Claude Code wrote for the hidden session [sessionId] run in [cwd]:
 * <configDir>/projects/<encoded cwd>/<sessionId>.jsonl, nothing else. Refuses (false) unless the
 * resolved path is exactly that file inside that folder, and is a plain file. [dirExisted]: the
 * project folder was already there before the probe ran; if it wasn't, it goes too when empty.
 * Returns whether a file was deleted. Never throws.
 */
export function deleteHiddenTranscript(configDir: string, cwd: string, sessionId: string, dirExisted: Record<string, boolean> = {}): boolean {
  let deleted = false
  if (!SESSION_ID.test(sessionId)) return false
  for (const dir of transcriptDirs(configDir, cwd)) {
    try {
      const file = resolve(dir, `${sessionId}.jsonl`)
      if (dirname(file) !== resolve(dir) || !file.startsWith(resolve(configDir, 'projects') + sep)) continue
      const st = lstatSync(file, { throwIfNoEntry: false })
      if (st?.isFile()) {
        unlinkSync(file)
        deleted = true
      }
      // The folder the probe made, and nothing is left in it.
      if (!dirExisted[dir]) {
        try {
          rmdirSync(dir)
        } catch {}
      }
    } catch {}
  }
  return deleted
}
