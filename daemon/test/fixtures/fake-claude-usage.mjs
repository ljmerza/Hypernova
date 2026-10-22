// A stand-in for interactive Claude Code in the usage probe's test (usage-probe.test.ts), so no
// test ever starts the real one: it asks "trust this folder?", shows an empty input box, and when
// "/usage" is typed and entered, draws the screen in $FAKE_USAGE_SCREEN. Esc closes it again. Like
// Claude Code, it registers in <CLAUDE_CONFIG_DIR>/sessions/<pid>.json while it runs and writes a
// transcript line for the command, so the test can see that the daemon lists neither (it says it's
// busy while /usage is up, so the test can also see that it never counts as working). It notes
// its arguments, folder, a few environment variables and every key it got in
// $FAKE_USAGE_STATE/run.json.
import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const state = process.env.FAKE_USAGE_STATE
const claudeDir = process.env.CLAUDE_CONFIG_DIR
const args = process.argv.slice(2)
const sessionId = args[args.indexOf('--session-id') + 1]
const cwd = process.cwd()
// The environment the probe asked for (usage-probe.ts PROBE_ENV): quiet, and in English.
const env = Object.fromEntries(['DISABLE_AUTOUPDATER', 'DISABLE_TELEMETRY', 'DISABLE_ERROR_REPORTING', 'LANG', 'LC_ALL'].map((k) => [k, process.env[k] ?? null]))
const run = { args, cwd, keys: '', pid: process.pid, env }
const note = () => writeFileSync(join(state, 'run.json'), JSON.stringify(run))
note()

const registry = join(claudeDir, 'sessions', `${process.pid}.json`)
mkdirSync(join(claudeDir, 'sessions'), { recursive: true })
const register = (status) => writeFileSync(registry, JSON.stringify({ pid: process.pid, sessionId, cwd, startedAt: Date.now(), kind: 'interactive', status }))
register('idle')
const bye = () => {
  try {
    unlinkSync(registry)
  } catch {}
  process.exit(0)
}
process.on('SIGHUP', bye)
process.on('SIGTERM', bye)

const rule = '─'.repeat(100)
const draw = (lines) => process.stdout.write('\x1b[2J\x1b[H' + lines.join('\r\n'))
const start = ['╭─── Claude Code v9.9.9 ───╮', '│      Welcome back!      │', '╰─────────────────────────╯', '']
const box = (text) => draw([...start, rule, `❯ ${text}`, rule, '  ? for shortcuts'])

let mode = 'trust'
let typed = ''
draw(['Do you trust the files in this folder?', '', cwd, '', '❯ Yes, I trust this folder', '  No, exit', '', 'Enter to confirm · Esc to cancel'])
process.stdout.write('\x1b[?2004h') // bracketed paste, as Claude Code asks for

process.stdin.setRawMode?.(true)
process.stdin.on('data', (d) => {
  const s = d.toString('utf8')
  run.keys += s
  note()
  if (mode === 'trust') {
    if (s.includes('\r')) {
      mode = 'box'
      setTimeout(() => box(''), 300)
    }
    return
  }
  if (mode === 'usage') {
    if (s.includes('\x1b') && !s.includes('[')) {
      mode = 'box'
      register('idle')
      box('')
    }
    return
  }
  if (s.includes('\r')) {
    const cmd = typed.trim()
    typed = ''
    if (cmd === '/usage') {
      mode = 'usage'
      register('busy')
      mkdirSync(join(claudeDir, 'projects', cwd.replace(/[^a-zA-Z0-9]/g, '-')), { recursive: true })
      writeFileSync(
        join(claudeDir, 'projects', cwd.replace(/[^a-zA-Z0-9]/g, '-'), `${sessionId}.jsonl`),
        JSON.stringify({ type: 'user', cwd, sessionId, timestamp: new Date().toISOString(), message: { role: 'user', content: '<command-name>/usage</command-name>' } }) + '\n',
      )
      draw(['Loading usage data…'])
      setTimeout(() => draw(readFileSync(process.env.FAKE_USAGE_SCREEN, 'utf8').replace(/\n$/, '').split('\n')), 600)
    } else box('')
    return
  }
  typed += s.replace(/\x1b\[20[01]~/g, '')
  box(typed)
})
