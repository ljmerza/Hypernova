// A permission rule's text: what Claude Code makes of it, what may leave the server, and how much it lets through
// (docs/PROTOCOL.md, "Permission rules"; docs/DECISIONS.md, "Permission rules, edited"). Pure: no file is opened here.
//
// judgeRule is Claude Code 2.1.317's own check (`Tke` in its binary), written out again because it has no command that
// answers for one rule. test/rules-e2e.test.ts holds the two together: what this calls a problem, `claude doctor` says it skips.

import { maskText, MASK } from './mcp-view.ts'
import { maskCommandLine } from './normalize.ts'

export type RuleList = 'allow' | 'ask' | 'deny'
export const RULE_LISTS: readonly RuleList[] = ['allow', 'ask', 'deny']
/** Weighed in this order, measured on 2.1.317: deny beats ask, ask beats allow, whichever is the wider. */
export const LIST_WORDS: Record<RuleList, string> = { allow: 'Allow', ask: 'Ask', deny: 'Deny' }

/** Old tool names Claude Code still reads (its table in 2.1.317). */
const OLD_NAMES: Record<string, string> = { Task: 'Agent', KillShell: 'TaskStop', KillBash: 'TaskStop', ListPeers: 'ListAgents', Brief: 'SendUserMessage', ListMcpResources: 'ListMcpResourcesTool', ReadMcpResource: 'ReadMcpResourceTool', ReadMcpResourceDir: 'ReadMcpResourceDirTool' }
const FILE_TOOLS = new Set(['Read', 'Write', 'Edit', 'Glob', 'NotebookRead', 'NotebookEdit', 'Cd'])
/** Tools that exist in 2.1.317, for a note only: a name off this list is still saved, since a newer Claude Code may have it. */
const KNOWN_TOOLS = new Set(['Agent', 'AskUserQuestion', 'Bash', 'Cd', 'Edit', 'EnterPlanMode', 'EnterWorktree', 'ExitPlanMode', 'ExitWorktree', 'Glob', 'Grep', 'KillShell', 'LSP', 'ListAgents', 'ListMcpResourcesTool', 'MultiEdit', 'NotebookEdit', 'NotebookRead', 'Read', 'ReadMcpResourceTool', 'SendUserMessage', 'Skill', 'SlashCommand', 'TaskCreate', 'TaskGet', 'TaskList', 'TaskOutput', 'TaskStop', 'TaskUpdate', 'TodoWrite', 'ToolSearch', 'WebFetch', 'WebSearch', 'Write'])

const escapedAt = (s: string, i: number) => {
  let n = 0
  for (let at = i - 1; at >= 0 && s[at] === '\\'; at--) n++
  return n % 2 !== 0
}
const unescapedCount = (s: string, c: string) => {
  let n = 0
  for (let i = 0; i < s.length; i++) if (s[i] === c && !escapedAt(s, i)) n++
  return n
}
const firstUnescaped = (s: string, c: string) => {
  for (let i = 0; i < s.length; i++) if (s[i] === c && !escapedAt(s, i)) return i
  return -1
}
const lastUnescaped = (s: string, c: string) => {
  for (let i = s.length - 1; i >= 0; i--) if (s[i] === c && !escapedAt(s, i)) return i
  return -1
}
const hasStar = (s: string) => {
  for (let i = 0; i < s.length; i++) if (s[i] === '*' && !escapedAt(s, i)) return true
  return false
}

type Shape = { kind: 'bare' } | { kind: 'malformed' } | { kind: 'call'; tool: string; raw: string }
function shapeOf(rule: string): Shape {
  if (!rule.includes('(') && !rule.includes(')')) return { kind: 'bare' }
  const open = firstUnescaped(rule, '(')
  const close = lastUnescaped(rule, ')')
  if (open === -1 && close === -1) return { kind: 'bare' }
  const tool = open === -1 ? rule : rule.slice(0, open)
  if (open === -1 || close <= open || close !== rule.length - 1 || /[()]/.test(tool)) return { kind: 'malformed' }
  return { kind: 'call', tool, raw: rule.slice(open + 1, close) }
}

/** A rule as Claude Code reads it: `Bash(*)` and `Bash` are the same rule. */
export function ruleParts(rule: string): { tool: string; content?: string } {
  const s = shapeOf(rule)
  const name = (t: string) => (Object.hasOwn(OLD_NAMES, t) ? OLD_NAMES[t]! : t)
  if (s.kind !== 'call' || !s.tool) return { tool: name(rule) }
  if (s.raw === '' || s.raw === '*') return { tool: name(s.tool) }
  return { tool: name(s.tool), content: s.raw.replaceAll('\\(', '(').replaceAll('\\)', ')').replaceAll('\\\\', '\\') }
}

const mcpParts = (tool: string) => {
  const [head, server, ...rest] = tool.split('__')
  return head === 'mcp' && server ? { server, tool: rest.length ? rest.join('__') : undefined } : null
}

