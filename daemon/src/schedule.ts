// When automations are due (docs/PROTOCOL.md, "Automations"): pure time maths, no I/O and no
// clock of its own, so tests can stand anywhere in time. Times of day are the Mac's local time, in
// an IANA zone passed in rather than the process's own: a running Node keeps the zone it started
// with, and the owner travels, so the daemon reads the Mac's zone again on each tick.
import { readlinkSync } from 'node:fs'
import { cronDay, cronOf } from './cron.ts'

export const MIN_MS = 60_000
export const HOUR_MS = 3600_000
export const DAY_MS = 24 * HOUR_MS
/** A due time found this soon after it is on time; later, the Mac slept or the daemon was down. */
export const ON_TIME_MS = 90_000
/** A missed time is caught up only this late at most (and never later than its own period). */
export const CATCH_UP_MS = 6 * HOUR_MS
/** No catching up when the next time is this close: that one runs instead. */
export const NEXT_SOON_MS = 15 * MIN_MS

export type Hm = { h: number; m: number }
export type TimeTrigger =
  | { kind: 'time'; at: string; days: number[] }
  | { kind: 'once'; at: number }
  | { kind: 'every'; hours: number; from: string; to: string | null; days: number[] }
  | { kind: 'cron'; expr: string }
/** One time a trigger is due: when, and its key (`2026-10-16T09:00`, Mac time), which fires once. */
export type Occurrence = { at: number; key: string }

/** "HH:MM", 00:00–23:59. */
export function parseHm(s: unknown): Hm | null {
  const m = typeof s === 'string' ? /^([01]\d|2[0-3]):([0-5]\d)$/.exec(s) : null
  return m ? { h: Number(m[1]), m: Number(m[2]) } : null
}

const pad = (n: number) => String(n).padStart(2, '0')

// ── Local time in a zone ─────────────────────────────────────────────────
export type LocalParts = { y: number; mo: number; d: number; h: number; mi: number; dow: number }
const formats = new Map<string, Intl.DateTimeFormat>()
const WEEKDAYS: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }

function format(tz: string): Intl.DateTimeFormat {
  let f = formats.get(tz)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', weekday: 'short' })
    formats.set(tz, f)
  }
  return f
}

/** A zone name Intl knows, else UTC (a zone that can't be read must not stop automations). */
export function validZone(tz: string | null | undefined): string {
  if (!tz) return 'UTC'
  try {
    format(tz)
    return tz
  } catch {
    return 'UTC'
  }
}

/** The calendar date, time and ISO weekday (1 Mon – 7 Sun) at [t] in [tz]. */
export function localParts(t: number, tz: string): LocalParts {
  const out: LocalParts = { y: 0, mo: 0, d: 0, h: 0, mi: 0, dow: 0 }
  for (const p of format(tz).formatToParts(t)) {
    if (p.type === 'year') out.y = Number(p.value)
    else if (p.type === 'month') out.mo = Number(p.value)
    else if (p.type === 'day') out.d = Number(p.value)
    else if (p.type === 'hour') out.h = Number(p.value) % 24
    else if (p.type === 'minute') out.mi = Number(p.value)
    else if (p.type === 'weekday') out.dow = WEEKDAYS[p.value] ?? 0
  }
  return out
}

/** Local wall time as if it were UTC: comparable numbers for "earlier" and "later" on the wall. */
const wall = (p: Pick<LocalParts, 'y' | 'mo' | 'd' | 'h' | 'mi'>) => Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi)
const minute = (t: number) => Math.floor(t / MIN_MS) * MIN_MS
const offsetAt = (t: number, tz: string) => wall(localParts(t, tz)) - minute(t)

/**
 * The instant the wall clock in [tz] shows this date and time. A time daylight saving skips (02:30
 * on the spring change) gives the first minute after the gap; one it repeats, the first of the two.
 */
