// A small TOML reader, for the one thing the daemon wants from Codex's config.toml: the top-level
// keys and which tables exist (agent-settings.ts). It walks the whole file so that a line inside a
// multi-line string (`developer_instructions = """…model = "x"…"""`) or an array is never taken for
// a key. It decodes strings, and keeps nothing itself: each key is handed to [onKey] with its path.
// Numbers and dates are checked for shape and come back as OTHER, as do arrays and inline tables.
//
// Not a dependency: the daemon has no TOML package, Node has none built in, and this is all it needs.

/** A value the caller gets as what it is: a string, a boolean, or OTHER for anything else. */
export const OTHER = Symbol('other')
export type TomlValue = string | boolean | typeof OTHER

/**
 * One statement and its place in the text. A table's header, or a `key = value` directly in [table]:
 * [valueFrom] to [valueTo] is the value alone, [lineEnd] is past the line's comment and newline.
 */
export type TomlStatement =
  | { kind: 'table'; path: string[]; from: number }
  | { kind: 'key'; table: string[]; key: string[]; value: TomlValue; valueFrom: number; valueTo: number; lineEnd: number }

/** The file isn't TOML. Its message never quotes the file: callers may not show even that. */
export class TomlError extends Error {
  constructor() {
    super('not TOML')
  }
}

const BARE_KEY = /[A-Za-z0-9_-]/
const NUMBER = /^[+-]?(?:\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?|0x[\da-fA-F_]+|0o[0-7_]+|0b[01_]+|inf|nan)$/
const DATE = /^\d{4}-\d{2}-\d{2}(?:[Tt]\d{2}:\d{2}.*)?$|^\d{2}:\d{2}(?::\d{2}.*)?$/
const ESCAPES: Record<string, string> = { b: '\b', t: '\t', n: '\n', f: '\f', r: '\r', e: '\x1b', '"': '"', '\\': '\\' }
const MAX_DEPTH = 64

/**
 * Walks [text], calling [onKey] for each table header (without a value) and each `key = value`,
 * with the key's whole path (`[mcp_servers.a]` then `command = "x"` is `['mcp_servers', 'a',
 * 'command']`), also for a key inside an inline table. Throws [TomlError] on anything that isn't
 * TOML, and on a top-level key given twice, which Codex's own parser refuses too.
 *
 * [onStatement], for the one caller that edits a line (agent-settings-edit.ts): where each statement
 * of the file is, in the text's own positions.
 */
