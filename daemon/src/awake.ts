// Stay awake (docs/PROTOCOL.md, "Stay awake"; docs/DECISIONS.md, "Stay awake"): the Mac stays
// awake while there is something to stay awake for, and is let go when there's a reason to.
//
// - While working (the default): held while sessions or their agents work, an automation asks for
//   it, or a phone is connected, and for 3 minutes after the last of those. The
//   grace is there because macOS sleeps the moment an assertion goes if the Mac has been idle long
//   enough: a phone's socket blinking during a network change would otherwise put it to sleep.
// - Backpack: held whatever happens, with the lid closed too, for 8 hours at most.
// - Off: nothing held.
//
// The hold is `caffeinate -i -s -w <daemon pid>`, a child of the daemon, rather than an IOKit
// assertion taken by the Mac app: the daemon is what knows when there's work, it runs whether or not
// the menu bar app does, and `-w` ties the assertion to the daemon's life, so a daemon that dies,
// even by SIGKILL with no chance to clean up, never leaves the Mac unable to sleep.
//
// A MacBook on battery sleeps when its lid closes whatever assertions say. Only macOS's
// system-wide SleepDisabled (`pmset -a disablesleep 1`, root only) keeps it awake, which is also
// what Amphetamine's Enhancer turns on. Backpack runs exactly that through `sudo -n`, against a rule
// the Mac app installs once with the owner's password (/etc/sudoers.d/hypernova: `pmset -a
// disablesleep 0` and `… 1` for this user, nothing else). When Backpack ends it puts back what was
// there before: the owner may have SleepDisabled on already (Amphetamine), and then Backpack never
// touches it. What it set is written down first, so a daemon that restarts finds and undoes it.
//
// Guards: the battery at 15% (adjustable) in either mode, and in Backpack macOS's thermal pressure
// at "heavy" or worse, and its 8 hours. Two minutes before it lets go, clients hear
// {t:"mac_sleep_soon"}; the guard clearing first (plugged in, cooled down) calls it off. Heat swings
// while a Mac throttles, so a guard has to stay clear for a while before it counts as gone, and the
// same warning isn't sent twice in a row. A SleepDisabled that is someone else's (Amphetamine's)
// keeps the Mac awake whatever Hypernova does, so clients are told rather than promised a sleep.
import { execFile, execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { broadcast, listSessionRecs } from './hub.ts'
import { getBattery, type Battery } from './battery.ts'
import { SUPPORT, log, readJson, writeJson } from './util.ts'
import { has, LINUX_REASONS, UNAVAILABLE } from './platform.ts'

export type AwakeMode = 'working' | 'backpack' | 'off'
/** Why the Mac is let go: the battery, the heat, or Backpack's hours are up. */
export type SleepReason = 'battery' | 'hot' | 'time'

/** `Snapshot.mac.awake` (docs/PROTOCOL.md, "Stay awake"). */
export type Awake = {
  mode: AwakeMode
  /** Keeping the Mac awake right now. */
  holding: boolean
  /** A closed lid won't put it to sleep now: macOS's SleepDisabled is on (Backpack's, or the owner's own). */
  lidClosedOk: boolean
  /** Backpack can be chosen: a MacBook, with the one-time setup done. */
  backpackReady: boolean
  /** When Backpack ends by itself (epoch ms); null outside Backpack. */
  until: number | null
  /**
   * What stands in the way: a guard ('battery', 'hot', 'time': counting down while [sleepAt] is
   * set, in force once it's past), or Backpack's switch failing ('setup': the rule is gone,
   * 'failed': sudo or pmset said no; also after Backpack, while what it changed can't be put
   * back). Null when nothing does.
   */
  reason: string | null
  /**
   * Backpack ends within [EXTEND_WINDOW_MS]: "+2 h" can be offered, and `{extend: true}` (POST
   * /v1/mac/awake) adds [EXTEND_BY_MS] to [until]. False otherwise (even in Backpack, long before the end).
   */
  extendable: boolean
  /** When a guard lets the Mac sleep ({t:"mac_sleep_soon"} said so); null when none is counting down. */
  sleepAt: number | null
  /** The lid is closed; null for a Mac without one, or before it was read. */
  lidClosed: boolean | null
  /** A MacBook: it has a battery or a lid. Desktops get no Backpack and no battery guard. */
  laptop: boolean
  /** The battery guard's level, in percent. */
  minBattery: number
  /**
   * Whose SleepDisabled is on: Backpack's ('backpack'), or another app's ('other', Amphetamine's
   * say), which keeps the Mac awake even after Hypernova lets go; null when it's off.
   */
  sleepDisabledBy: 'backpack' | 'other' | null
  /** The app macOS names as keeping the Mac awake, when [sleepDisabledBy] is 'other' and it names one. */
  otherApp: string | null
}

/** What macOS says, read every [READ_MS]. Null: couldn't tell. */
export type Readings = {
  /** `pmset -g`'s SleepDisabled. */
  sleepDisabled: 0 | 1 | null
  /** `ioreg`'s AppleClamshellState; null on a Mac without a lid. */
  lid: boolean | null
  /** Too hot to keep going in a closed bag (thermal pressure, or pmset's thermal warning). */
  hot: boolean
  /** An app other than Hypernova that `pmset -g` says is keeping the Mac awake ("Amphetamine"). */
  otherApp: string | null
}

/** Everything that touches the system, so tests can stand in for it. */
export type AwakeIO = {
  read(backpack: boolean): Promise<Readings>
  /** `pmset -g`'s SleepDisabled right now, for the value Backpack must put back. */
  sleepDisabled(): Promise<0 | 1 | null>
  /** `sudo -n pmset -a disablesleep <v>`: whether it worked. */
  setSleepDisabled(v: 0 | 1): Promise<boolean>
  /** The same, blocking, for the daemon's way out. */
  setSleepDisabledSync(v: 0 | 1): boolean
  /** The assertion: on (a caffeinate child) or off. */
  hold(on: boolean): void
  /** The sudoers rule is in place. */
  ruleInstalled(): boolean
}

/** What the daemon knows that Stay awake goes by. */
export type AwakeInputs = {
  now: () => number
  battery: () => Battery | null
  /** Sessions or their agents at work. */
  working: () => boolean
  send: (msg: object) => void
}

/** After the last reason to hold, how long it still holds. */
export const LINGER_MS = 3 * 60_000
/** How long before a guard lets go clients hear of it. */
export const WARN_MS = 2 * 60_000
/** Backpack's longest stretch. */
export const MAX_BACKPACK_MS = 8 * 3600_000
/** How long before Backpack ends "+2 h" is offered (and {t:"mac_backpack_ending"} said once). */
export const EXTEND_WINDOW_MS = 10 * 60_000
/** What each "+2 h" adds to Backpack's end. */
export const EXTEND_BY_MS = 2 * 3600_000
export const DEFAULT_MIN_BATTERY = 15
const TICK_MS = 5_000
const READ_MS = 20_000
/** After Backpack's switch failed, the wait before it's tried again. */
const RETRY_MS = 30_000
/**
 * How long a guard must stay clear before its countdown is called off. Thermal pressure swings
 * between heavy and moderate while a Mac throttles in a bag: without this every swing would call
 * the countdown off and start a new one, so the Mac would never be let go and the phone would hear
 * a new warning each time. Plugging in counts at once: that's the owner's own doing.
 */
export const CLEAR_MS = 60_000
/** After letting go for the heat, how long it must stay cool before Backpack holds again. */
export const COOL_MS = 5 * 60_000
/** The same warning isn't sent again within this: one heads-up per episode, not per swing. */
export const REWARN_MS = 10 * 60_000

type Saved = {
  mode?: AwakeMode
  minBattery?: number
  until?: number | null
  /** What Backpack found and whether it changed it: `applied` → put `prev` back when it ends. */
  backpack?: { prev: 0 | 1; applied: boolean } | null
}

const STATE_FILE = () => join(SUPPORT, 'awake.json')

// ── Reading macOS ─────────────────────────────────────────────────────────

/**
 * SleepDisabled from `pmset -g` (System-wide power settings), 0 or 1; null when the output isn't
 * pmset's.
 *
 *   System-wide power settings:
 *    SleepDisabled		1
 *
 * macOS lists it only once someone has set it, so a Mac where nothing ever has (no Amphetamine, no
 * `pmset disablesleep`) prints pmset's usual headers without the line: that is 0, macOS's default.
 */
export function parseSleepDisabled(out: string): 0 | 1 | null {
  const m = /^\s*SleepDisabled\s+(\d+)\s*$/m.exec(out)
  if (m) return Number(m[1]) === 0 ? 0 : 1
  return /^(System-wide power settings|Currently in use):/m.test(out) ? 0 : null
}

/**
 * The app other than Hypernova that `pmset -g` names on its sleep line, or null.
 *
 *    sleep                1 (sleep prevented by Amphetamine, caffeinate)
 *
 * caffeinate is Hypernova's own hold; lowercase names are macOS's daemons (powerd, coreaudiod),
 * which come and go and aren't what the owner would recognise.
 */
export function parseOtherApp(out: string): string | null {
  const m = /\(sleep prevented by ([^)]*)\)/.exec(out)
  if (!m) return null
  for (const raw of m[1].split(',')) {
    const name = raw.trim()
    if (/^[A-Z]/.test(name) && !/^Hypernova\b/i.test(name)) return name
  }
  return null
}

