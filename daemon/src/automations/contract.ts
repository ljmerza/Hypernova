// Automations' contract (docs/PROTOCOL.md, "Automations"): the shapes clients see, the limits and
// timings, the errors, and what the engine needs from the daemon (AutoIO). No state, no daemon.
import { HOUR_MS, MIN_MS } from '../schedule.ts'
import type { Approval, Session, Usage } from '../hub.ts'
import type { Account } from '../account.ts'
import type { ProviderStatus } from '../store.ts'
import type { Battery } from '../battery.ts'

// ── The contract (docs/PROTOCOL.md, "Automations") ───────────────────────
export type Window = 'five_hour' | 'seven_day'
export type SessionsFrom = 'mine' | 'automated' | 'any'
/** The triggers automations had before workflows. */
export type V1Trigger =
  | { kind: 'time'; at: string; days: number[] }
  | { kind: 'once'; at: number }
  | { kind: 'every'; hours: number; from: string; to: string | null; days: number[] }
  | { kind: 'reset'; window: Window; afterLimit: boolean; from: string | null; to: string | null }
  | { kind: 'limit'; window: Window }
  /**
   * `after` (needs_you only, 1–120 minutes): it fires only if the session still needs you that long
   * after (answered meanwhile: never); null, at once. Absent from a kept one or a save: see engine.
   */
  | { kind: 'session'; event: 'finished' | 'needs_you'; projectPath: string | null; from: SessionsFrom; after?: number | null }
  | { kind: 'power'; event: 'plugged_in' }
  | { kind: 'power'; event: 'low'; below: number }
export type Then = 'none' | 'notify'
/** Claude Code's modes, and Codex's sandboxes (a start step on [CODEX], which asks nobody). */
export type Mode = 'dontAsk' | 'default' | 'acceptEdits' | 'auto' | 'bypassPermissions' | 'read-only' | 'workspace-write'
/**
 * The providerId of a start step Codex runs (codex/sessions.ts): the agent, not one of Claude
 * Code's providers. No provider of Claude's has this id (theirs are random 8-character tokens).
 */
export const CODEX = 'codex'
export const CODEX_MODES: readonly Mode[] = ['read-only', 'workspace-write']
export type Action =
  | { kind: 'start'; projectPath: string; prompt: string; providerId: string; model: string | null; permissionMode: Mode; allowedTools: string[]; then: Then }
  | { kind: 'continue'; target: string; prompt: string; providerId: string | null; then: Then }
  | { kind: 'notify'; text: string }
/**
 * `outside_projects`: its folder is there but outside the projects folder (the daemon's path rule).
 * `no_model`: the model it names is no longer on its provider's list. `bad_definition`: what was
 * kept can't be read (a hand edit, an old shape): it was turned off. `no_gh`: a GitHub watch with
 * gh missing or signed out. `no_hooks`: a hook trigger without Claude Code's hooks installed.
 * `output_lost`: a webhook output's secret is gone from the Mac.
 */
export type Problem = null | 'no_battery' | 'no_project' | 'outside_projects' | 'no_session' | 'no_provider' | 'no_model' | 'mode_unavailable' | 'bad_definition' | 'no_gh' | 'no_hooks' | 'output_lost'
export type RunState = 'queued' | 'waiting' | 'running' | 'needs_you' | 'done' | 'failed' | 'skipped' | 'stopped'
/** `budget`: stopped at its budget; `exit`: a shell step's command exited non-zero. */
export type RunReason =
  | 'overlap' | 'queue_full' | 'daily_cap' | 'stale' | 'limited' | 'signed_out' | 'no_plan' | 'on_hold' | 'no_credit' | 'provider'
  | 'missed' | 'no_project' | 'outside_projects' | 'no_session' | 'no_provider' | 'no_model' | 'target_busy' | 'loop' | 'start_failed'
  | 'away' | 'blocked' | 'error' | 'timeout' | 'by_user' | 'paused' | 'mac_restart' | 'ended' | 'budget' | 'exit'
