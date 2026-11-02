// A tool, a prompt and a resource as the phone sees them: named fields, capped and masked. All of it is
// a third party's text, shown as text.

import { maskText } from './mcp-view.ts'
import type { RawFacts } from './mcp-client.ts'

export type ToolParam = { name: string; type: string; required: boolean; description?: string; default?: string; choices?: string[] }
export type ToolAnnotations = { title?: string; readOnly?: boolean; destructive?: boolean; idempotent?: boolean; openWorld?: boolean }
export type ToolDetail = { name: string; description?: string; annotations: ToolAnnotations; params: ToolParam[]; schema?: string }
export type NamedThing = { name: string; description?: string; uri?: string; args?: string[] }
export type ServerFacts = { serverName?: string; version?: string; instructions?: string; tools: ToolDetail[]; prompts: NamedThing[]; resources: NamedThing[]; moreTools: number }

const TOOLS_MAX = 500
const THINGS_MAX = 200
const PARAMS_MAX = 64
const TEXT_MAX = 4000
const SHORT_MAX = 200
const SCHEMA_MAX = 16 * 1024

/** Someone else's text, on one screen: masked, without control characters, and capped. */
function text(v: unknown, max: number = TEXT_MAX): string | undefined {
  if (typeof v !== 'string') return undefined
  // eslint-disable-next-line no-control-regex
  const t = maskText(v.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '')).trim()
  if (!t) return undefined
  return t.length > max ? `${t.slice(0, max - 1)}…` : t
}

const table = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {})

/** A parameter's type in a few words: a JSON schema can say it several ways. */
function typeWords(s: Record<string, unknown>): string {
  const t = s.type
  if (typeof t === 'string') {
    if (t === 'array') {
      const of = typeWords(table(s.items))
      return of === 'any' ? 'array' : `array of ${of}`
    }
    return t
  }
  if (Array.isArray(t)) return t.filter((x) => typeof x === 'string').join(' or ') || 'any'
  for (const k of ['anyOf', 'oneOf']) {
    const opts = s[k]
    if (Array.isArray(opts)) return [...new Set(opts.map((o) => typeWords(table(o))))].join(' or ') || 'any'
  }
  return Array.isArray(s.enum) ? 'one of' : 'any'
}

function paramsOf(schema: Record<string, unknown>): ToolParam[] {
  const props = table(schema.properties)
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((r) => typeof r === 'string') : [])
  return Object.entries(props)
    .slice(0, PARAMS_MAX)
    .flatMap(([name, raw]) => {
      const n = text(name, SHORT_MAX)
      if (!n) return []
      const s = table(raw)
      const description = text(s.description)
      const def = s.default === undefined ? undefined : text(JSON.stringify(s.default), SHORT_MAX)
      const choices = Array.isArray(s.enum) ? s.enum.slice(0, 40).flatMap((e) => text(typeof e === 'string' ? e : JSON.stringify(e), SHORT_MAX) ?? []) : undefined
      return [{ name: n, type: text(typeWords(s), SHORT_MAX) ?? 'any', required: required.has(name), ...(description ? { description } : {}), ...(def ? { default: def } : {}), ...(choices?.length ? { choices } : {}) }]
    })
}

function toolOf(raw: unknown): ToolDetail | null {
  const t = table(raw)
  const name = text(t.name, SHORT_MAX)
  if (!name) return null
  const schema = table(t.inputSchema)
  const a = table(t.annotations)
  const flag = (k: string) => (typeof a[k] === 'boolean' ? (a[k] as boolean) : undefined)
  const annotations: ToolAnnotations = {}
  const title = text(a.title ?? t.title, SHORT_MAX)
  if (title) annotations.title = title
  for (const [from, to] of [['readOnlyHint', 'readOnly'], ['destructiveHint', 'destructive'], ['idempotentHint', 'idempotent'], ['openWorldHint', 'openWorld']] as const) {
    const v = flag(from)
    if (v !== undefined) annotations[to] = v
  }
  const description = text(t.description)
  let pretty: string | undefined
  try {
    pretty = text(JSON.stringify(schema, null, 2), SCHEMA_MAX)
  } catch {}
  return { name, ...(description ? { description } : {}), annotations, params: paramsOf(schema), ...(pretty && pretty !== '{}' ? { schema: pretty } : {}) }
}

function thingOf(raw: unknown): NamedThing | null {
  const t = table(raw)
  const name = text(t.name, SHORT_MAX)
  if (!name) return null
  const description = text(t.description)
  const uri = text(t.uri, SHORT_MAX)
  const args = Array.isArray(t.arguments) ? t.arguments.flatMap((a) => text(table(a).name, SHORT_MAX) ?? []) : undefined
  return { name, ...(description ? { description } : {}), ...(uri ? { uri } : {}), ...(args?.length ? { args } : {}) }
}

export function factsOf(raw: RawFacts): ServerFacts {
  const tools = raw.tools.slice(0, TOOLS_MAX).flatMap((t) => toolOf(t) ?? [])
  const serverName = text(raw.serverInfo?.name, SHORT_MAX)
  const version = text(raw.serverInfo?.version, SHORT_MAX)
  const instructions = text(raw.instructions)
  return {
    ...(serverName ? { serverName } : {}),
    ...(version ? { version } : {}),
    ...(instructions ? { instructions } : {}),
    tools,
    prompts: raw.prompts.slice(0, THINGS_MAX).flatMap((p) => thingOf(p) ?? []),
    resources: raw.resources.slice(0, THINGS_MAX).flatMap((r) => thingOf(r) ?? []),
    moreTools: Math.max(0, raw.tools.length - TOOLS_MAX),
  }
}
