// The one thing demo/data's files don't hold literally: times, so a screenshot never looks stale.
// A JSON string that is exactly one of these is replaced as the file is read (demo/README.md):
//   "@now", "@now-3m", "@now+2h", "@now-1d4h"   epoch ms; units s, m, h, d
//   "@day", "@day-3", "@day+1"                  "YYYY-MM-DD", that many days from today, local time
//   "@next 07:30"                               epoch ms of the next 07:30, local time
//   "@next 07:30 1-5", "@next 18:00 5"          the same, on those ISO days only (1 Monday … 7 Sunday)
//   "@last 07:30", "@last 07:30 1-5"            the same, the latest one that has passed
// Nothing here knows about the server or the protocol, so another client's demo can do the same.

const UNIT_MS: Record<string, number> = { s: 1000, m: 60_000, h: 3_600_000, d: 86_400_000 }
const NOW = /^@now(?:([+-])((?:\d+[smhd])+))?$/
const DAY = /^@day(?:([+-])(\d+))?$/
const CLOCK = /^@(next|last) ([01]\d|2[0-3]):([0-5]\d)(?: ([1-7])(?:-([1-7]))?)?$/
/** Looks like one of ours, so a typo fails the test rather than reaching the phone as a string. */
export const LOOKS_LIKE_PLACEHOLDER = /^@(now|day|next|last)\b/

const pad = (n: number) => String(n).padStart(2, '0')
const isoDay = (d: Date) => ((d.getDay() + 6) % 7) + 1

function clock(dir: 1 | -1, h: number, m: number, first: number, last: number, now: number): number {
  for (let i = 0; i <= 8; i++) {
    const d = new Date(now)
    d.setDate(d.getDate() + dir * i)
    d.setHours(h, m, 0, 0)
    if (dir * (d.getTime() - now) > 0 && isoDay(d) >= first && isoDay(d) <= last) return d.getTime()
  }
  throw new Error(`no day in ${first}-${last}`)
}

/** What [s] stands for, or [s] itself when it isn't a placeholder. */
export function resolveString(s: string, now: number): string | number {
  let m = NOW.exec(s)
  if (m) {
    let ms = 0
    for (const [, n, unit] of (m[2] ?? '').matchAll(/(\d+)([smhd])/g)) ms += Number(n) * UNIT_MS[unit!]!
    return m[1] === '-' ? now - ms : now + ms
  }
  m = DAY.exec(s)
  if (m) {
    const d = new Date(now)
    d.setDate(d.getDate() + (m[1] === '-' ? -1 : 1) * Number(m[2] ?? 0))
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  m = CLOCK.exec(s)
  if (m) return clock(m[1] === 'next' ? 1 : -1, Number(m[2]), Number(m[3]), Number(m[4] ?? 1), Number(m[5] ?? m[4] ?? 7), now)
  return s
}

/** [v] with every placeholder in it replaced; the input is left as it was. */
export function resolve<T>(v: T, now: number = Date.now()): T {
  if (typeof v === 'string') return resolveString(v, now) as T
  if (Array.isArray(v)) return v.map((x) => resolve(x, now)) as T
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, resolve(x, now)])) as T
  return v
}
