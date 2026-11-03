// Claude Code's interactive sign-in screens, as they really are (2.1.317, in a holder with a
// signed-out HOME): /login's in a session, and a first run's. The phone can't work them from cards.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const { boxLines, loginScreen, menuOf } = await import('../src/term/shared.ts')

const fixtures = join(import.meta.dirname, 'fixtures')
const screen = (dir: string, name: string) => readFileSync(join(fixtures, dir, `${name}.txt`), 'utf8').split('\n')

test('each of Claude Code 2.1.317 sign-in screens reads as one', () => {
  for (const name of ['login-method', 'console-method', 'code-prompt', 'first-run-method', 'first-run-code']) {
    assert.equal(loginScreen(screen('login-screens', name)), true, name)
  }
})

test("/login's menus: the dialog's own title and words, not the transcript above it", () => {
  const m = menuOf(screen('login-screens', 'login-method'))!
  assert.equal(m.title, 'Login')
  assert.deepEqual(m.body, ['Claude Code can be used with your Claude subscription or billed based on API usage through your Console account.', 'Select login method:'])
  assert.deepEqual(m.options.map((o) => o.label), ['Claude account with subscription · Individual, Team, or Enterprise', 'Anthropic Console account · API usage billing', '3rd-party platform · Amazon Bedrock, Microsoft Foundry, Google Vertex AI'])
  assert.equal(m.cancel, true)
  const c = menuOf(screen('login-screens', 'console-method'))!
  assert.equal(c.title, 'Login')
  assert.deepEqual(c.body, ['Anthropic Console account', 'How do you want to sign in?'])
})

test('the code prompt has no menu on it, which is why the phone had nothing to draw', () => {
  assert.equal(menuOf(screen('login-screens', 'code-prompt')), null)
  assert.equal(menuOf(screen('login-screens', 'first-run-code')), null)
})

test('anything else is not a sign-in screen', () => {
  for (const f of readdirSync(join(fixtures, 'permission-screens'))) assert.equal(loginScreen(screen('permission-screens', f.replace(/\.txt$/, ''))), false, f)
  for (const f of readdirSync(join(fixtures, 'question-screens'))) assert.equal(loginScreen(screen('question-screens', f.replace(/\.txt$/, ''))), false, f)
  // The words in the transcript over the input box.
  const rule = '─'.repeat(100)
  const box = ['  I would say: Select login method:', 'Select login method:', 'Paste code here if prompted >', rule, '❯ ', rule, '  ? for shortcuts']
  assert.ok(boxLines(box))
  assert.equal(loginScreen(box), false)
  // The same words in a transcript, under Claude Code's own permission dialog.
  const dialog = ['Select login method:', 'Paste code here if prompted >', rule, ' Bash command', '', '   ls', '', ' Do you want to proceed?', ' ❯ 1. Yes', '   2. No', '', ' Esc to cancel']
  assert.ok(menuOf(dialog)?.permission)
  assert.equal(loginScreen(dialog), false)
  assert.equal(loginScreen(['']), false)
})
