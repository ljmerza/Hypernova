// The Mac's battery (docs/PROTOCOL.md, "Battery"): `pmset -g batt` and `ioreg -rn
// AppleSmartBattery` as macOS prints them on a laptop and on a desktop, read into
// Snapshot.mac.battery, and {t:"battery"} only when clients would show something new.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-battery-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { parsePmset, parseIoreg, batteryNews, getBattery, noteBattery, readBattery, steadyBattery, steadyMinutes } = await import('../src/battery.ts')
const hub = await import('../src/hub.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

const sent: any[] = []
hub.addClient({ send: (s: string) => sent.push(JSON.parse(s)) })
const batteryMessages = () => sent.filter((m) => m.t === 'battery')

// `pmset -g batt` as macOS 26 prints it (a tab before the percentage).
const PMSET = {
  discharging: "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=7209059)\t84%; discharging; 5:12 remaining present: true\n",
  estimating: "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=7209059)\t84%; discharging; (no estimate) present: true\n",
  charging: "Now drawing from 'AC Power'\n -InternalBattery-0 (id=7209059)\t56%; charging; 1:23 remaining present: true\n",
  finishing: "Now drawing from 'AC Power'\n -InternalBattery-0 (id=7209059)\t98%; finishing charge; 0:09 remaining present: true\n",
  charged: "Now drawing from 'AC Power'\n -InternalBattery-0 (id=7209059)\t100%; charged; 0:00 remaining present: true\n",
  // Optimized charging holding it at 80%: on the adapter, not charging.
  held: "Now drawing from 'AC Power'\n -InternalBattery-0 (id=7209059)\t80%; AC attached; not charging present: true\n",
  // A Mac mini, iMac, Mac Studio or Mac Pro.
  desktop: "Now drawing from 'AC Power'\n",
  // A desktop on a UPS: the UPS's battery isn't the Mac's.
  ups: "Now drawing from 'AC Power'\n -CP1500PFCLCD (id=12345)\t100%; charged; 0:00 remaining present: true\n",
  noBattery: "Now drawing from 'AC Power'\nNo battery found\n",
  removed: "Now drawing from 'AC Power'\n -InternalBattery-0 (id=7209059)\t0%; AC attached; not charging present: false\n",
}

// `ioreg -rn AppleSmartBattery` on the owner's MacBook (macOS 26), cut to the lines around the
// two estimates; the nested dictionaries hold keys too, written without spaces, which never count.
const ioreg = (toEmpty: number, toFull: number) =>
  '+-o AppleSmartBattery  <class AppleSmartBattery, id 0x100000975, registered, matched, active, busy 0 (4 ms), retain 7>\n' +
  '    {\n' +
  '      "CurrentCapacity" = 27\n' +
  '      "TimeRemaining" = 152\n' +
  `      "AvgTimeToFull" = ${toFull}\n` +
  '      "ExternalConnected" = No\n' +
  '      "BatteryData" = {"Ra03"=55,"AvgTimeToEmpty"=9999,"StateOfCharge"=27,"LifetimeData"={"UpdateTime"=1792094296}}\n' +
  '      "BatteryInstalled" = Yes\n' +
  '      "FullyCharged" = No\n' +
  '      "AdapterDetails" = {"FamilyCode"=0}\n' +
  '      "MaxCapacity" = 100\n' +
  '      "InstantAmperage" = 18446744073709551066\n' +
  `      "AvgTimeToEmpty" = ${toEmpty}\n` +
  '      "IsCharging" = No\n' +
  '    }\n'

// Recorded on the owner's MacBook (macOS 26) on the evening of 15 October, while it ran from 28% down
// to 22%: pmset said "20:00 remaining" all along. The battery's own estimate went with the load,
// minute by minute at 28% (about 3 W): 373, 464, 402, 346, then 1532 at a quiet moment; at 27% and
// 5.6 W, 152. The charge itself fell about 6% an hour then (four to five hours from 28%), and a
// third of a percent a minute in the afternoon's heavier work (an hour and a half from 28%).
const STUCK = "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=7209059)\t28%; discharging; 20:00 remaining present: true\n"
const RECORDED = [373, 464, 402, 346, 1532]

test("macOS 26's pmset sits at its 20-hour cap: the time comes from the battery's own estimate", () => {
  assert.deepEqual(parsePmset(STUCK, parseIoreg(ioreg(402, 65535))), { percent: 28, charging: false, onAC: false, minutesLeft: 402 })
  const stuck27 = STUCK.replace('28%', '27%')
  assert.deepEqual(parsePmset(stuck27, parseIoreg(ioreg(152, 65535))), { percent: 27, charging: false, onAC: false, minutesLeft: 152 })
  // pmset's alone: its cap is no estimate.
  assert.deepEqual(parsePmset(STUCK), { percent: 28, charging: false, onAC: false, minutesLeft: null })
  // Just unplugged, the battery hasn't averaged yet (65535): no time, whatever pmset says.
  assert.equal(parsePmset(PMSET.discharging, parseIoreg(ioreg(65535, 65535)))?.minutesLeft, null)
  // Charging: until full, by its own estimate.
  assert.deepEqual(parsePmset(PMSET.charging, parseIoreg(ioreg(65535, 47))), { percent: 56, charging: true, onAC: true, minutesLeft: 47 })
  assert.equal(parsePmset(PMSET.charging, parseIoreg(ioreg(65535, 65535)))?.minutesLeft, null)
  // Full, or held by optimized charging: no time, whatever the battery says.
  assert.equal(parsePmset(PMSET.charged, parseIoreg(ioreg(65535, 0)))?.minutesLeft, null)
  assert.equal(parsePmset(PMSET.held, parseIoreg(ioreg(300, 120)))?.minutesLeft, null)
  // The keys are read where ioreg puts them, never from inside a nested dictionary.
  assert.deepEqual(parseIoreg(ioreg(152, 65535)), { toEmpty: 152, toFull: null })
  // A desktop has no AppleSmartBattery, so ioreg prints nothing.
  assert.equal(parseIoreg(''), null)
  assert.equal(parsePmset(PMSET.desktop, parseIoreg('')), null)
})

test("the battery's own estimate is steadied: its pace over the last five minutes, started over on a change", () => {
  const t0 = 1_000_000
  const at28 = (minutes: number) => ({ percent: 28, charging: false, onAC: false, minutesLeft: minutes })
  const steady = (minutes: number, at: number) => steadyBattery(at28(minutes), true, at)?.minutesLeft
  // The recorded minutes, a minute apart: the quiet minute's 1532 moves the average a little, not
  // to 25 hours. Averaged as a pace (time left is charge over current), so busy minutes count.
  const got = RECORDED.map((m, i) => steady(m, t0 + i * 60_000))
  assert.deepEqual(got, [373, 414, 410, 392, 460])
  // Five minutes on, the first has dropped out.
  assert.equal(steady(402, t0 + 5 * 60_000), Math.round(1 / ((1 / 464 + 1 / 402 + 1 / 346 + 1 / 1532 + 1 / 402) / 5)))
  // After a gap (the Mac slept) it starts over. A heavier load then pulls the time down, not all
  // the way at once: 20% at 0.1% a minute, then 19% at 0.2%, is 19% at 0.15%.
  assert.equal(steadyBattery({ ...at28(200), percent: 20 }, true, t0 + 30 * 60_000)?.minutesLeft, 200)
  assert.equal(steadyBattery({ ...at28(95), percent: 19 }, true, t0 + 31 * 60_000)?.minutesLeft, 127)
  // Plugged in: the charging estimates start over; the percent to go is up to full.
  assert.equal(steadyBattery({ percent: 30, charging: true, onAC: true, minutesLeft: 90 }, true, t0 + 32 * 60_000)?.minutesLeft, 90)
  assert.equal(steadyBattery({ percent: 31, charging: true, onAC: true, minutesLeft: 70 }, true, t0 + 33 * 60_000)?.minutesLeft, 78)
  assert.equal(steadyBattery({ percent: 60, charging: true, onAC: true, minutesLeft: 40 }, true, t0 + 60 * 60_000)?.minutesLeft, 40)
  // Charging up to full: 1% to go at the end, then none.
  assert.equal(steadyBattery({ percent: 99, charging: true, onAC: true, minutesLeft: 1 }, true, t0 + 61 * 60_000)?.minutesLeft, 1)
  // pmset's own time is macOS's average already: as it is, and the battery's average starts over.
  assert.equal(steadyBattery(at28(250), false, t0 + 62 * 60_000)?.minutesLeft, 250)
  assert.equal(steady(400, t0 + 63 * 60_000), 400)
  // No time (just unplugged, full) or no battery: nothing to average, and it starts over.
  assert.equal(steadyBattery({ ...at28(400), minutesLeft: null }, true, t0 + 64 * 60_000)?.minutesLeft, null)
  assert.equal(steady(100, t0 + 65 * 60_000), 100)
  assert.equal(steadyBattery(null, false, t0 + 66 * 60_000), null)
  assert.deepEqual(steadyMinutes([]), null)
})

test('pmset on a laptop: the percentage, charging, the power source and the time left', () => {
  assert.deepEqual(parsePmset(PMSET.discharging), { percent: 84, charging: false, onAC: false, minutesLeft: 312 })
  assert.deepEqual(parsePmset(PMSET.estimating), { percent: 84, charging: false, onAC: false, minutesLeft: null })
  assert.deepEqual(parsePmset(PMSET.charging), { percent: 56, charging: true, onAC: true, minutesLeft: 83 })
  assert.deepEqual(parsePmset(PMSET.finishing), { percent: 98, charging: true, onAC: true, minutesLeft: 9 })
  assert.deepEqual(parsePmset(PMSET.charged), { percent: 100, charging: false, onAC: true, minutesLeft: null })
  assert.deepEqual(parsePmset(PMSET.held), { percent: 80, charging: false, onAC: true, minutesLeft: null })
  // Spaces where the tab was.
  assert.deepEqual(parsePmset(PMSET.discharging.replace('\t', '  ')), { percent: 84, charging: false, onAC: false, minutesLeft: 312 })
  // Its cap, and anything past it, isn't an estimate.
  assert.equal(parsePmset(PMSET.discharging.replace('5:12', '20:00'))?.minutesLeft, null)
  assert.equal(parsePmset(PMSET.discharging.replace('5:12', '19:59'))?.minutesLeft, 1199)
})

test('pmset on a Mac without a battery of its own: null, so nothing shows', () => {
  for (const [name, out] of Object.entries({ desktop: PMSET.desktop, ups: PMSET.ups, noBattery: PMSET.noBattery, removed: PMSET.removed, empty: '' })) {
    assert.equal(parsePmset(out), null, name)
  }
})

test('clients hear of a percentage, charging or the power source changing, not of the minutes left', () => {
  const laptop = { percent: 84, charging: false, onAC: false, minutesLeft: 312 }
  assert.equal(batteryNews(null, null), false)
  assert.equal(batteryNews(null, laptop), true)
  assert.equal(batteryNews(laptop, null), true)
  assert.equal(batteryNews(laptop, { ...laptop }), false)
  assert.equal(batteryNews(laptop, { ...laptop, minutesLeft: 300 }), false)
  assert.equal(batteryNews(laptop, { ...laptop, percent: 83 }), true)
  assert.equal(batteryNews(laptop, { ...laptop, onAC: true }), true)
  assert.equal(batteryNews(laptop, { ...laptop, onAC: true, charging: true }), true)
})

test('readings: the snapshot has the latest, {t:"battery"} goes out only with news, a failed read changes nothing', async () => {
  assert.equal(getBattery(), null)
  const read = (out: string | null, times: string | null = null) => readBattery(async () => ({ pmset: out, ioreg: times }))
  await read(PMSET.discharging)
  assert.deepEqual(batteryMessages(), [{ t: 'battery', battery: { percent: 84, charging: false, onAC: false, minutesLeft: 312 } }])
  // The minutes alone: kept for the snapshot, not sent.
  await read(PMSET.discharging.replace('5:12', '5:01'))
  assert.equal(getBattery()?.minutesLeft, 301)
  assert.equal(batteryMessages().length, 1)
  // pmset couldn't say (a timeout): the last reading stands.
  await read(null)
  assert.equal(getBattery()?.percent, 84)
  assert.equal(batteryMessages().length, 1)
  // The battery's own average, when ioreg has one, over pmset's.
  await read(PMSET.discharging, ioreg(140, 65535))
  assert.equal(getBattery()?.minutesLeft, 140)
  // Steadied: a minute twice as busy brings it to 84% at 0.9% a minute, not to the 70 it says.
  await read(PMSET.discharging, ioreg(70, 65535))
  assert.equal(getBattery()?.minutesLeft, 93)
  assert.equal(batteryMessages().length, 1)
  await read(PMSET.charging)
  assert.deepEqual(batteryMessages().at(-1), { t: 'battery', battery: { percent: 56, charging: true, onAC: true, minutesLeft: 83 } })
  noteBattery(null)
  assert.deepEqual(batteryMessages().at(-1), { t: 'battery', battery: null })
  // A desktop reads null again and again: nothing to send.
  await read(PMSET.desktop)
  await read(PMSET.desktop)
  assert.equal(batteryMessages().length, 3)
})

test("this Mac's own pmset and ioreg, run without blocking: a battery or null, never an error", async () => {
  noteBattery(null)
  await readBattery()
  const b = getBattery()
  if (b) {
    assert.ok(Number.isInteger(b.percent) && b.percent >= 0 && b.percent <= 100)
    assert.equal(typeof b.charging, 'boolean')
    assert.equal(typeof b.onAC, 'boolean')
    assert.ok(b.minutesLeft === null || (Number.isInteger(b.minutesLeft) && b.minutesLeft > 0))
  }
})
