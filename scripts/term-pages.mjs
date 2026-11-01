#!/usr/bin/env node
// The phone's two terminal pages (android/app/src/main/assets/term/ with xterm.js, term-ghostty/
// with ghostty-web) in a headless Chromium at the phone's size, with a stand-in for the app's
// bridge (window.HN) in place of TerminalScreen.kt: no phone, no daemon, nothing on the network.
//
//   node scripts/term-pages.mjs [--chrome <path>] [--runs 5] [--mb 4]
//
// Checks the Ghostty page (a screen shows, typing and keys go out, it fits the width, a finger
// scrolls the history and output doesn't pull it back, the fullscreen view gets wheel turns, the
// colours) and measures both pages: load time (page start to the bridge's ready()), a big output
// burst (parsed and drawn), and the time the page spends while nothing happens. Exits 1 when a
// check fails. Chromium: --chrome, $CHROME, Playwright's headless shell, or chromium on the PATH.
import { createServer } from 'node:http'
import { spawn, execFileSync } from 'node:child_process'
import { readFile, readdir } from 'node:fs/promises'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir, homedir } from 'node:os'
import { join, extname, normalize, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '..', 'android', 'app', 'src', 'main', 'assets')
const args = process.argv.slice(2)
const opt = (name, dflt) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : dflt }
const RUNS = Number(opt('runs', 5))
const MB = Number(opt('mb', 4))

// The Nothing Phone 3's screen (docs/DEV-SETUP.md's AVD): 1260 × 2800 at 480 dpi, so 420 × 933
// CSS pixels at 3×; the terminal's part of it, below the top bar and above the key bar.
const VIEW = { width: 420, height: 720, deviceScaleFactor: 3, mobile: true }

async function findChrome() {
  const given = opt('chrome', process.env.CHROME)
  if (given) return given
  const pw = join(homedir(), '.cache', 'ms-playwright')
  if (existsSync(pw)) {
    for (const d of (await readdir(pw)).filter((d) => d.startsWith('chromium_headless_shell-')).sort().reverse()) {
      const p = join(pw, d, 'chrome-headless-shell-linux64', 'chrome-headless-shell')
      if (existsSync(p)) return p
    }
  }
  for (const c of ['chromium', 'chromium-browser', 'google-chrome']) {
    try { return execFileSync('which', [c]).toString().trim() } catch {}
  }
  throw new Error('No Chromium: pass --chrome <path>')
}

function serve() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf', '.wasm': 'application/wasm' }
  const server = createServer(async (req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^\/+/, '')
    if (path.startsWith('..')) { res.writeHead(404); return res.end() }
    try {
      const body = await readFile(join(ASSETS, path))
      res.writeHead(200, { 'content-type': types[extname(path)] || 'application/octet-stream', 'cache-control': 'no-store' })
      res.end(body)
    } catch { res.writeHead(404); res.end() }
  })
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)))
}

async function launch(chrome) {
  const profile = mkdtempSync(join(tmpdir(), 'hn-term-pages-'))
  const proc = spawn(chrome, ['--headless', '--no-sandbox', '--disable-gpu', '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] })
  const url = await new Promise((ok, fail) => {
    let err = ''
    proc.stderr.on('data', (d) => {
      err += d
      const m = err.match(/DevTools listening on (ws:\/\/\S+)/)
      if (m) ok(m[1])
    })
    proc.on('exit', (c) => fail(new Error(`Chromium exited (${c}): ${err}`)))
  })
  return { proc, url, done() { proc.kill(); rmSync(profile, { recursive: true, force: true }) } }
}

/** A DevTools connection to one page. */
async function page(browserUrl) {
  const browser = new WebSocket(browserUrl)
  await new Promise((ok) => browser.addEventListener('open', ok, { once: true }))
  let n = 0
  const waiting = new Map()
  const events = []
  browser.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id && waiting.has(m.id)) {
      const { ok, fail } = waiting.get(m.id)
      waiting.delete(m.id)
      m.error ? fail(new Error(m.error.message)) : ok(m.result)
    } else if (m.method) events.push(m)
  })
  const send = (method, params = {}, sessionId) => new Promise((ok, fail) => {
    const id = ++n
    waiting.set(id, { ok, fail })
    browser.send(JSON.stringify({ id, method, params, sessionId }))
  })
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
  const cdp = (m, p) => send(m, p, sessionId)
  const evaluate = async (expression) => {
    const r = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    return r.result.value
  }
  // Closed with its tab, so no page goes on drawing behind the next one's measurements.
  const close = async () => { await send('Target.closeTarget', { targetId }).catch(() => {}); browser.close() }
  return { cdp, evaluate, events, close }
}