export type FiredBy = 'schedule' | 'reset' | 'limit' | 'session' | 'power' | 'manual' | 'webhook' | 'file' | 'github' | 'hook'
export type Run = {
  id: string
  automationId: string
  name: string
  /** [detail]: what fired it in a few words, for the row (file, github, hook); null otherwise. */
  fired: { by: FiredBy; at: number; sessionId: string | null; detail: string | null }
  state: RunState
  reason: RunReason | null
  until: number | null
  sessionIds: string[]
  startedAt: number | null
  endedAt: number | null
  late: number
  missed: number
  lateWhy: null | 'asleep' | 'down'
  result: string | null
  quiet: boolean
  skippedTools: string[]
  permissionMode: string | null
  depth: number
  /** Run again by Retry: the run it retries. */
  retryOf: string | null
  /** One per step of the definition it ran; [] for a run kept from before steps. */
  steps: RunStep[]
  /** The step at work; once ended, the last one that ran. */
  step: number
  /** It waits for your answer to an ask step (state needs_you). */
  ask: RunAsk | null
  outputs: RunOutput[]
  /** Retry from a step: the step it began at. */
  retryFrom: string | null
  budgetUsed: { minutes: number; windowPct: number | null } | null
}
export type Note = { id: string; automationId: string | null; runId: string | null; name: string; text: string; to: 'phone'; at: number; ask: NoteAsk | null }
export type Automation = {
  id: string
  name: string
  enabled: boolean
  onlyOnce: boolean
  trigger: Trigger
  /** steps[0] as an Action when a client without steps can show it exactly (mirrorAction). */
  action: Action | ActionMirror
  steps: Step[]
  outputs: Output[]
  budget: Budget | null
  template: string | null
  createdAt: number
  updatedAt: number
  nextAt: number | null
  lastRun: Run | null
  runCount: number
  paid: boolean
  problem: Problem
}

// ── Workflows (docs/PROTOCOL.md, "Automations", "Workflows"; docs/specs/workflows.md) ──
// A trigger, 1 to 5 steps in order (each with at most one guard), 0 to 2 outputs, a budget. The
// same automations: a one-step one with outputs `then` can say is exactly an automation as above,
// and its `action` (mirrorAction) is what a client without steps reads and edits.

/**
 * What a daemon can do of workflows: `GET /v1/automations` and `Snapshot.automations` list them. A
 * daemon that sends `features` (even empty) sends `steps`, `outputs` and `budget` and takes them;
 * one plain step and a notify output (today's action and `then`) need no feature. "steps":
 * more than one step, guards, `step:<id>` targets, Retry from a step. "step.output": `{step:<id>}`
 * and `HYPERNOVA_STEP_<id>`. "trigger.after": the session trigger's `after`.
 */
export type Feature =
  | 'steps' | 'ask' | 'shell' | 'budget' | 'step.output'
  | 'trigger.webhook' | 'trigger.cron' | 'trigger.file' | 'trigger.github' | 'trigger.hook' | 'trigger.after'
  | 'output.webhook' | 'output.file'
/** What this daemon offers now: each lands as it's built (an editor offers only these). */
export const FEATURES: readonly Feature[] = ['steps', 'ask', 'shell', 'budget', 'step.output', 'trigger.webhook', 'trigger.cron', 'trigger.file', 'trigger.github', 'trigger.hook', 'trigger.after', 'output.webhook', 'output.file']

export type FileEvent = 'created' | 'changed' | 'deleted'
export type GithubEvent = 'pr_opened' | 'review_comment' | 'checks_failed'
export type HookEvent = 'Stop' | 'StopFailure' | 'PostToolUseFailure' | 'PreCompact' | 'SessionEnd'
/**
 * Fired by `POST /local/automations/{id}/fire` (its token), `POST /v1/automations/{id}/fire` (a
 * paired device) or `hypernova automation fire`. As clients see it: never the token, which is
 * shown once in the save's answer (`{automation, token}`). Input: `{kind: "webhook"}`; the view's
 * other fields are ignored and the token stays across saves.
 */