/**
 * The lid from `ioreg -r -k AppleClamshellState -d 1` (IOPMrootDomain): true when closed, false
 * when open, null on a Mac without one (the key isn't there).
 *
 *       "AppleClamshellState" = Yes
 */
export function parseLid(out: string): boolean | null {
  const m = /"AppleClamshellState"\s*=\s*(Yes|No)\b/.exec(out)
  return m ? m[1] === 'Yes' : null
}

/**
 * The thermal pressure level from `notifyutil -g com.apple.system.thermalpressurelevel`, the
 * value ProcessInfo.thermalState is made from: 0 nominal, 1 moderate, 2 heavy, 3 trapping, 4
 * sleeping. Null when it can't be read.
 *
 *   com.apple.system.thermalpressurelevel 0
 */
export function parsePressure(out: string): number | null {
  const m = /thermalpressurelevel\s+(\d+)/.exec(out)
  return m ? Number(m[1]) : null
}

/** "heavy" or worse (ProcessInfo's .serious and .critical): no place for a Mac in a closed bag. */
export const HOT_PRESSURE = 2

/**
 * Whether `pmset -g therm` reports a thermal warning (Intel Macs; Apple silicon ones print only the
 * three "Note: No … has been recorded" lines and speak through the pressure level above). A
 * thermal warning level of 5 (danger) or more counts, 255 (unknown) doesn't; so does the CPU held
 * at 60% of its speed or less, which only sustained heat does.
 *
 *   Note: No thermal warning level has been recorded
 *   Note: No performance warning level has been recorded
 *   Note: No CPU power status has been recorded
 */
