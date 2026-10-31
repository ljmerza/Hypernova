// AskUserQuestion's form in a terminal when it asks several questions (an ask handed back after 10
// minutes): its tab row ("←  ☐ Versioning  ☐ First version  ✔ Submit  →") is the menu's `steps`,
// not its title, so the phone can move between the questions (owner, 2026-10-31). The screens
// are laid out from Claude Code 2.1.314's code (the tab row, "Type something.", "Chat about
// this", "Review your answers"), not captured from a real one.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const { menuOf } = await import('../src/term/shared.ts')
const screen = (name: string) => readFileSync(join(import.meta.dirname, 'fixtures', 'question-screens', name), 'utf8').split('\n')

test('a question of two: the tabs are its steps, the question its title, the options as they are', () => {
  const m = menuOf(screen('two-questions.txt'))
  assert.ok(m, 'a menu')
  assert.deepEqual(m.steps, { labels: ['Versioning', 'First version'], answered: [false, false] })
  assert.equal(m.title, 'Which versioning scheme should releases use?')
  assert.deepEqual(m.options.map((o) => o.label), ['Semver', 'CalVer', 'Build number', 'Type something.', 'Chat about this'])
  assert.equal(m.options[0]!.detail, 'MAJOR.MINOR.PATCH, bumped from the commit types')
  assert.equal(m.cursor, 0)
  assert.equal(m.cancel, true)
  assert.equal(m.permission, null)
})

test('an answered question is ticked; the next question is another menu', () => {
  const lines = screen('two-questions.txt')
  const next = lines.map((l) =>
    l
      .replace('☐ Versioning', '☒ Versioning')
      .replace('Which versioning scheme should releases use?', 'What should the first version be?')
      .replace('1. Semver', '1. 0.1.0')
      .replace('2. CalVer', '2. 1.0.0')
      .replace('3. Build number', '3. Keep the current one'),
  )
  const a = menuOf(lines)!
  const b = menuOf(next)!
  assert.deepEqual(b.steps, { labels: ['Versioning', 'First version'], answered: [true, false] })
  assert.notEqual(a.id, b.id)
})

test('the Submit tab: Review your answers, Submit answers and Cancel, with the steps still there', () => {
  const m = menuOf(screen('review.txt'))
  assert.ok(m, 'a menu')
  assert.equal(m.title, 'Review your answers')
  assert.deepEqual(m.options.map((o) => o.label), ['Submit answers', 'Cancel'])
  assert.deepEqual(m.steps, { labels: ['Versioning', 'First version'], answered: [true, false] })
  assert.ok(m.body.includes('Ready to submit your answers?'))
})

test('headers cut short on a narrow screen still read as steps', () => {
  const lines = screen('two-questions.txt').map((l) => l.replace('☐ Versioning  ☐ First version', '☐ Versioning  ☐ First v…'))
  assert.deepEqual(menuOf(lines)!.steps, { labels: ['Versioning', 'First v…'], answered: [false, false] })
})

test('a menu without the tab row has no steps; a line with arrows but no tabs is not one', () => {
  const lines = screen('two-questions.txt').filter((l) => !l.includes('✔ Submit'))
  const m = menuOf(lines)
  assert.ok(m)
  assert.equal(m.steps, null)
  const odd = screen('two-questions.txt').map((l) => (l.includes('✔ Submit') ? ' ←  something  ✔ Submit  →' : l))
  assert.equal(menuOf(odd)!.steps, null)
  assert.equal(menuOf(screen('../limit-screens/menu-arm.txt'))!.steps, null)
})