export type WebhookTrigger = { kind: 'webhook'; last4: string; createdAt: number; url: string; command: string }
/** A webhook trigger as kept in automations.json: the token's SHA-256, never the token. */
export type WebhookTriggerKept = { kind: 'webhook'; tokenHash: string; last4: string; createdAt: number }
export type CronTrigger = { kind: 'cron'; expr: string }
export type FileTrigger = { kind: 'file'; path: string; glob: string | null; event: FileEvent }
export type GithubTrigger = { kind: 'github'; repoPath: string; events: GithubEvent[]; every: 5 | 10 | 15 }
/** `tool`: PostToolUseFailure only (one tool's name, "Bash"); null otherwise. */
export type HookTrigger = { kind: 'hook'; event: HookEvent; tool: string | null; projectPath: string | null; from: SessionsFrom }
/** A trigger as clients see it and send it (a webhook's input is `{kind: "webhook"}`). */
export type Trigger = V1Trigger | WebhookTrigger | CronTrigger | FileTrigger | GithubTrigger | HookTrigger
export type WorkflowTrigger = Trigger
/** A trigger as kept: a webhook's token hashed. */
export type KeptTrigger = V1Trigger | WebhookTriggerKept | CronTrigger | FileTrigger | GithubTrigger | HookTrigger

/**
 * About "the step before": the nearest earlier step that ran (not skipped) and isn't a notify.
 * `contains`: its output, plain text, ignoring case. `answer`: an ask answered `text`.
 */
export type Guard = { on: 'ok' | 'failed' | 'quiet' | 'not_quiet' | 'contains' | 'answer'; text?: string }
/** `id`: [STEP_ID], unique in the automation, kept across edits; `if`: null on the first step. */
export type StepBase = { id: string; if: Guard | null }
export type StartStep = StepBase & Omit<Extract<Action, { kind: 'start' }>, 'then'>
/** `target` also `step:<id>`: the sessions an earlier start or continue step of the same run worked in. */
export type ContinueStep = StepBase & Omit<Extract<Action, { kind: 'continue' }>, 'then'>
/** A pause for a person: answered on the phone or the Mac, else [default] after [timeoutMin]. */
export type AskStep = StepBase & { kind: 'ask'; question: string; answers: string[]; default: string; timeoutMin: number }
/** `/bin/sh -c` in [projectPath], no TTY, a timeout; outside words only through the environment ([SHELL_ENV]). */
export type ShellStep = StepBase & { kind: 'shell'; projectPath: string; command: string; timeoutSec: number }
export type NotifyStep = StepBase & Extract<Action, { kind: 'notify' }>
export type Step = StartStep | ContinueStep | AskStep | ShellStep | NotifyStep
export type StepKind = Step['kind']

export type WebhookPreset = 'ntfy' | 'slack' | 'discord' | 'telegram' | 'post'
/** `text`: 1–500, placeholders filled, shown as it is; null: the run's result. */
export type OutputBase = { id: string; text: string | null }
export type Output = OutputBase &
  (
    | { kind: 'notify' }
    /** As clients see it: [host] and the secret's [last4], never the secret. */
    | { kind: 'webhook'; preset: WebhookPreset; host: string; last4: string }
    | { kind: 'file'; path: string }
  )
/**
 * A webhook output as saved: its secret (`url`; Telegram's `botToken` and `chatId`) only when new
 * or changed. Saved with the [id] it had and no secret, it keeps the one kept for that id
 * (`<support>/automation-secrets.json`, 0600).
 */
export type WebhookOutputInput = { id?: string; kind: 'webhook'; preset: WebhookPreset; text?: string | null; url?: string; botToken?: string; chatId?: string }
export type OutputInput = Exclude<Output, { kind: 'webhook' }> | WebhookOutputInput
/** At least one set. `minutes`: time at work (not waiting for you or a reset); `windowPct`: points of the 5-hour window. */
export type Budget = { minutes: number | null; windowPct: number | null }

/** `action` for a client without steps when steps[0] can't be shown as an Action exactly (mirrorAction). */
export type ActionMirror = { kind: 'workflow'; steps: number }
export type WorkflowProblem = Problem
export type WorkflowAutomation = Automation
/**
 * `POST` / `PUT /v1/automations…`. With [steps], the whole new shape: [action] is ignored,
 * [outputs] absent is [], [budget] absent is null. Without, an old client's save (action + then).
 */
