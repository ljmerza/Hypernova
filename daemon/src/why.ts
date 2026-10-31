// Why Claude Code asked (IDEAS PA7; docs/PROTOCOL.md, "Approvals", "Why it asked"): one line for an
// approval and for Claude Code's own permission dialog, from what Claude Code itself says first,
// then from Hypernova's own reading of the rules in effect (permission-rules.ts), labelled as such.
//
// What Claude Code 2.1.316 says about the reason (`grep -a` on the binary):
// - The PermissionRequest hook's input carries none: the common fields, `tool_name`, `tool_input`,
//   `permission_suggestions` and, for an MCP tool, `mcp_server`. No decision reason, no blocked path.
//   Its suggestions still tell one thing: an `addDirectories` suggestion is offered when the call
//   reaches a folder outside the project's.
// - The SDK's canUseTool gets `decisionReason` (words), `decisionReasonType` (`rule`, `mode`,
//   `safetyCheck`, `workingDir`, `hook`, `classifier`, `other`, `subcommandResults`,
//   `sandboxOverride`, `asyncAgent`), `blockedPath` and `matchedAskRule` ({source, toolName,
//   ruleContent}).
// - The terminal dialog prints it over the question: "Ask rule X overrides auto mode for this
//   command." (an ask rule in auto mode, not the managed policy's), "Permission rule X requires
//   confirmation for this command.", "Hook X requires confirmation for this command…", a working
//   folder's reason ("Path is outside allowed working directories", "… which is outside the allowed
//   working directories"), or a safety check's own words; then "/permissions to …" under some.
//
// Hypernova's reading is only that: rules matched the way Claude Code is documented to match them,
// simplified (a command's parts split on && || ; |, a path pattern as a glob). It never sends a
// settings file's other keys: only a rule string, as the rules route already does.
import { homedir } from 'node:os'
import { isAbsolute, join, resolve, sep } from 'node:path'
import { parseRule, ruleString, type RuleValue } from './always.ts'
import type { RuleFile, RuleSource, RulesInEffect } from './permission-rules.ts'

/**
 * What made it ask. `ask_rule`: an ask rule matches; `hook`: a hook asked for confirmation;
 * `outside_folder`: it reaches a path outside the project's folders; `safety`: one of Claude Code's
 * own checks (a protected file, a risky command); `classifier`: auto mode's classifier;
 * `mode`: the session's mode (plan); `no_rule`: no allow rule matches; `other`: a reason that is
 * none of these.
 */
export type WhyKind = 'ask_rule' | 'hook' | 'outside_folder' | 'safety' | 'classifier' | 'mode' | 'no_rule' | 'other'

/** `claude`: Claude Code said so (its dialog, the SDK, its suggestion); `hypernova`: Hypernova's reading of the rules. */
export type WhySource = 'claude' | 'hypernova'

/** One line on why it asked; [rule] the rule as a settings file writes it, when one is the reason. */
export type Why = { kind: WhyKind; text: string; rule?: string; source: WhySource }

/** A line or a path in a reason is cut to this. */
const TEXT_MAX = 300
const cut = (s: string) => (s.length > TEXT_MAX ? s.slice(0, TEXT_MAX - 1) + '…' : s)
const why = (kind: WhyKind, text: string, source: WhySource, rule?: string | null): Why => ({ kind, text: cut(text), ...(rule ? { rule: cut(rule) } : {}), source })

// ── What Claude Code says ────────────────────────────────────────────────

const ASK_RULE = /^Ask rule (.+) overrides auto mode for this \S+?\.?$/
const PERMISSION_RULE = /^Permission rule (.+) requires confirmation for this \S+?\.?$/
const HOOK = /^Hook \S.* requires confirmation for this \S+/
const OUTSIDE = /outside (the )?allowed working director/i
const SAFETY = /\b(requires? (manual |explicit )?(approval|confirmation)|cannot be (statically )?(validated|checked)|Dangerous \S+ operation)\b/i
const CONFIG = /^\/permissions to /

/**
 * Why, from the lines of Claude Code's own permission dialog (its body, the question last): the
 * first line that reads as one of its reasons. Null when it prints none (a plain ask: no allow rule).
 */
