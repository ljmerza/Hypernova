// A `claude agents` job that died (job-deaths.ts, IDEAS NT4): which list reads say so, once.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-deaths-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
const { DeathWatch, OWNER_MS, RESPAWN_HOLD_MS, deathReason } = await import('../src/job-deaths.ts')
after(() => rmSync(scratch, { recursive: true, force: true }))

const seen = (state: string, o: { reason?: string | null; respawning?: boolean; job?: string } = {}) => ({
  job: o.job ?? 'j1',
  sessionId: `s-${o.job ?? 'j1'}`,
  state,
  reason: o.reason ?? null,
  respawning: o.respawning ?? false,
})

test('working, then failed: one death; failed again: none', () => {
  const w = new DeathWatch(null)
  assert.deepEqual(w.see([seen('working')], 1000), [])
  const d = w.see([seen('failed', { reason: 'exit 1' })], 2000)
  assert.deepEqual(d, [{ job: 'j1', sessionId: 's-j1', reason: 'exit 1', at: 2000 }])
  assert.deepEqual(w.see([seen('failed', { reason: 'exit 1' })], 3000), [])
})

test('done, stopped and blocked are never deaths', () => {
  const w = new DeathWatch(null)
  w.see([seen('working')], 0)
  for (const s of ['done', 'stopped', 'blocked', 'done']) assert.deepEqual(w.see([seen(s)], 1), [])
})

test('a job first seen already failed says nothing (an old death)', () => {
  const w = new DeathWatch(null)
  assert.deepEqual(w.see([seen('failed')], 0), [])
  assert.deepEqual(w.see([seen('failed')], 1), [])
})

test('a restart of the daemon: an old death isn’t said again; one while it was down is', () => {
  const file = join(scratch, 'support', 'job-states-1.json')
  const before = new DeathWatch(file)
  before.see([seen('working', { job: 'a' }), seen('working', { job: 'b' })], 0)
  assert.equal(before.see([seen('failed', { job: 'a' }), seen('working', { job: 'b' })], 1).length, 1)
  // The daemon restarts; b dies while it is down.
  const again = new DeathWatch(file)
  const d = again.see([seen('failed', { job: 'a' }), seen('failed', { job: 'b', reason: 'process gone while supervisor was down' })], 2)
  assert.deepEqual(d.map((x) => x.job), ['b'])
  assert.deepEqual(new DeathWatch(file).see([seen('failed', { job: 'a' }), seen('failed', { job: 'b' })], 3), [])
})

test('the owner stopped or deleted it from the phone: not a death, even when it ends failed', () => {
  const w = new DeathWatch(null)
  w.see([seen('working')], 0)
  w.ownerActed('j1', 1000)
  assert.deepEqual(w.see([seen('failed')], 1000 + OWNER_MS - 1), [])
  // Long after, a new life and a death of its own counts.
  w.see([seen('working')], 1000 + OWNER_MS + 1)
  assert.equal(w.see([seen('failed')], 1000 + OWNER_MS + 2).length, 1)
})

test('a death Claude Code is respawning waits: back at work, nothing; still dead after the hold, said once', () => {
  const w = new DeathWatch(null)
  w.see([seen('working')], 0)
  assert.deepEqual(w.see([seen('failed', { respawning: true })], 10), [])
  assert.deepEqual(w.see([seen('working')], 20), [], 'it came back')
  assert.deepEqual(w.see([seen('failed', { respawning: true })], 100), [])
  assert.deepEqual(w.see([seen('failed', { respawning: true })], 100 + RESPAWN_HOLD_MS - 1), [])
  assert.equal(w.see([seen('failed', { respawning: true })], 100 + RESPAWN_HOLD_MS).length, 1)
  assert.deepEqual(w.see([seen('failed', { respawning: true })], 200 + RESPAWN_HOLD_MS), [])
})

test('a job gone from the list (deleted) is forgotten: seen again failed, it is an old one', () => {
  const w = new DeathWatch(null)
  w.see([seen('working')], 0)
  assert.deepEqual(w.see([], 1), [])
  assert.deepEqual(w.see([seen('failed')], 2), [])
})

test('deathReason: one short line, no "; respawning", the bare word "failed" is no reason', () => {
  assert.equal(deathReason('exit 1 — boom; respawning'), 'exit 1 — boom')
  assert.equal(deathReason('\n  SIGKILL (137)\nmore'), 'SIGKILL (137)')
  assert.equal(deathReason('', 'API error'), 'API error')
  assert.equal(deathReason('failed'), null)
  assert.equal(deathReason(undefined), null)
  assert.ok(deathReason('x'.repeat(300))!.length <= 121)
})
