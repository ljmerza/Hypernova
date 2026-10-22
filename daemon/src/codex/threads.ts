// The Codex threads Hypernova knows, kept in <support>/codex-threads.json. A thread's id is its
// session's id on the phone. The conversation itself is Codex's own; this keeps what Hypernova
// needs before Codex is reached (its folder, title, settings) and whether it can be resumed.
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson } from '../util.ts'

/** Codex's own sandboxes Hypernova offers; asking happens "on request" in both. */
export const CODEX_MODES = ['read-only', 'workspace-write'] as const
export type CodexMode = (typeof CODEX_MODES)[number]
export const isCodexMode = (m: unknown): m is CodexMode => typeof m === 'string' && (CODEX_MODES as readonly string[]).includes(m)

export type CodexThread = {
  id: string
  cwd: string
  title: string
  model: string | null
  effort: string | null
  /** Its sandbox: one Hypernova offers, or a terminal's own as Codex reported it ("danger-full-access"). */
  mode: CodexMode | string
  /**
   * Hypernova decides its sandbox and asking (one it started, or a terminal's whose mode the owner
   * changed in Hypernova): every turn Hypernova starts there says so again, whatever a terminal set.
   */
  chosen?: boolean
  createdAt: number
  lastActivityAt: number
  /** Started from Hypernova (the phone, an automation, a switch) or in a terminal (`codex` in a shell). */
  origin: 'hypernova' | 'terminal'
  /**
   * Codex has written it down (a first turn, or context put in by a switch): only then can it be
   * resumed (docs/specs/codex-app-server.md, 1.2). A thread that never was can't come back.
   */
  started: boolean
  /** An automation's run started it (automations.ts). */
  automation?: { id: string; name: string; runId: string }
  /** "never": an automation's unattended run, which asks nobody. Unset: "on-request". */
  approvalPolicy?: 'on-request' | 'never'
}

const FILE = join(SUPPORT, 'codex-threads.json')
let threads: CodexThread[] = readJson<CodexThread[]>(FILE, [])
if (!Array.isArray(threads)) threads = []
const save = () => writeJson(FILE, threads)

export const listThreads = () => threads
export const getThread = (id: string) => threads.find((t) => t.id === id)
export const isCodex = (id: string) => threads.some((t) => t.id === id)
export function saveThread(t: CodexThread): CodexThread {
  threads = threads.filter((x) => x.id !== t.id).concat(t)
  save()
  return t
}
/** Change a thread's record in place; nothing when there's none. */
export function patchThread(id: string, patch: Partial<CodexThread>): CodexThread | null {
  const t = getThread(id)
  if (!t) return null
  Object.assign(t, patch)
  save()
  return t
}
