// When automations are due (schedule.ts): next times for each kind of time trigger, daylight
// saving in Berlin, and what a Mac that slept (or a daemon that was down) catches up on. Pure time
// maths: every test names its own "now" and zone.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { clockText, dueBetween, inWindow, localDay, localTime, nextOccurrence, occurrencesBetween, parseHm, type TimeTrigger } from '../src/schedule.ts'

const MSK = 'Europe/Moscow' // UTC+3 all year
const BER = 'Europe/Berlin'
/** A Moscow wall time as an instant: "2026-10-16T09:00" → epoch ms. */
const msk = (s: string) => Date.parse(`${s}:00+03:00`)
const iso = (t: number | undefined) => (t == null ? null : new Date(t).toISOString())
const WEEKDAYS = [1, 2, 3, 4, 5]
const MIN = 60_000
const HOUR = 3600_000

test('times of day: "HH:MM" only', () => {
  assert.deepEqual(parseHm('09:00'), { h: 9, m: 0 })
  assert.deepEqual(parseHm('23:59'), { h: 23, m: 59 })
  for (const bad of ['9:00', '24:00', '12:60', '', null, 900]) assert.equal(parseHm(bad), null, String(bad))
})

test('every day, weekdays and chosen days: the next time after now', () => {
  // Friday 16 Oct 2026, 10:00 in Moscow.
  const now = msk('2026-10-16T10:00')
  const daily: TimeTrigger = { kind: 'time', at: '09:00', days: [1, 2, 3, 4, 5, 6, 7] }
  assert.equal(nextOccurrence(daily, now, MSK)?.at, msk('2026-10-17T09:00'))
  assert.equal(nextOccurrence(daily, now, MSK)?.key, '2026-10-17T09:00')
  // Weekdays: Monday.
  assert.equal(nextOccurrence({ kind: 'time', at: '09:00', days: WEEKDAYS }, now, MSK)?.at, msk('2026-10-19T09:00'))
  // Later today.
  assert.equal(nextOccurrence({ kind: 'time', at: '18:30', days: WEEKDAYS }, now, MSK)?.at, msk('2026-10-16T18:30'))
  // Mondays and Thursdays only, from a Friday.
  assert.equal(nextOccurrence({ kind: 'time', at: '07:15', days: [1, 4] }, now, MSK)?.at, msk('2026-10-19T07:15'))
  assert.equal(nextOccurrence({ kind: 'time', at: '07:15', days: [4] }, now, MSK)?.at, msk('2026-10-22T07:15'))
  // Exactly at a due time: the next one, not this one.
  assert.equal(nextOccurrence(daily, msk('2026-10-17T09:00'), MSK)?.at, msk('2026-10-18T09:00'))
})

test('once: its own time, and nothing after it', () => {
  const at = msk('2026-10-16T15:30')
  const once: TimeTrigger = { kind: 'once', at }
  assert.equal(nextOccurrence(once, msk('2026-10-16T10:00'), MSK)?.at, at)
  assert.equal(nextOccurrence(once, at, MSK), null)
  assert.deepEqual(occurrencesBetween(once, at - MIN, at, MSK), [{ at, key: `once:${at}` }])
})

test('every N hours: from, up to and including to; around the clock; past midnight; on its days', () => {
  const from = msk('2026-10-16T00:00')
  const to = msk('2026-10-17T00:00')
  // 9:00 to 19:00 every 2 hours, on Friday.
  const day = occurrencesBetween({ kind: 'every', hours: 2, from: '09:00', to: '19:00', days: [5] }, from, to, MSK)
  assert.deepEqual(day.map((o) => o.key), ['09', '11', '13', '15', '17', '19'].map((h) => `2026-10-16T${h}:00`))
  // Not on Saturday.
  assert.deepEqual(occurrencesBetween({ kind: 'every', hours: 2, from: '09:00', to: '19:00', days: [6] }, from, to, MSK), [])
  // Around the clock from 9:00 every 4 hours: the same grid all day, 1:00 included.
  const clock = occurrencesBetween({ kind: 'every', hours: 4, from: '09:00', to: null, days: [5] }, from, to, MSK)
  assert.deepEqual(clock.map((o) => o.key.slice(11)), ['01:00', '05:00', '09:00', '13:00', '17:00', '21:00'])
  // 22:00 to 02:00 every 2 hours on Friday: the window runs into Saturday.
  const night = occurrencesBetween({ kind: 'every', hours: 2, from: '22:00', to: '02:00', days: [5] }, from, msk('2026-10-18T00:00'), MSK)
  assert.deepEqual(night.map((o) => o.key), ['2026-10-16T22:00', '2026-10-17T00:00', '2026-10-17T02:00'])
  // Every hour: next after 10:20 is 11:00.
  assert.equal(nextOccurrence({ kind: 'every', hours: 1, from: '09:00', to: '19:00', days: WEEKDAYS }, msk('2026-10-16T10:20'), MSK)?.at, msk('2026-10-16T11:00'))
  // After the last of the day: Monday's first.
  assert.equal(nextOccurrence({ kind: 'every', hours: 1, from: '09:00', to: '19:00', days: WEEKDAYS }, msk('2026-10-16T19:00'), MSK)?.at, msk('2026-10-19T09:00'))
})