export type WorkflowInput = {
  name: string
  enabled: boolean
  onlyOnce: boolean
  trigger: Trigger | { kind: 'webhook' }
  steps?: Step[]
  outputs?: OutputInput[]
  budget?: Budget | null
  action?: Action | ActionMirror
  template?: string | null
  /** Any start step in bypassPermissions: after the fingerprint (phone) or Touch ID (Mac). */
  confirmBypass?: true
  /** Any start or continue step on a provider that costs money: after a confirmation. */
  confirmPaid?: true
  /** Any shell step: after the fingerprint (phone) or Touch ID (Mac), on every save. */
  confirmShell?: true
}
/** A save's answer: [token] only when this save made a webhook trigger's token (shown once). */
export type SaveAnswer = { automation: Automation; token?: string }

export type StepState = 'pending' | 'running' | 'needs_you' | 'waiting' | 'done' | 'failed' | 'stopped' | 'skipped'
export type WorkflowRunReason = RunReason
/** A step that didn't run: its guard was false, no guard after a failure, or the run ended first. */
export type StepReason = RunReason | 'guard' | 'failed_before' | 'not_reached'
export type RunStep = {
  id: string
  kind: StepKind
  state: StepState
  reason: StepReason | null
  startedAt: number | null
  endedAt: number | null
  /** Its output for the row, ≤ 300 characters, masked (a notify's: the text it sent). */
  result: string | null
  quiet: boolean
  sessionIds: string[]
  /** A shell step's exit code. */
  exit: number | null
  /** An ask step's answer, and who gave it ("timeout": its default, nobody answered). */
  answer: { text: string; by: AnswerBy; at: number } | null
  /** Retry from a later step: taken over from the run it retries. */
  reused: boolean
}
export type AnswerBy = 'phone' | 'mac' | 'timeout'
/** The question a run waits on (state needs_you); [until]: when its default is taken. */
export type RunAsk = { stepId: string; question: string; answers: string[]; default: string; until: number }
export type NoteAsk = RunAsk & { runId: string }
/** `pending`: a webhook being sent, or waiting 30 s to be tried again (its first [error] then). */
export type RunOutput = { id: string; kind: Output['kind']; state: 'pending' | 'sent' | 'failed' | 'skipped'; error: string | null; at: number | null }
export type WorkflowFiredBy = FiredBy
export type WorkflowRun = Run
export type WorkflowNote = Note
/** `POST /v1/automations/runs/{runId}/answer`. */
export type AnswerInput = { stepId: string; answer: string }
/** `POST /v1/automations/runs/{runId}/retry`. */
export type RetryInput = { fromStep?: string }

/** Every error code a client of automations may meet, by status (docs/PROTOCOL.md, "Workflows", Errors). */
export const ERROR_CODES = {
  400: [
    'bad_name', 'bad_template', 'bad_trigger', 'bad_time', 'bad_days', 'past_time', 'no_battery', 'bad_cron', 'too_often', 'not_github', 'no_gh',
    'bad_action', 'bad_steps', 'bad_step', 'bad_guard', 'no_prompt', 'no_project', 'outside_projects', 'no_session', 'no_provider', 'no_model',
    'bad_mode', 'confirm_bypass', 'confirm_paid', 'confirm_shell', 'bad_rule', 'rule_too_broad', 'bad_placeholder', 'bad_target',
    'bad_question', 'bad_answers', 'bad_timeout', 'no_command', 'bad_outputs', 'bad_output', 'bad_text', 'bad_url', 'insecure_url', 'bad_bot_token',
    'bad_chat', 'bad_path', 'secret_file', 'bad_budget', 'too_many', 'bad_enabled', 'bad_paused', 'bad_answer',
  ],
  401: ['bad_token'],
  404: ['no_automation', 'no_run', 'not_found'],
  409: ['running', 'active', 'daily_cap', 'limited', 'signed_out', 'no_plan', 'on_hold', 'no_credit', 'provider', 'no_provider', 'confirm_paid', 'has_steps', 'steps_changed', 'not_asking', 'not_webhook', 'paused', 'off'],
  413: ['too_large'],
  415: ['not_text'],
  429: ['too_often'],
} as const
export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES][number]

