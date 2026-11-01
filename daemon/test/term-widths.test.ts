// A character's width on the holder's copy of the screen (docs/PROTOCOL.md, "Shared terminals",
// "Widths"): the same as Claude Code's and the phone page's, so a snapshot drawn there has its columns
// where live output would put them.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-widths-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { spawnHolder } = await import('../src/term/shared.ts')
const { endTerm, relayPhone, waitForTerm } = await import('../src/terms.ts')

const require = createRequire(import.meta.url)
const { Terminal } = require('@xterm/headless') as typeof import('@xterm/headless')
const { SerializeAddon } = require('@xterm/addon-serialize') as typeof import('@xterm/addon-serialize')
const { UnicodeGraphemesAddon } = require('@xterm/addon-unicode-graphemes') as typeof import('@xterm/addon-unicode-graphemes')
type Term = InstanceType<typeof Terminal>

after(() => rmSync(scratch, { recursive: true, force: true }))
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** A terminal counting widths as the holder and the page do (or, without the add-on, xterm's own way). */
function terminal(cols = 80, rows = 24, widths = true): Term {
  const t = new Terminal({ cols, rows, scrollback: 1000, allowProposedApi: true })
  if (widths) t.loadAddon(new UnicodeGraphemesAddon())
  return t
}
const write = (t: Term, d: string | Uint8Array) => new Promise<void>((ok) => t.write(d, ok))

/** Every cell's characters and width, every line's wrapping and the cursor: what lining up means. */
function layout(t: Term) {
  const b = t.buffer.active
  const lines: unknown[] = []
  const cell = b.getNullCell()
  for (let y = 0; y < b.length; y++) {
    const l = b.getLine(y)!
    const cells: string[] = []
    for (let x = 0; x < l.length; x++) {
      l.getCell(x, cell)
      cells.push(`${cell.getChars()}/${cell.getWidth()}`)
    }
    lines.push({ wrapped: l.isWrapped, cells: cells.join(' ') })
  }
  // A blank tail of the buffer isn't a difference.
  while (lines.length && /^(\/1 ?)*$/.test((lines.at(-1) as { cells: string }).cells)) lines.pop()
  return { lines, cursor: [b.cursorX, b.cursorY + b.baseY] }
}

// What a phone's terminal gets: the wide ones, the sequences, wrapping and colour across them.
const SCREEN = [
  '🚀 rocket',
  '❤️ heart',
  '👨‍👩‍👧 family',
  '漢字 cjk',
  '👋🏽 skin',
  '🇫🇷 flag',
  'é accent',
  '✻ Thinking… ⏺ Bash ⎿ done ❯ │ ─',
  '\x1b[31m🚀\x1b[0m\x1b[1m❤️\x1b[22m\x1b[44m漢\x1b[0m colours',
  'x'.repeat(79) + '🚀 pushed to the next line',
  'y'.repeat(78) + '👨‍👩‍👧 fits at the end',
  'END',
].join('\r\n')

test("the holder's widths: an emoji is 2 columns, a sequence is one character", async () => {
  const t = terminal()
  assert.equal(t.unicode.activeVersion, '15-graphemes')
  for (const [s, cols] of [
    ['a', 1],
    ['🚀', 2],
    ['❤️', 2],
    ['❤', 1],
    ['⚠️', 2],
    ['👨‍👩‍👧', 2],
    ['👋🏽', 2],
    ['🇫🇷', 2],
    ['漢', 2],
    ['漢字', 4],
    ['é', 1],
    // Claude Code's own marks stay narrow.
    ['⏺', 1],
    ['⎿', 1],
    ['✻', 1],
    ['❯', 1],
    ['─', 1],
  ] as const) {
    await write(t, `\x1b[2J\x1b[H${s}`)
    assert.equal(t.buffer.active.cursorX, cols, JSON.stringify(s))
  }
  // Without it, xterm's default: what the phone used to get (the rocket 1, the family 3).
  const plain = terminal(80, 24, false)
  await write(plain, '🚀')
  assert.equal(plain.buffer.active.cursorX, 1)
  await write(plain, '\x1b[2J\x1b[H👨‍👩‍👧')
  assert.equal(plain.buffer.active.cursorX, 3)
})

test('a sequence cut across two writes is still one character', async () => {
  const t = terminal()
  await write(t, '❤')
  await write(t, '️x')
  assert.equal(t.buffer.active.cursorX, 3)
  await write(t, '\x1b[2J\x1b[H👨‍')
  await write(t, '👩‍')
  await write(t, '👧')
  assert.equal(t.buffer.active.cursorX, 2)
  // Bytes cut inside a character, as a socket's frames are.
  const bytes = Buffer.from('\x1b[2J\x1b[H🚀❤️y')
  for (const b of bytes) await write(t, Uint8Array.of(b))
  assert.equal(t.buffer.active.cursorX, 5)
})

test('a snapshot replayed lines up with the screen it was made from', async () => {
  const live = terminal()
  const serializer = new SerializeAddon()
  live.loadAddon(serializer)
  await write(live, SCREEN)
  const page = terminal()
  await write(page, serializer.serialize({ scrollback: 500 }))
  assert.deepEqual(layout(page), layout(live))
})

test('the same with history, and output after the snapshot lands where it should', async () => {
  const live = terminal(40, 6)
  const serializer = new SerializeAddon()
  live.loadAddon(serializer)
  for (let i = 0; i < 30; i++) await write(live, `${i} 🚀 ❤️ 👨‍👩‍👧 漢字\r\n`)
  await write(live, '🚀 half a li')
  const page = terminal(40, 6)
  await write(page, serializer.serialize({ scrollback: 500 }))
  assert.deepEqual(layout(page), layout(live))
  for (const t of [live, page]) await write(t, 'ne ❤️ on\r\n\x1b[2A\x1b[3C👨‍👩‍👧')
  assert.deepEqual(layout(page), layout(live))
})

test('a page counting the old way shifts the columns, which is why they must agree', async () => {
  const live = terminal()
  const serializer = new SerializeAddon()
  live.loadAddon(serializer)
  await write(live, '🚀 rocket')
  const old = terminal(80, 24, false)
  await write(old, serializer.serialize())
  assert.notDeepEqual(layout(old), layout(live))
})

test('a real holder: its hello says its widths, and its snapshot lines up with the bytes claude wrote', async () => {
  const id = 'd3d3d3d3d3'
  const child = `process.stdout.write(${JSON.stringify(SCREEN)}); setInterval(() => {}, 1000)`
  spawnHolder({
    id,
    cwd: scratch,
    file: process.execPath,
    args: ['-e', child],
    env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' },
    cols: 80,
    rows: 24,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: null },
    waitForTty: false,
    autoTrust: false,
  })
  try {
    assert.ok(await waitForTerm(id, 6000), 'the holder came up')
    // Joined again until the snapshot has it all, so the screen here came through the snapshot.
    let got: { hello: any; snapshot: string } | null = null
    for (let tries = 0; tries < 60 && !got; tries++) {
      const messages: string[] = []
      const on: Record<string, (...x: any[]) => void> = {}
      const ws = { OPEN: 1, readyState: 1, bufferedAmount: 0, send: (d: unknown) => void messages.push(String(d)), close: () => {}, on: (ev: string, f: (...x: any[]) => void) => void (on[ev] = f) }
      relayPhone(id, ws as any, 80, 24)
      for (let i = 0; i < 40 && !messages.some((m) => m.includes('"snapshot"')); i++) await sleep(25)
      on.close?.()
      const parsed = messages.flatMap((m) => {
        try {
          return [JSON.parse(m)]
        } catch {
          return []
        }
      })
      const snapshot = parsed.find((m) => m.t === 'snapshot')?.data as string | undefined
      if (snapshot?.includes('END')) got = { hello: parsed.find((m) => m.t === 'hello'), snapshot }
      else await sleep(50)
    }
    assert.ok(got, 'a snapshot with the whole screen')
    assert.equal(got.hello.unicode, '15-graphemes')
    const page = terminal()
    await write(page, got.snapshot)
    const bytes = terminal()
    await write(bytes, SCREEN)
    assert.deepEqual(layout(page), layout(bytes))
  } finally {
    endTerm(id)
  }
})