// The bridge's stand-in: what the page tells the app, kept for the checks.
const STUB = `
  window.__hn = { ready: 0, opens: [], sent: [], texts: [], scrolled: [], states: [], frames: 0 }
  window.HN = {
    ready() { __hn.ready = performance.now() }, state(j) { __hn.states.push(j) }, closed(c) { __hn.closed = c },
    exited() {}, tapped() {}, scrolled(a) { __hn.scrolled.push(a) }, open(c, r) { __hn.opens.push([c, r]); return 1 },
    send(id, b) { __hn.sent.push(atob(b)) }, sendText(id, t) { __hn.texts.push(t) }, shut() {}, took() {},
  }
  const tick = () => { __hn.frames++; requestAnimationFrame(tick) }; requestAnimationFrame(tick)
`

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64')
const wait = (ms) => new Promise((ok) => setTimeout(ok, ms))
async function until(p, expr, ms = 10000) {
  const end = Date.now() + ms
  for (;;) {
    const v = await p.evaluate(expr)
    if (v) return v
    if (Date.now() > end) throw new Error(`timed out waiting for ${expr}`)
    await wait(10)
  }
}

/** Opens [dir]'s page, ready and connected at the size it fits. Returns the page and its load time. */
async function open(browserUrl, base, dir) {
  const p = await page(browserUrl)
  await p.cdp('Page.enable')
  await p.cdp('Runtime.enable')
  await p.cdp('Emulation.setDeviceMetricsOverride', VIEW)
  await p.cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
  await p.cdp('Network.enable')
  await p.cdp('Network.setCacheDisabled', { cacheDisabled: true })
  await p.cdp('Page.addScriptToEvaluateOnNewDocument', { source: STUB })
  await p.cdp('Page.navigate', { url: `${base}/${dir}/index.html` })
  const ready = await until(p, '__hn.ready')
  await p.evaluate('hn.connect(null)')
  const [cols, rows] = await until(p, '__hn.opens[0]')
  // The daemon's hello: the terminal at this phone's size, no Mac window.
  await p.evaluate(`hn.rx(1, [['o'], ['t' + ${JSON.stringify(JSON.stringify({ t: 'hello', v: 1, cols, rows, tty: 0, phone: 1 }))}]])`)
  return { p, ready, cols, rows }
}

const rx = (items) => `hn.rx(1, ${JSON.stringify(items)})`

/** Lines like Claude Code's: colours, bold, box drawing, CJK and emoji. */
function burst(bytes) {
  const words = ['Reading', 'src/term/holder.ts', 'const', 'snapshot', '→', '✓', '漢字', '🚀', 'résumé', '│', '─────', 'claude']
  let out = ''
  let i = 0
  while (out.length < bytes) {
    const c = 31 + (i % 7)
    out += `\x1b[${c}m${String(i).padStart(6)}\x1b[0m \x1b[1m${words[i % words.length]}\x1b[22m ${words[(i * 7) % words.length]} ${'─'.repeat(i % 40)} ${words[(i * 3) % words.length]}\r\n`
    i++
  }
  return out
}

