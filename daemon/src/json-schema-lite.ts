// What a settings value must look like, by the schemas shipped in src/schemas (docs/DECISIONS.md, "Every setting, from
// the agents' own schemas"). Draft-07's keywords those two files use, and nothing else: test/agent-settings-form.test.ts fails
// when a refreshed schema brings a keyword that isn't [UNDERSTOOD] or [IGNORED], so it can't quietly check less.
//
// Not a dependency: this is a small walk, the daemon runs with no build step, and it never sees a schema from outside the repo.
// What it says never quotes a value, only the place and what's expected.

export type Schema = Record<string, any>

export const UNDERSTOOD = new Set(['$ref', 'type', 'enum', 'const', 'minimum', 'maximum', 'exclusiveMinimum', 'minLength', 'maxLength', 'pattern', 'minItems', 'maxItems', 'uniqueItems', 'items', 'properties', 'required', 'additionalProperties', 'propertyNames', 'anyOf', 'oneOf', 'allOf', 'not'])
/** What decides nothing about a value here. `format` is only a hint in draft-07 and the agents don't hold to it. */
export const IGNORED = new Set(['$schema', '$id', '$defs', 'definitions', 'title', 'description', 'default', 'examples', 'format', 'allowTrailingCommas'])

const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

function typeFits(type: string, v: unknown): boolean {
  switch (type) {
    case 'string': return typeof v === 'string'
    case 'boolean': return typeof v === 'boolean'
    case 'number': return typeof v === 'number' && Number.isFinite(v)
    case 'integer': return typeof v === 'number' && Number.isInteger(v)
    case 'array': return Array.isArray(v)
    case 'object': return isObject(v)
    case 'null': return v === null
    default: return false
  }
}

/** The schema a `$ref` of this file's points at; undefined when there's none. */
export function resolveRef(root: Schema, ref: string): Schema | undefined {
  if (!ref.startsWith('#/')) return undefined
  let at: any = root
  for (const part of ref.slice(2).split('/')) at = isObject(at) ? at[part.replaceAll('~1', '/').replaceAll('~0', '~')] : undefined
  return isObject(at) ? at : undefined
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const wordsOf = (type: unknown) => (Array.isArray(type) ? type.join(' or ') : String(type))

/**
 * The first thing wrong with [v] at [where], in words, or null when it fits [schema]. [root] holds the `$ref`s. A pattern
 * that doesn't compile here counts as fitting: the agent's own check still stands behind this one.
 */
export function problem(root: Schema, schema: Schema, v: unknown, where = ''): string | null {
  const at = where || 'This'
  if (schema.$ref) {
    const target = resolveRef(root, schema.$ref)
    const wrong = target ? problem(root, target, v, where) : null
    if (wrong) return wrong
  }
  if (schema.type !== undefined) {
    const types: string[] = Array.isArray(schema.type) ? schema.type : [schema.type]
    if (!types.some((t) => typeFits(t, v))) return `${at}: should be ${wordsOf(schema.type)}.`
  }
  if (schema.enum && !schema.enum.some((e: unknown) => same(e, v))) return `${at}: isn’t one of the values this takes.`
  if ('const' in schema && !same(schema.const, v)) return `${at}: isn’t the value this takes.`
  if (typeof v === 'number') {
    if (schema.minimum !== undefined && v < schema.minimum) return `${at}: the least it takes is ${schema.minimum}.`
    if (schema.maximum !== undefined && v > schema.maximum) return `${at}: the most it takes is ${schema.maximum}.`
    if (schema.exclusiveMinimum !== undefined && v <= schema.exclusiveMinimum) return `${at}: should be more than ${schema.exclusiveMinimum}.`
  }
  if (typeof v === 'string') {
    if (schema.minLength !== undefined && v.length < schema.minLength) return `${at}: can’t be this short.`
    if (schema.maxLength !== undefined && v.length > schema.maxLength) return `${at}: can’t be this long.`
    if (schema.pattern !== undefined) {
      let re: RegExp | null = null
      try {
        re = new RegExp(schema.pattern, 'u')
      } catch {}
      if (re && !re.test(v)) return `${at}: isn’t in the form this takes.`
    }
  }
  if (Array.isArray(v)) {
    if (schema.minItems !== undefined && v.length < schema.minItems) return `${at}: needs at least ${schema.minItems}.`
    if (schema.maxItems !== undefined && v.length > schema.maxItems) return `${at}: takes at most ${schema.maxItems}.`
    if (schema.uniqueItems && new Set(v.map((x) => JSON.stringify(x))).size !== v.length) return `${at}: has the same one twice.`
    if (isObject(schema.items)) {
      for (const [i, x] of v.entries()) {
        const wrong = problem(root, schema.items, x, `${where}[${i}]`)
        if (wrong) return wrong
      }
    }
  }
  if (isObject(v)) {
    for (const name of schema.required ?? []) if (!Object.hasOwn(v, name)) return `${where ? `${where}.` : ''}${name}: is needed.`
    const props: Record<string, Schema> = schema.properties ?? {}
    for (const [name, x] of Object.entries(v)) {
      const inner = where ? `${where}.${name}` : name
      if (schema.propertyNames) {
        const wrong = problem(root, schema.propertyNames, name, inner)
        if (wrong) return wrong
      }
      if (Object.hasOwn(props, name)) {
        const wrong = problem(root, props[name]!, x, inner)
        if (wrong) return wrong
      } else if (schema.additionalProperties === false) return `${inner}: isn’t a setting this takes.`
      else if (isObject(schema.additionalProperties)) {
        const wrong = problem(root, schema.additionalProperties, x, inner)
        if (wrong) return wrong
      }
    }
  }
  for (const part of schema.allOf ?? []) {
    const wrong = problem(root, part, v, where)
    if (wrong) return wrong
  }
  if (schema.anyOf && !schema.anyOf.some((s: Schema) => problem(root, s, v, where) === null)) return `${at}: isn’t in a form this takes.`
  if (schema.oneOf && schema.oneOf.filter((s: Schema) => problem(root, s, v, where) === null).length !== 1) return `${at}: isn’t in a form this takes.`
  if (schema.not && problem(root, schema.not, v, where) === null) return `${at}: isn’t in a form this takes.`
  return null
}
