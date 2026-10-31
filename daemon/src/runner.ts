// Sessions Hypernova drives itself, through the Agent SDK: one claude process per prompt,
// resumed by session id, so they survive daemon restarts and stay normal Claude Code sessions.
// The SDK is loaded when a prompt first runs (sdk.ts): without it a prompt isn't sent, and says why.
import type { Query } from '@anthropic-ai/claude-agent-sdk'
import { randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { findClaude, log, trimText } from './util.ts'
import { REMOVED_TEXT, getManaged, getProvider, providerEnv, saveManaged, useProvider, type ManagedSession } from './store.ts'
import {
  AWAY_MESSAGE,
  blockSession,
  ensureParsed,
  getSession,
  notice,
  offerUsage,
  putEvent,
  requestApproval,
  sessionHealthy,
  setBlocked,
  setState,
  startTool,
  upsertSession,
  emitEvent,
  seedFrom,
  sessionRule,
  type AwayRule,
} from './hub.ts'
import { applyToolResult, feedAssistantBlock, isLauncher, promptTitle, queueView, resultLinks, toolSummary, typedCommand, unsentPrompts, userEvent, type QueueEntry } from './normalize.ts'
import { classifyApiError, cleanText, type Blocker } from './blockers.ts'
import { bypassBlockedByRoot } from './platform.ts'
import { agentLabel, applyLinks, sdkTask } from './agents.ts'
import { attachmentReadInput, readsAttachment } from './attachments.ts'
import { isBuiltinCommand } from './commands.ts'
import { noteDenied } from './denied.ts'
import { noteHookEvent } from './hookevents.ts'
import type { AutomationStart } from './terms.ts'
import { SdkMissingError, claudeSdk, type ClaudeSdk } from './sdk.ts'
import { alwaysUpdates, suggestedRules, suggestedScope } from './always.ts'

/**
 * One session's runs: the query running now, and the prompts waiting behind it ([queue], shaped
 * like Claude Code's own queue entries). [stopSaid]: a Stop already said what it dropped.
 */
type Run = { q: Query | null; abort: AbortController | null; queue: QueueEntry[]; running: boolean; interrupted: boolean; stopSaid: boolean }
const runs = new Map<string, Run>()
const runFor = (id: string) => {
  let r = runs.get(id)
  if (!r) runs.set(id, (r = { q: null, abort: null, queue: [], running: false, interrupted: false, stopSaid: false }))
  return r
}

/** The session shows the prompts waiting behind the one running. */
function publishQueue(id: string, run: Run): void {
  const queue = queueView(run.queue)
  upsertSession(id, { queue, queued: queue.length })
}

// Variables a parent Claude Code process sets; a child must not inherit them.
const SCRUB = ['CLAUDECODE', 'CLAUDE_CODE_CHILD_SESSION', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_CODE_MESSAGING_SOCKET', 'CLAUDE_CODE_MESSAGING_TOKEN', 'CLAUDE_PID', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_SESSION_ATTENDED', 'CLAUDE_EFFORT', 'CLAUDE_CODE_EXECPATH', 'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_MODEL']
export function childEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !SCRUB.includes(k)) env[k] = v
  return { ...env, ...extra, HYPERNOVA_MANAGED: '1' }
}

/**
 * A new session the daemon runs itself. An automation's run (automations.ts) names it (`title`)
 * and adds its badge, allow rules and appended system prompt, as a shared terminal would take them.
 */
