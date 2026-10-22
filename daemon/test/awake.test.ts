// Stay awake (docs/PROTOCOL.md, "Stay awake"): what macOS prints, read; the assertion held while
// there's work or a phone, and let go after; the guards and their two-minute warning; Backpack
// putting back the SleepDisabled it found; and the API, against stand-ins for sudo, pmset, ioreg
// and caffeinate (never the real ones: nothing here can change this Mac's sleep).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-awake-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })

// ── Stand-ins for the system's programs ──────────────────────────────────
// sudo runs nothing: it writes down what it was asked and, for the one command the rule allows,
// sets the stand-in SleepDisabled that the stand-in pmset reports. Asked to list (`-n -l`), it
// says yes while Hypernova's rule file, or another app's (Amphetamine's), is there. A SleepDisabled
// of "none" is a Mac where nobody ever set it: pmset leaves the line out.
const bin = join(scratch, 'bin')
mkdirSync(bin)
const sleepFile = join(scratch, 'sleepdisabled')
const sudoLog = join(scratch, 'sudo.log')
const caffeinateLog = join(scratch, 'caffeinate.log')
const lidFile = join(scratch, 'lid')
const otherRule = join(scratch, 'amphetamine_PowerProtect')
const script = (name: string, body: string) => {
  const p = join(bin, name)
  writeFileSync(p, '#!/bin/sh\n' + body)
  chmodSync(p, 0o755)
  return p
}
writeFileSync(sleepFile, '1')
writeFileSync(lidFile, 'No')
process.env.HYPERNOVA_SUDO = script('sudo', `if [ "$1" = "-n" ] && [ "$2" = "-l" ]; then if [ -f '${join(scratch, 'sudoers.d', 'hypernova')}' ] || [ -f '${otherRule}' ]; then exit 0; fi; exit 1; fi
echo "$*" >> '${sudoLog}'
if [ "$1" = "-n" ] && [ "$2" = "/usr/bin/pmset" ] && [ "$3" = "-a" ] && [ "$4" = "disablesleep" ]; then printf '%s' "$5" > '${sleepFile}'; exit 0; fi
exit 1
`)
process.env.HYPERNOVA_PMSET = script('pmset', `if [ "$1" = "-g" ] && [ -z "$2" ] && [ "$(cat '${sleepFile}')" = none ]; then printf 'System-wide power settings:\\nCurrently in use:\\n standby              1\\n sleep                1\\n'; exit 0; fi
if [ "$1" = "-g" ] && [ -z "$2" ]; then printf 'System-wide power settings:\\n SleepDisabled\\t\\t%s\\nCurrently in use:\\n sleep                1\\n' "$(cat '${sleepFile}')"; exit 0; fi
if [ "$2" = "therm" ]; then printf 'Note: No thermal warning level has been recorded\\nNote: No performance warning level has been recorded\\nNote: No CPU power status has been recorded\\n'; exit 0; fi
exit 1
`)
process.env.HYPERNOVA_IOREG = script('ioreg', `printf '+-o IOPMrootDomain  <class IOPMrootDomain>\\n    {\\n      "AppleClamshellCausesSleep" = Yes\\n      "AppleClamshellState" = %s\\n    }\\n' "$(cat '${lidFile}')"\n`)
process.env.HYPERNOVA_NOTIFYUTIL = script('notifyutil', `echo "com.apple.system.thermalpressurelevel 0"\n`)
// Stays while the process named after -w lives, as the real one does.
process.env.HYPERNOVA_CAFFEINATE = script('caffeinate', `echo "$*" >> '${caffeinateLog}'
while [ $# -gt 0 ]; do if [ "$1" = "-w" ]; then W=$2; fi; shift; done
while kill -0 "$W" 2>/dev/null; do sleep 1; done
`)
const rule = join(scratch, 'sudoers.d', 'hypernova')
process.env.HYPERNOVA_SUDOERS = rule

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
process.env.HYPERNOVA_API_PORT = String(await freePort())
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'

const awake = await import('../src/awake.ts')
const { AwakeKeeper, parseSleepDisabled, parseOtherApp, parseLid, parsePressure, parseTherm, LINGER_MS, WARN_MS, MAX_BACKPACK_MS, EXTEND_WINDOW_MS, EXTEND_BY_MS, CLEAR_MS, COOL_MS, REWARN_MS } = awake
type Readings = import('../src/awake.ts').Readings
type Battery = import('../src/battery.ts').Battery

after(() => rmSync(scratch, { recursive: true, force: true }))

// ── What macOS prints ─────────────────────────────────────────────────────