// ── Workflows' limits (docs/PROTOCOL.md, "Workflows", Limits) ─────────────
export const MAX_STEPS = 5
export const MAX_OUTPUTS = 2
/** A step's or an output's id. */
export const STEP_ID = /^[A-Za-z0-9_-]{1,24}$/
/** The id of the one step (and output) an automation saved before steps has, or an old client's save makes. */
export const FIRST_STEP_ID = 'st_0'
export const FIRST_OUTPUT_ID = 'ou_0'
export const GUARD_TEXT_MAX = 200
export const ASK_QUESTION_MAX = 200
export const ASK_ANSWERS_MIN = 2
export const ASK_ANSWERS_MAX = 3
export const ASK_ANSWER_MAX = 24
export const ASK_DEFAULT_ANSWERS = ['Approve', 'Skip'] as const
export const ASK_TIMEOUT_MIN = 10
export const ASK_TIMEOUT_MAX_MIN = 60
export const SHELL_COMMAND_MAX = 2000
export const SHELL_TIMEOUT_SEC = 60
export const SHELL_TIMEOUT_MAX_SEC = 600
/** SIGTERM, then SIGKILL this long after, to a shell step over its time. */
export const SHELL_KILL_MS = 5000
/** A shell step's output kept: the last 8 KB. */
export const SHELL_OUTPUT_MAX = 8 * 1024
/** A step's output as a row's result (and `{prev}` of a shell step): its last 300 characters. */
export const STEP_RESULT_MAX = 300
/**
 * `{step:<id>}` (text.ts): an earlier step's output, as `{prev}` was right after it. Outside text:
 * quoted in a prompt, refused in a No asking prompt and in a shell command (which reads [stepEnv]).
 * The variable that holds an earlier step's whole output for a shell step: `HYPERNOVA_STEP_<id>`, the
 * id as it is with each "-" as "_" (a variable's name can't hold "-"; of two ids that then read the
 * same, the later step's).
 */
export const stepEnv = (id: string) => `HYPERNOVA_STEP_${id.replace(/-/g, '_')}`
/** The session trigger's `after`: 1 to this many minutes. */
export const SESSION_AFTER_MAX_MIN = 120
export const OUTPUT_TEXT_MAX = 500
export const PAYLOAD_MAX = 8 * 1024
export const FIRE_BODY_MAX = 64 * 1024
/** "hnf_" + 32 hex. */
export const FIRE_TOKEN = /^hnf_[0-9a-f]{32}$/
export const WEBHOOK_TIMEOUT_MS = 10_000
/** A webhook that failed on the network or with a 5xx is tried once more after this. */
export const WEBHOOK_RETRY_MS = 30_000
export const FILE_QUIET_MS = 10_000
export const FILE_GLOB_MAX = 200
/** At most this many changed paths in a file watch's {payload}. */
export const FILE_PATHS_MAX = 50
export const GITHUB_EVERY = [5, 10, 15] as const
export const CRON_MIN_GAP_MS = 5 * MIN_MS
export const BUDGET_MINUTES_MAX = 720
export const BUDGET_PCT_MAX = 100
/** How often a run's budget is looked at. */
export const BUDGET_CHECK_MS = 30_000

/** Placeholders, today's and the steps' (docs/PROTOCOL.md, "Workflows", Placeholders). */
export const PLACEHOLDERS = ['project', 'session', 'result', 'ask', 'battery', 'time', 'week', 'prev', 'exit', 'output', 'answer', 'payload'] as const
export type Placeholder = (typeof PLACEHOLDERS)[number]
/**
 * Words from another session or from outside Hypernova: quoted in a start's or continue's prompt;
 * refused in a No asking start's prompt and in a shell command (400 `bad_placeholder`).
 */
export const OUTSIDE_PLACEHOLDERS: readonly Placeholder[] = ['session', 'result', 'ask', 'prev', 'output', 'payload']
/**
 * The only placeholders a shell command may hold: each becomes a quoted reference to its variable
 * in [SHELL_ENV] (text.ts fillShell), never its value.
 */