export function createSession(opts: { cwd: string; providerId?: string; model?: string | null; permissionMode?: string; effort?: string | null; prompt?: string; forkFrom?: string; title?: string; context?: string } & Omit<AutomationStart, 'settingsFile'>): string {
  // A provider deleted since the phone listed it: 404, never the subscription instead.
  const provider = useProvider(opts.providerId)
  const id = randomUUID()
  const m: ManagedSession = {
    id,
    cwd: opts.cwd,
    providerId: provider.id,
    model: opts.model || null,
    permissionMode: opts.permissionMode ?? 'default',
    createdAt: Date.now(),
    title: trimText(opts.title ?? promptTitle(opts.prompt ?? ''), 80) ?? 'New session',
    ...(opts.automation ? { automation: opts.automation, allowedTools: opts.allowedTools ?? [], appendSystemPrompt: opts.appendSystemPrompt } : {}),
    ...(opts.context ? { context: opts.context } : {}),
    ...(opts.effort ? { effort: opts.effort } : {}),
  }
  saveManaged(m)
  upsertSession(id, { cwd: m.cwd, origin: 'hypernova', state: 'starting', title: m.title ?? 'New session', providerId: m.providerId, model: m.model, effort: m.effort ?? null, permissionMode: m.permissionMode, createdAt: m.createdAt, lastActivityAt: Date.now(), automation: m.automation ?? null })
  ensureParsed(getSession(id)!)
  if (opts.forkFrom) seedFrom(id, opts.forkFrom)
  // A switch's session waits for its first prompt, with the context it carried over.
  if (opts.prompt?.trim()) sendPrompt(id, opts.prompt, opts.forkFrom)
  else setState(id, 'idle')
  return id
}

/**
 * Queue a prompt; it runs now if the session is idle. Returns whether it waits behind the one
 * running: one claude process runs one prompt, so it waits for the whole reply. [clientId]: the
 * phone send it came from.
 */
export function sendPrompt(id: string, text: string, forkFrom?: string, clientId?: string): boolean {
  const run = runFor(id)
  run.queue.push({ id: `q:${randomUUID()}`, content: text, at: Date.now(), clientId: clientId ?? null })
  upsertSession(id, { stoppedForAway: false })
  if (!run.running) {
    // A fork waiting for its first prompt (prepareFork) starts from its parent's conversation.
    void drain(id, forkFrom ?? getManaged(id)?.forkFrom)
    return false
  }
  publishQueue(id, run)
  return true
}

export async function interrupt(id: string): Promise<void> {
  const run = runs.get(id)
  if (!run?.running) return
  run.interrupted = true
  // Stop means stop here: prompts waiting behind this one are dropped, not run next (Claude
  // Code's own terminal keeps them). Said at once, naming them, so the phone that sent them
  // takes their text back rather than waiting for them (docs/DECISIONS.md).
  const dropped = run.queue.splice(0)
  if (dropped.length) {
    publishQueue(id, run)
    run.stopSaid = true
    const n = dropped.length
    putEvent(id, {
      id: `n:${randomUUID()}`,
      kind: 'notice',
      ts: Date.now(),
      text: `Stopped. ${n} queued message${n === 1 ? ' wasn’t' : 's weren’t'} sent.`,
      tone: 'info',
      unsent: { reason: 'dropped', prompts: unsentPrompts(dropped) },
    })
  }
  try {
    await run.q?.interrupt()
  } catch {
    run.abort?.abort()
  }
}

/**
 * Change a session's permission mode. Returns "next_turn" whenever a turn runs: a query started
 * with a prompt string (not streaming input) can't change its mode, so the running turn keeps the
 * old one and the next query starts in the new one. Asked all the same, in case it can.
 */
export async function setMode(id: string, mode: string): Promise<'now' | 'next_turn'> {
  const m = getManaged(id)
  if (!m) return 'now'
  m.permissionMode = mode
  saveManaged(m)
  upsertSession(id, { permissionMode: mode })
  const run = runs.get(id)
  if (!run?.running || !run.q) return 'now'
  if (mode !== 'bypassPermissions') {
    try {
      await run.q.setPermissionMode(mode as never)
    } catch {}
  }
  return 'next_turn'
}

/**
 * Change a session's reasoning effort: each turn is its own query, so it goes with the next one
 * ("next_turn" while one runs).
 */