export function whyFromDialog(lines: string[]): Why | null {
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i]!.trim()
    const m = ASK_RULE.exec(l) ?? PERMISSION_RULE.exec(l)
    if (m) return why('ask_rule', l, 'claude', m[1])
    if (HOOK.test(l)) return why('hook', l, 'claude')
    if (OUTSIDE.test(l)) return why('outside_folder', l, 'claude')
    if (SAFETY.test(l)) return why('safety', l, 'claude')
    // A reason Claude Code prints with "/permissions to …" under it, of a kind not known here.
    if (!CONFIG.test(l) && l && CONFIG.test(lines[i + 1]?.trim() ?? '')) return why('other', l, 'claude')
  }
  return null
}

/** The canUseTool options the SDK passes that say why (sdk.d.ts; `decisionReasonType` is untyped there). */
export type SdkAsk = {
  decisionReason?: unknown
  decisionReasonType?: unknown
  blockedPath?: unknown
  matchedAskRule?: unknown
}

const SDK_KINDS: Record<string, WhyKind> = { rule: 'ask_rule', hook: 'hook', workingDir: 'outside_folder', safetyCheck: 'safety', classifier: 'classifier', mode: 'mode' }
const str = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null)

/** Why, from what the SDK hands canUseTool. Null when it says nothing. */
export function whyFromSdk(o: SdkAsk | undefined): Why | null {
  if (!o) return null
  const reason = str(o.decisionReason)
  const type = str(o.decisionReasonType)
  const m = o.matchedAskRule as { toolName?: unknown; ruleContent?: unknown; source?: unknown } | undefined
  if (m && typeof m === 'object' && str(m.toolName)) {
    const rule = ruleString({ toolName: str(m.toolName)!, ...(str(m.ruleContent) ? { ruleContent: str(m.ruleContent)! } : {}) })
    const where = SDK_SOURCE[String(m.source)]
    return why('ask_rule', `Ask rule ${rule}${where ? ` in ${where}` : ''} asks for this call.`, 'claude', rule)
  }
  const path = str(o.blockedPath)
  // A write outside the folders comes as a safety check, its words saying so.
  if (type === 'workingDir' || (path && !type) || (reason && OUTSIDE.test(reason))) return why('outside_folder', reason ?? `${path} is outside the project’s folders.`, 'claude')
  if (type && SDK_KINDS[type]) {
    const kind = SDK_KINDS[type]!
    return why(kind, reason ?? DEFAULT_TEXT[kind], 'claude')
  }
  if (reason) return why('other', reason, 'claude')
  return null
}

const SDK_SOURCE: Record<string, string> = {
  policySettings: 'the managed policy',
  localSettings: 'the project’s local settings',
  projectSettings: 'the project’s settings',
  userSettings: 'your user settings',
  session: 'this session’s rules',
  cliArg: 'the command line',
}
const DEFAULT_TEXT: Record<WhyKind, string> = {
  ask_rule: 'A permission rule asks for this call.',
  hook: 'A hook asked for confirmation.',
  outside_folder: 'It reaches outside the project’s folders.',
  safety: 'One of Claude Code’s own safety checks asked.',
  classifier: 'Auto mode’s classifier asked.',
  mode: 'The session’s mode asks for this.',
  no_rule: 'No allow rule matches this call.',
  other: 'Claude Code asked.',
}

/**
 * Why, from Claude Code's suggestions on a PermissionRequest: it offers to add a folder only when
 * the call reaches one outside the project's. Null otherwise.
 */
export function whyFromSuggestions(suggestions: unknown): Why | null {
  for (const u of Array.isArray(suggestions) ? suggestions : []) {
    if (!u || typeof u !== 'object' || (u as { type?: unknown }).type !== 'addDirectories') continue
    const dirs = (u as { directories?: unknown }).directories
    const first = Array.isArray(dirs) ? dirs.find((d): d is string => typeof d === 'string' && !!d.trim()) : undefined
    return why('outside_folder', first ? `It reaches ${first}, outside the project’s folders: Claude Code offers to add it.` : 'It reaches outside the project’s folders: Claude Code offers to add the folder.', 'claude')
  }
  return null
}

// ── Hypernova's reading of the rules ─────────────────────────────────────

const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit'])
// Read rules cover the tools that read files too (Claude Code's docs say so for Grep and Glob).
const READ_TOOLS = new Set(['Read', 'Glob', 'Grep'])
const PATH_KEYS = ['file_path', 'notebook_path', 'path']

