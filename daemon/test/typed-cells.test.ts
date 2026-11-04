// What's typed in Claude Code's input box and what only looks it, in real terminal cells: the bytes are
// as Claude Code 2.1.317 draws them (measured with its screen cell by cell), through @xterm/headless.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import xterm from '@xterm/headless'
import { typedOf } from '../src/term/shared.ts'

const INV = (s: string) => `\x1b[7m${s}\x1b[27m`
const DIM = (s: string) => `\x1b[2m${s}\x1b[22m`
const row = async (bytes: string) => {
  const term = new xterm.Terminal({ cols: 60, rows: 3, allowProposedApi: true })
  await new Promise<void>((ok) => term.write(bytes, ok))
  return typedOf(term.buffer.active.getLine(0)!).trim()
}

test('a placeholder isn’t typed, its first letter under the cursor included', async () => {
  // A letter that looks just like a typed one under the cursor: inverse, not dim. Only what follows it tells.
  assert.equal(await row(`${INV('P')}${DIM('ress')} ${DIM('up')} ${DIM('to')} ${DIM('edit')} ${DIM('queued')} ${DIM('messages')}`), '')
  assert.equal(await row(`${INV('P')}${DIM('ress up to edit queued messages')}`), '')
  // Any placeholder: the words don't matter, nor a space after its first letter.
  assert.equal(await row(`${INV('A')} ${DIM('different hint')}`), '')
})

test('an empty box is empty', async () => {
  assert.equal(await row(INV(' ')), '')
})

test('typed text with a dim suggestion after it: the typed part, without the suggestion’s letters', async () => {
  assert.equal(await row(`x /he${INV('l')}${DIM('p')}`), 'x /he')
  assert.equal(await row(`h /co${INV('p')}${DIM('y +5')}`), 'h /co')
  assert.equal(await row(`h${INV('e')}${DIM('lp')}`), 'h')
})

test('typed text with the cursor anywhere on it is all typed: it’s someone’s unsent words', async () => {
  assert.equal(await row(`hello${INV(' ')}`), 'hello')
  assert.equal(await row(`hel${INV('l')}o`), 'hello')
  assert.equal(await row(`${INV('h')}ello`), 'hello')
  // One letter with the cursor on it, nothing after: it looks like a placeholder's first letter, and isn't one.
  assert.equal(await row(INV('h')), 'h')
  assert.equal(await row(`x /h${INV('e')}`), 'x /he')
  assert.equal(await row(`${INV('x')} /he`), 'x /he')
  // A word after a space, not dim.
  assert.equal(await row(`${INV('a')} b`), 'a b')
})
