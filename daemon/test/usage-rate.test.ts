// The spend rate of the 5-hour and weekly windows: a least-squares pace over recent readings.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { recordReading, rateFor, resetRateHistory } from '../src/usage-rate.ts'

const MIN = 60_000
const now = 1_800_000_000_000
const reset5 = now + 3 * 3600_000
beforeEach(() => resetRateHistory())

test('steady spend gives the pace, and no estimate of when the limit comes', () => {
  // 0.8 %/min, fractional readings; 12 readings over 11 minutes, now at 62%.
  for (let i = 0; i <= 11; i++) recordReading('five', 53.2 + 0.8 * i, reset5, now - (11 - i) * MIN)
  const r = rateFor(true, { pct: 62, resetsAt: reset5 }, null, now)!
  assert.equal(r.working, true)
  assert.ok(Math.abs(r.fiveHour! - 0.8) < 0.01)
  assert.deepEqual(Object.keys(r).sort(), ['fiveHour', 'sevenDay', 'working'])
  assert.equal(r.sevenDay, null)
})

test('idle readings give a zero pace; not working gives no rate at all', () => {
  for (let i = 0; i <= 8; i++) recordReading('five', 40, reset5, now - (8 - i) * MIN)
  const r = rateFor(true, { pct: 40, resetsAt: reset5 }, null, now)!
  assert.equal(r.fiveHour, 0)
  assert.equal(rateFor(false, { pct: 40, resetsAt: reset5 }, null, now), null)
})

test('a reset in between starts the history over', () => {
  for (let i = 0; i <= 8; i++) recordReading('five', 80 + i, now - 2 * MIN, now - (10 - i) * MIN)
  // New window: two readings only.
  recordReading('five', 1, reset5, now - MIN)
  recordReading('five', 2, reset5, now)
  const r = rateFor(true, { pct: 2, resetsAt: reset5 }, null, now)!
  assert.equal(r.fiveHour, null)
})

test('whole-percent steps still give a sensible pace', () => {
  // 1 % every 2 minutes = 0.5 %/min
  for (let i = 0; i <= 6; i++) recordReading('five', 30 + i, reset5, now - (12 - 2 * i) * MIN)
  const r = rateFor(true, { pct: 36, resetsAt: reset5 }, null, now)!
  assert.ok(Math.abs(r.fiveHour! - 0.5) < 0.06, String(r.fiveHour))
})

test('too few readings, or too short a span, give null', () => {
  recordReading('five', 10, reset5, now - 5 * MIN)
  recordReading('five', 12, reset5, now)
  assert.equal(rateFor(true, { pct: 12, resetsAt: reset5 }, null, now)!.fiveHour, null)
  resetRateHistory()
  for (let i = 0; i < 5; i++) recordReading('five', 10 + i, reset5, now - (4 - i) * 30_000) // 2 minutes
  assert.equal(rateFor(true, { pct: 14, resetsAt: reset5 }, null, now)!.fiveHour, null)
})

test('forced limit readings are ignored', () => {
  for (let i = 0; i <= 10; i++) recordReading('five', 10 + 0.1 * i, now + 30 * MIN, now - (10 - i) * MIN)
  const r = rateFor(true, { pct: 11, resetsAt: now + 30 * MIN }, null, now)!
  assert.ok(r.fiveHour! > 0)
  recordReading('five', 100, now + 30 * MIN, now, true)
  assert.ok(rateFor(true, { pct: 11, resetsAt: now + 30 * MIN }, null, now)!.fiveHour! > 0)
})

test('the week uses a longer stretch', () => {
  const reset7 = now + 4 * 24 * 3600_000
  for (let i = 0; i <= 6; i++) recordReading('seven', 14 + 0.05 * 5 * i, reset7, now - (30 - 5 * i) * MIN)
  const r = rateFor(true, null, { pct: 15.5, resetsAt: reset7 }, now)!
  assert.ok(Math.abs(r.sevenDay! - 0.05) < 0.01)
})