export function setEffort(id: string, effort: string): 'now' | 'next_turn' {
  const m = getManaged(id)
  if (m && m.effort !== effort) saveManaged({ ...m, effort })
  upsertSession(id, { effort })
  return runs.get(id)?.running ? 'next_turn' : 'now'
}

async function drain(id: string, forkFrom?: string): Promise<void> {
  const run = runFor(id)
  while (run.queue.length) {
    const next = run.queue.shift()!
    publishQueue(id, run)
    run.running = true
    try {
      await runOne(id, next.content, forkFrom, next.clientId)
    } catch (e) {
      log('run failed', id, e)
      notice(id, `Claude Code stopped with an error: ${(e as Error).message ?? e}`, 'error')
      setState(id, 'error')
    }
    // Forked once its transcript is there: later prompts resume the fork itself. (One that failed
    // before Claude Code wrote anything forks with the next prompt.)
    if (forkFrom && existsSync(getSession(id)?.transcriptPath ?? '')) {
      const m = getManaged(id)
      if (m?.forkFrom) {
        delete m.forkFrom
        saveManaged(m)
      }
      forkFrom = undefined
    }
    run.running = false
  }
  publishQueue(id, run)
}

/**
 * A session that will fork [parentId] onto [providerId] with its first prompt (the phone's
 * "Continue on <provider>" for a conversation open in a terminal, on a Mac that can't run shared
 * terminals): it shows the parent's history until then. Returns its id.
 */
export function prepareFork(parentId: string, cwd: string, providerId: string, title?: string): string {
  const id = randomUUID()
  const m: ManagedSession = { id, cwd, providerId, model: null, permissionMode: 'default', createdAt: Date.now(), title: title ?? 'New session', forkFrom: parentId }
  saveManaged(m)
  upsertSession(id, { cwd, origin: 'hypernova', state: 'idle', title: m.title ?? 'New session', providerId, model: null, permissionMode: m.permissionMode, createdAt: m.createdAt, lastActivityAt: Date.now() })
  ensureParsed(getSession(id)!)
  seedFrom(id, parentId)
  return id
}