// `pmset -g` on the owner's MacBook (macOS 26) with Amphetamine's SleepDisabled on.
const PMSET_G = `System-wide power settings:
 SleepDisabled\t\t1
Currently in use:
 standby              1
 Sleep On Power Button 1
 hibernatefile        /var/vm/sleepimage
 powernap             1
 networkoversleep     0
 disksleep            10
 sleep                1 (sleep prevented by Amphetamine, caffeinate)
 hibernatemode        3
 ttyskeepawake        1
 displaysleep         1
 tcpkeepalive         1
 lowpowermode         0
 womp                 1
`

// `pmset -g` on a Mac where nothing ever set SleepDisabled (no Amphetamine, no `pmset
// disablesleep`): the system-wide part is empty, as recorded in the review of Stay awake.
const PMSET_G_FRESH = `System-wide power settings:
Currently in use:
 standby              1
 Sleep On Power Button 1
 hibernatefile        /var/vm/sleepimage
 powernap             1
 networkoversleep     0
 disksleep            10
 sleep                1
 hibernatemode        3
 ttyskeepawake        1
 displaysleep         10
 tcpkeepalive         1
 lowpowermode         0
 womp                 1
`

test('SleepDisabled from pmset -g', () => {
  assert.equal(parseSleepDisabled(PMSET_G), 1)
  assert.equal(parseSleepDisabled(PMSET_G.replace('SleepDisabled\t\t1', 'SleepDisabled\t\t0')), 0)
  // macOS lists it only once someone has set it: unlisted is its default, 0.
  assert.equal(parseSleepDisabled(PMSET_G_FRESH), 0)
  assert.equal(parseSleepDisabled(PMSET_G_FRESH.replace('System-wide power settings:\n', '')), 0)
  // Not pmset's output at all: can't tell.
  assert.equal(parseSleepDisabled(''), null)
  assert.equal(parseSleepDisabled('pmset: command not found\n'), null)
})

test('the app keeping the Mac awake, from pmset -g', () => {
  assert.equal(parseOtherApp(PMSET_G), 'Amphetamine')
  assert.equal(parseOtherApp(PMSET_G_FRESH), null)
  // Hypernova's own caffeinate and macOS's daemons aren't another app.
  assert.equal(parseOtherApp(' sleep                1 (sleep prevented by caffeinate, powerd)\n'), null)
  assert.equal(parseOtherApp(' sleep                1 (sleep prevented by coreaudiod, Hypernova, Lungo)\n'), 'Lungo')
})

test('the lid from ioreg, and a Mac without one', () => {
  // `ioreg -r -k AppleClamshellState -d 1` on the owner's MacBook, cut to the lines around it.
  const out = (state: string) =>
    '+-o IOPMrootDomain  <class IOPMrootDomain, id 0x1000002c3, registered, matched, active, busy 0 (125 ms), retain 191>\n' +
    '    {\n      "Wake Type" = "UserActivity Assertion"\n' +
    `      "AppleClamshellCausesSleep" = Yes\n      "AppleClamshellState" = ${state}\n    }\n`
  assert.equal(parseLid(out('Yes')), true)
  assert.equal(parseLid(out('No')), false)
  // A Mac mini: no IOPMrootDomain carries the key, so ioreg prints nothing.
  assert.equal(parseLid(''), null)
})

test('heat: the pressure level, and pmset -g therm', () => {
  assert.equal(parsePressure('com.apple.system.thermalpressurelevel 0\n'), 0)
  assert.equal(parsePressure('com.apple.system.thermalpressurelevel 2\n'), 2)
  assert.equal(parsePressure(''), null)
  // Apple silicon (the owner's): nothing recorded, never hot.
  const none = 'Note: No thermal warning level has been recorded\nNote: No performance warning level has been recorded\nNote: No CPU power status has been recorded\n'
  assert.equal(parseTherm(none), false)
  // An Intel Mac, cool and then held back by heat.
  const cpu = (limit: number) => `Note: No thermal warning level has been recorded\nNote: No performance warning level has been recorded\n2026-10-16 01:58:42 +0300 CPU Power notify\n\tCPU_Scheduler_Limit \t= 100\n\tCPU_Available_CPUs \t= 8\n\tCPU_Speed_Limit \t= ${limit}\n`
  assert.equal(parseTherm(cpu(100)), false)
  assert.equal(parseTherm(cpu(85)), false)
  assert.equal(parseTherm(cpu(50)), true)
  // A thermal warning level: danger (5) and up counts, normal (0) and unknown (255) don't.
  assert.equal(parseTherm('Thermal Warning Level = 5\n'), true)
  assert.equal(parseTherm('Thermal_Level\t\t= 10\n'), true)
  assert.equal(parseTherm('Thermal Warning Level = 0\n'), false)
  assert.equal(parseTherm('Thermal Warning Level = 255\n'), false)
})

// ── The keeper, on a stand-in system ─────────────────────────────────────

