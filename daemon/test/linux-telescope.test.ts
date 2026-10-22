// Telescope on a Linux server, where there is no AppKit or `sips`: PNGs written, read and shrunk in
// TypeScript (telescope/png.ts), and the terminal drawn with JetBrains Mono rasterized here
// (telescope/ttf.ts, paint.ts). The Mac keeps drawing with AppKit; these run on any system.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { crc32, deflateSync } from 'node:zlib'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-tscope-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const { decodePng, encodePng, flatten, shrinkPng, shrinkRgba } = await import('../src/telescope/png.ts')
const { paintFonts, paintTerminal } = await import('../src/telescope/paint.ts')
const { loadFont, rasterize } = await import('../src/telescope/ttf.ts')
const { termLayout, TERM_W, TERM_H } = await import('../src/telescope/terminal.ts')
const { pngSize } = await import('../src/shots.ts')

const FONT = join(import.meta.dirname, '..', '..', 'android', 'app', 'src', 'main', 'res', 'font', 'jetbrains_mono.ttf')

/** A PNG put together by hand: one IHDR, the rows (each with filter byte 0), IEND. */
function handPng(width: number, height: number, depth: number, type: number, rows: number[][], extra: Array<[string, number[]]> = []): Buffer {
  const chunk = (kind: string, body: Buffer) => {
    const len = Buffer.alloc(4)
    len.writeUInt32BE(body.length)
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(crc32(Buffer.concat([Buffer.from(kind, 'latin1'), body])))
    return Buffer.concat([len, Buffer.from(kind, 'latin1'), body, crc])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = depth
  ihdr[9] = type
  const raw = Buffer.from(rows.flatMap((r) => [0, ...r]))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    ...extra.map(([k, b]) => chunk(k, Buffer.from(b))),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

test('PNG: what it writes reads back the same, opaque as RGB, with alpha as RGBA, every filter used', () => {
  // A gradient, noise and flat areas: each row's filter differs (none, sub, up, average, Paeth).
  const w = 61, h = 37
  const data = new Uint8Array(w * h * 4)
  let seed = 7
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const o = (y * w + x) * 4
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    data[o] = y < 10 ? 20 : (x * 4) & 255
    data[o + 1] = y % 3 ? (y * 7) & 255 : seed & 255
    data[o + 2] = (x * y) & 255
    data[o + 3] = 255
  }
  const opaque = encodePng({ width: w, height: h, data })
  assert.equal(opaque[25], 2, 'RGB')
  assert.deepEqual(pngSize(opaque), { width: w, height: h })
  assert.deepEqual(decodePng(opaque), { width: w, height: h, data })
  data[3] = 0
  data[7] = 128
  const clear = encodePng({ width: w, height: h, data })
  assert.equal(clear[25], 6, 'RGBA')
  assert.deepEqual(decodePng(clear)!.data, data)
})

test('PNG: the kinds adb, Chrome and charting tools write (palette, grey, 16 bits) read as RGBA', () => {
  // A palette with a transparent entry.
  const pal = decodePng(handPng(2, 1, 8, 3, [[0, 1]], [['PLTE', [255, 0, 0, 0, 0, 255]], ['tRNS', [0]]]))!
  assert.deepEqual([...pal.data], [255, 0, 0, 0, 0, 0, 255, 255])
  // 16-bit grey: the high byte counts.
  const grey = decodePng(handPng(2, 1, 16, 0, [[0x80, 0x01, 0xff, 0xff]]))!
  assert.deepEqual([...grey.data], [128, 128, 128, 255, 255, 255, 255, 255])
  // Grey with alpha, 8 bits.
  assert.deepEqual([...decodePng(handPng(1, 1, 8, 4, [[50, 60]]))!.data], [50, 50, 50, 60])
  // Not a PNG, cut short, interlaced: not read (the caller keeps the original or says so).
  assert.equal(decodePng(Buffer.from('not a png at all, not at all, no')), null)
  const whole = handPng(2, 1, 8, 2, [[1, 2, 3, 4, 5, 6]])
  assert.equal(decodePng(whole.subarray(0, whole.length - 20)), null)
  const laced = Buffer.from(whole)
  laced[28] = 1
  assert.equal(decodePng(laced), null)
})

test('shrinking: the longer side to the most allowed, areas averaged; a picture that fits stays as it is', () => {
  const w = 400, h = 200
  const data = new Uint8Array(w * h * 4)
  // Black and white columns, one pixel each: averaged, they're grey.
  for (let i = 0; i < w * h; i++) {
    const v = (i % w) % 2 ? 255 : 0
    data.set([v, v, v, 255], i * 4)
  }
  const png = encodePng({ width: w, height: h, data })
  const small = shrinkPng(png, 100)!
  assert.deepEqual(pngSize(small), { width: 100, height: 50 })
  const px = decodePng(small)!.data
  assert.ok(Math.abs(px[0]! - 128) <= 1 && px[3] === 255)
  assert.equal(shrinkPng(png, 400), png)
  assert.equal(shrinkPng(Buffer.from('nope'), 10), null)
  // Transparent pixels don't darken an edge; laid on white they're white.
  const edge = shrinkRgba({ width: 2, height: 1, data: new Uint8Array([200, 0, 0, 255, 0, 0, 0, 0]) }, 1)
  assert.deepEqual([...edge.data.subarray(0, 3)], [200, 0, 0])
  assert.deepEqual([...flatten({ width: 1, height: 1, data: new Uint8Array([0, 0, 0, 0]) }).data], [255, 255, 255, 255])
})

test('JetBrains Mono read and drawn here: an M covers part of its box, a space nothing', () => {
  const font = loadFont(FONT)!
  assert.ok(font, 'the font loads')
  const m = rasterize(font, font.glyphOf('M'.codePointAt(0)!), 32)!
  assert.ok(m.w > 10 && m.h > 15, `${m.w}×${m.h}`)
  const ink = m.a.reduce((s, v) => s + v, 0) / (255 * m.w * m.h)
  assert.ok(ink > 0.2 && ink < 0.8, `coverage ${ink}`)
  assert.equal(rasterize(font, font.glyphOf(' '.codePointAt(0)!), 32), null)
  // A monospace font: M and i advance the same.
  assert.equal(font.advanceOf(font.glyphOf(0x4d)), font.advanceOf(font.glyphOf(0x69)))
  assert.equal(loadFont(join(scratch, 'no-font.ttf')), null)
})

test('the terminal drawn without AppKit: the size asked for, the background, the text in its colours', () => {
  const spec = termLayout('⏺ Hello from a Linux server\n  ⎿  Done in 2s\n\n❯ ')
  const fonts = paintFonts(FONT, [])
  assert.equal(fonts.length, 1)
  const png = paintTerminal(spec, fonts)
  assert.deepEqual(pngSize(png), { width: TERM_W, height: TERM_H })
  const img = decodePng(png)!
  const hex = (o: number) => '#' + [0, 1, 2].map((k) => img.data[o + k]!.toString(16).padStart(2, '0')).join('')
  assert.equal(hex(0), spec.background.toLowerCase())
  // Text was drawn: many pixels differ from the background, and some carry each run's colour.
  let inked = 0
  const colours = new Set<string>()
  for (let o = 0; o < img.data.length; o += 4) {
    const c = hex(o)
    if (c !== spec.background.toLowerCase()) inked++
    colours.add(c)
  }
  assert.ok(inked > 2000, `${inked} pixels of text`)
  for (const run of spec.lines.flat().filter((r) => r.text.trim())) assert.ok(colours.has(run.color.toLowerCase()), `the colour ${run.color}`)
  // No font at all: a plain error, not a crash.
  assert.throws(() => paintTerminal(spec, []), /no font/)
})
