// Telescope (docs/PROTOCOL.md, "Telescope"): the picker, the rules for files, and each source with
// recorded outputs: adb and simctl are stand-ins that print what the real ones printed, the Mac
// app's window round trip is a fake feed, and the drawings (a terminal, an SVG, a PDF page) are
// made by the Mac's own AppKit, as in use.
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync, spawn } from 'node:child_process'
import { crc32, deflateSync } from 'node:zlib'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hypernova-telescope-')))
// The scratch folder (a fake support folder, home, adb and projects) goes when the tests end.
after(() => rmSync(scratch, { recursive: true, force: true }))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
process.env.HYPERNOVA_CHROME = join(scratch, 'no-chrome')
mkdirSync(process.env.HOME, { recursive: true })

/** A real PNG of w × h grey pixels. */
function png(w: number, h: number, grey = 128): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4)
    len.writeUInt32BE(data.length)
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data])
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(crc32(body))
    return Buffer.concat([len, body, crc])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 0
  const rows = Buffer.alloc((w + 1) * h, grey)
  for (let y = 0; y < h; y++) rows[y * (w + 1)] = 0
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))])
}

/** A PDF with pages of these sizes (points), with a correct cross-reference table. */
function pdf(sizes: Array<[number, number]>): Buffer {
  const objs = [`<< /Type /Catalog /Pages 2 0 R >>`, `<< /Type /Pages /Kids [${sizes.map((_, i) => `${i + 3} 0 R`).join(' ')}] /Count ${sizes.length} >>`, ...sizes.map(([w, h]) => `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${w} ${h}] >>`)]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objs.forEach((o, i) => {
    offsets.push(out.length)
    out += `${i + 1} 0 obj\n${o}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

// adb as recorded: `adb devices -l` with the emulator up, then its screen, and the AVD's name.
// Every call is logged, so the tests see the exact arguments.
const adbLog = join(scratch, 'adb.log')
const adb = join(scratch, 'adb')
writeFileSync(join(scratch, 'screen.png'), png(108, 240, 200))
writeFileSync(
  adb,
  `#!${process.execPath}
const fs = require('fs'); const a = process.argv.slice(2)
fs.appendFileSync(${JSON.stringify(adbLog)}, JSON.stringify(a) + '\\n')
if (a[0] === 'devices') process.stdout.write(process.env.FAKE_ADB_DEVICES ?? 'List of devices attached\\n\\n')
else if (a[2] === 'exec-out') process.stdout.write(fs.readFileSync(${JSON.stringify(join(scratch, 'screen.png'))}))
else if (a[2] === 'emu') process.stdout.write('Pixel_8_API_35\\nOK\\n')
else process.exit(1)
`,
)
chmodSync(adb, 0o755)
process.env.HYPERNOVA_ADB = adb
const EMULATOR = 'List of devices attached\nemulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1\n\n'

// xcrun on a Mac with only the Command Line Tools (this one), with no Simulator booted, or with one.
const xcrun = join(scratch, 'xcrun')
writeFileSync(
  xcrun,
  `#!${process.execPath}
const fs = require('fs'); const a = process.argv.slice(2)
const mode = process.env.FAKE_SIMCTL
if (mode === 'clt') { process.stderr.write('xcrun: error: unable to find utility "simctl", not a developer tool or in PATH\\n'); process.exit(72) }
if (mode === 'off') { process.stderr.write('No devices are booted.\\n'); process.exit(149) }
fs.writeFileSync(a[a.length - 1], fs.readFileSync(${JSON.stringify(join(scratch, 'screen.png'))}))
process.stdout.write('Detected file type \\'PNG\\' from extension\\nWrote screenshot to: ' + a[a.length - 1] + '\\n')
`,
)
chmodSync(xcrun, 0o755)
process.env.HYPERNOVA_XCRUN = xcrun

const { clues, plan, deviceIn, picturesIn, usedDevice } = await import('../src/telescope/evidence.ts')
const { sessionPicture, pickPicture, sniff } = await import('../src/telescope/files.ts')
const { parseAdbDevices, chooseDevice, validSerial } = await import('../src/telescope/android.ts')
const { simctlProblem } = await import('../src/telescope/simulator.ts')
const { termLayout, TERM_COLORS } = await import('../src/telescope/terminal.ts')
const { drawFile, drawTerminal } = await import('../src/telescope/draw.ts')
const { appTargets, parsePs, captureWindow, windowAnswer, systemFolder } = await import('../src/telescope/window.ts')
const { telescope, shrink, MAX_SIDE } = await import('../src/telescope/index.ts')
const { macFeed } = await import('../src/guide.ts')
const { pngSize, shotPath, SHOTS_DIR } = await import('../src/shots.ts')

type Ev = Parameters<typeof clues>[0][number]
let n = 0
const bash = (command: string, ts: number, output = ''): Ev => ({ id: `e${++n}`, kind: 'tool', ts, toolUseId: `t${n}`, name: 'Bash', summary: '', input: { command }, status: 'done', output, exitCode: 0, durationMs: 1, diff: null, path: null })
const tool = (name: string, ts: number, input: Record<string, unknown>, path: string | null = null): Ev => ({ id: `e${++n}`, kind: 'tool', ts, toolUseId: `t${n}`, name, summary: '', input, status: 'done', output: '', exitCode: null, durationMs: 1, diff: null, path })

/** A project folder of its own, with files in it. */
function project(name: string, files: Record<string, Buffer | string> = {}): string {
  const dir = join(scratch, 'projects', name)
  mkdirSync(dir, { recursive: true })
  for (const [p, body] of Object.entries(files)) {
    mkdirSync(join(dir, p, '..'), { recursive: true })
    writeFileSync(join(dir, p), body)
  }
  return dir
}

const svg = '<?xml version="1.0"?>\n<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200"><rect width="400" height="200" fill="#fff"/><circle cx="100" cy="100" r="80" fill="#2a7"/></svg>\n'

// ------------------------------------------------------------------ the picker

test('the transcript points at each source by what the session ran, wrote and read', () => {
  const cwd = '/p/app'
  const list = clues([
    bash('npm run dev', 1),
    tool('Write', 2, { file_path: '/p/app/index.html' }, '/p/app/index.html'),
    bash('./gradlew :app:installDebug && adb -s emulator-5556 shell am start -n com.x/.Main', 3),
    bash('xcrun simctl boot "iPhone 16" && xcrun simctl install booted build/App.app', 4),
    bash('swift build -c release && .build/release/Hypernova --render-popover /tmp/x', 5),
    tool('Write', 6, { file_path: '/p/app/out/chart.svg' }, '/p/app/out/chart.svg'),
    bash('python3 plot.py', 7, 'Saved figure to out/sales.png\n'),
    tool('Read', 8, { file_path: '/p/app/docs/report.pdf', pages: '3-4' }, '/p/app/docs/report.pdf'),
    { id: 'a', kind: 'assistant', ts: 9, text: 'Open http://localhost:3000 and look at chart.png, or run adb install.', streaming: false },
  ], cwd)
  assert.deepEqual(
    list.map((c) => [c.source, c.at, 'device' in c ? c.device : 'path' in c ? `${c.path}${c.page ? `#${c.page}` : ''}${c.wrote ? ' wrote' : ''}` : '']),
    [
      ['image', 8, '/p/app/docs/report.pdf#3'],
      ['image', 7, '/p/app/out/sales.png'],
      ['image', 6, '/p/app/out/chart.svg wrote'],
      ['window', 5, ''],
      ['simulator', 4, ''],
      ['emulator', 3, 'emulator-5556'],
      ['web', 2, ''],
      ['web', 1, ''],
    ],
  )
  // Newest first, each source once; a page served from the folder may still be there; the
  // terminal last, for command-line work.
  assert.deepEqual(plan(list), ['image', 'window', 'simulator', 'emulator', 'web', 'terminal'])
  assert.deepEqual(plan([]), ['web', 'terminal'])
  assert.equal(usedDevice(list), 'emulator-5556')
  // What Claude only wrote about counts for nothing.
  assert.deepEqual(clues([{ id: 'a', kind: 'assistant', ts: 1, text: 'adb install, npm run dev, chart.png', streaming: false }], cwd), [])
  // A Flutter run on Chrome is a page, on macOS a window, not the emulator.
  assert.deepEqual(clues([bash('flutter run -d chrome', 1)], cwd).map((c) => c.source), ['web'])
  assert.deepEqual(clues([bash('flutter run -d macos', 1)], cwd).map((c) => c.source), ['window'])
  assert.deepEqual(clues([bash('flutter run', 1)], cwd).map((c) => c.source), ['emulator'])
  // adb and electron count when run, not when only named.
  const src = (cmd: string) => clues([bash(cmd, 1)], cwd).map((c) => c.source)
  for (const cmd of ['adb install app.apk', 'cd android && adb -s emulator-5554 shell', 'ANDROID_SERIAL=emulator-5554 adb shell', '"$ANDROID_HOME/platform-tools/adb" devices', '~/Library/Android/sdk/platform-tools/adb logcat | grep App', 'x=$(adb shell getprop)'])
    assert.deepEqual(src(cmd), ['emulator'], cmd)
  for (const cmd of ['grep -rn adb src', 'cat docs/adb.md', 'echo adb', 'rg "adb devices"'])
    assert.deepEqual(src(cmd), [], cmd)
  for (const cmd of ['npx electron .', 'electron .', 'npm run build && electron main.js', 'npx electron-forge start'])
    assert.deepEqual(src(cmd), ['window'], cmd)
  for (const cmd of ['npm i electron', 'grep -r electron package.json', 'npm i -D electron-builder'])
    assert.deepEqual(src(cmd), [], cmd)
})

test('the transcript is read quickly however much a session printed, and once per event', () => {
  const cwd = '/p/app'
  // Data a session prints (a float array, comma runs) once made every comma scan to the end.
  const floats = `[${Array.from({ length: 2600 }, (_, i) => (i / 7).toFixed(3)).join(',')}]`
  const commas = 'a,'.repeat(8192)
  const events = Array.from({ length: 40 }, (_, i) => bash('python3 plot.py', i, i % 2 ? floats : commas))
  let t = performance.now()
  assert.deepEqual(clues(events, cwd), [])
  assert.ok(performance.now() - t < 300, `took ${performance.now() - t} ms`)
  // The same events again (a watching client's next look): answered from what was kept.
  t = performance.now()
  clues(events, cwd)
  assert.ok(performance.now() - t < 20, `again took ${performance.now() - t} ms`)
  // Names separated by commas are still found, each one.
  assert.deepEqual(picturesIn('saved a.png,b.png and c.svg', cwd), ['/p/app/a.png', '/p/app/b.png', '/p/app/c.svg'])
  // A command still running gets its output later: that is read when it comes.
  const running = { ...bash('python3 plot.py', 50), status: 'running' } as Ev
  assert.deepEqual(clues([running], cwd), [])
  Object.assign(running, { status: 'done', output: 'Saved figure to out/sales.png' })
  assert.deepEqual(clues([running], cwd).map((c) => 'path' in c && c.path), ['/p/app/out/sales.png'])
})

test('the device a command names, and nothing that could become an option or a second command', () => {
  assert.equal(deviceIn('adb -s emulator-5554 install app.apk'), 'emulator-5554')
  assert.equal(deviceIn('adb -d -s R58M123ABC shell input tap 10 10'), 'R58M123ABC')
  assert.equal(deviceIn('ANDROID_SERIAL=192.168.1.20:5555 ./gradlew installDebug'), '192.168.1.20:5555')
  assert.equal(deviceIn('flutter run -d emulator-5556'), 'emulator-5556')
  assert.equal(deviceIn("adb -s 'adb-R58M-x7y._adb-tls-connect._tcp' install a.apk"), 'adb-R58M-x7y._adb-tls-connect._tcp')
  assert.equal(deviceIn('adb install app.apk'), null)
  assert.equal(deviceIn('adb -s -e install'), null)
  assert.equal(deviceIn('adb -s $(rm -rf ~) install'), null)
  for (const bad of ['-e', '--help', 'a b', 'x;rm', '$(id)', '', 'é']) assert.equal(validSerial(bad), false, bad)
})

test('picture names in a command line or its output, relative ones in the session folder', () => {
  assert.deepEqual(picturesIn('python3 plot.py --out charts/q3.png\nSaved figure to "charts/q3.png".', '/p/app'), ['/p/app/charts/q3.png'])
  assert.deepEqual(picturesIn('cp a.jpg ../b.webp; open ~/Desktop/c.pdf /tmp/d.svg', '/p/app'), ['/p/app/a.jpg', '/p/b.webp', '~/Desktop/c.pdf', '/tmp/d.svg'])
  assert.deepEqual(picturesIn('see https://example.com/x.png? nope: x.pngx, report.pdf.bak', '/p/app'), [])
})

// ------------------------------------------------------------------ the rules for files

test('a picture is shown only from inside the session folder, never a credential or Hypernova’s own', () => {
  const cwd = project('files', {
    'out/chart.png': png(4, 4),
    'out/chart.svg': svg,
    'docs/report.pdf': pdf([[200, 100]]),
    'out/fake.png': 'not a picture at all',
    '.ssh/diagram.png': png(4, 4),
    '.aws/graph.png': png(4, 4),
  })
  const outside = project('elsewhere', { 'secret.png': png(4, 4) })
  symlinkSync(join(outside, 'secret.png'), join(cwd, 'out', 'link.png'))
  mkdirSync(join(process.env.HYPERNOVA_SUPPORT!, 'attachments'), { recursive: true })
  writeFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'attachments', 'phone.png'), png(4, 4))

  assert.equal(sessionPicture('out/chart.png', cwd)?.type, 'png')
  assert.equal(sessionPicture(join(cwd, 'out/chart.svg'), cwd)?.type, 'svg')
  assert.equal(sessionPicture('docs/report.pdf', cwd)?.type, 'pdf')
  for (const p of ['out/fake.png', '.ssh/diagram.png', '.aws/graph.png', 'out/link.png', '../elsewhere/secret.png', join(outside, 'secret.png'), 'out/missing.png', 'out', 'out/chart.png\0x'])
    assert.equal(sessionPicture(p, cwd), null, p)
  // A session whose folder holds Hypernova's own (one in the home folder): its attachments stay out.
  assert.equal(sessionPicture(join(process.env.HYPERNOVA_SUPPORT!, 'attachments', 'phone.png'), scratch), null)
  // Nothing at all for a session at the root of the disk.
  assert.equal(sessionPicture('/etc/hosts.png', '/'), null)

  assert.equal(sniff(png(2, 2)), 'png')
  assert.equal(sniff(Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0])), 'jpeg')
  assert.equal(sniff(Buffer.from('GIF89a')), 'gif')
  assert.equal(sniff(Buffer.from('RIFF\0\0\0\0WEBPVP8 ')), 'webp')
  assert.equal(sniff(Buffer.from('%PDF-1.7\n')), 'pdf')
  assert.equal(sniff(Buffer.from(svg)), 'svg')
  assert.equal(sniff(Buffer.from('<html><body>an <svg> inside</body></html>')), 'svg')
  assert.equal(sniff(Buffer.from('hello')), null)
})

test('the newest picture the session made: one it wrote, or one a command made while it ran', () => {
  const cwd = project('pick', { 'old.png': png(4, 4), 'new.png': png(4, 4), 'drawn.svg': svg })
  const since = Date.now() - 3600_000
  // old.png was there long before the session: a command naming it (an `ls`) doesn't make it the session's.
  const long = (Date.now() - 48 * 3600_000) / 1000
  utimesSync(join(cwd, 'old.png'), long, long)
  utimesSync(join(cwd, 'drawn.svg'), long, long)
  const pick = (cs: Array<{ at: number; path: string; page: number | null; wrote: boolean }>) => pickPicture(cs, cwd, since)?.real.split('/').pop() ?? null
  assert.equal(pick([{ at: 2, path: join(cwd, 'old.png'), page: null, wrote: false }]), null)
  assert.equal(pick([{ at: 2, path: join(cwd, 'old.png'), page: null, wrote: false }, { at: 1, path: join(cwd, 'new.png'), page: null, wrote: false }]), 'new.png')
  // Written by the session itself: whatever its date says.
  assert.equal(pick([{ at: 3, path: join(cwd, 'drawn.svg'), page: null, wrote: true }, { at: 1, path: join(cwd, 'new.png'), page: null, wrote: false }]), 'drawn.svg')
  // A session in the home folder: only a picture it wrote itself, never one a command only named
  // there (a screenshot saved meanwhile, `ls ~/Pictures`), however new.
  const home = process.env.HOME!
  mkdirSync(join(home, 'Pictures'), { recursive: true })
  writeFileSync(join(home, 'Pictures/shot.png'), png(4, 4))
  writeFileSync(join(home, 'plot.png'), png(4, 4))
  const atHome = (cs: Array<{ at: number; path: string; page: number | null; wrote: boolean }>) => pickPicture(cs, home, since)?.real.split('/').pop() ?? null
  assert.equal(atHome([{ at: 2, path: join(home, 'Pictures/shot.png'), page: null, wrote: false }]), null)
  assert.equal(atHome([{ at: 2, path: join(home, 'Pictures/shot.png'), page: null, wrote: false }, { at: 1, path: join(home, 'plot.png'), page: null, wrote: true }]), 'plot.png')
})

// ------------------------------------------------------------------ each source

test('Android: adb devices as recorded, the device chosen, a refusal in plain words', () => {
  const recorded = [
    'List of devices attached',
    'emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1',
    'R58M123ABC             unauthorized usb:1-1 transport_id:3',
    '192.168.1.20:5555      offline transport_id:4',
    'adb-R58M-x7y._adb-tls-connect._tcp device product:husky model:Pixel_8_Pro device:husky transport_id:5',
    '',
  ].join('\n')
  const list = parseAdbDevices(recorded)
  assert.deepEqual(list.map((d) => [d.serial, d.state, d.model, d.emulator]), [
    ['emulator-5554', 'device', 'sdk gphone64 arm64', true],
    ['R58M123ABC', 'unauthorized', null, false],
    ['192.168.1.20:5555', 'offline', null, false],
    ['adb-R58M-x7y._adb-tls-connect._tcp', 'device', 'Pixel 8 Pro', false],
  ])
  const code = (f: () => unknown) => {
    try {
      f()
      return 'ok'
    } catch (e: any) {
      return `${e.status} ${e.code}: ${e.message}`
    }
  }
  assert.equal(chooseDevice(list, 'emulator-5554', null).serial, 'emulator-5554')
  assert.equal(chooseDevice(list, null, 'adb-R58M-x7y._adb-tls-connect._tcp').serial, 'adb-R58M-x7y._adb-tls-connect._tcp')
  // Several and nothing said: the emulator (the phone the owner can't name by voice is a "telescope phone").
  assert.equal(chooseDevice(list, null, 'emulator-5560').serial, 'emulator-5554')
  assert.equal(chooseDevice(list, null, null, 'phone').serial, 'adb-R58M-x7y._adb-tls-connect._tcp')
  assert.equal(chooseDevice(list, null, null, 'emulator').serial, 'emulator-5554')
  // The session's own device, unless the owner said the other kind.
  assert.equal(chooseDevice(list, null, 'adb-R58M-x7y._adb-tls-connect._tcp', 'emulator').serial, 'emulator-5554')
  assert.equal(code(() => chooseDevice(list.slice(0, 1), null, null, 'phone')), '404 no_phone: No Android phone is connected to the Mac.')
  assert.equal(code(() => chooseDevice(list.slice(3), null, null, 'emulator')), '404 no_emulator: No emulator is running.')
  // Two of a kind: the words ask for what the glasses can say.
  const two = parseAdbDevices('emulator-5554 device\nemulator-5556 device\nR58M1 device\nR58M2 device\n')
  assert.equal(code(() => chooseDevice(two, null, null)), '409 which_device: Several Android devices are connected (emulator-5554, emulator-5556, R58M1, R58M2). Say “telescope phone” or “telescope emulator”.')
  assert.equal(code(() => chooseDevice(two, null, null, 'phone')), '409 which_device: Several phones are connected (R58M1, R58M2), and the session didn’t name one.')
  assert.equal(chooseDevice(two, null, 'R58M2', 'phone').serial, 'R58M2')
  assert.equal(code(() => chooseDevice(list, 'R58M123ABC', null)), '409 device_unauthorized: Allow USB debugging on R58M123ABC first.')
  assert.equal(code(() => chooseDevice(list, 'emulator-9999', null)), '404 no_device: emulator-9999 isn’t connected to the Mac.')
  assert.equal(code(() => chooseDevice([], null, null)), '404 no_emulator: No emulator is running, and no Android phone is connected.')
  assert.equal(chooseDevice(list.slice(0, 1), null, 'emulator-5560').serial, 'emulator-5554')
})

test('iOS Simulator: what simctl says, in words for the lens', () => {
  const said = (s: string) => {
    const e = simctlProblem(s) as any
    return `${e.status} ${e.code}: ${e.message}`
  }
  assert.equal(said('xcrun: error: unable to find utility "simctl", not a developer tool or in PATH'), '501 no_simulator: The iOS Simulator needs Xcode on the Mac.')
  assert.equal(said('No devices are booted.'), '404 simulator_off: No iOS Simulator is running.')
  assert.equal(said('Something else broke'), '502 capture_failed: The iOS Simulator didn’t give a picture.')
})

test('the terminal: Claude Code’s screen laid out large, in the Hypernova colours', () => {
  const screen = [
    '', '',
    '> run the daemon tests',
    '',
    '⏺ Bash(cd daemon && npm test)',
    '  ⎿  ✔ credential files stay on the Mac (1.2ms)',
    '     ℹ tests 214 · pass 214 · fail 0',
    '',
    '⏺ All 214 tests pass.',
    '✻ Brewing… (12s · esc to interrupt)',
    '╭────────────────────╮',
    '│ > fix the lens     │',
    '╰────────────────────╯',
    '  ⏵⏵ accept edits on (shift+tab to cycle)',
    '', '', '',
  ].join('\n')
  const spec = termLayout(screen)
  // Blank lines above and below are gone; the font is as large as fits (30 at most).
  assert.equal(spec.lines.length, 12)
  assert.equal(spec.width, 1152)
  assert.equal(spec.height, 512)
  assert.ok(spec.fontSize > 25 && spec.fontSize <= 30, String(spec.fontSize))
  const c = TERM_COLORS
  const line = (i: number) => spec.lines[i]!.map((r) => [r.text, r.col, r.color])
  assert.deepEqual(line(0), [['>', 0, c.mint], ['run the daemon tests', 2, c.ink]])
  // ⏺ isn't in the font: drawn alone at its column, the words after it at theirs.
  assert.deepEqual(line(2), [['⏺', 0, c.mint], ['Bash(cd daemon && npm test)', 2, c.ink]])
  assert.deepEqual(line(3)[0], ['⎿', 2, c.dim])
  assert.equal(line(3)[1]![2], c.muted)
  assert.deepEqual(line(7)[0], ['✻', 0, c.mint])
  // The input box's frame is a hairline, what's typed in it ink.
  assert.deepEqual(line(8), [['╭────────────────────╮', 0, c.ghost]])
  assert.deepEqual(line(9), [['│', 0, c.ghost], ['> fix the lens', 2, c.ink], ['│', 21, c.ghost]])
  assert.equal(line(11)[0]![2], c.dim)
  // A long screen keeps its newest lines at a readable size rather than shrinking everything.
  const tall = termLayout(Array.from({ length: 60 }, (_, i) => `line ${i}`).join('\n'))
  assert.equal(tall.fontSize, 14)
  assert.equal(tall.lines.at(-1)![0]!.text, 'line 59')
  assert.ok(tall.lines.length < 60)
  // Wide characters take two columns.
  assert.deepEqual(termLayout('日本 ok').lines[0]!.map((r) => [r.text, r.col]), [['日', 0], ['本', 2], ['ok', 5]])
  // A wide terminal's long reply wraps at the smallest font rather than losing its end: each
  // continuation hung under the text, in the colour of the line it continues.
  const reply = `⏺ ${Array.from({ length: 60 }, (_, i) => `w${i}`).join(' ')} the end`
  const wide = termLayout(`${reply}\n  ${'x'.repeat(200)}\n╭${'─'.repeat(200)}╮\n> `)
  assert.equal(wide.fontSize, 14)
  const text = wide.lines.map((l) => l.map((r) => r.text).join(' '))
  assert.ok(text.join(' ').includes('w59 the end'), text.join('\n'))
  assert.deepEqual(wide.lines[0]![0]!.color, c.mint)
  assert.deepEqual([wide.lines[1]![0]!.col, wide.lines[1]![0]!.color], [2, c.ink])
  assert.ok(text.every((t, i) => wide.lines[i]!.every((r) => r.col + [...r.text].length <= 132)), 'nothing past the last column')
  // An indented line keeps its indent and hangs two further; a frame of box glyphs is only cut.
  const xs = wide.lines.filter((l) => l[0]?.text.startsWith('x'))
  assert.deepEqual(xs.map((l) => l[0]!.col), [2, 4])
  assert.equal(wide.lines.filter((l) => l[0]?.text.startsWith('╭')).length, 1)
})

test('drawn on the Mac: the terminal, an SVG chart and a PDF’s page, as PNGs', { skip: process.platform !== 'darwin' }, async () => {
  const term = await drawTerminal(termLayout('⏺ All 214 tests pass.\n> '))
  assert.deepEqual(pngSize(term), { width: 1152, height: 512 })
  const cwd = project('draw', { 'chart.svg': svg, 'report.pdf': pdf([[612, 792], [792, 612]]) })
  // A drawing is drawn large, so a zoomed quarter is sharp.
  const s = await drawFile(join(cwd, 'chart.svg'), 'image')
  assert.deepEqual([s.width, s.height], [1600, 800])
  const p = await drawFile(join(cwd, 'report.pdf'), 'pdf', 2)
  assert.deepEqual([p.page, p.pages, p.width, p.height], [2, 2, 1600, 1236])
  const first = await drawFile(join(cwd, 'report.pdf'), 'pdf', 99)
  assert.deepEqual([first.page, first.pages], [2, 2])
})

test('Mac app windows: only processes running a program built in the session folder', async () => {
  const cwd = project('macapp', {
    // A bundle that claims another app's id counts for nothing: windows are matched by process.
    'dist/Demo.app/Contents/Info.plist': '<plist><dict><key>CFBundleIdentifier</key><string>com.apple.Safari</string></dict></plist>',
    'dist/Demo.app/Contents/MacOS/Demo': '#!/bin/sh\n',
    '.build/release/Tool': '#!/bin/sh\n',
    'Notes.xcodeproj/project.pbxproj': '',
  })
  const derived = join(process.env.HOME!, 'Library/Developer/Xcode/DerivedData/Notes-abcdefghijklmnopqrstuvwxyzab/Build/Products/Debug/Notes.app/Contents/MacOS')
  const other = join(process.env.HOME!, 'Library/Developer/Xcode/DerivedData/Other-abcdefghijklmnopqrstuvwxyzab/Build/Products/Debug/Other.app/Contents/MacOS')
  for (const d of [derived, other]) mkdirSync(d, { recursive: true })
  writeFileSync(join(derived, 'Notes'), '')
  writeFileSync(join(other, 'Other'), '')
  const elsewhere = project('macapp-other', { '.build/release/Tool': '' })
  symlinkSync(join(elsewhere, '.build/release/Tool'), join(cwd, 'link-to-tool'))
  const ps = [
    `  101 /Applications/Safari.app/Contents/MacOS/Safari`,
    `  202 ${cwd}/dist/Demo.app/Contents/MacOS/Demo`,
    `  303 ${cwd}/.build/release/Tool`,
    `  404 ${elsewhere}/.build/release/Tool`,
    `  505 ${cwd}/link-to-tool`,
    `  606 ${derived}/Notes`,
    `  707 ${other}/Other`,
    `  808 ${cwd}/gone`,
    // Started from a shell in the session folder by a relative path, as `ps` shows it.
    `  909 .build/release/Tool`,
    `  910 ./bin/elsewhere`,
    `  911 node`,
    // Says it runs the session's Tool (argv[0] is whatever a process sets), but runs Messages.
    `  912 ${cwd}/.build/release/Tool`,
    `  ${process.pid} ${cwd}/.build/release/Tool`,
  ].join('\n')
  assert.deepEqual(parsePs(ps)[1], { pid: 202, exe: `${cwd}/dist/Demo.app/Contents/MacOS/Demo` })
  const folders = new Map([[909, cwd], [910, elsewhere]])
  // What the kernel loaded for each (lsof's first txt file).
  const exes = new Map([
    [202, `${cwd}/dist/Demo.app/Contents/MacOS/Demo`], [303, `${cwd}/.build/release/Tool`], [606, `${derived}/Notes`],
    [909, `${cwd}/.build/release/Tool`], [912, '/System/Applications/Messages.app/Contents/MacOS/Messages'],
  ])
  const roots = [cwd, realpathSync(join(derived, '../../../../'))]
  assert.deepEqual(await appTargets(cwd, ps, folders, exes), { pids: [202, 303, 606, 909], names: ['Demo', 'Tool', 'Notes'], roots })
  // A session in the home folder can't say which app it builds: every app would count.
  const none = { pids: [], names: [], roots: [] }
  assert.deepEqual(await appTargets(process.env.HOME!, ps, folders, exes), none)
})

test('Mac app windows: never in a folder of the Mac’s or of installed apps', async () => {
  const home = realpathSync(process.env.HOME!)
  for (const bad of ['/', home, join(home, '..'), '/Applications', '/System/Applications', '/Library/Apple', '/usr/local/bin', '/opt/homebrew', join(home, 'Applications'), join(home, 'Library'), join(home, 'Library/Mail')])
    assert.equal(systemFolder(bad, home), true, bad)
  for (const good of [join(home, 'Programming/app'), '/private/tmp/project', '/Volumes/Work/app']) assert.equal(systemFolder(good, home), false, good)
  // The real list: a session at /Applications names none of the apps installed there.
  assert.deepEqual(await appTargets('/Applications'), { pids: [], names: [], roots: [] })
  assert.deepEqual(await appTargets('/System/Applications'), { pids: [], names: [], roots: [] })
})

test('Mac app windows: a process that only names itself after the session’s program isn’t its', { skip: process.platform !== 'darwin' || !existsSync('/usr/bin/cc') }, async () => {
  const cwd = realpathSync(project('macapp-spoof', { '.build/release/.keep': '' }))
  const built = join(cwd, '.build/release/App')
  // A program really built in the folder (a copy of a system program is killed on launch).
  execFileSync('/usr/bin/cc', ['-x', 'c', '-', '-o', built], { input: '#include <unistd.h>\nint main(void) { sleep(20); return 0; }\n' })
  // It, and /bin/sleep started with its path as argv[0] (`exec -a`), which `ps` shows as if it
  // were the session's program.
  const real = spawn(built, [], { stdio: 'ignore' })
  const spoof = spawn('/bin/sleep', ['20'], { stdio: 'ignore', argv0: built })
  try {
    await new Promise((r) => setTimeout(r, 300))
    const listed = execFileSync('/bin/ps', ['-o', 'pid=,comm=', '-p', `${real.pid},${spoof.pid}`]).toString()
    assert.equal(parsePs(listed).filter((p) => p.exe === built).length, 2, listed)
    const t = await appTargets(cwd)
    assert.deepEqual([t.pids, t.names, t.roots], [[real.pid], ['App'], [cwd]])
  } finally {
    real.kill('SIGKILL')
    spoof.kill('SIGKILL')
  }
})

test('a window: asked of the Mac app on its feed alone, answered on the local route', { skip: process.platform !== 'darwin' && 'app windows are a Mac’s' }, async () => {
  const cwd = project('macapp2')
  const targets = { pids: [202], names: ['Demo'], roots: [cwd] }
  // No Mac app listening.
  await assert.rejects(captureWindow(cwd, true, targets), (e: any) => e.code === 'no_mac_app' && e.status === 503)
  const sent: any[] = []
  let reply: (m: any) => object = () => ({ png: png(64, 40).toString('base64'), app: 'Demo', title: 'Settings' })
  let closed: () => void = () => {}
  const ws = {
    readyState: 1, OPEN: 1,
    send(s: string) {
      const m = JSON.parse(s)
      sent.push(m)
      setTimeout(() => windowAnswer(m.job, Buffer.from(JSON.stringify(reply(m)))), 5)
    },
    once(ev: string, f: () => void) { if (ev === 'close') closed = f },
  }
  macFeed(ws as any)
  const c = await captureWindow(cwd, true, targets)
  assert.deepEqual(pngSize(c.png), { width: 64, height: 40 })
  assert.deepEqual([c.title, c.label], ['Demo · Settings', 'Demo'])
  assert.equal(sent[0].t, 'telescope.window')
  assert.match(sent[0].job, /^[0-9a-f]{24}$/)
  // The folders go along, for the Mac app to check each process's program again itself.
  assert.deepEqual([sent[0].pids, sent[0].roots, sent[0].ask, Object.keys(sent[0]).sort()], [[202], [cwd], true, ['ask', 'job', 'pids', 'roots', 't']])
  // It found none of them running from there.
  reply = () => ({ error: 'no_app' })
  await assert.rejects(captureWindow(cwd, true, targets), (e: any) => e.code === 'no_app' && e.status === 404)
  // Screen Recording not allowed yet: asked now on the Mac, or to be allowed there.
  reply = () => ({ error: 'no_permission', asked: true })
  await assert.rejects(captureWindow(cwd, true, targets), (e: any) => e.code === 'screen_recording' && /asking now/.test(e.message))
  reply = () => ({ error: 'no_permission', asked: false })
  await assert.rejects(captureWindow(cwd, false, targets), (e: any) => e.code === 'screen_recording' && /Allow Screen Recording/.test(e.message))
  reply = () => ({ error: 'no_window', app: 'Demo' })
  await assert.rejects(captureWindow(cwd, true, targets), (e: any) => e.code === 'no_window' && e.message === 'No window of Demo is open on the Mac.')
  // Something that isn't a picture is never kept.
  reply = () => ({ png: Buffer.from('nope').toString('base64'), app: 'Demo' })
  await assert.rejects(captureWindow(cwd, true, targets), (e: any) => e.code === 'capture_failed')
  // An answer for a job nobody waits for goes nowhere.
  assert.equal(windowAnswer('0'.repeat(24), Buffer.from('{}')), false)
  closed()
})

// ------------------------------------------------------------------ the route

const session = (cwd: string, extra: Record<string, unknown> = {}) => ({ id: `s-${++n}`, cwd, title: 'A session', projectName: 'app', createdAt: Date.now() - 3600_000, term: null, ...extra })

test('telescope: a named source, its picture as a token link, the same link while nothing changes', async () => {
  process.env.FAKE_ADB_DEVICES = EMULATOR
  writeFileSync(adbLog, '')
  const s = session(project('route1'))
  const shot = await telescope(s, [], { source: 'emulator' })
  assert.match(shot.url, /^\/v1\/shots\/[0-9a-f]{24}\.png$/)
  assert.deepEqual([shot.source, shot.title, shot.label, shot.device, shot.width, shot.height, shot.auto], ['emulator', 'Android emulator · Pixel 8 API 35', 'Pixel 8 API 35', 'emulator-5554', 108, 240, false])
  const token = shot.url.slice(10, 34)
  assert.ok(shotPath(token))
  assert.deepEqual(readFileSync(join(SHOTS_DIR, `${token}.png`)), readFileSync(join(scratch, 'screen.png')))
  // adb ran with fixed arguments and the device id it listed.
  assert.deepEqual(readFileSync(adbLog, 'utf8').trim().split('\n').map((l) => JSON.parse(l)), [['devices', '-l'], ['-s', 'emulator-5554', 'exec-out', 'screencap', '-p'], ['-s', 'emulator-5554', 'emu', 'avd', 'name']])
  // Unchanged: the same link (a watching client reloads nothing), taken again just now.
  const again = await telescope(s, [], { source: 'emulator' })
  assert.equal(again.url, shot.url)
  assert.equal(again.digest, shot.digest)
  assert.ok(again.at >= shot.at)
  // A device id that could be an option never reaches adb.
  await assert.rejects(telescope(s, [], { source: 'emulator', device: '-e' }), (e: any) => e.code === 'bad_device')
  await assert.rejects(telescope(s, [], { source: 'emulator', device: 'emulator-9' }), (e: any) => e.code === 'no_device')
  await assert.rejects(telescope(s, [], { source: 'binoculars' }), (e: any) => e.status === 400 && e.code === 'bad_source')
})

test('a picture larger than the lens and the phone can use is shrunk before it is kept', { skip: process.platform !== 'darwin' }, async () => {
  const big = png(1260, 2800, 90)
  const small = await shrink(big)
  assert.deepEqual(pngSize(small), { width: Math.round((1260 * MAX_SIDE) / 2800), height: MAX_SIDE })
  // One that fits is left as it is.
  const fits = png(1152, 512)
  assert.equal(await shrink(fits), fits)
})

test('watching: only the newest frames stay on disk, the picture asked for before stays', { skip: process.platform !== 'darwin' }, async () => {
  process.env.FAKE_ADB_DEVICES = EMULATOR
  const screen = join(scratch, 'screen.png')
  const before = readFileSync(screen)
  try {
    const s = session(project('watching'))
    writeFileSync(screen, png(1260, 2800, 10))
    const asked = await telescope(s, [], { source: 'emulator' })
    // Shrunk for the lens and the phone, not kept at the phone's own resolution.
    assert.deepEqual([asked.width, asked.height], [720, MAX_SIDE])
    const frames = []
    for (const grey of [20, 30, 40]) {
      writeFileSync(screen, png(1260, 2800, grey))
      frames.push(await telescope(s, [], { source: 'emulator', watch: true }))
    }
    const kept = (shot: { url: string }) => !!shotPath(shot.url.slice(10, 34))
    assert.deepEqual([asked, ...frames].map(kept), [true, false, true, true])
    // Unchanged while watching: the same link, nothing new written or removed.
    const same = await telescope(s, [], { source: 'emulator', watch: true })
    assert.equal(same.url, frames[2]!.url)
    assert.deepEqual([asked, ...frames].map(kept), [true, false, true, true])
  } finally {
    writeFileSync(screen, before)
  }
})

test('telescope picks by itself: a source passed over for something the owner can fix says what to do', { skip: process.platform !== 'darwin' }, async () => {
  const cwd = project('route-skipped', { 'out/chart.svg': svg })
  const wrote = tool('Write', 1, { file_path: join(cwd, 'out/chart.svg') }, join(cwd, 'out/chart.svg'))
  process.env.FAKE_ADB_DEVICES = 'List of devices attached\nemulator-5554 device\nemulator-5556 device\n\n'
  const shot = await telescope(session(cwd), [wrote, bash('adb install app.apk', 2)], {})
  assert.equal(shot.source, 'image')
  assert.deepEqual(shot.skipped, { source: 'emulator', code: 'which_device', message: 'Several Android devices: say “telescope phone” or “telescope emulator”.' })
  // Said by kind, it is found.
  process.env.FAKE_ADB_DEVICES = 'List of devices attached\nemulator-5554 device\nR58M123ABC device model:Pixel_8\n\n'
  const phone = await telescope(session(cwd), [], { source: 'emulator', prefer: 'phone' })
  assert.deepEqual([phone.device, phone.label, phone.skipped], ['R58M123ABC', 'Pixel 8', undefined])
  // Nothing passed over: nothing to say.
  process.env.FAKE_ADB_DEVICES = 'List of devices attached\n\n'
  assert.equal((await telescope(session(cwd), [wrote], {})).skipped, undefined)
})

test('telescope picks by itself: the newest source that has something, else why not', async () => {
  const cwd = project('route2', { 'out/chart.svg': svg })
  const s = session(cwd)
  const wrote = tool('Write', 1, { file_path: join(cwd, 'out/chart.svg') }, join(cwd, 'out/chart.svg'))
  // The emulator is newest, but none runs: the chart the session drew comes next.
  process.env.FAKE_ADB_DEVICES = 'List of devices attached\n\n'
  if (process.platform === 'darwin') {
    const shot = await telescope(s, [wrote, bash('adb install app.apk', 2)], {})
    assert.deepEqual([shot.source, shot.label, shot.auto, shot.path], ['image', 'chart.svg', true, realpathSync(join(cwd, 'out/chart.svg'))])
  }
  // Only the emulator in the transcript, and no emulator: that is what the lens says.
  await assert.rejects(telescope(s, [bash('adb install app.apk', 2)], {}), (e: any) => e.code === 'no_emulator' && /No emulator is running/.test(e.message))
  // Nothing at all (no page, no shared terminal).
  await assert.rejects(telescope(s, [], {}), (e: any) => e.status === 404 && e.code === 'nothing_to_show')
  // With the emulator up, it wins over the older chart.
  process.env.FAKE_ADB_DEVICES = EMULATOR
  const shot = await telescope(session(cwd), [wrote, bash('adb install app.apk', 2)], {})
  assert.equal(shot.source, 'emulator')
})

test('telescope image: a PDF at the page the session read, or the page asked for', { skip: process.platform !== 'darwin' }, async () => {
  const cwd = project('route3', { 'docs/report.pdf': pdf([[612, 792], [612, 792], [612, 792]]) })
  const s = session(cwd)
  const read = tool('Read', 1, { file_path: join(cwd, 'docs/report.pdf'), pages: '2' }, join(cwd, 'docs/report.pdf'))
  const shot = await telescope(s, [read], { source: 'image' })
  assert.deepEqual([shot.page, shot.pages, shot.label], [2, 3, 'report.pdf · page 2 of 3'])
  const third = await telescope(s, [read], { source: 'image', page: 3 })
  assert.deepEqual([third.page, third.label], [3, 'report.pdf · page 3 of 3'])
  await assert.rejects(telescope(session(project('route3b')), [], { source: 'image' }), (e: any) => e.code === 'no_picture')
})

test('telescope simulator: the Simulator’s screen, or plainly why not', { skip: process.platform !== 'darwin' && 'the iOS Simulator is a Mac’s' }, async () => {
  const s = session(project('route4'))
  process.env.FAKE_SIMCTL = 'clt'
  await assert.rejects(telescope(s, [], { source: 'simulator' }), (e: any) => e.status === 501 && e.message === 'The iOS Simulator needs Xcode on the Mac.')
  process.env.FAKE_SIMCTL = 'off'
  await assert.rejects(telescope(s, [], { source: 'simulator' }), (e: any) => e.status === 404 && e.message === 'No iOS Simulator is running.')
  process.env.FAKE_SIMCTL = 'booted'
  const shot = await telescope(s, [], { source: 'simulator' })
  assert.deepEqual([shot.source, shot.label, shot.width], ['simulator', 'iOS Simulator', 108])
  // Its scratch file is gone.
  assert.deepEqual(existsSync(join(SHOTS_DIR, 'tmp')) ? readdirSync(join(SHOTS_DIR, 'tmp')) : [], [])
})

test('telescope terminal: none for a session that isn’t in a shared terminal', async () => {
  await assert.rejects(telescope(session(project('route5')), [], { source: 'terminal' }), (e: any) => e.status === 409 && e.code === 'no_terminal')
})