export function parseTherm(out: string): boolean {
  const level = /Thermal[ _]?(?:Warning[ _]?)?Level[^=\d\n]*[=:]\s*(\d+)/i.exec(out)
  if (level) {
    const n = Number(level[1])
    if (n >= 5 && n !== 255) return true
  }
  const speed = /CPU_Speed_Limit\s*=\s*(\d+)/.exec(out)
  return !!speed && Number(speed[1]) <= 60
}

const bin = (env: string, fallback: string) => process.env[env] || fallback

/** [file]'s output, or null when it couldn't say (a missing program too: no lid, no pmset). */
function run(file: string, args: string[], timeout = 5000): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(file, args, { timeout, encoding: 'utf8', maxBuffer: 512 * 1024 }, (e, out) => resolve(e ? null : out))
  })
}

/**
 * Whether this daemon may run sudo for real. A test copy (HYPERNOVA_SUPPORT) on the owner's Mac
 * never changes macOS's own sleep setting unless a stand-in sudo is named (HYPERNOVA_SUDO), as it
 * leaves ~/.zshrc and Claude Code's settings alone.
 */
const mayToggle = () => !process.env.HYPERNOVA_SUPPORT || !!process.env.HYPERNOVA_SUDO

/** The pmset the sudoers rule names: always this path, whatever pmset reads use. */
const RULE_PMSET = '/usr/bin/pmset'

export const realIO: AwakeIO = {
  async read(backpack) {
    const pmset = bin('HYPERNOVA_PMSET', '/usr/bin/pmset')
    const [g, lid, pressure, therm] = await Promise.all([
      run(pmset, ['-g']),
      run(bin('HYPERNOVA_IOREG', '/usr/sbin/ioreg'), ['-r', '-k', 'AppleClamshellState', '-d', '1']),
      // Heat only matters to Backpack: no need to ask otherwise.
      backpack ? run(bin('HYPERNOVA_NOTIFYUTIL', '/usr/bin/notifyutil'), ['-g', 'com.apple.system.thermalpressurelevel']) : Promise.resolve(null),
      backpack ? run(pmset, ['-g', 'therm']) : Promise.resolve(null),
    ])
    const level = pressure == null ? null : parsePressure(pressure)
    return {
      sleepDisabled: g == null ? null : parseSleepDisabled(g),
      lid: lid == null ? null : parseLid(lid),
      hot: (level != null && level >= HOT_PRESSURE) || (therm != null && parseTherm(therm)),
      otherApp: g == null ? null : parseOtherApp(g),
    }
  },
  async sleepDisabled() {
    const g = await run(bin('HYPERNOVA_PMSET', '/usr/bin/pmset'), ['-g'])
    return g == null ? null : parseSleepDisabled(g)
  },
  setSleepDisabled(v) {
    if (!mayToggle()) {
      log('awake: a test copy leaves macOS sleep alone (would run pmset -a disablesleep', v, ')')
      return Promise.resolve(false)
    }
    return new Promise((resolve) => {
      // -n: never a password prompt; without the rule it fails at once.
      execFile(bin('HYPERNOVA_SUDO', '/usr/bin/sudo'), ['-n', RULE_PMSET, '-a', 'disablesleep', String(v)], { timeout: 10_000, encoding: 'utf8' }, (e, _out, err) => {
        if (e) log('awake: pmset -a disablesleep', v, 'failed:', String(err || e.message).trim().slice(0, 200))
        resolve(!e)
      })
    })
  },
  setSleepDisabledSync(v) {
    if (!mayToggle()) return false
    try {
      execFileSync(bin('HYPERNOVA_SUDO', '/usr/bin/sudo'), ['-n', RULE_PMSET, '-a', 'disablesleep', String(v)], { timeout: 5000, stdio: 'ignore' })
      return true
    } catch {
      return false
    }
  },
  hold(on) {
    holdCaffeinate(on)
  },
  ruleInstalled,
}

