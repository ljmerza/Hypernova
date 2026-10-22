// A holder's end, as the daemon hears it (docs/PROTOCOL.md, "Shared terminals"): the exit code, and
// for the control client the screen as the command left it, wrapped lines joined back. The sign-in
// reads Claude Code's answer to a code there, printed just before it exits.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-holder-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { spawnHolder } = await import('../src/term/shared.ts')
const { ensureTerm, onTermExit, termAlive } = await import('../src/terms.ts')
const { afterCode } = await import('../src/account.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

test("the screen a command ended with comes with its exit, wrapped lines joined back", async () => {
  const code = 'AuthCode0123456789abcdefghijklmnopqrstuvwxyz#FakeState1x0123456789abcdefghij'
  const held = "Your account is on hold and can't sign in to Claude Code. View details or appeal: https://claude.ai/settings/account"
  // As `claude auth login` ends on an account on hold, in a terminal 40 columns wide: the prompt
  // and the typed code wrap, and so does the answer. A moment first, for the daemon to connect.
  const script = `sleep 1; printf 'Paste code here if prompted > %s\\n' "$1"; printf '%s\\n' "$2" >&2; exit 1`
  const id = 'e0f1a2b3c4'
  const pid = spawnHolder({
    id,
    cwd: scratch,
    file: '/bin/sh',
    args: ['-c', script, 'sh', code, held],
    env: { PATH: '/usr/bin:/bin', TERM: 'xterm-256color', LANG: 'en_US.UTF-8' },
    cols: 40,
    rows: 20,
    origin: 'hypernova',
    sessionId: null,
    terminal: { app: null },
    waitForTty: false,
    autoTrust: false,
  })
  const ended = new Promise<{ code: number | null; text: string | null }>((resolve) => onTermExit(id, (code, text) => resolve({ code, text })))
  for (let i = 0; i < 100 && termAlive(id, pid); i++) {
    ensureTerm(id)
    await new Promise((r) => setTimeout(r, 50))
  }
  let timer: NodeJS.Timeout | undefined
  const got = await Promise.race([ended, new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new Error('the holder never said it ended')), 10_000)))])
  clearTimeout(timer)
  assert.equal(got.code, 1)
  const lines = got.text!.split('\n')
  assert.ok(lines.includes(`Paste code here if prompted > ${code}`), got.text!)
  assert.ok(lines.includes(held), got.text!)
  // What the sign-in makes of it: the answer alone, never the code's wrapped end.
  assert.equal(afterCode(got.text!), held)
})