class FakeIO {
  sleepDisabled_: 0 | 1 | null = 0
  lid: boolean | null = false
  hot = false
  otherApp: string | null = null
  rule = true
  sudoWorks = true
  held = false
  calls: Array<0 | 1> = []
  syncCalls: Array<0 | 1> = []
  async read(): Promise<Readings> {
    return { sleepDisabled: this.sleepDisabled_, lid: this.lid, hot: this.hot, otherApp: this.otherApp }
  }
  async sleepDisabled() {
    return this.sleepDisabled_
  }
  async setSleepDisabled(v: 0 | 1) {
    this.calls.push(v)
    if (!this.sudoWorks || !this.rule) return false
    this.sleepDisabled_ = v
    return true
  }
  setSleepDisabledSync(v: 0 | 1) {
    this.syncCalls.push(v)
    if (!this.sudoWorks || !this.rule) return false
    this.sleepDisabled_ = v
    return true
  }
  hold(on: boolean) {
    this.held = on
  }
  ruleInstalled() {
    return this.rule
  }
}

function setup(o: { battery?: Battery | null; file?: string; io?: FakeIO } = {}) {
  const io = o.io ?? new FakeIO()
  const sent: any[] = []
  const world = { now: 1_000_000, battery: o.battery === undefined ? ({ percent: 80, charging: false, onAC: false, minutesLeft: 300 } as Battery | null) : o.battery, working: false }
  const k = new AwakeKeeper(io, { now: () => world.now, battery: () => world.battery, working: () => world.working, send: (m) => sent.push(m) }, o.file ?? null)
  const warnings = () => sent.filter((m) => m.t === 'mac_sleep_soon')
  const states = () => sent.filter((m) => m.t === 'awake').map((m) => m.awake)
  return { io, k, world, sent, warnings, states }
}

test('While working: held while a session works, a phone is connected or an automation runs, and 3 minutes after', async () => {
  const { io, k, world, states } = setup()
  await k.read()
  assert.equal(k.state().mode, 'working')
  assert.equal(io.held, false, 'nothing to stay awake for')
  assert.equal(states().at(-1).holding, false)

  world.working = true
  k.review()
  assert.equal(io.held, true, 'a session works')
  world.working = false
  world.now += LINGER_MS - 1000
  k.review()
  assert.equal(io.held, true, 'still within the grace')
  world.now += 2000
  k.review()
  assert.equal(io.held, false, 'let go 3 minutes after the work')

  const bye = k.clientConnected()
  assert.equal(io.held, true, 'a phone connected')
  world.now += 60 * 60_000
  k.review()
  assert.equal(io.held, true, 'for as long as it stays')
  bye()
  bye()
  assert.equal(io.held, true, 'a socket blinking during a network change keeps it')
  const again = k.clientConnected()
  again()
  world.now += LINGER_MS + 1
  k.review()
  assert.equal(io.held, false, 'the phone gone, after the grace')

  k.keep('automation:nightly', true)
  assert.equal(io.held, true, 'an automation runs')
  k.keep('automation:nightly', false)
  world.now += LINGER_MS + 1
  k.review()
  assert.equal(io.held, false)
  assert.deepEqual(io.calls, [], 'While working never touches SleepDisabled')
})

test('Off holds nothing, whatever runs', async () => {
  const { io, k, world } = setup()
  await k.read()
  assert.deepEqual(k.setMode('off'), { ok: true })
  world.working = true
  k.clientConnected()
  k.review()
  assert.equal(io.held, false)
  assert.equal(k.state().holding, false)
  assert.equal(k.state().mode, 'off')
})

test('the battery guard: two minutes of warning, then let go; plugged in, held again', async () => {
  const { io, k, world, warnings, states } = setup({ battery: { percent: 16, charging: false, onAC: false, minutesLeft: 30 } })
  await k.read()
  world.working = true
  k.review()
  assert.equal(io.held, true)
  assert.equal(warnings().length, 0, 'at 16%: nothing yet')

  world.battery = { percent: 15, charging: false, onAC: false, minutesLeft: 28 }
  k.review()
  assert.deepEqual(warnings(), [{ t: 'mac_sleep_soon', inSeconds: 120, reason: 'battery', percent: 15, stillAwake: false, otherApp: null }])
  assert.equal(io.held, true, 'still held while the warning runs')
  const s = states().at(-1)
  assert.equal(s.reason, 'battery')
  assert.equal(s.sleepAt, world.now + WARN_MS)
  world.now += 60_000
  k.review()
  assert.equal(warnings().length, 1, 'said once')
  world.now += 60_000
  k.review()
  assert.equal(io.held, false, 'let go at the end of the two minutes')
  assert.equal(k.state().reason, 'battery')
  assert.equal(k.state().sleepAt, null)
  assert.equal(k.state().holding, false)

  world.battery = { percent: 14, charging: true, onAC: true, minutesLeft: 90 }
  k.review()
  assert.equal(io.held, true, 'plugged in: held again')
  assert.equal(k.state().reason, null)
})