/** How often sudo is asked again whether Backpack's command is allowed, when nothing changed. */
const RULE_ASK_MS = 5 * 60_000
/** sudo's last answer, and whether Hypernova's rule file was there when it was asked. */
let ruleAnswer: { ok: boolean; file: boolean } | null = null
let ruleAskedAt = -Infinity
let ruleAsking = false

/**
 * Whether `sudo -n pmset -a disablesleep 0|1` will work for this user. sudo itself is asked
 * (`sudo -n -l <command>` lists, never runs): Hypernova's own file can be there for another user of
 * this Mac, and another rule (Amphetamine's Enhancer allows the same two commands to every admin)
 * can make the setup unnecessary. Asked in the background and remembered, since this is read on
 * every review; a rule file that just appeared or went (setup or removal on the Mac) is believed at
 * once until sudo's fresh answer comes.
 */
function ruleInstalled(): boolean {
  const file = existsSync(bin('HYPERNOVA_SUDOERS', '/private/etc/sudoers.d/hypernova'))
  // A test copy without a stand-in sudo never runs sudo: the file is all it goes by.
  if (!mayToggle()) return file
  if (!ruleAsking && (ruleAnswer?.file !== file || Date.now() - ruleAskedAt > RULE_ASK_MS)) {
    ruleAsking = true
    ruleAskedAt = Date.now()
    execFile(bin('HYPERNOVA_SUDO', '/usr/bin/sudo'), ['-n', '-l', RULE_PMSET, '-a', 'disablesleep', '1'], { timeout: 10_000 }, (e) => {
      ruleAnswer = { ok: !e, file }
      ruleAsking = false
    })
  }
  return ruleAnswer && ruleAnswer.file === file ? ruleAnswer.ok : file
}

/** A test copy holds no real assertion unless a stand-in caffeinate is named, as with sudo. */
const mayHold = () => !process.env.HYPERNOVA_SUPPORT || !!process.env.HYPERNOVA_CAFFEINATE

let caffeinate: ChildProcess | null = null
/**
 * The assertion: `caffeinate -i -s` (no idle sleep; no system sleep on power) for as long as the
 * daemon lives (`-w`). A caffeinate that ends by itself is started again by the next review.
 */
function holdCaffeinate(on: boolean): void {
  if (on && !caffeinate && mayHold()) {
    const child = spawn(bin('HYPERNOVA_CAFFEINATE', '/usr/bin/caffeinate'), ['-i', '-s', '-w', String(process.pid)], { stdio: 'ignore' })
    child.on('error', (e) => log('awake: caffeinate', e.message))
    child.on('exit', () => {
      if (caffeinate === child) caffeinate = null
    })
    child.unref()
    caffeinate = child
  } else if (!on && caffeinate) {
    const child = caffeinate
    caffeinate = null
    child.kill('SIGTERM')
  }
}

// ── The keeper ────────────────────────────────────────────────────────────

/**
 * Stay awake's state and decisions. [review] decides from what's known now; [read] refreshes
 * what macOS says; both run on timers in the daemon and by hand in tests.
 */
export class AwakeKeeper {
  private mode: AwakeMode = 'working'
  private minBattery = DEFAULT_MIN_BATTERY
  private until: number | null = null
  private backpack: Saved['backpack'] = null
  private readings: Readings = { sleepDisabled: null, lid: null, hot: false, otherApp: null }
  private clients = 0
  private keeps = new Set<string>()
  /** When the last reason to hold was seen (While working's grace runs from it). */
  private activeAt = -Infinity
  /** A guard counting down to letting go. */
  private countdown: { reason: SleepReason; at: number } | null = null
  /** A guard in force: the Mac is let go until it clears. */
  private blocked: SleepReason | null = null
  /** When each guard was last seen in force (how long it has been clear is measured from it). */
  private seenAt: Partial<Record<SleepReason, number>> = {}
  /** When each guard's warning was last sent. */
  private warnedAt: Partial<Record<SleepReason, number>> = {}
  /** Backpack's 8 hours: warned about already. */
  private timeWarned = false
  /** The end (`until`) that clients were told is near ({t:"mac_backpack_ending"}); an extension is a new end. */
  private endingTold: number | null = null
  private holding = false
  /** Backpack's switch failed ('setup' or 'failed'); cleared when it works or Backpack ends. */
  private problem: string | null = null
  /** A pmset switch under way: one at a time. */
  private toggling: Promise<void> | null = null
  /** When the last switch failed. */
  private failedAt = -Infinity
  private last = ''