/** The file or folder a file tool's call names, or null. */
export function callPath(tool: string, input: Record<string, unknown>): string | null {
  if (!EDIT_TOOLS.has(tool) && !READ_TOOLS.has(tool)) return null
  for (const k of PATH_KEYS) if (typeof input[k] === 'string' && (input[k] as string).trim()) return (input[k] as string).trim()
  return null
}

/** A command's parts, split on && || ; | and new lines outside quotes, each trimmed. */
export function commandParts(command: string): string[] {
  const out: string[] = []
  let cur = ''
  let q: string | null = null
  for (let i = 0; i < command.length; i++) {
    const c = command[i]!
    if (q) {
      if (c === '\\' && q === '"' && i + 1 < command.length) {
        cur += c + command[++i]
        continue
      }
      if (c === q) q = null
      cur += c
      continue
    }
    if (c === '\\' && i + 1 < command.length) {
      cur += c + command[++i]
      continue
    }
    if (c === '"' || c === "'") {
      q = c
      cur += c
      continue
    }
    if (c === ';' || c === '\n' || c === '|' || (c === '&' && command[i + 1] === '&')) {
      if (cur.trim()) out.push(cur.trim())
      cur = ''
      if ((c === '|' && command[i + 1] === '|') || c === '&') i++
      continue
    }
    cur += c
  }
  if (cur.trim()) out.push(cur.trim())
  return out
}