export const SHELL_PLACEHOLDERS: readonly Placeholder[] = ['project', 'time', 'battery', 'exit', 'answer']
/** What a shell step finds in its environment: every value it may use, none ever pasted into its command. */
export const SHELL_ENV = {
  automation: 'HYPERNOVA_AUTOMATION',
  run: 'HYPERNOVA_RUN',
  project: 'HYPERNOVA_PROJECT',
  time: 'HYPERNOVA_TIME',
  battery: 'HYPERNOVA_BATTERY',
  prev: 'HYPERNOVA_PREV',
  output: 'HYPERNOVA_OUTPUT',
  answer: 'HYPERNOVA_ANSWER',
  exit: 'HYPERNOVA_EXIT',
  payload: 'HYPERNOVA_PAYLOAD',
} as const

/** `then` as outputs: "notify" is a plain notify, "none" nothing. */
export function outputsOfThen(then: Then): Output[] {
  if (then === 'notify') return [{ id: FIRST_OUTPUT_ID, text: null, kind: 'notify' }]
  return []
}
/** An old client's action as one step (with [id]) and the outputs its `then` says. */
export function stepOfAction(a: Action, id: string = FIRST_STEP_ID): { step: Step; outputs: Output[] } {
  if (a.kind === 'start' || a.kind === 'continue') {
    const { then, ...rest } = a
    return { step: { id, if: null, ...rest }, outputs: outputsOfThen(then) }
  }
  return { step: { id, if: null, ...a }, outputs: [] }
}
/** The `then` that says [outputs], or null when `then` can't (more than one, a webhook, a file, a text of its own). */
function thenOf(outputs: Output[]): Then | null {
  if (!outputs.length) return 'none'
  const o = outputs[0]!
  if (outputs.length > 1 || o.text != null) return null
  if (o.kind === 'notify') return 'notify'
  return null
}
/**
 * `action` as a client without steps reads it: steps[0] as an Action when it shows the automation
 * exactly (one step of kind start, continue but not `step:…`, or notify, no guard, outputs
 * `then` can say: none at all for notify), else `{kind: "workflow", steps}`, a kind it
 * doesn't know, so it shows it without editing it.
 */
export function mirrorAction(steps: Step[], outputs: Output[]): Action | ActionMirror {
  const mirror: ActionMirror = { kind: 'workflow', steps: steps.length }
  const s = steps[0]
  if (steps.length !== 1 || !s || s.if) return mirror
  const then = thenOf(outputs)
  switch (s.kind) {
    case 'start': {
      if (then == null) return mirror
      const { id: _i, if: _g, ...a } = s
      return { ...a, then }
    }
    case 'continue': {
      if (then == null || s.target.startsWith('step:')) return mirror
      const { id: _i, if: _g, ...a } = s
      return { ...a, then }
    }
    case 'notify': {
      if (outputs.length) return mirror
      const { id: _i, if: _g, ...a } = s
      return a
    }
  }
  return mirror
}

// ── Limits and timings (docs/specs/spec-automations.md, §4) ──────────────
export const MAX_AUTOMATIONS = 50
export const MAX_RUNNING = 2
export const MAX_QUEUED = 10
export const DAILY_CAP = 24
export const DAILY_NOTES_CAP = 200
/**
 * A webhook trigger takes this many fires a minute (its token's or a device's; 429 `too_often` past
 * it, nothing recorded): a loop on the same machine can't fill the runs, the log or the phone.
 */
