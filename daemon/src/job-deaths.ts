// A `claude agents` job that died (docs/PROTOCOL.md, "Session menu", "A job that died"; IDEAS NT4).
// Claude Code says so itself: a job's state file (~/.claude/jobs/<job>/state.json) ends in "done"
// when it finished, "stopped" when the owner stopped it (`claude stop`, the phone's Stop, a signal),
// and "failed" when it died: a crash or an error exit ("exit 1 — …", "SIGKILL (137)"), its process
// gone mid-turn ("process gone while supervisor was down"), an API error that ended it, or Claude
// itself giving up. Its `detail` says why. A deleted job (`claude rm`) leaves the list. "crashed" is
// on the way to "failed" (Claude Code respawns it first), and a "failed" whose detail ends in
// "; respawning" may still come back: that one is said only if it's still dead a minute later.
import { join } from 'node:path'
import { statSync, readFileSync } from 'node:fs'
import { CLAUDE_DIR, SUPPORT, readJson, trimText, writeJson } from './util.ts'
import { maskSecrets } from './normalize.ts'

/** A job's end as its state file says it: whether it died, why, and whether Claude Code is bringing it back. */
export type JobEnd = { state: string; reason: string | null; respawning: boolean }

const RESPAWNING = /;\s*respawning\s*$/i

/** [detail] (or [needs]) as one short line for a notification: no "; respawning", secrets masked, ≤ 120 characters. */
export function deathReason(detail: unknown, needs?: unknown): string | null {
  const raw = typeof detail === 'string' && detail.trim() ? detail : typeof needs === 'string' ? needs : ''
  const line = raw.replace(RESPAWNING, '').split('\n').map((l) => l.trim()).find(Boolean) ?? ''
  if (!line || line === 'failed') return null
  return trimText(maskSecrets(line), 120)
}

// Each job's state file, read again only when it changes (a poll every 5 s, most never do).
const ends = new Map<string, { mtime: number; end: JobEnd | null }>()

/** Job [job]'s state file: its state and, for one that died, why. Null when it can't be read. */
export function jobEnd(job: string): JobEnd | null {
  const file = join(CLAUDE_DIR, 'jobs', job, 'state.json')
  let mtime: number
  try {
    mtime = statSync(file).mtimeMs
  } catch {
    ends.delete(job)
    return null
  }
  const known = ends.get(job)
  if (known && known.mtime === mtime) return known.end
  let end: JobEnd | null = null
  try {
    const o = JSON.parse(readFileSync(file, 'utf8'))
    if (typeof o?.state === 'string') {
      const failed = o.state === 'failed'
      end = { state: o.state, reason: failed ? deathReason(o.detail, o.needs) : null, respawning: failed && typeof o.detail === 'string' && RESPAWNING.test(o.detail) }
    }
  } catch {}
  ends.set(job, { mtime, end })
  return end
}

/** One job as a list read has it: its state ("working", "blocked", "done", "stopped", "failed") and, dead, why. */
export type JobSeen = { job: string; sessionId: string; state: string; reason: string | null; respawning: boolean }
export type JobDeath = { job: string; sessionId: string; reason: string | null; at: number }

/** How long a death Claude Code is respawning waits to be said: back at work by then, it wasn't one. */
export const RESPAWN_HOLD_MS = 60_000
/** A job the owner stopped, deleted or restarted from the phone in this long: whatever it ends as, it's his doing. */
export const OWNER_MS = 60_000

/**
 * Which jobs died since they were last seen alive. Each job's last state is kept on disk
 * (`<support>/job-states.json`), so a restart of the daemon neither says an old death again nor
 * misses one that happened while it was down; a job seen for the first time already dead (the
 * first run, or one older than the file) says nothing. A death is said once: it stays "failed"
 * until the job works again (a prompt, `claude respawn`), and only another death after that is new.
 */
export class DeathWatch {
  private last: Record<string, string>
  private pending = new Map<string, number>()
  private owner = new Map<string, number>()
  private file: string | null

  constructor(file: string | null) {
    this.file = file
    this.last = file ? readJson<Record<string, string>>(file, {}) : {}
    if (!this.last || typeof this.last !== 'object' || Array.isArray(this.last)) this.last = {}
  }

  /** The owner stops, deletes or restarts [job] now (the phone's menu): its end isn't a death. */
  ownerActed(job: string, at = Date.now()): void {
    this.owner.set(job, at)
  }

  /** One read of the whole list: the deaths to say now. Jobs not in it (deleted) are forgotten. */
  see(jobs: JobSeen[], now = Date.now()): JobDeath[] {
    const out: JobDeath[] = []
    const listed = new Set<string>()
    let changed = false
    const set = (job: string, state: string) => {
      if (this.last[job] === state) return
      this.last[job] = state
      changed = true
    }
    for (const j of jobs) {
      listed.add(j.job)
      const prev = this.last[j.job]
      if (j.state !== 'failed') {
        this.pending.delete(j.job)
        set(j.job, j.state)
        continue
      }
      // Dead when first seen, or already said: nothing new.
      if (prev === undefined || prev === 'failed') {
        this.pending.delete(j.job)
        set(j.job, 'failed')
        continue
      }
      const acted = this.owner.get(j.job)
      if (acted != null && now - acted < OWNER_MS) {
        this.pending.delete(j.job)
        set(j.job, 'failed')
        continue
      }
      // Claude Code is bringing it back: wait (its last state stays the living one meanwhile, so
      // a restart of the daemon waits again rather than losing it).
      if (j.respawning) {
        const since = this.pending.get(j.job) ?? now
        this.pending.set(j.job, since)
        if (now - since < RESPAWN_HOLD_MS) continue
      }
      this.pending.delete(j.job)
      set(j.job, 'failed')
      out.push({ job: j.job, sessionId: j.sessionId, reason: j.reason, at: now })
    }
    for (const job of Object.keys(this.last)) {
      if (listed.has(job)) continue
      delete this.last[job]
      this.pending.delete(job)
      changed = true
    }
    for (const [job, at] of this.owner) if (now - at >= OWNER_MS) this.owner.delete(job)
    if (changed && this.file) {
      try {
        writeJson(this.file, this.last)
      } catch {}
    }
    return out
  }
}

/** The daemon's own watch, kept in the support folder. */
export const deaths = new DeathWatch(join(SUPPORT, 'job-states.json'))
