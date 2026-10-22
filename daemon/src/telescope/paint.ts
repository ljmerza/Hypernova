// The terminal drawn without AppKit, for a Linux server (draw.ts uses this off the Mac): the same
// layout (terminal.ts's TermSpec), the same colours, JetBrains Mono as the daemon ships it, glyphs
// rasterized by ttf.ts and the picture written by png.ts. A glyph the font lacks is looked for in
// the system's monospace fonts, then drawn as its nearest plain cousin (⏺ as ●, ⎿ as └), else left
// out; its column stays where it was either way, as on the Mac.
import { existsSync } from 'node:fs'
import { encodePng } from './png.ts'
import { loadFont, rasterize, type Font, type Mask } from './ttf.ts'
import type { TermSpec } from './draw.ts'

/** Monospace fonts a Debian or Ubuntu system often has, for the glyphs JetBrains Mono lacks. */
export const SYSTEM_MONO = [
  '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf',
  '/usr/share/fonts/truetype/noto/NotoSansMono-Regular.ttf',
  '/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf',
  '/usr/share/fonts/truetype/ubuntu/UbuntuMono[wght].ttf',
]

/** Claude Code's glyphs that fonts often lack, and what stands in for them. */
const STAND_INS: Record<string, string[]> = {
  '⏺': ['●', '•', '*'],
  '⎿': ['└', 'L'],
  '❯': ['›', '>'],
  '⏵': ['▶', '>'],
  '⏸': ['‖', '|'],
  '✻': ['*'],
  '✶': ['*'],
  '✳': ['*'],
  '✢': ['+'],
  '✽': ['*'],
  '✗': ['×', 'x'],
  '✘': ['×', 'x'],
  '✓': ['√', 'v'],
  '✔': ['√', 'v'],
}

const fonts = new Map<string, Font | null>()
const fontAt = (path: string) => {
  if (!fonts.has(path)) fonts.set(path, existsSync(path) ? loadFont(path) : null)
  return fonts.get(path) ?? null
}

/** The fonts to draw with, in order: the one given (JetBrains Mono), then the system's. */
export function paintFonts(primary: string | null, extra: string[] = SYSTEM_MONO): Font[] {
  return [primary, ...extra].flatMap((p) => {
    const f = p ? fontAt(p) : null
    return f ? [f] : []
  })
}

/** Which font draws [ch], and its glyph: the first that has it, else a stand-in's. */
function glyphFor(list: Font[], ch: string): { font: Font; gid: number } | null {
  for (const c of [ch, ...(STAND_INS[ch] ?? [])]) {
    const cp = c.codePointAt(0)!
    for (const font of list) {
      const gid = font.glyphOf(cp)
      if (gid) return { font, gid }
    }
  }
  return null
}

const rgb = (hex: string): [number, number, number] => [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)]

/**
 * The terminal's screen as a PNG, laid out by terminal.ts: each run at its column, a column as wide
 * as the main font's own advance (0.6 em for JetBrains Mono), every line's baseline at the same
 * place in its line. Throws when no font can be read at all.
 */
export function paintTerminal(spec: TermSpec, list: Font[]): Buffer {
  const main = list[0]
  if (!main) throw new Error('no font to draw the terminal with')
  const { width: W, height: H } = spec
  const data = new Uint8Array(W * H * 4)
  const [br, bg, bb] = rgb(spec.background)
  for (let i = 0; i < data.length; i += 4) {
    data[i] = br
    data[i + 1] = bg
    data[i + 2] = bb
    data[i + 3] = 255
  }
  const size = spec.fontSize
  const adv = (main.advanceOf(main.glyphOf(0x4d)) / main.unitsPerEm) * size
  const masks = new Map<string, Mask | null>()
  spec.lines.forEach((runs, i) => {
    // Text centred in its line: the cap height and descenders share the line's spare room.
    const baseline = spec.padY + i * spec.lineHeight + (spec.lineHeight - size) / 2 + size * 0.8
    for (const run of runs) {
      const [r, g, b] = rgb(run.color)
      let k = 0
      for (const ch of run.text) {
        const x = spec.padX + (run.col + k) * adv
        k++
        if (ch === ' ') continue
        const hit = glyphFor(list, ch)
        if (!hit) continue
        // A column between pixels keeps its quarter pixel, so columns stay true across a line.
        const whole = Math.floor(x)
        const frac = Math.round((x - whole) * 4) / 4
        const key = `${list.indexOf(hit.font)}:${hit.gid}:${size}:${frac}`
        let m = masks.get(key)
        if (m === undefined) masks.set(key, (m = rasterize(hit.font, hit.gid, size, frac)))
        if (!m) continue
        blit(data, W, H, m, whole + m.left, Math.round(baseline) + m.top, r, g, b)
      }
    }
  })
  return encodePng({ width: W, height: H, data })
}

/** A glyph's coverage laid over the picture in one colour. */
function blit(data: Uint8Array, W: number, H: number, m: Mask, ox: number, oy: number, r: number, g: number, b: number): void {
  for (let y = 0; y < m.h; y++) {
    const py = oy + y
    if (py < 0 || py >= H) continue
    for (let x = 0; x < m.w; x++) {
      const px = ox + x
      const a = m.a[y * m.w + x]!
      if (!a || px < 0 || px >= W) continue
      const o = (py * W + px) * 4
      const t = a / 255
      data[o] = Math.round(data[o]! * (1 - t) + r * t)
      data[o + 1] = Math.round(data[o + 1]! * (1 - t) + g * t)
      data[o + 2] = Math.round(data[o + 2]! * (1 - t) + b * t)
    }
  }
}
