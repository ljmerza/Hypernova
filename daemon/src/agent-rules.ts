// Claude Code's permission rules, to see and change (docs/PROTOCOL.md, "Permission rules"; docs/DECISIONS.md, "Permission rules,
// edited"; the risk: docs/SECURITY-MODEL.md). `permissions.allow`, `ask` and `deny` of the user-level settings.json, and nothing else.
//
// - A rule's text leaves only through maskedRule, each secret a numbered placeholder. An edit that keeps one keeps the secret.
// - The server decides what loosens. Without `confirmed` that's a 403 whose words say what the change does: a one-tap confirm, by
//   the owner's choice no fingerprint, so nothing loosens by accident rather than to stop someone holding the device.
// - One writer: openForEdit, jsonListEdit and writeEdit, as PUT /v1/agents/settings for any other key. Only a rule's bytes change.
//
// Codex has no such lists: `approval_policy` and `sandbox_mode` are on its settings, and `~/.codex/rules` holds Starlark programs.
// Only how many files are there is read, never what's in them.

import { readdirSync } from 'node:fs'
import { join } from 'node:path'
import { isTable, codexHome, parseTable, readCapped } from './agent-settings.ts'
import { EditRefused, fieldsOf, jsonListEdit, openForEdit, RULES_KEY, rulesUndo, writeEdit, type Dirs } from './agent-settings-edit.ts'
import { broadWords, covers, judgeRule, LIST_WORDS, maskedRule, restoredRule, RULE_LISTS, type RuleList } from './rule-text.ts'
import { MASK } from './mcp-view.ts'
import { CLAUDE_DIR, log } from './util.ts'

/** Past this a file isn't for a phone's list, as for raw JSON. */
const RULES_MAX = 256 * 1024
/** A rule typed here, at most. */
const RULE_MAX = 8192
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/

export type RuleRow = { index: number; text: string; masked: boolean; problem?: string; warning?: string; broad?: string; overriddenBy?: 'ask' | 'deny'; overrides?: string }
/** [rules] null: the file has something other than a list here, which makes Claude Code skip the whole file. [others]: entries that aren't text, which it drops. */
export type RuleListReply = { rules: RuleRow[] | null; others: number }
export type RulesReply =
  | { agent: 'claude'; supported: true; where: string; state: 'ok' | 'missing' | 'malformed' | 'unreadable' | 'too_big'; whyNot: string | null; hash: string | null; lists: Record<RuleList, RuleListReply> | null; undo: { needsConfirm: boolean; change: string } | null }
  | { agent: 'codex'; supported: false; rulesFiles: number | null }

const stored = (doc: Record<string, unknown>, list: RuleList): unknown => {
  const p = doc.permissions
  return isTable(p) && Object.hasOwn(p, list) ? p[list] : []
}

function rowsOf(doc: Record<string, unknown>): Record<RuleList, RuleListReply> {
  const strings = (list: RuleList) => {
    const l = stored(doc, list)
    return Array.isArray(l) ? l.filter((r): r is string => typeof r === 'string') : []
  }
  const out = {} as Record<RuleList, RuleListReply>
  for (const list of RULE_LISTS) {
    const raw = stored(doc, list)
    if (!Array.isArray(raw)) {
      out[list] = { rules: null, others: 0 }
      continue
    }
    const higher: RuleList[] = list === 'allow' ? ['deny', 'ask'] : list === 'ask' ? ['deny'] : []
    const rules: RuleRow[] = []
    let others = 0
    raw.forEach((r, index) => {
      if (typeof r !== 'string') return void others++
      const { shown, secrets } = maskedRule(r)
      const row: RuleRow = { index, text: shown, masked: secrets.length > 0 }
      const { problem, warning } = judgeRule(r, list, shown)
      if (problem) row.problem = problem
      if (warning) row.warning = warning
      if (!problem) {
        if (list === 'allow') {
          const broad = broadWords(r)
          if (broad) row.broad = broad
        }
        for (const over of higher) {
          const by = strings(over).find((h) => !judgeRule(h, over).problem && covers(h, r))
          if (by) {
            row.overriddenBy = over as 'ask' | 'deny'
            row.overrides = maskedRule(by).shown
            break
          }
        }
      }
      rules.push(row)
    })
    out[list] = { rules, others }
  }
  return out
}

