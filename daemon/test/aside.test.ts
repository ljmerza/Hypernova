// Reading Claude Code's /btw panel off a terminal's screen (aside.ts, readAside) and putting its
// answer back into lines as written (reflow). The screens are drawn the way Claude Code 2.1.314's
// panel lays itself out, read from its code (the question two columns in, the answer four, the
// footer's hints), not photographed from a running Claude Code: replace them with real captures
// when there are some (docs/PROTOCOL.md, "Side questions", says what a live look must confirm).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
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

// A real panel, captured on the owner's phone (2026-10-30): a `claude attach` holder 84 columns wide,
// Claude Code 2.1.314. An earlier question shows without its answer and with no "(+N earlier /btw)"
// line; the footer starts "↑/↓ to browse" and wraps ("Esc to" / "close ·"), with the status line's
// "← 4 agents" at the right of the same rows. Indents as read off the screenshot.
test('readAside: the real panel, its footer wrapped beside the status line', () => {
  const real = readFileSync(join(import.meta.dirname, 'fixtures', 'btw-screens', 'answered-84.txt'), 'utf8')
  const p = readAside(real, "I'm testing the btw")
  assert.equal(p?.state, 'answered')
  assert.equal(p?.clipped, false)
  assert.ok(p?.text.startsWith('Your /btw question reached me'))
  assert.ok(p?.text.endsWith('need adjusting.'))
  assert.equal(p?.text.split('\n\n').length, 2)
  assert.ok(!/to browse|close ·|← 4/.test(p!.text))
  assert.equal(
    // As watchAside reflows it: the screen's columns less 7.
    reflow(p!.text, 84 - 7).split('\n\n')[0],
    'Your /btw question reached me and I\'m answering it, so sending works. The main session keeps working in the background; it\'s starting the bug-fix, tidy-up and permission-rules agents.',
  )
  assert.equal(reflow(p!.text, 84 - 7).split('\n').length, 3)
  // The earlier question isn't this one's panel.
  assert.equal(readAside(real, 'is it possible to run the ui in a desktop?'), null)
  // Still answering, the same wrapped footer without "c to copy".
  const asking = [
    '  /btw is it possible to run the ui in a desktop?',
    "  /btw I'm testing the btw",
    '',
    '    ⠙ Answering…',
    '',
    '  ↑/↓ to browse · x to clear history · f to fork · Esc to close ·              ← 4',
    '                                                                            agents',
  ].join('\n')
  assert.equal(readAside(asking, "I'm testing the btw")?.state, 'asking')
  // Wrapped between "Esc" and "to close": the rows are read as one footer.
  const split = ['', '  /btw why?', '', '    Because.', '', '  ↑/↓ to browse · c to copy · f to fork · x to clear history · Esc     ← 4', '  to close ·                                                     agents'].join('\n')
  assert.deepEqual(readAside(split, 'why?'), { state: 'answered', text: 'Because.', clipped: false })
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
