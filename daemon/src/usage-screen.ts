// Claude Code's /usage screen, read as plain text (usage-probe.ts opens it in a hidden terminal).
// Its status line reports only the 5-hour and the all-models week (2.1.302: `rate_limits` has
// `five_hour` and `seven_day`); the weeks some models have of their own (Fable 5.1, Sonnet) show
// only here.
//
// As 2.1.302 draws its Usage tab 62 columns wide or more (checked against its code), each limit is
// a block of three lines, the blocks a blank line apart:
//
//   Current week (all models)
//   ████████████████████▌                              41% used
//   Resets Oct 21, 10am (Europe/Moscow)
//
// and narrower, the reset on the title's line and the percent on a line of its own:
//
//   Current week (all models) · Resets Oct 21, 10am (Europe/Moscow)
//   ████████████████████▌
//   41% used
//
// The rows: "Current session" (the 5-hour window), "Current week (all models)", "Current week
// (Sonnet only)" (Max and Team plans), one "Current week (<model>)" for each model with a week of
// its own, named as the plan names the model ("Current week (Fable)", or "(Fable 5.1)"), then
// credits ("Usage credits": spent, not a limit). A promo line can sit under a row. The percent is
// whole (Claude Code floors it); the reset is to the minute, in the Mac's time zone, which it
// names, and a model's row always gives its date.
//
// The first two rows are the windows the status line reports too: the probe reads them as official
// readings of their own (planWindows), which keep the spend pace going while no status line
// refreshes (usage-rate.ts).

export type ScreenRow = { title: string; pct: number; reset: string | null; resetsAt: number | null }
export type UsageScreen = {
  rows: ScreenRow[]
  /** The usage rows are there: the screen was read. */
  ok: boolean
  /** Claude Code is still fetching ("Loading usage data…", or "Refreshing…" over the last numbers). */
  loading: boolean
  /** Claude Code said it couldn't fetch them ("Error: Failed to load usage data", rate limited). */
  error: string | null
  /**
   * The screen is there but says the per-model breakdown isn't ("Per-model breakdown unavailable
   * (rate limited)", "Partial usage data"): the model rows are missing, not gone. The text it said.
   */
  partial: string | null
}
/** A model's own weekly limit: "Fable", "Sonnet". [resetsAt]: epoch ms, null when the screen gave none. */
export type ModelWeek = { label: string; pct: number; resetsAt: number | null }

const PCT = /(\d{1,3}(?:\.\d+)?)%\s+used\s*$/
// The meter: block elements (█▉▊▋▌▍▎▏, ░▒▓) and spaces; an empty one draws as nothing at all.
const BAR = /^[\s▀-▟■□]*$/u
const RESETS = /\b(?:Resets|Expires)\s+(.+?)\s*$/
const blank = (s: string | undefined) => !s || !s.trim()

/** Every row on the screen, and whether it is the usage screen, still loading or an error. */
export function readUsageScreen(text: string, now = Date.now()): UsageScreen {
  const lines = text.split('\n').map((l) => l.replace(/\s+$/, ''))
  const rows: ScreenRow[] = []
  for (let i = 0; i < lines.length; i++) {
    const m = PCT.exec(lines[i]!)
    if (!m) continue
    // What's left of the percent is the meter (wide layout) or nothing (narrow): anything else,
    // such as a sentence that happens to end that way, isn't a row.
    if (!BAR.test(lines[i]!.slice(0, m.index))) continue
    // The block above: the title on top. Narrow, an empty meter right above draws as a blank line.
    let j = i - 1
    if (blank(lines[j]) && j - 1 >= 0 && !blank(lines[j - 1]) && !BAR.test(lines[j - 1]!)) j--
    const block: string[] = []
    while (j >= 0 && i - j <= 6 && !blank(lines[j]) && !PCT.test(lines[j]!)) block.unshift(lines[j--]!)
    const texts = block.filter((l) => !BAR.test(l))
    if (!texts.length) continue
    let title = texts[0]!.trim()
    let reset: string | null = null
    const dot = title.indexOf(' · ')
    if (dot > 0) {
      reset = RESETS.exec(title.slice(dot + 3))?.[1] ?? null
      title = title.slice(0, dot).trim()
    }
    // Wide: the reset is under the meter, alone or after a note ("$12.40 / $50.00 spent · Resets …").
    for (let k = i + 1; !reset && k <= i + 3 && !blank(lines[k]) && !PCT.test(lines[k]!); k++) reset = RESETS.exec(lines[k]!)?.[1] ?? null
    rows.push({ title, pct: Number(m[1]), reset, resetsAt: reset ? parseReset(reset, now) : null })
  }
  const all = lines.join('\n')
  const err = /^\s*Error:\s*(.+)$/m.exec(all)?.[1]?.trim() ?? (/Failed to load usage data|Usage endpoint is rate limited/.test(all) ? 'Failed to load usage data' : null)
  const partial = /^\s*(Per-model breakdown unavailable[^\n]*|Partial usage data[^\n]*)$/im.exec(all)?.[1]?.trim() ?? null
  return {
    rows,
    partial,
    ok: rows.some((r) => r.title === 'Current session' || /^Current week \(all models\)$/i.test(r.title)),
    loading: /Loading usage data|Refreshing(?:…|\.\.\.)/.test(all),
    error: err,
  }
}

