// Pictures drawn on the Mac with AppKit, through JavaScript for Automation (`osascript -l
// JavaScript`, on every Mac): a file the session made (any image macOS opens, SVG included, or a
// PDF's page) turned into a PNG, and the terminal's text drawn as a picture. The scripts are fixed
// text; what they draw comes in as JSON on standard input, and they answer one line of JSON.
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { HOME } from '../util.ts'
import { pngSize, MAX_PNG } from '../shots.ts'
import { fail, scratchFile } from './common.ts'
import { runFile } from './run.ts'
import { ON_MAC } from '../platform.ts'
import { paintFonts, paintTerminal } from './paint.ts'
import { decodePng, encodePng, flatten, shrinkRgba } from './png.ts'

const OSASCRIPT = '/usr/bin/osascript'

/** Reads the JSON on standard input. */
const STDIN = `
ObjC.import('AppKit')
function input() {
  const d = $.NSFileHandle.fileHandleWithStandardInput.readDataToEndOfFile
  return JSON.parse($.NSString.alloc.initWithDataEncoding(d, $.NSUTF8StringEncoding).js)
}
function blank(W, H, colour) {
  const rep = $.NSBitmapImageRep.alloc.initWithBitmapDataPlanesPixelsWidePixelsHighBitsPerSampleSamplesPerPixelHasAlphaIsPlanarColorSpaceNameBytesPerRowBitsPerPixel(null, W, H, 8, 4, true, false, $.NSDeviceRGBColorSpace, 0, 0)
  $.NSGraphicsContext.saveGraphicsState
  $.NSGraphicsContext.setCurrentContext($.NSGraphicsContext.graphicsContextWithBitmapImageRep(rep))
  colour.setFill
  $.NSRectFill($.NSMakeRect(0, 0, W, H))
  return rep
}
function save(rep, out) {
  $.NSGraphicsContext.restoreGraphicsState
  const png = rep.representationUsingTypeProperties($.NSBitmapImageFileTypePNG, $({}))
  return png.writeToFileAtomically(out, true)
}
`

/**
 * A file as a PNG on white (a chart with a transparent background reads as one): a bitmap at its
 * own pixels, at most `max` on its longer side; a drawing (SVG, a PDF's page) at `vector` on its
 * longer side, so a zoomed quarter is still sharp.
 */
const FILE_SCRIPT = `${STDIN}
ObjC.import('PDFKit')
function run() {
  const s = input()
  const url = $.NSURL.fileURLWithPath(s.src)
  let image, w, h, pages = 0, page = 0
  if (s.kind === 'pdf') {
    const doc = $.PDFDocument.alloc.initWithURL(url)
    if (!doc || doc.isNil()) return JSON.stringify({ error: 'unreadable' })
    if (doc.isLocked) return JSON.stringify({ error: 'locked' })
    pages = Number(doc.pageCount)
    if (pages < 1) return JSON.stringify({ error: 'empty' })
    page = Math.min(Math.max(1, s.page || 1), pages)
    const p = doc.pageAtIndex(page - 1)
    const box = p.boundsForBox($.kPDFDisplayBoxCropBox)
    const turned = Number(p.rotation) % 180 !== 0
    w = turned ? box.size.height : box.size.width
    h = turned ? box.size.width : box.size.height
    const k = s.vector / Math.max(w, h)
    w = Math.max(1, Math.round(w * k)); h = Math.max(1, Math.round(h * k))
    image = p.thumbnailOfSizeForBox($.NSMakeSize(w, h), $.kPDFDisplayBoxCropBox)
  } else {
    image = $.NSImage.alloc.initWithContentsOfURL(url)
    if (!image || image.isNil()) return JSON.stringify({ error: 'unreadable' })
    let pw = 0, ph = 0
    const reps = image.representations
    for (let i = 0; i < reps.count; i++) { const r = reps.objectAtIndex(i); pw = Math.max(pw, r.pixelsWide); ph = Math.max(ph, r.pixelsHigh) }
    if (pw > 0 && ph > 0) { const k = Math.min(1, s.max / Math.max(pw, ph)); w = pw * k; h = ph * k }
    else {
      w = image.size.width; h = image.size.height
      if (!(w > 0 && h > 0)) return JSON.stringify({ error: 'empty' })
      const k = Math.min(s.max / Math.max(w, h), Math.max(1, s.vector / Math.max(w, h)))
      w *= k; h *= k
    }
    w = Math.max(1, Math.round(w)); h = Math.max(1, Math.round(h))
  }
  const rep = blank(w, h, $.NSColor.whiteColor)
  image.drawInRectFromRectOperationFraction($.NSMakeRect(0, 0, w, h), $.NSZeroRect, $.NSCompositingOperationSourceOver, 1)
  if (!save(rep, s.out)) return JSON.stringify({ error: 'write' })
  return JSON.stringify({ ok: true, width: w, height: h, page: page, pages: pages })
}
`

