// Telescope's terminal source: the session's shared terminal (the real Claude Code screen the
// holder keeps) drawn as a crisp picture in the Hypernova look, for command-line work, where there
// is no page or app to show. Drawn at twice the lens' picture (1152 × 512), so the whole screen
// fits the glasses and a zoomed quarter is pixel for pixel. Only the screen's text is used (its
// plain words, as the holder gives them); colours come from what each line is.
import { termScreen } from '../terms.ts'
import { drawTerminal, type TermSpec } from './draw.ts'
import { fail, type Capture } from './common.ts'

/** Two lens pictures wide and high (576 × 256 under the label line). */
export const TERM_W = 1152
export const TERM_H = 512
const PAD = 18
/** A monospace glyph is about 0.6 em wide; lines get some air. */
const ADVANCE = 0.6
const LEADING = 1.3
/** Smaller than this is unreadable on the lens even zoomed: the oldest lines give way instead. */
const MIN_FONT = 14
const MAX_FONT = 30

/** The Hyper-cosmos terminal (docs/DESIGN.md): warm near-black, ink, muted, ghost hairlines, mint. */
export const TERM_COLORS = {
  background: '#0F0D0A',
  ink: '#F5F2EB',
  muted: '#B5B0A6',
  dim: '#7A7468',
  ghost: '#45403A',
  mint: '#7FFFA0',
  danger: '#FF7878',
  amber: '#E8B341',
} as const

/** Columns a character takes: CJK and emoji take two. */
export function cellWidth(ch: string): number {
  const c = ch.codePointAt(0) ?? 0
  if (c < 0x1100) return 1
  if ((c <= 0x115f) || (c >= 0x2e80 && c <= 0xa4cf) || (c >= 0xac00 && c <= 0xd7a3) || (c >= 0xf900 && c <= 0xfaff) || (c >= 0xfe30 && c <= 0xfe4f) ||
      (c >= 0xff00 && c <= 0xff60) || (c >= 0xffe0 && c <= 0xffe6) || (c >= 0x1f300 && c <= 0x1f64f) || (c >= 0x1f900 && c <= 0x1f9ff)) return 2
  return 1
}

const columns = (s: string) => [...s].reduce((n, ch) => n + cellWidth(ch), 0)

/** Glyphs JetBrains Mono (or Menlo) has at its own advance: anything else is drawn on its own. */
const IN_FONT = /[ -ɏ‐-‧←-⇿─-▟]/u
const BOX = /[─-╿]/u

/**
 * What a line of Claude Code's screen is, by its first glyph: Claude's own words after ⏺ (the
 * bullet mint), a tool's output under ⎿ (muted), the spinner (mint glyph), the input box (ghost
 * frame, ink text), the footer (dim), errors (danger), deep output (muted).
 */
function lineColor(line: string): { lead: string | null; rest: string } {
  const t = line.trimStart()
  const c = TERM_COLORS
  if (/^(?:✗|✘|Error\b|error:|FAIL\b)/u.test(t)) return { lead: null, rest: c.danger }
  if (/^[⏺●]/u.test(t)) return { lead: c.mint, rest: c.ink }
  if (/^[✻✶✳✢✽·*]\s+\S.*…/u.test(t)) return { lead: c.mint, rest: c.muted }
  if (/^⎿/u.test(t)) return { lead: c.dim, rest: c.muted }
  if (/^(?:⏵⏵|⏸|\? for shortcuts)/u.test(t)) return { lead: null, rest: c.dim }
  if (/^[❯>]\s/u.test(t)) return { lead: c.mint, rest: c.ink }
  return { lead: null, rest: line.length - t.length >= 4 ? c.muted : c.ink }
}

/** A wrapped line goes on under the start of its text, two columns in. */
const HANG = 2

/**
 * A line too wide for the columns there are, as several: cut at the last space that fits, else at
 * the column, each continuation hung under the line's own text. A line of the input box's frame (box
 * glyphs) is only cut: wrapped, its frame would come apart.
 */
