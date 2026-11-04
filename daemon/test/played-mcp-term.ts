// A shared terminal played from real /mcp screens (fixtures/mcp-screens, Claude Code 2.1.317): what's on the screen follows the keys a person would press,
// so mcp-session.ts is checked for where it presses them. Not a test itself.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { TermOps } from '../src/mcp-session.ts'
import type { Typed } from '../src/terms.ts'

const DIR = join(fileURLToPath(new URL('.', import.meta.url)), 'fixtures', 'mcp-screens')
export const screenFile = (name: string) => readFileSync(join(DIR, `${name}.txt`), 'utf8')

export type Played = TermOps & {
  typed: string[]
  pressed: string[]
  /** What's up now. */
  shown: () => 'box' | 'list' | 'page' | 'tools'
  /** Screens to show, and what to do when a command's typed. */
  list: string
  page: string
  tools: string
  box: string
  /** A line /mcp's answer adds to the box's screen, by the command. Unset: nothing happens. */
  answers: Record<string, string | (() => string)>
  /** What the next `type` says, once. */
  refuse: Typed['reason'] | null
}

const cursorAt = (text: string, row: number) => {
  // Takes the cursor off where the capture had it, and puts it on the [row]th row of the list or the option.
  let at = -1
  return text
    .split('\n')
    .map((l) => {
      const plain = l.replace(/^(\s*)❯ /, '$1  ')
      if (!/^\s*(?:[✔✘⚠…◯]|\d+\.)\s+\S/u.test(plain) && !/^ {5}\S+( {2,}\S.*)?$/.test(plain)) return plain
      at++
      return at === row ? plain.replace(/^(\s*) {2}/, '$1❯ ') : plain
    })
    .join('\n')
}

export function playedTerm(o: Partial<Pick<Played, 'list' | 'page' | 'tools' | 'box' | 'answers'>> = {}): Played {
  let shown: 'box' | 'list' | 'page' | 'tools' = 'box'
  let row = 0
  let extra = ''
  const p: Played = {
    typed: [],
    pressed: [],
    list: o.list ?? 'list-mixed',
    page: o.page ?? 'page-connected',
    tools: o.tools ?? 'tools-paged',
    box: o.box ?? 'dismissed-idle',
    answers: o.answers ?? {},
    refuse: null,
    shown: () => shown,
    type: async (_tid, text) => {
      if (p.refuse) {
        const reason = p.refuse
        p.refuse = null
        return { ok: false, reason }
      }
      if (shown !== 'box') return { ok: false, reason: 'no_input' }
      p.typed.push(text)
      if (text === '/mcp') {
        shown = 'list'
        row = 0
      } else {
        const a = p.answers[text]
        if (a) extra += `\n${typeof a === 'function' ? a() : a}`
      }
      return { ok: true }
    },
    keys: (_tid, data) => {
      p.pressed.push(data)
      if (data === '\x1b') {
        if (shown === 'box') throw new Error('Esc with no dialog up: that would stop Claude’s turn')
        shown = shown === 'tools' ? 'page' : shown === 'page' ? 'list' : 'box'
        row = 0
      } else if (data === '\r') {
        if (shown === 'list') shown = 'page'
        else if (shown === 'page') shown = 'tools'
        row = 0
      } else if (data === '\x1b[B') row++
      return true
    },
    screen: async () => {
      if (shown === 'box') return screenFile(p.box) + extra
      return cursorAt(screenFile(shown === 'list' ? p.list : shown === 'page' ? p.page : p.tools), row)
    },
  }
  return p
}
