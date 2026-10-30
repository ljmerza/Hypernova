// Claude Code's own permission dialog read off a terminal's screen (term/shared.ts, menuOf): the
// screen the owner's phone couldn't answer (2026-10-30, a `claude agents` job seen through
// `claude attach`, an ask rule over auto mode) is a menu with `permission`, its command whole;
// another command makes another menu; menus that aren't one keep `permission: null`.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const { menuOf } = await import('../src/term/shared.ts')
const fixtures = join(import.meta.dirname, 'fixtures')
const screen = (path: string) => readFileSync(join(fixtures, path), 'utf8').split('\n')

test('the Arc screen: Yes and No, the tool, the whole command, the ask rule in the body', () => {
  const m = menuOf(screen('permission-screens/attach-ask-rule.txt'))
  assert.ok(m, 'a menu')
  assert.equal(m.title, 'Bash command')
  assert.deepEqual(m.options.map((o) => o.label), ['Yes', 'No'])
  assert.equal(m.cursor, 0)
  assert.equal(m.cancel, true)
  assert.deepEqual(m.permission, {
    tool: 'Bash command',
    command:
      '/media/cubxi/docker/projects/arc/.venv/bin/python /media/cubxi/docker/projects/arc/scratch/deploy/check.py http://192.168.1.76:8108 /healthz / /variants/1/ /variants/1/blend/ /compare/?v=1 /accuracy/ /devices/',
    question: 'Do you want to proceed?',
  })
  assert.deepEqual(m.body, [
    'Smoke-check all live pages',
    'Ask rule Bash(python:*) overrides auto mode for this command.',
    '/permissions to let auto mode decide',
    'Do you want to proceed?',
  ])
})

test('the same dialog for another command is another menu (an answer never lands on the next one)', () => {
  const lines = screen('permission-screens/attach-ask-rule.txt')
  const other = lines.map((l) => l.replace('/healthz', '/readyz'))
  assert.notEqual(menuOf(lines)!.id, menuOf(other)!.id)
})

test('a command without the │ margin, lines kept as lines; more options than Yes and No', () => {
  // Rebuilt from a raw capture of a plain terminal's dialog (2026-10-29): its spacing is assumed.
  const lines = [
    '────────────────────────────────────────────────────────────────────────────────',
    ' Bash command',
    '',
    ' Create the perm-ok file in /tmp/hn-tests',
    '╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌',
    '   mkdir -p /tmp/hn-tests',
    '   touch /tmp/hn-tests/perm-ok',
    '╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌',
    '',
    ' Do you want to proceed?',
    ' ❯ 1. Yes',
    '   2. Yes, and always allow access to /tmp/hn-tests from this project',
    '   3. Yes, and switch to auto mode · auto mode handles these prompts for you',
    '   4. No',
    '',
    ' Esc to cancel · Tab to amend',
  ]
  const m = menuOf(lines)
  assert.ok(m?.permission)
  assert.equal(m.permission.command, 'mkdir -p /tmp/hn-tests\ntouch /tmp/hn-tests/perm-ok')
  assert.equal(m.options.length, 4)
  assert.equal(m.options[3]!.label, 'No')
  // The command isn't the body as well.
  assert.deepEqual(m.body, ['Create the perm-ok file in /tmp/hn-tests', 'Do you want to proceed?'])
})

test('a subagent’s dialog in its session’s terminal: whose it is stays in the tool, the count of open asks doesn’t', () => {
  // Laid out from Claude Code 2.1.314's code (the title row: title, "· from the <name> agent", and
  // "1 of 3" at its right while several asks are open), not captured from a real screen.
  const lines = screen('permission-screens/subagent-ask.txt')
  const m = menuOf(lines)
  assert.ok(m?.permission, 'a permission dialog')
  assert.equal(m.title, 'Bash command · from the general-purpose agent')
  assert.deepEqual(m.permission, {
    tool: 'Bash command · from the general-purpose agent',
    command: 'ls /media/cubxi/docker/projects/hypernova/daemon/test',
    question: 'Do you want to proceed?',
  })
  assert.deepEqual(m.body, ["List the daemon's tests", 'Do you want to proceed?'])
  assert.deepEqual(m.options.map((o) => o.label), ['Yes', 'Yes, and don’t ask again for ls commands in /media/cubxi/docker/projects/hypernova', 'No'])
  // The next ask's turn ("2 of 3") is the same dialog for the same command.
  assert.equal(menuOf(lines.map((l) => l.replace('1 of 3', '2 of 3')))!.id, m.id)
})

test('a menu that isn’t a permission dialog keeps permission null', () => {
  const m = menuOf(screen('limit-screens/menu-arm.txt'))
  assert.ok(m)
  assert.equal(m.permission, null)
})