export function wrapLine(line: string, fit: number): string[] {
  if (columns(line) <= fit || BOX.test(line)) return [line]
  const indent = Math.min(Math.floor(fit / 3), line.length - line.trimStart().length + HANG)
  const out: string[] = []
  let rest = [...line]
  let first = true
  while (rest.length) {
    const room = first ? fit : fit - indent
    let col = 0, cut = 0, space = -1
    while (cut < rest.length && col + cellWidth(rest[cut]!) <= room) {
      if (rest[cut] === ' ' && cut > 0) space = cut
      col += cellWidth(rest[cut]!)
      cut++
    }
    if (cut < rest.length && space > room / 2) cut = space
    out.push((first ? '' : ' '.repeat(indent)) + rest.slice(0, cut).join('').replace(/\s+$/, ''))
    rest = rest.slice(cut)
    while (rest[0] === ' ') rest.shift()
    first = false
  }
  return out
}

/**
 * The screen's text laid out for drawing: blank lines above and below gone, the font as large as
 * fits; lines too wide even at the smallest font wrapped, not cut (Claude's replies on a wide
 * terminal), and the newest lines kept when they don't all fit; each line split into runs of one
 * colour (a wrapped line's colour carries on), every run placed at its column.
 */
export function termLayout(text: string, width = TERM_W, height = TERM_H): TermSpec {
  let lines = text.replace(/\r/g, '').split('\n').map((l) => l.replace(/\s+$/, ''))
  while (lines.length && !lines[0]!.trim()) lines.shift()
  while (lines.length && !lines.at(-1)!.trim()) lines.pop()
  if (!lines.length) lines = ['']
  const roomW = width - 2 * PAD, roomH = height - 2 * PAD
  // Each row to draw, with the colours of the line it came from (a continuation has no lead glyph).
  let rows = lines.map((line) => ({ text: line, ...lineColor(line) }))
  const cols = Math.max(20, ...lines.map(columns))
  if (roomW / (cols * ADVANCE) < MIN_FONT) {
    const most = Math.floor(roomW / (MIN_FONT * ADVANCE))
    rows = rows.flatMap((r) => wrapLine(r.text, most).map((text, i) => ({ text, lead: i ? null : r.lead, rest: r.rest })))
  }
  const widest = Math.max(20, ...rows.map((r) => columns(r.text)))
  let size = Math.min(MAX_FONT, roomW / (widest * ADVANCE), roomH / (rows.length * LEADING))
  if (size < MIN_FONT) {
    size = MIN_FONT
    rows = rows.slice(-Math.max(1, Math.floor(roomH / (size * LEADING))))
  }
  const fit = Math.floor(roomW / (size * ADVANCE))
  const c = TERM_COLORS
  const out: TermSpec['lines'] = rows.map(({ text: line, lead, rest }) => {
    const runs: Array<{ text: string; col: number; color: string; alone: boolean }> = []
    let col = 0
    let leadDone = !lead
    for (const ch of line) {
      const w = cellWidth(ch)
      if (col + w > fit) break
      const last = runs.at(-1)
      const joins = (color: string) => !!last && !last.alone && last.color === color && last.col + columns(last.text) === col
      if (ch === ' ') {
        // A space only carries on a run; the next glyph after a gap starts at its own column.
        if (last && !last.alone && last.col + columns(last.text) === col) last.text += ' '
        col += 1
        continue
      }
      let color = rest
      if (BOX.test(ch)) color = c.ghost
      else if (!leadDone) {
        color = lead!
        leadDone = true
      }
      // A glyph the font hasn't (⏺, ⎿, ⏵, CJK) goes alone at its column; the rest joins its run.
      const alone = !IN_FONT.test(ch)
      if (!alone && joins(color)) last!.text += ch
      else runs.push({ text: ch, col, color, alone })
      col += w
    }
    return runs.map(({ text, col, color }) => ({ text: text.replace(/\s+$/, ''), col, color })).filter((r) => r.text)
  })
  const lineHeight = size * LEADING
  return {
    width,
    height,
    fontSize: Math.round(size * 10) / 10,
    lineHeight: Math.round(lineHeight * 10) / 10,
    padX: PAD,
    // Short screens sit at the top, like a terminal's.
    padY: PAD,
    background: c.background,
    lines: out,
  }
}

/** The session's shared terminal as a picture. */
export async function captureTerminal(s: { term: { id: string } | null; projectName: string }): Promise<Capture> {
  if (!s.term) throw fail(409, 'no_terminal', 'This session isn’t running in a shared terminal, so there’s no screen to show.')
  const screen = await termScreen(s.term.id)
  if (!screen) throw fail(502, 'capture_failed', 'The session’s terminal didn’t answer.')
  const png = await drawTerminal(termLayout(screen.text))
  return { png, title: `Terminal · ${s.projectName}`, label: 'terminal' }
}
