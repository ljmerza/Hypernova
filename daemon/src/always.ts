// What "Always" covers (docs/PROTOCOL.md, "Approvals"; IDEAS PA2, PA3): the owner picks where the
// rule goes (this session, this project, everywhere) and may edit it first. Claude Code writes the
// rule itself, as its own "Yes, and don't ask again" does: the hook's allow (and the SDK's
// canUseTool answer) carries it in `updatedPermissions` with a destination, and Claude Code adds it
// to the session and, for a settings file, saves it there. The daemon never edits a settings file.
//
// Checked against Claude Code 2.1.314 (`grep -a` on the binary): a PermissionRequest hook's allow
// may hold `updatedPermissions`, each `{type: "addRules", rules: [{toolName, ruleContent?}],
// behavior: "allow" | "deny" | "ask", destination}` with a destination of `userSettings`,
// `projectSettings`, `localSettings`, `session` or `cliArg`; it applies them to the session and
// saves those for the three settings files (`localSettings` is the project's
// `.claude/settings.local.json`, `projectSettings` its `.claude/settings.json`, `userSettings`
// `~/.claude/settings.json`). Its own suggestions for a command go to `localSettings`.
//
// A rule is written as Claude Code writes one: `Tool` or `Tool(content)`, the content's `\`, `(`
// and `)` escaped with a backslash; `Tool()` and `Tool(*)` are the whole tool. A command's prefix
// is `Bash(npm test *)` (a space, then `*`), as 2.1.314's own suggestions write it; the older
// `Bash(npm test:*)` that files still hold is a prefix too, and passes through as it is.

/** Where an Always rule goes, as the phone names it. */
export type AlwaysScope = 'session' | 'project' | 'everywhere'
export const ALWAYS_SCOPES: readonly AlwaysScope[] = ['session', 'project', 'everywhere']

/**
 * Claude Code's destination for each scope. "This project" is the project's local file
 * (`.claude/settings.local.json`): Claude Code keeps it out of git, so the rule stays the owner's
 * and never lands in a commit for others, as its own dialog's "don't ask again" does.
 */
export const SCOPE_DESTINATION = { session: 'session', project: 'localSettings', everywhere: 'userSettings' } as const

/** A rule as Claude Code holds it. */
export type RuleValue = { toolName: string; ruleContent?: string }

/** What the owner chose on the Always sheet: where, and the rules (already checked). */
export type AlwaysChoice = { scope: AlwaysScope; rules: RuleValue[] | null }

/** Rules one Always may add at most, and a rule's length at most. */
export const ALWAYS_RULES_MAX = 20
export const ALWAYS_RULE_MAX = 2000

const TOOL_NAME = /^[A-Za-z][\w-]*$/
const FILE_DESTINATIONS = new Set(['localSettings', 'projectSettings', 'userSettings'])

/** Index of the first `ch` in [s] not escaped by a backslash (an odd run of them), or -1. */
function firstUnescaped(s: string, ch: string): number {
  for (let i = 0; i < s.length; i++) if (s[i] === ch && !escaped(s, i)) return i
  return -1
}
function lastUnescaped(s: string, ch: string): number {
  for (let i = s.length - 1; i >= 0; i--) if (s[i] === ch && !escaped(s, i)) return i
  return -1
}
function escaped(s: string, i: number): boolean {
  let n = 0
  for (let j = i - 1; j >= 0 && s[j] === '\\'; j--) n++
  return n % 2 === 1
}

/** [v] as Claude Code writes it in a settings file (`Bash(npm test *)`). */
export function ruleString(v: RuleValue): string {
  if (!v.ruleContent) return v.toolName
  return `${v.toolName}(${v.ruleContent.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)')})`
}

/**
 * A rule the owner typed, read as Claude Code reads one, or null when it isn't one: `Tool` or
 * `Tool(content)`, the tool a name (`Bash`, `mcp__github__create_issue`), the content ending the
 * rule, no control characters, at most [ALWAYS_RULE_MAX] long. `Tool()` and `Tool(*)` are `Tool`.
 */
export function parseRule(raw: unknown): RuleValue | null {
  if (typeof raw !== 'string') return null
  const s = raw.trim()
  if (!s || s.length > ALWAYS_RULE_MAX || /[\u0000-\u001f\u007f]/.test(s)) return null
  const open = firstUnescaped(s, '(')
  const close = lastUnescaped(s, ')')
  if (open === -1 && close === -1) return TOOL_NAME.test(s) ? { toolName: s } : null
  if (open === -1 || close !== s.length - 1 || close <= open) return null
  const toolName = s.slice(0, open)
  if (!TOOL_NAME.test(toolName)) return null
  const inner = s.slice(open + 1, close)
  if (inner.trim() === '' || inner === '*') return { toolName }
  // As Claude Code unescapes it: \( and \) first, then \\.
  return { toolName, ruleContent: inner.replaceAll('\\(', '(').replaceAll('\\)', ')').replaceAll('\\\\', '\\') }
}