  private io: AwakeIO
  private inputs: AwakeInputs
  /** Where the mode, the battery level and Backpack's record are kept (null: nowhere, in tests). */
  private file: string | null

  constructor(io: AwakeIO, inputs: AwakeInputs, file: string | null = null) {
    this.io = io
    this.inputs = inputs
    this.file = file
    if (file) this.load()
  }

  private load(): void {
    const s = readJson<Saved>(this.file!, {})
    if (s.mode === 'working' || s.mode === 'backpack' || s.mode === 'off') this.mode = s.mode
    if (typeof s.minBattery === 'number' && s.minBattery >= 5 && s.minBattery <= 50) this.minBattery = Math.round(s.minBattery)
    this.until = typeof s.until === 'number' ? s.until : null
    if (s.backpack && (s.backpack.prev === 0 || s.backpack.prev === 1)) this.backpack = { prev: s.backpack.prev, applied: !!s.backpack.applied }
    if (this.mode === 'backpack' && this.until == null) this.mode = 'working'
  }

  private save(): void {
    if (!this.file) return
    try {
      writeJson(this.file, { mode: this.mode, minBattery: this.minBattery, until: this.until, backpack: this.backpack } satisfies Saved)
    } catch (e) {
      log('awake: could not save', e)
    }
  }

  get laptop(): boolean {
    return this.inputs.battery() != null || this.readings.lid != null
  }

  state(): Awake {
    const now = this.inputs.now()
    let reason: string | null = null
    let sleepAt: number | null = null
    if (this.countdown) {
      reason = this.countdown.reason
      sleepAt = this.countdown.at
    } else if (this.blocked) reason = this.blocked
    else if (this.endsInBag(now)) {
      reason = 'time'
      sleepAt = this.until
    } else if (this.problem) reason = this.problem
    const sd = this.readings.sleepDisabled
    const sleepDisabledBy = sd === 1 ? (this.backpack?.applied ? 'backpack' : 'other') : null
    return {
      mode: this.mode,
      holding: this.holding,
      lidClosedOk: this.readings.sleepDisabled === 1,
      backpackReady: this.laptop && this.io.ruleInstalled(),
      until: this.mode === 'backpack' ? this.until : null,
      extendable: this.extendable(now),
      reason,
      sleepAt,
      lidClosed: this.readings.lid,
      laptop: this.laptop,
      minBattery: this.minBattery,
      sleepDisabledBy,
      otherApp: sleepDisabledBy === 'other' ? this.readings.otherApp : null,
    }
  }

  /** Backpack, with at most [EXTEND_WINDOW_MS] left and not over: "+2 h" is on offer. */
  private extendable(now: number): boolean {
    return this.mode === 'backpack' && this.until != null && this.until > now && this.until - now <= EXTEND_WINDOW_MS
  }

  /**
   * "+2 h": Backpack's end moves [EXTEND_BY_MS] on, each time it's asked, in its last ten minutes
   * only (there's nothing to extend before: the 8 hours are a promise, not a stock to top up). The
   * guards (battery, heat) go on applying: a Mac that must let go still does. An end that was warned
   * about is warned about afresh when the new one comes near.
   */
  extend(): { ok: true } | { ok: false; code: string; message: string } {
    const now = this.inputs.now()
    if (this.mode !== 'backpack' || this.until == null) return { ok: false, code: 'not_backpack', message: 'Backpack isn’t on, so there is nothing to extend.' }
    if (!this.extendable(now)) return { ok: false, code: 'too_early', message: 'Backpack can be extended in its last 10 minutes.' }
    this.until += EXTEND_BY_MS
    this.timeWarned = false
    this.endingTold = null
    this.save()
    this.review()
    return { ok: true }
  }

  /**
   * Backpack's last two minutes with the lid closed. Only then does its end mean sleep: with the lid
   * open it goes back to While working, which keeps holding for a connected phone.
   */
  private endsInBag(now: number): boolean {
    return this.mode === 'backpack' && this.until != null && this.until - now <= WARN_MS && this.readings.lid === true
  }

  /** A phone connected; the function returned is its disconnect. */
  clientConnected(): () => void {
    this.clients++
    this.activeAt = this.inputs.now()
    this.review()
    let gone = false
    return () => {
      if (gone) return
      gone = true
      this.clients = Math.max(0, this.clients - 1)
      this.review()
    }
  }

