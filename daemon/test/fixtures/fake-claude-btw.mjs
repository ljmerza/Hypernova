// A stand-in for `claude attach <job>` showing Claude Code's /btw panel, for the side-question tests
// (claude-agents.test.ts), so no test ever starts the real one. It titles its terminal with the
// session's name ("Mine"; "Some other session" for a job named btwdrft*, as a holder that drifted
// shows), draws a spinner over the input box (Claude at work), takes typed and pasted text into the
// box, and on Enter with "/btw <question>" draws the panel in place of the box: "Answering…", then
// after a moment a long answer the terminal has to wrap (btwfail*: an error instead). Space closes
// the panel. Every key it got lands in $FAKE_AGENTS_DIR/btw-keys-<job>.
import { appendFileSync } from 'node:fs'
import { join } from 'node:path'

const job = process.argv[2] ?? 'btw'
const keys = join(process.env.FAKE_AGENTS_DIR ?? '.', `btw-keys-${job}`)
const title = job.startsWith('btwdrft') ? 'Some other session' : 'Mine'
const rule = '─'.repeat(98)
const draw = (lines) => process.stdout.write(`\x1b[2J\x1b[H\x1b]0;${title}\x07` + lines.join('\r\n'))
const top = ['╭─── Claude Code ───╮', '╰───────────────────╯', '', '> Refactor the parser', '', '● Reading src/parser.ts…', '']
const box = (text) => draw([...top, '✻ Refactoring… (12s · esc to interrupt)', rule, `❯ ${text}`, rule, '  ⏵⏵ auto mode on (shift+tab to cycle)'])
// About 96 columns at most, as Claude Code wraps the answer in a 100-column holder.
const ANSWER = [
  'The parser keeps its tokens in a ring buffer of 64 entries, so a lookahead past that many',
  'tokens wraps around and reads stale ones. That only happens in the generated fixtures.',
  '',
  '- src/parser.ts: the buffer',
  '- src/lexer.ts: where tokens are made',
]

let mode = 'box'
let typed = ''
box('')
process.stdout.write('\x1b[?2004h') // bracketed paste, as Claude Code asks for
process.stdin.setRawMode?.(true)
process.stdin.on('data', (d) => {
  const s = d.toString('utf8')
  appendFileSync(keys, s)
  if (mode === 'panel') {
    if (s === ' ') {
      mode = 'box'
      box('')
    }
    return
  }
  if (s.includes('\r')) {
    const q = /^\/btw\s+(.*)$/.exec(typed.trim())
    typed = ''
    if (!q) return box('')
    mode = 'panel'
    const head = [...top, '', `  /btw ${q[1]}`, '']
    draw([...head, '    ⠋ Answering…', '', '  Esc to close'])
    setTimeout(() => {
      if (job.startsWith('btwfail')) draw([...head, '    Rate limited', '', '  Esc to close'])
      else draw([...head, ...ANSWER.map((l) => (l ? `    ${l}` : '')), '', '  ↑/↓ to scroll · c to copy · f to fork · Esc to close'])
    }, 400)
    return
  }
  typed += s.replace(/\x1b\[20[01]~/g, '')
  box(typed)
})
process.on('SIGHUP', () => process.exit(0))
process.on('SIGTERM', () => process.exit(0))