/** The weeks of the models that have one ("Current week (Fable)", "(Sonnet only)"), not "all models". */
export function modelWeeks(rows: ScreenRow[]): ModelWeek[] {
  const out: ModelWeek[] = []
  for (const r of rows) {
    const m = /^Current week \((.+)\)$/i.exec(r.title)
    if (!m || /^all models$/i.test(m[1]!.trim())) continue
    const label = m[1]!.trim().replace(/\s+only$/i, '')
    if (!label || out.some((o) => o.label === label)) continue
    out.push({ label, pct: r.pct, resetsAt: r.resetsAt })
  }
  return out
}

/**
 * The two windows the status line reports too, as the screen shows them: "Current session" (the
 * 5-hour window) and "Current week (all models)". [resetsAt] is null when the row gave no reset or
 * one that didn't read; null for a row that isn't there.
 */
export type PlanWindow = { pct: number; resetsAt: number | null }
export function planWindows(rows: ScreenRow[]): { fiveHour: PlanWindow | null; sevenDay: PlanWindow | null } {
  const one = (title: RegExp): PlanWindow | null => {
    const r = rows.find((x) => title.test(x.title))
    return r ? { pct: r.pct, resetsAt: r.resetsAt } : null
  }
  return { fiveHour: one(/^Current session$/i), sevenDay: one(/^Current week \(all models\)$/i) }
}

// ── Reset times ──────────────────────────────────────────────────────────
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']
const WEEKDAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat']

/**
 * A reset as Claude Code writes it, to epoch ms: "2am", "2:30pm" (within a day: the next one),
 * "Oct 21, 10am", "Oct 21, 10:30pm", "Jan 2, 2027, 10am", "Nov 1" (the year: the one that puts it
 * nearest to now), each with the time zone it's in, "(Europe/Moscow)" (else the Mac's). In case a
 * version words it so, a weekday too: "Thu 10am", "Thursday at 10:30pm" (the next one), "Thu, Oct
 * 21, 10am" (the date decides), and "Oct 21 at 10am". A 24-hour clock and the day before the month,
 * as other locales write them, read too: "19:00", "21 Oct", "21 Oct, 19:00", "Oct 21, 19:00".
 * Null for anything else.
 */