  /** Something else that wants the Mac awake while it runs (an automation): on, then off. */
  keep(key: string, on: boolean): void {
    if (on) this.keeps.add(key)
    else this.keeps.delete(key)
    if (on) this.activeAt = this.inputs.now()
    this.review()
  }

  /**
   * Choose a mode (and the battery guard's level). Backpack starts its 8 hours again each time it's
   * chosen; it needs a MacBook and the one-time setup.
   */
  setMode(mode: AwakeMode | undefined, minBattery?: number): { ok: true } | { ok: false; code: string; message: string } {
    if (minBattery !== undefined) {
      if (!Number.isFinite(minBattery) || minBattery < 5 || minBattery > 50) return { ok: false, code: 'bad_battery', message: 'Choose a battery level between 5% and 50%.' }
      this.minBattery = Math.round(minBattery)
    }
    if (mode !== undefined) {
      if (mode === 'backpack') {
        if (!this.laptop) return { ok: false, code: 'no_battery', message: 'Backpack mode is for a MacBook: this Mac has no battery.' }
        if (!this.io.ruleInstalled()) return { ok: false, code: 'needs_setup', message: 'Backpack mode needs a one-time setup on the Mac: open Hypernova in the menu bar and choose Backpack.' }
        this.until = this.inputs.now() + MAX_BACKPACK_MS
        this.timeWarned = false
      } else this.until = null
      if (mode !== this.mode) {
        // A guard counting down was about the old mode; a new mode is worth a new warning.
        this.countdown = null
        this.blocked = null
        this.problem = null
        this.warnedAt = {}
      }
      this.mode = mode
      // Choosing to stay awake starts While working's grace now: an idle Mac isn't let go at once.
      if (mode !== 'off') this.activeAt = this.inputs.now()
    }
    this.save()
    this.review()
    return { ok: true }
  }

  /** Something to hold for right now (While working). */
  private active(): boolean {
    return this.clients > 0 || this.keeps.size > 0 || this.inputs.working()
  }

  /** The guards in the way now, Backpack's hours aside, the battery first. */
  private guards(): SleepReason[] {
    if (this.mode === 'off') return []
    const out: SleepReason[] = []
    const b = this.inputs.battery()
    if (b && !b.onAC && b.percent <= this.minBattery) out.push('battery')
    if (this.mode === 'backpack' && this.readings.hot) out.push('hot')
    return out
  }

  /** [r] has been out of force for [wait], or the Mac is plugged in (for the battery). */
  private cleared(r: SleepReason, now: number, active: SleepReason[], wait: number): boolean {
    if (active.includes(r)) return false
    if (r === 'battery' && this.inputs.battery()?.onAC) return true
    return now - (this.seenAt[r] ?? -Infinity) >= wait
  }

  /** Decide from what's known now: hold or not, Backpack's switch on or off, and tell clients of a change. */
  review(): void {
    const now = this.inputs.now()
    if (this.mode === 'working' && this.active()) this.activeAt = now
    // Backpack's hours are up: back to While working, which a closed lid doesn't keep awake.
    if (this.mode === 'backpack' && this.until != null && now >= this.until) {
      log('awake: Backpack ended after its hours')
      this.mode = 'working'
      this.until = null
      this.countdown = null
      this.blocked = null
      this.problem = null
      this.save()
    }
    // Backpack's permission removed (Settings, an uninstall, by hand): While working again. A
    // SleepDisabled it had switched and can't put back any more stays on record, and shows.
    if (this.mode === 'backpack' && !this.io.ruleInstalled()) {
      log('awake: Backpack’s rule is gone; While working again')
      this.mode = 'working'
      this.until = null
      this.countdown = null
      this.blocked = null
      this.save()
    }
    const wants = this.mode === 'backpack' || (this.mode === 'working' && now - this.activeAt < LINGER_MS)
    const active = this.guards()
    for (const r of active) this.seenAt[r] = now
    // A countdown is called off, and a let-go ends, only once the guard has stayed clear a while
    // (see CLEAR_MS); after the heat, Backpack waits until the Mac has been cool for minutes.
    if (this.countdown && this.cleared(this.countdown.reason, now, active, CLEAR_MS)) this.countdown = null
    if (this.blocked && this.cleared(this.blocked, now, active, this.blocked === 'hot' ? COOL_MS : CLEAR_MS)) this.blocked = null
    if (!this.blocked) {
      if (!this.countdown && active.length) {
        const g = active[0]
        if (this.holding && wants) {
          this.countdown = { reason: g, at: now + WARN_MS }
          // A guard back within minutes of the last one is the same episode: the phone heard already.
          if (now - (this.warnedAt[g] ?? -Infinity) >= REWARN_MS) {
            this.warnedAt[g] = now
            this.warn(g, WARN_MS)
          }
        } else {
          // Nothing held: nothing to warn about, it simply isn't held.
          this.blocked = g
        }
      }
      // A countdown ends in letting go, or at once once there's nothing left to hold.
      if (this.countdown && (now >= this.countdown.at || !wants)) {
        if (wants) log('awake: letting the Mac go:', this.countdown.reason)
        this.blocked = this.countdown.reason
        this.countdown = null
      }
    }
    // Backpack's last two minutes, said once, and only where they end in sleep (the lid closed).
    if (this.endsInBag(now) && !this.timeWarned && !this.blocked) {
      this.timeWarned = true
      this.warn('time', Math.max(0, this.until! - now))
    }
    const hold = wants && !this.blocked
    if (hold !== this.holding) {
      this.holding = hold
      this.io.hold(hold)
    } else if (hold) this.io.hold(true)
    this.switchBackpack(this.mode === 'backpack' && hold)
    this.announce()
    // Ten minutes out, said once per end (after the awake message that says `extendable`) so a phone and the Mac can offer "+2 h".
    if (this.mode === 'backpack' && this.until != null && this.extendable(now) && this.endingTold !== this.until) {
      this.endingTold = this.until
      this.inputs.send({ t: 'mac_backpack_ending', inSeconds: Math.max(0, Math.round((this.until - now) / 1000)), until: this.until, extendBy: EXTEND_BY_MS / 1000 })
    }
  }