export function scanToml(text: string, onKey: (path: string[], value?: TomlValue) => void, onStatement?: (s: TomlStatement) => void): void {
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0
  const fail = (): never => {
    throw new TomlError()
  }
  const seen = new Set<string>()

  const skipSpaces = () => {
    while (text[i] === ' ' || text[i] === '\t') i++
  }
  const skipComment = () => {
    if (text[i] !== '#') return
    while (i < text.length && text[i] !== '\n') i++
  }
  /** Spaces, newlines and comments: between statements, and inside arrays and inline tables. */
  const skipBlank = () => {
    for (;;) {
      skipSpaces()
      skipComment()
      if (text[i] === '\n') i++
      else if (text[i] === '\r' && text[i + 1] === '\n') i += 2
      else return
    }
  }
  /** Nothing else on this line: a comment at most. */
  const endOfLine = () => {
    skipSpaces()
    skipComment()
    if (i >= text.length) return
    if (text[i] === '\n') i++
    else if (text[i] === '\r' && text[i + 1] === '\n') i += 2
    else fail()
  }

  const escape = (): string => {
    const c = text[i++]
    if (c === undefined) return fail()
    if (c in ESCAPES) return ESCAPES[c]!
    const size = c === 'u' ? 4 : c === 'U' ? 8 : c === 'x' ? 2 : 0
    if (!size) return fail()
    const hex = text.slice(i, i + size)
    if (hex.length !== size || !/^[\da-fA-F]+$/.test(hex)) return fail()
    i += size
    const code = Number.parseInt(hex, 16)
    if (code > 0x10ffff) return fail()
    return String.fromCodePoint(code)
  }
  const basic = (): string => {
    i++
    let out = ''
    for (;;) {
      const c = text[i]
      if (c === undefined || c === '\n') return fail()
      i++
      if (c === '"') return out
      out += c === '\\' ? escape() : c
    }
  }
  const literal = (): string => {
    i++
    const end = text.indexOf("'", i)
    if (end < 0) return fail()
    const out = text.slice(i, end)
    if (out.includes('\n')) return fail()
    i = end + 1
    return out
  }
  /** After the opening three quotes. A closing run may be up to five long: its first ones belong to the string. */
  const multiline = (q: '"' | "'"): string => {
    i += 3
    if (text[i] === '\n') i++
    else if (text[i] === '\r' && text[i + 1] === '\n') i += 2
    let out = ''
    for (;;) {
      const c = text[i]
      if (c === undefined) return fail()
      if (c === q) {
        let n = 0
        while (text[i + n] === q) n++
        if (n >= 3) {
          if (n > 5) return fail()
          i += n
          return out + q.repeat(n - 3)
        }
        out += q.repeat(n)
        i += n
        continue
      }
      i++
      if (q === '"' && c === '\\') {
        // A backslash at a line's end joins it with the next one's first text.
        let j = i
        while (text[j] === ' ' || text[j] === '\t') j++
        if (text[j] === '\n' || (text[j] === '\r' && text[j + 1] === '\n')) {
          i = j
          while (text[i] === ' ' || text[i] === '\t' || text[i] === '\n' || text[i] === '\r') i++
          continue
        }
        out += escape()
        continue
      }
      out += c
    }
  }

  const key = (): string[] => {
    const path: string[] = []
    for (;;) {
      skipSpaces()
      const c = text[i]
      if (c === '"') path.push(basic())
      else if (c === "'") path.push(literal())
      else {
        const from = i
        while (i < text.length && BARE_KEY.test(text[i]!)) i++
        if (i === from) return fail()
        path.push(text.slice(from, i))
      }
      skipSpaces()
      if (text[i] !== '.') return path
      i++
    }
  }


  const value = (path: string[], depth: number): TomlValue => {
    if (depth > MAX_DEPTH) return fail()
    skipSpaces()
    const c = text[i]
    if (c === '"') return text.startsWith('"""', i) ? multiline('"') : basic()
    if (c === "'") return text.startsWith("'''", i) ? multiline("'") : literal()
    if (c === '[') {
      i++
      for (;;) {
        skipBlank()
        if (text[i] === ']') {
          i++
          return OTHER
        }
        // An array's items aren't keys: what's inside its tables doesn't reach onKey.
        value([], depth + 1)
        skipBlank()
        if (text[i] === ',') i++
        else if (text[i] !== ']') return fail()
      }
    }
    if (c === '{') {
      i++
      for (;;) {
        skipBlank()
        if (text[i] === '}') {
          i++
          return OTHER
        }
        const inner = key()
        if (text[i] !== '=') return fail()
        i++
        if (path.length) {
          const inside = [...path, ...inner]
          onKey(inside, value(inside, depth + 1))
        } else value([], depth + 1)
        skipBlank()
        if (text[i] === ',') i++
        else if (text[i] !== '}') return fail()
      }
    }
    const from = i
    while (i < text.length && !/[\s,\]}#]/.test(text[i]!)) i++
    const word = text.slice(from, i)
    if (word === 'true') return true
    if (word === 'false') return false
    // A date and its time may be split by one space.
    if (/^\d{4}-\d{2}-\d{2}$/.test(word) && text[i] === ' ' && /^\d{2}:\d{2}/.test(text.slice(i + 1, i + 6))) {
      i++
      while (i < text.length && !/[\s,\]}#]/.test(text[i]!)) i++
      return OTHER
    }
    if (NUMBER.test(word) || DATE.test(word)) return OTHER
    return fail()
  }

  let table: string[] = []
  for (;;) {
    skipBlank()
    if (i >= text.length) return
    if (text[i] === '[') {
      const from = i
      const double = text[i + 1] === '['
      i += double ? 2 : 1
      table = key()
      if (text[i] !== ']' || (double && text[i + 1] !== ']')) fail()
      i += double ? 2 : 1
      onKey(table)
      endOfLine()
      onStatement?.({ kind: 'table', path: table, from })
    } else {
      const inner = key()
      if (text[i] !== '=') fail()
      i++
      const path = [...table, ...inner]
      if (path.length === 1) {
        if (seen.has(path[0]!)) fail()
        seen.add(path[0]!)
      }
      skipSpaces()
      const valueFrom = i
      const v = value(path, 0)
      const valueTo = i
      onKey(path, v)
      endOfLine()
      onStatement?.({ kind: 'key', table, key: inner, value: v, valueFrom, valueTo, lineEnd: i })
    }
  }
}
