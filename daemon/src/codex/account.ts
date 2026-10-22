// Codex's account, its usage windows and its models, as the phone and the Mac show them
// (docs/PROTOCOL.md, "Codex"). The email never leaves the daemon.
import { broadcast, type Window } from '../hub.ts'
import { log } from '../util.ts'
import { CODEX_MIN } from './binary.ts'
import { codexBinary, codexConnected, codexEnabled, codexProblem, onCodexChange, onCodexConnected, onCodexNotification, rpc } from './client.ts'
import { tokensOf } from './events.ts'

/** `type`: how Codex is signed in ("chatgpt", "apiKey"), null when it isn't. */
export type CodexAccount = { signedIn: boolean; type: string | null; planType: string | null }
/** Codex's own limits: its 5-hour and weekly windows (either can be missing), and whether one is used up. */
export type CodexUsage = { fiveHour: Window | null; sevenDay: Window | null; limited: boolean; observedAt: number | null }
export type CodexState = {
  /** Installed, at least [min], so it can be used here. */
  available: boolean
  enabled: boolean
  connected: boolean
  version: string | null
  min: string
  path: string | null
  /** What's wrong, in words, when it can't be used now. */
  problem: string | null
  account: CodexAccount | null
  usage: CodexUsage | null
}

let account: CodexAccount | null = null
let usage: CodexUsage | null = null

const FIVE_HOURS = 5 * 3600_000
const WEEK = 7 * 24 * 3600_000
/** A window whose reset passed starts over empty, as Claude's do (hub.ts, getUsage). */
const roll = (w: Window | null, period: number, now: number): Window | null => {
  if (!w) return null
  if (w.resetsAt && now > w.resetsAt) return { pct: 0, resetsAt: w.resetsAt + Math.ceil((now - w.resetsAt) / period) * period }
  return w
}

/** What the snapshot says about Codex; null when it isn't installed (nothing changes for a Claude-only Mac). */
export function codexState(now = Date.now()): CodexState | null {
  const b = codexBinary()
  if (!b.path) return null
  const problem = !b.ok ? `Hypernova needs Codex ${CODEX_MIN} or later (this one is ${b.version ?? 'unknown'}).` : codexEnabled() ? codexProblem() : null
  const u = usage && { ...usage, fiveHour: roll(usage.fiveHour, FIVE_HOURS, now), sevenDay: roll(usage.sevenDay, WEEK, now) }
  return { available: b.ok, enabled: codexEnabled(), connected: codexConnected(), version: b.version, min: CODEX_MIN, path: b.path, problem, account: codexEnabled() ? account : null, usage: codexEnabled() ? u : null }
}

/** What clients last heard, so they hear again only when it changes. */
let sent = ''
export function sendCodex(): void {
  const s = codexState()
  const k = JSON.stringify(s)
  if (k === sent) return
  sent = k
  broadcast({ t: 'codex', codex: s })
}
/** When the account was last read; a sign-in or sign-out done in a terminal shows by the next read. */
let readAt = 0
/** Every few seconds (main.ts): a window that reset is announced then, and the account is read again now and then (every 30 s while signed out, else every 10 minutes). */
export function tickCodex(now = Date.now()): void {
  sendCodex()
  if (!codexEnabled() || !codexConnected()) return
  if (now - readAt > (account?.signedIn === false ? 30_000 : 600_000)) void refreshCodexAccount()
}

type RateWindow = { usedPercent?: number; windowDurationMins?: number | null; resetsAt?: number | null } | null | undefined
/** Codex's snapshot as windows: by their length, 5 hours or a week, whichever slot each is in. */
export function usageOf(snap: any, now = Date.now()): CodexUsage {
  const out: CodexUsage = { fiveHour: null, sevenDay: null, limited: !!snap?.rateLimitReachedType, observedAt: now }
  for (const w of [snap?.primary, snap?.secondary] as RateWindow[]) {
    if (!w || typeof w.usedPercent !== 'number') continue
    const win = { pct: Math.max(0, Math.min(100, w.usedPercent)), resetsAt: typeof w.resetsAt === 'number' ? w.resetsAt * 1000 : 0 }
    if (w.windowDurationMins === 300) out.fiveHour = win
    else if (w.windowDurationMins === 10080) out.sevenDay = win
    // A window of another length: the shorter one takes the 5-hour slot, the longer the week's.
    else if ((w.windowDurationMins ?? 0) < 1440) out.fiveHour ??= win
    else out.sevenDay ??= win
  }
  return out
}

