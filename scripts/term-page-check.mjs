#!/usr/bin/env node
// The phone's terminal page (android/app/src/main/assets/term/, xterm.js and its add-ons) in a
// headless Chromium at the phone's size, with a stand-in for the app's bridge (window.HN) in place
// of TerminalScreen.kt: no phone, no daemon, nothing on the network. By hand, in no suite (it needs
// a Chromium, which CI doesn't install).
//
//   node scripts/term-page-check.mjs [--chrome <path>]
//   node scripts/term-page-check.mjs --measure --webgl <addon-webgl.js> [--gl gpu|swiftshader] [--runs 5] [--mb 4]
//
// Checks widths (a snapshot from the daemon's own headless terminal lines up here), links (a tap
// reaches the bridge, the right address, http and https only) and search. --measure instead
// compares the page's drawing with @xterm/addon-webgl added to it: `npm pack @xterm/addon-webgl@0.19.0`
// for the file, since the page doesn't ship it (docs/DECISIONS.md, "xterm.js add-ons"). Exits 1 when
// a check fails. Chromium: --chrome, $CHROME, Playwright's, or chromium on the PATH. The daemon's
// node_modules are used for the snapshot check.
import { createServer } from 'node:http'
import { spawn, execFileSync } from 'node:child_process'
import { readFile, readdir } from 'node:fs/promises'
import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir, homedir } from 'node:os'
import { join, extname, normalize, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const ASSETS = join(ROOT, 'android', 'app', 'src', 'main', 'assets')
const args = process.argv.slice(2)
const opt = (name, dflt) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : dflt }
const MEASURE = args.includes('--measure')
const WEBGL = opt('webgl', null)
const GL = opt('gl', 'swiftshader')
const RUNS = Number(opt('runs', 5))
const MB = Number(opt('mb', 4))

// The Nothing Phone 3's screen (docs/DEV-SETUP.md's AVD), less the top bar and the key bar.
const VIEW = { width: 420, height: 720, deviceScaleFactor: 3, mobile: true }

async function findChrome() {
  const given = opt('chrome', process.env.CHROME)
  if (given) return given
  const pw = join(homedir(), '.cache', 'ms-playwright')
  if (existsSync(pw)) {
    for (const d of (await readdir(pw)).filter((d) => /^chromium-\d+$/.test(d)).sort().reverse()) {
      const p = join(pw, d, 'chrome-linux64', 'chrome')
      if (existsSync(p)) return p
    }
  }
  for (const c of ['chromium', 'chromium-browser', 'google-chrome']) {
    try { return execFileSync('which', [c]).toString().trim() } catch {}
  }
  throw new Error('No Chromium: pass --chrome <path>')
}

function serve() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.ttf': 'font/ttf' }
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x')
    const path = normalize(decodeURIComponent(url.pathname)).replace(/^\/+/, '')
    if (path.startsWith('..')) { res.writeHead(404); return res.end() }
    try {
      let body
      if (path === 'webgl.js' && WEBGL) body = await readFile(WEBGL)
      else body = await readFile(join(ASSETS, path))
      // For --measure: the same page, with the WebGL add-on loaded once the terminal is open.
      if (path.endsWith('index.html') && url.searchParams.has('webgl')) {
        const before = body.toString('utf8')
        const after = before
          .replace('<script src="addon-fit.js"></script>', '<script src="addon-fit.js"></script><script src="/webgl.js"></script>')
          // The page's opaque viewport hides the add-on's canvas.
          .replace('.xterm .xterm-viewport { background: #14110E !important; }', '.xterm .xterm-viewport { background: transparent !important; }')
          .replace('term.open(el)\n', 'term.open(el)\n    const gl = new WebglAddon.WebglAddon(); gl.onContextLoss(() => { window.__lost = true; gl.dispose() }); term.loadAddon(gl); window.__gl = gl\n')
        if (after === before) throw new Error('the page changed: nowhere to add the WebGL add-on')
        body = after
      }
      res.writeHead(200, { 'content-type': types[extname(path)] || 'application/octet-stream', 'cache-control': 'no-store' })
      res.end(body)
    } catch { res.writeHead(404); res.end() }
  })
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)))
}

