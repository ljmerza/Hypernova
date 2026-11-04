// Every one of the agents' settings as a form, generated from their own schemas (docs/DECISIONS.md, "Every setting, from the
// agents' own schemas"; docs/PROTOCOL.md, "The settings form"; the risk: docs/SECURITY-MODEL.md, 25). The server decides: a row
// says what control it takes and what it asks, and a change is judged here whatever a client sends. What stands:
//
// - Only agent-settings-edit.ts writes, by position, with its hash, backup, Undo and refused links. Nothing here touches a file.
// - agent-settings-classes.ts says what each key takes, and a key it doesn't know asks.
// - What runs code or loosens something wants `confirm: true`: a plain one-tap confirm the owner chose over the fingerprint.
// - A secret goes out as the JSON tab's placeholder (agent-raw.ts), comes back as what's stored, and is refused anywhere else.
// - Nothing a value holds reaches a log: only the key's name.
//
// Claude Code: every key, in `settings.json`. Codex's `config.toml`: only plain values at its top level, as far as the TOML
// reader can see inside to mask (DECISIONS, "JSON mode", 10); its tables and lists are shown as there, and changed on the server.

import { accessSync, constants, existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { classify, maskedAt, parseError, PLACEHOLDER, restoredAt } from './agent-raw.ts'
import { MCP_NAME, nameIsClean, modelIdFits, type ShownValue } from './agent-settings.ts'
import { CLAUDE_EXTRA, CLAUDE_KEYS, CLAUDE_NESTED, CODEX_KEYS, GROUPS, UNKNOWN, type Applies, type Entry, type Screen, type Take } from './agent-settings-classes.ts'
import { backupDir, commitEdit, EDITABLE, EditRefused, fileOf, isTable, jsonEditAny, loosens, openForEdit, parseJson, parseToml, readCurrent, readLast, restoreLast, textOf, TOML_NUMBER, tomlEdit, tomlRemove, type Agent, type Dirs, type Last, type ModelList, type WhyNot } from './agent-settings-edit.ts'
import { problem, resolveRef, type Schema } from './json-schema-lite.ts'
import { OTHER } from './toml-reader.ts'
import { log } from './util.ts'

const SCHEMAS = new URL('./schemas/', import.meta.url)
const loaded: Partial<Record<Agent | 'sources', Schema>> = {}
function loadOnce(name: Agent | 'sources', file: string): Schema {
  return (loaded[name] ??= JSON.parse(readFileSync(new URL(file, SCHEMAS), 'utf8')) as Schema)
}
export const schemaOf = (agent: Agent): Schema => loadOnce(agent, agent === 'claude' ? 'claude-code-settings.schema.json' : 'codex-config.schema.json')
export const sourceOf = (agent: Agent): Schema => loadOnce('sources', 'sources.json')[agent]
const tableOf = (agent: Agent) => (agent === 'claude' ? CLAUDE_KEYS : CODEX_KEYS)

/** A value past this isn't sent to a phone's form: the JSON tab is the way to it. */
const VALUE_MAX = 64 * 1024
/** What a client may send for one key. */
const SENT_MAX = 256 * 1024
const DESCRIPTION_MAX = 600

export type Control = 'switch' | 'pick' | 'text' | 'number' | 'list' | 'json' | 'link' | 'readonly'
export type Ask = Exclude<Take, 'plain'>
export type Rules = { min?: number; max?: number; above?: number; integer?: true; minLength?: number; maxLength?: number; pattern?: string; minItems?: number; maxItems?: number; unique?: true; itemMinLength?: number }
type Shape = { control: Control; of?: unknown[] } & Rules
export type OfferedValue = { value: string | boolean; label?: string; asks: Ask | null }
export type FormRow = {
  key: string
  group: string
  groupTitle: string
  title: string
  description: string
  control: Control
  /** Set in the file, or not; `opaque`: set, in a shape that doesn't leave. */
  state: 'set' | 'unset' | 'opaque'
  value?: unknown
  /** A `json` row's value as text, laid out, so a client neither reorders nor reformats it. */
  text?: string
  /** Places in [value] kept back on the server. */
  hidden?: number
  default?: unknown
  of?: OfferedValue[]
  rules?: Rules
  /** Whether a change is asked about first. Each of [of] says for itself where the direction decides. */
  asks: Ask | null
  /** Why it can't be changed here. */
  readOnly?: 'managed' | 'not_offered' | 'shape' | 'too_big'
  /** It can be taken out of the file. */
  removable: boolean
  screen?: Screen
  /** The installed agent's own check doesn't know this key: it may do nothing. */
  notRead?: true
  /** It's in the file, and not in the schema. */
  notInSchema?: true
  applies?: Applies
}
export type FormUndo = { key: string; asks: boolean }
export type FormReply = {
  agent: Agent
  scope: 'user'
  source: { from: string; licence: string; fetched: string; checkedAgainst: string }
  placeholder: string
  state: 'ok' | 'missing' | 'malformed' | 'unreadable'
  hash: string | null
  whyNot: WhyNot
  undo: FormUndo | null
  rows: FormRow[]
  /** Keys of the file whose names don't go out: they look like a secret or aren't a key's shape. */
  hiddenKeys: number
}

const flat = (root: Schema, s: Schema): Schema => {
  let at = s
  for (let i = 0; i < 8; i++) {
    if (at.$ref) {
      const { $ref, ...rest } = at
      at = { ...(resolveRef(root, $ref) ?? {}), ...rest }
    } else if (Array.isArray(at.allOf) && at.allOf.length === 1) {
      const { allOf, ...rest } = at
      at = { ...allOf[0], ...rest }
    } else break
  }
  return at
}

/** The words of an enum, also one split over `anyOf` or `oneOf`. A free-text branch beside them (a custom theme) stays open to the file and to the JSON tab. */
function wordsOf(root: Schema, s: Schema): unknown[] | null {
  if (Array.isArray(s.enum)) return s.enum
  const parts = s.anyOf ?? s.oneOf
  if (!Array.isArray(parts)) return null
  const flats = parts.map((p: Schema) => flat(root, p))
  if (!flats.every((p: Schema) => p.type === 'string')) return null
  const words = flats.flatMap((p: Schema) => (Array.isArray(p.enum) ? p.enum : []))
  return words.length ? words : null
}

/** What control a schema's key takes: the same for any key, so a new one needs no code. */
export function shapeOf(root: Schema, schema: Schema): Shape {
  const s = flat(root, schema)
  const words = wordsOf(root, s)
  if (words) return { control: 'pick', of: words }
  const types: string[] = (Array.isArray(s.type) ? s.type : s.type ? [s.type] : []).filter((t: string) => t !== 'null')
  if (types.length !== 1) return { control: 'json' }
  switch (types[0]) {
    case 'boolean':
      return { control: 'switch' }
    case 'string':
      return { control: 'text', ...(s.minLength !== undefined ? { minLength: s.minLength } : {}), ...(s.maxLength !== undefined ? { maxLength: s.maxLength } : {}), ...(s.pattern ? { pattern: s.pattern } : {}) }
    case 'integer':
    case 'number':
      return { control: 'number', ...(types[0] === 'integer' ? { integer: true as const } : {}), ...(s.minimum !== undefined ? { min: s.minimum } : {}), ...(s.maximum !== undefined ? { max: s.maximum } : {}), ...(s.exclusiveMinimum !== undefined ? { above: s.exclusiveMinimum } : {}) }
    case 'array': {
      const items = isTable(s.items) ? flat(root, s.items) : null
      if (items?.type !== 'string' || items.enum) return { control: 'json' }
      return { control: 'list', ...(s.minItems !== undefined ? { minItems: s.minItems } : {}), ...(s.maxItems !== undefined ? { maxItems: s.maxItems } : {}), ...(s.uniqueItems ? { unique: true as const } : {}), ...(items.minLength !== undefined ? { itemMinLength: items.minLength } : {}) }
    }
    default:
      return { control: 'json' }
  }
}

/** The schema's words, without the links out: nothing here opens a browser for them. */
export function plainDescription(text: unknown): string {
  if (typeof text !== 'string') return ''
  const out = text.replace(/\s*See https?:\/\/\S+(?:\s+and\s+https?:\/\/\S+)*(?:\s+for [^.]*\.)?\.?/g, '').trim()
  return out.length > DESCRIPTION_MAX ? `${out.slice(0, DESCRIPTION_MAX - 1).trimEnd()}…` : out
}

/** `autoCompactEnabled` and `model_verbosity`, in words, for a key the app has none of its own for. */
export function titleOf(key: string): string {
  const leaf = key.split('.').at(-1)!.replace(/^\$/, '')
  const words = leaf.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ').replace(/ [Ee]nabled$/, '').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const askWords: Record<Ask, string> = {
  runs: 'This can run a command on the server, as you, with nothing more to confirm.',
  loosens: 'This lets the agent do more without asking you, or takes a guard off.',
  reach: 'This changes what’s allowed, who can sign in, or where data and files go.',
  unknown: 'Hypernova doesn’t know what this setting does, so it asks first.',
}
const needsConfirm = (key: string, ask: Ask) => new EditRefused(403, 'confirm_needed', `${key}\n\n${askWords[ask]}`)
const notAllowed = () => new EditRefused(403, 'value_not_allowed', 'That value can’t be chosen from here. Change it on the server.')

/** The keys that go by how far they allow, not by what they are: agent-settings-edit.ts's order decides, each way. */
const DIRECTED = new Set(['permissions.defaultMode', 'approval_policy', 'sandbox_mode'])
const shownOf = (key: string, found: boolean, v: unknown): ShownValue => (!found ? { key, state: 'unset' } : typeof v === 'string' || typeof v === 'boolean' ? { key, state: 'set', value: v } : { key, state: 'unrecognised' })
function askOf(key: string, entry: Entry, found: boolean, was: unknown, to: unknown): Ask | null {
  if (DIRECTED.has(key)) return loosens(key, shownOf(key, found, was), typeof to === 'string' || typeof to === 'boolean' ? to : null) ? 'loosens' : null
  return entry.take === 'plain' ? null : entry.take
}

/** The list-screen rule for a key, if it has one: the values it offers are all the form offers. */
const ruleFor = (agent: Agent, key: string) => EDITABLE.find((r) => r.agent === agent && r.key === key)

const at = (doc: Record<string, unknown>, path: readonly string[]): { found: boolean; v: unknown } => {
  let d: unknown = doc
  for (const p of path) {
    if (!isTable(d) || !Object.hasOwn(d, p)) return { found: false, v: undefined }
    d = d[p]
  }
  return { found: true, v: d }
}

/** A key of the file that isn't in the schema, as far as its name may go out. */
const nameFits = (key: string) => MCP_NAME.test(key) && nameIsClean(key) && !Object.hasOwn(Object.prototype, key)

const claudeSlots = (): { key: string; path: string[]; entry: Entry; schema: Schema }[] => {
  const root = schemaOf('claude')
  const out: { key: string; path: string[]; entry: Entry; schema: Schema }[] = []
  for (const [key, schema] of Object.entries<Schema>(root.properties)) {
    if (key === 'permissions') {
      for (const [sub, inner] of Object.entries<Schema>(schema.properties)) {
        const full = `permissions.${sub}`
        out.push({ key: full, path: ['permissions', sub], entry: Object.hasOwn(CLAUDE_NESTED, full) ? CLAUDE_NESTED[full]! : UNKNOWN, schema: inner })
      }
    } else out.push({ key, path: [key], entry: Object.hasOwn(CLAUDE_KEYS, key) ? CLAUDE_KEYS[key]! : UNKNOWN, schema })
  }
  for (const [key, entry] of Object.entries(CLAUDE_EXTRA)) out.push({ key, path: [key], entry, schema: {} })
  return out
}

function baseRow(key: string, entry: Entry, description: string): Pick<FormRow, 'key' | 'group' | 'groupTitle' | 'title' | 'description' | 'asks'> & Partial<FormRow> {
  return {
    key,
    group: entry.group,
    groupTitle: GROUPS[entry.group] ?? GROUPS.other!,
    title: titleOf(key),
    description,
    asks: entry.take === 'plain' || DIRECTED.has(key) ? null : entry.take,
    ...(entry.notRead ? { notRead: true as const } : {}),
    ...(entry.applies ? { applies: entry.applies } : {}),
  }
}

function offered(agent: Agent, key: string, shape: Shape, entry: Entry, found: boolean, was: unknown, models: ModelList): OfferedValue[] | undefined {
  const rule = ruleFor(agent, key)
  const list: { value: string | boolean; label?: string }[] | null = rule ? (rule.of === 'models' ? (models ?? []).filter((m) => modelIdFits(m.value)).map((m) => ({ value: m.value, label: m.name })) : rule.of.map((value) => ({ value }))) : shape.of ? (shape.of.filter((v) => typeof v === 'string' || typeof v === 'boolean') as (string | boolean)[]).map((value) => ({ value })) : null
  return list?.map((c) => ({ ...c, asks: askOf(key, entry, found, was, c.value) }))
}

function claudeRow(slot: ReturnType<typeof claudeSlots>[number], doc: Record<string, unknown>): FormRow | null {
  const { key, path, entry, schema } = slot
  const root = schemaOf('claude')
  const { found, v } = at(doc, path)
  const base = baseRow(key, entry, plainDescription(schema.description))
  if (entry.managed && !found) return null
  if (entry.screen) return { ...base, control: 'link', state: found ? 'opaque' : 'unset', asks: null, removable: false, screen: entry.screen }
  const shape = shapeOf(root, schema)
  const dflt = flat(root, schema).default
  const out: FormRow = {
    ...base,
    control: shape.control,
    state: found ? 'set' : 'unset',
    removable: found,
    ...(dflt !== undefined && dflt !== null && !(typeof dflt === 'object' && !Object.keys(dflt).length) ? { default: dflt } : {}),
  }
  const { control: _c, of: _o, ...rules } = shape
  if (Object.keys(rules).length) out.rules = rules
  const choices = offered('claude', key, shape, entry, found, v, null)
  if (choices) {
    out.control = 'pick'
    out.of = choices
  }
  if (found) {
    if (entry.mask && typeof v === 'string') {
      out.value = PLACEHOLDER
      out.hidden = 1
    } else {
      const masked = maskedAt(path, v)
      if (JSON.stringify(masked.value).length > VALUE_MAX) {
        out.state = 'opaque'
        out.readOnly = 'too_big'
      } else {
        out.value = masked.value
        if (masked.hidden) out.hidden = masked.hidden
      }
    }
    // What's in the file isn't what the control holds: it's shown as it is, and edited as JSON.
    if (!out.readOnly && !out.of && problem(root, schema, v) !== null) out.control = 'json'
  } else if (out.control === 'json') {
    // Where an editor starts for a key that isn't there.
    const type = flat(root, schema).type
    out.text = type === 'array' ? '[]' : type === 'object' ? '{}' : '""'
  }
  if (entry.refuse) {
    out.readOnly = 'not_offered'
    out.control = 'readonly'
  }
  if (entry.managed) {
    out.readOnly = 'managed'
    out.control = 'readonly'
    out.removable = false
  }
  if (out.readOnly === 'too_big') out.control = 'readonly'
  return out
}

function codexRows(text: string | null, models: ModelList): FormRow[] {
  const root = schemaOf('codex')
  const statements = text === null ? [] : parseToml(text) ?? []
  const present = new Map<string, { value?: string | boolean | number; plain: boolean }>()
  for (const s of statements) {
    if (s.kind === 'table') {
      if (!present.has(s.path[0]!)) present.set(s.path[0]!, { plain: false })
      continue
    }
    if (s.table.length) continue
    const name = s.key[0]!
    const bytes = text!.slice(s.valueFrom, s.valueTo)
    if (s.key.length === 1 && s.value !== OTHER) present.set(name, { value: s.value as string | boolean, plain: !bytes.includes('\n') })
    else if (s.key.length === 1 && TOML_NUMBER.test(bytes)) present.set(name, { value: Number(bytes), plain: true })
    else present.set(name, { plain: false })
  }
  const rows: FormRow[] = []
  for (const [key, schema] of Object.entries<Schema>(root.properties)) {
    const entry = Object.hasOwn(CODEX_KEYS, key) ? CODEX_KEYS[key]! : UNKNOWN
    const has = present.get(key)
    const base = baseRow(key, entry, plainDescription(schema.description ?? flat(root, schema).description))
    if (entry.screen) {
      rows.push({ ...base, control: 'link', state: has ? 'opaque' : 'unset', asks: null, removable: false, screen: entry.screen })
      continue
    }
    const shape = shapeOf(root, schema)
    const choices = offered('codex', key, shape, entry, !!has, has?.value, models)
    const scalar = !!choices || shape.control === 'switch' || shape.control === 'text' || shape.control === 'number'
    // A list, a table, or anything else the TOML reader can't look inside to mask: that it's there, and no more.
    if (!scalar || (has && !has.plain)) {
      rows.push({ ...base, control: 'readonly', state: has ? 'opaque' : 'unset', readOnly: 'shape', removable: false })
      continue
    }
    const out: FormRow = { ...base, control: choices ? 'pick' : shape.control, state: has ? 'set' : 'unset', removable: !!has }
    if (choices) out.of = choices
    const { control: _c, of: _o, ...rules } = shape
    if (Object.keys(rules).length) out.rules = rules
    const dflt = flat(root, schema).default
    if (dflt !== undefined && dflt !== null) out.default = dflt
    if (has) {
      const masked = maskedAt([key], has.value)
      out.value = masked.value
      if (masked.hidden) out.hidden = masked.hidden
      if (!choices && problem(root, schema, has.value) !== null) {
        out.control = 'readonly'
        out.readOnly = 'shape'
      }
    }
    rows.push(out)
  }
  // Keys of the file its schema has never heard of: there, and a name at most.
  for (const [key, has] of present) {
    if (Object.hasOwn(root.properties, key) || !nameFits(key)) continue
    rows.push({ ...baseRow(key, UNKNOWN, ''), control: 'readonly', state: has ? 'opaque' : 'unset', readOnly: 'shape', removable: false, notInSchema: true })
  }
  return rows
}

function backupBytes(last: Last): Buffer | null {
  return last.backup ? readFileSync(join(backupDir(), last.backup)) : null
}

/** What Undo of the form's last change would take, or null when it can't: it's a change too, so it goes by the same rules. */
function undoJudged(agent: Agent, text: string, last: Last): { asks: Ask | null } | null {
  const bytes = backupBytes(last)
  const was = bytes ? textOf(bytes) : '{}'
  if (was === null) return null
  if (agent === 'claude') {
    const now = parseJson(text)
    const before = parseJson(was)
    if (!now || !before) return null
    if (classify(now, before).some((c) => c.take === 'refused')) return null
    const slot = claudeSlotOf(last.key, now, before)
    if (!slot) return null
    const a = at(now, slot.path)
    const b = at(before, slot.path)
    return { asks: askOf(last.key, slot.entry, a.found, a.v, b.found ? b.v : null) }
  }
  const entry = Object.hasOwn(CODEX_KEYS, last.key) ? CODEX_KEYS[last.key]! : UNKNOWN
  const rule = ruleFor('codex', last.key)
  const beforeValue = codexValue(was, last.key)
  if (rule && rule.of !== 'models' && beforeValue.found && !rule.of.includes(beforeValue.v as string)) return null
  const nowValue = codexValue(text, last.key)
  return { asks: askOf(last.key, entry, nowValue.found, nowValue.v, beforeValue.found ? beforeValue.v : null) }
}

function codexValue(text: string, key: string): { found: boolean; v: unknown } {
  for (const s of parseToml(text) ?? []) {
    if (s.kind !== 'key' || s.table.length || s.key.length !== 1 || s.key[0] !== key) continue
    if (s.value !== OTHER) return { found: true, v: s.value }
    const bytes = text.slice(s.valueFrom, s.valueTo)
    return { found: true, v: TOML_NUMBER.test(bytes) ? Number(bytes) : undefined }
  }
  return { found: false, v: undefined }
}

function formUndo(agent: Agent, text: string | null, hash: string | null): FormUndo | null {
  if (hash === null || text === null) return null
  const last = readLast(agent)
  if (!last?.form || last.wrote !== hash) return null
  try {
    const judged = undoJudged(agent, text, last)
    return judged ? { key: last.key, asks: judged.asks !== null } : null
  } catch {
    return null
  }
}

/** GET /v1/agents/settings/form. It doesn't throw: what's wrong with a file is its state. */
export async function settingsForm(agent: Agent, models: () => Promise<ModelList>, dirs: Dirs = {}): Promise<FormReply> {
  const { url: _u, file: _f, ...source } = sourceOf(agent)
  const file = fileOf(agent, dirs)
  const reply: FormReply = { agent, scope: 'user', source: source as FormReply['source'], placeholder: PLACEHOLDER, state: 'ok', hash: null, whyNot: null, undo: null, rows: [], hiddenKeys: 0 }
  let cur: ReturnType<typeof readCurrent>
  try {
    cur = readCurrent(file)
  } catch (e) {
    const code = e instanceof EditRefused ? e.code : ''
    return { ...reply, state: code === 'no_folder' ? 'missing' : 'unreadable', whyNot: code === 'file_is_link' ? 'link' : code === 'no_folder' ? 'no_folder' : 'unreadable' }
  }
  if (!cur) {
    reply.state = 'missing'
    if (!existsSync(dirname(file))) reply.whyNot = 'no_folder'
  }
  reply.hash = cur?.hash ?? null
  const text = cur ? textOf(cur.bytes) : null
  if (cur && text === null) return { ...reply, state: 'unreadable', whyNot: 'unreadable' }
  if (text !== null && !(agent === 'claude' ? parseJson(text) : parseToml(text))) return { ...reply, state: 'malformed' }
  try {
    if (!reply.whyNot) {
      accessSync(cur ? file : dirname(file), constants.W_OK)
      if (cur) accessSync(dirname(file), constants.W_OK)
    }
  } catch {
    reply.whyNot = 'read_only'
  }
  reply.undo = formUndo(agent, text, reply.hash)
  if (agent === 'codex') {
    const list = ruleFor('codex', 'model') ? await models().catch(() => null) : null
    return { ...reply, rows: sorted(codexRows(text, list)) }
  }
  const doc = text === null ? {} : parseJson(text)!
  for (const slot of claudeSlots()) {
    const row = claudeRow(slot, doc)
    if (row) reply.rows.push(row)
  }
  reply.rows.push(...claudeExtras(doc, reply))
  return { ...reply, rows: sorted(reply.rows) }
}

const GROUP_ORDER = Object.keys(GROUPS)
/** In the groups' order, each by its key: what's set going first is the client's to do, since it follows what's searched. */
const sorted = (rows: FormRow[]): FormRow[] => rows.map((r) => ((r.control === 'json' || r.control === 'readonly') && r.value !== undefined ? { ...r, text: JSON.stringify(r.value, null, 2) } : r)).sort((a, b) => GROUP_ORDER.indexOf(a.group) - GROUP_ORDER.indexOf(b.group) || a.key.localeCompare(b.key))

function claudeExtras(doc: Record<string, unknown>, reply: FormReply): FormRow[] {
  const rows: FormRow[] = []
  const known = new Set(Object.keys(schemaOf('claude').properties).concat(Object.keys(CLAUDE_EXTRA)))
  for (const key of Object.keys(doc)) {
    if (known.has(key)) continue
    if (!nameFits(key)) {
      reply.hiddenKeys++
      continue
    }
    const masked = maskedAt([key], doc[key])
    const size = JSON.stringify(masked.value).length
    rows.push({
      ...baseRow(key, UNKNOWN, ''),
      control: size > VALUE_MAX ? 'readonly' : 'json',
      state: size > VALUE_MAX ? 'opaque' : 'set',
      removable: true,
      notInSchema: true,
      ...(size > VALUE_MAX ? { readOnly: 'too_big' as const } : { value: masked.value }),
      ...(masked.hidden ? { hidden: masked.hidden } : {}),
    })
  }
  return rows
}

/** Where a key goes and what it takes, by the schema, or the file for one it doesn't have. [docs]: where a key not in the schema may be. */
function claudeSlotOf(key: string, ...docs: Record<string, unknown>[]): { path: string[]; entry: Entry; schema: Schema | null } | null {
  const slot = claudeSlots().find((s) => s.key === key)
  if (slot) return slot
  if (key === 'permissions') return null
  if (nameFits(key) && docs.some((d) => Object.hasOwn(d, key))) return { path: [key], entry: UNKNOWN, schema: null }
  return null
}

export type FormChange = { ok: true; agent: Agent; key: string; changed: boolean; hash: string | null; undo: FormUndo | null }

function stated(body: unknown): { agent: Agent; key: string; value: unknown; expected: string | null; confirm: boolean } {
  if (!isTable(body)) throw new EditRefused(400, 'bad_request', 'Send an agent, a key, a value and what you expect the file to be.')
  const { agent, key, expected, confirm, valueJson } = body
  if (agent !== 'claude' && agent !== 'codex') throw new EditRefused(400, 'bad_agent', 'That isn’t an agent with settings here.')
  if (typeof key !== 'string') throw new EditRefused(400, 'bad_request', 'Say which setting this is for.')
  if (expected !== null && typeof expected !== 'string') throw new EditRefused(400, 'bad_expected', 'Say which version of the file this is for.')
  // A JSON editor's text, so its keys arrive in the order they were typed. Its error says where, never what.
  let value = body.value
  if (typeof valueJson === 'string') {
    if (valueJson.length > SENT_MAX) throw new EditRefused(400, 'bad_value', 'That’s too long for one setting.')
    const bad = parseError(valueJson)
    if (bad) throw new EditRefused(400, 'bad_json', `Line ${bad.line}, column ${bad.column}: that isn’t valid JSON. Nothing was written.`)
    value = JSON.parse(valueJson)
  }
  if (value === undefined || JSON.stringify(value).length > SENT_MAX) throw new EditRefused(400, 'bad_value', 'That’s too long for one setting.')
  return { agent, key, value, expected, confirm: confirm === true }
}

const unchanged = (agent: Agent, key: string, cur: ReturnType<typeof readCurrent>, text: string | null): FormChange => ({ ok: true, agent, key, changed: false, hash: cur?.hash ?? null, undo: formUndo(agent, text, cur?.hash ?? null) })

/** What the old list's Undo needs of this change: only a key it has a rule for can be its to put back. */
function forLast(agent: Agent, key: string, found: boolean, was: unknown, value: unknown): Pick<Last, 'key' | 'before' | 'after' | 'form'> {
  if (!ruleFor(agent, key)) return { key, before: { key, state: 'unrecognised' }, after: null, form: true }
  return { key, before: shownOf(key, found, was), after: typeof value === 'string' || typeof value === 'boolean' ? value : null, form: true }
}

/**
 * PUT /v1/agents/settings/form. The same synchronous stretch as the list's route from the read to the rename, so two
 * phones can't interleave here. [models]: for Codex's model row. [testHooks]: a test's way into the writer's last moment.
 */
export async function changeFormSetting(body: unknown, models: () => Promise<ModelList>, dirs: Dirs = {}, testHooks: { beforeRename?: () => void } = {}): Promise<FormChange> {
  const { agent, key, value, expected, confirm } = stated(body)
  const list = agent === 'codex' && key === 'model' && value !== null ? await models().catch(() => null) : null
  const file = fileOf(agent, dirs)
  const { cur, text } = openForEdit(agent, file, expected)
  const next = agent === 'claude' ? claudeNext(key, value, confirm, text) : codexNext(key, value, confirm, text, list)
  if (next === null) return unchanged(agent, key, cur, text)
  const wrote = commitEdit(agent, file, cur, expected, Buffer.from(next.text, 'utf8'), forLast(agent, key, next.found, next.was, value), testHooks)
  // Its name only: what it holds may be a command or a secret.
  log(`agent settings: form ${agent} ${key}${next.ask ? ` (asked: ${next.ask})` : ''}`)
  return { ok: true, agent, key, changed: true, hash: wrote, undo: formUndo(agent, next.text, wrote) }
}

type Next = { text: string; found: boolean; was: unknown; ask: Ask | null }

function claudeNext(key: string, sent: unknown, confirm: boolean, text: string | null): Next | null {
  const doc = text === null ? {} : parseJson(text)!
  const slot = claudeSlotOf(key, doc)
  if (!slot) throw new EditRefused(403, 'key_not_editable', 'That setting isn’t one this can change.')
  const { path, entry, schema } = slot
  if (entry.managed) throw new EditRefused(403, 'managed_only', 'That setting does nothing outside a managed install, so it isn’t changed from here.')
  if (entry.screen) throw new EditRefused(403, 'own_screen', 'That setting has its own screen.')
  const { found, v: was } = at(doc, path)
  let value: unknown = null
  if (sent === null) {
    if (!found) return null
  } else {
    if (entry.refuse) throw notAllowed()
    // A masked value left as it was: nothing to do, and the mask is never written.
    if (entry.mask && sent === PLACEHOLDER) {
      if (!found) throw new EditRefused(400, 'placeholder_unknown', `${key}: a hidden value stays where it was. Type a new value.`)
      return null
    }
    value = restoredAt(path, sent, found ? was : undefined)
    if (found && isDeepStrictEqual(value, was)) return null
    const rule = ruleFor('claude', key)
    if (rule && rule.of !== 'models' && !rule.of.includes(value as string)) throw notAllowed()
  }
  const after = structuredClone(doc)
  const holder = path.slice(0, -1).reduce<Record<string, unknown>>((h, p) => (h[p] = isTable(h[p]) ? h[p] : {}) as Record<string, unknown>, after)
  if (sent === null) delete holder[path.at(-1)!]
  else holder[path.at(-1)!] = value
  // Whatever the table says, what would switch a check off for good doesn't get through, nested or not.
  if (classify(doc, after).some((c) => c.take === 'refused')) throw notAllowed()
  if (sent !== null && schema) {
    const wrong = problem(schemaOf('claude'), schema, value, key)
    if (wrong) throw new EditRefused(400, 'bad_value', wrong)
  }
  const ask = askOf(key, entry, found, was, sent === null ? null : value)
  if (ask && !confirm) throw needsConfirm(key, ask)
  return { text: jsonEditAny(text ?? '{}\n', path, sent === null ? null : value), found, was, ask }
}

function codexNext(key: string, value: unknown, confirm: boolean, text: string | null, models: ModelList): Next | null {
  if (!Object.hasOwn(schemaOf('codex').properties, key)) throw new EditRefused(403, 'key_not_editable', 'That setting isn’t one this can change.')
  const entry = CODEX_KEYS[key] ?? UNKNOWN
  if (entry.screen) throw new EditRefused(403, 'own_screen', 'That setting has its own screen.')
  const root = schemaOf('codex')
  const schema = root.properties[key] as Schema
  const shape = shapeOf(root, schema)
  const rule = ruleFor('codex', key)
  if (!rule && !['pick', 'switch', 'text', 'number'].includes(shape.control)) throw new EditRefused(403, 'read_only_here', 'That setting is a table or a list, so it’s changed on the server.')
  const { found, v: was } = text === null ? { found: false, v: undefined } : codexValue(text, key)
  if (value === null) {
    if (!found) return null
  } else {
    if (typeof value !== 'string' && typeof value !== 'boolean' && typeof value !== 'number') throw notAllowed()
    if (value === PLACEHOLDER) {
      if (!found) throw new EditRefused(400, 'placeholder_unknown', `${key}: a hidden value stays where it was. Type a new value.`)
      return null
    }
    if (found && value === was) return null
    if (rule) {
      if (rule.of === 'models') {
        if (!models) throw new EditRefused(409, 'models_unavailable', 'Codex’s models aren’t known right now, so this can’t be changed.')
        if (typeof value !== 'string' || !modelIdFits(value) || !models.some((m) => m.value === value)) throw notAllowed()
      } else if (!rule.of.includes(value as string)) throw notAllowed()
    } else {
      const wrong = problem(root, schema, value, key)
      if (wrong) throw new EditRefused(400, 'bad_value', wrong)
    }
  }
  const ask = askOf(key, entry, found, was, value)
  if (ask && !confirm) throw needsConfirm(key, ask)
  const before = text ?? ''
  return { text: value === null ? tomlRemove(before, key) : tomlEdit(before, key, value as string | boolean | number), found, was, ask }
}

/** POST /v1/agents/settings/form/undo: the form's last change, if the file is still what it wrote. One step, and judged as a change. */
export function undoFormChange(body: unknown, dirs: Dirs = {}): { ok: true; agent: Agent; key: string; hash: string | null } {
  if (!isTable(body)) throw new EditRefused(400, 'bad_request', 'Send an agent and what you expect the file to be.')
  const { agent, expected, confirm } = body
  if (agent !== 'claude' && agent !== 'codex') throw new EditRefused(400, 'bad_agent', 'That isn’t an agent with settings here.')
  if (typeof expected !== 'string') throw new EditRefused(400, 'bad_expected', 'Say which version of the file this is for.')
  const last = readLast(agent)
  if (!last?.form) throw new EditRefused(409, 'nothing_to_undo', 'There’s no change here to undo.')
  const file = fileOf(agent, dirs)
  const { cur, text } = openForEdit(agent, file, expected)
  if (!cur || text === null || cur.hash !== last.wrote) throw new EditRefused(409, 'file_changed', 'That file changed on the server since this was read. Nothing was written.')
  const judged = undoJudged(agent, text, last)
  if (!judged) throw new EditRefused(409, 'undo_refused', 'What was there before can’t be set from here. Its backup is on the server.')
  if (judged.asks && confirm !== true) throw needsConfirm(last.key, judged.asks)
  const hash = restoreLast(agent, file, cur, last)
  log(`agent settings: form ${agent} ${last.key} (undo)`)
  return { ok: true, agent, key: last.key, hash }
}