async function runOne(id: string, text: string, forkFrom?: string, clientId?: string | null): Promise<void> {
  const m = getManaged(id)
  const rec = getSession(id)
  if (!m || !rec) throw new Error('unknown session')
  const st = ensureParsed(rec)
  const claude = findClaude()
  if (!claude) throw new Error('Claude Code is not installed on this Mac')
  const provider = getProvider(m.providerId)
  const exists = existsSync(rec.transcriptPath)
  const started = Date.now()
  // A "/" or "!" command shows as one, as in a terminal session's transcript. With files the
  // @"path" mentions come first, so Claude Code reads it as a prompt.
  const sent = userEvent(`u:${randomUUID()}`, started, text)
  const command = sent.kind === 'user' && !sent.files?.length && typedCommand(sent.text, isBuiltinCommand)
  if (sent.kind === 'user' && command) sent.command = command
  if (sent.kind === 'user' && clientId) sent.clientId = clientId
  putEvent(id, sent)
  if (provider.removed) {
    // Its provider was deleted: the prompt isn't sent anywhere (least of all on the owner's
    // Claude login); the session says why and waits to be moved to another provider.
    putEvent(id, { id: `e:${randomUUID()}`, kind: 'notice', ts: started, text: `Not sent. ${REMOVED_TEXT}`, tone: 'error', blocker: 'provider' })
    setBlocked(id, { kind: 'provider', text: REMOVED_TEXT, resetsAt: null, autoResumeAt: null }, started)
    setState(id, 'idle', null)
    return
  }
  // Claude Code's SDK isn't on this Mac (Setup fetches it): the prompt isn't sent, and the notice
  // says how to get it, as for a removed provider.
  let query: ClaudeSdk['query']
  try {
    query = (await claudeSdk()).query
  } catch (e) {
    if (!(e instanceof SdkMissingError)) throw e
    log('prompt not sent:', id.slice(0, 8), 'has no SDK to run on', e.cause ?? '')
    putEvent(id, { id: `e:${randomUUID()}`, kind: 'notice', ts: started, text: `Not sent. ${e.message}`, tone: 'error' })
    setState(id, 'idle', null)
    return
  }
  setState(id, 'working', 'Thinking')

  const abort = new AbortController()
  const run = runFor(id)
  run.abort = abort
  run.interrupted = false
  run.stopSaid = false
  const sessionOpts = forkFrom ? { resume: forkFrom, forkSession: true, sessionId: id } : exists ? { resume: id } : { sessionId: id }
  const q = query({
    prompt: text,
    options: {
      cwd: m.cwd,
      ...sessionOpts,
      model: m.model ?? undefined,
      // The effort chosen on the phone, with every query (each turn is one): none, Claude Code's default.
      ...(m.effort ? { effort: m.effort as never } : {}),
      permissionMode: m.permissionMode as never,
      // An automation's run: its allow rules, what it tells Claude, and its name on the first query.
      ...(m.automation
        ? { allowedTools: m.allowedTools ?? [], ...(m.appendSystemPrompt ? { systemPrompt: { type: 'preset' as const, preset: 'claude_code' as const, append: m.appendSystemPrompt } } : {}), ...(exists ? {} : { title: m.title }) }
        : {}),
      // What a switch from another agent carried over, with every query (switch.ts).
      ...(m.context ? { systemPrompt: { type: 'preset' as const, preset: 'claude_code' as const, append: m.context } } : {}),
      // Always allowed (it is only the permission to switch): the phone can move a session to
      // Bypass later, from its next turn, without a restart. It starts in the mode chosen.
      allowDangerouslySkipPermissions: !bypassBlockedByRoot(),
      settingSources: ['user', 'project', 'local'],
      includePartialMessages: true,
      env: childEnv(providerEnv(provider)),
      pathToClaudeCodeExecutable: claude,
      abortController: abort,
      stderr: (d: string) => log('[claude]', id.slice(0, 8), d.trim().slice(0, 500)),
      canUseTool: async (toolName, input, opts) => {
        if (readsAttachment(toolName, input)) return { behavior: 'allow', updatedInput: attachmentReadInput(id, input) ?? input }
        const kind = toolName === 'AskUserQuestion' ? 'question' : toolName === 'ExitPlanMode' ? 'plan' : 'tool'
        // A subagent asks: the phone says which.
        const agentId = typeof opts.agentID === 'string' && opts.agentID ? opts.agentID : null
        const ans = await requestApproval(
          {
            sessionId: id,
            kind,
            toolName,
            summary: kind === 'tool' ? `${toolName}: ${toolSummary(toolName, input)}` : toolSummary(toolName, input),
            input: input as Record<string, unknown>,
            reason: (opts as { decisionReason?: string; title?: string }).title ?? (opts as { decisionReason?: string }).decisionReason ?? null,
            canAlways: !!opts.suggestions?.length,
            alwaysRules: suggestedRules(opts.suggestions),
            alwaysScope: suggestedScope(opts.suggestions),
            questions: kind === 'question' ? ((input as { questions?: unknown[] }).questions ?? null) : null,
            plan: kind === 'plan' ? String((input as { plan?: string }).plan ?? '') : null,
            agentId,
            agentLabel: agentId ? agentLabel(id, agentId) : null,
          },
          (opts as { toolUseID?: string }).toolUseID ?? null,
          opts.signal,
          sdkAwayRule(m),
        )
        if (ans.decision === 'timeout') return { behavior: 'deny', message: AWAY_MESSAGE }
        if (ans.decision === 'terminal') return { behavior: 'deny', message: 'Cancelled.' }
        if (kind === 'question') {
          if (ans.decision !== 'deny' && ans.answers) return { behavior: 'allow', updatedInput: { ...input, answers: ans.answers } }
          return { behavior: 'deny', message: ans.message || 'The user chose not to answer.' }
        }
        if (kind === 'plan') {
          if (ans.decision !== 'deny') {
            void setMode(id, 'default')
            return { behavior: 'allow', updatedInput: input }
          }
          return { behavior: 'deny', message: ans.message ? `The user wants to keep planning: ${ans.message}` : 'The user wants to keep planning. Ask what to change.' }
        }
        if (ans.decision === 'deny') return { behavior: 'deny', message: ans.message || 'The user denied this from Hypernova.' }
        if (ans.decision === 'always') return { behavior: 'allow', updatedInput: input, updatedPermissions: alwaysUpdates(opts.suggestions, ans.always) as never }
        if (ans.decision === 'session') return { behavior: 'allow', updatedInput: input, updatedPermissions: sessionRule(toolName) as never }
        return { behavior: 'allow', updatedInput: input }
      },
    },
  })
  run.q = q

  // Streaming state: the message being generated and its text so far.
  let curMsg: string | null = null
  // Workflow calls' scripts until their results name the run.
  const scripts = new Map<string, string>()
  let lastFlush = 0
  let ok = false
  // What stops Claude (docs/PROTOCOL.md, "Account"): whose an API error is, the latest refused
  // limit's details (the error message after it doesn't carry them), when the request behind the
  // next reply went out, and the error that stopped this run.
  const builtIn = provider.kind === 'anthropic'
  let refused: Record<string, unknown> | null = null
  let askedAt = started
  const stop: { by: Blocker | null } = { by: null }
  const stopBy = (b: Blocker, text: string) => {
    putEvent(id, { id: `e:${randomUUID()}`, kind: 'notice', ts: Date.now(), text: cleanText(text, 1000) || b.text, tone: b.tone, ...(b.kind ? { blocker: b.kind } : {}) })
    blockSession(id, b)
    if (b.kind) stop.by = b
  }
  try {
    for await (const msg of q as AsyncIterable<any>) {
      // Agents the session started: when they start, how far they are, how they end (agents.ts).
      if (msg.type === 'system' && typeof msg.subtype === 'string' && msg.subtype.startsWith('task_')) {
        sdkTask(id, msg)
        continue
      }
      if (msg.parent_tool_use_id) continue // an agent's own turns: its card shows them
      switch (msg.type) {
        case 'system':
          // A call refused without asking ("Allowed only"): the automation's run keeps it (denied.ts).
          if (msg.subtype === 'permission_denied') {
            const ev = st.byId.get(msg.tool_use_id)
            noteDenied(id, String(msg.tool_name ?? ''), ev && ev.kind === 'tool' ? ev.input : {})
          } else if (msg.subtype === 'init') upsertSession(id, { model: msg.model ?? rec.model, permissionMode: msg.permissionMode ?? rec.permissionMode })
          // A failed request Claude Code tries again after a pause: say so rather than "Thinking".
          else if (msg.subtype === 'api_retry') setState(id, 'working', `Retrying (${msg.attempt ?? 1} of ${msg.max_retries ?? '?'})`)
          break
        case 'auth_status':
          // Claude Code couldn't sign in for this run.
          if (typeof msg.error === 'string' && msg.error) stopBy(classifyApiError({ error: 'authentication_failed', text: msg.error, builtIn, at: Date.now() }), msg.error)
          break
        case 'stream_event': {
          const e = msg.event
          if (e?.type === 'message_start') curMsg = e.message?.id ?? null
          else if (e?.type === 'content_block_delta' && e.delta?.type === 'text_delta' && curMsg) {
            const evId = `t:${curMsg}`
            const prev = st.byId.get(evId)
            if (prev && prev.kind === 'assistant') {
              prev.text += e.delta.text
              prev.streaming = true
            } else {
              const ev = { id: evId, kind: 'assistant' as const, ts: Date.now(), text: e.delta.text, streaming: true }
              st.events.push(ev)
              st.byId.set(evId, ev)
            }
            if (Date.now() - lastFlush > 120) {
              lastFlush = Date.now()
              emitEvent(id, evId)
            }
            if (rec.activity !== 'Writing') setState(id, 'working', 'Writing')
          } else if (e?.type === 'content_block_start' && e.content_block?.type === 'thinking') {
            if (rec.activity !== 'Thinking') setState(id, 'working', 'Thinking')
          }
          break
        }
        case 'assistant': {
          const message = msg.message
          if (msg.error) {
            // An API error, worded as if Claude said it: a notice, never Claude's words.
            const text = (message?.content ?? []).map((b: any) => (b?.type === 'text' ? b.text : '')).join('\n')
            stopBy(classifyApiError({ error: msg.error, text, rateLimitInfo: refused, builtIn, at: Date.now() }), text)
            break
          }
          if (message?.model && message.model !== '<synthetic>' && message.content?.length) sessionHealthy(id, askedAt, message.model)
          for (const b of message?.content ?? []) {
            if (b.type === 'tool_use') {
              // A workflow's phases are in its whole script (the event keeps only its start).
              if (b.name === 'Workflow' && typeof b.input?.script === 'string') scripts.set(b.id, b.input.script)
              const ev = startTool(id, b.id, b.name, b.input ?? {})
              setState(id, 'working', `${b.name}: ${ev.summary}`)
            } else {
              feedAssistantBlock(st, message.id, b, Date.now(), false)
              const evId = b.type === 'text' ? `t:${message.id}` : b.type === 'thinking' ? `th:${message.id}` : null
              if (evId) emitEvent(id, evId)
              if (b.type === 'text' && b.text?.trim()) upsertSession(id, { lastText: trimText(b.text, 200) })
            }
          }
          if (message?.usage) {
            const u = message.usage
            const used = (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0)
            if (used > 0) upsertSession(id, { contextPct: Math.min(100, Math.round((used / 200_000) * 100)) })
          }
          break
        }
        case 'user': {
          // Tool results: the next request goes out now.
          askedAt = Date.now()
          for (const b of msg.message?.content ?? []) {
            if (b?.type !== 'tool_result') continue
            const ev = st.byId.get(b.tool_use_id)
            if (ev && ev.kind === 'tool') {
              applyToolResult(ev, b, msg.tool_use_result, Date.now())
              emitEvent(id, ev.id)
              // Automations' hook triggers: a failed call, as the PostToolUseFailure hook says it.
              if (b.is_error) noteHookEvent('PostToolUseFailure', id, ev.name, `${ev.name} failed: ${ev.summary}`)
              // The agent or workflow run a launcher started.
              applyLinks(id, resultLinks(ev, b.tool_use_id, msg.tool_use_result, Date.now(), scripts.get(b.tool_use_id)))
            }
          }
          setState(id, 'working', 'Thinking')
          break
        }
        case 'rate_limit_event': {
          const info = msg.rate_limit_info
          const w = info?.unifiedWindows ?? {}
          const conv = (v: any) => (v?.utilization != null && v.resetsAt ? { pct: v.utilization <= 1 ? v.utilization * 100 : v.utilization, resetsAt: v.resetsAt * 1000 } : null)
          offerUsage(conv(w.five_hour), conv(w.seven_day), Date.now())
          // Refused for a limit (or carrying on past it, on usage credits): the account hears now;
          // the error message that follows stops the session.
          if (info?.status === 'rejected' && builtIn) {
            refused = info
            blockSession(id, { ...classifyApiError({ error: 'rate_limit', rateLimitInfo: info, builtIn, at: Date.now() }), kind: null })
          }
          break
        }
        case 'result': {
          ok = !msg.is_error
          if (run.interrupted) break // "Stopped." is shown instead of an error
          // Stopped by a limit or the account with no error message before (an older Claude Code):
          // the result says which, by its HTTP status.
          const status = typeof msg.api_error_status === 'number' ? msg.api_error_status : null
          if (!ok && !stop.by && (msg.terminal_reason === 'blocking_limit' || (status !== null && [401, 402, 403, 429].includes(status)))) {
            const text = String(msg.result || msg.errors?.join('\n') || '')
            const error = status === 401 || status === 403 ? 'authentication_failed' : status === 402 ? 'unknown' : 'rate_limit'
            stopBy(classifyApiError({ error, status, text, rateLimitInfo: refused, builtIn, at: Date.now() }), text)
          }
          // What the run cost counts even when a limit stopped it after real work.
          const cost = typeof msg.total_cost_usd === 'number' ? msg.total_cost_usd : null
          if (cost != null) upsertSession(id, { costUsd: (rec.costUsd ?? 0) + cost })
          // Stopped by a limit or the account: its notice said so, the run isn't a failure.
          if (stop.by) break
          putEvent(id, {
            id: `r:${randomUUID()}`,
            kind: 'result',
            ts: Date.now(),
            ok,
            durationMs: msg.duration_ms ?? Date.now() - started,
            costUsd: cost,
            turns: msg.num_turns ?? 0,
            error: ok ? null : (msg.errors?.join('\n') || msg.result || msg.subtype || 'Error'),
          })
          break
        }
      }
    }
  } catch (e) {
    // The SDK throws on an error result too ("Claude Code returned an error result: API Error: 402…"):
    // stopped by a limit, the account or the provider, that's the block it said, not a failure.
    if (!abort.signal.aborted && !run.interrupted && !stop.by) throw e
  } finally {
    run.q = null
    run.abort = null
    // Close anything still marked as streaming or running.
    for (const ev of st.events) {
      if (ev.kind === 'assistant' && ev.streaming) {
        ev.streaming = false
        emitEvent(id, ev.id)
      }
      // An agent's or a workflow's call runs on with it in the background (agents.ts closes it),
      // unless the claude process was killed, and its agents with it.
      if (ev.kind === 'tool' && ev.status === 'running' && ev.ts >= started && (!isLauncher(ev.name) || abort.signal.aborted)) {
        ev.status = abort.signal.aborted || run.interrupted ? 'error' : 'done'
        emitEvent(id, ev.id)
      }
    }
  }
  const stopped = abort.signal.aborted || run.interrupted
  if (stopped && !run.stopSaid) notice(id, 'Stopped.', 'info')
  // Stopped by a usage limit or the account: idle with Session.blocked saying why, not an error.
  setState(id, ok || stopped || stop.by ? 'idle' : 'error')
  // Automations' hook triggers: the turn ended (Stop), or an error ended it (StopFailure), as the hooks say it.
  noteHookEvent(ok || stopped ? 'Stop' : 'StopFailure', id, null, ok || stopped ? 'A turn ended.' : 'An API error ended its turn.')
}

export const isRunning = (id: string) => !!runs.get(id)?.running

/**
 * What 10 minutes without an answer does to an ask of a session run here: never a "no" for the
 * owner (docs/DECISIONS.md, "Never auto-deny"), and there is no dialog to hand it back to, so it
 * waits for the phone however long it takes (the SDK waits on canUseTool as long). An automation's
 * run still stops, away, after 10 minutes (Automations, "Following").
 */
export const sdkAwayRule = (_m: Pick<ManagedSession, 'automation'>): AwayRule => 'never'

/** A turn running in session [id] with query [q] (tests: a real one needs Claude Code). */
export function testTurn(id: string, q: Pick<Query, 'setPermissionMode'> | null): void {
  const run = runFor(id)
  run.q = q as Query | null
  run.running = !!q
}