  /**
   * {t:"mac_sleep_soon"}. With someone else's SleepDisabled on, letting go won't put the Mac to
   * sleep, so it says so (`stillAwake`, and the app when macOS names it): the owner still wants to
   * know that a Mac in a bag will run on to 0%, but must not be told it's about to sleep.
   */
  private warn(reason: SleepReason, ms: number): void {
    const b = this.inputs.battery()
    const s = this.state()
    this.inputs.send({ t: 'mac_sleep_soon', inSeconds: Math.round(ms / 1000), reason, percent: b ? b.percent : null, stillAwake: s.sleepDisabledBy === 'other', otherApp: s.otherApp })
  }

  private announce(): void {
    const s = this.state()
    const key = JSON.stringify(s)
    if (key === this.last) return
    this.last = key
    this.inputs.send({ t: 'awake', awake: s })
  }

  /**
   * Turn Backpack's lid-closed sleep off ([on]) or put back what was there before. One switch at a
   * time; a review after each catches up with anything that changed meanwhile.
   */
  private switchBackpack(on: boolean): void {
    if (this.toggling) return
    // After a switch that failed, the next try waits a while rather than asking sudo in a loop.
    if (this.inputs.now() - this.failedAt < RETRY_MS) return
    // On: not done yet, or SleepDisabled went back to 0 behind Backpack's back (Amphetamine ending
    // its own session, say), which a closed lid would sleep through.
    let step: (() => Promise<boolean>) | null = null
    if (on && (!this.backpack || this.readings.sleepDisabled === 0)) step = () => this.enterBackpack()
    else if (!on && this.backpack) step = () => this.leaveBackpack()
    if (!step) return
    this.toggling = step()
      .then((ok) => {
        this.failedAt = ok ? -Infinity : this.inputs.now()
      })
      .catch((e) => {
        log('awake: backpack switch', e)
        this.failedAt = this.inputs.now()
      })
      .finally(() => {
        this.toggling = null
        this.review()
      })
  }

  private async enterBackpack(): Promise<boolean> {
    // What's there now, fresh: it's what Backpack puts back. Someone else's 1 (Amphetamine) is left as it is.
    const now = await this.io.sleepDisabled()
    if (now == null) {
      this.problem = 'failed'
      return false
    }
    this.readings.sleepDisabled = now
    if (now === 1) {
      if (!this.backpack) this.backpack = { prev: 1, applied: false }
      this.problem = null
      this.save()
      return true
    }
    // Written down before it's done: a daemon that dies after this restores 0 when it starts again.
    // (Found at 0 though Backpack had set it or found it at 1: either way 0 is what was there.)
    this.backpack = { prev: 0, applied: true }
    this.save()
    const ok = await this.io.setSleepDisabled(1)
    if (!ok) {
      this.backpack = null
      this.save()
      this.problem = this.io.ruleInstalled() ? 'failed' : 'setup'
      return false
    }
    this.problem = null
    this.readings.sleepDisabled = 1
    log('awake: Backpack on (SleepDisabled 1)')
    return true
  }

  private async leaveBackpack(): Promise<boolean> {
    const bp = this.backpack
    if (!bp) return true
    // Already back where it was (put back by the Mac app's removal while the daemon was away, or
    // by hand): nothing to switch.
    if (bp.applied && (await this.io.sleepDisabled()) === bp.prev) {
      log('awake: SleepDisabled already back at', bp.prev)
    } else if (bp.applied) {
      const ok = await this.io.setSleepDisabled(bp.prev)
      if (!ok) {
        // Kept, so a later review tries again (and a restart does).
        this.problem = this.io.ruleInstalled() ? 'failed' : 'setup'
        return false
      }
      this.readings.sleepDisabled = bp.prev
      log('awake: Backpack off (SleepDisabled', bp.prev, 'as before)')
    }
    this.backpack = null
    this.problem = null
    this.save()
    return true
  }

