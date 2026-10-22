// Cron expressions for automations' cron trigger (docs/PROTOCOL.md, "Automations", "Workflows"):
// five fields (minute, hour, day of the month, month, day of the week) in the Mac's time, read the
// way cron reads them. Pure: no clock, no zone (schedule.ts walks the days in the Mac's zone).

/** What a cron expression allows, field by field. */
export type Cron = {
  minutes: number[]
  hours: number[]
  /** Days of the month 1–31, months 1–12, days of the week 0–6 (0 Sunday). */
  dom: Set<number>
  months: Set<number>
  dow: Set<number>
  /** A day field was narrowed ("*" is not): with both narrowed, either one matching is enough. */
  domAny: boolean
  dowAny: boolean
}
export class CronError extends Error {}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']
const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat']
const MACROS: Record<string, string> = { '@hourly': '0 * * * *', '@daily': '0 0 * * *', '@midnight': '0 0 * * *', '@weekly': '0 0 * * 0', '@monthly': '0 0 1 * *' }
const FIELDS = [
  { name: 'minute', lo: 0, hi: 59, names: null },
  { name: 'hour', lo: 0, hi: 23, names: null },
  { name: 'day of the month', lo: 1, hi: 31, names: null },
  { name: 'month', lo: 1, hi: 12, names: MONTHS },
  { name: 'day of the week', lo: 0, hi: 7, names: DAYS },
] as const

/** "a minute", "an hour". */
const a = (name: string) => `${name === 'hour' ? 'an' : 'a'} ${name}`
function value(s: string, f: (typeof FIELDS)[number]): number {
  const named = f.names?.indexOf(s.toLowerCase() as never) ?? -1
  if (named >= 0) return named + (f.name === 'month' ? 1 : 0)
  if (!/^\d{1,2}$/.test(s)) throw new CronError(`“${s}” isn’t ${a(f.name)}.`)
  const n = Number(s)
  if (n < f.lo || n > f.hi) throw new CronError(`The ${f.name} goes from ${f.lo} to ${f.hi}: “${s}” doesn’t.`)
  return n
}

/** One field: `*`, `a`, `a-b`, `a,b`, `*\/n`, `a-b/n`, `a/n`. */
function field(text: string, f: (typeof FIELDS)[number]): Set<number> {
  const out = new Set<number>()
  for (const part of text.split(',')) {
    const m = /^([^/]+)(?:\/(\d{1,2}))?$/.exec(part)
    if (!m) throw new CronError(`“${part}” isn’t ${a(f.name)}.`)
    const step = m[2] === undefined ? 1 : Number(m[2])
    if (step < 1) throw new CronError(`A step of 0 in the ${f.name} field.`)
    let lo: number
    let hi: number
    if (m[1] === '*') [lo, hi] = [f.lo, f.hi]
    else if (m[1]!.includes('-')) {
      const [from, to, ...more] = m[1]!.split('-')
      if (more.length || !from || !to) throw new CronError(`“${part}” isn’t a range of the ${f.name} field.`)
      ;[lo, hi] = [value(from, f), value(to, f)]
      if (hi < lo) throw new CronError(`“${part}” runs backwards.`)
    } else {
      lo = value(m[1]!, f)
      hi = m[2] === undefined ? lo : f.hi
    }
    for (let x = lo; x <= hi; x += step) out.add(x)
  }
  return out
}

/** A cron expression read, or a CronError saying what's wrong. */
export function parseCron(expr: unknown): Cron {
  const raw = typeof expr === 'string' ? expr.trim() : ''
  if (!raw) throw new CronError('Write a cron expression, like */15 9-18 * * 1-5.')
  if (raw.length > 120) throw new CronError('That cron expression is too long.')
  const text = raw.startsWith('@') ? MACROS[raw.toLowerCase()] : raw
  if (!text) throw new CronError(`“${raw}” isn’t one of @hourly, @daily, @weekly or @monthly.`)
  const parts = text.split(/\s+/)
  if (parts.length !== 5) throw new CronError('Five fields: minute, hour, day of the month, month, day of the week.')
  const [mi, h, dom, mo, dow] = parts.map((p, i) => field(p, FIELDS[i]!))
  const days = new Set([...dow!].map((d) => d % 7))
  return {
    minutes: [...mi!].sort((a, b) => a - b),
    hours: [...h!].sort((a, b) => a - b),
    dom: dom!,
    months: mo!,
    dow: days,
    domAny: parts[2] === '*',
    dowAny: parts[4] === '*',
  }
}

/** Whether a cron runs on this day ([dow] 1 Monday … 7 Sunday, as schedule.ts counts). */
export function cronDay(c: Cron, mo: number, d: number, dow: number): boolean {
  if (!c.months.has(mo)) return false
  const domOk = c.dom.has(d)
  const dowOk = c.dow.has(dow % 7)
  // Both narrowed: either one is enough (cron's own rule); one narrowed: that one.
  if (!c.domAny && !c.dowAny) return domOk || dowOk
  return domOk && dowOk
}

const cache = new Map<string, Cron | null>()
/** [parseCron], remembered; null for one that doesn't read. */
export function cronOf(expr: string): Cron | null {
  if (cache.has(expr)) return cache.get(expr)!
  let c: Cron | null = null
  try {
    c = parseCron(expr)
  } catch {}
  if (cache.size > 200) cache.clear()
  cache.set(expr, c)
  return c
}
