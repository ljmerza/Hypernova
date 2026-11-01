// `hn ls` and `hn attach` (daemon/src/term/cli.ts; docs/PROTOCOL.md, "Shared terminals"): the
// shared terminals listed from a shell, and one opened in this terminal: its screen as it is now,
// keys typed into it, Ctrl+\ to detach while it keeps running. `hn attach` needs a real terminal,
// so it runs here in a pseudo-terminal of its own (node-pty, which the holder uses too).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import pty from 'node-pty'

const scratch = mkdtempSync(join(tmpdir(), 'hn-cli-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
const project = join(scratch, 'parser-app')
mkdirSync(project)

const { spawnHolder } = await import('../src/term/shared.ts')
const { waitForTerm, termAlive } = await import('../src/terms.ts')

const CLI = join(import.meta.dirname, '..', 'src', 'term', 'cli.ts')
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
/** The environment `hn` runs with: this file's scratch state, never the owner's. */
const env = (support = process.env.HYPERNOVA_SUPPORT!) => ({
  PATH: process.env.PATH!,
  HOME: process.env.HOME!,
  HYPERNOVA_SUPPORT: support,
  HYPERNOVA_LOGS: process.env.HYPERNOVA_LOGS!,
  CLAUDE_CONFIG_DIR: process.env.CLAUDE_CONFIG_DIR!,
  TERM: 'xterm-256color',
  LANG: 'en_US.UTF-8',
})

/** `hn <args>` without a terminal: its exit code and what it printed. */
function hn(args: string[], support?: string): Promise<{ code: number; out: string; err: string }> {
  return new Promise((resolve) => {
    execFile(process.execPath, [CLI, ...args], { env: env(support), timeout: 20_000 }, (e, out, err) => {
      resolve({ code: e ? ((e as any).code ?? 1) : 0, out, err })
    })
  })
}

/** `hn <args>` in a pseudo-terminal: everything it draws, what to type, and its exit. */
function hnTty(args: string[], support?: string) {
  const p = pty.spawn(process.execPath, [CLI, ...args], { name: 'xterm-256color', cols: 80, rows: 24, cwd: scratch, env: env(support) })
  let screen = ''
  let exit: number | null = null
  p.onData((d) => (screen += d))
  p.onExit(({ exitCode }) => (exit = exitCode))
  return { p, screen: () => screen, exit: () => exit }
}

async function until(what: string, cond: () => boolean, ms = 10_000): Promise<void> {
  const t0 = Date.now()
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${what}`)
    await sleep(20)
  }
}

const pids: number[] = []
after(() => {
  for (const pid of pids) {
    try {
      process.kill(pid, 'SIGKILL')
    } catch {}
  }
  rmSync(scratch, { recursive: true, force: true })
})

/** A shared terminal in [cwd] running a shell loop that says READY, then echoes each line it reads. */
async function holder(id: string, cwd = project): Promise<number> {
  const pid = spawnHolder({
    id,
    cwd,
    file: '/bin/sh',
    args: ['-c', 'echo READY-$0; while IFS= read -r l; do echo "got:$l"; done', id],
    env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' },
    cols: 80,
    rows: 24,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: null },
    waitForTty: false,
    autoTrust: false,
  })
  assert.ok(pid, 'the holder started')
  pids.push(pid!)
  assert.ok(await waitForTerm(id, 6000), 'the holder came up')
  return pid!
}

test('hn ls: none running says so; a running one is listed with its id, its folder and where it is open', async () => {
  const empty = join(scratch, 'empty-support')
  mkdirSync(empty, { recursive: true })
  const none = await hn(['ls'], empty)
  assert.equal(none.code, 0)
  assert.equal(none.out, 'No shared sessions are running.\n')

  const id = 'c1c1c1c1c1'
  await holder(id)
  const r = await hn(['ls'])
  assert.equal(r.code, 0, r.err)
  const lines = r.out.trimEnd().split('\n')
  assert.equal(lines.length, 1, r.out)
  // "<id>  <folder padded>  <age>  where it is open" (a Linux server: "in a terminal").
  assert.match(lines[0]!, new RegExp(`^${id}  parser-app\\s+\\d+s  not open ${process.platform === 'darwin' ? 'on the Mac' : 'in a terminal'}$`))
  // `hn list` is the same command.
  const age = (out: string) => out.replace(/ +\d+[smhd]  /, ' <age>  ')
  assert.equal(age((await hn(['list'])).out), age(r.out))
})

test('hn attach: refused without a terminal; in one it draws the screen, types into it, and Ctrl+\\ detaches leaving it running', async () => {
  const id = 'd2d2d2d2d2'
  const pid = await holder(id)
  for (let i = 0; i < 50 && !termAlive(id, pid); i++) await sleep(20)

  const noTty = await hn(['attach', id])
  assert.equal(noTty.code, 2)
  assert.equal(noTty.err, 'hn attach needs a terminal.\n')

  const a = hnTty(['attach', id])
  // Its screen as it is now (the holder's snapshot), from a clean screen.
  await until('the screen', () => a.screen().includes(`READY-${id}`))
  assert.ok(a.screen().includes('\x1b[H\x1b[2J'), 'a reattach starts from a clean screen')
  // Keys go to the command in the holder.
  a.p.write('hello there\r')
  await until('the echo', () => a.screen().includes('got:hello there'))
  // While attached, `hn ls` says it is open here (the holder writes that down a moment later).
  let open = ''
  for (let i = 0; i < 40 && !new RegExp(`^${id}  .*open here`, 'm').test(open); i++) {
    open = (await hn(['ls'])).out
    await sleep(100)
  }
  assert.match(open, new RegExp(`^${id}  parser-app\\s+\\d+s  open here ${process.platform === 'darwin' ? 'on the Mac' : 'in a terminal'}$`, 'm'), open)

  // Ctrl+\ detaches: a word about how to come back, exit 0, and the session goes on.
  a.p.write('\x1c')
  await until('the exit', () => a.exit() !== null)
  assert.equal(a.exit(), 0)
  assert.match(a.screen(), new RegExp(`Detached\\. The session keeps running: open it from .*hn attach ${id}`))
  await sleep(300)
  assert.ok(termAlive(id, pid), 'still running after the detach')

  // Attached again by the start of its id: the same screen, the line typed before on it.
  const b = hnTty(['attach', 'd2d2'])
  await until('the screen again', () => b.screen().includes('got:hello there'))
  b.p.write('\x1c')
  await until('the second exit', () => b.exit() !== null)
  assert.equal(b.exit(), 0)
})

test('hn attach: nothing matching, several matching, or nothing running says what there is and exits 1', async () => {
  const empty = join(scratch, 'empty-support-2')
  mkdirSync(empty, { recursive: true })
  const nothing = hnTty(['attach'], empty)
  await until('nothing running', () => nothing.exit() !== null)
  assert.equal(nothing.exit(), 1)
  assert.match(nothing.screen(), /No shared sessions are running\./)

  const miss = hnTty(['attach', 'no-such-session'])
  await until('the refusal', () => miss.exit() !== null)
  assert.equal(miss.exit(), 1)
  assert.match(miss.screen(), /No shared session matches “no-such-session”\. Running:/)
  assert.match(miss.screen(), /hn attach c1c1c1c1c1 +parser-app/)

  // Two holders in the same folder: its name matches both, so `hn attach` asks which.
  const twin = 'e3e3e3e3e3'
  await holder(twin)
  const both = hnTty(['attach', 'parser-app'])
  await until('the choice', () => both.exit() !== null)
  assert.equal(both.exit(), 1)
  assert.match(both.screen(), /More than one session matches; pick one:/)
  assert.match(both.screen(), new RegExp(`hn attach ${twin}`))
})
