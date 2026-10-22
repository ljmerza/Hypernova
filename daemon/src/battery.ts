// The Mac's own battery, for the phone and the glasses (docs/PROTOCOL.md, "Battery"): `pmset -g
// batt` and the battery's own time estimate from `ioreg` (averaged over five minutes) at start and
// once a minute, run without blocking. A Mac without one (Mac mini, iMac, Mac Studio, Mac Pro) has none, `null`, and the
// clients show nothing for it. Clients hear of a change they'd show ({t:"battery"}): the
// percentage, charging, or the power source; the snapshot always has the latest reading, minutes
// left included.
import { execFile } from 'node:child_process'
import { broadcast } from './hub.ts'
import { log } from './util.ts'

export type Battery = {
  percent: number
  charging: boolean
  /** On the power adapter (charging or not); false on the battery itself. */
  onAC: boolean
  /** Discharging: until it's empty; charging: until it's full; else, or while macOS estimates, null. */
  minutesLeft: number | null
}

/** The battery's own estimates, in minutes (null: it has none right now). */
export type BatteryTimes = { toEmpty: number | null; toFull: number | null }

const PMSET = '/usr/bin/pmset'
const IOREG = '/usr/sbin/ioreg'
const EVERY_MS = 60_000
/** How often the clock is looked at: a minute of the timer doesn't count the time the Mac slept. */
const TICK_MS = 10_000

/**
 * pmset's time is macOS's power-source estimate, which caps at 20 hours, and on macOS 26 it can
 * sit at that cap ("20:00 remaining") for a whole evening while the battery runs down in a few
 * hours. That reading is no estimate at all.
 */
const PMSET_CAP_MIN = 20 * 60
/**
 * How far back the battery's own estimates are averaged. Each is its last minute's, and it swings
 * with the load: on the owner's MacBook at 28%, 402 minutes, then 346, then 1532 at a quiet moment.
 */
const STEADY_MS = 5 * 60_000

/**
 * What `pmset -g batt` says about the Mac's own battery, or null when it has none. Only the internal
 * battery counts: a UPS on USB is listed the same way ("-CP1500PFCLCD (id=…)\t100%; charged; …"),
 * and a desktop on one still has no battery of its own. The time left comes from the battery's own
 * averages ([times], from `ioreg`) when there are any; pmset's own time is used only without them.
 *
 *   Now drawing from 'Battery Power'
 *    -InternalBattery-0 (id=7209059)\t84%; discharging; 5:12 remaining present: true
 */
export function parsePmset(out: string, times: BatteryTimes | null = null): Battery | null {
  const line = out.split('\n').find((l) => l.includes('InternalBattery'))
  if (!line || /present:\s*false/i.test(line)) return null
  const m = /\s(\d{1,3})%;\s*([^;]*)(?:;\s*(.*))?$/.exec(line)
  if (!m) return null
  const percent = Math.min(100, Math.max(0, Number(m[1])))
  const state = m[2]!.trim().toLowerCase()
  // "charging" and "finishing charge" charge; "charged", "AC attached" ("not charging", which
  // optimized charging holds at 80%) and "discharging" don't.
  const charging = state === 'charging' || state === 'finishing charge'
  const source = /Now drawing from '([^']*)'/.exec(out)?.[1]
  const onAC = source ? source === 'AC Power' : state !== 'discharging'
  let minutes: number | null = null
  if (times) minutes = state === 'discharging' ? times.toEmpty : charging ? times.toFull : null
  else {
    // "0:00 remaining" with a full battery, "(no estimate)" while macOS works it out, and the
    // 20-hour cap: no time to show.
    const t = /(\d{1,4}):(\d{2}) remaining/.exec(m[3] ?? '')
    const pm = t ? Number(t[1]) * 60 + Number(t[2]) : 0
    minutes = pm > 0 && pm < PMSET_CAP_MIN ? pm : null
  }
  const timed = state === 'discharging' || charging
  return { percent, charging, onAC, minutesLeft: timed && minutes != null && minutes > 0 ? minutes : null }
}

/**
 * The battery's own time estimates from `ioreg -rn AppleSmartBattery`: AvgTimeToEmpty and
 * AvgTimeToFull, its averages over the last minute, in minutes. 65535 is the battery's "no
 * estimate" (just unplugged, or not charging). Null without a battery or those keys (then pmset's
 * time stands in).
 *
 *       "AvgTimeToFull" = 65535
 *       "AvgTimeToEmpty" = 185
 */
export function parseIoreg(out: string): BatteryTimes | null {
  const read = (key: string): number | null | undefined => {
    const m = new RegExp(`^\\s*"${key}" = (\\d{1,6})\\s*$`, 'm').exec(out)
    if (!m) return undefined
    const n = Number(m[1])
    return n > 0 && n < 65535 ? n : null
  }
  const toEmpty = read('AvgTimeToEmpty')
  const toFull = read('AvgTimeToFull')
  if (toEmpty === undefined && toFull === undefined) return null
  return { toEmpty: toEmpty ?? null, toFull: toFull ?? null }
}