test('the battery guard is called off when the Mac is plugged in during the warning, and its level is adjustable', async () => {
  const { io, k, world, warnings } = setup({ battery: { percent: 20, charging: false, onAC: false, minutesLeft: 40 } })
  await k.read()
  world.working = true
  k.review()
  assert.deepEqual(k.setMode(undefined, 25), { ok: true })
  assert.equal(warnings().length, 1, 'at 20% with the level at 25%')
  assert.equal(k.state().minBattery, 25)
  world.battery = { percent: 20, charging: true, onAC: true, minutesLeft: 60 }
  world.now += 30_000
  k.review()
  assert.equal(k.state().reason, null)
  assert.equal(k.state().sleepAt, null)
  world.now += 5 * 60_000
  k.review()
  assert.equal(io.held, true, 'never let go')
  assert.deepEqual(k.setMode(undefined, 70), { ok: false, code: 'bad_battery', message: 'Choose a battery level between 5% and 50%.' })
})

test('a guard already in force when there is nothing to hold: no warning, and it stays let go', async () => {
  const { io, k, world, warnings } = setup({ battery: { percent: 9, charging: false, onAC: false, minutesLeft: 12 } })
  await k.read()
  k.review()
  world.working = true
  k.review()
  assert.equal(io.held, false)
  assert.equal(warnings().length, 0)
  assert.equal(k.state().reason, 'battery')
})

test('a desktop Mac: no battery guard, no Backpack', async () => {
  const io = new FakeIO()
  io.lid = null
  io.rule = true
  const { k, world } = setup({ battery: null, io })
  await k.read()
  assert.equal(k.state().laptop, false)
  assert.equal(k.state().backpackReady, false)
  assert.deepEqual(k.setMode('backpack'), { ok: false, code: 'no_battery', message: 'Backpack mode is for a MacBook: this Mac has no battery.' })
  world.working = true
  k.review()
  assert.equal(k.state().holding, true)
})

test('Backpack needs the one-time setup', async () => {
  const io = new FakeIO()
  io.rule = false
  const { k } = setup({ io })
  await k.read()
  assert.equal(k.state().backpackReady, false)
  const r = k.setMode('backpack')
  assert.equal(r.ok, false)
  assert.equal(!r.ok && r.code, 'needs_setup')
})

test('Backpack: SleepDisabled on while it lasts, then 0 again as it was', async () => {
  const io = new FakeIO()
  io.sleepDisabled_ = 0
  const { k, world, states } = setup({ io })
  await k.read()
  assert.equal(k.state().lidClosedOk, false)
  assert.deepEqual(k.setMode('backpack'), { ok: true })
  await k.settle()
  assert.deepEqual(io.calls, [1])
  assert.equal(io.held, true, 'held with nothing running: that is Backpack')
  const s = states().at(-1)
  assert.equal(s.mode, 'backpack')
  assert.equal(s.lidClosedOk, true)
  assert.equal(s.until, world.now + MAX_BACKPACK_MS)
  assert.equal(s.holding, true)

  k.setMode('working')
  await k.settle()
  assert.deepEqual(io.calls, [1, 0], 'put back to 0')
  assert.equal(k.state().lidClosedOk, false)
  assert.equal(k.state().until, null)
})

test('Backpack leaves an owner’s own SleepDisabled (Amphetamine) as it found it', async () => {
  const io = new FakeIO()
  io.sleepDisabled_ = 1
  const { k } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  assert.equal(k.state().lidClosedOk, true)
  k.setMode('off')
  await k.settle()
  assert.deepEqual(io.calls, [], 'never switched: it was 1 before and is 1 after')
  assert.equal(io.sleepDisabled_, 1)
})

test('Backpack takes over when the owner’s SleepDisabled goes off during it, and puts 0 back after', async () => {
  const io = new FakeIO()
  io.sleepDisabled_ = 1
  const { k } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  // Amphetamine's own session ended and it turned it off.
  io.sleepDisabled_ = 0
  await k.read()
  await k.settle()
  assert.deepEqual(io.calls, [1])
  k.setMode('working')
  await k.settle()
  assert.deepEqual(io.calls, [1, 0])
})

test('Backpack’s hours: warned two minutes before, then back to While working', async () => {
  const io = new FakeIO()
  io.lid = true
  const { k, world, warnings } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  world.now += MAX_BACKPACK_MS - WARN_MS
  k.review()
  assert.deepEqual(warnings(), [{ t: 'mac_sleep_soon', inSeconds: 120, reason: 'time', percent: 80, stillAwake: false, otherApp: null }])
  assert.equal(k.state().reason, 'time')
  assert.equal(k.state().sleepAt, k.state().until)
  world.now += WARN_MS
  k.review()
  await k.settle()
  assert.equal(k.state().mode, 'working')
  assert.equal(k.state().until, null)
  assert.deepEqual(io.calls, [1, 0])
  assert.equal(warnings().length, 1)
})

