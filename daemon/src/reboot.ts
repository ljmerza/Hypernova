// A restart (or shutdown) of the Mac takes every shared terminal with it, so each conversation open
// in one ends at once, though nobody ended it. The daemon keeps the sessions it last saw open in
// <support>/open-sessions.json. Started again after the Mac booted, it marks the recent ones among
// them as cut short by the restart (`endedBy: "mac_restart"`), and the phone keeps those on Home,
// ready to resume, rather than in History (docs/PROTOCOL.md, "After a restart of the Mac").
import { existsSync, readFileSync } from 'node:fs'
import { inContainer } from './platform.ts'
import { uptime } from 'node:os'
import { join } from 'node:path'
import { SUPPORT, log, readJson, writeJson } from './util.ts'

/** A conversation cut short stays marked this long after the Mac came back, unless it runs again first. */
export const CUT_KEEP_MS = 48 * 3600_000
/** Only conversations active in the 48 hours before the restart count: older ones were done with. */
export const CUT_RECENT_MS = 48 * 3600_000
/** At most this many, the most recently active. */
export const CUT_MAX = 10
/**
 * A session that ended stays on the open list for a minute. Shutting down, macOS ends every claude
 * a few seconds before it ends the daemon: those ends mustn't read as the owner closing them.
 */
export const OPEN_GRACE_MS = 60_000

/**
 * `at`: when it was last written. `open`: the sessions open then (or ended under a minute before).
 * `cut`: the sessions a restart cut short, by id, with when the Mac came back.
 */
export type OpenFile = { at: number; open: string[]; cut: Record<string, number> }

const FILE = join(SUPPORT, 'open-sessions.json')
/** No record yet of what was open: the first start of a daemon that keeps one. */
const firstRun = !existsSync(FILE)
let saved: OpenFile = load()
/** When each session was last seen open, in this run. */
const seenOpen = new Map<string, number>()

function load(): OpenFile {
  const o = readJson<Partial<OpenFile> | null>(FILE, null)
  return { at: Number(o?.at) || 0, open: Array.isArray(o?.open) ? o.open.filter((x) => typeof x === 'string') : [], cut: o?.cut && typeof o.cut === 'object' ? { ...o.cut } : {} }
}

function save(): void {
  try {
    writeJson(FILE, saved)
  } catch (e) {
    log('open sessions not saved', e)
  }
}

/**
 * When a container's first process started (epoch ms), from /proc/1/stat's start time (clock ticks
 * after the HOST's boot, 100 a second on Linux): in a container `uptime()` is the host's, so a
 * container restarted by its policy would never look restarted. Null when it can't be read.
 */
export function containerStartedAt(hostBootMs: number, read: (f: string) => string = (f) => readFileSync(f, 'utf8')): number | null {
  try {
    const stat = read('/proc/1/stat')
    // The fields after the command's closing parenthesis; starttime is the 22nd field overall.
    const rest = stat.slice(stat.lastIndexOf(')') + 2).split(' ')
    const ticks = Number(rest[19])
    return Number.isFinite(ticks) && ticks > 0 ? Math.round(hostBootMs + (ticks / 100) * 1000) : null
  } catch {
    return null
  }
}

let bootAt: number | null = null
/** When the Mac booted (epoch ms). HYPERNOVA_BOOTED_AT stands in for a restart in tests and test daemons. */
export function bootedAt(): number {
  const host = Math.round(Date.now() - uptime() * 1000)
  bootAt ??= Number(process.env.HYPERNOVA_BOOTED_AT) || (inContainer() ? containerStartedAt(host) : null) || host
  return bootAt
}

/**
 * Something from before the Mac last booted ([startedAt], epoch ms): a holder's or a claude's pid
 * found alive now is some other process's. Ten seconds allow for the boot time's rounding.
 */
export const fromBeforeBoot = (startedAt: unknown) => typeof startedAt === 'number' && startedAt > 0 && startedAt < bootedAt() - 10_000