/** GET /v1/agents/rules. Whatever state the file is in, a 200. A file that's a link is shown and can't be changed: what's in it is read as Claude Code reads it. */
export async function rulesReply(agent: 'claude' | 'codex', dirs: Dirs = {}): Promise<RulesReply> {
  if (agent === 'codex') {
    try {
      const names = readdirSync(join(dirs.codex ?? codexHome(), 'rules'))
      return { agent, supported: false, rulesFiles: names.filter((n) => n.endsWith('.rules')).length }
    } catch (e) {
      return { agent, supported: false, rulesFiles: (e as NodeJS.ErrnoException).code === 'ENOENT' ? 0 : null }
    }
  }
  // The hash first: if the file moves on after it, a change is refused rather than made over what wasn't seen.
  const { hash, whyNot } = fieldsOf('claude', dirs, null)
  const base = { agent, supported: true as const, where: '~/.claude/settings.json', whyNot, hash, lists: null, undo: null }
  const read = await readCapped(join(dirs.claude ?? CLAUDE_DIR, 'settings.json'), RULES_MAX, true)
  if ('state' in read) {
    if (read.state === 'missing') return { ...base, state: 'missing', lists: rowsOf({}) }
    return { ...base, state: read.state === 'unreadable' ? 'unreadable' : 'malformed' }
  }
  const doc = parseStrict(read.text)
  if (!doc) return { ...base, state: 'malformed' }
  return { ...base, state: 'ok', lists: rowsOf(doc), undo: rulesUndo(hash, dirs) }
}

/** Not as parseTable, which takes an empty file as an empty object: this is what's edited, and jsonListEdit wouldn't take it. */
function parseStrict(text: string): Record<string, unknown> | null {
  return text.trim() ? parseTable(text) : null
}

// ── A change ─────────────────────────────────────────────────────────────

type Wanted =
  | { op: 'add'; list: RuleList; text: string }
  | { op: 'edit'; list: RuleList; index: number; text: string }
  | { op: 'delete'; list: RuleList; index: number }
  | { op: 'move'; list: RuleList; index: number; to: RuleList }

const isList = (v: unknown): v is RuleList => v === 'allow' || v === 'ask' || v === 'deny'
const badRequest = () => new EditRefused(400, 'bad_request', 'Say which rule, in which list, and what to do with it.')

function wanted(rule: unknown): Wanted {
  if (!isTable(rule) || !isList(rule.list)) throw badRequest()
  const { op, list, index, text, to } = rule
  const at = () => {
    if (typeof index !== 'number' || !Number.isInteger(index) || index < 0) throw badRequest()
    return index
  }
  const typed = () => {
    if (typeof text !== 'string') throw badRequest()
    return text
  }
  if (op === 'add') return { op, list, text: typed() }
  if (op === 'edit') return { op, list, index: at(), text: typed() }
  if (op === 'delete') return { op, list, index: at() }
  if (op === 'move' && isList(to) && to !== list) return { op, list, index: at(), to }
  throw badRequest()
}

/** What's typed, as it goes in the file: spaces round it off (measured: Claude Code doesn't read a rule with them), and each placeholder back. */
function typedRule(text: string, was: string | null, list: RuleList, asTyped: boolean): string {
  const t = text.trim()
  if (t.length > RULE_MAX || CONTROL.test(t)) throw new EditRefused(400, 'bad_value', 'That can’t be a rule: it’s too long, or has control characters in it.')
  if (t.includes(MASK)) throw new EditRefused(400, 'masked_value', 'That still has a mask in it. Type what goes there, or start again from the rule as it’s shown.')
  const real = restoredRule(t, was)
  if (real === null) throw new EditRefused(400, 'placeholder_unknown', 'A hidden value stays where it was. Put it back, or type a new one.')
  if (!asTyped) {
    const { problem } = judgeRule(real, list, maskedRule(real).shown)
    if (problem) throw new EditRefused(400, 'bad_rule', problem)
  }
  return real
}

const quoted = (r: string) => maskedRule(r).shown

