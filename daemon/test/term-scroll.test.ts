// A phone scrolling its terminal view (docs/PROTOCOL.md, "Shared terminals", phone view): Claude
// Code's fullscreen view keeps its own history, so the phone's drag reaches it as mouse-wheel
// turns. They go in as `keys`: scrolling isn't using the terminal, so a Mac window keeps its size,
// and nothing but scrolling passes.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { connect } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-scroll-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { msg, sockPath, spawnHolder } = await import('../src/term/shared.ts')
const { endTerm, isScroll, relayPhone, waitForTerm } = await import('../src/terms.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

test('only scrolling passes as scrolling', () => {
  assert.ok(isScroll('\x1b[<64;12;5M'), 'a wheel turn up')
  assert.ok(isScroll('\x1b[<65;1;1M\x1b[<65;1;1M'), 'two turns down')
  assert.ok(isScroll('\x1b[<80;3;4M'), 'a turn with Ctrl held')
  assert.ok(isScroll('\x1b[5~') && isScroll('\x1b[6~') && isScroll('\x1b[1;5F'), 'PgUp, PgDn, Ctrl+End')
  assert.ok(!isScroll('\x1b[<0;12;5M'), 'a click')
  assert.ok(!isScroll('\x1b[<96;12;5M'), 'a drag')
  assert.ok(!isScroll('\x1b[A'), 'an arrow key')
  assert.ok(!isScroll('\x1b[5~ls\r'), 'typing after a page')
  assert.ok(!isScroll(''), 'nothing')
  assert.ok(!isScroll(42), 'not text')
})

test("a phone's scrolling reaches claude without taking a Mac window's size", async () => {
  const id = 'c2c2c2c2c2'
  // Stands in for claude: says the bytes it gets, in hex.
  const echo = "process.stdin.setRawMode(true); process.stdin.on('data', (b) => process.stdout.write('GOT:' + b.toString('hex') + '\\n'))"
  spawnHolder({
    id,
    cwd: scratch,
    file: process.execPath,
    args: ['-e', echo],
    env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' },
    cols: 80,
    rows: 24,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: null },
    waitForTty: false,
    autoTrust: false,
  })
  assert.ok(await waitForTerm(id, 6000), 'the holder came up')
  // A Mac window at 80 × 24.
  const tty = connect(sockPath(id))
  tty.on('error', () => {})
  await new Promise<void>((r) => tty.on('connect', () => r()))
  tty.write(msg({ t: 'hello', v: 1, role: 'tty', cols: 80, rows: 24, snapshot: false }))
  await sleep(200)
  // A phone at 60 × 10, as relayPhone sees its WebSocket.
  const got: string[] = []
  const on: Record<string, (...x: any[]) => void> = {}
  const ws = { OPEN: 1, readyState: 1, bufferedAmount: 0, send: (d: unknown) => void got.push(String(d)), close: () => {}, on: (ev: string, f: (...x: any[]) => void) => void (on[ev] = f) }
  relayPhone(id, ws as any, 60, 10)
  // Cleaned up even when a check fails: the window's socket would keep the test running.
  try {
    for (let i = 0; i < 100 && !got.some((m) => m.includes('"snapshot"')); i++) await sleep(50)
    const sizes = () => got.filter((m) => m.includes('"t":"state"') || m.includes('"t":"hello"')).map((m) => JSON.parse(m).cols as number)
    const saw = async (hex: string) => {
      for (let i = 0; i < 60 && !got.some((m) => m.includes(`GOT:${hex}`)); i++) await sleep(50)
      return got.some((m) => m.includes(`GOT:${hex}`))
    }
    const say = (m: unknown) => on.message?.(Buffer.from(JSON.stringify(m)), false)

    say({ t: 'scroll', data: '\x1b[<64;5;5M' })
    assert.ok(await saw(Buffer.from('\x1b[<64;5;5M').toString('hex')), 'claude got the wheel turn')
    say({ t: 'scroll', data: 'ls\r' })
    say({ t: 'scroll', data: '\x1b[1;5F' })
    assert.ok(await saw(Buffer.from('\x1b[1;5F').toString('hex')), 'claude got Ctrl+End')
    assert.ok(!got.some((m) => m.includes(`GOT:${Buffer.from('ls\r').toString('hex')}`)), 'typing sent as scrolling was dropped')
    await sleep(200)
    assert.ok(!sizes().includes(60), "the Mac window's size stayed")

    // Typing on the phone does take the size (the check above would have seen it).
    on.message?.(Buffer.from('x'), true)
    for (let i = 0; i < 60 && !sizes().includes(60); i++) await sleep(50)
    assert.ok(sizes().includes(60), 'typing took the size')
  } finally {
    on.close?.()
    tty.destroy()
    endTerm(id)
  }
})