/** A wildcard in a tool's name is refused in Allow unless it's after a literal `mcp__<server>__`. */
function allowWildcard(tool: string): string | null {
  if (!tool.includes('*')) return null
  const m = mcpParts(tool)
  if (m && !m.server.includes('*')) return null
  return `Wildcard tool name "${tool}" is not supported in allow rules. An allow rule has to name the tool: a wildcard only goes after mcp__<server>__`
}

/** A star before the rest of a command: it also matches any option put there. */
function starBeforeRest(content: string): boolean {
  if (content.endsWith(':*')) return false
  const words = content.trim().split(/\s+/).filter(Boolean)
  if (words.length < 3 || hasStar(words[0]!)) return false
  let star = false
  for (const w of words.slice(1)) {
    if (/^(?:[|&;<>]|\d+[<>])/.test(w)) return false
    if (hasStar(w)) {
      star = true
      continue
    }
    if (w.startsWith('-')) continue
    return star
  }
  return false
}

/**
 * What Claude Code makes of [rule] in [list]. [problem]: it would skip the rule, in its own words as far as they go.
 * [warning]: it reads the rule, though not as it looks. Neither ever quotes more of the rule than [shown] says.
 */
export function judgeRule(rule: string, list: RuleList, shown: string = maskedRule(rule).shown): { problem?: string; warning?: string } {
  if (!rule.trim()) return { problem: 'A rule can’t be empty.' }
  if (rule !== rule.trim()) return { problem: 'Claude Code doesn’t read a rule with spaces round it.' }
  const s = shapeOf(rule)
  if (s.kind === 'malformed') return { problem: 'Rules are written Tool or Tool(content), and have to end at the closing ")".' }
  if (s.kind === 'call' && s.raw === '') return { problem: s.tool ? `Empty parentheses: use just "${s.tool}", or put a pattern in them.` : 'Empty parentheses with no tool name.' }
  const { tool, content } = ruleParts(rule)
  if (mcpParts(tool)) {
    if (s.kind === 'call') return { problem: 'MCP rules don’t take a pattern in parentheses: use mcp__server__tool or mcp__server__*.' }
    if (list === 'allow') {
      const w = allowWildcard(tool)
      if (w) return { problem: w }
    }
    return {}
  }
  if (!tool) return { problem: 'A rule needs a tool’s name.' }
  if (list === 'allow') {
    const w = allowWildcard(tool)
    if (w) return { problem: w }
  }
  if (!tool.includes('_') && tool[0] !== tool[0]!.toUpperCase()) return { problem: `Tool names start with a capital: ${tool[0]!.toUpperCase()}${tool.slice(1)}.` }
  if (s.kind === 'call' && /(?:^|[^\\])\\[()]/.test(s.raw) && unescapedCount(s.raw, '(') !== unescapedCount(s.raw, ')')) return { problem: 'A "\\(" or "\\)" beside an unescaped parenthesis is ambiguous.' }
  if (tool === 'WebSearch' && content !== undefined && /[*?]/.test(content)) return { problem: 'WebSearch rules don’t take wildcards.' }
  if (tool === 'WebFetch' && content !== undefined) {
    if (content.includes('://') || content.startsWith('http')) return { problem: 'WebFetch rules name a site, not a URL: WebFetch(domain:example.com).' }
    if (!content.startsWith('domain:')) return { problem: 'WebFetch rules start with "domain:": WebFetch(domain:example.com).' }
  }
  if (tool === 'Bash' && content !== undefined) {
    if (content === ':*') return { problem: 'There’s nothing before the :*. Try Bash(npm *).' }
    if (list === 'allow' && starBeforeRest(content)) return { warning: `${shown} has a * before the rest of the command, so it also allows any option put there.` }
    const prefix = /^(.+):\*$/.exec(content)?.[1]?.trimEnd()
    if (prefix !== undefined) {
      if (hasStar(prefix)) return { warning: `${shown} mixes * with the :* at its end: Claude Code takes the * literally here.` }
    } else if (content.includes(':*')) return { warning: `${shown} has a :* that isn’t at the end, so Claude Code reads it as a * and the : as itself.` }
  }
  if (FILE_TOOLS.has(tool) && content?.includes(':*')) return { problem: 'The ":*" form is for Bash. Files use * and **, as in Read(src/**).' }
  if (content !== undefined) {
    const real = tool === 'Write' || tool === 'NotebookEdit' || tool === 'MultiEdit' ? 'Edit' : tool === 'Glob' ? 'Read' : null
    if (real) return { warning: `${tool}(path) never matches: use ${real}(path), which covers it.` }
  }
  if (!KNOWN_TOOLS.has(tool)) return { warning: `${tool} isn’t a tool this server knows of. Claude Code keeps the rule, and it does nothing unless a tool has that name.` }
  return {}
}

// ── What may leave ───────────────────────────────────────────────────────

/** A secret left where it is, by its number. The same words as raw JSON's (agent-raw.ts). */
const IN_RULE = /<hidden on the server(?: (\d+))?>/g
const hiddenWord = (n: number) => `<hidden on the server ${n}>`