/**
 * Lines of coloured runs, each at its column: a run is drawn where its first column falls, so a
 * glyph from a fallback font (⏺, ⎿) never pushes the rest of the line out of its columns.
 */
const TERMINAL_SCRIPT = `${STDIN}
ObjC.import('CoreText')
function run() {
  const s = input()
  if (s.fontFile) $.CTFontManagerRegisterFontsForURL($.NSURL.fileURLWithPath(s.fontFile), $.kCTFontManagerScopeProcess, null)
  let font = $.NSFont.fontWithNameSize('JetBrainsMono-Regular', s.fontSize)
  if (!font || font.isNil()) font = $.NSFont.fontWithNameSize('Menlo-Regular', s.fontSize)
  const rgb = (x) => $.NSColor.colorWithSRGBRedGreenBlueAlpha(parseInt(x.slice(1, 3), 16) / 255, parseInt(x.slice(3, 5), 16) / 255, parseInt(x.slice(5, 7), 16) / 255, 1)
  const rep = blank(s.width, s.height, rgb(s.background))
  const probe = $.NSMutableDictionary.alloc.init
  probe.setObjectForKey(font, $.NSFontAttributeName)
  const adv = $('M').sizeWithAttributes(probe).width
  const colours = {}
  s.lines.forEach((runs, i) => {
    const y = s.height - s.padY - (i + 1) * s.lineHeight + (s.lineHeight - s.fontSize) / 2 + s.fontSize * 0.2
    for (const r of runs) {
      const a = $.NSMutableDictionary.alloc.init
      a.setObjectForKey(font, $.NSFontAttributeName)
      a.setObjectForKey(colours[r.color] || (colours[r.color] = rgb(r.color)), $.NSForegroundColorAttributeName)
      $(r.text).drawAtPointWithAttributes($.NSMakePoint(s.padX + r.col * adv, y), a)
    }
  })
  if (!save(rep, s.out)) return JSON.stringify({ error: 'write' })
  return JSON.stringify({ ok: true, width: s.width, height: s.height, advance: adv })
}
`

/** Runs a script with its JSON, and reads back the picture it wrote. */
async function draw(script: string, spec: Record<string, unknown>): Promise<{ png: Buffer; answer: any }> {
  const out = scratchFile()
  try {
    const r = await runFile(OSASCRIPT, ['-l', 'JavaScript', '-e', script], { input: JSON.stringify({ ...spec, out: out.path }), timeout: 20_000, max: 1024 * 1024 })
    let answer: any = null
    try {
      answer = JSON.parse(r.out.toString('utf8').trim().split('\n').pop() ?? '')
    } catch {}
    if (!answer?.ok) return { png: Buffer.alloc(0), answer: answer ?? { error: r.missing ? 'no_osascript' : r.timedOut ? 'timeout' : 'failed' } }
    let png = Buffer.alloc(0)
    try {
      png = readFileSync(out.path)
    } catch {}
    if (!pngSize(png) || png.length > MAX_PNG) return { png: Buffer.alloc(0), answer: { error: 'not_png' } }
    return { png, answer }
  } finally {
    out.remove()
  }
}

