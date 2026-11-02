// scripts/test-with-limits.mjs, macos-unit's way of running the tests: a hang fails by name, the files
// after it still report, and nothing a test leaves behind keeps the run open.
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const script = join(import.meta.dirname, '..', '..', 'scripts', 'test-with-limits.mjs')
const dir = mkdtempSync(join(tmpdir(), 'hn-limits-'))
after(() => rmSync(dir, { recursive: true, force: true }))

/** Test files [files] (name → source) in a folder of their own; its name. */
let folders = 0
function files(sources: Record<string, string>): string {
  const name = `f${++folders}`
  mkdirSync(join(dir, name))
  for (const [file, source] of Object.entries(sources)) writeFileSync(join(dir, name, file), source)
  return name
}
/** Runs the script on a folder of [files]: its exit code and all it printed. */
function run(limits: [number, number, number], folder: string, summaryFile?: string): Promise<{ code: number | null; out: string }> {
  const env: NodeJS.ProcessEnv = { ...process.env, ...(summaryFile ? { GITHUB_STEP_SUMMARY: summaryFile } : {}) }
  // Inside a test: the runner would take itself for one of this run's files.
  delete env.NODE_TEST_CONTEXT
  if (!summaryFile) delete env.GITHUB_STEP_SUMMARY
  return new Promise((resolve) => {
    const p = spawn(process.execPath, [script, ...limits.map(String), `${folder}/*.test.mjs`], { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'] })
    let out = ''
    p.stdout.on('data', (d) => (out += d))
    p.stderr.on('data', (d) => (out += d))
    p.on('close', (code) => resolve({ code, out }))
  })
}
const summaryOf = (out: string) => out.slice(out.lastIndexOf("## The daemon's tests"))
const alive = (pid: number) => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}
/** A test file that starts a child holding its stdout, its pid in [pidFile], [then] its test's body. */
const leavesChild = (pidFile: string, then: string) => `
  import { test } from 'node:test'
  import { spawn } from 'node:child_process'
  import { writeFileSync } from 'node:fs'
  test('starts a child on its stdout', async () => {
    const c = spawn('sleep', ['120'], { stdio: 'inherit' })
    c.unref()
    writeFileSync(${JSON.stringify(pidFile)}, String(c.pid))
    ${then}
  })
`

test('a test that hangs, its file kept up by a timer: fails by name, the rest of its file runs, the file stops at its limit, and the next file still reports', async () => {
  const folder = files({
    'a-hang.test.mjs': `
      import { test } from 'node:test'
      test('passes first', () => {})
      test('hangs for ever', async () => { setInterval(() => {}, 1000); await new Promise(() => {}) })
      test('fails on its own', () => { throw new Error('no') })
      test('its own limit runs out', { timeout: 100 }, async () => { await new Promise(() => {}) })
      test('passes after', () => {})
    `,
    'b-next.test.mjs': `import { test } from 'node:test'\ntest('the next file passes', () => {})\n`,
  })
  const summaryFile = join(dir, 'summary.md')
  const t0 = Date.now()
  const r = await run([1, 3, 60], folder, summaryFile)
  assert.ok(Date.now() - t0 < 30_000, 'it didn’t wait for the run’s limit')
  assert.equal(r.code, 1)
  assert.match(r.out, /✔ the next file passes/)
  assert.match(r.out, /Over the limit of 3 s for a file, stopping: .*a-hang\.test\.mjs/)
  const summary = summaryOf(r.out)
  assert.match(summary, /^3 passed, /m)
  assert.match(summary, /ran out of time \(1\.0 s a test unless it sets its own, 3\.0 s a file\)/)
  assert.match(summary, /### Hit the time limit\n\n- hangs for ever \(.*a-hang\.test\.mjs:\d+:\d+\)\n- its own limit runs out \(/)
  assert.match(summary, /### Failed\n\n- fails on its own/)
  assert.match(summary, /### Stopped at the limit for a file\n\n- .*a-hang\.test\.mjs/)
  assert.match(summary, /### Slowest\n\n- 1\.\d s hangs for ever/)
  assert.ok(!summary.includes('Stopped after'))
  assert.equal(readFileSync(summaryFile, 'utf8').trim(), summary.trim(), 'on the run’s page too')
})

test('every test of a file that awaits between its tests is run and counted', async () => {
  // What --test-force-exit loses, and why it isn't used.
  const folder = files({
    'late.test.mjs': `
      import { test } from 'node:test'
      test('before the await', () => {})
      await new Promise((r) => setTimeout(r, 300))
      test('after the await', () => {})
    `,
  })
  const r = await run([30, 60, 90], folder)
  assert.equal(r.code, 0, r.out)
  assert.match(r.out, /✔ after the await/)
  assert.match(summaryOf(r.out), /^2 passed, 0 failed, of which 0 ran out of time/m)
})

test('a hanging test’s leftover child on its file’s stdout: both stop at the file’s limit, and the run ends', async () => {
  const pidFile = join(dir, 'hang-child.pid')
  const folder = files({ 'holds.test.mjs': leavesChild(pidFile, 'await new Promise(() => {})') })
  const t0 = Date.now()
  const r = await run([1, 3, 60], folder)
  assert.ok(Date.now() - t0 < 30_000, 'not left for the child’s two minutes')
  assert.equal(r.code, 1)
  assert.match(summaryOf(r.out), /### Hit the time limit\n\n- starts a child on its stdout/)
  assert.equal(alive(Number(readFileSync(pidFile, 'utf8'))), false)
})

test('a finished file’s leftover child on its stdout: stopped at the file’s limit, and the run ends as it would have', async () => {
  const pidFile = join(dir, 'done-child.pid')
  const folder = files({ 'done.test.mjs': leavesChild(pidFile, '') })
  const t0 = Date.now()
  const r = await run([1, 3, 60], folder)
  assert.ok(Date.now() - t0 < 30_000, 'not left for the child’s two minutes')
  assert.match(r.out, /✔ starts a child on its stdout/)
  assert.equal(alive(Number(readFileSync(pidFile, 'utf8'))), false)
  const summary = summaryOf(r.out)
  assert.match(summary, /^1 passed, /m)
  assert.match(summary, /### Stopped at the limit for a file\n\n- sleep 120/)
})

test('a file that hangs before its first test, where no test’s limit runs: stopped at the file’s limit, by name', async () => {
  const folder = files({ 'top.test.mjs': `setInterval(() => {}, 1000)\nawait new Promise(() => {})\n` })
  const r = await run([1, 3, 60], folder)
  assert.equal(r.code, 1)
  assert.match(summaryOf(r.out), /### Hit the time limit\n\n- .*top\.test\.mjs/)
})

test('the run’s limit stops it all and says what was still going', async () => {
  const folder = files({ 'long.test.mjs': `import { test } from 'node:test'\ntest('long', async () => { await new Promise((r) => setTimeout(r, 120_000)) })\n` })
  const r = await run([100, 110, 3], folder)
  assert.equal(r.code, 124)
  const summary = summaryOf(r.out)
  assert.match(summary, /Stopped after 3\.0 s/)
  assert.match(summary, /### Still running when stopped\n\n- .*long\.test\.mjs/)
})

test('limits that can’t work are refused', async () => {
  const r = await run([5, 5, 60], files({}))
  assert.equal(r.code, 2)
  assert.match(r.out, /usage/)
})
