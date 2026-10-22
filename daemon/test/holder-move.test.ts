// A conversation that switched agents moves its terminal (switch.ts): the old holder tells its
// clients where it went and ends, and a phone's relay follows to the new holder's screen
// (docs/PROTOCOL.md, "Shared terminals", move).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-move-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { spawnHolder } = await import('../src/term/shared.ts')
const { endTerm, moveTerm, relayPhone, termAlive, waitForTerm } = await import('../src/terms.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

function holder(id: string, says: string): number | null {
  return spawnHolder({
    id,
    cwd: scratch,
    file: '/bin/sh',
    args: ['-c', `echo ${says}; sleep 30`],
    env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' },
    cols: 60,
    rows: 10,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: null },
    waitForTty: false,
    autoTrust: false,
  })
}

test('a phone watching the old terminal is taken to the new one; the old one ends', async () => {
  const a = 'a0a0a0a0a0'
  const b = 'b1b1b1b1b1'
  const pidA = holder(a, 'SOURCE-A')
  holder(b, 'DEST-B')
  assert.ok(await waitForTerm(a, 6000), 'holder A came up')
  assert.ok(await waitForTerm(b, 6000), 'holder B came up')
  // A phone's WebSocket, as relayPhone sees one.
  const got: string[] = []
  let closed: number | null = null
  const on: Record<string, (...x: any[]) => void> = {}
  const ws = { OPEN: 1, readyState: 1, bufferedAmount: 0, send: (d: unknown) => void got.push(String(d)), close: (code: number) => void (closed = code), on: (ev: string, f: (...x: any[]) => void) => void (on[ev] = f) }
  relayPhone(a, ws as any, 60, 10)
  const shows = (word: string) => got.some((m) => m.includes('"snapshot"') && m.includes(word))
  for (let i = 0; i < 100 && !shows('SOURCE-A'); i++) await sleep(50)
  assert.ok(shows('SOURCE-A'), 'the phone sees the old terminal')
  assert.ok(moveTerm(a, b))
  for (let i = 0; i < 100 && !shows('DEST-B'); i++) await sleep(50)
  assert.ok(shows('DEST-B'), 'the phone now sees the new terminal')
  assert.equal(closed, null, 'its view stayed open across the move')
  for (let i = 0; i < 100 && termAlive(a, pidA); i++) await sleep(50)
  assert.equal(termAlive(a, pidA), false, 'the old holder ended')
  on.close?.()
  endTerm(b)
})