test('Backpack’s hours with the lid open: no sleep promised, since While working carries on', async () => {
  const io = new FakeIO()
  io.lid = false
  const { k, world, warnings } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  world.now += MAX_BACKPACK_MS - WARN_MS
  k.review()
  assert.equal(warnings().length, 0)
  assert.equal(k.state().sleepAt, null)
  // The lid closes in the last minute: now it would sleep, and it's said, with the time left.
  world.now += 60_000
  io.lid = true
  await k.read()
  assert.deepEqual(warnings().map((w) => [w.reason, w.inSeconds]), [['time', 60]])
  assert.equal(k.state().sleepAt, k.state().until)
})

test('Backpack’s extend: offered in the last 10 minutes (said once), +2 h each time, refused earlier and outside Backpack', async () => {
  const io = new FakeIO()
  io.lid = false
  const { k, world, sent, states } = setup({ io })
  await k.read()
  assert.deepEqual(k.extend(), { ok: false, code: 'not_backpack', message: 'Backpack isn’t on, so there is nothing to extend.' })
  k.setMode('backpack')
  await k.settle()
  const end = k.state().until!
  assert.equal(k.state().extendable, false, 'hours away: nothing to offer')
  assert.equal((k.extend() as any).code, 'too_early')
  const ending = () => sent.filter((m) => m.t === 'mac_backpack_ending')
  world.now = end - EXTEND_WINDOW_MS - 1000
  k.review()
  assert.equal(ending().length, 0)
  world.now = end - EXTEND_WINDOW_MS + 1000
  k.review()
  assert.equal(k.state().extendable, true)
  assert.equal(states().at(-1).extendable, true, 'the awake message carries it, for the Mac popover')
  assert.deepEqual(ending().map((m) => [m.inSeconds, m.until, m.extendBy]), [[Math.round(EXTEND_WINDOW_MS / 1000) - 1, end, 7200]])
  k.review()
  assert.equal(ending().length, 1, 'said once per end')
  assert.deepEqual(k.extend(), { ok: true })
  assert.equal(k.state().until, end + EXTEND_BY_MS)
  assert.equal(k.state().extendable, false, 'a new end hours away')
  assert.equal(k.state().mode, 'backpack')
  // Again, when that end comes near: another 2 h, and it is said again.
  world.now = end + EXTEND_BY_MS - 60_000
  k.review()
  assert.equal(ending().length, 2)
  assert.deepEqual(k.extend(), { ok: true })
  assert.equal(k.state().until, end + 2 * EXTEND_BY_MS)
  // After the end it's over: nothing to extend.
  world.now = end + 2 * EXTEND_BY_MS + 1
  k.review()
  assert.equal((k.extend() as any).code, 'not_backpack')
})

test('Backpack’s extend doesn’t lift the guards: the battery still lets the Mac go', async () => {
  const io = new FakeIO()
  io.lid = false
  const { k, world } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  world.now = k.state().until! - 5 * 60_000
  k.review()
  assert.deepEqual(k.extend(), { ok: true })
  world.battery = { percent: 10, charging: false, onAC: false, minutesLeft: 20 }
  k.review()
  assert.equal(k.state().reason, 'battery')
  assert.notEqual(k.state().sleepAt, null, 'the battery guard counts down all the same')
  world.now += WARN_MS + 1000
  k.review()
  assert.equal(k.state().holding, false, 'and lets go')
})

test('Backpack and heat: let go two minutes after macOS reports heavy thermal pressure, held again once cool', async () => {
  const io = new FakeIO()
  const { k, world, warnings } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  io.hot = true
  await k.read()
  assert.deepEqual(warnings().map((w) => w.reason), ['hot'])
  world.now += WARN_MS
  k.review()
  await k.settle()
  assert.equal(k.state().holding, false)
  assert.equal(k.state().reason, 'hot')
  assert.deepEqual(io.calls, [1, 0], 'SleepDisabled put back: a closed lid sleeps now')
  assert.equal(k.state().mode, 'backpack', 'still Backpack: it carries on once cool')
  io.hot = false
  await k.read()
  await k.settle()
  assert.equal(k.state().holding, false, 'cool for a moment is not cool yet')
  world.now += COOL_MS
  await k.read()
  await k.settle()
  assert.equal(k.state().holding, true)
  assert.equal(k.state().reason, null)
  assert.deepEqual(io.calls, [1, 0, 1])
})

