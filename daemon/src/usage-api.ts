// The plan's usage through the Agent SDK's call for it: the first way the usage probe looks
// (usage-probe.ts), with the /usage screen as its fallback. docs/DECISIONS.md, "The SDK's usage call".
//
// The call is marked as liable to change or go, so anything unexpected is a failed look, never a
// partial reading: no method, a throw, a wrong shape, `rate_limits` null (a 429 with nothing saved).
// So is an answer with no `model_scoped`: that's how one rebuilt from saved data looks, and it
// says nothing else about its age. Its windows could be hours old.
//
// A process under the SDK in the probe's own hidden folder, with no prompt ever sent, no setting
// sources, hooks and MCP servers off and no transcript. Claude Code's login is its own business.
import { childEnv } from './runner.ts'
import { claudeSdk } from './sdk.ts'
import { findClaude } from './util.ts'
import type { ModelWeek, PlanWindow } from './usage-screen.ts'

const METHOD = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET'
/** The most one look waits, process start included. */
const LOOK_MS = 30_000
/**
 * How old an answer may be: Claude Code answers from a snapshot of up to this long ago, with no
 * time on it (measured on 2.1.316). A reading is taken as true this long before it was asked for, so a
 * status line from that minute isn't overruled by something possibly older.
 */
export const SNAPSHOT_MS = 60_000

export type ApiLook =
  | { ok: true; fiveHour: PlanWindow | null; sevenDay: PlanWindow | null; noFive: boolean; models: ModelWeek[]; at: number }
  | { ok: false; why: string }

/** The call used here: a test's stand-in has it, or doesn't. */
export type UsageQuery = { [METHOD]?: (opts: { skipBehaviors: boolean }) => Promise<unknown> }
type Runner = (cwd: string, env: Record<string, string>, work: (q: UsageQuery) => Promise<unknown>) => Promise<unknown>

/** What the process starts with. */
export const queryOptions = (cwd: string, env: Record<string, string>, claude: string, abortController: AbortController) => ({
  cwd,
  settingSources: [],
  persistSession: false,
  env: childEnv(env),
  pathToClaudeCodeExecutable: claude,
  abortController,
  extraArgs: { settings: '{"disableAllHooks":true}', 'strict-mcp-config': null },
})

const realRunner: Runner = async (cwd, env, work) => {
  // Never under `node --test`: a test that reaches this without a stand-in would read the real account.
  if (process.env.NODE_TEST_CONTEXT) throw new Error('not_in_tests')
  const claude = findClaude()
  if (!claude) throw new Error('no_claude')
  const { query } = await claudeSdk()
  let release = () => {}
  const idle = (async function* () {
    await new Promise<void>((r) => (release = r))
  })()
  const abort = new AbortController()
  const q = query({ prompt: idle as AsyncIterable<never>, options: queryOptions(cwd, env, claude, abort) as any })
  const timer = setTimeout(() => abort.abort(), LOOK_MS)
  try {
    await q.initializationResult()
    return await work(q as unknown as UsageQuery)
  } finally {
    clearTimeout(timer)
    release()
    abort.abort()
  }
}

let runner: Runner = realRunner
/** The SDK this daemon loaded has no such call: it won't grow one before a restart, so no process is started for it. */
let missing = false
/** Tests put a stand-in here. */
export const useUsageApi = (r: Runner | null) => {
  runner = r ?? realRunner
  missing = false
}

class Unexpected extends Error {}
const unexpected = (what: string): never => {
  throw new Unexpected(what)
}
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** epoch ms, null when the answer gave none. A reset that doesn't read means the format changed. */
function resetOf(v: unknown, what: string): number | null {
  if (v === null || v === undefined) return null
  const t = typeof v === 'string' ? Date.parse(v) : NaN
  return Number.isFinite(t) ? t : unexpected(`${what}.resets_at`)
}

/** A percent as the screen gives it, whole and floored, so that a reading merges the same either way (offerScreenUsage). */
function pctOf(v: unknown, what: string): number {
  return typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.floor(v) : unexpected(`${what}.utilization`)
}

/** A window: null when the plan has none or its number is unknown. */
function windowOf(v: unknown, what: string): PlanWindow | null {
  if (v === null || v === undefined) return null
  if (!isObject(v)) return unexpected(what)
  if (v.utilization === null || v.utilization === undefined) return null
  return { pct: pctOf(v.utilization, what), resetsAt: resetOf(v.resets_at, what) }
}

/** The call's answer as a reading, or why it isn't one. Reads only the documented fields. */
export function readAnswer(raw: unknown, askedAt: number): ApiLook {
  try {
    if (!isObject(raw)) return unexpected('not an object')
    if (raw.rate_limits_available === false) return { ok: false, why: 'no plan limits here' }
    const r = raw.rate_limits
    if (r === null || r === undefined) return { ok: false, why: 'rate_limits null' }
    if (!isObject(r)) return unexpected('rate_limits')
    if (r.model_scoped === undefined) return { ok: false, why: 'no model_scoped: possibly old' }
    if (!Array.isArray(r.model_scoped)) return unexpected('model_scoped')
    const fiveHour = windowOf(r.five_hour, 'five_hour')
    const sevenDay = windowOf(r.seven_day, 'seven_day')
    if (!fiveHour && !sevenDay) return { ok: false, why: 'no windows' }
    // Named as the screen names them: "Sonnet" from its "(Sonnet only)", then each model's own.
    const models: ModelWeek[] = []
    const sonnet = windowOf(r.seven_day_sonnet, 'seven_day_sonnet')
    if (sonnet) models.push({ label: 'Sonnet', pct: sonnet.pct, resetsAt: sonnet.resetsAt })
    for (const m of r.model_scoped) {
      if (!isObject(m) || typeof m.display_name !== 'string') return unexpected('model_scoped row')
      const label = m.display_name.trim()
      if (!label || label.length > 64) return unexpected('model_scoped name')
      // A row with no number can't replace what's known, and the weeks are replaced whole.
      if (m.utilization === null || m.utilization === undefined) return { ok: false, why: 'a model row has no number' }
      if (!models.some((o) => o.label.toLowerCase() === label.toLowerCase())) models.push({ label, pct: pctOf(m.utilization, label), resetsAt: resetOf(m.resets_at, label) })
    }
    // Said outright, as the screen's missing row does: the plan has no 5-hour window. A key that's just absent says nothing.
    return { ok: true, fiveHour, sevenDay, noFive: r.five_hour === null, models, at: askedAt - SNAPSHOT_MS }
  } catch (e) {
    if (e instanceof Unexpected) return { ok: false, why: `wrong shape: ${e.message}` }
    throw e
  }
}

/** One look through the SDK, in [cwd]. Never throws, and always answers within [ms]. */
export async function readUsageApi(cwd: string, env: Record<string, string>, ms = LOOK_MS + 5_000): Promise<ApiLook> {
  if (missing) return { ok: false, why: 'no such method' }
  const askedAt = Date.now()
  let timer: NodeJS.Timeout | undefined
  try {
    const raw = await Promise.race([
      runner(cwd, env, async (q) => {
        const call = q[METHOD]
        if (typeof call !== 'function') {
          missing = true
          throw new Error('no such method')
        }
        return call.call(q, { skipBehaviors: true })
      }),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('timed out')), ms)
      }),
    ])
    return readAnswer(raw, askedAt)
  } catch (e: any) {
    return { ok: false, why: String(e?.message ?? e).split('\n')[0]!.slice(0, 120) }
  } finally {
    clearTimeout(timer)
  }
}