export function localTime(y: number, mo: number, d: number, h: number, mi: number, tz: string): number {
  const want = Date.UTC(y, mo - 1, d, h, mi)
  // The offsets around it: one, or two when a change is near.
  const offs = [...new Set([offsetAt(want - DAY_MS, tz), offsetAt(want, tz), offsetAt(want + DAY_MS, tz)])]
  const hits = offs.map((o) => want - o).filter((t) => wall(localParts(t, tz)) === want)
  if (hits.length) return Math.min(...hits)
  // In a gap: the first instant whose wall time is at or past the one asked for.
  let lo = want - Math.max(...offs)
  let hi = want - Math.min(...offs)
  if (lo > hi) [lo, hi] = [hi, lo]
  while (hi - lo > MIN_MS) {
    const mid = lo + Math.floor((hi - lo) / 2 / MIN_MS) * MIN_MS
    if (wall(localParts(mid, tz)) >= want) hi = mid
    else lo = mid
  }
  return hi
}

/** The calendar date [n] days after this one. */
function addDays(y: number, mo: number, d: number, n: number): { y: number; mo: number; d: number } {
  const t = new Date(Date.UTC(y, mo - 1, d + n))
  return { y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate() }
}
/** ISO weekday of a calendar date. */
const dowOf = (y: number, mo: number, d: number) => ((new Date(Date.UTC(y, mo - 1, d)).getUTCDay() + 6) % 7) + 1
const keyOf = (y: number, mo: number, d: number, h: number, mi: number) => `${y}-${pad(mo)}-${pad(d)}T${pad(h)}:${pad(mi)}`

/** "2026-10-16", the Mac's calendar day at [t]. */
export function localDay(t: number, tz: string): string {
  const p = localParts(t, tz)
  return `${p.y}-${pad(p.mo)}-${pad(p.d)}`
}

/** "9:00", the Mac's clock at [t]. */
export function clockText(t: number, tz: string): string {
  const p = localParts(t, tz)
  return `${p.h}:${pad(p.mi)}`
}

/** The time of day at [t] falls between [from] and [to] (to may be past midnight; both null: any time). */
export function inWindow(t: number, from: Hm | null, to: Hm | null, tz: string): boolean {
  if (!from || !to) return true
  const p = localParts(t, tz)
  const x = p.h * 60 + p.mi
  const a = from.h * 60 + from.m
  const b = to.h * 60 + to.m
  return a <= b ? x >= a && x < b : x >= a || x < b
}

// ── Occurrences ──────────────────────────────────────────────────────────
/** The times of day an `every` trigger runs, as minutes after midnight, and whether each is past midnight of its window. */
function everyGrid(t: { hours: number; from: string; to: string | null }): { min: number; nextDay: boolean }[] {
  const from = parseHm(t.from)
  if (!from || !(t.hours > 0) || 24 % t.hours) return []
  const step = t.hours * 60
  const start = from.h * 60 + from.m
  const out: { min: number; nextDay: boolean }[] = []
  if (t.to == null) {
    // Around the clock: the same grid each day (every allowed step divides 24 hours).
    for (let x = start % step; x < 24 * 60; x += step) out.push({ min: x, nextDay: false })
    return out
  }
  const to = parseHm(t.to)
  if (!to) return []
  // Up to and including `to`; a `to` earlier than `from` runs past midnight into the next day.
  const end = to.h * 60 + to.m + (to.h * 60 + to.m < start ? 24 * 60 : 0)
  for (let x = start; x <= end; x += step) out.push({ min: x % (24 * 60), nextDay: x >= 24 * 60 })
  return out
}

/**
 * A trigger's due times after [from] up to and including [to], oldest first. Days are looked at
 * from the day before (a zone ahead of UTC, a window past midnight) to the day after.
 */
export function occurrencesBetween(t: TimeTrigger, from: number, to: number, tz: string): Occurrence[] {
  if (t.kind === 'once') return t.at > from && t.at <= to ? [{ at: t.at, key: `once:${t.at}` }] : []
  if (to <= from) return []
  const cron = t.kind === 'cron' ? cronOf(t.expr) : null
  if (t.kind === 'cron' && !cron) return []
  const days = new Set(t.kind === 'cron' ? [] : t.days)
  const first = localParts(from, tz)
  const count = Math.min(400, Math.ceil((to - from) / DAY_MS) + 3)
  const out: Occurrence[] = []
  for (let i = -1; i < count; i++) {
    const day = addDays(first.y, first.mo, first.d, i)
    const dow = dowOf(day.y, day.mo, day.d)
    if (cron ? !cronDay(cron, day.mo, day.d, dow) : !days.has(dow)) continue
    const times: { h: number; m: number; on: { y: number; mo: number; d: number } }[] = []
    if (cron) {
      for (const h of cron.hours) for (const m of cron.minutes) times.push({ h, m, on: day })
    } else if (t.kind === 'time') {
      const at = parseHm(t.at)
      if (at) times.push({ ...at, on: day })
    } else if (t.kind === 'every') {
      for (const g of everyGrid(t)) times.push({ h: Math.floor(g.min / 60), m: g.min % 60, on: g.nextDay ? addDays(day.y, day.mo, day.d, 1) : day })
    }
    for (const x of times) {
      const at = localTime(x.on.y, x.on.mo, x.on.d, x.h, x.m, tz)
      if (at > from && at <= to) out.push({ at, key: keyOf(x.on.y, x.on.mo, x.on.d, x.h, x.m) })
    }
  }
  out.sort((a, b) => a.at - b.at)
  // A repeated key (a window past midnight overlapping the next day's) counts once.
  return out.filter((o, i) => i === 0 || o.key !== out[i - 1]!.key)
}