export function parseReset(text: string, now = Date.now()): number | null {
  let s = text.trim()
  let tz: string | undefined
  const z = /\s*\(([A-Za-z][\w+\-]*(?:\/[\w+\-]+)*)\)\s*$/.exec(s)
  if (z) {
    tz = validZone(z[1]!)
    s = s.slice(0, z.index).trim()
  }
  // "… at 10am" reads as "…, 10am"; a weekday in front is set aside.
  s = s.replace(/\s+at\s+(?=\d)/i, ', ')
  let weekday = -1
  const wd = /^(sun|mon|tue|wed|thu|fri|sat)[a-z]*\.?,?\s+/i.exec(s)
  if (wd) {
    weekday = WEEKDAYS.indexOf(wd[1]!.toLowerCase())
    s = s.slice(wd[0].length)
  }
  // A 12-hour clock's hour (1–12 with am/pm) or a 24-hour one's (0–23, no am/pm).
  const hour = (h: string, ap: string | undefined) => (ap ? (Number(h) % 12) + (ap.toLowerCase() === 'pm' ? 12 : 0) : Number(h))
  const clock = (h: string | undefined, m: string | undefined, ap: string | undefined) => !h || (ap ? Number(h) >= 1 && Number(h) <= 12 : Number(h) <= 23 && m !== undefined) && Number(m ?? 0) < 60
  const time = /^(\d{1,2})(?::(\d{2}))?\s*([ap]m)?$/i.exec(s)
  if (time) {
    if (!clock(time[1], time[2], time[3])) return null
    const today = partsIn(now, tz)
    const at = (dayShift: number) => wallTime(today.year, today.month, today.day + dayShift, hour(time[1]!, time[3]), Number(time[2] ?? 0), tz)
    // That weekday's (today's while it's still ahead), else today's; gone by more than a few
    // minutes, the next one.
    const shift = weekday < 0 ? 0 : (weekday - new Date(Date.UTC(today.year, today.month, today.day)).getUTCDay() + 7) % 7
    const t = at(shift)
    return t < now - 10 * 60_000 ? at(shift + (weekday < 0 ? 1 : 7)) : t
  }
  // "Oct 21[, 2027][, 10am | 19:00]", or the day first: "21 Oct[ 2027][, 19:00]".
  const TIME = '(?:,?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)?)?'
  const monthFirst = new RegExp(`^([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{1,2})(?:,\\s*(\\d{4}))?${TIME}$`, 'i').exec(s)
  const dayFirst = monthFirst ? null : new RegExp(`^(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\.?(?:,?\\s*(\\d{4}))?${TIME}$`, 'i').exec(s)
  const date = monthFirst ? { mon: monthFirst[1]!, day: monthFirst[2]!, year: monthFirst[3], h: monthFirst[4], m: monthFirst[5], ap: monthFirst[6] } : dayFirst ? { mon: dayFirst[2]!, day: dayFirst[1]!, year: dayFirst[3], h: dayFirst[4], m: dayFirst[5], ap: dayFirst[6] } : null
  if (!date) return null
  const month = MONTHS.indexOf(date.mon.toLowerCase())
  const day = Number(date.day)
  // A bare hour reads only with am/pm ("Oct 21, 10am"); "Oct 21, 10" isn't a time.
  if (month < 0 || day < 1 || day > 31 || !clock(date.h, date.m, date.ap)) return null
  const h = date.h ? hour(date.h, date.ap) : 0
  const mi = Number(date.m ?? 0)
  if (date.year) return wallTime(Number(date.year), month, day, h, mi, tz)
  const y = partsIn(now, tz).year
  let best: number | null = null
  for (const year of [y - 1, y, y + 1]) {
    const t = wallTime(year, month, day, h, mi, tz)
    if (best === null || Math.abs(t - now) < Math.abs(best - now)) best = t
  }
  return best
}

function validZone(tz: string): string | undefined {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz })
    return tz
  } catch {
    return undefined
  }
}

/** The wall clock at [t] in [tz] (else the Mac's own zone); [month] 0-based. */
function partsIn(t: number, tz: string | undefined): { year: number; month: number; day: number; hour: number; minute: number; second: number } {
  if (!tz) {
    const d = new Date(t)
    return { year: d.getFullYear(), month: d.getMonth(), day: d.getDate(), hour: d.getHours(), minute: d.getMinutes(), second: d.getSeconds() }
  }
  const p: Record<string, string> = {}
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' })
  for (const x of f.formatToParts(new Date(t))) p[x.type] = x.value
  return { year: Number(p.year), month: Number(p.month) - 1, day: Number(p.day), hour: Number(p.hour) % 24, minute: Number(p.minute), second: Number(p.second) }
}

/** The instant a wall clock in [tz] shows this time (day overflow rolls into the next month). */
function wallTime(year: number, month: number, day: number, hour: number, minute: number, tz: string | undefined): number {
  if (!tz) return new Date(year, month, day, hour, minute).getTime()
  const asUtc = Date.UTC(year, month, day, hour, minute)
  // The zone's offset at that moment, asked twice: once near it, once at the answer (a DST change between).
  const offset = (t: number) => {
    const p = partsIn(t, tz)
    return Date.UTC(p.year, p.month, p.day, p.hour, p.minute, p.second) - Math.floor(t / 1000) * 1000
  }
  let t = asUtc - offset(asUtc)
  const again = offset(t)
  if (asUtc - again !== t) t = asUtc - again
  return t
}