export const FIRES_PER_MIN = 10
export const MAX_DEPTH = 2
export const STALE_MS = 6 * HOUR_MS
export const WAIT_MAX_MS = 6 * HOUR_MS
/** A session is finished once it has stayed idle this long: the moment the phone says "Finished". */
export const SETTLE_MS = 5000
/** An agent's approval counts as needing you once it has waited this long (agents often settle it). */
export const AGENT_ASK_MS = 10_000
/** After a reset or a limit, the wait before it fires (the reset settles; the other sessions hit the limit). */
export const AFTER_RESET_MS = 60_000
export const AFTER_LIMIT_MS = 60_000
/** A continue that can't be typed (the session waits on an answer, has text in its box) tries again. */
export const RETRY_MS = 30_000
export const RETRY_STARTING_MS = 5000
export const RETRY_FOR_MS = 10 * MIN_MS
/** A run stops following its sessions after this; the sessions are left alone. */
export const TIMEOUT_MS = 12 * HOUR_MS
/** A run's terminal is closed this long after it's done, if nobody has it open. */
export const TIDY_MS = 15 * MIN_MS
/** A session a run started that never got to work: it didn't start. */
export const NO_START_MS = 10 * MIN_MS
export const PLUG_DEBOUNCE_MS = 10 * MIN_MS
export const SESSION_EVENT_GAP_MS = MIN_MS
export const NOTES_KEEP_MS = 12 * HOUR_MS
/** A run's session that waits on a terminal menu with no approval open (that has its own 10 minutes) gets Esc after this. */
export const NEEDS_MAX_MS = 10 * MIN_MS


/** Exactly, so the run's last reply can be compared with what it was asked to say. */
export const NOTHING_NEW = 'Nothing new.'
/** What a run's session is told about itself (docs/specs/spec-automations.md, "Running"). */
export function appendedPrompt(name: string): string {
  return `This session was started by a Hypernova automation named "${name}", not typed by the user just now, and nobody is watching it live. Work on your own: don't ask the user questions; make reasonable choices and mention them. Start your final reply with one sentence that sums up the outcome: it is sent to the user's phone. If nothing needs the user's attention, make your final reply exactly: ${NOTHING_NEW}`
}

export class AutomationError extends Error {
  status: number
  code: string
  /** More fields for the error object (a limit's `until`). */
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}
/**
 * A 400. [field]: the part of the body it is about, as a path into it ("name", "trigger.expr",
 * "steps[2].command", "outputs[0].url"), so an editor marks that field.
 */
export const bad = (code: string, message: string, field?: string) => new AutomationError(400, code, message, field ? { field } : undefined)

