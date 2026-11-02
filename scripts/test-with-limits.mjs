#!/usr/bin/env node
// The daemon's tests with limits, so a hang costs a test and not the whole result (macos-unit, in
// server-packages.yml; docs/DECISIONS.md, "A time limit on each of macos-unit's tests"). From daemon/:
//
//   node ../scripts/test-with-limits.mjs <seconds a test> <seconds a file> <seconds the run> [glob]
//
// Three limits, as each covers what the one before can't, measured on Node 24:
//   a test   --test-timeout fails it by name. The rest of its file goes on, but what kept it waiting
//            (a timer, a socket, a child) keeps the file's process up, and the runner waits for that
//            for ever, with every later file's results behind it.
//   a file   this script ends that process and what it started, and any process of the run left
//            without its parent that's as old as the limit, such as a child holding a finished file's
//            stdout, which keeps the runner reading. The runner reports that file failed and goes on.
//            It also covers a file that hangs before its first test, where no test's limit runs.
//   the run  stops it all, and says what was still going.
//
// Not --test-force-exit, which would make the second limit simple: it exits once the tests known so far
// are done, so a file that awaits between its tests silently loses the rest. It lost 19 of 1243 here.
//
// Prints a summary, adds it to the run's page when there's one, and exits as the runner did: 124 if
// the run had to be stopped. `npm test` doesn't come through here.
import { execFileSync, spawn } from 'node:child_process'
import { appendFileSync } from 'node:fs'

const [testSeconds, fileSeconds, runSeconds, glob = 'test/**/*.test.ts'] = process.argv.slice(2)
const testMs = Number(testSeconds) * 1000
const fileMs = Number(fileSeconds) * 1000
const runMs = Number(runSeconds) * 1000
if (!(testMs > 0) || !(fileMs > testMs) || !(runMs > 0)) {
  console.error('usage: test-with-limits.mjs <seconds a test> <seconds a file, more than a test> <seconds the run> [glob]')
  process.exit(2)
}

// Its own process group, so that what it started can be told apart. Nothing outside it is ever signalled.
const runner = spawn(process.execPath, ['--test', `--test-timeout=${testMs}`, '--test-reporter=spec', glob], { stdio: ['ignore', 'pipe', 'pipe'], detached: true })
const killGroup = () => {
  try {
    process.kill(-runner.pid, 'SIGKILL')
  } catch {}
}

let output = ''
for (const stream of [runner.stdout, runner.stderr]) {
  stream.setEncoding('utf8')
  stream.on('data', (s) => {
    output += s
    process.stdout.write(s)
  })
}

/** The processes of the runner's group, but for the runner. */
function group() {
  try {
    return execFileSync('ps', ['-A', '-o', 'pid=,ppid=,pgid=,args='], { encoding: 'utf8' })
      .split('\n')
      .map((l) => /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(.*)$/.exec(l))
      .filter((m) => m && Number(m[3]) === runner.pid && Number(m[1]) !== runner.pid)
      .map((m) => ({ pid: Number(m[1]), ppid: Number(m[2]), args: m[4].replace(`${process.execPath} `, 'node ') }))
  } catch {
    return []
  }
}
const kill = (pid) => {
  try {
    process.kill(pid, 'SIGKILL')
  } catch {}
}

/** The file limit: a file's process (the runner's child), what it started, and anything left without its parent. */
const firstSeen = new Map()
const overLimit = []
function watch() {
  const now = Date.now()
  const procs = group()
  for (const p of procs) if (!firstSeen.has(p.pid)) firstSeen.set(p.pid, now)
  const tooOld = (p) => now - firstSeen.get(p.pid) >= fileMs
  const inGroup = new Set(procs.map((p) => p.pid))
  const doomed = new Set(procs.filter((p) => tooOld(p) && (p.ppid === runner.pid || !inGroup.has(p.ppid))))
  for (const p of doomed) {
    const what = p.args.replace(/ --\S+/g, '')
    overLimit.push(what)
    console.log(`\nOver the limit of ${fileMs / 1000} s for a file, stopping: ${what}`)
  }
  // Children before parents wouldn't matter: all of them go in this one pass.
  for (const d of doomed) for (const c of procs) if (c.ppid === d.pid) doomed.add(c)
  for (const p of doomed) kill(p.pid)
}
const watching = setInterval(watch, Math.min(5000, fileMs / 3))