/**
 * The sessions a restart cut short, by id, with when the Mac came back ([boot]): those open when
 * the daemon last wrote [file], if the Mac has booted since, and active in the 48 hours before it
 * (not since), the 10 most recent. [lastActivity]: when each was last active, undefined for one it
 * doesn't know. Nothing when the Mac hasn't booted since: only the daemon restarted, and terminals
 * outlive it.
 */
export function cutShort(file: OpenFile, boot: number, lastActivity: (id: string) => number | undefined): Record<string, number> {
  if (file.at >= boot) return {}
  const recent: [string, number][] = []
  for (const id of new Set(file.open)) {
    const at = lastActivity(id)
    if (at !== undefined && at >= boot - CUT_RECENT_MS && at < boot) recent.push([id, at])
  }
  recent.sort((a, b) => b[1] - a[1])
  return Object.fromEntries(recent.slice(0, CUT_MAX).map(([id]) => [id, boot]))
}

/**
 * At start, once the sessions are known: mark the ones a restart of the Mac cut short, and keep
 * earlier marks that still hold (a daemon restart since must not lose them). [lastActivity] is
 * undefined for a session that isn't ended (or isn't known), which can't have been cut short.
 * [phoneSessions]: the sessions the phone started in shared terminals, for a first start with no
 * record of what was open. Nobody ends one of those but by quitting claude in its terminal, so
 * the ones last active in the 48 hours before the boot are taken as open when it came (as on
 * 15 October, when the restart that showed this ended six of them).
 */
export function startAfterBoot(lastActivity: (id: string) => number | undefined, phoneSessions: () => string[] = () => [], now = Date.now()): string[] {
  const boot = bootedAt()
  const fresh = cutShort(firstRun ? { at: 0, open: phoneSessions(), cut: {} } : saved, boot, lastActivity)
  const cut: Record<string, number> = {}
  for (const [id, at] of Object.entries({ ...saved.cut, ...fresh })) if (typeof at === 'number' && now < at + CUT_KEEP_MS && lastActivity(id) !== undefined) cut[id] = at
  // Booted since: nothing is open any more. Only the daemon restarted: what was open may still be
  // (its terminal outlived the daemon), and gets a minute to show up again before it leaves the list.
  const open = saved.at >= boot ? saved.open : []
  for (const id of open) seenOpen.set(id, now)
  saved = { at: now, open, cut }
  save()
  const ids = Object.keys(fresh)
  if (ids.length) log(`the Mac restarted: ${ids.length} conversation(s) it cut short stay on Home`, ids.map((id) => id.slice(0, 8)).join(' '))
  return ids
}

/**
 * The sessions open now, from each poll. One that ended stays on the list for a minute; the file
 * is rewritten when the list changes. Returns the sessions whose mark ran out (48 hours), whose
 * clients should hear of it.
 */
export function noteOpen(ids: Iterable<string>, now = Date.now()): string[] {
  let changed = false
  for (const id of ids) {
    seenOpen.set(id, now)
    // Running again (resumed): no longer one the restart cut short.
    if (id in saved.cut) {
      delete saved.cut[id]
      changed = true
    }
  }
  for (const [id, at] of seenOpen) if (now - at > OPEN_GRACE_MS) seenOpen.delete(id)
  const expired = Object.entries(saved.cut).filter(([, at]) => now >= at + CUT_KEEP_MS).map(([id]) => id)
  for (const id of expired) delete saved.cut[id]
  const open = [...seenOpen.keys()].sort()
  if (changed || expired.length || open.join() !== [...saved.open].sort().join()) {
    saved = { ...saved, at: now, open }
    save()
  }
  return expired
}

/** Session [id] runs again (a prompt resumed it): it's no longer one the restart cut short. */
export function runsAgain(id: string): void {
  if (!(id in saved.cut)) return
  delete saved.cut[id]
  save()
}

/** A restart of the Mac cut session [id] short, recently enough to keep it on Home. */
export function cutByRestart(id: string, now = Date.now()): boolean {
  const at = saved.cut[id]
  return typeof at === 'number' && now < at + CUT_KEEP_MS
}