test('daylight saving in Berlin: a skipped 02:30 runs at 03:00; a repeated one runs the first time', () => {
  // Spring: 29 Mar 2026, 02:00 CET jumps to 03:00 CEST.
  assert.equal(iso(localTime(2026, 3, 29, 2, 30, BER)), '2026-03-29T01:00:00.000Z')
  const daily: TimeTrigger = { kind: 'time', at: '02:30', days: [1, 2, 3, 4, 5, 6, 7] }
  const spring = nextOccurrence(daily, Date.parse('2026-03-28T12:00:00Z'), BER)
  assert.equal(iso(spring?.at), '2026-03-29T01:00:00.000Z')
  assert.equal(spring?.key, '2026-03-29T02:30')
  // Autumn: 25 Oct 2026, 03:00 CEST goes back to 02:00 CET; 02:30 happens twice.
  assert.equal(iso(localTime(2026, 10, 25, 2, 30, BER)), '2026-10-25T00:30:00.000Z')
  const autumn = occurrencesBetween(daily, Date.parse('2026-10-24T12:00:00Z'), Date.parse('2026-10-25T12:00:00Z'), BER)
  assert.deepEqual(autumn.map((o) => iso(o.at)), ['2026-10-25T00:30:00.000Z'])
  // An ordinary day either side.
  assert.equal(iso(localTime(2026, 10, 26, 2, 30, BER)), '2026-10-26T01:30:00.000Z')
  assert.equal(clockText(Date.parse('2026-10-25T01:30:00Z'), BER), '2:30')
  assert.equal(localDay(Date.parse('2026-10-25T23:30:00Z'), BER), '2026-10-26')
})

test('catch-up: asleep 40 minutes runs late; 7 hours is missed', () => {
  const weekdays: TimeTrigger = { kind: 'time', at: '09:00', days: WEEKDAYS }
  // Asleep from 8:00, woke 9:40 on a Friday: runs now, 40 minutes late.
  const woke = dueBetween(weekdays, msk('2026-10-16T08:00'), msk('2026-10-16T09:40'), MSK)
  assert.equal(woke.kind, 'fire')
  if (woke.kind === 'fire') {
    assert.equal(woke.occ.at, msk('2026-10-16T09:00'))
    assert.equal(woke.late, 40 * MIN)
    assert.equal(woke.missed, 0)
  }
  // Woke at 16:00: more than 6 hours late.
  const late = dueBetween(weekdays, msk('2026-10-16T08:00'), msk('2026-10-16T16:00'), MSK)
  assert.equal(late.kind, 'missed')
  if (late.kind === 'missed') assert.equal(late.missed, 1)
  // On time (the timer looks every 30 s at most): no lateness.
  const onTime = dueBetween(weekdays, msk('2026-10-16T08:59'), msk('2026-10-16T09:00') + 20_000, MSK)
  assert.equal(onTime.kind === 'fire' && onTime.late, 0)
  // Nothing due.
  assert.equal(dueBetween(weekdays, msk('2026-10-16T09:01'), msk('2026-10-16T12:00'), MSK).kind, 'none')
  // An occurrence already handled (its key) isn't due again, after a clock change.
  assert.equal(dueBetween(weekdays, msk('2026-10-16T08:00'), msk('2026-10-16T09:10'), MSK, '2026-10-16T09:00').kind, 'none')
})

