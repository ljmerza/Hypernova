// Carrying on after signing in, across a daemon restart: what resume.json kept comes back, so one
// that already carried on doesn't again, one still waiting its moment does once, and a sign-in the
// daemon was down for is noticed. A file of its own, so the daemon starts with the files kept.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = mkdtempSync(join(tmpdir(), 'hn-login-restart-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
mkdirSync(process.env.HYPERNOVA_SUPPORT, { recursive: true, mode: 0o700 })
const fakeState = join(scratch, 'fake')
mkdirSync(fakeState)
writeFileSync(join(fakeState, 'signed-in'), '')
process.env.FAKE_CLAUDE_STATE = fakeState
const fake = join(scratch, 'claude-bin')
copyFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-claude.sh'), fake)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

const t0 = Date.now()
const entry = (sid: string, o: object) => ({
  sid, providerId: 'anthropic', since: t0 - 600_000, kind: 'logged_out', text: 'Please run /login', resetsAt: null, state: 'hidden', at: null,
  by: 'hypernova', why: null, rearms: 0, createdAt: t0 - 600_000, noticed: false, noticeAt: 0, firedAt: null, liftedAt: null, login: true, ...o,
})
// The account is back; one carried on before the restart, one was waiting its moment, one was
// waiting for a sign-in that came while the daemon was down, one already says to Continue.
writeFileSync(
  join(process.env.HYPERNOVA_SUPPORT, 'account.json'),
  JSON.stringify({ account: { state: 'ok', providerId: 'anthropic', plan: 'max', limit: null, reason: null, text: null, since: null, sessionId: null, checkedAt: null }, okAt: t0 - 30_000, replyAt: 0, clearedAt: t0 - 30_000 }),
)
writeFileSync(
  join(process.env.HYPERNOVA_SUPPORT, 'resume.json'),
  JSON.stringify([
    entry('fired', { state: 'fired', firedAt: t0 - 20_000 }),
    entry('armed', { state: 'armed', at: t0 - 1000 }),
    entry('missed', {}),
    entry('ready', { state: 'ready', at: t0 - 20_000, liftedAt: t0 - 20_000 }),
  ]),
)

const { CONTINUE_TEXT } = await import('../src/blockers.ts')
const hub = await import('../src/hub.ts')
const store = await import('../src/store.ts')
const resume = await import('../src/resume.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

let now = t0
resume.useClock(() => now, () => 0.5)
const sent: string[] = []
resume.useDeliver(async (s, text) => {
  assert.equal(text, CONTINUE_TEXT.login)
  sent.push(s.id)
  return 'ok'
})
for (const id of ['fired', 'armed', 'missed', 'ready']) {
  store.saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: t0, engine: 'sdk' })
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', providerId: 'anthropic', state: 'idle' })
}

test('after a restart each carries on at most once, and what already did does not', async () => {
  await resume.tick()
  now += 120_000
  await resume.tick()
  await resume.tick()
  assert.deepEqual(sent.sort(), ['armed', 'missed'])
  assert.equal(hub.listSessions().find((s) => s.id === 'ready')?.blocked?.resume?.state, 'ready')
  now += 120_000
  await resume.tick()
  assert.equal(sent.length, 2)
})