/** Whether clients hear of [next]: a battery coming or going, the percentage, charging, the power source. */
export function batteryNews(prev: Battery | null, next: Battery | null): boolean {
  if (!prev || !next) return prev !== next
  return Math.abs(prev.percent - next.percent) >= 1 || prev.charging !== next.charging || prev.onAC !== next.onAC
}

/** One of the battery's own estimates, kept a few minutes to average over. */
export type Estimate = { at: number; charging: boolean; percent: number; minutes: number }

/**
 * The minutes left at the battery's average pace over [recent] (its estimates in one state,
 * charging or not, oldest first), for the percent the newest still has to go. The pace (percent
 * to go over minutes) is what's averaged, not the minutes: time left is charge over current, and
 * one quiet minute's "25 hours" would otherwise outweigh four busy ones.
 */
export function steadyMinutes(recent: Estimate[]): number | null {
  const last = recent.at(-1)
  if (!last) return null
  const toGo = (e: Estimate) => (e.charging ? 100 - e.percent : e.percent)
  const paces = recent.filter((e) => toGo(e) > 0).map((e) => toGo(e) / e.minutes)
  if (!paces.length || toGo(last) <= 0) return last.minutes
  const pace = paces.reduce((a, b) => a + b, 0) / paces.length
  return Math.max(1, Math.round(toGo(last) / pace))
}

/** The battery's own estimates of the last few minutes, all in the newest one's state. */
let recent: Estimate[] = []

/**
 * [b] with its minutes left steadied over the battery's estimates of the last five minutes.
 * [fromBattery]: its time is the battery's own; pmset's is macOS's, already an average, and stands
 * as it is. Plugging in or out, a full battery or a gap (the Mac slept) starts the average over.
 */
export function steadyBattery(b: Battery | null, fromBattery: boolean, now: number): Battery | null {
  if (!b || b.minutesLeft == null || !fromBattery) {
    recent = []
    return b
  }
  recent = recent.filter((e) => e.charging === b.charging && e.at <= now && now - e.at < STEADY_MS)
  recent.push({ at: now, charging: b.charging, percent: b.percent, minutes: b.minutesLeft })
  return { ...b, minutesLeft: steadyMinutes(recent) }
}

let current: Battery | null = null
/** The latest reading (null: no battery, or none read yet). */
export const getBattery = (): Battery | null => current

/** A reading: kept for the snapshot, sent to clients when it's news. */
export function noteBattery(next: Battery | null): void {
  const news = batteryNews(current, next)
  current = next
  if (news) broadcast({ t: 'battery', battery: next })
}

/** What the two commands said: pmset's (null when it couldn't say this time) and ioreg's. */
export type BatteryRead = { pmset: string | null; ioreg: string | null }

/** [file]'s output; null when it couldn't say this time. Throws when there's no such program at all. */
function run(file: string, args: string[]): Promise<string | null> {
  return new Promise((resolve, reject) => {
    execFile(file, args, { timeout: 5000, encoding: 'utf8', maxBuffer: 256 * 1024 }, (e, out) => {
      if ((e as NodeJS.ErrnoException | null)?.code === 'ENOENT') return reject(e)
      resolve(e ? null : out)
    })
  })
}

/**
 * Both readings, side by side. Without pmset (not macOS) this throws, which won't change; without
 * ioreg's (it failed, or isn't there) pmset's time stands in.
 */
async function runBoth(): Promise<BatteryRead> {
  const [pmset, ioreg] = await Promise.all([run(PMSET, ['-g', 'batt']), run(IOREG, ['-rn', 'AppleSmartBattery']).catch(() => null)])
  return { pmset, ioreg }
}

let reading = false
let timer: NodeJS.Timeout | null = null
/** When the last reading was taken, by the wall clock (which, unlike timers, counts sleep). */
let readAt = 0

/** Read the battery now: one read at a time, and a read that fails leaves the last reading. */
export async function readBattery(read: () => Promise<BatteryRead> = runBoth): Promise<void> {
  if (reading) return
  reading = true
  readAt = Date.now()
  try {
    const out = await read()
    if (out.pmset != null) {
      const times = out.ioreg == null ? null : parseIoreg(out.ioreg)
      noteBattery(steadyBattery(parsePmset(out.pmset, times), times != null, Date.now()))
    }
  } catch (e) {
    // No pmset (not macOS): no battery to show, ever.
    log('battery: pmset unavailable', (e as NodeJS.ErrnoException).code ?? e)
    if (timer) clearInterval(timer)
    timer = null
    noteBattery(null)
  } finally {
    reading = false
  }
}

/**
 * At start: a reading now and one a minute (a timer that never keeps the daemon running). The
 * minute goes by the wall clock: a timer's minute doesn't count the time the Mac slept, so a phone
 * reconnecting after a night in the backpack would get the evening's battery for up to a minute.
 */
export function startBattery(): void {
  if (timer) return
  timer = setInterval(() => {
    // A clock set back reads at once too, rather than waiting for it to catch up.
    const gap = Date.now() - readAt
    if (gap >= EVERY_MS || gap < 0) void readBattery()
  }, TICK_MS)
  timer.unref()
  void readBattery()
}
