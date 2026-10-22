// The integration's settings.json and status line (docs/PROTOCOL.md, "Hooks"): any status line is
// tapped (a bash script in place, anything else wrapped and given back on uninstall), settings.json
// keeps its mode and its dotfiles symlink, and hooks with a stale secret are brought up to date.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-hooks-'))
process.env.HOME = join(scratch, 'home')
// Where the tap writes on a Mac ("$HOME/Library/Application Support/Hypernova"), and where it reads.
process.env.HYPERNOVA_SUPPORT = join(process.env.HOME, 'Library', 'Application Support', 'Hypernova')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(process.env.HOME, '.claude')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const hooks = await import('../src/hooks.ts')
const { hookSecret } = await import('../src/store.ts')
const { LOCAL_PORT, SUPPORT } = await import('../src/util.ts')
const SETTINGS = join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json')
const read = () => JSON.parse(readFileSync(SETTINGS, 'utf8'))
const write = (o: object) => writeFileSync(SETTINGS, JSON.stringify(o))

test('a status line of any shape is tapped, runs as before, and comes back as it was', () => {
  const original = `printf 'ctx %s' "$(cat | head -c 14)"`
  write({ statusLine: { type: 'command', command: original, padding: 0 } })
  hooks.installIntegration()
  const s = read()
  assert.match(s.statusLine.command, /^bash ".*\/bin\/statusline\.sh" --then [A-Za-z0-9+/=]+$/)
  assert.equal(s.statusLine.padding, 0)
  assert.deepEqual(hooks.integrationStatus().statusLine, { installed: true, supported: true })
  assert.ok(s.hooks.SubagentStop, 'SubagentStop goes in with the rest')
  // Claude Code runs it with the status JSON on stdin: the owner's line prints, the tap writes.
  const input = JSON.stringify({ session_id: 'sess-wrap-1', model: { id: 'claude-opus-5-5' } })
  const out = execFileSync('/bin/sh', ['-c', s.statusLine.command], { input, env: { ...process.env, PATH: '/usr/bin:/bin' }, encoding: 'utf8' })
  assert.equal(out, 'ctx {"session_id":')
  assert.equal(JSON.parse(readFileSync(join(SUPPORT, 'statusline', 'sess-wrap-1.json'), 'utf8')).model.id, 'claude-opus-5-5')
  // Installing again doesn't wrap it twice.
  hooks.installIntegration()
  assert.equal(read().statusLine.command, s.statusLine.command)
  hooks.uninstallIntegration()
  assert.deepEqual(read().statusLine, { type: 'command', command: original, padding: 0 })

  // ccstatusline through npx, a Python script, a bash script that reads its input another way.
  const py = 'python3 ~/.claude/statusline.py'
  const other = join(process.env.HOME!, 'line.sh')
  writeFileSync(other, '#!/bin/bash\nread -r input\necho "$input"\n')
  for (const cmd of ['npx -y ccstatusline@latest', py, `bash "${other}"`]) {
    write({ statusLine: { type: 'command', command: cmd } })
    hooks.installIntegration()
    assert.match(read().statusLine.command, /--then /, cmd)
    hooks.uninstallIntegration()
    assert.equal(read().statusLine.command, cmd)
  }
  assert.equal(readFileSync(other, 'utf8'), '#!/bin/bash\nread -r input\necho "$input"\n')

  // A bash script with input=$(cat): tapped in place, as before.
  const tapped = join(process.env.HOME!, 'tapped.sh')
  writeFileSync(tapped, '#!/bin/bash\ninput=$(cat)\necho hi\n', { mode: 0o755 })
  write({ statusLine: { type: 'command', command: `bash "${tapped}"` } })
  hooks.installIntegration()
  assert.equal(read().statusLine.command, `bash "${tapped}"`)
  assert.ok(readFileSync(tapped, 'utf8').includes('# >>> hypernova'))
  hooks.uninstallIntegration()
  assert.equal(readFileSync(tapped, 'utf8'), '#!/bin/bash\ninput=$(cat)\necho hi\n')

  // None: Hypernova's own silent one, gone again on uninstall.
  write({})
  hooks.installIntegration()
  assert.match(read().statusLine.command, /^bash ".*\/bin\/statusline\.sh"$/)
  hooks.uninstallIntegration()
  assert.equal(read().statusLine, undefined)
})