let stopped = null
const stop = setTimeout(() => {
  clearInterval(watching)
  stopped = group().map((p) => p.args)
  killGroup()
}, runMs)

const code = await new Promise((done) => {
  runner.on('exit', (c, signal) => {
    // Its output comes in a moment after; something still holding the pipe won't be waited for.
    const late = setTimeout(() => done(c ?? (signal ? 1 : 0)), 2000)
    runner.on('close', (cc) => {
      clearTimeout(late)
      done(cc ?? c ?? 1)
    })
    killGroup()
  })
})
clearTimeout(stop)
clearInterval(watching)

/** The runner's own lines: a result of each test, in the order reported, and where each failure is. */
function read(text) {
  const results = []
  const places = new Map()
  // A test may set its own limit, which comes first: the end of the run says which ones ran out.
  const timedOut = new Set()
  let place = null
  let listed = null
  let listing = false
  const totals = {}
  for (const line of text.split('\n')) {
    const total = /^ℹ (tests|pass|fail|cancelled|skipped) (\d+)$/.exec(line)
    if (total) totals[total[1]] = Number(total[2])
    if (line.startsWith('✖ failing tests:')) listing = true
    if (listing && listed && line.includes('test timed out after')) timedOut.add(listed)
    const at = /^test at (\S+)$/.exec(line.trim())
    if (at) place = at[1]
    const m = /^\s*([✔✖]) (.+) \((\d+(?:\.\d+)?)ms\)(?: # .*)?$/.exec(line)
    if (!m) continue
    if (listing) {
      listed = m[2]
      if (place) places.set(m[2], place)
      place = null
    } else {
      results.push({ ok: m[1] === '✔', name: m[2], ms: Number(m[3]) })
    }
  }
  return { results, places, timedOut, totals }
}

const { results, places, timedOut, totals } = read(output)
const named = (r) => `${r.name}${places.has(r.name) ? ` (${places.get(r.name)})` : ''}`
const seconds = (ms) => `${(ms / 1000).toFixed(1)} s`
const failed = results.filter((r) => !r.ok)
const ranOut = (r) => r.ms >= testMs || timedOut.has(r.name)
const hitLimit = failed.filter(ranOut)
const others = failed.filter((r) => !ranOut(r))
const slowest = [...results].sort((a, b) => b.ms - a.ms).slice(0, 10)
const passed = results.length - failed.length

const out = ['', '## The daemon\'s tests', '']
if (stopped) out.push(`**Stopped after ${seconds(runMs)}, so this is only what was reported by then (a file's results wait for the files before it).**`, '')
out.push(`${totals.pass ?? passed} passed, ${(totals.fail ?? 0) + (totals.cancelled ?? 0) || failed.length} failed, of which ${hitLimit.length} ran out of time (${seconds(testMs)} a test unless it sets its own, ${seconds(fileMs)} a file)${totals.skipped ? `, ${totals.skipped} skipped` : ''}.`)
const list = (title, lines) => {
  if (lines.length) out.push('', `### ${title}`, '', ...lines.map((l) => `- ${l}`))
}
list('Hit the time limit', hitLimit.map(named))
list('Failed', others.map(named))
list('Stopped at the limit for a file', overLimit)
if (stopped) list('Still running when stopped', stopped.length ? stopped : ['nothing but the runner: look at the last test above'])
list('Slowest', slowest.map((r) => `${seconds(r.ms)} ${r.name}`))
const summary = out.join('\n') + '\n'
process.stdout.write(summary)
if (process.env.GITHUB_STEP_SUMMARY) {
  try {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary)
  } catch {}
}
process.exit(stopped ? 124 : code)
