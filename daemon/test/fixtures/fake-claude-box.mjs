// A stand-in for `claude attach <job>` with Claude Code's empty input box, for prompts longer than a
// line (contract.test.ts: Allow & retry's approval). It takes typed text into the box (shown cut to
// one line, as a box that grows keeps its rules), and on Enter appends what was typed to
// $FAKE_AGENTS_DIR/typed-<job> and empties the box. Nothing else.
import { appendFileSync } from 'node:fs'
import { join } from 'node:path'

const job = process.argv[2] ?? 'box'
const out = join(process.env.FAKE_AGENTS_DIR ?? '.', `typed-${job}`)
const rule = '─'.repeat(60)
let typed = ''
const box = () => process.stdout.write(`\x1b[2J\x1b[H\x1b]0;Mine\x07\r\n${rule}\r\n❯ ${typed.slice(0, 50)}\r\n${rule}\r\n`)
box()
process.stdin.setRawMode?.(true)
process.stdin.on('data', (d) => {
  for (const ch of d.toString('utf8').replace(/\x1b\[20[01]~/g, '')) {
    if (ch === '\r') {
      appendFileSync(out, typed + '\n')
      typed = ''
    } else if (ch >= ' ' || ch === '\n') typed += ch
  }
  box()
})
process.on('SIGHUP', () => process.exit(0))
process.on('SIGTERM', () => process.exit(0))