async function launch(chrome) {
  const profile = mkdtempSync(join(tmpdir(), 'hn-term-page-'))
  const gl = GL === 'gpu'
    ? ['--use-angle=vulkan', '--enable-features=Vulkan', '--ignore-gpu-blocklist', '--enable-gpu-rasterization']
    : ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']
  const proc = spawn(chrome, ['--headless=new', '--no-sandbox', ...gl, '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] })
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
  const close = async () => { await send('Target.closeTarget', { targetId }).catch(() => {}); browser.close() }
  return { cdp, evaluate, events, close }
}

// The bridge's stand-in: what the page tells the app, kept for the checks.
const STUB = `
  window.__hn = { ready: 0, opens: [], sent: [], texts: [], scrolled: [], links: [], tapped: 0, frames: 0 }
  window.HN = {
    ready() { __hn.ready = performance.now() }, state() {}, closed() {}, exited() {},
    tapped() { __hn.tapped++ }, link(u) { __hn.links.push(u) }, scrolled(a) { __hn.scrolled.push(a) },
    open(c, r) { __hn.opens.push([c, r]); return 1 }, send(id, b) { __hn.sent.push(atob(b)) },
    sendText(id, t) { __hn.texts.push(t) }, shut() {}, took() {},
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
const rx = (items) => `hn.rx(1, ${JSON.stringify(items)})`
const message = (m) => 't' + JSON.stringify(m)

/** The page, ready and connected. [unicode]: what the holder's hello says (undefined: an older holder). */
async function open(browserUrl, base, { unicode = '15-graphemes', webgl = false } = {}) {
  const p = await page(browserUrl)
  await p.cdp('Page.enable')
  await p.cdp('Runtime.enable')
  await p.cdp('Emulation.setDeviceMetricsOverride', VIEW)
  await p.cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
  await p.cdp('Page.addScriptToEvaluateOnNewDocument', { source: STUB })
  await p.cdp('Page.navigate', { url: `${base}/term/index.html${webgl ? '?webgl=1' : ''}` })
  const ready = await until(p, '__hn.ready')
  await p.evaluate('hn.connect(null)')
  const [cols, rows] = await until(p, '__hn.opens[0]')
  await p.evaluate(rx(['o', message({ t: 'hello', v: 1, cols, rows, tty: 0, phone: 1, ...(unicode ? { unicode } : {}) })]))
  return { p, ready, cols, rows }
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`)
  if (!ok) failures.push(name)
}