  /** Read what macOS says (the lid, SleepDisabled, the heat), then review. */
  async read(): Promise<void> {
    const r = await this.io.read(this.mode === 'backpack')
    // A switch under way knows better than a reading taken while it ran.
    if (this.toggling) r.sleepDisabled = this.readings.sleepDisabled
    this.readings = r
    this.review()
  }

  /**
   * The daemon is on its way out: let go of the lid-closed setting it made, unless the lid is closed
   * right now. Then this is most likely a restart (launchd brings the daemon back in seconds, and it
   * carries on from awake.json), and putting it back would put a Mac in a bag to sleep at once.
   */
  exit(): void {
    this.io.hold(false)
    if (!this.backpack?.applied || this.readings.lid === true) return
    // A switch still under way (its sudo is a child that outlives the daemon) may land after this:
    // then the record stays, so the next start reads what's really there and puts it back.
    if (this.io.setSleepDisabledSync(this.backpack.prev) && !this.toggling) {
      this.backpack = null
      this.save()
    }
  }

  /** Waits for a pmset switch under way (tests). */
  async settle(): Promise<void> {
    while (this.toggling) await this.toggling
  }
}

// ── The daemon's keeper ───────────────────────────────────────────────────

/** Sessions or their agents at work: starting, working, waiting on an answer mid-turn, or agents running. */
function sessionsWorking(): boolean {
  for (const r of listSessionRecs()) {
    if (r.state === 'starting' || r.state === 'working' || r.state === 'needs_you' || r.agentsRunning > 0) return true
  }
  return false
}

let keeper: AwakeKeeper | null = null
const daemonKeeper = (): AwakeKeeper =>
  (keeper ??= new AwakeKeeper(realIO, { now: Date.now, battery: getBattery, working: sessionsWorking, send: broadcast }, STATE_FILE()))

/**
 * Stay awake on a machine without it (Linux, platform.ts): nothing held, nothing to choose. Old
 * clients read it as Off; new ones hide Stay awake (Snapshot.mac.unavailable.awake says why).
 */
const NOT_HERE: Awake = { mode: 'off', holding: false, lidClosedOk: false, backpackReady: false, until: null, extendable: false, reason: null, sleepAt: null, lidClosed: null, laptop: false, minBattery: DEFAULT_MIN_BATTERY, sleepDisabledBy: null, otherApp: null }

/** `Snapshot.mac.awake`. */
export const awakeState = (): Awake => (has('awake') ? daemonKeeper().state() : { ...NOT_HERE })
/** A phone connected to the live feed; call what it returns when it disconnects. */
export const awakeClient = (): (() => void) => (has('awake') ? daemonKeeper().clientConnected() : () => {})
/** For automations: keep the Mac awake while [key] runs (While working). */
export const keepAwake = (key: string, on: boolean): void => {
  if (has('awake')) daemonKeeper().keep(key, on)
}

/** `POST /v1/mac/awake`: `{mode?, minBattery?}` or `{extend: true}` → the new state, or why not. */
export function setAwake(b: { mode?: unknown; minBattery?: unknown; extend?: unknown }): { ok: true; awake: Awake } | { ok: false; code: string; message: string } {
  if (!has('awake')) return { ok: false, code: 'unavailable', message: UNAVAILABLE.awake ?? LINUX_REASONS.awake }
  if (b.extend === true) {
    const r = daemonKeeper().extend()
    return r.ok ? { ok: true, awake: awakeState() } : r
  }
  const mode = b.mode === undefined ? undefined : b.mode === 'working' || b.mode === 'backpack' || b.mode === 'off' ? b.mode : null
  if (mode === null) return { ok: false, code: 'bad_mode', message: 'Choose working, backpack or off.' }
  const minBattery = b.minBattery === undefined ? undefined : Number(b.minBattery)
  const r = daemonKeeper().setMode(mode, minBattery)
  return r.ok ? { ok: true, awake: awakeState() } : r
}

let timers: NodeJS.Timeout[] = []
/** At start: read macOS now and every 20 s, review every 5 s, and clean up on the way out. */
export function startAwake(): void {
  // No caffeinate, pmset or lid to read on Linux: nothing to start.
  if (timers.length || !has('awake')) return
  const k = daemonKeeper()
  void k.read()
  timers = [setInterval(() => void k.read(), READ_MS), setInterval(() => k.review(), TICK_MS)]
  for (const t of timers) t.unref()
  process.on('exit', () => k.exit())
}