/** Read the account and its limits now (on connect, after each turn, on Refresh); one read at a time. */
let reading: Promise<CodexState | null> | null = null
export function refreshCodexAccount(): Promise<CodexState | null> {
  reading ??= readAccount().finally(() => {
    reading = null
  })
  return reading
}
async function readAccount(): Promise<CodexState | null> {
  readAt = Date.now()
  try {
    const c = await rpc()
    const a = await c.call('account/read', {})
    const acc = a?.account ?? null
    account = { signedIn: !!acc, type: typeof acc?.type === 'string' ? acc.type : null, planType: typeof acc?.planType === 'string' ? acc.planType : null }
    if (acc) {
      const r = await c.call('account/rateLimits/read', {}).catch(() => null)
      if (r?.rateLimits) usage = usageOf(r.rateLimits)
    }
  } catch (e) {
    log('codex account read failed', (e as Error).message)
  }
  sendCodex()
  return codexState()
}

/**
 * A Codex session's usage strip (GET …/usage), in the shape Claude's has (session-usage.ts): its
 * context and tokens as Codex counts them, Codex's own windows, and no price (it's the account's).
 */
export function codexSessionUsage(rec: { id: string; model: string | null }) {
  const t = tokensOf(rec.id)
  const total = t?.total ?? {}
  const cached = total.cachedInputTokens ?? 0
  const u = codexState()?.usage ?? null
  return {
    model: rec.model,
    modelName: models?.list.find((m) => m.value === rec.model)?.name ?? null,
    context: t?.used != null && t.window ? { used: t.used, window: t.window, pct: Math.min(100, Math.round((t.used / t.window) * 100)), exact: true } : null,
    tokens: { input: Math.max(0, (total.inputTokens ?? 0) - cached), output: total.outputTokens ?? 0, cacheRead: cached, cacheWrite: total.cacheWriteInputTokens ?? 0 },
    agentTokens: 0,
    costUsd: null,
    costExact: false,
    fiveHour: u?.fiveHour ?? null,
    sevenDay: u?.sevenDay ?? null,
    usageSource: u ? 'official' : 'none',
    updatedAt: t?.at ?? 0,
  }
}

export type CodexModel = { value: string; name: string; description: string | null; efforts: string[]; defaultEffort: string | null; isDefault: boolean }
let models: { at: number; list: CodexModel[] } | null = null
/** Codex's models for the pickers, read at most once an hour. */
export async function codexModels(fresh = false): Promise<CodexModel[]> {
  if (!fresh && models && Date.now() - models.at < 3600_000) return models.list
  const c = await rpc()
  const r = await c.call('model/list', {})
  // The owner's own default (~/.codex/config.toml) is what a thread starts on without a model, not
  // the list's: the pickers show that one as the default, with its effort.
  const cfg = await c.call('config/read', {}).then((x) => x?.config ?? null).catch(() => null)
  const own = typeof cfg?.model === 'string' ? cfg.model : null
  const ownEffort = typeof cfg?.model_reasoning_effort === 'string' ? cfg.model_reasoning_effort : null
  const visible = (r?.data ?? []).filter((m: any) => !m.hidden && typeof m.model === 'string')
  const mine = !!own && visible.some((m: any) => m.model === own)
  const list: CodexModel[] = visible.map((m: any) => {
    const isDefault = mine ? m.model === own : m.isDefault === true
    const efforts = (m.supportedReasoningEfforts ?? []).map((e: any) => e?.reasoningEffort).filter((e: unknown): e is string => typeof e === 'string')
    return {
      value: m.model,
      name: String(m.displayName ?? m.model),
      description: typeof m.description === 'string' ? m.description : null,
      efforts,
      defaultEffort: isDefault && mine && ownEffort && efforts.includes(ownEffort) ? ownEffort : typeof m.defaultReasoningEffort === 'string' ? m.defaultReasoningEffort : null,
      isDefault,
    }
  })
  models = { at: Date.now(), list }
  return list
}

onCodexConnected(() => void refreshCodexAccount())
onCodexChange(() => sendCodex())
onCodexNotification((m) => {
  if (m.method === 'account/rateLimits/updated' && m.params?.rateLimits) {
    usage = usageOf(m.params.rateLimits)
    sendCodex()
  } else if (m.method === 'account/updated') void refreshCodexAccount()
})