test('catch-up: three days asleep is one missed row; a short sleep after it runs once with the others folded in', () => {
  const daily: TimeTrigger = { kind: 'time', at: '09:00', days: [1, 2, 3, 4, 5, 6, 7] }
  // Asleep from Friday evening to Monday 16:00: Sat, Sun and Mon 9:00 missed.
  const weekend = dueBetween(daily, msk('2026-10-16T20:00'), msk('2026-10-19T16:00'), MSK)
  assert.deepEqual(weekend.kind === 'missed' && weekend.missed, 3)
  // Woke Monday 10:00 instead: runs Monday's, late an hour, with two missed.
  const monday = dueBetween(daily, msk('2026-10-16T20:00'), msk('2026-10-19T10:00'), MSK)
  assert.equal(monday.kind, 'fire')
  if (monday.kind === 'fire') {
    assert.equal(monday.occ.key, '2026-10-19T09:00')
    assert.equal(monday.late, HOUR)
    assert.equal(monday.missed, 2)
  }
})

test('catch-up: every hour after 3 hours asleep; never when the next time is within 15 minutes', () => {
  const hourly: TimeTrigger = { kind: 'every', hours: 1, from: '00:00', to: null, days: [1, 2, 3, 4, 5, 6, 7] }
  // Asleep 8:30 to 11:40: 9, 10 and 11 due; 11:00 runs 40 minutes late, two folded in.
  const woke = dueBetween(hourly, msk('2026-10-16T08:30'), msk('2026-10-16T11:40'), MSK)
  assert.equal(woke.kind, 'fire')
  if (woke.kind === 'fire') {
    assert.equal(woke.occ.key, '2026-10-16T11:00')
    assert.equal(woke.late, 40 * MIN)
    assert.equal(woke.missed, 2)
  }
  // Woke 11:50: 12:00 is 10 minutes away, so these are missed and 12:00 runs as usual.
  const soon = dueBetween(hourly, msk('2026-10-16T08:30'), msk('2026-10-16T11:50'), MSK)
  assert.deepEqual(soon.kind === 'missed' && soon.missed, 3)
  // The last of the day, 40 minutes late: within its hour, so it runs, the rest folded in.
  const period = dueBetween({ kind: 'every', hours: 1, from: '09:00', to: '19:00', days: WEEKDAYS }, msk('2026-10-16T09:30'), msk('2026-10-16T19:40'), MSK)
  assert.equal(period.kind, 'fire')
  // An hour and a half late: longer than its period, missed (not run into the evening).
  const longer = dueBetween({ kind: 'every', hours: 1, from: '09:00', to: '10:00', days: WEEKDAYS }, msk('2026-10-16T08:30'), msk('2026-10-16T11:30'), MSK)
  assert.equal(longer.kind, 'missed', '10:00 is 90 minutes ago, longer than its hour')
})

test('a once missed while the Mac was off: within 6 hours it runs, else it is missed', () => {
  const at = msk('2026-10-16T09:00')
  const run = dueBetween({ kind: 'once', at }, msk('2026-10-16T08:00'), msk('2026-10-16T14:00'), MSK)
  assert.equal(run.kind === 'fire' && run.late, 5 * HOUR)
  assert.equal(dueBetween({ kind: 'once', at }, msk('2026-10-16T08:00'), msk('2026-10-16T15:30'), MSK).kind, 'missed')
})

test('a time window: between from and to, past midnight too', () => {
  const night = (s: string) => inWindow(msk(s), parseHm('00:00'), parseHm('07:00'), MSK)
  assert.equal(night('2026-10-16T03:10'), true)
  assert.equal(night('2026-10-16T07:00'), false)
  assert.equal(night('2026-10-16T23:59'), false)
  const late = (s: string) => inWindow(msk(s), parseHm('22:00'), parseHm('06:00'), MSK)
  assert.equal(late('2026-10-16T23:00'), true)
  assert.equal(late('2026-10-16T05:59'), true)
  assert.equal(late('2026-10-16T12:00'), false)
  assert.equal(inWindow(msk('2026-10-16T12:00'), null, null, MSK), true)
})