/** The words of the confirm: what this does, plainly. Null: it only tightens, so it doesn't ask. */
function loosening(w: Wanted, before: string | null, after: string | null): string | null {
  const broad = after !== null && (w.op === 'add' || w.op === 'edit' || w.op === 'move') && (w.op === 'move' ? w.to : w.list) === 'allow' ? broadWords(after) : null
  const tail = broad ? ` ${broad}` : ''
  if (w.op === 'add') return w.list === 'allow' ? (broad ? `${quoted(after!)} goes on Allow. ${broad}` : `Claude Code will do what ${quoted(after!)} covers without asking.`) : null
  if (w.op === 'edit') {
    if (w.list === 'allow') return `${quoted(before!)} becomes ${quoted(after!)}. Claude Code will do what that covers without asking.${tail}`
    return `${quoted(before!)} becomes ${quoted(after!)}. What only the old one covered is no longer on ${LIST_WORDS[w.list]}.`
  }
  if (w.op === 'delete') {
    if (w.list === 'allow') return null
    return `${quoted(before!)} comes off ${LIST_WORDS[w.list]}. What only this ${w.list === 'deny' ? 'stopped' : 'made Claude Code ask about'} goes by the other rules and the mode instead.`
  }
  const rank = (l: RuleList) => RULE_LISTS.indexOf(l)
  if (rank(w.to) > rank(w.list)) return null
  if (w.to === 'allow') return `${quoted(before!)} moves from ${LIST_WORDS[w.list]} to Allow. Claude Code will do what it covers without asking.${tail}`
  return `${quoted(before!)} moves from Deny to Ask. Claude Code will ask about what it covers rather than refuse.`
}

export const isRuleChange = (b: unknown): b is Record<string, unknown> => isTable(b) && b.rule !== undefined

/**
 * PUT /v1/agents/settings with `rule`: one rule added, edited, taken out or moved. All of it in one synchronous stretch, as any other
 * key. `expected` is the file's hash, so an index means what the client saw. `confirmed`: the owner has read what it does. `asTyped`:
 * keep a rule Claude Code would skip, for what this can't judge.
 */
export function changeRule(body: Record<string, unknown>, dirs: Dirs = {}, testHooks: { beforeRename?: () => void } = {}) {
  if (body.agent !== 'claude') throw new EditRefused(409, 'not_for_codex', 'Codex has no lists of rules to change.')
  const expected = body.expected
  if (expected !== null && typeof expected !== 'string') throw new EditRefused(400, 'bad_expected', 'Say which version of the file this is for.')
  const w = wanted(body.rule)
  const { file, cur, text } = openForEdit('claude', dirs, expected)
  const doc = text === null ? {} : (JSON.parse(text) as Record<string, unknown>)
  const from = stored(doc, w.list)
  if (!Array.isArray(from)) throw new EditRefused(409, 'unsupported_shape', 'That list isn’t a list in the file, so Claude Code is skipping the whole file. Fix it on the server.')
  const was = w.op === 'add' ? null : from[w.index]
  if (w.op !== 'add' && typeof was !== 'string') throw new EditRefused(409, 'file_changed', 'That rule isn’t there any more. Nothing was written.')
  const asTyped = body.asTyped === true
  const before = was as string | null
  const same = (hash = cur?.hash ?? '') => ({ ok: true as const, agent: 'claude' as const, key: RULES_KEY, value: null, changed: false, hash, loosened: false })
  const path = (l: RuleList) => ['permissions', l]

  let next: string | null = null
  let after: string | null = null
  const base = text ?? '{}\n'
  switch (w.op) {
    case 'add':
      after = typedRule(w.text, null, w.list, asTyped)
      if (from.includes(after)) return same()
      next = jsonListEdit(base, path(w.list), { op: 'add', text: after })
      break
    case 'edit':
      after = typedRule(w.text, before, w.list, asTyped)
      if (after === before) return same()
      if (from.includes(after)) throw new EditRefused(400, 'bad_rule', `${LIST_WORDS[w.list]} already has that rule.`)
      next = jsonListEdit(base, path(w.list), { op: 'replace', index: w.index, text: after })
      break
    case 'delete':
      next = jsonListEdit(base, path(w.list), { op: 'remove', index: w.index })
      break
    case 'move': {
      after = before
      const into = stored(doc, w.to)
      if (!Array.isArray(into)) throw new EditRefused(409, 'unsupported_shape', 'That list isn’t a list in the file, so Claude Code is skipping the whole file. Fix it on the server.')
      next = jsonListEdit(base, path(w.list), { op: 'remove', index: w.index })
      if (!into.includes(before)) next = jsonListEdit(next, path(w.to), { op: 'add', text: before! })
      break
    }
  }
  const asks = loosening(w, before, after)
  if (asks && body.confirmed !== true) throw new EditRefused(403, 'confirm_needed', asks)
  const hash = writeEdit('claude', file, cur, next, expected, RULES_KEY, { key: RULES_KEY, state: 'unset' }, null, testHooks)
  // Which list and what was done, never a rule's text: it can hold a secret.
  log(`agent settings: claude rules ${w.op} ${w.list}${w.op === 'move' ? ` -> ${w.to}` : ''}${asks ? ' (loosens, confirmed)' : ''}`)
  return { ok: true as const, agent: 'claude' as const, key: RULES_KEY, value: null, changed: true, hash, loosened: !!asks }
}