/** The first due time after [after], within the next 8 days (a week and a gap; a cron, a year), or null. */
export function nextOccurrence(t: TimeTrigger, after: number, tz: string): Occurrence | null {
  if (t.kind === 'once') return t.at > after ? { at: t.at, key: `once:${t.at}` } : null
  if (t.kind === 'cron') {
    // A month at a time: most crons are due within the first.
    for (let from = after; from < after + 400 * DAY_MS; from += 31 * DAY_MS) {
      const x = occurrencesBetween(t, from, from + 31 * DAY_MS, tz)[0]
      if (x) return x
    }
    return null
  }
  return occurrencesBetween(t, after, after + 8 * DAY_MS, tz)[0] ?? null
}

/** The next [n] due times after [after] (the editor's "next three"). */
export function nextOccurrences(t: TimeTrigger, after: number, tz: string, n = 3): number[] {
  const out: number[] = []
  let from = after
  while (out.length < n) {
    const x = nextOccurrence(t, from, tz)
    if (!x) break
    out.push(x.at)
    from = x.at
  }
  return out
}

/**
 * What to do about the due times after [since] up to [now]:
 * - `none`: nothing is due;
 * - `fire`: run [occ] now, [late] ms after it (0 on time), with [missed] older ones folded in;
 * - `missed`: too late (or the next one is too close): one missed run for [missed] due times.
 *
 * A time found within 90 s is on time. Otherwise (the Mac slept, the daemon was down) the latest,
 * L, still runs if it is at most 6 hours and at most the trigger's period ago, and the next time is
 * more than 15 minutes away; else the lot is recorded as missed.
 */
export type Due = { kind: 'none' } | { kind: 'fire'; occ: Occurrence; late: number; missed: number } | { kind: 'missed'; occ: Occurrence; missed: number }
export function dueBetween(t: TimeTrigger, since: number, now: number, tz: string, skipKey: string | null = null): Due {
  const occs = occurrencesBetween(t, since, now, tz).filter((o) => o.key !== skipKey)
  const last = occs[occs.length - 1]
  if (!last) return { kind: 'none' }
  const ago = now - last.at
  if (occs.length === 1 && ago <= ON_TIME_MS) return { kind: 'fire', occ: last, late: 0, missed: 0 }
  // Its period: every N hours, N hours (a window's last run isn't caught up into the evening);
  // otherwise the time to its next due time (a day, or more for chosen days; a once has none).
  const period = t.kind === 'every' ? t.hours * HOUR_MS : (nextOccurrence(t, last.at, tz)?.at ?? Infinity) - last.at
  const next = nextOccurrence(t, now, tz)
  if (ago <= Math.min(CATCH_UP_MS, period) && (!next || next.at - now > NEXT_SOON_MS)) return { kind: 'fire', occ: last, late: ago, missed: occs.length - 1 }
  return { kind: 'missed', occ: last, missed: occs.length }
}

// ── The Mac's zone ───────────────────────────────────────────────────────
/**
 * The Mac's time zone now, read from /etc/localtime (macOS points it at the zone's file, and moves
 * it when the zone changes), else what this process started with.
 */
export function systemZone(): string {
  try {
    const m = /zoneinfo\/(.+)$/.exec(readlinkSync('/etc/localtime'))
    if (m) return validZone(m[1])
  } catch {}
  return validZone(Intl.DateTimeFormat().resolvedOptions().timeZone)
}