/** Where a cell is on the page, in CSS pixels. */
const cellAt = (p, col, row) => p.evaluate(`(() => {
  const r = document.querySelector('.xterm-screen').getBoundingClientRect(), s = hn.peek()
  return { x: r.left + (${col} + 0.5) * r.width / s.cols, y: r.top + (${row} + 0.5) * r.height / s.rows }
})()`)
async function tap(p, col, row) {
  const { x, y } = await cellAt(p, col, row)
  await p.cdp('Input.synthesizeTapGesture', { x, y })
  await wait(80)
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

async function widths(browserUrl, base) {
  const { p, cols, rows } = await open(browserUrl, base)
  check('hello turns the same widths on', (await p.evaluate('hn.peek().unicode')) === '15-graphemes')
  for (const [s, want] of [['🚀', 2], ['❤️', 2], ['👨‍👩‍👧', 2], ['👋🏽', 2], ['🇫🇷', 2], ['漢字', 4], ['⏺', 1], ['✻', 1], ['❯', 1], ['─', 1]]) {
    await p.evaluate(rx(['b' + b64('\x1b[2J\x1b[H' + s)]))
    const x = (await p.evaluate('hn.peek()')).cursorX
    check(`cursor after ${JSON.stringify(s)}`, x === want, `${x}`)
  }

  // A snapshot made by the daemon's own headless terminal, then live output after it.
  let need
  try { need = createRequire(join(ROOT, 'daemon', 'package.json')) } catch {}
  const lines = ['🚀 rocket', '❤️ heart', '👨‍👩‍👧 family', '漢字 cjk', '\x1b[31m🚀\x1b[0m\x1b[1m❤️\x1b[22m colours', '─'.repeat(10) + '🚀│']
  try {
    const { Terminal } = need('@xterm/headless')
    const { SerializeAddon } = need('@xterm/addon-serialize')
    const { UnicodeGraphemesAddon } = need('@xterm/addon-unicode-graphemes')
    const t = new Terminal({ cols, rows, scrollback: 1000, allowProposedApi: true })
    const ser = new SerializeAddon()
    t.loadAddon(ser)
    t.loadAddon(new UnicodeGraphemesAddon())
    const put = (d) => new Promise((ok) => t.write(d, ok))
    await put(lines.join('\r\n') + '\r\n🚀 half')
    await p.evaluate(rx([message({ t: 'snapshot', data: ser.serialize({ scrollback: 2000 }) })]))
    await until(p, 'hn.peek().lines[0].includes("rocket")')
    const more = ' a line ❤️ 👨‍👩‍👧 x'
    await put(more)
    await p.evaluate(rx(['b' + b64(more)]))
    await until(p, 'hn.peek().lines[6].endsWith("x")')
    const s = await p.evaluate('hn.peek()')
    const b = t.buffer.active
    const theirs = Array.from({ length: rows }, (_, i) => b.getLine(i)?.translateToString(true) ?? '')
    check("the daemon's snapshot lines up here", JSON.stringify(s.lines) === JSON.stringify(theirs), s.lines[6])
    check('the cursor where the daemon has it', s.cursorX === b.cursorX && s.cursorY === b.cursorY, `${s.cursorX},${s.cursorY}`)
  } catch (e) {
    if (e?.code !== 'MODULE_NOT_FOUND') throw e
    console.log("-- skipped the daemon's snapshot: no daemon/node_modules")
  }
  await p.close()

  // A holder from before this: what it kept by the old rules is drawn by them.
  const old = await open(browserUrl, base, { unicode: null })
  check('an older holder keeps the old widths', (await old.p.evaluate('hn.peek().unicode')) === '6')
  await old.p.close()
}

async function links(browserUrl, base) {
  const { p } = await open(browserUrl, base)
  const clear = () => p.evaluate('__hn.links.length = 0')
  const got = () => p.evaluate('__hn.links')
  await p.evaluate(rx([message({ t: 'snapshot', data: [
    'see https://example.com/a?b=1 for more',
    '🚀👨‍👩‍👧 https://example.org/wide then',
    'plain words here',
    '\x1b]8;;https://example.net/marked\x1b\\marked link\x1b]8;;\x1b\\ and',
    '\x1b]8;;file:///etc/passwd\x1b\\file link\x1b]8;;\x1b\\ and',
    'ftp://example.com/no and javascript:alert(1) here',
    'y'.repeat(30) + 'https://example.com/' + 'long'.repeat(30) + ' after',
  ].join('\r\n') })]))
  await until(p, 'hn.peek().lines[2].includes("plain")')

  await tap(p, 8, 0)
  check('a tap on an address reaches the app', JSON.stringify(await got()) === '["https://example.com/a?b=1"]', JSON.stringify(await got()))
  await clear()
  // After 🚀 (2) and the family (2) and a space: column 5 on. Widths and links agree.
  await tap(p, 9, 1)
  check('the right address after wide characters', JSON.stringify(await got()) === '["https://example.org/wide"]', JSON.stringify(await got()))
  await clear()
  await tap(p, 3, 2)
  check('a tap on words is not a link', (await got()).length === 0)
  await tap(p, 2, 3)
  check('an address the program marked (OSC 8)', JSON.stringify(await got()) === '["https://example.net/marked"]', JSON.stringify(await got()))
  await clear()
  await tap(p, 2, 4)
  await tap(p, 2, 5)
  await tap(p, 20, 5)
  check('never anything but http and https', (await got()).length === 0, JSON.stringify(await got()))
  // An address wrapped onto the next line: a tap on its second line.
  const s0 = await p.evaluate('hn.peek()')
  const wrapped = s0.lines.findIndex((l, i) => i > 6 && l.includes('long'))
  await tap(p, 4, wrapped)
  check('an address across wrapped lines is whole', (await got())[0] === 'https://example.com/' + 'long'.repeat(30), JSON.stringify(await got()))
  await clear()
  // Dragging from an address scrolls; it doesn't open anything.
  await p.evaluate(rx(['b' + b64(Array.from({ length: 200 }, (_, i) => `https://example.com/${i}`).join('\r\n'))]))
  await wait(100)
  const c = await cellAt(p, 8, 3)
  await drag(p, c.x, c.y, c.y + 200)
  check('a drag from an address scrolls and opens nothing', (await got()).length === 0, JSON.stringify(await got()))
  await p.evaluate('hn.latest()')
  // Claude Code's fullscreen view, the mouse on: a tap on an address still reaches the app.
  await p.evaluate(rx(['b' + b64('\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[2J\x1b[Hopen https://example.com/full now')]))
  await until(p, 'hn.peek().lines[0].includes("full")')
  await clear()
  await tap(p, 10, 0)
  check('in the fullscreen view too', JSON.stringify(await got()) === '["https://example.com/full"]', JSON.stringify(await got()))
  check('and claude gets no click for it', !(await p.evaluate('__hn.sent')).some((d) => d.includes('\x1b[<')) && !(await p.evaluate('__hn.texts')).some((t) => t.includes('[<0;')), JSON.stringify(await p.evaluate('__hn.sent')))
  // The line changed under the same spot: the address now there, not the one before.
  await p.evaluate(rx(['b' + b64('\x1b[2J\x1b[Hopen https://example.com/next now')]))
  await until(p, 'hn.peek().lines[0].includes("next")')
  await clear()
  await tap(p, 10, 0)
  check('the same spot after a redraw', JSON.stringify(await got()) === '["https://example.com/next"]', JSON.stringify(await got()))
  await p.close()
}

async function search(browserUrl, base) {
  const { p } = await open(browserUrl, base)
  const history = Array.from({ length: 800 }, (_, i) => (i === 100 || i === 500 ? `line ${i} the needle 🚀 here` : `line ${i} hay`)).join('\r\n')
  await p.evaluate(rx(['b' + b64(history)]))
  await until(p, 'hn.peek().lines.some((l) => l.includes("line 799"))')
  const before = await p.evaluate('hn.peek()')
  check('the screen is at the latest', before.viewportY === before.baseY && before.baseY > 0, `${before.viewportY}/${before.baseY}`)

  let r = await p.evaluate('hn.find("needle")')
  await wait(100)
  let s = await p.evaluate('hn.peek()')
  check('finds the newest match first', r.found && s.lines.some((l) => l.includes('line 500 the needle')), `${JSON.stringify(r)} viewport ${s.viewportY}`)
  check('says how many', r.count === 2, JSON.stringify(r))
  check('and shows it selected', s.selection === 'needle', JSON.stringify(s.selection))
  const decorated = await p.evaluate(`document.querySelectorAll('.xterm-decoration').length`)
  check('the match is marked on the screen', decorated > 0, `${decorated}`)
  check('the page says it moved away from the latest', (await p.evaluate('__hn.scrolled')).at(-1) === true)

  r = await p.evaluate('hn.findNext("needle")')
  await wait(100)
  s = await p.evaluate('hn.peek()')
  check('next goes on back', r.found && s.lines.some((l) => l.includes('line 100 the needle')), JSON.stringify(r))
  r = await p.evaluate('hn.findPrevious("needle")')
  await wait(100)
  s = await p.evaluate('hn.peek()')
  check('previous comes the other way', r.found && s.lines.some((l) => l.includes('line 500 the needle')), JSON.stringify(r))

  // Output while looking leaves the match where it is.
  const at = s.viewportY
  await p.evaluate(rx(['b' + b64('\r\nmore output\r\nand more\r\n')]))
  await wait(150)
  s = await p.evaluate('hn.peek()')
  check('output while looking stays put', s.viewportY === at, `${at} → ${s.viewportY}`)

  r = await p.evaluate('hn.find("no such words")')
  check('no match says so', !r.found && r.count === 0, JSON.stringify(r))
  await p.evaluate('hn.findClear()')
  await wait(50)
  check('clearing takes the marks away', (await p.evaluate(`document.querySelectorAll('.xterm-decoration').length`)) === 0)
  await p.evaluate('hn.latest()')
  await wait(100)
  s = await p.evaluate('hn.peek()')
  check('"Jump to latest" comes back', s.viewportY === s.baseY && (await p.evaluate('__hn.scrolled')).at(-1) === false, `${s.viewportY}/${s.baseY}`)
  await p.close()
}

async function stillWorks(browserUrl, base) {
  const { p } = await open(browserUrl, base)
  await p.evaluate('__hn.sent.length = 0; hn.text("hi🚀"); hn.key("enter")')
  const sent = await p.evaluate('__hn.sent')
  check('typing and keys still go out', Buffer.from(sent[0], 'latin1').toString('utf8') === 'hi🚀' && sent[1] === '\r', JSON.stringify(sent))
  await p.close()
}

// ── --measure ───────────────────────────────────────────────────────────────

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
const metrics = async (p) => Object.fromEntries((await p.cdp('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]))

/** Parsed and drawn: [data] in the app's 64 KiB frames, until a callback after all of it and two frames more. */
async function feed(p, data) {
  await p.evaluate('__hn.texts.length = 0; __hn.frames0 = __hn.frames')
  const a = await metrics(p)
  const t0 = await p.evaluate('performance.now()')
  const buf = Buffer.from(data, 'utf8')
  const frames = []
  for (let i = 0; i < buf.length; i += 65536) frames.push('b' + buf.subarray(i, i + 65536).toString('base64'))
  for (let i = 0; i < frames.length; i += 16) await p.evaluate(rx(frames.slice(i, i + 16)))
  await p.evaluate(rx([message({ t: 'snapshot', data: '\x1b[?1000h' })]))
  await until(p, '__hn.texts.some((t) => t.includes("scroll"))', 180000)
  const f = await p.evaluate('__hn.frames')
  await until(p, `__hn.frames >= ${f + 2}`)
  const t1 = await p.evaluate('performance.now()')
  const b = await metrics(p)
  return { ms: t1 - t0, taskMs: (b.TaskDuration - a.TaskDuration) * 1000, frames: await p.evaluate('__hn.frames - __hn.frames0') }
}

/** Steady output, as a working Claude Code draws it: its screen redrawn in place, about 30 times a second, for 3 s. */
async function steady(p, cols, rows) {
  const screen = (n) => {
    let s = '\x1b[H'
    for (let y = 0; y < rows; y++) s += `\x1b[${31 + ((y + n) % 7)}m${`${n} ⏺ 🚀 src/term/holder.ts `.repeat(8).slice(0, cols - 4)}\x1b[0m\x1b[K\r\n`
    return s.slice(0, -2)
  }
  const a = await metrics(p)
  const f0 = await p.evaluate('__hn.frames')
  const t0 = Date.now()
  let n = 0
  while (Date.now() - t0 < 3000) {
    await p.evaluate(rx(['b' + b64(screen(n++))]))
    await wait(33)
  }
  const f1 = await p.evaluate('__hn.frames')
  const b = await metrics(p)
  const s = (Date.now() - t0) / 1000
  return { fps: (f1 - f0) / s, taskMsPerSec: ((b.TaskDuration - a.TaskDuration) * 1000) / s }
}

/** A finger going through the history, held down for 2 s. */
async function scrolling(p) {
  await p.evaluate(rx(['b' + b64('\x1b[?1000l')]))
  const a = await metrics(p)
  const f0 = await p.evaluate('__hn.frames')
  const t0 = Date.now()
  await p.cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 100 }] })
  for (let i = 0; Date.now() - t0 < 2000; i++) {
    await p.cdp('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 200, y: 100 + (i % 2 ? 300 : 0) + i % 300 }] })
    await wait(16)
  }
  await p.cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  const f1 = await p.evaluate('__hn.frames')
  const b = await metrics(p)
  const s = (Date.now() - t0) / 1000
  return { fps: (f1 - f0) / s, taskMsPerSec: ((b.TaskDuration - a.TaskDuration) * 1000) / s }
}

/** The page while nothing happens but the cursor's blink. */
async function idle(p) {
  const a = await metrics(p)
  await wait(3000)
  const b = await metrics(p)
  return ((b.TaskDuration - a.TaskDuration) * 1000) / 3
}

async function measure(browserUrl, base) {
  if (!WEBGL) throw new Error('--measure needs --webgl <addon-webgl.js>')
  const data = burst(MB * 1024 * 1024)
  const kinds = {
    'DOM, old widths': { unicode: null },
    'DOM, graphemes': {},
    'WebGL, graphemes': { webgl: true },
  }
  const runs = Object.fromEntries(Object.keys(kinds).map((k) => [k, { load: [], idle: [], burst: [], burstTask: [], stFps: [], stTask: [], scFps: [], scTask: [], lost: 0, renderer: '' }]))
  // Taking turns, so a busy moment on this machine weighs on them alike.
  for (let i = 0; i < RUNS; i++) {
    for (const [k, o] of Object.entries(kinds)) {
      const r = runs[k]
      const { p, ready, cols, rows } = await open(browserUrl, base, o)
      await p.cdp('Performance.enable')
      r.load.push(ready)
      if (o.webgl) {
        r.renderer = await p.evaluate(`(() => { const c = [...document.querySelectorAll('canvas')].find((x) => x.getContext('webgl2')); if (!c) return 'no WebGL canvas'; const g = c.getContext('webgl2'); const e = g.getExtension('WEBGL_debug_renderer_info'); return e ? g.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'webgl2' })()`)
      }
      r.idle.push(await idle(p))
      const st = await steady(p, cols, rows)
      r.stFps.push(st.fps)
      r.stTask.push(st.taskMsPerSec)
      const b = await feed(p, data)
      r.burst.push(b.ms)
      r.burstTask.push(b.taskMs)
      const sc = await scrolling(p)
      r.scFps.push(sc.fps)
      r.scTask.push(sc.taskMsPerSec)
      if (o.webgl && await p.evaluate('!!window.__lost')) r.lost++
      await p.close()
    }
  }
  const med = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)]
  const out = {}
  for (const [k, r] of Object.entries(runs)) {
    out[k] = {
      'load ms': Math.round(med(r.load)),
      'idle task ms/s': Number(med(r.idle).toFixed(1)),
      'burst ms': Math.round(med(r.burst)),
      'burst range': `${Math.round(Math.min(...r.burst))}–${Math.round(Math.max(...r.burst))}`,
      'steady fps': Number(med(r.stFps).toFixed(1)),
      'steady task ms/s': Math.round(med(r.stTask)),
      'scroll fps': Number(med(r.scFps).toFixed(1)),
      'scroll task ms/s': Math.round(med(r.scTask)),
    }
    if (r.renderer) console.log(`${k}: renderer ${r.renderer}; context lost in ${r.lost} of ${RUNS} runs`)
  }
  console.log(`\n${MB} MiB burst, ${RUNS} runs each (medians), ${VIEW.width}×${VIEW.height} @${VIEW.deviceScaleFactor}x, headless Chromium, --gl ${GL}:`)
  console.table(out)
}

const chrome = await findChrome()
const server = await serve()
const base = `http://127.0.0.1:${server.address().port}`
const browser = await launch(chrome)
try {
  console.log(`Chromium: ${chrome}`)
  if (MEASURE) await measure(browser.url, base)
  else {
    await widths(browser.url, base)
    await links(browser.url, base)
    await search(browser.url, base)
    await stillWorks(browser.url, base)
  }
} finally {
  browser.done()
  server.close()
}
if (failures.length) {
  console.log(`\n${failures.length} check(s) failed`)
  process.exit(1)
}