/** Feeds [data] as the app does (64 KiB frames of bytes), then waits until it's parsed and drawn. */
async function feed(p, data) {
  await p.evaluate('__hn.texts.length = 0; __hn.frames0 = __hn.frames')
  const t0 = await p.evaluate('performance.now()')
  const buf = Buffer.from(data, 'utf8')
  const frames = []
  for (let i = 0; i < buf.length; i += 65536) frames.push('b' + buf.subarray(i, i + 65536).toString('base64'))
  for (let i = 0; i < frames.length; i += 16) await p.evaluate(rx(frames.slice(i, i + 16)))
  // The end: a snapshot that turns the mouse on, whose write callback (after everything before it)
  // asks claude's view to its bottom (opened()), a message the stand-in sees.
  await p.evaluate(rx(['t' + JSON.stringify({ t: 'snapshot', data: '\x1b[?1000h' })]))
  await until(p, '__hn.texts.some((t) => t.includes("scroll"))', 120000)
  // And drawn: two frames more.
  const f = await p.evaluate('__hn.frames')
  await until(p, `__hn.frames >= ${f + 2}`)
  const t1 = await p.evaluate('performance.now()')
  return { ms: t1 - t0, frames: (await p.evaluate('__hn.frames - __hn.frames0')) }
}

async function idleCost(p) {
  await p.cdp('Performance.enable')
  const get = async () => Object.fromEntries((await p.cdp('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]))
  const a = await get()
  await wait(3000)
  const b = await get()
  return { taskMsPerSec: ((b.TaskDuration - a.TaskDuration) * 1000) / 3 }
}

async function drag(p, x, y0, y1, steps = 12) {
  await p.cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: y0 }] })
  for (let i = 1; i <= steps; i++) {
    await p.cdp('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y0 + ((y1 - y0) * i) / steps }] })
    await wait(16)
  }
  await wait(120)
  await p.cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`)
  if (!ok) failures.push(name)
}

async function checks(browserUrl, base) {
  const { p, cols, rows } = await open(browserUrl, base, 'term-ghostty')
  const peek = () => p.evaluate('hn.peek()')

  // A screen: the snapshot, then live output.
  await p.evaluate(rx(['t' + JSON.stringify({ t: 'snapshot', data: '\x1b[31mred\x1b[0m Claude Code\r\n> ' })]))
  await until(p, 'hn.peek().lines[0].includes("red")')
  await p.evaluate(rx(['b' + b64('hello from the daemon')]))
  let s = await peek()
  check('shows the snapshot and live output', s.lines[0] === 'red Claude Code' && s.lines[1] === '> hello from the daemon', JSON.stringify(s.lines.slice(0, 2)))

  // The colours: the page's background, and the theme's red on the first cell.
  const px = await p.evaluate(`(() => {
    const c = document.querySelector('canvas'), x = c.getContext('2d'), k = c.width / c.clientWidth
    const at = (cx, cy) => Array.from(x.getImageData(Math.floor(cx * k), Math.floor(cy * k), 1, 1).data.slice(0, 3))
    const h = hn.peek().cell.height, w = hn.peek().cell.width
    let red = null
    for (let yy = 0; yy < h && !red; yy++) for (let xx = 0; xx < w * 3 && !red; xx++) {
      const p = at(xx, yy); if (p[0] > 200 && p[1] < 160 && p[2] < 160) red = p
    }
    return { bg: at(c.clientWidth - 2, c.clientHeight - 2), red }
  })()`)
  check('background is #14110E', px.bg.join() === '20,17,14', px.bg.join())
  check("red text is the theme's red (#FF7878)", !!px.red, px.red ? px.red.join() : 'none found')

  // Typing and keys go out as bytes; arrows as claude's cursor mode asks.
  await p.evaluate('__hn.sent.length = 0; hn.text("hi"); hn.key("enter"); hn.key("up")')
  let sent = await p.evaluate('__hn.sent')
  check('typing and keys reach the socket', JSON.stringify(sent) === JSON.stringify(['hi', '\r', '\x1b[A']), JSON.stringify(sent))
  await p.evaluate(`${rx(['b' + b64('\x1b[?1h')])}; __hn.sent.length = 0; hn.key("up")`)
  sent = await p.evaluate('__hn.sent')
  check('arrows in application cursor mode', sent[0] === '\x1bOA', JSON.stringify(sent))
  await p.evaluate(`${rx(['b' + b64('\x1b[?1l\x1b[?2004h')])}; __hn.sent.length = 0; hn.paste("a\\nb")`)
  sent = await p.evaluate('__hn.sent')
  check('paste is bracketed when claude asks', sent[0] === '\x1b[200~a\nb\x1b[201~', JSON.stringify(sent))

  // Fit to the width: the columns fill the page, less than one cell (and ghostty-web's 15px
  // scrollbar gutter) to spare.
  const width = VIEW.width - 14
  const used = cols * s.cell.width
  check('fits the width', used <= width && width - used < s.cell.width + 16, `${cols}×${rows}, ${used}px of ${width}px`)

  // A finger scrolls the history; new output doesn't pull it back; Jump to latest does.
  await p.evaluate(rx(['b' + b64(Array.from({ length: 300 }, (_, i) => `line ${i}`).join('\r\n'))]))
  await p.evaluate('__hn.scrolled.length = 0')
  const shot = () => p.evaluate('new Promise((ok) => requestAnimationFrame(() => requestAnimationFrame(() => ok(document.querySelector("canvas").toDataURL()))))')
  const before = await shot()
  await drag(p, 200, 200, 600)
  s = await peek()
  const back = s.viewportY
  check('the screen is drawn again after a scroll', (await shot()) !== before)
  check('a drag scrolls the history', back > 10 && (await p.evaluate('__hn.scrolled[0] === true')), `viewport ${back} lines back of ${s.scrollback}`)
  const top = s.lines[0]
  await p.evaluate(rx(['b' + b64('\r\nmore output\r\nand more')]))
  s = await peek()
  check('new output leaves the history where the finger left it', s.lines[0] === top && s.viewportY === back + 2, `${JSON.stringify(top)} → ${JSON.stringify(s.lines[0])}, viewport ${s.viewportY}`)
  await p.evaluate('hn.latest()')
  s = await peek()
  check('Jump to latest', s.viewportY === 0 && (await p.evaluate('__hn.scrolled.at(-1) === false')))

  // Scrollback: how much history 4 MiB keeps (xterm.js keeps 5000 lines).
  await p.evaluate(rx(['b' + b64(Array.from({ length: 8000 }, (_, i) => `history ${i} ${'x'.repeat(60)}`).join('\r\n'))]))
  s = await peek()
  check('keeps about 5000 lines of history', s.scrollback >= 4000, `${s.scrollback} lines`)

  // Claude Code's fullscreen view with the mouse on: a drag goes to it as SGR wheel turns, never arrows.
  await p.evaluate(`${rx(['b' + b64('\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[Hfullscreen')])}; __hn.texts.length = 0; __hn.sent.length = 0`)
  await drag(p, 200, 200, 400)
  const texts = (await p.evaluate('__hn.texts')).map((t) => JSON.parse(t))
  sent = await p.evaluate('__hn.sent')
  const wheel = texts.filter((t) => t.t === 'scroll' && /^\x1b\[<64;\d+;\d+M$/.test(t.data))
  check('fullscreen: wheel turns up, as SGR', wheel.length > 3 && !sent.some((d) => d.includes('\x1b[A')), `${wheel.length} turns, first ${JSON.stringify(wheel[0]?.data)}`)
  // Without the mouse: pages.
  await p.evaluate(`${rx(['b' + b64('\x1b[?1000l')])}; __hn.texts.length = 0`)
  // A page is the screen less two lines: three long drags make at least one.
  for (let i = 0; i < 3; i++) await drag(p, 200, 60, 700, 30)
  const pg = (await p.evaluate('__hn.texts')).map((t) => JSON.parse(t)).filter((t) => t.t === 'scroll')
  check('fullscreen without the mouse: PgUp', pg.length >= 1 && pg.every((t) => t.data === '\x1b[5~'), JSON.stringify(pg))

  // Resize: the terminal tells the daemon its new size.
  await p.evaluate('__hn.texts.length = 0')
  await p.cdp('Emulation.setDeviceMetricsOverride', { ...VIEW, height: 400 })
  await until(p, '__hn.texts.some((t) => JSON.parse(t).t === "resize")')
  const rs = (await p.evaluate('__hn.texts')).map((t) => JSON.parse(t)).find((t) => t.t === 'resize')
  check('a smaller page resizes the terminal', rs.cols === cols && rs.rows < rows, JSON.stringify(rs))
  // Widths (informational): where the cursor lands after each sample, Ghostty here and xterm.js 6
  // (the daemon's @xterm/headless, the same parser and width tables as the page's xterm.js).
  const samples = ['✻ Thinking', '⏺ Bash', '🚀x', '漢字x', 'e\u0301x', '👋🏽x', '👨\u200d👩\u200d👧x', '❤\ufe0fx', '🇫🇷x', 'क्षx']
  const ghostty = {}
  for (const t of samples) {
    await p.evaluate(rx(['b' + b64('\x1b[?1049l\x1b[2J\x1b[H' + t)]))
    ghostty[t] = (await peek()).cursorX
  }
  let xterm = {}
  try {
    const { createRequire } = await import('node:module')
    const { Terminal } = createRequire(join(ASSETS, '..', '..', '..', '..', '..', 'daemon', 'package.json'))('@xterm/headless')
    const t = new Terminal({ cols: 80, rows: 10, allowProposedApi: true })
    for (const x of samples) { await new Promise((ok) => t.write('\x1b[2J\x1b[H' + x, ok)); xterm[x] = t.buffer.active.cursorX }
  } catch { xterm = null }
  console.log('\nCursor column after each sample (wider = more cells):')
  console.table(Object.fromEntries(samples.map((t) => [JSON.stringify(t), { ghostty: ghostty[t], 'xterm.js 6': xterm ? xterm[t] : 'n/a (no daemon/node_modules)' }])))
  await p.close()
}

async function measure(browserUrl, base) {
  const data = burst(MB * 1024 * 1024)
  const dirs = ['term', 'term-ghostty']
  const runs = Object.fromEntries(dirs.map((d) => [d, { loads: [], bursts: [], frames: [], idle: [], size: null }]))
  // The two pages take turns, so a busy moment on this machine weighs on both alike.
  for (let i = 0; i < RUNS; i++) {
    for (const dir of dirs) {
      const r = runs[dir]
      const { p, ready, cols, rows } = await open(browserUrl, base, dir)
      r.loads.push(ready)
      r.size = `${cols}×${rows}`
      r.idle.push((await idleCost(p)).taskMsPerSec)
      const b = await feed(p, data)
      r.bursts.push(b.ms)
      r.frames.push(b.frames)
      await p.close()
    }
  }
  const med = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)]
  const out = {}
  for (const dir of dirs) {
    const r = runs[dir]
    out[dir] = {
      size: r.size,
      loadMs: Math.round(med(r.loads)),
      burstMs: Math.round(med(r.bursts)),
      burstMsRange: `${Math.round(Math.min(...r.bursts))}–${Math.round(Math.max(...r.bursts))}`,
      burstFrames: med(r.frames),
      idleTaskMsPerSec: Number(med(r.idle).toFixed(1)),
    }
  }
  console.log(`\n${MB} MiB burst, ${RUNS} runs each (medians), ${VIEW.width}×${VIEW.height} @${VIEW.deviceScaleFactor}x, headless Chromium, no GPU:`)
  console.table(out)
}

const chrome = await findChrome()
const server = await serve()
const base = `http://127.0.0.1:${server.address().port}`
const browser = await launch(chrome)
try {
  console.log(`Chromium: ${chrome}`)
  await checks(browser.url, base)
  if (!args.includes('--checks-only')) await measure(browser.url, base)
} finally {
  browser.done()
  server.close()
}
if (failures.length) {
  console.log(`\n${failures.length} check(s) failed`)
  process.exit(1)
}