/** The longer side of a bitmap, at most; and of a drawing (SVG, a PDF page), at least (sharp when zoomed). */
const MAX_SIDE = 2560
const VECTOR_SIDE = 1600

/** A picture file (already checked to be the session's, files.ts) as a PNG; a PDF at `page`. */
export async function drawFile(src: string, kind: 'pdf' | 'image', page = 1): Promise<{ png: Buffer; width: number; height: number; page: number; pages: number }> {
  if (!ON_MAC) return drawFileHere(src, kind)
  const { png, answer } = await draw(FILE_SCRIPT, { src, kind, page, max: MAX_SIDE, vector: VECTOR_SIDE })
  if (!png.length) {
    if (answer?.error === 'locked') throw fail(422, 'locked_pdf', 'That PDF is locked with a password.')
    throw fail(502, 'capture_failed', kind === 'pdf' ? 'The Mac couldn’t draw that PDF.' : 'The Mac couldn’t open that picture.')
  }
  return { png, width: answer.width, height: answer.height, page: answer.page || 0, pages: answer.pages || 0 }
}

export type TermSpec = {
  width: number
  height: number
  fontSize: number
  lineHeight: number
  padX: number
  padY: number
  background: string
  lines: Array<Array<{ text: string; col: number; color: string }>>
}

/** The terminal's screen, laid out by terminal.ts, as a PNG. */
export async function drawTerminal(spec: TermSpec): Promise<Buffer> {
  // Off the Mac: drawn in TypeScript with the same font (paint.ts).
  if (!ON_MAC) {
    const list = paintFonts(monoFont())
    if (!list.length) throw fail(502, 'capture_failed', 'This server has no font to draw the terminal with.')
    return paintTerminal(spec, list)
  }
  const { png } = await draw(TERMINAL_SCRIPT, { ...spec, fontFile: monoFont() })
  if (!png.length) throw fail(502, 'capture_failed', 'The Mac couldn’t draw the terminal.')
  return png
}

/**
 * A picture file on a machine without AppKit (Linux): a PNG, read and shrunk in TypeScript (png.ts)
 * and laid on white like the Mac's; anything else (a JPEG, an SVG, a PDF) needs a Mac for now, and
 * says so. 415 `unsupported_picture` lets Telescope pass over it to the next source when it picks.
 */
function drawFileHere(src: string, kind: 'pdf' | 'image'): { png: Buffer; width: number; height: number; page: number; pages: number } {
  let img: ReturnType<typeof decodePng> = null
  if (kind === 'image') {
    try {
      img = decodePng(readFileSync(src))
    } catch {}
  }
  if (!img) throw fail(415, 'unsupported_picture', kind === 'pdf' ? 'This server shows PNG pictures only; PDFs need a Mac for now.' : 'This server shows PNG pictures only; that one needs a Mac for now.')
  const flat = shrinkRgba(flatten(img), MAX_SIDE)
  return { png: encodePng(flat), width: flat.width, height: flat.height, page: 0, pages: 0 }
}

let font: string | null | undefined
/**
 * JetBrains Mono, as the Mac app ships it (the daemon inside the app, the app where it's
 * installed, this repository): Menlo stands in when there's none.
 */
export function monoFont(): string | null {
  if (font !== undefined) return font
  const here = dirname(fileURLToPath(import.meta.url))
  const bundled = 'Contents/Resources/Fonts/JetBrainsMono.ttf'
  const candidates = [
    process.env.HYPERNOVA_MONO_FONT,
    join(here, '../../../Fonts/JetBrainsMono.ttf'),
    join('/Applications/Hypernova.app', bundled),
    join(HOME, 'Applications/Hypernova.app', bundled),
    join(here, '../../../android/app/src/main/res/font/jetbrains_mono.ttf'),
  ]
  font = candidates.find((p): p is string => !!p && existsSync(p)) ?? null
  return font
}