/**
 * [rule] as a client may see it, each secret swapped for a numbered placeholder, and those secrets. Masked as a tool row's
 * command is (Secrets.maskCommand), plus what maskText adds: a URL's parameters and secret-named flags. If the pieces
 * don't line up, the whole rule stays here.
 */
export function maskedRule(rule: string): { shown: string; secrets: string[] } {
  const masked = maskCommandLine(maskText(rule))
  if (!masked.includes(MASK)) return { shown: rule, secrets: [] }
  const pieces = masked.split(MASK)
  const secrets: string[] = []
  let at = pieces[0]!.length
  if (!rule.startsWith(pieces[0]!)) return { shown: hiddenWord(1), secrets: [rule] }
  for (let i = 1; i < pieces.length; i++) {
    const piece = pieces[i]!
    const next = i === pieces.length - 1 ? (rule.endsWith(piece) ? rule.length - piece.length : -1) : piece ? rule.indexOf(piece, at) : at
    if (next < at) return { shown: hiddenWord(1), secrets: [rule] }
    secrets.push(rule.slice(at, next))
    at = next + piece.length
  }
  // A value taken up to the end takes the rule's closing parenthesis with it, which is no secret.
  const lastSecret = secrets.at(-1)!
  if (!pieces.at(-1) && lastSecret.length > 1 && lastSecret.endsWith(')') && rule.includes('(') && !lastSecret.includes('(')) {
    secrets[secrets.length - 1] = lastSecret.slice(0, -1)
    pieces[pieces.length - 1] = ')'
  }
  return { shown: pieces.map((p, i) => (i === 0 ? p : `${hiddenWord(i)}${p}`)).join(''), secrets }
}

/** [sent] with each placeholder back as the secret it stands for in [stored]. Null: one this rule never gave out. */
export function restoredRule(sent: string, stored: string | null): string | null {
  const { secrets } = stored === null ? { secrets: [] as string[] } : maskedRule(stored)
  let bad = false
  const out = sent.replace(IN_RULE, (_m, n: string | undefined) => {
    const secret = n === undefined ? undefined : secrets[Number(n) - 1]
    if (secret === undefined) bad = true
    return secret ?? ''
  })
  return bad ? null : out
}

// ── How much it lets through ─────────────────────────────────────────────

/** A command with one of these first can run anything at all. */
const RUNS_ANYTHING = new Set(['bash', 'sh', 'zsh', 'dash', 'fish', 'ksh', 'python', 'python2', 'python3', 'node', 'nodejs', 'deno', 'bun', 'perl', 'ruby', 'php', 'lua', 'eval', 'exec', 'sudo', 'su', 'doas', 'env', 'xargs', 'nohup', 'time', 'npx', 'bunx', 'pnpx', 'uvx', 'docker', 'podman', 'ssh'])

/** In Allow, what [rule] does if it's wide enough to say so; null for an ordinary one. */
export function broadWords(rule: string): string | null {
  const { tool, content } = ruleParts(rule)
  const all = content === undefined || /^[*\s:]*$/.test(content)
  if (tool === 'Bash') {
    if (all) return 'Claude Code can run any command without asking. That’s close to Bypass.'
    const first = /^(?:[A-Za-z0-9_./-]*\/)?([A-Za-z0-9_.+-]+)(?=$|[\s:])/.exec(content!.trim())?.[1]
    if (first && RUNS_ANYTHING.has(first)) return `${first} can run anything, so this lets Claude Code run any program that way without asking. That’s close to Bypass.`
    return null
  }
  const everywhere = all || (content !== undefined && /^(?:\/\/|~\/|\/)?\*\*(?:\/\*+)?$/.test(content))
  if (['Edit', 'Write', 'MultiEdit', 'NotebookEdit'].includes(tool) && everywhere) return 'Claude Code can change any file it can reach without asking, including its own settings and your shell’s startup files.'
  if (['Read', 'Glob', 'Grep'].includes(tool) && everywhere) return 'Claude Code can read any file it can reach without asking, keys and tokens included.'
  if (tool === 'WebFetch' && all) return 'Claude Code can fetch from any site without asking, which is also a way to send data out.'
  if (tool === 'Agent' && all) return 'Claude Code can start any agent without asking.'
  const m = mcpParts(tool)
  if (m && (m.tool === undefined || m.tool === '*')) return `Claude Code can use every tool of the ${m.server} server without asking.`
  if (m) return null
  if (all) return `Claude Code can use ${tool} for anything without asking.`
  return null
}

/** Whether a rule in a higher list wins over [lower] whatever the call is. Only what's sure: the same rule, a whole tool or a whole MCP server. */
export function covers(higher: string, lower: string): boolean {
  const h = ruleParts(higher)
  const l = ruleParts(lower)
  if (h.tool === l.tool) return h.content === undefined || h.content === l.content
  const m = mcpParts(h.tool)
  return !!m && (m.tool === undefined || m.tool === '*') && l.tool.startsWith(`mcp__${m.server}__`)
}