test('heat swinging between heavy and moderate: one warning, and the Mac is let go all the same', async () => {
  const io = new FakeIO()
  io.lid = true
  const { k, world, warnings } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  // Thirty minutes of throttling in a bag: hot, then not, on each 20 s read.
  for (let i = 0; i < 90; i++) {
    io.hot = i % 2 === 0
    await k.read()
    await k.settle()
    world.now += 20_000
  }
  assert.equal(warnings().length, 1, 'one heads-up for the episode')
  assert.equal(k.state().holding, false, 'let go two minutes in, and kept let go while it swings')
  assert.equal(k.state().reason, 'hot')
  assert.equal(io.sleepDisabled_, 0, 'a closed lid sleeps now')
  assert.deepEqual(io.calls, [1, 0])
})

test('a guard that clears for less than a minute keeps its countdown; one that comes back soon after isn’t warned twice', async () => {
  const io = new FakeIO()
  const { k, world, warnings } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  io.hot = true
  await k.read()
  const at = k.state().sleepAt
  world.now += 30_000
  io.hot = false
  await k.read()
  assert.equal(k.state().sleepAt, at, 'clear for 30 s: still counting down')
  world.now += CLEAR_MS
  k.review()
  assert.equal(k.state().sleepAt, null, 'clear for a minute: called off')
  assert.equal(k.state().holding, true)
  world.now += 60_000
  io.hot = true
  await k.read()
  assert.ok(k.state().sleepAt, 'counting down again')
  assert.equal(warnings().length, 1, 'not warned again within minutes')
  // A new episode, much later, is warned about.
  io.hot = false
  await k.read()
  world.now += REWARN_MS
  k.review()
  io.hot = true
  await k.read()
  assert.equal(warnings().length, 2)
})

test('someone else’s SleepDisabled (Amphetamine): the warning says the Mac stays awake rather than promising sleep', async () => {
  const io = new FakeIO()
  io.sleepDisabled_ = 1
  io.otherApp = 'Amphetamine'
  io.lid = true
  const { k, world, warnings } = setup({ io, battery: { percent: 40, charging: false, onAC: false, minutesLeft: 90 } })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  assert.equal(k.state().sleepDisabledBy, 'other')
  assert.equal(k.state().otherApp, 'Amphetamine')
  world.battery = { percent: 15, charging: false, onAC: false, minutesLeft: 25 }
  k.review()
  assert.deepEqual(warnings(), [{ t: 'mac_sleep_soon', inSeconds: 120, reason: 'battery', percent: 15, stillAwake: true, otherApp: 'Amphetamine' }])
  world.now += WARN_MS
  k.review()
  await k.settle()
  assert.equal(k.state().holding, false)
  assert.equal(k.state().sleepDisabledBy, 'other', 'still on: the Mac stays awake, and clients say so')
  assert.deepEqual(io.calls, [], 'never Hypernova’s to switch')

  // Backpack's own: it's put back when Backpack lets go, so the Mac does sleep.
  const mine = new FakeIO()
  mine.sleepDisabled_ = 0
  const b = setup({ io: mine })
  await b.k.read()
  b.k.setMode('backpack')
  await b.k.settle()
  assert.equal(b.k.state().sleepDisabledBy, 'backpack')
  assert.equal(b.k.state().otherApp, null)
})

test('heat doesn’t count While working', async () => {
  const io = new FakeIO()
  io.hot = true
  const { k, world, warnings } = setup({ io })
  await k.read()
  world.working = true
  k.review()
  assert.equal(k.state().holding, true)
  assert.equal(warnings().length, 0)
})

test('a sudo that fails: said, and tried again only after a while', async () => {
  const io = new FakeIO()
  io.sudoWorks = false
  const { k, world } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  assert.equal(k.state().reason, 'failed')
  assert.equal(k.state().lidClosedOk, false)
  for (let i = 0; i < 5; i++) k.review()
  await k.settle()
  assert.deepEqual(io.calls, [1], 'no loop')
  io.sudoWorks = true
  world.now += 31_000
  k.review()
  await k.settle()
  assert.deepEqual(io.calls, [1, 1])
  assert.equal(k.state().reason, null)
})

test('a restart finds what Backpack set and undoes it once Backpack is over', async () => {
  const file = join(scratch, 'restart', 'awake.json')
  const io = new FakeIO()
  const a = setup({ io, file })
  await a.k.read()
  a.k.setMode('backpack')
  await a.k.settle()
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')).backpack, { prev: 0, applied: true })
  // The daemon dies in the backpack (no exit handler) and comes back after the 8 hours.
  const b = setup({ io, file })
  b.world.now = a.world.now + MAX_BACKPACK_MS + 60_000
  await b.k.read()
  await b.k.settle()
  assert.deepEqual(io.calls, [1, 0])
  assert.equal(b.k.state().mode, 'working')
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).backpack, null)

  // Back within the hours: it carries on, and doesn't switch again.
  const c = setup({ io, file })
  await c.k.read()
  c.k.setMode('backpack')
  await c.k.settle()
  const d = setup({ io, file })
  d.world.now = c.world.now + 3600_000
  await d.k.read()
  await d.k.settle()
  assert.equal(d.k.state().mode, 'backpack')
  assert.equal(d.k.state().holding, true)
  assert.deepEqual(io.calls, [1, 0, 1])
})