const isAllowRules = (u: any): boolean => !!u && typeof u === 'object' && u.type === 'addRules' && u.behavior === 'allow' && Array.isArray(u.rules)
const ruleValue = (r: any): RuleValue | null =>
  r && typeof r === 'object' && typeof r.toolName === 'string' && r.toolName ? { toolName: r.toolName, ...(typeof r.ruleContent === 'string' && r.ruleContent ? { ruleContent: r.ruleContent } : {}) } : null

/** The allow rules in Claude Code's suggestion, as written in a settings file, in its order, once each. */
export function suggestedRules(suggestions: unknown): string[] {
  const out: string[] = []
  for (const u of Array.isArray(suggestions) ? suggestions : []) {
    if (!isAllowRules(u)) continue
    for (const r of u.rules) {
      const v = ruleValue(r)
      if (v && !out.includes(ruleString(v))) out.push(ruleString(v))
    }
  }
  return out
}

/** Where Claude Code's suggestion puts its allow rules, as a scope (null when it has none). */
export function suggestedScope(suggestions: unknown): AlwaysScope | null {
  const u = (Array.isArray(suggestions) ? suggestions : []).find(isAllowRules)
  if (!u) return null
  if (u.destination === 'userSettings') return 'everywhere'
  if (u.destination === 'localSettings' || u.destination === 'projectSettings') return 'project'
  return 'session'
}

/** Why an Always answer's `scope` and `rules` were refused: a code and the words the phone shows. */
export class AlwaysError extends Error {
  readonly code: 'bad_scope' | 'bad_rule'
  constructor(code: 'bad_scope' | 'bad_rule', message: string) {
    super(message)
    this.code = code
  }
}

/**
 * The owner's choice from an answer's body (`POST /v1/approvals/{id}`): none when it names neither
 * `scope` nor `rules` (Claude Code's suggestion as it is). `rules` without a scope, a scope that
 * isn't one of [ALWAYS_SCOPES], or a rule that isn't one throws.
 */
export function readAlwaysChoice(b: any): AlwaysChoice | undefined {
  if (b?.scope === undefined && b?.rules === undefined) return undefined
  if (!ALWAYS_SCOPES.includes(b?.scope)) throw new AlwaysError('bad_scope', 'Choose this session, this project or everywhere.')
  if (b.rules === undefined || b.rules === null) return { scope: b.scope, rules: null }
  if (!Array.isArray(b.rules) || b.rules.length === 0) throw new AlwaysError('bad_rule', 'Give at least one rule.')
  if (b.rules.length > ALWAYS_RULES_MAX) throw new AlwaysError('bad_rule', `At most ${ALWAYS_RULES_MAX} rules at once.`)
  const rules: RuleValue[] = []
  for (const r of b.rules) {
    const v = parseRule(r)
    if (!v) throw new AlwaysError('bad_rule', `“${String(r).slice(0, 80)}” isn’t a rule. Write Tool or Tool(pattern), such as Bash(npm test *).`)
    if (!rules.some((x) => ruleString(x) === ruleString(v))) rules.push(v)
  }
  return { scope: b.scope, rules }
}

/**
 * What Claude Code gets with an Always: its own suggestion unchanged without a [choice]; else one
 * allow rule set (the owner's rules, or the suggested ones) to the chosen scope's destination, then
 * the rest of the suggestion (a folder to add, a mode) as it was, except that "this session" keeps
 * all of it to the session: nothing is saved to a file then.
 */
export function alwaysUpdates(suggestions: unknown, choice?: AlwaysChoice): unknown[] {
  const list = Array.isArray(suggestions) ? suggestions : []
  if (!choice) return list
  const rules = choice.rules ?? list.filter(isAllowRules).flatMap((u) => u.rules.map(ruleValue).filter((v: RuleValue | null): v is RuleValue => !!v))
  const others = list
    .filter((u) => !isAllowRules(u))
    .map((u: any) => (choice.scope === 'session' && u && typeof u === 'object' && FILE_DESTINATIONS.has(u.destination) ? { ...u, destination: 'session' } : u))
  const add = rules.length ? [{ type: 'addRules', rules, behavior: 'allow', destination: SCOPE_DESTINATION[choice.scope] }] : []
  return [...add, ...others]
}
