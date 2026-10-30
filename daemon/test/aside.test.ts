// Reading Claude Code's /btw panel off a terminal's screen (aside.ts, readAside) and putting its
// answer back into lines as written (reflow). The screens are drawn the way Claude Code 2.1.314's
// panel lays itself out, read from its code (the question two columns in, the answer four, the
// footer's hints), not photographed from a running Claude Code: replace them with real captures
// when there are some (docs/PROTOCOL.md, "Side questions", says what a live look must confirm).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-aside-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
const { readAside, reflow } = await import('../src/aside.ts')
after(() => rmSync(scratch, { recursive: true, force: true }))

/** A 34-row screen: the conversation above, then [panel] at the bottom. */
const screen = (panel: string[]) => {
  const top = ['╭─── Claude Code ───╮', '╰───────────────────╯', '', '> Refactor the parser', '', '● Reading src/parser.ts…', '']
  const lines = [...top, ...panel]
  while (lines.length < 34) lines.push('')
  return lines.join('\n')
}

test('readAside: "Answering…" is asking; the answer once the footer offers to copy it', () => {
  const asking = screen(['', '  /btw why is it slow?', '', '    ⠙ Answering…', '', '  Esc to close'])
  assert.deepEqual(readAside(asking, 'why is it slow?'), { state: 'asking', text: '', clipped: false })
  const retry = screen(['', '  /btw why is it slow?', '', '    ⠙ API overloaded · retrying in 4s · attempt 2/10', '', '  Esc to close'])
  assert.equal(readAside(retry, 'why is it slow?')?.state, 'asking')
  const done = screen(['', '  /btw why is it slow?', '', '    It re-reads the file on each call.', '', '    - cache it', '', '  ↑/↓ to scroll · c to copy · f to fork · Esc to close'])
  assert.deepEqual(readAside(done, 'why is it slow?'), { state: 'answered', text: 'It re-reads the file on each call.\n\n- cache it', clipped: false })
})

test('readAside: earlier questions above it, a question cut short with "…", and another question’s panel', () => {
  const panel = ['', '  (+2 earlier /btw)', '  /btw what was the first thing', '  /btw and the second', '  /btw how many tokens does the ring buffer in the parser hol…', '', '    64.', '', '  shift+←/→ to browse · c to copy · x to clear history · Esc to close']
  const q = 'how many tokens does the ring buffer in the parser hold at most?'
  assert.deepEqual(readAside(screen(panel), q), { state: 'answered', text: '64.', clipped: false })
  // The lowest question line is someone else's: not this question's answer, though an earlier one matches.
  assert.equal(readAside(screen(panel), 'and the second'), null)
  // No panel at all: the input box is back.
  assert.equal(readAside(screen(['────────', '❯ ', '────────']), q), null)
})

test('readAside: an error in words is failed; an answer that fills the panel is clipped', () => {
  const failed = screen(['', '  /btw is it green?', '', '    Side questions aren’t available when viewing a session read-only', '', '  Esc to close'])
  assert.deepEqual(readAside(failed, 'is it green?'), { state: 'failed', text: 'Side questions aren’t available when viewing a session read-only', clipped: false })
  // 34 rows: max(5, 34 − 11) = 23 lines of answer at most before it scrolls.
  const long = Array.from({ length: 23 }, (_, i) => `    line ${i + 1}`)
  const clipped = readAside(['', '  /btw list them', '', ...long, '', '  ↑/↓ to scroll · c to copy · Esc to close', '', '', '', '', '', ''].join('\n'), 'list them')
  assert.equal(clipped?.clipped, true)
  assert.equal(clipped?.text.split('\n').length, 23)
})

test('reflow: lines the terminal broke are joined; lists, blank lines and short lines stay', () => {
  const w = 40
  const text = [
    'A sentence long enough that the terminal', // 40 columns: "had" didn't fit after it
    'had to break it here.',
    'Short line.',
    'Another short one.',
    '',
    '- a list item that runs on far enough to',
    '  wrap onto a second line',
    '- the next item',
    '1. numbered',
  ].join('\n')
  assert.equal(
    reflow(text, w),
    ['A sentence long enough that the terminal had to break it here.', 'Short line.', 'Another short one.', '', '- a list item that runs on far enough to wrap onto a second line', '- the next item', '1. numbered'].join('\n'),
  )
})