/** One part of a command as a rule sees it: leading VAR=value words off; also with its program's folder off. */
function partForms(part: string): string[] {
  const s = part.replace(/^(\s*[A-Za-z_][A-Za-z0-9_]*=(?:'[^']*'|"[^"]*"|\S*)\s+)+/, '').trim()
  const first = /^\S+/.exec(s)?.[0] ?? ''
  // A program by its path (`.venv/bin/python …`) reads as its name too: the owner's ask rule
  // Bash(python:*) asked for one in Claude Code 2.1.316 (his Arc session, 2026-10-30).
  if (first.includes('/') && !first.endsWith('/')) return [s, first.slice(first.lastIndexOf('/') + 1) + s.slice(first.length)]
  return [s]
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** A Bash rule's content against one part of a command: exact, `prefix:*`, or with `*` wildcards. */
function commandMatches(content: string, part: string): boolean {
  return partForms(part).some((c) => {
    if (content.endsWith(':*')) {
      const p = content.slice(0, -2)
      return c === p || c.startsWith(p + ' ') || (p.endsWith(' ') && c.startsWith(p))
    }
    if (content.includes('*')) {
      // `npm test *` also covers `npm test` itself.
      const re = new RegExp('^' + content.split('*').map(escapeRe).join('.*') + '$', 's')
      return re.test(c) || (content.endsWith(' *') && c === content.slice(0, -2))
    }
    return c === content
  })
}

/** A path rule's content as a glob over absolute paths: `//abs`, `~/home`, `/` and bare from the project's folder. */
function pathMatches(content: string, file: string, cwd: string, home: string): boolean {
  const base = content.startsWith('//') ? content.slice(1) : content.startsWith('~/') ? join(home, content.slice(2)) : join(cwd, content.replace(/^\.\//, ''))
  const re = new RegExp(
    '^' +
      base
        .split(/(\*\*\/?|\*|\?)/)
        .map((p) => (p === '**' || p === '**/' ? '.*' : p === '*' ? '[^/]*' : p === '?' ? '[^/]' : escapeRe(p)))
        .join('') +
      '(/.*)?$',
  )
  return re.test(file)
}

/** The host of a URL, or null. */
function hostOf(url: unknown): string | null {
  try {
    return typeof url === 'string' ? new URL(url).hostname.toLowerCase() : null
  } catch {
    return null
  }
}

/**
 * [rule] against a call of [tool] with [input]. For a command, [part]s: an ask or deny rule asks
 * when it matches any part, an allow rule allows only when every part is matched (by some rule).
 */
export function ruleMatches(rule: RuleValue, tool: string, input: Record<string, unknown>, cwd: string, home: string = homedir(), part?: string): boolean {
  const rt = rule.toolName
  if (rt.startsWith('mcp__') && !rule.ruleContent) {
    if (rt.endsWith('__*')) return tool.startsWith(rt.slice(0, -1))
    return tool === rt || tool.startsWith(rt + '__')
  }
  const sameTool = rt === tool || (rt === 'Edit' && EDIT_TOOLS.has(tool)) || (rt === 'Read' && READ_TOOLS.has(tool))
  if (!sameTool) return false
  const content = rule.ruleContent
  if (!content) return true
  if (tool === 'Bash') {
    const cmd = typeof input.command === 'string' ? input.command : ''
    return part !== undefined ? commandMatches(content, part) : commandParts(cmd).some((p) => commandMatches(content, p))
  }
  if (tool === 'WebFetch') {
    const host = hostOf(input.url)
    const m = /^domain:(.+)$/.exec(content)
    if (!host || !m) return false
    const d = m[1]!.toLowerCase()
    return d.startsWith('*.') ? host.endsWith(d.slice(1)) : host === d
  }
  const p = callPath(tool, input)
  if (p === null) return false
  return pathMatches(content, resolveFrom(p, cwd, home), cwd, home)
}

const resolveFrom = (p: string, cwd: string, home: string) => (p.startsWith('~/') ? join(home, p.slice(2)) : isAbsolute(p) ? resolve(p) : resolve(cwd, p))
const inside = (p: string, dir: string) => p === dir || p.startsWith(dir.endsWith(sep) ? dir : dir + sep)

/** Where a source's rules are written, in words. */
const SOURCE_WORDS: Record<RuleSource, string> = {
  managed: 'the managed policy',
  session: 'this session’s own rules',
  local: 'the project’s local settings',
  project: 'the project’s settings',
  user: 'your user settings',
}

/** What the call is, in a word ("this command", "this edit"). */
const callNoun = (tool: string) => (tool === 'Bash' ? 'command' : EDIT_TOOLS.has(tool) ? 'edit' : 'call')

type Found = { rule: string; source: RuleSource }

/** The first rule of [list] (highest precedence first) that matches, or null. */
function first(files: RuleFile[], list: 'allow' | 'ask', tool: string, input: Record<string, unknown>, cwd: string, home: string, part?: string): Found | null {
  for (const f of files)
    for (const raw of f[list]) {
      const v = parseRule(raw)
      if (v && ruleMatches(v, tool, input, cwd, home, part)) return { rule: raw, source: f.source }
    }
  return null
}

/**
 * Hypernova's reading of why a call of [tool] asked in a session in [cwd] and [mode], from the
 * [rules] in effect: a matching ask rule; a file outside the project's folders; an allow rule that
 * matches anyway (so one of Claude Code's own checks asked); plan mode; or no allow rule. A
 * matching deny rule would have refused the call, so it isn't looked for.
 */
export function whyFromRules(tool: string, input: Record<string, unknown>, mode: string | null, cwd: string, rules: RulesInEffect, home: string = homedir()): Why {
  const files = rules.sources.filter((f) => f.state === 'ok' && (!rules.managedOnly || f.source === 'managed'))
  const noun = callNoun(tool)
  const ask = first(files, 'ask', tool, input, cwd, home)
  if (ask) return why('ask_rule', `Ask rule ${ask.rule} in ${SOURCE_WORDS[ask.source]} matches this ${noun}.`, 'hypernova', ask.rule)
  const p = callPath(tool, input)
  if (p !== null) {
    const abs = resolveFrom(p, cwd, home)
    const dirs = [resolve(cwd), ...files.flatMap((f) => f.additionalDirectories).map((d) => resolveFrom(d, cwd, home))]
    if (!dirs.some((d) => inside(abs, d))) return why('outside_folder', `${abs} is outside the project’s folders.`, 'hypernova')
  }
  // Every part of a command needs an allow rule of its own.
  const parts = tool === 'Bash' ? commandParts(typeof input.command === 'string' ? input.command : '') : [undefined]
  const allows = parts.map((part) => first(files, 'allow', tool, input, cwd, home, part))
  const allowed = parts.length > 0 && allows.every((a) => a !== null)
  if (allowed && mode !== 'plan') {
    const a = allows[0]!
    return why('safety', `Allow rule ${a.rule} matches, yet Claude Code asked: one of its own checks, most likely.`, 'hypernova', a.rule)
  }
  if (mode === 'plan') return why('mode', `Plan mode, and no allow rule matches this ${noun}.`, 'hypernova')
  if (mode === 'auto' || mode === 'bypassPermissions') return why('other', `No ask rule matches this ${noun}: one of Claude Code’s own checks, most likely.`, 'hypernova')
  if (parts.length > 1 && allows.some((a) => a !== null)) return why('no_rule', `No allow rule matches every part of this ${noun}.`, 'hypernova')
  return why('no_rule', `No allow rule matches this ${noun}.`, 'hypernova')
}
