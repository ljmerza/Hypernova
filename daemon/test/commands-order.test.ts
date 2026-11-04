// Which commands wait in Claude Code's queue while Claude works, and which take no words after them
// (commands.ts; measured on 2.1.318, with the real binary in prompt-order-e2e.test.ts).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-cmd-order-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = scratch
const { splitCommand, waitsInQueue } = await import('../src/commands.ts')

test('what Claude Code queues while Claude works: commands and skills, but not the ones it runs at once, nor words', () => {
  for (const t of ['/clear', '/compact', '/compact keep it short', '/context', '/init', '/rewind', '/hn-plain do it', '/plugin:skill words', '  /clear']) assert.equal(waitsInQueue(t), true, t)
  for (const t of ['/model', '/effort high', '/cost', '/usage', '/config', '/help', '/fast', '/status', '/btw why?', '/rename x']) assert.equal(waitsInQueue(t), false, t)
  for (const t of ['read this', '/Users/me/shot.png what is this', '!ls', '@"/tmp/a.png" /clear', '']) assert.equal(waitsInQueue(t), false, t)
})

test('words after a command with no use for them are a message; a command’s own words stay its own', () => {
  assert.deepEqual(splitCommand('/clear read and stop /tmp/a.md'), { command: '/clear', words: 'read and stop /tmp/a.md' })
  assert.deepEqual(splitCommand('/clear\nread and stop /tmp/a.md'), { command: '/clear', words: 'read and stop /tmp/a.md' })
  assert.deepEqual(splitCommand('/new  two\nlines'), { command: '/new', words: 'two\nlines' })
  assert.deepEqual(splitCommand('/context what now?'), { command: '/context', words: 'what now?' })
  for (const t of ['/clear', '/clear ', '/compact keep the names', '/compact\nkeep the names', '/effort high', '/hn-plain do it', '/clearer words', 'read /clear words', '/Users/me x']) assert.equal(splitCommand(t), null, t)
})
