// Which `codex …` command lines start Codex's interactive TUI, the only ones a shared terminal
// takes (`hn run-codex`, term/cli.ts); everything else runs plain codex. Kept free of the daemon's
// state, as `hn` loads it on every `codex` typed in a shell.
import { join } from 'node:path'
import { SUPPORT } from '../util.ts'

/** What `hn` and holders read to reach Codex's app-server, written by the daemon (codex/client.ts). */
export const CODEX_STATE = join(SUPPORT, 'codex-state.json')
export type CodexStateFile = { ok: boolean; socket: string | null; binary: string | null; version: string | null }

/** Codex's subcommands (codex --help, 0.163.0): never a shared session. */
const SUBCOMMANDS = new Set(['exec', 'e', 'review', 'login', 'logout', 'mcp', 'plugin', 'app-server', 'remote-control', 'app', 'completion', 'update', 'doctor', 'sandbox', 'debug', 'apply', 'a', 'agents', 'queue', 'archive', 'delete', 'migrate-rollouts', 'unarchive', 'cloud', 'exec-server', 'features', 'help'])
/** The TUI's options that take a value, and the ones that don't. Anything else: plain codex. */
const WITH_VALUE = new Set(['-m', '--model', '-s', '--sandbox', '-a', '--ask-for-approval', '-C', '--cd', '-c', '--config', '-p', '--profile', '--enable', '--disable', '--add-dir'])
const FLAGS = new Set(['--search', '--no-alt-screen', '--full-auto', '--last'])

/**
 * A TUI start: plain `codex`, `codex "prompt"`, `codex resume [id|--last]`, `codex fork …`, with
 * the TUI's usual options. Returns the thread it resumes (null for a new one or a picker), or
 * undefined for a command line that isn't one ("not for a shared terminal").
 */
export function codexLaunch(args: string[]): { resume: string | null } | undefined {
  const words = [...args]
  let resume: string | null = null
  if (words[0] === 'resume' || words[0] === 'fork') {
    const verb = words.shift()
    const id = words[0] && !words[0].startsWith('-') ? words.shift()! : null
    if (id && !/^[\w-]{8,80}$/.test(id)) return undefined
    if (verb === 'resume') resume = id
  }
  let prompt = false
  for (let i = 0; i < words.length; i++) {
    const a = words[i]!
    if (a.includes('=') && WITH_VALUE.has(a.slice(0, a.indexOf('=')))) continue
    if (WITH_VALUE.has(a)) {
      if (!words[i + 1] || words[i + 1]!.startsWith('-')) return undefined
      i++
      continue
    }
    if (FLAGS.has(a)) continue
    if (a.startsWith('-') || SUBCOMMANDS.has(a) || prompt) return undefined
    prompt = true
  }
  return { resume }
}