test('settings.json keeps its mode and its dotfiles symlink; a new one is private', () => {
  unlinkSync(SETTINGS)
  hooks.installIntegration()
  assert.equal(statSync(SETTINGS).mode & 0o777, 0o600)
  hooks.uninstallIntegration()
  unlinkSync(SETTINGS)
  const dotfiles = join(process.env.HOME!, 'dotfiles')
  mkdirSync(dotfiles, { recursive: true })
  const real = join(dotfiles, 'settings.json')
  writeFileSync(real, JSON.stringify({ env: { SECRET_TOKEN: 'x' } }), { mode: 0o600 })
  chmodSync(real, 0o600)
  symlinkSync(real, SETTINGS)
  hooks.installIntegration()
  assert.ok(lstatSync(SETTINGS).isSymbolicLink(), 'still a link')
  assert.equal(statSync(real).mode & 0o777, 0o600)
  assert.ok(JSON.parse(readFileSync(real, 'utf8')).hooks.Stop)
  // An install from before the optional hooks: added through the link too.
  const s = JSON.parse(readFileSync(real, 'utf8'))
  delete s.hooks.SubagentStop
  writeFileSync(real, JSON.stringify(s))
  assert.equal(hooks.addOptionalHooks(), true)
  assert.ok(lstatSync(SETTINGS).isSymbolicLink())
  assert.equal(statSync(real).mode & 0o777, 0o600)
  hooks.uninstallIntegration()
  assert.ok(lstatSync(SETTINGS).isSymbolicLink())
  assert.deepEqual(JSON.parse(readFileSync(real, 'utf8')), { env: { SECRET_TOKEN: 'x' } })
})

test('hooks carrying another secret (a lost hook-secret file, a baked image) get the current one', () => {
  const stale = `http://127.0.0.1:${LOCAL_PORT}/hooks/stale-secret`
  const elsewhere = 'http://127.0.0.1:1/hooks/someone-else'
  write({ hooks: { Stop: [{ hooks: [{ type: 'http', url: stale, timeout: 10 }, { type: 'http', url: elsewhere }] }], PreToolUse: [{ matcher: '*', hooks: [{ type: 'command', command: 'true' }, { type: 'http', url: stale }] }] } })
  assert.equal(hooks.healHooks(), true)
  const s = read()
  const now = `http://127.0.0.1:${LOCAL_PORT}/hooks/${hookSecret}`
  assert.equal(s.hooks.Stop[0].hooks[0].url, now)
  assert.equal(s.hooks.Stop[0].hooks[1].url, elsewhere)
  assert.equal(s.hooks.PreToolUse[0].hooks[1].url, now)
  assert.equal(s.hooks.PreToolUse[0].hooks[0].command, 'true')
  assert.equal(hooks.healHooks(), false)
  assert.ok(existsSync(join(SUPPORT, 'backups')))
})

test('the log folder and the log are the owner’s alone', async () => {
  const { LOGS, log } = await import('../src/util.ts')
  log('a line')
  assert.equal(statSync(LOGS).mode & 0o777, 0o700)
  assert.equal(statSync(join(LOGS, 'daemon.log')).mode & 0o777, 0o600)
})

test('PreCompact goes in with the optional hooks, and a hook call hands it (and the others) to the automations', async () => {
  write({})
  hooks.installIntegration()
  assert.ok(read().hooks.PreCompact, 'installed with the rest')
  const s = read()
  delete s.hooks.PreCompact
  write(s)
  assert.equal(hooks.addOptionalHooks(), true)
  assert.ok(read().hooks.PreCompact)
  const { onHookEvent } = await import('../src/hookevents.ts')
  const heard: string[] = []
  onHookEvent((event, sid, tool, line) => heard.push(`${event}|${sid}|${tool}|${line}`))
  const cwd = join(scratch, 'proj')
  mkdirSync(cwd, { recursive: true })
  await hooks.handleHook({ session_id: 'sess-pc', hook_event_name: 'PreCompact', cwd, trigger: 'auto' }, {} as never, {} as never)
  await hooks.handleHook({ session_id: 'sess-pc', hook_event_name: 'PostToolUseFailure', cwd, tool_name: 'Bash', tool_input: { command: 'npm test' }, tool_use_id: 'tu1', error: 'exit 1\nmore' }, {} as never, {} as never)
  await hooks.handleHook({ session_id: 'sess-pc', hook_event_name: 'Stop', cwd }, {} as never, {} as never)
  assert.deepEqual(heard, ['PreCompact|sess-pc|null|Its context is about to be compacted (automatically).', 'PostToolUseFailure|sess-pc|Bash|Bash failed: npm test: exit 1', 'Stop|sess-pc|null|A turn ended.'])
  hooks.uninstallIntegration()
})

test('the hooks’ route takes its own secret only (compared in constant time)', () => {
  assert.equal(hooks.hookPathOk(`/hooks/${hookSecret}`), true)
  for (const p of ['/hooks/', `/hooks/${hookSecret.slice(0, -1)}`, `/hooks/${hookSecret}x`, `/hooks/${hookSecret.toUpperCase() === hookSecret ? hookSecret.toLowerCase() : hookSecret.toUpperCase()}`, '/hooks/x'])
    assert.equal(hooks.hookPathOk(p), false, p)
})