test('Backpack’s permission removed while it lasts: While working again, and what it switched shows until it can be put back', async () => {
  const io = new FakeIO()
  const { k, world } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  io.rule = false
  k.review()
  await k.settle()
  assert.equal(k.state().mode, 'working')
  assert.equal(k.state().reason, 'setup', 'SleepDisabled is still 1 and nothing can switch it back')
  assert.deepEqual(io.calls, [1, 0])
  // The rule comes back (set up again): it's put back on the next try.
  io.rule = true
  world.now += 31_000
  k.review()
  await k.settle()
  assert.deepEqual(io.calls, [1, 0, 0])
  assert.equal(io.sleepDisabled_, 0)
  assert.equal(k.state().reason, null)
})

test('a record of what Backpack switched, already put back by someone else: settled without sudo', async () => {
  const file = join(scratch, 'settled', 'awake.json')
  mkdirSync(join(scratch, 'settled'), { recursive: true })
  writeFileSync(file, JSON.stringify({ mode: 'backpack', minBattery: 15, until: 0, backpack: { prev: 0, applied: true } }))
  const io = new FakeIO()
  io.sleepDisabled_ = 0
  const { k } = setup({ io, file })
  await k.read()
  await k.settle()
  assert.deepEqual(io.calls, [])
  assert.equal(k.state().mode, 'working')
  assert.equal(k.state().reason, null)
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).backpack, null)
})

test('on the way out: SleepDisabled put back with the lid open, left with it closed (a restart in a bag)', async () => {
  const io = new FakeIO()
  const { k } = setup({ io })
  await k.read()
  k.setMode('backpack')
  await k.settle()
  io.lid = true
  await k.read()
  k.exit()
  assert.deepEqual(io.syncCalls, [])
  assert.equal(io.held, false)
  io.lid = false
  await k.read()
  k.exit()
  assert.deepEqual(io.syncCalls, [0])
})

test('on the way out with a switch still under way: the record stays for the next start', async () => {
  const file = join(scratch, 'exit-race', 'awake.json')
  const io = new FakeIO()
  // A sudo that hasn't answered yet when the daemon exits.
  let land: (ok: boolean) => void = () => {}
  io.setSleepDisabled = (v: 0 | 1) => {
    io.calls.push(v)
    return new Promise<boolean>((r) => (land = r))
  }
  const { k } = setup({ io, file })
  await k.read()
  k.setMode('backpack')
  await new Promise((r) => setTimeout(r, 0))
  k.exit()
  assert.deepEqual(io.syncCalls, [0])
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')).backpack, { prev: 0, applied: true }, 'kept: the late 1 may still land')
  land(true)
})

// ── The API, on the daemon's own servers ──────────────────────────────────

const { startServers } = await import('../src/http.ts')
const { addDevice } = await import('../src/store.ts')
const hub = await import('../src/hub.ts')
const TOKEN = 'awake-test-token-0123456789abcdef0123'
addDevice({ id: 'phone', name: 'Phone', model: 'Test', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const API = Number(process.env.HYPERNOVA_API_PORT)

function call(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { authorization: `Bearer ${TOKEN}`, ...(data ? { 'content-type': 'application/json' } : {}) } }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: s ? JSON.parse(s) : null }))
    })
    req.on('error', reject)
    req.end(data)
  })
}
const until = async (ok: () => boolean, ms = 4000) => {
  const end = Date.now() + ms
  while (!ok() && Date.now() < end) await new Promise((r) => setTimeout(r, 25))
  return ok()
}

