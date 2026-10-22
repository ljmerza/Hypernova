// How fast the 5-hour and weekly windows are being spent, in % per minute, from the official
// readings: the status lines', and the usage probe's look at /usage (every 2 minutes while something
// works, usage-probe.ts), which keeps them coming while no status line refreshes (agents at work in
// the background). Pure: hub.ts feeds it readings and asks for the pace.
//
// The meters are whole percents, so one reading says little; the pace is a least-squares line over
// the readings of a recent stretch (5-hour: 10 minutes; the week, which moves slowly: 30). With too
// few readings, or readings that cover too little time, there is no pace (null) rather than a
// guess built from one step of a staircase.

export type RateKey = 'five' | 'seven'
type Sample = { t: number; pct: number }

const CONFIG: Record<RateKey, { windowMs: number; minSpanMs: number }> = {
  five: { windowMs: 10 * 60_000, minSpanMs: 4 * 60_000 },
  seven: { windowMs: 30 * 60_000, minSpanMs: 12 * 60_000 },
}
const MIN_SAMPLES = 3
/** Under this the pace counts as standing still: no ETA. */
const IDLE_PER_MIN = 0.005

const history: Record<RateKey, { resetsAt: number; samples: Sample[] }> = {
  five: { resetsAt: 0, samples: [] },
  seven: { resetsAt: 0, samples: [] },
}

/** Remember one official reading. A limit Claude Code hit (pct forced to 100) says nothing about pace. */
export function recordReading(k: RateKey, pct: number, resetsAt: number, t: number, forced = false): void {
  if (forced || !Number.isFinite(pct) || pct >= 100) return
  const h = history[k]
  // A new window starts from nothing; a reset time that only drifts by a minute is the same window.
  if (Math.abs(resetsAt - h.resetsAt) > 120_000) {
    h.samples = []
    h.resetsAt = resetsAt
  }
  const last = h.samples[h.samples.length - 1]
  if (last && t <= last.t + 1000) {
    // Same second: keep the higher, like the merge does.
    if (pct > last.pct) last.pct = pct
    return
  }
  // Readings can arrive out of order across sessions; keep them sorted.
  h.samples.push({ t, pct })
  h.samples.sort((a, b) => a.t - b.t)
  const cutoff = Math.max(...h.samples.map((s) => s.t)) - 2 * CONFIG[k].windowMs
  h.samples = h.samples.filter((s) => s.t >= cutoff)
}

/** %/min over the recent stretch ending at [now], or null when there is too little to go on. */
export function paceOf(k: RateKey, now: number): number | null {
  const { windowMs, minSpanMs } = CONFIG[k]
  const s = history[k].samples.filter((x) => x.t >= now - windowMs && x.t <= now + 5000)
  if (s.length < MIN_SAMPLES) return null
  if (s[s.length - 1].t - s[0].t < minSpanMs) return null
  const t0 = s[0].t
  const n = s.length
  let sx = 0, sy = 0, sxx = 0, sxy = 0
  for (const p of s) {
    const x = (p.t - t0) / 60_000
    sx += x; sy += p.pct; sxx += x * x; sxy += x * p.pct
  }
  const den = n * sxx - sx * sx
  if (den <= 0) return null
  return Math.max(0, (n * sxy - sx * sy) / den)
}

/**
 * `…EtaMs`: from now, as of now. `…LimitAt`: the same projection as a moment (epoch ms, to the
 * minute), counted from the last reading, so it holds still between readings and every client
 * shows the same time.
 */
export type Rate = {
  fiveHour: number | null
  sevenDay: number | null
  fiveHourEtaMs: number | null
  sevenDayEtaMs: number | null
  fiveHourLimitAt: number | null
  sevenDayLimitAt: number | null
  working: boolean
}

type Win = { pct: number; resetsAt: number } | null

/**
 * The rate block of Usage: null while nothing works. The pace is rounded (0.01 %/min, the ETA to
 * the minute) so an unchanged pace doesn't make a new message every few seconds.
 */
export function rateFor(working: boolean, five: Win, seven: Win, now = Date.now()): Rate | null {
  if (!working) return null
  const one = (k: RateKey, w: Win): [number | null, number | null, number | null] => {
    // A window that has rolled over or is at its limit has no pace; its history is of the old one.
    if (!w || w.pct >= 100 || now >= w.resetsAt || Math.abs(w.resetsAt - history[k].resetsAt) > 120_000) return [null, null, null]
    const p = paceOf(k, now)
    if (p == null) return [null, null, null]
    const rate = Math.round(p * 100) / 100
    if (p < IDLE_PER_MIN) return [0, null, null]
    const eta = ((100 - w.pct) / p) * 60_000
    if (now + eta >= w.resetsAt) return [rate, null, null]
    // From the last reading the meter shows: it doesn't move between readings.
    const last = history[k].samples.filter((x) => x.t <= now + 5000).at(-1)
    const from = last ? last.t + ((100 - w.pct) / p) * 60_000 : now + eta
    const at = Math.round(from / 60_000) * 60_000
    return [rate, Math.max(60_000, Math.round(eta / 60_000) * 60_000), at < w.resetsAt ? Math.max(at, Math.ceil(now / 60_000) * 60_000) : null]
  }
  const [fr, fe, fa] = one('five', five)
  const [sr, se, sa] = one('seven', seven)
  return { fiveHour: fr, sevenDay: sr, fiveHourEtaMs: fe, sevenDayEtaMs: se, fiveHourLimitAt: fa, sevenDayLimitAt: sa, working: true }
}

/** For tests. */
export function resetRateHistory(): void {
  for (const k of ['five', 'seven'] as const) history[k] = { resetsAt: 0, samples: [] }
}
