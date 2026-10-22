// A send's clientId (docs/PROTOCOL.md, "Sending a prompt"): the same one within the window is one send.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-dedup-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const { dedupSend, DEDUP_WINDOW_MS } = await import('../src/http.ts')

const ok = (id: string) => ({ id, queued: false })

test('a repeated clientId sends once and gets the first answer', async () => {
  let n = 0
  const send = async () => (n++, ok('s1'))
  const a = await dedupSend('s1', 'c-1', send)
  const b = await dedupSend('s1', 'c-1', send)
  assert.equal(n, 1)
  assert.deepEqual(a, b)
})

test('another clientId, another session, or none at all, sends again', async () => {
  let n = 0
  const send = async () => (n++, ok('x'))
  await dedupSend('s2', 'c-1', send)
  await dedupSend('s2', 'c-2', send)
  await dedupSend('s3', 'c-1', send)
  await dedupSend('s2', undefined, send)
  await dedupSend('s2', undefined, send)
  assert.equal(n, 5)
})

test('two at once are one send', async () => {
  let n = 0
  const send = () => new Promise<ReturnType<typeof ok>>((r) => (n++, setTimeout(() => r(ok('s4')), 20)))
  await Promise.all([dedupSend('s4', 'c-9', send), dedupSend('s4', 'c-9', send)])
  assert.equal(n, 1)
})

test('a failed send is forgotten, so it can go again', async () => {
  let n = 0
  const send = async () => { n++; if (n === 1) throw new Error('typing'); return ok('s5') }
  await assert.rejects(dedupSend('s5', 'c-1', send))
  await dedupSend('s5', 'c-1', send)
  assert.equal(n, 2)
})

test('after the window it is a new send', async () => {
  let n = 0
  const send = async () => (n++, ok('s6'))
  let t = 1_000_000
  await dedupSend('s6', 'c-1', send, () => t)
  t += DEDUP_WINDOW_MS - 1
  await dedupSend('s6', 'c-1', send, () => t)
  assert.equal(n, 1)
  t += DEDUP_WINDOW_MS + 5
  await dedupSend('s6', 'c-1', send, () => t)
  assert.equal(n, 2)
})