test('the API: GET and POST /v1/mac/awake, the snapshot, the awake message, and Backpack through sudo', async () => {
  const close = startServers()
  const sent: any[] = []
  const remove = hub.addClient({ send: (s: string) => sent.push(JSON.parse(s)) })
  try {
    for (let i = 0; i < 50 && (await call('GET', '/v1/health').catch(() => null))?.status !== 200; i++) await new Promise((r) => setTimeout(r, 20))
    writeFileSync(sleepFile, '0')
    awake.startAwake()
    // The first read of the stand-in lid makes it a MacBook.
    assert.ok(await until(() => awake.awakeState().laptop), 'a MacBook')

    const got = await call('GET', '/v1/mac/awake')
    assert.equal(got.status, 200)
    assert.equal(got.json.awake.mode, 'working')
    assert.equal(got.json.awake.backpackReady, false)
    assert.equal(got.json.awake.lidClosed, false)
    const snap = await call('GET', '/v1/snapshot')
    assert.equal(snap.json.mac.awake.mode, 'working')

    assert.equal((await call('POST', '/v1/mac/awake', { mode: 'sideways' })).status, 400)
    const early = await call('POST', '/v1/mac/awake', { mode: 'backpack' })
    assert.equal(early.status, 409)
    assert.equal(early.json.error.code, 'needs_setup')

    // The Mac app's one-time setup puts the rule in place.
    mkdirSync(join(scratch, 'sudoers.d'), { recursive: true })
    writeFileSync(rule, '# stand-in\n')
    const on = await call('POST', '/v1/mac/awake', { mode: 'backpack' })
    assert.equal(on.status, 200)
    assert.equal(on.json.awake.mode, 'backpack')
    assert.ok(on.json.awake.until > Date.now() + MAX_BACKPACK_MS - 60_000)
    assert.ok(await until(() => readFileSync(sleepFile, 'utf8') === '1'), 'SleepDisabled on through sudo')
    assert.match(readFileSync(sudoLog, 'utf8'), /^-n \/usr\/bin\/pmset -a disablesleep 1$/m)
    assert.ok(await until(() => existsSync(caffeinateLog)), 'caffeinate started')
    assert.match(readFileSync(caffeinateLog, 'utf8'), new RegExp(`^-i -s -w ${process.pid}$`, 'm'))
    assert.ok(await until(() => sent.some((m) => m.t === 'awake' && m.awake.mode === 'backpack' && m.awake.lidClosedOk)), '{t:"awake"} told clients')

    // Far from its end, "+2 h" is refused; the route answers 409 (the rest is the keeper's test).
    const soon = await call('POST', '/v1/mac/awake', { extend: true })
    assert.equal(soon.status, 409)
    assert.equal(soon.json.error.code, 'too_early')
    const off = await call('POST', '/v1/mac/awake', { mode: 'working', minBattery: 20 })
    assert.equal(off.status, 200)
    assert.equal(off.json.awake.minBattery, 20)
    assert.ok(await until(() => readFileSync(sleepFile, 'utf8') === '0'), 'put back to 0')
    assert.deepEqual(readFileSync(sudoLog, 'utf8').trim().split('\n'), ['-n /usr/bin/pmset -a disablesleep 1', '-n /usr/bin/pmset -a disablesleep 0'])
    assert.equal((await call('POST', '/v1/mac/awake', { minBattery: 3 })).json.error.code, 'bad_battery')
    assert.ok(existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'awake.json')), 'kept for the next start')
    await call('POST', '/v1/mac/awake', { mode: 'off' })
  } finally {
    remove()
    await close()
  }
})

test('a Mac where SleepDisabled was never set (no line in pmset -g): Backpack sets 1 and puts 0 back', async () => {
  // The rule is in place from the test above; the stand-ins answer as such a Mac would.
  writeFileSync(sleepFile, 'none')
  writeFileSync(sudoLog, '')
  const file = join(scratch, 'fresh', 'awake.json')
  const io = { ...awake.realIO, hold() {} }
  const k = new AwakeKeeper(io, { now: Date.now, battery: () => ({ percent: 80, charging: false, onAC: false, minutesLeft: 300 }), working: () => false, send: () => {} }, file)
  await k.read()
  assert.equal(k.state().lidClosedOk, false)
  assert.equal(k.state().sleepDisabledBy, null)
  assert.deepEqual(k.setMode('backpack'), { ok: true })
  await k.settle()
  assert.equal(k.state().reason, null, 'not "failed"')
  assert.equal(readFileSync(sleepFile, 'utf8'), '1')
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')).backpack, { prev: 0, applied: true })
  assert.equal(k.state().sleepDisabledBy, 'backpack')
  k.setMode('working')
  await k.settle()
  assert.equal(readFileSync(sleepFile, 'utf8'), '0')
  assert.deepEqual(readFileSync(sudoLog, 'utf8').trim().split('\n'), ['-n /usr/bin/pmset -a disablesleep 1', '-n /usr/bin/pmset -a disablesleep 0'])
})

test('Backpack is ready when sudo already allows its command (Amphetamine’s rule), without Hypernova’s own', async () => {
  rmSync(rule)
  assert.equal(awake.realIO.ruleInstalled(), false, 'the file just went: believed at once')
  assert.ok(await until(() => !awake.realIO.ruleInstalled() && existsSync(rule) === false), 'sudo agrees')
  writeFileSync(otherRule, '# stand-in\n')
  // sudo is asked again after a while, or as soon as Hypernova's file comes or goes.
  writeFileSync(rule, '# stand-in\n')
  awake.realIO.ruleInstalled()
  rmSync(rule)
  assert.ok(await until(() => awake.realIO.ruleInstalled()), 'sudo says the command is allowed')
  rmSync(otherRule)
})
