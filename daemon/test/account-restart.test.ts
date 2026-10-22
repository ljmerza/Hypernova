// The account after a daemon restart (docs/PROTOCOL.md, "Account"): what account.json kept comes
// back as it was. A file of its own, so the daemon starts fresh with a kept account.json, as it
// does after a restart: the usage meter empty, the limit remembered.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = mkdtempSync(join(tmpdir(), 'hn-acct-restart-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR]) mkdirSync(d, { recursive: true })
mkdirSync(process.env.HYPERNOVA_SUPPORT, { recursive: true, mode: 0o700 })
// Claude Code is a stand-in, signed in on Max: the check at start finds nothing wrong.
const fakeState = join(scratch, 'fake')
mkdirSync(fakeState)
writeFileSync(join(fakeState, 'signed-in'), '')
process.env.FAKE_CLAUDE_STATE = fakeState
const fake = join(scratch, 'claude-bin')
copyFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-claude.sh'), fake)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

// What the daemon before kept: a 5-hour limit hit ten minutes ago by a session that has ended
// since, and three other providers' trouble, one limit long past.
const now = Date.now()
const resetsAt = Math.ceil((now + 2 * 3600_000) / 1000) * 1000
const since = now - 600_000
const text = "You've hit your session limit · resets 2am"
writeFileSync(
  join(process.env.HYPERNOVA_SUPPORT, 'account.json'),
  JSON.stringify({
    account: { state: 'limited', providerId: 'anthropic', plan: 'max', limit: { window: 'five_hour', label: 'session limit', resetsAt, autoResumeAt: null, usingCredits: false }, reason: null, text, since, sessionId: 'gone', checkedAt: null },
    okAt: now - 3600_000,
    byCheck: false,
    providers: {
      'old-gw': { state: 'limited', text: 'API Error: 429', since: now - 4 * 3600_000 },
      'new-gw': { state: 'limited', text: 'API Error: 429', since: now - 600_000 },
      'bad-gw': { state: 'bad_key', text: 'API Error: 401', since: now - 4 * 3600_000 },
    },
  }),
  { mode: 0o600 },
)

const account = await import('../src/account.ts')
const hub = await import('../src/hub.ts')
const { classifyApiError } = await import('../src/blockers.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

test('a 5-hour limit kept from before the restart fills the meter again, and the sky agrees with the card', async () => {
  assert.equal(hub.getUsage().fiveHour, null)
  account.startAccount()
  await account.requestCheck()
  const a = account.getAccount()
  assert.deepEqual([a.state, a.limit?.window, a.limit?.resetsAt, a.since], ['limited', 'five_hour', resetsAt, since])
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 100, resetsAt })
  assert.equal(hub.getUsage().source, 'official')
  // A status line still carrying a lower number from the same window can't lower it.
  hub.offerUsage({ pct: 41, resetsAt: resetsAt + 30_000 }, null, Date.now())
  assert.equal(hub.getUsage().fiveHour?.pct, 100)
})

test("another provider's limit kept from long ago is over at start; its other trouble stays", () => {
  assert.equal(account.providerStatus('old-gw'), null)
  assert.equal(account.providerStatus('new-gw')?.state, 'limited')
  assert.equal(account.providerStatus('bad-gw')?.state, 'bad_key')
})

test('signed out on top of the kept limit, then in again: the limit is back with its first since', async () => {
  hub.upsertSession('rs-after', { cwd: scratch, origin: 'terminal', state: 'working' })
  hub.blockSession('rs-after', { ...classifyApiError({ error: 'authentication_failed', text: 'OAuth token revoked · Please run /login', builtIn: true, at: Date.now() }), check: false })
  assert.deepEqual([account.getAccount().state, hub.getUsage().source, hub.getUsage().fiveHour?.pct], ['logged_out', 'stale', 100])
  assert.equal(JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'account.json'), 'utf8')).under.since, since)
  await new Promise((r) => setTimeout(r, 5))
  account.noteLogin(Date.now())
  await account.requestCheck(true)
  const a = account.getAccount()
  assert.deepEqual([a.state, a.limit?.resetsAt, a.since, a.text, a.sessionId], ['limited', resetsAt, since, text, 'gone'])
  assert.deepEqual([hub.getUsage().source, hub.getUsage().fiveHour], ['official', { pct: 100, resetsAt }])
})