// ── What the engine needs from the daemon ─────────────────────────────────
export type StartSpec = {
  cwd: string
  prompt: string
  providerId: string
  model: string | null
  permissionMode: Mode
  allowedTools: string[]
  appendSystemPrompt: string
  /** The session's name, "Morning brief, Thu 16 Oct". */
  name: string
  automation: { id: string; name: string; runId: string }
}
/** A prompt sent ([sessionId]: where it went, a resumed or forked session's), or why not (a route's 409 code). */
export type Sent = { ok: true; sessionId: string } | { ok: false; code: string }
export type AutoIO = {
  now(): number
  /** The Mac's time zone now. */
  tz(): string
  start(o: StartSpec): Promise<string>
  prompt(id: string, text: string, afterLimit: boolean): Promise<Sent>
  /** Move a session to another provider first ("Switch provider at the limit"); the id it goes on under. */
  move(id: string, providerId: string): Promise<string>
  interrupt(id: string): Promise<void>
  endTerm(termId: string): void
  session(id: string): Session | null
  sessions(): Session[]
  approvals(): Approval[]
  account(): Account
  providerKind(id: string): 'anthropic' | 'openrouter' | 'custom' | 'codex' | null
  providerName(id: string): string
  providerStatus(id: string): ProviderStatus | null
  usage(): Usage
  battery(): Battery | null
  /** The session's last reply, whole (null: none yet). */
  lastReply(id: string): string | null
  /**
   * Calls Claude Code refused in the session without asking since [since] ("Permission to use … has
   * been denied", in its transcript): the PermissionDenied hook doesn't fire for a dontAsk refusal
   * (2.1.301; test/fixtures/permission-denied.json), so this is where they're read.
   */
  refused(id: string, since: number): { tool: string; input: unknown }[]
  /** The folder for a run, when it passes the daemon's path rule and is there. */
  project(path: string): string | null
  /** The folder is there, but outside the projects folder (so [project] refuses it). */
  outside?(path: string): boolean
  /** The error a session last stopped on, in words (null: none). */
  lastError?(id: string): string | null
  /** False when the owner said the provider costs nothing (a model on this computer); unset: it costs money. */
  providerPaid?(id: string): boolean
  /**
   * Codex's account, for a run on it: set up and signed in, or out of its limit until [resetsAt];
   * and its 5-hour window, for a budget's points (null: it has none).
   */
  codexGate?(): { state: 'ok' | 'off' | 'signed_out' | 'limited'; resetsAt: number | null }
  codexFiveHour?(): number | null
  /** Whether [model] is on [providerId]'s list as last read (null: no list to go by). */
  modelKnown?(providerId: string, model: string): boolean | null
  /** [providerId]'s models, read now if need be (null: couldn't): checked when one is saved. */
  models?(providerId: string): Promise<string[] | null>
  /** "This week Claude worked …", from Statistics. */
  week(): Promise<string | null>
  send(msg: object): void
  keepAwake(key: string, on: boolean): void
  load(): { state: unknown; runs: unknown }
  save(state: object): void
  saveRuns(runs: object): void
  /** Call tick() in [ms] (the daemon's one timer; nothing in tests). */
  wake(ms: number): void

  // ── Workflows (each optional: a daemon without one doesn't offer what needs it) ──
  /** A shell step: `/bin/sh -c` in [cwd], no TTY, stdin empty, killed (its group) past [timeoutMs]. */
  shell?(o: ShellSpec): ShellRun
  /**
   * A webhook output's request (https only, 10 s, no redirects, never to this computer or a private
   * network unless allowed): its status, or a throw for a network error (`private_address`: refused).
   */
  post?(r: WebhookRequest): Promise<{ status: number }>
  /** Hosts a webhook may reach on a private address (settings.json `webhookPrivateHosts`). */
  webhookPrivateHosts?(): readonly string[]
  /** A file output: [path] checked as at save, then [line] appended (made when missing). Throws an AutomationError. */
  append?(path: string, line: string): void
  /** A file output's path at save: the file it means, or why not. */
  checkFile?(path: string): { path: string } | { code: 'no_project' | 'outside_projects' | 'secret_file' | 'bad_path' }
  /** Webhook outputs' secrets (automation-secrets.json, 0600), by output id. */
  loadSecrets?(): unknown
  saveSecrets?(s: object): void
  /** A file watch: [on] for each file created, changed or deleted under [path] (paths relative); the stop. */
  watch?(path: string, on: (rel: string, event: FileEvent) => void): () => void
  /** A GitHub watch's look: what is there now (null: gh missing, signed out, or it failed). */
  gh?(repoPath: string, events: GithubEvent[]): Promise<GhItem[] | null>
  /** gh is installed and signed in. */
  ghReady?(): Promise<boolean>
  /** The folder's git remote is on GitHub. */
  githubRepo?(path: string): Promise<boolean>
  /** Claude Code's hooks are installed (a hook trigger fires for terminal sessions). */
  hooksInstalled?(): boolean
  /** Where a webhook trigger is fired: the local URL and the CLI line for this computer. */
  fireInfo?(id: string): { url: string; command: string }
}
/** A shell step to run. */
export type ShellSpec = { cwd: string; command: string; env: Record<string, string>; timeoutMs: number }
/** A shell step at work: how it ended (its output masked and cut), and a way to stop it. */
export type ShellRun = { done: Promise<{ exit: number | null; output: string; timedOut: boolean }>; kill(): void }
/** A webhook output's secret, kept by output id. */
export type OutputSecret = { url?: string; botToken?: string; chatId?: string }
/** What a webhook output sends (outputs.ts builds it). */
export type WebhookRequest = { url: string; headers: Record<string, string>; body: string }
/** One thing a GitHub watch saw: [key] names it for good (a PR, a review, a failed check). */
export type GhItem = { key: string; event: GithubEvent; line: string }


/** Placeholder values, taken when it fires (and, for the steps' own, as each step ends). */
export type Ctx = {
  project: string | null
  session: string | null
  result: string | null
  ask: string | null
  battery: string | null
  time: string | null
  prev?: string | null
  exit?: string | null
  output?: string | null
  answer?: string | null
  payload?: string | null
  /** `{step:<id>}`: each step's output as it ended (as `{prev}` was right after it), by step id. */
  steps?: Record<string, string>
}
