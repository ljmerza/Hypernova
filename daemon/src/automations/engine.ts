// The automations engine: the store, firing through the gates, following runs, and the routes.
// Everything that touches the daemon goes through an AutoIO (contract.ts; the daemon's is daemon.ts).
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { basename, join, sep } from 'node:path'
import { isSecretFile } from '../secretfiles.ts'
import { log } from '../util.ts'
import { DAY_MS, MIN_MS, ON_TIME_MS, clockText, dueBetween, inWindow, localDay, localParts, nextOccurrence, nextOccurrences, occurrencesBetween, parseHm, type TimeTrigger } from '../schedule.ts'
import { CronError, parseCron } from '../cron.ts'
import { maskSecrets } from '../normalize.ts'
import { CONTINUE_TEXT } from '../blockers.ts'
import type { Approval, Session } from '../hub.ts'
import type { Battery } from '../battery.ts'
import {
  AFTER_LIMIT_MS, AFTER_RESET_MS, AGENT_ASK_MS, AutomationError, DAILY_CAP, DAILY_NOTES_CAP, FIRES_PER_MIN, MAX_AUTOMATIONS, MAX_DEPTH, MAX_QUEUED, MAX_RUNNING, NEEDS_MAX_MS, NOTES_KEEP_MS, NO_START_MS, PLUG_DEBOUNCE_MS, RETRY_FOR_MS, RETRY_MS, RETRY_STARTING_MS, SESSION_EVENT_GAP_MS, SETTLE_MS, STALE_MS, TIDY_MS, TIMEOUT_MS, WAIT_MAX_MS,
  ASK_ANSWERS_MAX, ASK_ANSWERS_MIN, ASK_ANSWER_MAX, ASK_DEFAULT_ANSWERS, ASK_QUESTION_MAX, ASK_TIMEOUT_MAX_MIN, ASK_TIMEOUT_MIN, BUDGET_MINUTES_MAX, BUDGET_PCT_MAX, CRON_MIN_GAP_MS, FILE_GLOB_MAX, FILE_PATHS_MAX, FILE_QUIET_MS, FIRST_STEP_ID, GITHUB_EVERY, GUARD_TEXT_MAX, MAX_OUTPUTS, MAX_STEPS, OUTPUT_TEXT_MAX, PAYLOAD_MAX, SHELL_COMMAND_MAX, SHELL_ENV, SHELL_PLACEHOLDERS, SHELL_TIMEOUT_MAX_SEC, SHELL_TIMEOUT_SEC, STEP_ID, STEP_RESULT_MAX, WEBHOOK_RETRY_MS, SHELL_OUTPUT_MAX as SHELL_OUTPUT_KEPT,
  SESSION_AFTER_MAX_MIN, FEATURES, CODEX, CODEX_MODES, appendedPrompt, bad, mirrorAction, stepEnv, stepOfAction,
  type Action, type AnswerBy, type AutoIO, type Automation, type Budget, type Ctx, type Feature, type FileEvent, type FiredBy, type GhItem, type GithubEvent, type Guard, type HookEvent, type KeptTrigger, type Note, type NoteAsk, type Output, type OutputSecret, type Problem, type Run, type RunOutput, type RunReason, type RunState, type RunStep, type ShellRun, type Step, type StepReason, type Then, type Trigger, type Window,
} from './contract.ts'
import { FROM_OUTSIDE, capQuestion, checkRule, fill, fillShell, isQuiet, placeholdersIn, resultOf, ruleFor, sentences, stepRefs } from './text.ts'
import { PRESETS, checkSecret, fileLine, secretHint, webhookRequest } from './outputs.ts'

const MAX_TICK_MS = 30_000
/** The timer never comes sooner than this after a tick (a deadline already past must not spin it). */
const MIN_TICK_MS = 1000
const RUNS_KEPT = 50
const RUNS_MAX_AGE_MS = 90 * DAY_MS

// ── Kept on disk ─────────────────────────────────────────────────────────
/** An automation as kept: the definition, plus what it needs to fire each time once. */
type Def = {
  id: string
  name: string
  enabled: boolean
  onlyOnce: boolean
  trigger: KeptTrigger
  /** steps[0] as an Action (then from the outputs it can say), kept for an older daemon reading the file. */
  action: Action
  /** 1–5 steps (an automation kept before steps: its action, "st_0"); 0–2 outputs (webhooks' secrets apart); a budget. */
  steps: Step[]
  outputs: Output[]
  budget: Budget | null
  template: string | null
  createdAt: number
  updatedAt: number
  /** Time triggers: the last due time handled (fired or missed) and its key; nothing at or before [armedAt] is due. */
  firedFor: string | null
  firedAt: number | null
  armedAt: number
  /** Reset and limit triggers: the reset, or the limit's start, it last fired for. */
  resetFiredFor: number | null
  limitFiredFor: number | null
  /** Battery low: armed for this discharge. */
  lowArmed: boolean
  lastPluggedAt: number | null
  /** Its last run's session ran in another mode than asked (Auto not on this account). */
  modeProblem: boolean
  /** The day the daily cap was last said, so it's said once. */
  capDay: string | null
  /**
   * The owner agreed it may cost money (`confirmPaid` when saved). Checked again when it runs: a
   * session it continues may have moved to a paid provider since, and that wasn't agreed to.
   */
  paidOk: boolean
  /** What was kept couldn't be read as an automation (a hand edit, an old shape): it stays off. */
  bad?: boolean
  /** A GitHub watch: what it has seen (keys), and when it last looked (it fires only on what's new). */
  ghSeen?: string[] | null
  ghLookAt?: number | null
}
type Target = { id: string; state: 'pending' | 'sent' | 'failed'; nextAt: number; until: number; moved: boolean; code: string | null }
/**
 * A run's session as last seen: [last] its state (one that ends mid-turn didn't finish), [needsAt]
 * since when it has waited on a terminal menu with no approval open, [escAt] when it got Esc for that.
 */
type Seen = { worked: boolean; idleAt: number | null; since: number; finished: boolean; last?: string | null; needsAt?: number | null; escAt?: number | null }
/** How a run is followed (kept with it, so a restart picks it up). */
type Follow = {
  /** It took its slot: started its session, or prompted one. */
  started: boolean
  queuedAt: number | null
  /** Waiting for a reset before it starts: gated again then. */
  regateAt: number | null
  targets: Target[]
  seen: Record<string, Seen>
  doneAt: number | null
  tidyAt: number | null
  /** The mode it asked for (start), to tell when Claude Code used another. */
  asked: string | null
  /** It holds one of the 2 slots (a start, or a continue that resumes an ended session). */
  slot: boolean
  /** The step at r.step is to begin on the next pass (the one before ended). */
  next?: boolean
  /** The placeholders' values as each step began, by step id (Retry from a step takes them). */
  ctxBefore?: Record<string, Ctx>
  /** Its budget: time at work so far, since when it works now, the 5-hour window's points when it began. */
  workMs?: number
  workSince?: number | null
  pct0?: number | null
  /** A shell step's end, once its command ended (picked up by the next pass). */
  shellEnd?: { exit: number | null; output: string; timedOut: boolean } | null
  /** Webhook outputs waiting to be tried again (by output id), and when. */
  retryAt?: Record<string, number>
  /** Each step's whole output while the run works (a session's last reply, a command's 8 KB): `contains` and HYPERNOVA_STEP_<id> look at it. */
  full?: Record<string, string>
  /** The last notify step's text as sent (an output with no text, when no step gave a result). */
  told?: string
}
/** How a run was fired: Run now, the session that fired it, how late, Retry (and from which step), what fired it. */
type FireOpts = {
  manual?: boolean
  sessionId?: string | null
  late?: number
  missed?: number
  lateWhy?: Run['lateWhy']
  retryOf?: string | null
  from?: { index: number; steps: RunStep[]; ctx: Ctx } | null
  detail?: string | null
}
/** What a run runs: its definition as it fired (a run in progress keeps it across edits). */
type Spec = { name: string; trigger: KeptTrigger; steps: Step[]; outputs: Output[]; budget: Budget | null; paidOk?: boolean; action?: Action }
type RunRec = Run & { spec: Spec; ctx: Ctx; f: Follow }
type Saved = {
  version: 1
  paused: boolean
  maxRunning: number
  automations: Def[]
  /** Each usage window's reset and how full it was before it, as last read (official usage only). */
  windows: Partial<Record<Window, { resetsAt: number; pct: number }>>
  /** The account's 5-hour and weekly limits, newest last: which window, its reset, when it began. */
  limits: { window: Window; resetsAt: number | null; since: number }[]
  /** Sessions a usage limit stopped that haven't worked since ("Carry on after the limit"). */
  limitStopped: Record<string, { at: number; armed: boolean }>
  /** Account episodes already told about (one note for all automations). */
  toldFor: string | null
}

const ACTIVE: RunState[] = ['queued', 'waiting', 'running', 'needs_you']
/** A continue's 409s that mean "not now": tried again every 30 s for 10 minutes. */
const BUSY = ['needs_answer', 'waiting', 'typing', 'starting', 'terminal_busy', 'busy']
/** Gate 6's skips, also met by one session of a continue on the provider it carries on with. */
const GATE_REASONS: RunReason[] = ['limited', 'signed_out', 'no_plan', 'on_hold', 'no_credit', 'provider']
/** A run's session stopped by a block with no reset: the run's reason, as the gate's skips are worded. */
const BLOCKED_REASON: Partial<Record<string, RunReason>> = { logged_out: 'signed_out', no_subscription: 'no_plan', on_hold: 'on_hold', no_credit: 'no_credit', provider: 'provider', limit: 'limited', model: 'limited' }
const isActive = (r: Run) => ACTIVE.includes(r.state)
const hex = (n: number) => randomBytes(n).toString('hex')
/** At most 300 characters, as a run's result. */
const cut = (s: string) => (s.length > 300 ? s.slice(0, 299).trimEnd() + '…' : s)
const isWindow = (v: unknown): v is Window => v === 'five_hour' || v === 'seven_day'
const isTimeTrigger = (t: KeptTrigger): t is TimeTrigger & KeptTrigger => t.kind === 'time' || t.kind === 'once' || t.kind === 'every' || t.kind === 'cron'
const sha = (s: string) => createHash('sha256').update(s).digest('hex')
const SESSION_STEPS = new Set(['start', 'continue'])
/** The step kinds that only tell (they don't count as "the step before" and pass gates 1 to 3 only). */
const TELLS = new Set(['notify'])
const FILE_EVENTS: FileEvent[] = ['created', 'changed', 'deleted']
const GH_EVENTS: GithubEvent[] = ['pr_opened', 'review_comment', 'checks_failed']
const HOOK_EVENTS: HookEvent[] = ['Stop', 'StopFailure', 'PostToolUseFailure', 'PreCompact', 'SessionEnd']
/** A step as the Action it is (start, continue, notify), `then` none: what the session machinery runs. */
function asAction(st: Step): Action | null {
  switch (st.kind) {
    case 'start':
    case 'continue': {
      const { id: _i, if: _g, ...a } = st
      return { ...a, then: 'none' } as Action
    }
    case 'notify': {
      const { id: _i, if: _g, ...a } = st
      return a as Action
    }
  }
  return null
}
/** The Action kept beside the steps for an older daemon: steps[0], `then` from the outputs when they say one. */
function keptAction(steps: Step[], outputs: Output[]): Action {
  const m = mirrorAction(steps, outputs)
  if (m.kind !== 'workflow') return m
  const a = asAction(steps[0]!)
  return a ?? { kind: 'notify', text: 'This automation needs a newer Hypernova.' }
}
/** A step that failed before it said anything, in words: {prev} for a step guarded "failed" after it. */
const STEP_WHY: Partial<Record<RunReason, string>> = {
  no_project: 'The project folder is gone.',
  outside_projects: 'Its folder is outside your projects folder.',
  no_session: 'That session no longer exists.',
  no_provider: 'That provider was removed.',
  no_model: 'Its model isn’t offered any more.',
  start_failed: 'Its session didn’t start.',
  error: 'It stopped with an error.',
  away: 'Nobody answered it for 10 minutes.',
  ended: 'It ended before it finished.',
  mac_restart: 'The Mac restarted before it finished.',
  timeout: 'It ran past its time.',
}
/** A file a run of the watching automation wrote this long before it ended is its own write, not news. */
const OWN_WRITES_MS = 5000
/** A file watch keeps this many changed paths between quiet moments, and counts the rest. */
const FILE_CHANGES_KEPT = 1000
/** A glob read: a literal character, `?`, `*` (within a name), `**` (across folders), `**` then a slash (no folder or any), `{a,b}`. */
type GlobPart = { k: 'c'; c: string } | { k: '?' } | { k: '*' } | { k: '**' } | { k: '**/' } | { k: 'alt'; alts: GlobPart[][] }
/** One step of a glob's matcher: a character, one that isn't "/", any one, a fork, a jump, the end. */
type GlobOp = { op: 'c'; c: string } | { op: 'ns' } | { op: 'any' } | { op: 'split'; x: number; y: number } | { op: 'jmp'; x: number } | { op: 'end' }
function readGlob(g: string): GlobPart[] {
  let i = 0
  const seq = (depth: number): GlobPart[] => {
    const out: GlobPart[] = []
    while (i < g.length) {
      const c = g[i]!
      if (c === '}' || (c === ',' && depth > 0)) break
      i++
      if (c === '*') {
        if (g[i] !== '*') out.push({ k: '*' })
        else {
          while (g[i] === '*') i++
          if (g[i] === '/') {
            i++
            out.push({ k: '**/' })
          } else out.push({ k: '**' })
        }
      } else if (c === '?') out.push({ k: '?' })
      else if (c === '{') {
        const alts = [seq(depth + 1)]
        while (g[i] === ',') {
          i++
          alts.push(seq(depth + 1))
        }
        if (g[i] !== '}') throw new Error('A “{” isn’t closed.')
        i++
        out.push({ k: 'alt', alts })
      } else out.push({ k: 'c', c })
    }
    return out
  }
  const parts = seq(0)
  if (i < g.length) throw new Error('A “}” closes nothing.')
  return parts
}
function compileGlob(parts: GlobPart[], prog: GlobOp[] = []): GlobOp[] {
  const loop = (any: boolean) => {
    const at = prog.length
    prog.push({ op: 'split', x: at + 1, y: at + 3 }, any ? { op: 'any' } : { op: 'ns' }, { op: 'jmp', x: at })
  }
  for (const p of parts) {
    if (p.k === 'c') prog.push({ op: 'c', c: p.c })
    else if (p.k === '?') prog.push({ op: 'ns' })
    else if (p.k === '*' || p.k === '**') loop(p.k === '**')
    else if (p.k === '**/') {
      // No folder, or any folders: (anything, then "/"), or nothing.
      const fork: GlobOp & { op: 'split' } = { op: 'split', x: prog.length + 1, y: -1 }
      prog.push(fork)
      loop(true)
      prog.push({ op: 'c', c: '/' })
      fork.y = prog.length
    } else {
      const ends: (GlobOp & { op: 'jmp' })[] = []
      p.alts.forEach((alt, k) => {
        const last = k === p.alts.length - 1
        const fork: GlobOp & { op: 'split' } = { op: 'split', x: prog.length + 1, y: -1 }
        if (!last) prog.push(fork)
        compileGlob(alt, prog)
        if (last) return
        const end: GlobOp & { op: 'jmp' } = { op: 'jmp', x: -1 }
        prog.push(end)
        ends.push(end)
        fork.y = prog.length
      })
      for (const e of ends) e.x = prog.length
    }
  }
  return prog
}
const globs = new Map<string, { prog: GlobOp[]; anywhere: boolean }>()
/**
 * A file watch's pattern against a path relative to its folder: `*` within a name, `**` across
 * folders, `?`, `{a,b}`. One without a slash matches a file's name in any folder (as .gitignore).
 * Matched by walking every way through the pattern at once (no regular expression, no
 * backtracking): time grows with the path's length times the pattern's, whatever either holds, so
 * a file named to make a pattern backtrack (a ReDoS) can't hold the daemon up. A pattern whose
 * braces don't pair throws.
 */
export function globMatch(glob: string, path: string): boolean {
  let m = globs.get(glob)
  if (!m) {
    m = { prog: [...compileGlob(readGlob(glob)), { op: 'end' }], anywhere: !glob.includes('/') }
    if (globs.size > 200) globs.clear()
    globs.set(glob, m)
  }
  const { prog, anywhere } = m
  const mark = new Int32Array(prog.length).fill(-1)
  const add = (to: number[], pc: number, gen: number) => {
    const stack = [pc]
    while (stack.length) {
      const p = stack.pop()!
      if (mark[p] === gen) continue
      mark[p] = gen
      const op = prog[p]!
      if (op.op === 'jmp') stack.push(op.x)
      else if (op.op === 'split') stack.push(op.y, op.x)
      else to.push(p)
    }
  }
  let now: number[] = []
  add(now, 0, 0)
  for (let i = 0; i < path.length; i++) {
    const ch = path[i]!
    const next: number[] = []
    for (const p of now) {
      const op = prog[p]!
      if ((op.op === 'c' && op.c === ch) || (op.op === 'ns' && ch !== '/') || op.op === 'any') add(next, p + 1, i + 1)
    }
    // A pattern without a slash may start at any file's name.
    if (anywhere && ch === '/') add(next, 0, i + 1)
    now = next
    if (!now.length && !anywhere) return false
  }
  return now.some((p) => prog[p]!.op === 'end')
}
const newRunStep = (st: Step): RunStep => ({ id: st.id, kind: st.kind, state: 'pending', reason: null, startedAt: null, endedAt: null, result: null, quiet: false, sessionIds: [], exit: null, answer: null, reused: false })
const DAY_NAMES = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const MONTHS = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

// ── Validation ────────────────────────────────────────────────────────────
function days(v: unknown, field = 'trigger.days'): number[] {
  if (!Array.isArray(v) || !v.length || v.length > 7 || !v.every((d) => Number.isInteger(d) && d >= 1 && d <= 7) || new Set(v).size !== v.length) throw bad('bad_days', 'Pick at least one day.', field)
  return [...(v as number[])].sort((a, b) => a - b)
}
function hm(v: unknown, code = 'bad_time', field = 'trigger.at'): string {
  if (!parseHm(v)) throw bad(code, 'Pick a time of day.', field)
  return v as string
}
const optHm = (v: unknown, field: string) => (v == null ? null : hm(v, 'bad_time', field))
/** A cron that would run twice within 5 minutes (over the next 8 days). */
function tooOften(expr: string, now: number, tz: string): boolean {
  const occ = occurrencesBetween({ kind: 'cron', expr }, now, now + 8 * DAY_MS, tz)
  for (let i = 1; i < occ.length; i++) if (occ[i]!.at - occ[i - 1]!.at < CRON_MIN_GAP_MS) return true
  return false
}
/** An old client can show it exactly (its action mirrors its one step). */
const simple = (d: Pick<Def, 'steps' | 'outputs'>) => mirrorAction(d.steps, d.outputs).kind !== 'workflow'
const FILE_WORDS: Record<string, string> = {
  no_project: 'That folder doesn’t exist.',
  outside_projects: 'That file is outside your projects folder. Pick one in a project.',
  secret_file: 'That file holds secrets: pick another.',
  bad_path: 'Pick a file (its folder must be there).',
}
const str = (v: unknown) => (typeof v === 'string' ? v : '')

/**
 * A kept definition reads as an automation: the shape of its trigger and action, never whether its
 * folder or provider is there (those are problems it shows, not damage).
 */
function sane(d: any): boolean {
  const t = d?.trigger
  const a = d?.action
  const hmOk = (v: unknown) => parseHm(v) != null
  const daysOk = (v: unknown) => Array.isArray(v) && v.length > 0 && v.every((x) => Number.isInteger(x) && x >= 1 && x <= 7)
  const trig =
    !!t &&
    typeof t === 'object' &&
    ((t.kind === 'time' && hmOk(t.at) && daysOk(t.days)) ||
      (t.kind === 'once' && Number.isFinite(t.at)) ||
      (t.kind === 'every' && [1, 2, 3, 4, 6, 8, 12].includes(t.hours) && hmOk(t.from) && (t.to == null || hmOk(t.to)) && daysOk(t.days)) ||
      (t.kind === 'reset' && isWindow(t.window) && (t.from == null || hmOk(t.from)) && (t.to == null || hmOk(t.to))) ||
      (t.kind === 'limit' && isWindow(t.window)) ||
      (t.kind === 'session' && (t.event === 'finished' || t.event === 'needs_you')) ||
      (t.kind === 'power' && (t.event === 'plugged_in' || (t.event === 'low' && Number.isInteger(t.below)))) ||
      (t.kind === 'webhook' && typeof t.tokenHash === 'string' && t.tokenHash.length === 64) ||
      (t.kind === 'cron' && typeof t.expr === 'string' && saneCron(t.expr)) ||
      (t.kind === 'file' && typeof t.path === 'string' && FILE_EVENTS.includes(t.event)) ||
      (t.kind === 'github' && typeof t.repoPath === 'string' && Array.isArray(t.events) && t.events.every((e: unknown) => GH_EVENTS.includes(e as GithubEvent))) ||
      (t.kind === 'hook' && HOOK_EVENTS.includes(t.event)))
  const act = Array.isArray(d.steps) ? d.steps.length >= 1 && d.steps.length <= MAX_STEPS && d.steps.every(saneStep) && (d.outputs == null || Array.isArray(d.outputs)) : saneStep({ ...a, id: FIRST_STEP_ID })
  return trig && act && typeof d.name === 'string'
}
function saneStep(a: any): boolean {
  return (
    !!a &&
    typeof a === 'object' &&
    typeof a.id === 'string' &&
    ((a.kind === 'start' && typeof a.projectPath === 'string' && typeof a.prompt === 'string' && typeof a.providerId === 'string' && Array.isArray(a.allowedTools)) ||
      (a.kind === 'continue' && typeof a.target === 'string' && typeof a.prompt === 'string') ||
      (a.kind === 'notify' && typeof a.text === 'string') ||
      (a.kind === 'ask' && typeof a.question === 'string' && Array.isArray(a.answers) && typeof a.default === 'string' && Number.isFinite(a.timeoutMin)) ||
      (a.kind === 'shell' && typeof a.projectPath === 'string' && typeof a.command === 'string' && Number.isFinite(a.timeoutSec)))
  )
}
function saneCron(expr: string): boolean {
  try {
    parseCron(expr)
    return true
  } catch {
    return false
  }
}

/**
 * Placeholders whose values come from another session or outside ({step:<id>} too): quoted as data
 * in a prompt (fill), never with No asking. "${payload}" counts too: fill fills it in a prompt (only
 * a shell command keeps "${…}" as the shell's own).
 */
const FROM_SESSION = FROM_OUTSIDE

// ── The engine ────────────────────────────────────────────────────────────
export class Automations {
  private io: AutoIO
  private s: Saved
  private runs = new Map<string, RunRec>()
  private notes: Note[] = []
  private startedAt: number
  private firstTick = true
  private ticking: Promise<void> | null = null
  private again = false
  private runsDirty = false
  private runsSavedAt = 0
  /** What clients last heard, to send only what changed. */
  private sent = new Map<string, string>()
  private sentCounts = ''
  // Event triggers' memory (not kept: events aren't caught up).
  private lastState = new Map<string, string>()
  private finishing = new Map<string, number>()
  private agentAsks = new Map<string, number>()
  private seenAsks = new Set<string>()
  private sessionFired = new Map<string, number>()
  /** [key]: what needs you, `a:<approval id>` or `m:<menu id>` (a finish: ""). */
  private events: { event: 'finished' | 'needs_you'; sessionId: string; ask: string | null; at: number; key: string }[] = []
  /**
   * Session triggers with `after`: a needs-you that fires its automation only if the session still
   * needs you at [due] (its approval or menu not answered meanwhile). Not kept: events aren't caught up.
   */
  private waits: { automationId: string; sessionId: string; key: string; ask: string | null; due: number }[] = []
  private prevBattery: Battery | null | undefined = undefined
  private loopTold = new Set<string>()
  /** The problem each automation's last note was about (tellOnce); gone once it gets past it. */
  private told = new Map<string, string>()
  /**
   * When each session was last seen at work. A run's session can start working (or a continue be
   * taken) before the call that starts it returns, so its first "working" isn't missed.
   */
  private workedAt = new Map<string, number>()
  private holding = new Set<string>()
  /** Each automation's next due time as last worked out, and what it was worked out from. */
  private nextCache = new Map<string, { key: string; at: number | null }>()
  /** Runs changed since clients last heard (an ended run otherwise never changes). */
  private touched = new Set<string>()
  /** Webhook outputs' secrets, by output id (automation-secrets.json). */
  private secrets: Record<string, OutputSecret> = {}
  /** A webhook trigger's token the last save made: its answer shows it once. */
  private madeToken: string | null = null
  /** Shell steps at work, by run. */
  private shells = new Map<string, ShellRun>()
  /** File watches running, by automation: what they watch, and how to stop them. */
  private watchers = new Map<string, { key: string; stop: () => void }>()
  /** A file watch's changes since its last quiet moment, by automation (path → event), and when it went quiet. */
  private fileChanges = new Map<string, { paths: Map<string, FileEvent>; more: number; at: number }>()
  /** GitHub watches looking now, and those whose last look found gh missing or signed out. */
  private ghLooking = new Set<string>()
  private ghBad = new Set<string>()
  /**
   * Runs waiting on the daemon (a session starting, a prompt being typed): the timer's pass leaves
   * them alone meanwhile, so a step isn't followed half begun, nor a prompt typed twice.
   */
  private inFlight = new Set<string>()
  /** Claude Code hook events not yet handed to the automations listening. */
  private hookEvents: { event: HookEvent; sessionId: string; tool: string | null; line: string; at: number }[] = []
  /** Each webhook trigger's fires in the last minute (FIRES_PER_MIN), and when "too often" was last logged. */
  private fires = new Map<string, number[]>()
  private fireTold = new Map<string, number>()

  constructor(io: AutoIO) {
    this.io = io
    this.startedAt = io.now()
    this.s = this.loadState()
    this.loadRuns()
  }

  // ── Store ───────────────────────────────────────────────────────────────
  private loadState(): Saved {
    const raw = (this.io.load().state ?? {}) as Partial<Saved>
    const list = Array.isArray(raw.automations) ? raw.automations.filter((d) => d && typeof d.id === 'string' && d.trigger && d.action) : []
    return {
      version: 1,
      paused: raw.paused === true,
      maxRunning: Number.isInteger(raw.maxRunning) && raw.maxRunning! > 0 ? raw.maxRunning! : MAX_RUNNING,
      // Kept before paidOk was: one that names a paid provider itself was saved with confirmPaid.
      // One that doesn't read as an automation stays, off, so its card says so (the others run on).
      automations: list.map((d) => {
        const ok = sane(d)
        if (!ok) log('automation', d.id, 'kept in a shape that does not read: turned off')
        const now = this.io.now()
        const num = (v: unknown, or: number | null) => (typeof v === 'number' && Number.isFinite(v) ? v : or)
        // Kept before steps: its action is its one step, its `then` its outputs.
        const derived = !Array.isArray(d.steps) && ok ? stepOfAction(d.action) : null
        return {
          ...d,
          steps: derived ? [derived.step] : Array.isArray(d.steps) ? d.steps : [],
          outputs: derived ? derived.outputs : Array.isArray(d.outputs) ? d.outputs : [],
          budget: d.budget && typeof d.budget === 'object' ? d.budget : null,
          name: typeof d.name === 'string' ? d.name : 'Automation',
          enabled: ok && d.enabled === true,
          armedAt: num(d.armedAt, now)!,
          firedAt: num(d.firedAt, null),
          createdAt: num(d.createdAt, now)!,
          updatedAt: num(d.updatedAt, now)!,
          capDay: d.capDay ?? null,
          modeProblem: d.modeProblem === true,
          lowArmed: d.lowArmed !== false,
          paidOk: d.paidOk === true || (ok && this.namesPaid(derived ? [derived.step] : (d.steps as Step[]))),
          ...(ok ? {} : { bad: true }),
        }
      }),
      windows: raw.windows && typeof raw.windows === 'object' ? raw.windows : {},
      limits: Array.isArray(raw.limits) ? raw.limits.slice(-6) : [],
      limitStopped: raw.limitStopped && typeof raw.limitStopped === 'object' ? raw.limitStopped : {},
      toldFor: typeof raw.toldFor === 'string' ? raw.toldFor : null,
    }
  }
  private loadRuns(): void {
    const raw = (this.io.load().runs ?? {}) as { runs?: RunRec[]; notes?: Note[] }
    for (const r of Array.isArray(raw.runs) ? raw.runs : []) {
      if (!r || typeof r.id !== 'string' || !r.f || !r.spec) continue
      // Kept before steps: its action as its one step; an ended one shows no steps (as before).
      const old = !Array.isArray(r.spec.steps) && r.spec.action
      const derived = old ? stepOfAction(r.spec.action!) : null
      const spec: Spec = derived ? { ...r.spec, steps: [derived.step], outputs: derived.outputs, budget: null } : { ...r.spec, outputs: r.spec.outputs ?? [], budget: r.spec.budget ?? null }
      const steps = Array.isArray(r.steps) ? r.steps : derived && isActive(r) ? [{ ...newRunStep(derived.step), state: r.state === 'queued' ? 'pending' : (r.state as RunStep['state']), startedAt: r.startedAt, sessionIds: [...r.sessionIds] }] : []
      this.runs.set(r.id, {
        ...r,
        spec,
        steps,
        step: Number.isInteger(r.step) ? r.step : 0,
        ask: r.ask ?? null,
        outputs: Array.isArray(r.outputs) ? r.outputs : [],
        retryFrom: r.retryFrom ?? null,
        budgetUsed: r.budgetUsed ?? null,
        fired: { ...r.fired, detail: r.fired?.detail ?? null },
        retryOf: r.retryOf ?? null,
      })
    }
    this.notes = (Array.isArray(raw.notes) ? raw.notes : []).filter((n) => n && this.io.now() - n.at < NOTES_KEEP_MS).map((n) => ({ ...n, ask: n.ask ?? null }))
    const sec = this.io.loadSecrets?.()
    this.secrets = sec && typeof sec === 'object' ? { ...(sec as Record<string, OutputSecret>) } : {}
  }
  private save(): void {
    this.io.save(this.s)
  }
  /** Runs changed: written soon; [r], an ended one, is told to clients again. */
  private dirtyRuns(r?: RunRec): void {
    this.runsDirty = true
    if (r) this.touched.add(r.id)
  }
  /** Runs are written at most once a second. */
  private flushRuns(force = false): void {
    if (!this.runsDirty) return
    const now = this.io.now()
    if (!force && now - this.runsSavedAt < 1000) {
      this.io.wake(1000 - (now - this.runsSavedAt))
      return
    }
    this.prune()
    this.io.saveRuns({ version: 1, runs: [...this.runs.values()], notes: this.notes })
    this.runsDirty = false
    this.runsSavedAt = now
  }
  /** Write everything now (tests, and the daemon's way out). */
  flush(): void {
    this.save()
    this.flushRuns(true)
  }
  /**
   * The newest 50 runs per automation, none older than 90 days, none of a deleted one (but one whose
   * terminal is still to be closed stays until it is).
   */
  private prune(): void {
    const now = this.io.now()
    const ids = new Set(this.s.automations.map((d) => d.id))
    const by = new Map<string, RunRec[]>()
    for (const r of this.runs.values()) {
      if ((!ids.has(r.automationId) && r.f.tidyAt == null) || (!isActive(r) && now - r.fired.at > RUNS_MAX_AGE_MS)) {
        this.runs.delete(r.id)
        continue
      }
      by.set(r.automationId, [...(by.get(r.automationId) ?? []), r])
    }
    for (const list of by.values()) {
      list.sort((a, b) => b.fired.at - a.fired.at)
      for (const r of list.slice(RUNS_KEPT)) if (!isActive(r)) this.runs.delete(r.id)
    }
    this.notes = this.notes.filter((n) => now - n.at < NOTES_KEEP_MS).slice(-40)
  }

  // ── Views ───────────────────────────────────────────────────────────────
  private def(id: string): Def {
    const d = this.s.automations.find((x) => x.id === id)
    if (!d) throw new AutomationError(404, 'no_automation', 'That automation was deleted.')
    return d
  }
  private runsOf(id: string): RunRec[] {
    return [...this.runs.values()].filter((r) => r.automationId === id).sort((a, b) => b.fired.at - a.fired.at || (b.id < a.id ? -1 : 1))
  }
  private noBattery(): boolean {
    return this.io.battery() === null && this.io.now() - this.startedAt > 60_000
  }
  /** One of its steps names a provider other than the built-in one itself (a start's, or a continue's "carry on there"). */
  private namesPaid(steps: Step[]): boolean {
    return steps.some((a) => {
      const p = a.kind === 'start' ? a.providerId : a.kind === 'continue' ? a.providerId : null
      return !!p && this.costs(p)
    })
  }
  /** The first provider that costs money one of its session steps would run on, or null. */
  private paidViaSteps(steps: Step[], fired: string | null = null): string | null {
    for (const st of steps) {
      const a = asAction(st)
      const via = a ? this.paidVia(a, fired) : null
      if (via) return via
    }
    return null
  }
  /**
   * Running on provider [id] costs money: every provider but the subscription, unless the owner said
   * it costs nothing (a model on this computer). One that's gone counts as costing money.
   */
  private costs(id: string): boolean {
    const k = this.io.providerKind(id)
    if (k === 'anthropic') return false
    return !(k && this.io.providerPaid?.(id) === false)
  }
  /** Why the folder [path] can't be used: not there, or outside the projects folder. */
  private folderGone(path: string): 'no_project' | 'outside_projects' {
    return this.io.outside?.(path) ? 'outside_projects' : 'no_project'
  }
  /**
   * The provider that costs money it would run on, or null: the one it names, else the one the
   * session it continues is on ([fired]: the session that fired it). "Every working session" and
   * "every one a limit stopped" leave out sessions on a paid provider unless it's paid (continueTargets).
   */
  private paidVia(a: Action, fired: string | null = null): string | null {
    if (a.kind !== 'start' && a.kind !== 'continue') return null
    const p = this.providerFor(a, fired)
    return this.costs(p) ? p : null
  }
  private problemOf(d: Def): Problem {
    if (d.bad) return 'bad_definition'
    const t = d.trigger
    if (t.kind === 'power' && this.noBattery()) return 'no_battery'
    if (t.kind === 'file' && !this.io.project(t.path)) return this.folderGone(t.path)
    if (t.kind === 'github' && !this.io.project(t.repoPath)) return this.folderGone(t.repoPath)
    if (t.kind === 'github' && this.ghBad.has(d.id)) return 'no_gh'
    if (t.kind === 'hook' && this.io.hooksInstalled?.() === false) return 'no_hooks'
    for (const a of d.steps) {
      if ((a.kind === 'start' || a.kind === 'shell') && !this.io.project(a.projectPath)) return this.folderGone(a.projectPath)
      if (a.kind === 'continue' && !['trigger', 'working', 'limited'].includes(a.target) && !a.target.startsWith('step:') && !this.io.session(a.target)) return 'no_session'
      const p = a.kind === 'start' ? a.providerId : a.kind === 'continue' ? a.providerId : null
      if (p && !this.io.providerKind(p)) return 'no_provider'
      if (a.kind === 'start' && a.model && this.io.modelKnown?.(a.providerId, a.model) === false) return 'no_model'
    }
    if (d.outputs.some((o) => o.kind === 'webhook' && !this.secrets[`${d.id}/${o.id}`])) return 'output_lost'
    if (d.modeProblem) return 'mode_unavailable'
    return null
  }
  /** A trigger as clients see it: a webhook's token never, its fire address and command instead. */
  private triggerView(id: string, t: KeptTrigger): Trigger {
    // A session trigger always says its wait (null: at once), one kept from before it too.
    if (t.kind === 'session') return { ...t, after: t.after ?? null }
    if (t.kind !== 'webhook') return t
    const info = this.io.fireInfo?.(id) ?? { url: `/local/automations/${id}/fire`, command: `hypernova automation fire ${id}` }
    return { kind: 'webhook', last4: t.last4, createdAt: t.createdAt, url: info.url, command: info.command }
  }
  /**
   * The next due time, as the card shows it: none when off, paused or not on a clock. Worked out
   * again only when the minute, the zone or what it's worked out from changes (it's asked for on
   * every status read and every change).
   */
  private nextAt(d: Def): number | null {
    if (d.bad || !d.enabled || this.s.paused || !isTimeTrigger(d.trigger)) return null
    const now = this.io.now()
    const tz = this.io.tz()
    const key = `${Math.floor(now / MIN_MS)}|${tz}|${d.armedAt}|${d.firedFor}|${JSON.stringify(d.trigger)}`
    const hit = this.nextCache.get(d.id)
    if (hit?.key === key) return hit.at
    let at: number | null = null
    try {
      let next = nextOccurrence(d.trigger, Math.max(now, d.armedAt), tz)
      if (next && next.key === d.firedFor) next = nextOccurrence(d.trigger, next.at, tz)
      at = next?.at ?? null
    } catch (e) {
      log('automation', d.id, 'next time failed', (e as Error).message)
    }
    this.nextCache.set(d.id, { key, at })
    return at
  }
  view(d: Def): Automation {
    const runs = this.runsOf(d.id)
    return {
      id: d.id,
      name: d.name,
      enabled: d.enabled,
      onlyOnce: d.onlyOnce,
      trigger: this.triggerView(d.id, d.trigger),
      // What was kept and doesn't read is shown as it was kept.
      action: d.bad || !d.steps.length ? d.action : mirrorAction(d.steps, d.outputs),
      steps: d.steps.map((x) => ({ ...x })),
      outputs: d.outputs.map((x) => ({ ...x })),
      budget: d.budget ? { ...d.budget } : null,
      template: d.template,
      createdAt: d.createdAt,
      updatedAt: d.updatedAt,
      nextAt: this.nextAt(d),
      lastRun: runs[0] ? this.runView(runs[0]) : null,
      runCount: runs.length,
      paid: !!this.paidViaSteps(d.steps),
      problem: this.problemOf(d),
    }
  }
  runView(r: RunRec): Run {
    const { spec: _s, ctx: _c, f: _f, ...run } = r
    return {
      ...run,
      sessionIds: [...run.sessionIds],
      skippedTools: [...run.skippedTools],
      retryOf: run.retryOf ?? null,
      steps: run.steps.map((x) => ({ ...x, sessionIds: [...x.sessionIds] })),
      ask: run.ask ? { ...run.ask, answers: [...run.ask.answers] } : null,
      outputs: run.outputs.map((x) => ({ ...x })),
    }
  }
  /** What this daemon can do of workflows: each needs what it runs through. */
  features(): Feature[] {
    const io = this.io
    const can: Record<Feature, boolean> = {
      steps: true,
      ask: true,
      budget: true,
      'step.output': true,
      'trigger.after': true,
      'trigger.webhook': true,
      'trigger.cron': true,
      'trigger.hook': true,
      shell: !!io.shell,
      'trigger.file': !!io.watch,
      'trigger.github': !!(io.gh && io.ghReady && io.githubRepo),
      'output.webhook': !!(io.post && io.loadSecrets && io.saveSecrets),
      'output.file': !!(io.append && io.checkFile),
    }
    return FEATURES.filter((f) => can[f])
  }
  private offers(f: Feature): boolean {
    return this.features().includes(f)
  }
  list(): Automation[] {
    return this.s.automations.map((d) => this.view(d))
  }
  get paused(): boolean {
    return this.s.paused
  }
  private counts(): { running: number; queued: number } {
    let running = 0
    let queued = 0
    for (const r of this.runs.values()) {
      if (r.state === 'running' || r.state === 'needs_you') running++
      else if (r.state === 'queued') queued++
    }
    return { running, queued }
  }
  /** `Snapshot.automations`. */
  snapshot() {
    const now = this.io.now()
    return {
      features: this.features(),
      paused: this.s.paused,
      list: this.list(),
      ...this.counts(),
      notes: this.notes.filter((n) => now - n.at < NOTES_KEEP_MS).sort((a, b) => b.at - a.at).slice(0, 20),
      // Whether the battery triggers can work here: null while it isn't known yet (the first minute).
      hasBattery: this.io.battery() !== null ? true : this.noBattery() ? false : null,
    }
  }
  /** `/local/status`'s `automations`: the Mac popover's one line. */
  status() {
    let next: { id: string; name: string; at: number } | null = null
    for (const d of this.s.automations) {
      const at = this.nextAt(d)
      if (at != null && (!next || at < next.at)) next = { id: d.id, name: d.name, at }
    }
    const running = [...this.runs.values()]
      .filter((r) => r.state === 'running' || r.state === 'needs_you')
      .map((r) => ({ id: r.automationId, name: r.name, sessionId: r.sessionIds[0] ?? null, startedAt: r.startedAt }))
    return { paused: this.s.paused, count: this.s.automations.length, next, running }
  }

  /** Tell clients what changed: automations whose card would change, runs, the counts. */
  private announce(): void {
    const live = new Set<string>()
    for (const d of this.s.automations) {
      const v = this.view(d)
      const key = `a:${d.id}`
      live.add(key)
      const s = JSON.stringify(v)
      if (this.sent.get(key) !== s) {
        this.sent.set(key, s)
        this.io.send({ t: 'automation', automation: v })
      }
    }
    for (const r of this.runs.values()) {
      const key = `r:${r.id}`
      live.add(key)
      // An ended run already told about doesn't change unless something touched it.
      if (!isActive(r) && this.sent.has(key) && !this.touched.has(r.id)) continue
      const s = JSON.stringify(this.runView(r))
      if (this.sent.get(key) !== s) {
        this.sent.set(key, s)
        this.io.send({ t: 'run', run: this.runView(r) })
      }
    }
    this.touched.clear()
    for (const key of [...this.sent.keys()]) {
      if (live.has(key)) continue
      this.sent.delete(key)
      if (key.startsWith('a:')) this.io.send({ t: 'automation.removed', id: key.slice(2) })
    }
    const c = { paused: this.s.paused, ...this.counts() }
    const cs = JSON.stringify(c)
    if (cs !== this.sentCounts) {
      this.sentCounts = cs
      this.io.send({ t: 'automations', ...c })
    }
  }
  /** The Mac stays awake while a run works or needs you (Stay awake's While working). */
  private hold(): void {
    const want = new Set([...this.runs.values()].filter((r) => r.state === 'running' || r.state === 'needs_you').map((r) => r.id))
    for (const id of want) if (!this.holding.has(id)) this.io.keepAwake(`automation:${id}`, true)
    for (const id of this.holding) if (!want.has(id)) this.io.keepAwake(`automation:${id}`, false)
    this.holding = want
  }
  /** After any change: kept, told, held, and the timer set to the next thing to do. */
  private settle(): void {
    this.flushRuns()
    this.announce()
    this.hold()
    this.io.wake(this.nextWake())
  }

  // ── Validation ──────────────────────────────────────────────────────────
  /** A trigger as sent ([before]: the one it replaces), checked and made whole. Its errors name `trigger.…`. */
  private trigger(v: any, now: number, enabled: boolean, before: KeptTrigger | null): KeptTrigger {
    const F = (f?: string) => (f ? `trigger.${f}` : 'trigger')
    const not = (what: string) => bad('bad_trigger', `This Mac can’t watch for ${what} yet: update Hypernova on it.`, F())
    switch (v?.kind) {
      case 'time':
        return { kind: 'time', at: hm(v.at, 'bad_time', F('at')), days: days(v.days, F('days')) }
      case 'once': {
        if (typeof v.at !== 'number' || !Number.isFinite(v.at) || v.at % MIN_MS) throw bad('bad_time', 'Pick a day and a time.', F('at'))
        // A once that already ran keeps its time when saved again off (renamed, say).
        const kept = before?.kind === 'once' && before.at === v.at && !enabled
        if (v.at <= now && !kept) throw bad('past_time', 'Pick a time in the future.', F('at'))
        return { kind: 'once', at: v.at }
      }
      case 'every':
        if (![1, 2, 3, 4, 6, 8, 12].includes(v.hours)) throw bad('bad_trigger', 'Every 1, 2, 3, 4, 6, 8 or 12 hours.', F('hours'))
        return { kind: 'every', hours: v.hours, from: hm(v.from, 'bad_time', F('from')), to: optHm(v.to, F('to')), days: days(v.days, F('days')) }
      case 'reset': {
        if (!isWindow(v.window)) throw bad('bad_trigger', 'Pick the 5-hour or the weekly limit.', F('window'))
        const from = optHm(v.from, F('from'))
        const to = optHm(v.to, F('to'))
        if ((from == null) !== (to == null)) throw bad('bad_time', 'Pick both ends of the time window, or neither.', F(from == null ? 'from' : 'to'))
        return { kind: 'reset', window: v.window, afterLimit: v.afterLimit !== false, from, to }
      }
      case 'limit':
        if (!isWindow(v.window)) throw bad('bad_trigger', 'Pick the 5-hour or the weekly limit.', F('window'))
        return { kind: 'limit', window: v.window }
      case 'session':
      case 'hook': {
        if (v.kind === 'session' && v.event !== 'finished' && v.event !== 'needs_you') throw bad('bad_trigger', 'A session finishing or needing you.', F('event'))
        if (v.kind === 'hook' && !this.offers('trigger.hook')) throw not('Claude Code’s events')
        if (v.kind === 'hook' && !HOOK_EVENTS.includes(v.event)) throw bad('bad_trigger', 'Pick a Claude Code event: Stop, StopFailure, PostToolUseFailure, PreCompact or SessionEnd.', F('event'))
        const from = v.from === 'automated' || v.from === 'any' ? v.from : v.from == null || v.from === 'mine' ? 'mine' : null
        if (!from) throw bad('bad_trigger', 'Mine, automated or any sessions.', F('from'))
        let projectPath: string | null = null
        if (v.projectPath != null && v.projectPath !== '') {
          projectPath = this.io.project(str(v.projectPath))
          if (!projectPath) throw this.folderError(str(v.projectPath), F('projectPath'))
        }
        if (v.kind === 'session') {
          // Absent (a client that doesn't know it): the one it had stays; null: at once.
          const raw = v.after === undefined ? (before?.kind === 'session' && before.event === v.event ? (before.after ?? null) : null) : v.after
          let after: number | null = null
          if (raw != null) {
            if (!this.offers('trigger.after')) throw not('a session still needing you after a while')
            if (v.event !== 'needs_you') throw bad('bad_trigger', 'Only “needs you” can wait: a finished session is finished.', F('after'))
            if (!Number.isInteger(raw) || raw < 1 || raw > SESSION_AFTER_MAX_MIN) throw bad('bad_trigger', `From 1 to ${SESSION_AFTER_MAX_MIN} minutes.`, F('after'))
            after = raw
          }
          return { kind: 'session', event: v.event, projectPath, from, after }
        }
        let tool: string | null = null
        if (v.tool != null && v.tool !== '') {
          if (v.event !== 'PostToolUseFailure' || !/^[A-Za-z][\w-]{0,63}$/.test(str(v.tool))) throw bad('bad_trigger', 'A tool’s name, like Bash, and only for a failed tool call.', F('tool'))
          tool = str(v.tool)
        }
        return { kind: 'hook', event: v.event, tool, projectPath, from }
      }
      case 'power':
        if (this.noBattery()) throw bad('no_battery', 'This Mac has no battery.', F())
        if (v.event === 'plugged_in') return { kind: 'power', event: 'plugged_in' }
        if (v.event === 'low') {
          const below = v.below == null ? 20 : v.below
          if (!Number.isInteger(below) || below < 5 || below > 50 || below % 5) throw bad('bad_trigger', 'A battery level from 5% to 50%.', F('below'))
          return { kind: 'power', event: 'low', below }
        }
        throw bad('bad_trigger', 'Plugged in, or the battery low.', F('event'))
      case 'webhook':
        if (!this.offers('trigger.webhook')) throw not('a webhook')
        // Its token stays across saves; a new one is made when it's saved (create, replace).
        if (before?.kind === 'webhook') return { ...before }
        return { kind: 'webhook', tokenHash: '', last4: '', createdAt: now }
      case 'cron': {
        if (!this.offers('trigger.cron')) throw not('a cron time')
        const expr = str(v.expr).trim().replace(/\s+/g, ' ')
        try {
          parseCron(expr)
        } catch (e) {
          throw bad('bad_cron', e instanceof CronError ? e.message : 'That isn’t a cron expression.', F('expr'))
        }
        if (tooOften(expr, now, this.io.tz())) throw bad('too_often', 'At most every 5 minutes.', F('expr'))
        return { kind: 'cron', expr }
      }
      case 'file': {
        if (!this.offers('trigger.file')) throw not('files')
        const path = this.io.project(str(v.path))
        if (!path) throw this.folderError(str(v.path), F('path'))
        const glob = v.glob == null || v.glob === '' ? null : str(v.glob).trim()
        const reads = (x: string) => {
          try {
            globMatch(x, 'x')
            return true
          } catch {
            return false
          }
        }
        if (glob != null && (!glob || glob.length > FILE_GLOB_MAX || glob.startsWith('/') || glob.split('/').includes('..') || (glob.match(/\*\*/g) ?? []).length > 4 || !reads(glob))) throw bad('bad_trigger', 'A pattern inside the folder, like *.md or docs/**.', F('glob'))
        if (!FILE_EVENTS.includes(v.event)) throw bad('bad_trigger', 'A file created, changed or deleted.', F('event'))
        return { kind: 'file', path, glob, event: v.event }
      }
      case 'github': {
        if (!this.offers('trigger.github')) throw not('GitHub')
        const repoPath = this.io.project(str(v.repoPath))
        if (!repoPath) throw this.folderError(str(v.repoPath), F('repoPath'))
        const events: unknown[] = Array.isArray(v.events) ? v.events : []
        if (!events.length || new Set(events).size !== events.length || !events.every((e) => GH_EVENTS.includes(e as GithubEvent))) throw bad('bad_trigger', 'Pick what to watch: pull requests opened, review comments, failing checks.', F('events'))
        const every = v.every == null ? 10 : v.every
        if (!(GITHUB_EVERY as readonly number[]).includes(every)) throw bad('bad_trigger', 'Every 5, 10 or 15 minutes.', F('every'))
        return { kind: 'github', repoPath, events: GH_EVENTS.filter((e) => events.includes(e)), every }
      }
    }
    throw bad('bad_trigger', 'Pick when it runs.', F())
  }
  /**
   * An action (or a start, continue or notify step, sent with `then` none) checked; [at] is
   * its field's path ("action", "steps[2]"), [stepIds] the earlier start and continue steps a
   * continue may name as `step:<id>`.
   */
  private action(v: any, trigger: KeptTrigger, b: any, at = 'action', stepIds: Set<string> | null = null): Action {
    const then = (x: unknown): Then => {
      if (x == null) return 'none'
      if (x !== 'none' && x !== 'notify') throw bad('bad_action', 'Notify, or nothing.', `${at}.then`)
      return x
    }
    const provider = (x: unknown): string => {
      const id = typeof x === 'string' && x ? x : 'anthropic'
      if (!this.io.providerKind(id)) throw bad('no_provider', 'That provider isn’t set up on this Mac.', `${at}.providerId`)
      return id
    }
    const prompt = (x: unknown, empty = false): string => {
      const p = str(x).trim()
      if ((!p && !empty) || p.length > 8000) throw bad('no_prompt', p ? 'That prompt is too long (8000 characters at most).' : 'Write what it should do.', `${at}.prompt`)
      return p
    }
    switch (v?.kind) {
      case 'start': {
        const projectPath = this.io.project(str(v.projectPath))
        if (!projectPath) throw this.folderError(str(v.projectPath), `${at}.projectPath`)
        const providerId = provider(v.providerId)
        if (providerId === CODEX) {
          // Codex in its own sandbox, asking nobody: read only (the default) or allowed to edit its folder.
          const mode = v.permissionMode ?? 'read-only'
          if (!CODEX_MODES.includes(mode)) throw bad('bad_mode', 'Codex runs read only or can edit its folder.', `${at}.permissionMode`)
          if (Array.isArray(v.allowedTools) && v.allowedTools.length) throw bad('bad_rule', 'Codex takes no allow rules: its sandbox decides.', `${at}.allowedTools`)
          const model = v.model == null || v.model === '' ? null : str(v.model).trim().slice(0, 100) || null
          return { kind: 'start', projectPath, prompt: prompt(v.prompt), providerId, model, permissionMode: mode, allowedTools: [], then: then(v.then) }
        }
        const mode = v.permissionMode ?? 'dontAsk'
        if (!['dontAsk', 'default', 'acceptEdits', 'auto', 'bypassPermissions'].includes(mode)) throw bad('bad_mode', 'That isn’t a permission mode for an automation.', `${at}.permissionMode`)
        if (mode === 'bypassPermissions' && b.confirmBypass !== true) throw bad('confirm_bypass', 'Confirm “No asking” with your fingerprint or screen lock.', `${at}.permissionMode`)
        const text = prompt(v.prompt)
        // Words from another session or from outside ({result}, {prev}, {payload}…; it may have read
        // a web page) never go into a session that asks nothing before it acts.
        if (mode === 'bypassPermissions' && FROM_SESSION.test(text)) throw bad('bad_placeholder', 'Words from another session or from outside ({result}, {ask}, {session}, {prev}, {output}, {payload}, {step:…}) can’t go into a “No asking” prompt.', `${at}.prompt`)
        const rules = v.allowedTools == null ? [] : v.allowedTools
        if (!Array.isArray(rules) || rules.length > 20) throw bad('bad_rule', 'Up to 20 rules.', `${at}.allowedTools`)
        // Auto and No asking go by no allow rules: none are kept to stay active unseen.
        const kept =
          mode === 'auto' || mode === 'bypassPermissions'
            ? []
            : [
                ...new Set(
                  rules.map((r, j) => {
                    try {
                      return checkRule(r)
                    } catch (e) {
                      if (e instanceof AutomationError) e.extra = { field: `${at}.allowedTools[${j}]` }
                      throw e
                    }
                  }),
                ),
              ]
        const model = v.model == null || v.model === '' ? null : str(v.model).trim().slice(0, 100) || null
        return { kind: 'start', projectPath, prompt: text, providerId, model, permissionMode: mode, allowedTools: kept, then: then(v.then) }
      }
      case 'continue': {
        const target = str(v.target)
        if (target === 'trigger' && !['session', 'hook'].includes(trigger.kind)) throw bad('bad_target', '“The session that fired it” works only when a session fires it. Pick every working session, or a session trigger.', `${at}.target`)
        if (target.startsWith('step:')) {
          if (!stepIds?.has(target.slice(5))) throw bad('bad_target', 'Pick an earlier step that starts or continues a session.', `${at}.target`)
        } else if (!['trigger', 'working', 'limited'].includes(target) && !(target && this.io.session(target))) throw bad('no_session', 'That session no longer exists.', `${at}.target`)
        const providerId = v.providerId == null || v.providerId === '' ? null : provider(v.providerId)
        // "Switch provider at the limit" with nowhere to switch to would only carry on where it stopped.
        if (b.template === 'switch_provider' && !providerId) throw bad('no_provider', 'Add another provider first, then pick it here.', `${at}.providerId`)
        return { kind: 'continue', target, prompt: prompt(v.prompt, target === 'limited'), providerId, then: then(v.then) }
      }
      case 'notify': {
        const text = str(v.text).trim()
        if (!text || text.length > 500) throw bad('no_prompt', 'Write the notification (500 characters at most).', `${at}.text`)
        return { kind: 'notify', text }
      }
    }
    throw bad('bad_action', 'Pick what it does.', at === 'action' ? 'action' : `${at}.kind`)
  }
  /** 400 `outside_projects` for a folder outside the projects folder, else `no_project`. */
  private folderError(path: string, field?: string): AutomationError {
    return this.folderGone(path) === 'outside_projects'
      ? bad('outside_projects', 'That folder is outside your projects folder. Pick a project.', field)
      : bad('no_project', 'That project folder doesn’t exist.', field)
  }
  /** Its steps, checked in order (`steps[i]…`). */
  private steps(list: unknown, trigger: KeptTrigger, b: any): Step[] {
    if (!Array.isArray(list) || list.length < 1 || list.length > MAX_STEPS) throw bad('bad_steps', `From 1 to ${MAX_STEPS} steps.`, 'steps')
    if (list.length > 1 && !this.offers('steps')) throw bad('bad_steps', 'This Mac runs one step: update Hypernova on it for more.', 'steps')
    const out: Step[] = []
    const ids = new Set<string>()
    for (let i = 0; i < list.length; i++) {
      const v: any = list[i]
      const at = `steps[${i}]`
      if (!v || typeof v !== 'object') throw bad('bad_step', 'Pick what this step does.', at)
      const id = v.id == null || v.id === '' ? `st_${hex(4)}` : str(v.id)
      if (!STEP_ID.test(id) || ids.has(id)) throw bad('bad_step', 'Each step needs an id of its own (letters, digits, _ and -).', `${at}.id`)
      const guard = this.guard(v.if, i, out, at)
      let st: Step
      switch (v.kind) {
        case 'start':
        case 'continue':
        case 'notify': {
          const earlier = this.offers('steps') ? new Set(out.filter((x) => SESSION_STEPS.has(x.kind)).map((x) => x.id)) : null
          const { then: _t, ...a } = this.action({ ...v, then: 'none' }, trigger, b, at, earlier) as Action & { then?: Then }
          st = { id, if: guard, ...a } as Step
          break
        }
        case 'ask':
          if (!this.offers('ask')) throw bad('bad_step', 'This Mac can’t ask yet: update Hypernova on it.', `${at}.kind`)
          st = { id, if: guard, ...this.askStep(v, at) }
          break
        case 'shell':
          if (!this.offers('shell')) throw bad('bad_step', 'This Mac can’t run commands for automations yet: update Hypernova on it.', `${at}.kind`)
          st = { id, if: guard, ...this.shellStep(v, at) }
          break
        default:
          throw bad('bad_step', 'Pick what this step does.', `${at}.kind`)
      }
      // {step:<id>} names an earlier step that has a result (a notification has none).
      const field = st.kind === 'start' || st.kind === 'continue' ? 'prompt' : st.kind === 'ask' ? 'question' : st.kind === 'shell' ? 'command' : 'text'
      this.checkStepRefs((st as Record<string, any>)[field] ?? '', out, `${at}.${field}`)
      ids.add(id)
      out.push(st)
    }
    return out
  }
  /** Each `{step:<id>}` in [text] is one of [earlier] that gives a result: else 400 `bad_placeholder` on [field]. */
  private checkStepRefs(text: string, earlier: Step[], field: string): void {
    for (const id of stepRefs(text)) {
      const st = earlier.find((x) => x.id === id)
      if (!st || TELLS.has(st.kind)) throw bad('bad_placeholder', `{step:${id}} isn’t a step before this one that has a result: pick a start, continue, ask or shell step above it.`, field)
    }
  }
  /** A step's guard (`steps[i].if`): never on the first step. */
  private guard(v: any, i: number, earlier: Step[], at: string): Guard | null {
    if (v == null) return null
    const F = `${at}.if`
    if (i === 0) throw bad('bad_guard', 'The first step runs whenever it fires: it takes no “only if”.', F)
    if (!this.offers('steps')) throw bad('bad_guard', 'This Mac can’t run steps only if: update Hypernova on it.', F)
    if (typeof v !== 'object' || !['ok', 'failed', 'quiet', 'not_quiet', 'contains', 'answer'].includes(v.on)) throw bad('bad_guard', 'Pick when this step runs.', `${F}.on`)
    if (v.on === 'contains') {
      const text = str(v.text).trim()
      if (!text || text.length > GUARD_TEXT_MAX) throw bad('bad_guard', 'Write what its output should contain (200 characters at most).', `${F}.text`)
      return { on: 'contains', text }
    }
    if (v.on === 'answer') {
      const ask = [...earlier].reverse().find((x) => x.kind === 'ask')
      if (!ask || ask.kind !== 'ask') throw bad('bad_guard', '“The answer was …” needs an ask step before it.', F)
      const text = ask.answers.find((x) => x.toLowerCase() === str(v.text).trim().toLowerCase())
      if (!text) throw bad('bad_guard', `Pick one of its answers: ${ask.answers.join(', ')}.`, `${F}.text`)
      return { on: 'answer', text }
    }
    return { on: v.on }
  }
  private askStep(v: any, at: string): { kind: 'ask'; question: string; answers: string[]; default: string; timeoutMin: number } {
    const question = str(v.question).trim()
    if (!question || question.length > ASK_QUESTION_MAX) throw bad('bad_question', 'Write the question (200 characters at most).', `${at}.question`)
    const raw: unknown = v.answers == null ? [...ASK_DEFAULT_ANSWERS] : v.answers
    const answers = Array.isArray(raw) ? raw.map((x) => str(x).trim()) : []
    if (answers.length < ASK_ANSWERS_MIN || answers.length > ASK_ANSWERS_MAX || answers.some((x) => !x || x.length > ASK_ANSWER_MAX) || new Set(answers.map((x) => x.toLowerCase())).size !== answers.length)
      throw bad('bad_answers', 'Two or three different answers, 24 characters at most each.', `${at}.answers`)
    const def = v.default == null || v.default === '' ? answers[answers.length - 1]! : answers.find((x) => x.toLowerCase() === str(v.default).trim().toLowerCase())
    if (!def) throw bad('bad_answers', 'The answer taken when nobody answers must be one of its answers.', `${at}.default`)
    const timeoutMin = v.timeoutMin == null ? ASK_TIMEOUT_MIN : v.timeoutMin
    if (!Number.isInteger(timeoutMin) || timeoutMin < 1 || timeoutMin > ASK_TIMEOUT_MAX_MIN) throw bad('bad_timeout', 'Wait from 1 to 60 minutes for an answer.', `${at}.timeoutMin`)
    return { kind: 'ask', question, answers, default: def, timeoutMin }
  }
  private shellStep(v: any, at: string): { kind: 'shell'; projectPath: string; command: string; timeoutSec: number } {
    const projectPath = this.io.project(str(v.projectPath))
    if (!projectPath) throw this.folderError(str(v.projectPath), `${at}.projectPath`)
    const command = str(v.command).trim()
    if (!command || command.length > SHELL_COMMAND_MAX || command.includes('\0')) throw bad('no_command', 'Write the command (2000 characters at most).', `${at}.command`)
    // Words from outside never become part of a command line: a script reads them from its environment.
    const outside = placeholdersIn(command).find((k) => !(SHELL_PLACEHOLDERS as readonly string[]).includes(k))
    if (outside) {
      const env = outside.startsWith('step:') ? stepEnv(outside.slice(5)) : (SHELL_ENV as Record<string, string>)[outside]
      throw bad('bad_placeholder', env ? `{${outside}} can’t go into a command: use "$${env}" instead.` : `{${outside}} can’t go into a command.`, `${at}.command`)
    }
    const timeoutSec = v.timeoutSec == null ? SHELL_TIMEOUT_SEC : v.timeoutSec
    if (!Number.isInteger(timeoutSec) || timeoutSec < 1 || timeoutSec > SHELL_TIMEOUT_MAX_SEC) throw bad('bad_timeout', 'Give it from 1 to 600 seconds.', `${at}.timeoutSec`)
    return { kind: 'shell', projectPath, command, timeoutSec }
  }
  /** Its outputs (`outputs[i]…`): webhooks' secrets come back apart, to keep in the secrets file. */
  private outputs(list: unknown, before: Def | null): { outputs: Output[]; secrets: Record<string, OutputSecret> } {
    if (!Array.isArray(list) || list.length > MAX_OUTPUTS) throw bad('bad_outputs', `Up to ${MAX_OUTPUTS} outputs.`, 'outputs')
    const outputs: Output[] = []
    const secrets: Record<string, OutputSecret> = {}
    const ids = new Set<string>()
    list.forEach((v: any, i) => {
      const at = `outputs[${i}]`
      if (!v || typeof v !== 'object') throw bad('bad_output', 'Pick where the result goes.', at)
      const id = v.id == null || v.id === '' ? `ou_${hex(4)}` : str(v.id)
      if (!STEP_ID.test(id) || ids.has(id)) throw bad('bad_output', 'Each output needs an id of its own.', `${at}.id`)
      ids.add(id)
      const text = v.text == null || str(v.text).trim() === '' ? null : str(v.text).trim()
      if (v.text != null && typeof v.text !== 'string') throw bad('bad_text', 'Write what it says.', `${at}.text`)
      if (text != null && text.length > OUTPUT_TEXT_MAX) throw bad('bad_text', 'At most 500 characters.', `${at}.text`)
      switch (v.kind) {
        case 'notify':
          outputs.push({ id, text, kind: 'notify' })
          return
        case 'webhook': {
          if (!this.offers('output.webhook')) throw bad('bad_output', 'This Mac can’t send webhooks yet: update Hypernova on it.', `${at}.kind`)
          if (!PRESETS.includes(v.preset)) throw bad('bad_output', 'Pick ntfy, Slack, Discord, Telegram or a plain POST.', `${at}.preset`)
          const sent = checkSecret(v.preset, v, at, this.io.webhookPrivateHosts?.() ?? [])
          // Saved again without its secret: the one kept for it, if it's the same kind of webhook.
          const was = before?.outputs.find((o) => o.id === id)
          const kept = !sent && before && was?.kind === 'webhook' && was.preset === v.preset ? (this.secrets[`${before.id}/${id}`] ?? null) : null
          const secret = sent ?? kept
          if (!secret) throw v.preset === 'telegram' ? bad('bad_bot_token', 'Paste the bot’s token and the chat id.', `${at}.botToken`) : bad('bad_url', 'Paste the webhook’s address.', `${at}.url`)
          if (sent) secrets[id] = sent
          outputs.push({ id, text, kind: 'webhook', preset: v.preset, ...secretHint(v.preset, secret) })
          return
        }
        case 'file': {
          if (!this.offers('output.file') || !this.io.checkFile) throw bad('bad_output', 'This Mac can’t write to files for automations yet: update Hypernova on it.', `${at}.kind`)
          const r = this.io.checkFile(str(v.path).trim())
          if ('code' in r) throw bad(r.code, FILE_WORDS[r.code]!, `${at}.path`)
          outputs.push({ id, text, kind: 'file', path: r.path })
          return
        }
      }
      throw bad('bad_output', 'Pick where the result goes.', `${at}.kind`)
    })
    return { outputs, secrets }
  }
  private budget(v: any): Budget | null {
    if (v == null) return null
    if (!this.offers('budget')) throw bad('bad_budget', 'This Mac can’t keep a budget yet: update Hypernova on it.', 'budget')
    if (typeof v !== 'object') throw bad('bad_budget', 'A budget in minutes, or in points of the 5-hour window.', 'budget')
    const minutes = v.minutes == null ? null : v.minutes
    if (minutes != null && (!Number.isInteger(minutes) || minutes < 1 || minutes > BUDGET_MINUTES_MAX)) throw bad('bad_budget', 'From 1 to 720 minutes.', 'budget.minutes')
    const windowPct = v.windowPct == null ? null : v.windowPct
    if (windowPct != null && (!Number.isInteger(windowPct) || windowPct < 1 || windowPct > BUDGET_PCT_MAX)) throw bad('bad_budget', 'From 1 to 100 points.', 'budget.windowPct')
    return minutes == null && windowPct == null ? null : { minutes, windowPct }
  }
  /** An `AutomationInput` checked and made whole; [before]: the automation it replaces. */
  private input(b: any, before: Def | null): Pick<Def, 'name' | 'enabled' | 'onlyOnce' | 'trigger' | 'action' | 'steps' | 'outputs' | 'budget' | 'template' | 'paidOk'> & { secrets: Record<string, OutputSecret> } {
    if (!b || typeof b !== 'object') throw bad('bad_action', 'Send an automation.')
    const name = str(b.name).trim()
    if (!name || name.length > 60) throw bad('bad_name', 'Give it a name (60 characters at most).', 'name')
    const enabled = b.enabled !== false
    const trigger = this.trigger(b.trigger, this.io.now(), enabled, before?.trigger ?? null)
    let steps: Step[]
    let outputs: Output[]
    let budget: Budget | null
    let secrets: Record<string, OutputSecret> = {}
    if (b.steps !== undefined) {
      // The whole new shape.
      steps = this.steps(b.steps, trigger, b)
      const o = this.outputs(b.outputs ?? [], before)
      outputs = o.outputs
      secrets = o.secrets
      // An output's {step:<id>}: any of its steps that has a result.
      outputs.forEach((x, i) => this.checkStepRefs(x.text ?? '', steps, `outputs[${i}].text`))
      budget = this.budget(b.budget)
    } else if (b.action?.kind === 'workflow') {
      // A client without steps saving what it can't edit: the steps stay as they are.
      if (!before) throw bad('bad_action', 'Send its steps: an automation with steps is made in a newer Hypernova.', 'action')
      steps = before.steps
      outputs = before.outputs
      budget = before.budget
    } else {
      // A client without steps: its action is the one step, its `then` the outputs.
      if (before && !before.bad && !simple(before)) throw new AutomationError(409, 'has_steps', 'This automation has steps. Edit it in a newer Hypernova.')
      const a = this.action(b.action, trigger, b, 'action')
      // Its one step has none before it to name.
      if (a.kind === 'start' || a.kind === 'continue') this.checkStepRefs(a.prompt, [], 'action.prompt')
      else this.checkStepRefs(a.text, [], 'action.text')
      const keep = before?.steps[0]?.kind === a.kind ? before.steps[0].id : FIRST_STEP_ID
      const x = stepOfAction(a, keep)
      steps = [x.step]
      outputs = x.outputs
      budget = before?.budget ?? null
    }
    // A provider that costs money, also a continue of a session that runs on one. Agreed once, it
    // stays agreed while what it runs is unchanged (saved again unchanged, renamed).
    const via = this.paidViaSteps(steps)
    const agreed = b.confirmPaid === true || (!!before?.paidOk && JSON.stringify(before.steps) === JSON.stringify(steps))
    if (via && !agreed) {
      const i = steps.findIndex((st) => !!asAction(st) && !!this.paidVia(asAction(st)!))
      throw bad('confirm_paid', `This runs on ${this.io.providerName(via)}, which costs money. Confirm it to save.`, b.steps !== undefined ? `steps[${Math.max(0, i)}].providerId` : 'action.providerId')
    }
    // Commands run on the Mac: every save of one that has them is confirmed by the owner's hand.
    const sh = steps.findIndex((st) => st.kind === 'shell')
    if (sh >= 0 && b.confirmShell !== true) throw bad('confirm_shell', 'Confirm with your fingerprint or screen lock that it may run commands on the Mac.', `steps[${sh}].command`)
    // So is every save of one with a No asking start, also one whose steps are kept as they were
    // (an old client's {kind: "workflow"}): its trigger or name may be what changed.
    const nb = steps.findIndex((st) => st.kind === 'start' && st.permissionMode === 'bypassPermissions')
    if (nb >= 0 && b.confirmBypass !== true) throw bad('confirm_bypass', 'Confirm “No asking” with your fingerprint or screen lock.', b.steps !== undefined ? `steps[${nb}].permissionMode` : 'action')
    const template = b.template == null || b.template === '' ? null : str(b.template)
    if (template !== null && !/^[a-z][a-z0-9_]{0,31}$/.test(template)) throw bad('bad_template', 'That isn’t a template.', 'template')
    return { name, enabled, onlyOnce: trigger.kind === 'once' || b.onlyOnce === true, trigger, action: keptAction(steps, outputs), steps, outputs, budget, template, paidOk: agreed, secrets }
  }
  /**
   * Its start steps' models are on their providers' lists (as Claude Code or the catalogue gives
   * them; a "[1m]" suffix aside), when there's a list to go by: 400 `no_model`. Checked before a
   * save, apart from [input], since a list may need reading first. [before]: only changed ones.
   */
  private async checkModel(b: any, before: Def | null = null): Promise<void> {
    const list: any[] = Array.isArray(b?.steps) ? b.steps : [b?.action]
    for (let i = 0; i < list.length; i++) {
      const a = list[i]
      if (a?.kind !== 'start' || typeof a.model !== 'string' || !a.model.trim() || !this.io.models) continue
      // The model is checked only when it changed (a model that went stays a problem, not a refusal).
      if (before?.steps.some((st) => st.kind === 'start' && st.model === a.model)) continue
      const providerId = typeof a.providerId === 'string' && a.providerId ? a.providerId : 'anthropic'
      // The list read first if it's due; then the same rule as the card's problem (modelKnown).
      await this.io.models(providerId).catch(() => null)
      if (this.io.modelKnown?.(providerId, a.model) === false) throw bad('no_model', `${a.model.trim()} isn’t one of ${this.io.providerName(providerId)}’s models any more. Pick another.`, Array.isArray(b?.steps) ? `steps[${i}].model` : 'action.model')
    }
  }
  /** A GitHub watch needs gh signed in on the Mac and a GitHub remote (both read now). */
  private async checkGithub(b: any): Promise<void> {
    const t = b?.trigger
    if (t?.kind !== 'github' || !this.io.ghReady || !this.io.githubRepo) return
    const path = this.io.project(str(t.repoPath))
    if (!path) return
    if (!(await this.io.ghReady().catch(() => false))) throw bad('no_gh', 'Install gh on the Mac and sign in to it (gh auth login).', 'trigger.repoPath')
    if (!(await this.io.githubRepo(path).catch(() => false))) throw bad('not_github', 'That folder’s git remote isn’t on GitHub.', 'trigger.repoPath')
  }

  // ── Editing ─────────────────────────────────────────────────────────────
  /** Add one. A webhook trigger's token it made is [takeToken]'s, once. */
  create(b: unknown): Automation {
    if (this.s.automations.length >= MAX_AUTOMATIONS) throw bad('too_many', `${MAX_AUTOMATIONS} automations is the most.`)
    const now = this.io.now()
    const { secrets, ...x } = this.input(b, null)
    const d: Def = { id: `au_${hex(5)}`, ...x, createdAt: now, updatedAt: now, firedFor: null, firedAt: null, armedAt: now, resetFiredFor: null, limitFiredFor: null, lowArmed: true, lastPluggedAt: null, modeProblem: false, capDay: null }
    this.madeToken = this.mint(d)
    this.keepSecrets(d, secrets)
    this.s.automations.push(d)
    this.save()
    log('automation added', d.id, d.trigger.kind, d.steps.map((st) => st.kind).join('+'))
    this.settle()
    return this.view(d)
  }
  replace(id: string, b: unknown): Automation {
    const d = this.def(id)
    const now = this.io.now()
    const { secrets, ...x } = this.input(b, d)
    const moved = JSON.stringify(x.trigger) !== JSON.stringify(d.trigger)
    const switchedOn = x.enabled && !d.enabled
    Object.assign(d, x, { updatedAt: now, modeProblem: d.modeProblem && JSON.stringify(x.steps) === JSON.stringify(d.steps) })
    // Saved whole again: it reads as an automation now.
    delete d.bad
    // A new trigger starts afresh: nothing before now is due, and a reset, a limit or a watch fires anew.
    if (moved) Object.assign(d, { firedFor: null, firedAt: null, armedAt: now, resetFiredFor: null, limitFiredFor: null, lowArmed: true, ghSeen: null, ghLookAt: null })
    // Switched back on by a save: the time it was off isn't caught up (as with its switch).
    else if (switchedOn) d.armedAt = now
    this.madeToken = this.mint(d)
    this.keepSecrets(d, secrets)
    this.save()
    log('automation changed', id)
    this.settle()
    return this.view(d)
  }
  /** The token the last save made (a webhook trigger's), once: the save's answer shows it. */
  takeToken(): string | null {
    const t = this.madeToken
    this.madeToken = null
    return t
  }
  /** A webhook trigger without a token gets one (kept hashed): the token itself, or null. */
  private mint(d: Def, again = false): string | null {
    if (d.trigger.kind !== 'webhook' || (d.trigger.tokenHash && !again)) return null
    const token = `hnf_${hex(16)}`
    d.trigger = { kind: 'webhook', tokenHash: sha(token), last4: token.slice(-4), createdAt: this.io.now() }
    return token
  }
  /** `POST /v1/automations/{id}/token`: a new token for its webhook trigger; the old one stops at once. */
  newToken(id: string): { automation: Automation; token: string } {
    const d = this.def(id)
    if (d.trigger.kind !== 'webhook') throw new AutomationError(409, 'not_webhook', 'It isn’t fired by a webhook.')
    const token = this.mint(d, true)!
    d.updatedAt = this.io.now()
    this.save()
    log('automation', id, 'new fire token')
    this.settle()
    return { automation: this.view(d), token }
  }
  /** Webhook outputs' secrets: the new ones kept, those of outputs it no longer has dropped. */
  private keepSecrets(d: Def, sent: Record<string, OutputSecret>): void {
    const keep = new Set(d.outputs.filter((o) => o.kind === 'webhook').map((o) => `${d.id}/${o.id}`))
    let changed = false
    for (const [id, sec] of Object.entries(sent)) {
      this.secrets[`${d.id}/${id}`] = sec
      changed = true
    }
    for (const k of Object.keys(this.secrets)) {
      if (k.startsWith(`${d.id}/`) && !keep.has(k)) {
        delete this.secrets[k]
        changed = true
      }
    }
    if (changed) this.io.saveSecrets?.(this.secrets)
  }
  /**
   * Delete it. A run at work now is stopped first, as Stop would (its sessions get Esc and the Mac
   * is let go); its runs go, except one whose terminal is still to be tidied, until it is. Sessions
   * it started stay as normal ones (their badge comes from their record).
   */
  async remove(id: string): Promise<void> {
    this.def(id)
    for (const r of this.runsOf(id)) {
      if (!isActive(r)) continue
      if (r.f.started) for (const sid of r.sessionIds) await this.io.interrupt(sid).catch(() => {})
      this.end(r, 'stopped', 'by_user')
    }
    this.s.automations = this.s.automations.filter((d) => d.id !== id)
    this.nextCache.delete(id)
    this.keepSecrets({ id, outputs: [] } as unknown as Def, {})
    this.watchers.get(id)?.stop()
    this.watchers.delete(id)
    this.fileChanges.delete(id)
    this.fires.delete(id)
    this.fireTold.delete(id)
    for (const r of this.runsOf(id)) if (r.f.tidyAt == null) this.runs.delete(r.id)
    this.save()
    this.dirtyRuns()
    log('automation removed', id)
    await this.dequeue()
    this.settle()
  }
  setEnabled(id: string, on: unknown): Automation {
    if (typeof on !== 'boolean') throw bad('bad_enabled', 'Say enabled: true or false.')
    const d = this.def(id)
    if (on && d.trigger.kind === 'once' && d.trigger.at <= this.io.now()) throw bad('past_time', 'Pick a time in the future.')
    if (on !== d.enabled) {
      d.enabled = on
      // Off time is never caught up.
      if (on) d.armedAt = this.io.now()
      d.updatedAt = this.io.now()
      this.save()
    }
    this.settle()
    return this.view(d)
  }
  /**
   * Pause all, or resume. Paused, nothing starts by itself: runs waiting in line or for a reset that
   * haven't started end `skipped`, `paused` (Run now's own still go ahead); runs at work carry on.
   */
  setPaused(p: unknown): boolean {
    if (typeof p !== 'boolean') throw bad('bad_paused', 'Say paused: true or false.')
    if (p !== this.s.paused) {
      this.s.paused = p
      // Paused time is never caught up: resuming starts from the next due time.
      if (!p) for (const d of this.s.automations) d.armedAt = this.io.now()
      else for (const r of [...this.runs.values()]) if (this.held(r)) this.end(r, 'skipped', 'paused')
      this.save()
      log('automations', p ? 'paused' : 'resumed')
    }
    this.settle()
    return this.s.paused
  }
  /** A run Pause all holds back: in line or waiting for a reset, not started, not Run now's. */
  private held(r: RunRec): boolean {
    return (r.state === 'queued' || (r.state === 'waiting' && !r.f.started)) && r.fired.by !== 'manual'
  }
  /** A page of its runs, newest first, and how many it has kept in all (and how many of them were skipped). */
  runsPage(id: string, before: number | null, limit: number): Run[] {
    return this.runsList(id, before, limit).runs
  }
  runsList(id: string, before: number | null, limit: number): { runs: Run[]; total: number; skipped: number } {
    this.def(id)
    const all = this.runsOf(id)
    return {
      runs: all
        .filter((r) => before == null || r.fired.at < before)
        .slice(0, Math.min(100, Math.max(1, limit || 30)))
        .map((r) => this.runView(r)),
      total: all.length,
      skipped: all.filter((r) => r.state === 'skipped').length,
    }
  }
  async runNow(id: string): Promise<Run> {
    const d = this.def(id)
    const r = await this.fire(d, 'manual', this.io.now(), this.ctxFor(d, null, null), { manual: true })
    this.settle()
    if (!r) throw new AutomationError(409, 'running', 'It is already running.')
    return this.runView(r)
  }
  /**
   * Run an ended run again, as Run now would, with what it was fired with (the session that fired
   * it, its placeholders' values): 409 `active` while it still runs, `running` while another run
   * of it does; the new run names the one it retries.
   */
  /**
   * [fromStep]: Retry from a step. The automation's steps as they are now, from that one on; the
   * steps before it taken over from that run (state, result, sessions) with the values they gave
   * the placeholders. 400 `bad_step` (not a step of that run), 409 `steps_changed` (it, or one
   * before it, isn't in the automation any more).
   */
  async retry(runId: string, fromStep: unknown = null): Promise<Run> {
    const old = this.runs.get(runId)
    if (!old) throw new AutomationError(404, 'no_run', 'That run is gone.')
    if (isActive(old)) throw new AutomationError(409, 'active', 'That run is still going.')
    const d = this.def(old.automationId)
    let from: { index: number; steps: RunStep[]; ctx: Ctx } | null = null
    if (fromStep != null && fromStep !== '') {
      const at = old.steps.findIndex((x) => x.id === fromStep)
      if (typeof fromStep !== 'string' || at < 0) throw bad('bad_step', 'That isn’t a step of that run.', 'fromStep')
      const now = d.steps.findIndex((x) => x.id === fromStep)
      if (now < 0 || d.steps.slice(0, now).some((x, i) => old.steps[i]?.id !== x.id)) throw new AutomationError(409, 'steps_changed', 'Its steps changed since: run it again from the start.')
      from = { index: now, steps: old.steps.slice(0, now).map((x) => ({ ...x, sessionIds: [...x.sessionIds], reused: true })), ctx: old.f.ctxBefore?.[fromStep] ?? old.ctx }
    }
    const ctx = { ...(from?.ctx ?? old.ctx), time: this.ctxFor(d, null, null).time }
    const r = await this.fire(d, 'manual', this.io.now(), ctx, { manual: true, sessionId: old.fired.sessionId, retryOf: old.id, from, detail: old.fired.detail })
    this.settle()
    if (!r) throw new AutomationError(409, 'running', 'It is already running.')
    return this.runView(r)
  }
  async stopRun(runId: string): Promise<Run> {
    const r = this.runs.get(runId)
    if (!r) throw new AutomationError(404, 'no_run', 'That run is gone.')
    if (isActive(r)) {
      if (r.f.started) for (const sid of r.sessionIds) await this.io.interrupt(sid).catch(() => {})
      this.end(r, 'stopped', 'by_user')
      await this.dequeue()
    }
    this.settle()
    return this.runView(r)
  }

  // ── Firing ──────────────────────────────────────────────────────────────
  /** The placeholders' values when it fires ([payload]: what came in with it, 8 KB at most). */
  private ctxFor(d: Def, s: Session | null, ask: string | null, at = this.io.now(), payload: string | null = null): Ctx {
    const b = this.io.battery()
    const where = d.steps.find((st) => st.kind === 'start' || st.kind === 'shell') as { projectPath: string } | undefined
    return {
      project: s ? s.projectName : where ? basename(where.projectPath) : null,
      session: s ? s.title : null,
      result: s ? resultOf(this.io.lastReply(s.id)) : null,
      ask,
      battery: b ? `${b.percent}%` : null,
      time: clockText(at, this.io.tz()),
      prev: null,
      exit: null,
      output: null,
      answer: null,
      payload: payload ? payload.slice(0, PAYLOAD_MAX) : null,
    }
  }
  private newRun(d: Def, by: FiredBy, at: number, ctx: Ctx, o: FireOpts & { depth?: number }): RunRec {
    const steps = d.steps.map(newRunStep)
    const from = o.from ?? null
    if (from) for (let i = 0; i < from.index; i++) steps[i] = from.steps[i] ?? steps[i]!
    const r: RunRec = {
      id: `ru_${hex(5)}`,
      automationId: d.id,
      name: d.name,
      fired: { by, at, sessionId: o.sessionId ?? null, detail: o.detail ?? null },
      state: 'queued',
      reason: null,
      until: null,
      sessionIds: [],
      startedAt: null,
      endedAt: null,
      late: o.late ?? 0,
      missed: o.missed ?? 0,
      lateWhy: o.lateWhy ?? null,
      result: null,
      quiet: false,
      skippedTools: [],
      permissionMode: null,
      depth: o.depth ?? 0,
      retryOf: o.retryOf ?? null,
      steps,
      step: from?.index ?? 0,
      ask: null,
      outputs: [],
      retryFrom: from ? d.steps[from.index]!.id : null,
      budgetUsed: null,
      spec: { name: d.name, trigger: d.trigger, steps: d.steps, outputs: d.outputs, budget: d.budget, paidOk: d.paidOk },
      ctx,
      f: { started: false, queuedAt: null, regateAt: null, targets: [], seen: {}, doneAt: null, tidyAt: null, asked: null, slot: false },
    }
    this.runs.set(r.id, r)
    this.dirtyRuns()
    return r
  }
  /** A run that ended before it began (skipped or failed at a gate), kept for its row. */
  private record(d: Def, by: FiredBy, at: number, ctx: Ctx, state: RunState, reason: RunReason, o: FireOpts & { depth?: number } = {}): RunRec {
    const r = this.newRun(d, by, at, ctx, o)
    r.state = state
    r.reason = reason
    r.endedAt = this.io.now()
    this.unreached(r)
    log('automation run', r.id, d.id, state, reason)
    if (state === 'failed') this.noteFailure(r)
    return r
  }
  /** It does more than tell: a session, a question or a command (the full gates, 24 runs a day). */
  private works(d: Def): boolean {
    return d.steps.some((st) => !TELLS.has(st.kind))
  }
  /** The step a run is at, and its record. */
  private cur(r: RunRec): Step {
    return r.spec.steps[r.step] ?? r.spec.steps[0]!
  }
  private rstep(r: RunRec): RunStep {
    return (r.steps[r.step] ??= newRunStep(this.cur(r)))
  }
  /** The session step a run's account gate goes by: the one at [from] or the first after it. */
  private sessionStep(steps: Step[], from = 0): Action | null {
    for (const st of steps.slice(from)) if (SESSION_STEPS.has(st.kind)) return asAction(st)
    return null
  }

  /**
   * One firing of [d], through the gates in order. Returns the run, or null when nothing at all
   * happened (paused or off, its own session fired it, or Run now on one already running).
   */
  private async fire(d: Def, by: FiredBy, at: number, ctx: Ctx, o: FireOpts = {}): Promise<RunRec | null> {
    const now = this.io.now()
    const tz = this.io.tz()
    // 1. Paused, or off: nothing happens, nothing is recorded. Run now goes ahead anyway.
    if (!o.manual && (this.s.paused || !d.enabled)) return null
    // 2. Loops: its own sessions never fire it; a chain deeper than 2 stops. Its own are the ones it
    // started, and the ones a run of it is continuing or just did (their record names no automation).
    let depth = 0
    if (o.sessionId) {
      const auto = this.io.session(o.sessionId)?.automation ?? null
      if (auto?.id === d.id) return null
      const inRun = this.runIn(o.sessionId, now)
      if (inRun?.automationId === d.id) return null
      if (auto) depth = (this.runs.get(auto.runId)?.depth ?? 0) + 1
      if (inRun) depth = Math.max(depth, inRun.depth + 1)
      if (depth > MAX_DEPTH) {
        // Said once per automation and chain, not on every turn of the loop.
        const chain = `${d.id}|${auto?.runId ?? inRun?.id ?? o.sessionId}`
        if (this.loopTold.has(chain)) return null
        this.loopTold.add(chain)
        return this.record(d, by, at, ctx, 'skipped', 'loop', { ...o, depth })
      }
    }
    const runOpts = { ...o, depth }
    // 3. The daily cap: 24 runs a day (200 for one that only tells), said once that day.
    const today = localDay(now, tz)
    const ranToday = this.runsOf(d.id).filter((r) => r.state !== 'skipped' && localDay(r.fired.at, tz) === today).length
    if (ranToday >= (this.works(d) ? DAILY_CAP : DAILY_NOTES_CAP)) {
      if (o.manual) throw new AutomationError(409, 'daily_cap', `${DAILY_CAP} runs a day is the most.`)
      if (d.capDay === today) return null
      d.capDay = today
      this.save()
      return this.record(d, by, at, ctx, 'skipped', 'daily_cap', runOpts)
    }
    // Notify steps pass only those, and finish at once.
    if (!this.works(d)) {
      const r = this.newRun(d, by, at, ctx, runOpts)
      await this.begin(r)
      return r
    }
    // 4. Overlap: one run at a time.
    if (this.runsOf(d.id).some(isActive)) {
      if (o.manual) return null
      return this.record(d, by, at, ctx, 'skipped', 'overlap', runOpts)
    }
    // 5. What its first step works on is still there (a later step that's guarded may handle it
    // being gone, so then the step itself fails).
    const from = o.from?.index ?? 0
    const first = d.steps[from]!
    const gone = this.stepGone(first)
    if (gone && !d.steps.slice(from + 1).some((st) => st.if)) return this.record(d, by, at, ctx, 'failed', gone, runOpts)
    // A provider that costs money, which the owner didn't agree to (the session it continues moved there since).
    const via = d.paidOk ? null : this.paidViaSteps(d.steps.slice(from), o.sessionId ?? null)
    if (via) {
      const text = `It would run on ${this.io.providerName(via)}, which costs money. Save it again and agree to that.`
      if (o.manual) throw new AutomationError(409, 'confirm_paid', text)
      const r = this.record(d, by, at, ctx, 'skipped', 'provider', runOpts)
      this.tellOnce(r, `paid:${via}`, `It didn’t run: ${text.charAt(0).toLowerCase()}${text.slice(1)}`)
      return r
    }
    // What it works on is there and agreed to: a problem told about is over.
    if (!gone) this.told.delete(d.id)
    // 6. The account (or the provider) lets it run now, later today, or not: by its first session step.
    const sa = this.sessionStep(d.steps, from)
    const g = sa ? this.accountGate(this.providerFor(sa, o.sessionId ?? null), this.nextDue(d, now), now) : ({ kind: 'ok' } as const)
    if (g.kind === 'skip') {
      if (o.manual) throw new AutomationError(409, g.reason, g.text, g.until != null ? { until: g.until } : undefined)
      this.tell(g)
      return this.record(d, by, at, ctx, 'skipped', g.reason, runOpts)
    }
    const r = this.newRun(d, by, at, ctx, runOpts)
    if (g.kind === 'wait') {
      if (o.manual) {
        this.runs.delete(r.id)
        throw new AutomationError(409, 'limited', g.text, { until: g.until })
      }
      r.state = 'waiting'
      r.until = g.until
      r.f.regateAt = g.until + AFTER_RESET_MS
      log('automation run', r.id, d.id, 'waiting for the reset')
      return r
    }
    // 7. A slot.
    await this.slot(r)
    return r
  }
  /** The next time this one would be due again (time triggers), for "a reset before the next run". */
  private nextDue(d: Def, now: number): number | null {
    return isTimeTrigger(d.trigger) ? (nextOccurrence(d.trigger, now, this.io.tz())?.at ?? null) : null
  }
  /** The provider a run goes by: the one it names, else the session's it continues ([fired]: the one that fired it). */
  private providerFor(a: Action, fired: string | null = null): string {
    if (a.kind === 'start') return a.providerId
    if (a.kind === 'continue') {
      if (a.providerId) return a.providerId
      const sid = a.target === 'trigger' ? fired : a.target === 'working' || a.target === 'limited' || a.target.startsWith('step:') ? null : a.target
      if (sid) return this.io.session(sid)?.providerId ?? 'anthropic'
    }
    return 'anthropic'
  }
  /** The newest run working in session [sid] (one it started or continued), or done with it within the last minute. */
  private runIn(sid: string, now: number): RunRec | null {
    let found: RunRec | null = null
    for (const r of this.runs.values()) {
      if (!r.f.started || !r.sessionIds.includes(sid)) continue
      if (!isActive(r) && (r.endedAt == null || now - r.endedAt > SESSION_EVENT_GAP_MS)) continue
      if (!found || (r.startedAt ?? 0) > (found.startedAt ?? 0)) found = r
    }
    return found
  }
  private targetGone(a: Action): RunReason | null {
    if (a.kind === 'start') {
      if (!this.io.project(a.projectPath)) return this.folderGone(a.projectPath)
      if (!this.io.providerKind(a.providerId)) return 'no_provider'
      if (a.model && this.io.modelKnown?.(a.providerId, a.model) === false) return 'no_model'
    }
    if (a.kind === 'continue') {
      if (!['trigger', 'working', 'limited'].includes(a.target) && !a.target.startsWith('step:') && !this.io.session(a.target)) return 'no_session'
      if (a.providerId && !this.io.providerKind(a.providerId)) return 'no_provider'
    }
    return null
  }
  /** What a step works on is gone: its folder, session or provider. */
  private stepGone(st: Step): RunReason | null {
    if (st.kind === 'shell') return this.io.project(st.projectPath) ? null : this.folderGone(st.projectPath)
    const a = asAction(st)
    return a ? this.targetGone(a) : null
  }

  /**
   * Gate 6. The built-in provider goes by the account; another by its own status. A limit (also
   * one Claude would carry on past on usage credits: automations never spend them) makes a run
   * wait when its reset is known and comes before both the next due time and 6 hours from now.
   */
  private accountGate(providerId: string, nextAt: number | null, now: number): { kind: 'ok' } | { kind: 'wait'; until: number; text: string } | { kind: 'skip'; reason: RunReason; text: string; why: string; episode: string; until: number | null } {
    // [text] answers Run now; [why] ends the note "Automations are skipping runs: …".
    const skip = (reason: RunReason, text: string, why: string, episode: string, until: number | null = null) => ({ kind: 'skip' as const, reason, text, why, episode, until })
    const wait = (reset: number | null, episode: string): ReturnType<Automations['accountGate']> => {
      const until = reset ? ` until ${this.when(reset)}` : ''
      if (reset != null && reset > now && reset - now <= WAIT_MAX_MS && (nextAt == null || reset < nextAt)) return { kind: 'wait', until: reset, text: `Your usage limit is reached${until}.` }
      return skip('limited', `Your usage limit is reached${until}.`, `your usage limit is reached${until}.`, episode, reset != null && reset > now ? reset : null)
    }
    if (providerId === CODEX) {
      // Codex goes by its own account and limits (codex/account.ts).
      const g = this.io.codexGate?.() ?? { state: 'off' as const, resetsAt: null }
      if (g.state === 'off') return skip('no_provider', 'Codex isn’t set up on this Mac.', 'Codex isn’t set up on this Mac.', 'codex:off')
      if (g.state === 'signed_out') return skip('signed_out', 'Codex is signed out on the Mac.', 'Codex is signed out on the Mac.', 'codex:signed_out')
      if (g.state === 'limited') return wait(g.resetsAt, `codex:limited:${g.resetsAt ?? 0}`)
      return { kind: 'ok' }
    }
    if (this.io.providerKind(providerId) !== 'anthropic') {
      // One deleted since: never the subscription in its place.
      if (!this.io.providerKind(providerId)) return skip('no_provider', 'That provider was removed.', 'that provider was removed.', `${providerId}:removed`)
      const st = this.io.providerStatus(providerId)
      if (!st || st.state === 'down') return { kind: 'ok' }
      const episode = `${providerId}:${st.state}:${st.since}`
      if (st.state === 'limited') return wait(st.resetsAt, episode)
      const name = this.io.providerName(providerId)
      const said = st.state === 'bad_key' ? `${name} refused its key.` : `${name} is out of credits.`
      return skip('provider', said, said, episode)
    }
    const a = this.io.account()
    const episode = `${a.state}:${a.since ?? 0}`
    switch (a.state) {
      case 'limited': {
        const l = a.limit
        // Its reset has passed and the account hasn't caught up yet (it looks every 30 s).
        if (l?.resetsAt != null && l.resetsAt <= now) return { kind: 'ok' }
        // A model's own limit ("Opus limit") leaves the others: the session says so if it hits it.
        // Not while Claude carries on past it on usage credits: those are paid, so it waits or skips.
        if (l?.window === 'model' && !l.usingCredits) return { kind: 'ok' }
        return wait(l?.resetsAt ?? null, episode)
      }
      case 'logged_out':
        return skip('signed_out', 'Claude Code is signed out on the Mac.', 'Claude Code is signed out on the Mac.', episode)
      case 'no_subscription':
        return skip('no_plan', 'There’s no Claude plan on the Mac.', 'there’s no Claude plan on the Mac.', episode)
      case 'on_hold':
        return skip('on_hold', 'The Claude account is on hold.', 'the Claude account is on hold.', episode)
      case 'no_credit':
        return skip('no_credit', 'You’re out of usage credits.', 'you’re out of usage credits.', episode)
    }
    return { kind: 'ok' }
  }
  /**
   * One note per problem of an automation (its project folder gone, a provider it wasn't agreed to
   * run on), not one on every firing: said again only once it ran in between ([told]).
   */
  private tellOnce(r: RunRec, problem: string, text: string): void {
    if (this.told.get(r.automationId) === problem) return
    this.told.set(r.automationId, problem)
    this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text, to: 'phone' })
  }
  /** One note per account episode, for all automations together (a provider removed is the run's own failure, not an episode). */
  private tell(g: { why: string; episode: string; reason?: RunReason }): void {
    if (g.reason === 'no_provider' || this.s.toldFor === g.episode) return
    this.s.toldFor = g.episode
    this.save()
    this.addNote({ automationId: null, runId: null, name: 'Automations', text: `Automations are skipping runs: ${g.why}`, to: 'phone' })
  }
  /** "Fri 10:00", or "10:00" today. */
  private when(t: number): string {
    const tz = this.io.tz()
    const p = localParts(t, tz)
    return localDay(t, tz) === localDay(this.io.now(), tz) ? clockText(t, tz) : `${DAY_NAMES[p.dow]} ${clockText(t, tz)}`
  }

  /**
   * Gate 7: start now if a slot is free, else wait in line (at most 10). A run with a start or a
   * shell step holds a slot across its steps; one that only continues takes none unless it resumes
   * an ended session.
   */
  private async slot(r: RunRec): Promise<void> {
    r.f.slot = r.spec.steps.some((st) => st.kind === 'start' || st.kind === 'shell') || (this.cur(r).kind === 'continue' && this.continueTargets(r).some((id) => this.io.session(id)?.state === 'ended'))
    if (r.f.slot && this.slotsTaken() >= this.s.maxRunning) {
      const queued = [...this.runs.values()].filter((x) => x.state === 'queued' && x.id !== r.id).length
      if (queued >= MAX_QUEUED) {
        this.end(r, 'skipped', 'queue_full')
        return
      }
      r.state = 'queued'
      r.f.queuedAt = this.io.now()
      log('automation run', r.id, r.automationId, 'queued')
      this.dirtyRuns()
      return
    }
    await this.begin(r)
  }
  /** Runs that hold a slot: running, needing you, or waiting on a limit mid-run. */
  private slotsTaken(): number {
    return [...this.runs.values()].filter((r) => r.f.started && r.f.slot && (r.state === 'running' || r.state === 'needs_you' || r.state === 'waiting')).length
  }
  /** The oldest queued runs take the slots that came free. */
  private async dequeue(): Promise<void> {
    // Paused, only Run now's own leave the line (setPaused ended the others).
    const queued = [...this.runs.values()].filter((r) => r.state === 'queued' && !(this.s.paused && this.held(r))).sort((a, b) => (a.f.queuedAt ?? 0) - (b.f.queuedAt ?? 0))
    for (const r of queued) {
      if (this.slotsTaken() >= this.s.maxRunning) break
      await this.regate(r)
    }
  }
  /**
   * A queued or waiting run's turn: what it works on and the account looked at again, then it
   * starts. One at work that waited for a reset before a later session step goes on with that step.
   */
  private async regate(r: RunRec): Promise<void> {
    if (this.s.paused && this.held(r)) return this.end(r, 'skipped', 'paused')
    const d = this.s.automations.find((x) => x.id === r.automationId)
    const now = this.io.now()
    const st = this.cur(r)
    if (!r.f.started) {
      const gone = this.stepGone(st)
      if (gone && !r.spec.steps.slice(r.step + 1).some((x) => x.if)) return this.end(r, 'failed', gone)
      // The session it continues may have moved to a paid provider while it waited.
      if (!this.paidAllowed(r) && this.paidViaSteps(r.spec.steps.slice(r.step), r.fired.sessionId)) return this.end(r, 'skipped', 'provider')
    }
    const sa = r.f.started ? asAction(st) : this.sessionStep(r.spec.steps, r.step)
    const g = sa ? this.accountGate(this.providerFor(sa, r.fired.sessionId), d ? this.nextDue(d, now) : null, now) : ({ kind: 'ok' } as const)
    if (g.kind === 'skip') {
      this.tell(g)
      return this.end(r, 'skipped', g.reason)
    }
    // Limited again after waiting for the reset: skipped, not waited for twice.
    if (g.kind === 'wait') return this.end(r, 'skipped', 'limited')
    r.until = null
    r.f.regateAt = null
    if (r.f.started) {
      this.setState(r, 'running', null)
      await this.runStep(r, true)
    } else if (r.state === 'waiting') await this.slot(r)
    else await this.begin(r)
  }

  /** It starts: its first step (or the one a Retry begins at). */
  private async begin(r: RunRec): Promise<void> {
    const now = this.io.now()
    const d = this.s.automations.find((x) => x.id === r.automationId)
    r.f.started = true
    r.f.queuedAt = null
    r.startedAt = now
    r.state = 'running'
    // Its budget counts from now (the 5-hour window as it is now).
    if (r.spec.budget) {
      r.f.workMs = 0
      r.f.workSince = null
      r.f.pct0 = this.fiveHourOf(r)
      r.budgetUsed = { minutes: 0, windowPct: r.f.pct0 == null ? null : 0 }
    }
    // "Only the next time" is used up by a run it fired, not by Run now.
    if (d && r.fired.by !== 'manual') this.turnOff(d)
    this.dirtyRuns()
    this.workTick(r, now)
    await this.runStep(r, true)
  }
  /** The step that ended let the next one begin: it does (its gates first, for a session step). */
  private async advance(r: RunRec): Promise<void> {
    if (!r.f.next || !isActive(r)) return
    r.f.next = false
    await this.runStep(r, false)
  }
  /**
   * The step at r.step begins. [gated]: its gates were looked at already (it fired, or waited for
   * a reset); else a session step goes through them now.
   */
  private async runStep(r: RunRec, gated: boolean): Promise<void> {
    const now = this.io.now()
    const st = this.cur(r)
    const rs = this.rstep(r)
    r.f.ctxBefore = { ...(r.f.ctxBefore ?? {}), [st.id]: { ...r.ctx } }
    r.f.targets = []
    rs.startedAt = now
    const a = asAction(st)
    if (!gated && (SESSION_STEPS.has(st.kind) || st.kind === 'shell')) {
      const gone = this.stepGone(st)
      if (gone) {
        this.stepDone(r, 'failed', gone)
        return this.advance(r)
      }
      if (a) {
        if (!this.paidAllowed(r) && this.paidVia(a, r.fired.sessionId)) return this.end(r, 'skipped', 'provider')
        const g = this.accountGate(this.providerFor(a, r.fired.sessionId), null, now)
        if (g.kind === 'skip') {
          this.tell(g)
          return this.end(r, 'skipped', g.reason)
        }
        if (g.kind === 'wait') {
          rs.state = 'waiting'
          this.setState(r, 'waiting', g.until)
          r.f.regateAt = g.until + AFTER_RESET_MS
          log('automation run', r.id, 'step', st.id, 'waiting for the reset')
          return
        }
      }
    }
    rs.state = 'running'
    this.setState(r, 'running', null)
    this.dirtyRuns()
    switch (st.kind) {
      case 'start': {
        const cwd = this.io.project(st.projectPath)
        if (!cwd) {
          this.stepDone(r, 'failed', this.folderGone(st.projectPath))
          break
        }
        const p = localParts(now, this.io.tz())
        r.f.asked = st.permissionMode
        const since = this.io.now()
        this.inFlight.add(r.id)
        try {
          const sid = await this.io.start({
            cwd,
            prompt: fill(st.prompt, r.ctx, { quote: true }),
            providerId: st.providerId,
            model: st.model,
            permissionMode: st.permissionMode,
            allowedTools: st.allowedTools,
            appendSystemPrompt: appendedPrompt(r.spec.name),
            name: `${r.spec.name}, ${DAY_NAMES[p.dow]} ${p.d} ${MONTHS[p.mo]}`,
            automation: { id: r.automationId, name: r.spec.name, runId: r.id },
          })
          rs.sessionIds = [sid]
          if (!r.sessionIds.includes(sid)) r.sessionIds.push(sid)
          r.f.seen[sid] = { worked: false, idleAt: null, since, finished: false }
          log('automation run', r.id, r.automationId, 'started', sid.slice(0, 8))
          // Stopped while its session was starting: that session gets its Esc too, not left to run on unwatched.
          if (r.state !== 'running') await this.io.interrupt(sid).catch(() => {})
        } catch (e) {
          log('automation run', r.id, 'start failed', (e as Error).message)
          if (isActive(r)) this.stepDone(r, 'failed', 'start_failed')
        } finally {
          this.inFlight.delete(r.id)
        }
        break
      }
      case 'continue': {
        const ids = this.continueTargets(r)
        if (!ids.length) {
          const left = this.paidLeftOut(r)
          rs.result =
            left ? left
            : st.target === 'limited' ? 'No sessions were waiting on a limit.'
            : st.target.startsWith('step:') ? 'That step had no session to continue.'
            : st.target !== 'trigger' ? 'No sessions were working.'
            : r.fired.sessionId ? 'That session runs on a provider that costs money, so it wasn’t continued.'
            : 'No session fired it, so there was none to continue.'
          this.stepDone(r, 'done', null)
          break
        }
        r.f.targets = ids.map((id) => ({ id, state: 'pending', nextAt: now, until: now + RETRY_FOR_MS, moved: false, code: null }))
        await this.sendPending(r)
        break
      }
      case 'notify':
        await this.sendNotes(r)
        this.stepDone(r, 'done', null)
        break
      case 'ask':
        this.beginAsk(r)
        break
      case 'shell':
        this.beginShell(r)
        break
    }
    await this.advance(r)
  }
  /** The owner agreed this run may cost money (when it was saved; kept from before paidOk: when it named a paid provider). */
  private paidAllowed(r: RunRec): boolean {
    return r.spec.paidOk ?? this.namesPaid(r.spec.steps)
  }
  /**
   * "Every working session" and "every one a limit stopped" leave out sessions on a provider that
   * costs money unless that was agreed: how many it left out, in a sentence ("1 session on
   * OpenRouter was left alone: it costs money."), or null.
   */
  private paidLeftOut(r: RunRec): string | null {
    const a = asAction(this.cur(r))
    if (a?.kind !== 'continue' || (a.target !== 'working' && a.target !== 'limited') || this.paidAllowed(r) || a.providerId) return null
    const left = this.continueTargets(r, true)
    if (!left.length) return null
    const names = [...new Set(left.map((id) => this.io.providerName(this.io.session(id)?.providerId ?? '')))]
    const n = left.length
    return `${n} session${n === 1 ? '' : 's'} on ${names.length === 1 ? names[0] : 'other providers'} ${n === 1 ? 'was' : 'were'} left alone: ${n === 1 ? 'it costs' : 'they cost'} money.`
  }
  /** The sessions the continue step at work goes to, as of now ([paidOnly]: the ones it leaves out because they'd cost money). */
  private continueTargets(r: RunRec, paidOnly = false): string[] {
    const a = asAction(this.cur(r))
    if (a?.kind !== 'continue') return []
    // A session on a provider that costs money (or moved to one) only when the owner agreed to that.
    const paidOk = this.paidAllowed(r)
    const costs = (s: Session) => this.costs(a.providerId ?? s.providerId)
    const free = (s: Session) => (paidOnly ? !paidOk && costs(s) : paidOk || !costs(s))
    if (a.target === 'trigger') {
      const s = r.fired.sessionId ? this.io.session(r.fired.sessionId) : null
      // One that's gone is still its target: it fails no_session.
      return r.fired.sessionId && (!s || free(s)) ? [r.fired.sessionId] : []
    }
    if (a.target.startsWith('step:')) {
      // The sessions an earlier step of this run worked in.
      const ids = r.steps.find((x) => x.id === a.target.slice(5))?.sessionIds ?? []
      return ids.filter((id) => {
        const s = this.io.session(id)
        return !s || free(s)
      })
    }
    // An automation's own sessions are never its targets.
    const others = (s: Session) => s.automation?.id !== r.automationId && s.state !== 'ended' && free(s)
    if (a.target === 'working') return this.io.sessions().filter((s) => others(s) && (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0)).map((s) => s.id)
    if (a.target === 'limited') {
      return this.io.sessions()
        .filter((s) => others(s) && s.state !== 'working' && s.state !== 'starting')
        .filter((s) => {
          const stopped = this.s.limitStopped[s.id] ?? (s.blocked?.kind === 'limit' ? { at: 0, armed: s.blocked.resume?.state === 'armed' } : null)
          // Claude Code carries on what it armed by itself, unless the run moves it elsewhere now.
          return !!stopped && (!stopped.armed || !!a.providerId)
        })
        .map((s) => s.id)
    }
    return [a.target]
  }
  /** Type the continue into each target whose turn it is; a busy one tries again later, for 10 minutes. */
  private async sendPending(r: RunRec): Promise<void> {
    const a = asAction(this.cur(r))
    if (a?.kind !== 'continue' || this.inFlight.has(r.id)) return
    this.inFlight.add(r.id)
    try {
      await this.sendTargets(r, a)
    } finally {
      this.inFlight.delete(r.id)
    }
    this.dirtyRuns()
    this.followRun(r)
  }
  private async sendTargets(r: RunRec, a: Extract<Action, { kind: 'continue' }>): Promise<void> {
    const rs = this.rstep(r)
    const now = this.io.now()
    const afterLimit = a.target === 'limited'
    const text = fill(a.prompt, r.ctx, { quote: true }) || CONTINUE_TEXT.manual
    const paidOk = this.paidAllowed(r)
    for (const t of r.f.targets) {
      if (t.state !== 'pending' || t.nextAt > now) continue
      const s = this.io.session(t.id)
      if (!s) {
        t.state = 'failed'
        t.code = 'no_session'
        continue
      }
      // Each session by the provider it carries on with: one that costs money only if agreed to,
      // and that provider's own limit, credits or key (the run's gate looked at one provider only).
      // A session in No asking acts on what it reads without asking: words from another session or
      // from outside never go into one, as they never go into a No asking start.
      if (s.permissionMode === 'bypassPermissions' && FROM_SESSION.test(a.prompt)) {
        t.state = 'failed'
        t.code = 'bypass'
        continue
      }
      const via = a.providerId ?? s.providerId
      if (!paidOk && this.costs(via)) {
        t.state = 'failed'
        t.code = 'paid'
        continue
      }
      const g = this.accountGate(via, null, now)
      if (g.kind !== 'ok') {
        if (g.kind === 'skip') this.tell(g)
        t.state = 'failed'
        t.code = g.kind === 'skip' ? g.reason : 'limited'
        continue
      }
      try {
        if (a.providerId && !t.moved && this.io.session(t.id)?.providerId !== a.providerId) {
          t.id = await this.io.move(t.id, a.providerId)
          t.moved = true
        }
        t.moved = true
        const since = this.io.now()
        const sent = await this.io.prompt(t.id, text, afterLimit)
        if (sent.ok) {
          t.state = 'sent'
          if (!rs.sessionIds.includes(sent.sessionId)) rs.sessionIds.push(sent.sessionId)
          if (!r.sessionIds.includes(sent.sessionId)) r.sessionIds.push(sent.sessionId)
          r.f.seen[sent.sessionId] = { worked: false, idleAt: null, since, finished: false }
          log('automation run', r.id, 'continued', sent.sessionId.slice(0, 8))
        } else this.retryLater(t, sent.code)
      } catch (e) {
        this.retryLater(t, (e as { code?: string }).code ?? 'error')
      }
    }
  }
  private retryLater(t: Target, code: string): void {
    const now = this.io.now()
    const busy = BUSY.includes(code)
    t.code = code
    if (!busy || now >= t.until) {
      t.state = 'failed'
      return
    }
    t.nextAt = now + (code === 'starting' ? RETRY_STARTING_MS : RETRY_MS)
  }

  // ── Ask and shell steps ─────────────────────────────────────────────────
  /** An ask step: the run needs you, a note with the question (and its answers) to the phone. */
  private beginAsk(r: RunRec): void {
    const st = this.cur(r)
    if (st.kind !== 'ask') return
    const rs = this.rstep(r)
    // Filled, its question is cut to 200 characters between words ({prev} may be long; the run keeps it whole).
    r.ask = { stepId: st.id, question: capQuestion(maskSecrets(fill(st.question, r.ctx)), ASK_QUESTION_MAX), answers: [...st.answers], default: st.default, until: this.io.now() + st.timeoutMin * MIN_MS }
    rs.state = 'needs_you'
    this.setState(r, 'needs_you', null)
    const ask: NoteAsk = { ...r.ask, answers: [...r.ask.answers], runId: r.id }
    this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text: r.ask.question, to: 'phone', ask })
    log('automation run', r.id, 'asks', st.id)
  }
  /**
   * `POST /v1/automations/runs/{runId}/answer`: the first answer counts. 400 `bad_answer`,
   * `bad_step`; 409 `not_asking` (with the run).
   */
  async answer(runId: string, b: any, by: AnswerBy): Promise<Run> {
    const r = this.runs.get(runId)
    if (!r) throw new AutomationError(404, 'no_run', 'That run is gone.')
    const stepId = b?.stepId
    if (typeof stepId !== 'string' || !r.spec.steps.some((st) => st.id === stepId && st.kind === 'ask')) throw bad('bad_step', 'That isn’t an ask step of that run.', 'stepId')
    if (!r.ask || r.state !== 'needs_you' || r.ask.stepId !== stepId) throw new AutomationError(409, 'not_asking', 'It isn’t waiting for that answer any more.', { run: this.runView(r) })
    const text = r.ask.answers.find((x) => x.toLowerCase() === str(b?.answer).trim().toLowerCase())
    if (!text) throw bad('bad_answer', `Answer ${r.ask.answers.join(' or ')}.`, 'answer')
    this.answerStep(r, text, by)
    await this.advance(r)
    this.settle()
    return this.runView(r)
  }
  private answerStep(r: RunRec, text: string, by: AnswerBy): void {
    const rs = this.rstep(r)
    const question = r.ask?.question ?? ''
    rs.answer = { text, by, at: this.io.now() }
    rs.result = text
    r.ctx.answer = text
    r.ask = null
    log('automation run', r.id, 'answered', by)
    if (by === 'timeout') this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text: `Nobody answered “${question}”: taken as ${text}.`, to: 'phone' })
    this.stepDone(r, 'done', null)
  }
  /** A shell step: its command runs; its end is picked up by the next pass (shellEnded). */
  private beginShell(r: RunRec): void {
    const st = this.cur(r)
    if (st.kind !== 'shell') return
    const cwd = this.io.project(st.projectPath)
    if (!cwd) return this.stepDone(r, 'failed', this.folderGone(st.projectPath))
    if (!this.io.shell) return this.stepDone(r, 'failed', 'start_failed')
    // Every value the command may use is in its environment; its placeholders only name them.
    const filled = fillShell(st.command, r.ctx)
    const env: Record<string, string> = {
      [SHELL_ENV.automation]: r.automationId,
      [SHELL_ENV.run]: r.id,
      [SHELL_ENV.project]: r.ctx.project ?? '',
      [SHELL_ENV.time]: r.ctx.time ?? '',
      [SHELL_ENV.battery]: r.ctx.battery ?? '',
      [SHELL_ENV.prev]: r.ctx.prev ?? '',
      [SHELL_ENV.output]: r.ctx.output ?? '',
      [SHELL_ENV.answer]: r.ctx.answer ?? '',
      [SHELL_ENV.exit]: r.ctx.exit ?? '',
      [SHELL_ENV.payload]: r.ctx.payload ?? '',
      ...filled.env,
    }
    // Every step before it, by id: its whole output while the run works (a session's reply, a
    // command's kept 8 KB, an answer), else (taken over by a Retry) its output as {step:<id>} gives it.
    for (const x of r.spec.steps.slice(0, r.step)) env[stepEnv(x.id)] = r.f.full?.[x.id] ?? r.ctx.steps?.[x.id] ?? ''
    r.f.shellEnd = null
    let h: ShellRun
    try {
      h = this.io.shell({ cwd, command: filled.command, env, timeoutMs: st.timeoutSec * 1000 })
    } catch (e) {
      log('automation run', r.id, 'shell failed to start', (e as Error).message)
      return this.stepDone(r, 'failed', 'start_failed')
    }
    this.shells.set(r.id, h)
    log('automation run', r.id, 'runs its command', st.id)
    const ended = (x: { exit: number | null; output: string; timedOut: boolean }) => {
      if (this.shells.get(r.id) !== h) return
      this.shells.delete(r.id)
      r.f.shellEnd = x
      this.dirtyRuns()
      this.io.wake(0)
    }
    h.done.then(ended, (e) => ended({ exit: null, output: String((e as Error)?.message ?? e), timedOut: false }))
  }
  private shellEnded(r: RunRec): void {
    const x = r.f.shellEnd
    if (!x) return
    r.f.shellEnd = null
    const rs = this.rstep(r)
    const output = maskSecrets(x.output).slice(-SHELL_OUTPUT_KEPT)
    rs.exit = x.exit
    r.ctx.exit = x.exit == null ? null : String(x.exit)
    r.ctx.output = output || null
    const tail = output.trim()
    rs.result = tail ? (tail.length > STEP_RESULT_MAX ? '…' + tail.slice(-(STEP_RESULT_MAX - 1)) : tail) : null
    rs.quiet = !tail
    ;(r.f.full ??= {})[rs.id] = output
    if (x.timedOut) this.stepDone(r, 'failed', 'timeout')
    else if (x.exit === 0) this.stepDone(r, 'done', null)
    else this.stepDone(r, 'failed', x.exit == null ? 'error' : 'exit')
  }

  // ── Following a run ─────────────────────────────────────────────────────
  /** Where one of its sessions is: at work, needing you, waiting on a limit, finished, or why it stopped. */
  private sessionStatus(r: RunRec, sid: string): { kind: 'working' } | { kind: 'needs' } | { kind: 'waiting'; until: number } | { kind: 'finished' } | { kind: 'end'; state: RunState; reason: RunReason; why?: string | null } {
    const s = this.io.session(sid)
    const seen = (r.f.seen[sid] ??= { worked: false, idleAt: null, since: this.io.now(), finished: false })
    if (seen.finished) return { kind: 'finished' }
    const now = this.io.now()
    const starts = this.cur(r).kind === 'start'
    if (!s) return { kind: 'end', state: 'failed', reason: 'no_session' }
    if (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0 || (this.workedAt.get(sid) ?? -Infinity) >= seen.since) seen.worked = true
    // What it was last seen doing, before it ended (an end mid-turn isn't a finish).
    const lastSeen = seen.last ?? null
    if (s.state !== 'ended') seen.last = s.state === 'idle' && s.agentsRunning > 0 ? 'working' : s.state
    if (s.stoppedForAway) return { kind: 'end', state: 'stopped', reason: 'away' }
    if (s.blocked) {
      const until = s.blocked.autoResumeAt ?? s.blocked.resetsAt
      if (until != null) return { kind: 'waiting', until }
      // Said by its real reason (signed out, no plan, credit, a provider's trouble), in its own words.
      return { kind: 'end', state: 'failed', reason: BLOCKED_REASON[s.blocked.kind] ?? 'blocked', why: s.blocked.text || null }
    }
    if (s.pendingApprovalIds.length) {
      // A run's own approval answers itself "no" after 10 minutes (the session then stops, away);
      // a session it only continued is the owner's, whose asks are never a "no" (they go back to
      // Claude Code's dialog, the menu below, or wait for the phone).
      seen.needsAt = null
      return { kind: 'needs' }
    }
    if (s.menu || s.state === 'needs_you') {
      // A terminal menu with no approval behind it (a question, a dialog, Claude Code's own
      // permission prompt) never times out by itself: after 10 minutes it gets Esc, and the run
      // stops, away, so it neither needs you all night nor keeps the Mac awake and a slot taken.
      seen.needsAt ??= now
      if (now - seen.needsAt < NEEDS_MAX_MS) return { kind: 'needs' }
      // Never Esc on the permission dialog of a session the run didn't start (the owner's, its ask
      // handed back there): that would be a "no" for him (docs/DECISIONS.md, "Never auto-deny").
      // The run stops following it, away; the dialog stays for him.
      if (s.menu?.permission && s.automation?.runId !== r.id) return { kind: 'end', state: 'stopped', reason: 'away' }
      if (seen.escAt == null) {
        seen.escAt = now
        log('automation run', r.id, 'a menu waited 10 minutes: Esc')
        void this.io.interrupt(sid).catch(() => {})
      }
      return { kind: 'end', state: 'stopped', reason: 'away' }
    }
    seen.needsAt = null
    if (s.state === 'error') return { kind: 'end', state: 'failed', reason: 'error', why: this.io.lastError?.(sid) ?? null }
    if (s.state === 'ended') {
      if (!seen.worked) return { kind: 'end', state: 'failed', reason: starts ? 'start_failed' : 'error' }
      // Cut off mid-turn (a restart of the Mac, its terminal closed): stopped, not finished; its
      // half-written last reply isn't a result.
      if (s.endedBy === 'mac_restart') return { kind: 'end', state: 'stopped', reason: 'mac_restart' }
      if (lastSeen === 'working' || lastSeen === 'needs_you' || lastSeen === 'starting') return { kind: 'end', state: 'stopped', reason: 'ended' }
      seen.finished = true
      return { kind: 'finished' }
    }
    if (s.state === 'idle' && seen.worked && s.agentsRunning === 0 && !s.queued && !s.pendingApprovalIds.length) {
      seen.idleAt ??= now
      if (now - seen.idleAt >= SETTLE_MS) {
        seen.finished = true
        return { kind: 'finished' }
      }
      return { kind: 'working' }
    }
    seen.idleAt = null
    // Never got to work: it didn't start (or the prompt never reached Claude).
    if (!seen.worked && now - seen.since > NO_START_MS) return { kind: 'end', state: 'failed', reason: starts ? 'start_failed' : 'error' }
    return { kind: 'working' }
  }
  /** A run mirrors the step at work: its sessions, its question, or its command. */
  private followRun(r: RunRec): void {
    if (!r.f.started || !(r.state === 'running' || r.state === 'needs_you' || r.state === 'waiting')) return
    // Waiting at a later step's gate (regate's), a step about to begin (advance's), or on the daemon.
    if (r.f.regateAt != null || r.f.next || this.inFlight.has(r.id)) return
    const now = this.io.now()
    const st = this.cur(r)
    const rs = this.rstep(r)
    if (r.startedAt != null && now - r.startedAt > TIMEOUT_MS) {
      // Watched for 12 hours at most. A session it started itself and still at work gets Esc (one
      // it only continued is the owner's own, and is left alone), and the phone hears why.
      for (const sid of r.sessionIds) {
        const s = this.io.session(sid)
        if (s?.automation?.runId === r.id && (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0)) void this.io.interrupt(sid).catch(() => {})
      }
      this.end(r, 'stopped', 'timeout')
      this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text: 'It stopped after 12 hours, its longest.', to: 'phone' })
      return
    }
    if (st.kind === 'ask') {
      if (r.ask && now >= r.ask.until) this.answerStep(r, r.ask.default, 'timeout')
      else this.setState(r, 'needs_you', null)
      return
    }
    if (st.kind === 'shell') {
      if (r.f.shellEnd) this.shellEnded(r)
      // Its command was lost (the daemon restarted while it ran): stopped, as a session cut off is.
      else if (!this.shells.has(r.id)) this.stepDone(r, 'stopped', 'ended')
      return
    }
    if (TELLS.has(st.kind)) return
    const pending = r.f.targets.filter((t) => t.state === 'pending')
    if (st.kind === 'continue' && !rs.sessionIds.length) {
      if (pending.length) return this.setState(r, 'waiting', null)
      // Nothing could be typed anywhere.
      const codes = r.f.targets.map((t) => t.code ?? '')
      if (codes.some((c) => BUSY.includes(c))) return this.end(r, 'skipped', 'target_busy')
      // A limit, credits or a refused key on the provider a session carries on with; or a paid provider not agreed to.
      const gate = codes.find((c): c is RunReason => GATE_REASONS.includes(c as RunReason))
      if (gate) return this.end(r, 'skipped', gate)
      if (codes.includes('paid')) return this.end(r, 'skipped', 'provider')
      if (codes.includes('no_provider')) return this.stepDone(r, 'failed', 'no_provider')
      if (codes.includes('no_session')) return this.stepDone(r, 'failed', 'no_session')
      // The session is open in a plain terminal (or one the owner started on the Mac): it's never forked.
      if (codes.includes('no_terminal')) rs.result = 'That session is open in a terminal Hypernova doesn’t hold, so it couldn’t be continued.'
      if (codes.includes('bypass')) rs.result = 'That session runs with No asking, so words from outside weren’t typed into it.'
      return this.stepDone(r, 'failed', 'error')
    }
    const all = rs.sessionIds.map((sid) => this.sessionStatus(r, sid))
    // Claude Code ran it in another mode than asked (Auto isn't on every account).
    const first = st.kind === 'start' && rs.sessionIds[0] ? this.io.session(rs.sessionIds[0]) : null
    if (first && r.f.seen[first.id]?.worked && first.permissionMode && r.permissionMode !== first.permissionMode) {
      r.permissionMode = first.permissionMode
      this.dirtyRuns()
    }
    const ended = all.filter((x) => x.kind === 'end') as { kind: 'end'; state: RunState; reason: RunReason; why?: string | null }[]
    const stopped = ended.find((x) => x.state === 'stopped')
    if (stopped) return this.stepDone(r, 'stopped', stopped.reason)
    if (all.some((x) => x.kind === 'needs')) return this.setState(r, 'needs_you', null)
    const waits = all.filter((x) => x.kind === 'waiting') as { kind: 'waiting'; until: number }[]
    if (waits.length) return this.setState(r, 'waiting', Math.max(...waits.map((w) => w.until)))
    if (all.some((x) => x.kind === 'working') || pending.length) return this.setState(r, 'running', null)
    if (ended.length) return this.stepDone(r, ended[0]!.state as 'failed', ended[0]!.reason, ended[0]!.why ?? null)
    this.stepDone(r, 'done', null)
  }
  /** The run's state, and the step at work's with it. */
  private setState(r: RunRec, state: RunState, until: number | null): void {
    const rs = r.steps[r.step]
    if (rs && r.f.started && (rs.state === 'running' || rs.state === 'needs_you' || rs.state === 'waiting') && (state === 'running' || state === 'needs_you' || state === 'waiting') && rs.state !== state) {
      rs.state = state
      this.dirtyRuns()
    }
    if (r.state === state && r.until === until) return
    // A budget counts time at work: the moment it starts or stops working is when the state moves.
    const now = this.io.now()
    this.workTick(r, now)
    r.state = state
    r.until = until
    this.workTick(r, now)
    this.dirtyRuns()
  }

  // ── A step ends; the run goes on or ends ────────────────────────────────
  /**
   * What the step's sessions left: refused calls, its result (its reply's first two sentences, or
   * one sentence a session), whether it was quiet, the mode it really ran in. [why]: what stopped
   * it, in words, kept as its result instead.
   */
  private harvest(r: RunRec, rs: RunStep, st: Step, state: RunState, reason: RunReason | null, why: string | null): void {
    if (why) rs.result = cut(why)
    if (!rs.sessionIds.length || !(state === 'done' || state === 'stopped' || state === 'failed')) return
    for (const sid of rs.sessionIds) for (const x of this.io.refused(sid, r.f.seen[sid]?.since ?? r.startedAt ?? 0)) this.addRefused(r, x.tool, x.input)
    const replies = rs.sessionIds.map((id) => ({ id, text: this.io.lastReply(id) }))
    const said = replies.filter((x) => x.text != null && x.text.trim() && (r.f.seen[x.id]?.worked ?? true))
    rs.quiet = state === 'done' && said.length > 0 && said.every((x) => isQuiet(x.text))
    // Its first 8 KB (a review's start matters more than its end): `contains` and HYPERNOVA_STEP_<id>.
    ;(r.f.full ??= {})[rs.id] = maskSecrets(said.map((x) => x.text).join('\n\n')).slice(0, SHELL_OUTPUT_KEPT)
    // Cut off mid-turn, its last reply is half a thought: no result from it.
    const cutOff = reason === 'mac_restart' || reason === 'ended'
    if (why || cutOff) {
      // The result is what stopped it (or none).
    } else if (!rs.quiet && said.length === 1) rs.result = resultOf(said[0]!.text)
    else if (!rs.quiet && said.length > 1) {
      // One sentence per session, each a sentence of its own.
      const lines = said.filter((x) => !isQuiet(x.text)).map((x) => {
        const first = sentences(resultOf(x.text) ?? '')[0] ?? ''
        return `${this.io.session(x.id)?.title ?? 'Session'}: ${/[.!?…]$/.test(first) ? first : `${first}.`}`
      })
      rs.result = cut(lines.join(' '))
    }
    if (st.kind !== 'start') return
    const s = this.io.session(rs.sessionIds[0]!)
    if (s?.permissionMode && r.f.seen[s.id]?.worked) r.permissionMode = s.permissionMode
    const d = this.s.automations.find((x) => x.id === r.automationId)
    if (d && r.f.asked) {
      const off = !!r.permissionMode && r.permissionMode !== r.f.asked
      if (off !== d.modeProblem) {
        d.modeProblem = off
        this.save()
      }
    }
  }
  /** The step at work ended: kept, its output in the placeholders, and the next one found (or the run ends). */
  private stepDone(r: RunRec, state: 'done' | 'failed' | 'stopped', reason: RunReason | null, why: string | null = null): void {
    if (!isActive(r)) return
    const st = this.cur(r)
    const rs = this.rstep(r)
    rs.state = state
    rs.reason = reason
    rs.endedAt = this.io.now()
    this.harvest(r, rs, st, state, reason, why)
    // Its output for the next step: what it said, or, for a step that failed before saying anything,
    // why; and for any later step, as {step:<id>} (a new map: Retry's copies of ctx keep theirs).
    if (!TELLS.has(st.kind)) {
      r.ctx.prev = rs.result ?? (state !== 'done' && reason ? (STEP_WHY[reason] ?? null) : null)
      if (r.ctx.prev != null) r.ctx.steps = { ...r.ctx.steps, [st.id]: r.ctx.prev }
    }
    r.f.targets = []
    this.dirtyRuns(r)
    log('automation run', r.id, 'step', st.id, state, reason ?? '')
    const next = this.nextStep(r)
    if (next == null) return this.closeRun(r)
    r.step = next
    r.f.next = true
    this.io.wake(0)
  }
  /** The nearest earlier step that ran and doesn't only tell: what guards, {prev} and the no-guard rule look at. */
  private stepBefore(r: RunRec, i: number): RunStep | null {
    for (let j = i - 1; j >= 0; j--) {
      const x = r.steps[j]
      if (x && x.state !== 'skipped' && x.state !== 'pending' && !TELLS.has(x.kind)) return x
    }
    return null
  }
  /** The next step to run after r.step; the ones passed over are skipped (their guard, or a failure before them). */
  private nextStep(r: RunRec): number | null {
    const now = this.io.now()
    for (let i = r.step + 1; i < r.spec.steps.length; i++) {
      const st = r.spec.steps[i]!
      const rs = (r.steps[i] ??= newRunStep(st))
      const before = this.stepBefore(r, i)
      const failed = !!before && (before.state === 'failed' || before.state === 'stopped')
      if (st.if ? this.holds(r, st.if, before) : !failed) return i
      rs.state = 'skipped'
      rs.reason = st.if ? 'guard' : 'failed_before'
      rs.endedAt = now
    }
    return null
  }
  /** A guard about [before] (null: only notifications ran before it). */
  private holds(r: RunRec, g: Guard, before: RunStep | null): boolean {
    if (!before) return g.on === 'ok' || g.on === 'not_quiet'
    const done = before.state === 'done'
    switch (g.on) {
      case 'ok':
        return done
      case 'failed':
        return before.state === 'failed' || before.state === 'stopped'
      case 'quiet':
        return done && before.quiet
      case 'not_quiet':
        return done && !before.quiet
      case 'contains':
        return (r.f.full?.[before.id] ?? before.result ?? '').toLowerCase().includes((g.text ?? '').toLowerCase())
      case 'answer':
        return before.kind === 'ask' && (before.answer?.text ?? '').toLowerCase() === (g.text ?? '').toLowerCase()
    }
    return false
  }
  /**
   * No step left: the run ends by its steps. `done` when none failed or stopped, else as the first
   * that did; its result and quiet are the step before the end's (a failed run's: the failing step's).
   */
  private closeRun(r: RunRec): void {
    const failed = r.steps.find((x) => x.state === 'failed' || x.state === 'stopped')
    const state: RunState = failed ? (failed.state as RunState) : 'done'
    const reason = failed ? (failed.reason as RunReason) : null
    const last = this.stepBefore(r, r.steps.length) ?? [...r.steps].reverse().find((x) => x.state === 'done') ?? null
    const src = failed ?? last
    r.result = src?.result ?? null
    r.quiet = state === 'done' && !!src && src.quiet
    this.finish(r, state, reason)
  }
  /**
   * A run is ended from outside its steps (Stop, a gate, its budget, 12 hours, the account on
   * credits): the step at work ends with it, the ones after it aren't reached. [why]: what stopped
   * it, in words, kept as its result instead of the last reply.
   */
  private end(r: RunRec, state: RunState, reason: RunReason | null, why: string | null = null): void {
    const rs = r.steps[r.step]
    if (r.f.started && rs && (rs.state === 'running' || rs.state === 'needs_you' || rs.state === 'waiting')) {
      rs.state = state === 'queued' ? 'pending' : state
      rs.reason = reason
      rs.endedAt = this.io.now()
      this.harvest(r, rs, this.cur(r), state, reason, why)
      r.result = rs.result ?? r.result
      r.quiet = state === 'done' && rs.quiet
    } else if (why) r.result = cut(why)
    this.finish(r, state, reason)
  }
  /** The steps that never got to run (the run ended first) say so. */
  private unreached(r: RunRec): void {
    for (const x of r.steps) {
      if (x.state !== 'pending') continue
      x.state = 'skipped'
      x.reason = 'not_reached'
    }
  }
  /** A run is over: its outputs or its failure told, its command stopped, the Mac let go, a slot freed. */
  private finish(r: RunRec, state: RunState, reason: RunReason | null): void {
    const now = this.io.now()
    r.state = state
    r.reason = reason
    r.until = null
    r.endedAt = now
    r.f.regateAt = null
    r.f.next = false
    r.ask = null
    const sh = this.shells.get(r.id)
    if (sh) {
      this.shells.delete(r.id)
      sh.kill()
    }
    this.unreached(r)
    this.workTick(r, now)
    delete r.f.full
    if (r.sessionIds.length && (state === 'done' || state === 'stopped' || state === 'failed')) {
      r.f.doneAt = now
      // Only a terminal it started is ever closed: a continue's sessions are the owner's own.
      r.f.tidyAt = r.steps.some((x) => x.kind === 'start' && !x.reused && x.sessionIds.length) ? now + TIDY_MS : null
    }
    r.outputs = r.spec.outputs.map((o) => ({ id: o.id, kind: o.kind, state: 'skipped', error: null, at: null }))
    log('automation run', r.id, r.automationId, state, reason ?? '')
    this.dirtyRuns(r)
    if (state === 'done' && !r.quiet && r.f.started) this.sendOutputs(r)
    if (state === 'failed') this.noteFailure(r)
  }

  // ── Outputs ─────────────────────────────────────────────────────────────
  /** Where the result goes when it ends done (and isn't quiet): in order. */
  private sendOutputs(r: RunRec): void {
    const tz = this.io.tz()
    for (const o of r.spec.outputs) {
      const ro = r.outputs.find((x) => x.id === o.id)!
      const text = this.outputText(r, o)
      try {
        switch (o.kind) {
          case 'notify':
            this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text, to: 'phone' })
            break
          case 'file': {
            if (!this.io.append) throw new Error('This Mac can’t write to files for automations.')
            const p = localParts(r.endedAt ?? this.io.now(), tz)
            const two = (n: number) => String(n).padStart(2, '0')
            this.io.append(o.path, fileLine(`${p.y}-${two(p.mo)}-${two(p.d)}`, `${two(p.h)}:${two(p.mi)}`, r.name, text))
            break
          }
          case 'webhook':
            ro.state = 'pending'
            void this.deliver(r, o, false)
            continue
        }
        ro.state = 'sent'
        ro.at = this.io.now()
      } catch (e) {
        ro.state = 'failed'
        ro.error = cut(e instanceof AutomationError ? e.message : `It couldn’t be written: ${(e as Error).message}`)
        ro.at = this.io.now()
        log('automation run', r.id, 'output', o.id, 'failed')
      }
    }
  }
  /**
   * What an output says: its text filled; with none (or nothing left once filled), the run's result:
   * the step before the end's output, or, when no step gave one (only notifications ran, a session
   * said nothing), the last notification's text as sent, else "Done.". Every output names the
   * automation beside it (the note's name, ntfy's title, "<name>: " in Slack, Discord, Telegram and
   * the file's line), so that reads "Tell my team: Done.".
   */
  private outputText(r: RunRec, o: Output): string {
    const filled = o.text ? fill(o.text, r.ctx) : ''
    const said = r.steps.length ? (this.stepBefore(r, r.steps.length)?.result ?? r.f.told ?? null) : r.result
    return maskSecrets(filled || said || 'Done.')
  }
  /** A webhook output's POST; a network error or a 5xx is tried once more after 30 s. */
  private async deliver(r: RunRec, o: Output, again: boolean): Promise<void> {
    if (o.kind !== 'webhook') return
    const ro = r.outputs.find((x) => x.id === o.id)
    if (!ro) return
    const label = { ntfy: 'ntfy', slack: 'Slack', discord: 'Discord', telegram: 'Telegram', post: 'The address' }[o.preset]
    const sec = this.secrets[`${r.automationId}/${o.id}`]
    const fail = (error: string, retry: boolean) => {
      if (retry && !again) {
        ;(r.f.retryAt ??= {})[o.id] = this.io.now() + WEBHOOK_RETRY_MS
        ro.error = error
      } else {
        ro.state = 'failed'
        ro.error = error
        if (r.f.retryAt) delete r.f.retryAt[o.id]
      }
    }
    if (!sec || !this.io.post) fail('Its address is gone from the Mac: save it again with the address.', false)
    else {
      const text = this.outputText(r, o)
      const req = webhookRequest(o.preset, sec, r.name, text, { id: r.id, automationId: r.automationId, state: r.state, reason: r.reason, startedAt: r.startedAt, endedAt: r.endedAt, result: r.result, firedBy: r.fired.by })
      try {
        const res = await this.io.post(req)
        if (res.status >= 200 && res.status < 300) {
          ro.state = 'sent'
          ro.error = null
          if (r.f.retryAt) delete r.f.retryAt[o.id]
        } else fail(`${label} answered ${res.status}.`, res.status >= 500)
      } catch (e) {
        const code = (e as { code?: string }).code
        if (code === 'private_address') fail('That address is this computer or a private network: webhooks go to the internet only.', false)
        else fail(code === 'timeout' ? 'No answer in 10 s.' : `${label} couldn’t be reached.`, true)
      }
    }
    ro.at = this.io.now()
    log('automation run', r.id, 'webhook', o.id, ro.state)
    this.dirtyRuns(r)
    this.settle()
  }

  /** A run that couldn't start, or failed, tells the phone why, by its real reason. */
  private noteFailure(r: RunRec): void {
    // A run on Codex says so in Codex's words (its account, its start), not Claude Code's.
    const codex = r.spec.steps?.some((st) => st.kind === 'start' && st.providerId === CODEX) || r.sessionIds.some((id) => this.io.session(id)?.providerId === CODEX)
    const onCodex: Partial<Record<RunReason, string>> = {
      no_provider: 'It didn’t run: Codex isn’t set up on this Mac.',
      start_failed: 'It didn’t run: Codex didn’t start.',
      blocked: 'It stopped: Codex couldn’t go on.',
      signed_out: 'It stopped: Codex is signed out on the Mac.',
      limited: 'It stopped: Codex’s usage limit is reached.',
    }
    const why: Partial<Record<RunReason, string>> = {
      no_project: 'It didn’t run: the project folder is gone.',
      outside_projects: 'It didn’t run: its folder is outside your projects folder.',
      no_session: 'It didn’t run: that session no longer exists.',
      no_provider: 'It didn’t run: that provider was removed.',
      no_model: 'It didn’t run: its model isn’t offered any more.',
      start_failed: 'It didn’t run: Claude Code didn’t start.',
      error: r.result && r.sessionIds.length ? `It stopped with an error: ${r.result}` : 'It stopped with an error.',
      blocked: 'It stopped: Claude couldn’t go on.',
      signed_out: 'It stopped: Claude Code is signed out on the Mac.',
      no_plan: 'It stopped: there’s no Claude plan on the Mac.',
      on_hold: 'It stopped: the Claude account is on hold.',
      no_credit: 'It stopped: you’re out of usage credits.',
      limited: 'It stopped: your usage limit is reached.',
      provider: `It stopped: ${this.providerTrouble(r)}`,
      exit: `It failed: its command exited with ${r.steps.find((x) => x.state === 'failed' && x.reason === 'exit')?.exit ?? 'an error'}.`,
      timeout: 'It failed: its command ran past its time.',
    }
    // A continue that reached no session says why, when it knows (a terminal Hypernova doesn't hold).
    const own = r.reason === 'error' && !r.sessionIds.length && r.result
    const text = own ? `It didn’t run: ${r.result!.charAt(0).toLowerCase()}${r.result!.slice(1)}` : r.reason ? ((codex ? onCodex[r.reason] : undefined) ?? why[r.reason]) : null
    if (!text) return
    // What it works on being gone is said once, not on every firing until it's fixed.
    if (r.reason === 'no_project' || r.reason === 'outside_projects' || r.reason === 'no_session' || r.reason === 'no_provider' || r.reason === 'no_model') this.tellOnce(r, r.reason, text)
    else this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text, to: 'phone' })
  }
  /** "OpenRouter refused its key.": the trouble of the provider a run's session was on, in words. */
  private providerTrouble(r: RunRec): string {
    const a = asAction(this.cur(r)) ?? this.sessionStep(r.spec.steps)
    const pid = (r.sessionIds[0] && this.io.session(r.sessionIds[0])?.providerId) || (a ? this.providerFor(a, r.fired.sessionId) : 'anthropic')
    if (!this.io.providerKind(pid)) return 'its provider was removed.'
    const name = this.io.providerName(pid)
    switch (this.io.providerStatus(pid)?.state) {
      case 'bad_key':
        return `${name} refused its key.`
      case 'no_credit':
        return `${name} is out of credits.`
      case 'limited':
        return `${name} is rate limiting it.`
      case 'down':
        return `${name} isn’t answering.`
    }
    return `${name} couldn’t go on.`
  }
  /** A notify step: its text, placeholders filled, to the phone. */
  private async sendNotes(r: RunRec): Promise<void> {
    const a = this.cur(r)
    if (a.kind !== 'notify') return
    const week = a.text.includes('{week}') ? await this.io.week().catch(() => null) : null
    const text = fill(a.text, { ...r.ctx, week })
    this.rstep(r).result = resultOf(text) ?? text.slice(0, 300)
    // What an output with no text says when no other step gave a result (outputText).
    r.f.told = maskSecrets(text)
    this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text, to: 'phone' })
  }
  private addNote(n: Omit<Note, 'id' | 'at' | 'ask'> & { ask?: NoteAsk | null }): void {
    const note: Note = { ...n, ask: n.ask ?? null, text: maskSecrets(n.text), id: `no_${hex(5)}`, at: this.io.now() }
    this.notes.push(note)
    this.dirtyRuns()
    this.io.send({ t: 'note', note })
  }
  /** "Only the next time", and "once": off after its first run that starts (a missed once too). */
  private turnOff(d: Def): void {
    if (!d.onlyOnce && d.trigger.kind !== 'once') return
    d.enabled = false
    d.updatedAt = this.io.now()
    this.save()
  }

  // ── Budgets ─────────────────────────────────────────────────────────────
  /** The 5-hour window's points now, of the account the run spends: Codex's for one that starts on it. */
  private fiveHourOf(r: RunRec): number | null {
    const first = r.spec.steps?.find((st) => st.kind === 'start')
    if (first?.kind === 'start' && first.providerId === CODEX) return this.io.codexFiveHour?.() ?? null
    const u = this.io.usage()
    return u.source === 'official' ? (u.fiveHour?.pct ?? null) : null
  }
  /** Its time at work counted (not while it waits for you or a reset), and the window's points it used. */
  private workTick(r: RunRec, now: number): void {
    if (!r.spec.budget || !r.f.started) return
    const working = r.state === 'running' && isActive(r)
    if (r.f.workSince != null && !working) {
      r.f.workMs = (r.f.workMs ?? 0) + (now - r.f.workSince)
      r.f.workSince = null
    } else if (r.f.workSince == null && working) r.f.workSince = now
    const ms = (r.f.workMs ?? 0) + (r.f.workSince != null ? now - r.f.workSince : 0)
    const pct = this.fiveHourOf(r)
    const used = pct != null && r.f.pct0 != null ? Math.max(0, Math.round((pct - r.f.pct0) * 10) / 10) : null
    const next = { minutes: Math.floor(ms / MIN_MS), windowPct: used }
    if (JSON.stringify(next) !== JSON.stringify(r.budgetUsed)) {
      r.budgetUsed = next
      this.dirtyRuns()
    }
  }
  /** A run over its budget: its sessions at work get Esc, its command is killed, and it ends `stopped`, `budget`. */
  private async budgets(now: number): Promise<void> {
    for (const r of [...this.runs.values()]) {
      const b = r.spec.budget
      if (!b || !r.f.started || !isActive(r)) continue
      this.workTick(r, now)
      const ms = (r.f.workMs ?? 0) + (r.f.workSince != null ? now - r.f.workSince : 0)
      const overTime = b.minutes != null && ms >= b.minutes * MIN_MS
      const overPct = b.windowPct != null && r.budgetUsed?.windowPct != null && r.budgetUsed.windowPct >= b.windowPct
      if (!overTime && !overPct) continue
      for (const sid of this.rstep(r).sessionIds) {
        const s = this.io.session(sid)
        if (s && (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0)) await this.io.interrupt(sid).catch(() => {})
      }
      log('automation run', r.id, 'stopped at its budget')
      this.end(r, 'stopped', 'budget')
      const of = overTime ? `${b.minutes} minute${b.minutes === 1 ? '' : 's'}` : `${b.windowPct} point${b.windowPct === 1 ? '' : 's'} of the 5-hour window`
      this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text: `It stopped: it reached its budget of ${of}.`, to: 'phone' })
    }
  }

  // ── Events off the broadcast ────────────────────────────────────────────
  /** Every message the daemon broadcasts, before it's sent (hub.ts's tap). Cheap: most are ignored. */
  tap(m: any): void {
    switch (m?.t) {
      case 'session':
        if (m.session) this.onSession(m.session as Session)
        return
      case 'approval':
        if (m.approval) this.onApproval(m.approval as Approval)
        return
      case 'approval.resolved':
        // Answered (not turned away for nobody answering): a needs-you waiting `after` for it is over.
        if (m.by !== 'timeout' && typeof m.id === 'string') this.waits = this.waits.filter((w) => w.key !== `a:${m.id}`)
        this.io.wake(0)
        return
      case 'usage':
      case 'account':
      case 'battery':
        this.io.wake(0)
        return
    }
  }
  private onSession(s: Session): void {
    const now = this.io.now()
    const prev = this.lastState.get(s.id)
    this.lastState.set(s.id, s.state)
    if (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0) this.workedAt.set(s.id, now)
    // Most session messages (activity, text) change nothing here: the timer is woken only for those that may.
    let news = false
    // Sessions a usage limit stopped, for "Carry on after the limit" once it's reset.
    if (s.blocked?.kind === 'limit') {
      const armed = s.blocked.resume?.state === 'armed'
      const was = this.s.limitStopped[s.id]
      if (!was || was.armed !== armed) {
        this.s.limitStopped[s.id] = { at: was?.at ?? now, armed }
        this.save()
      }
    } else if ((s.state === 'working' || s.state === 'ended') && this.s.limitStopped[s.id]) {
      delete this.s.limitStopped[s.id]
      this.save()
    }
    // Finished: from working (or needing you, or starting) to idle, checked again 5 s later.
    if (s.state === 'idle' && (prev === 'working' || prev === 'needs_you' || prev === 'starting')) {
      this.finishing.set(s.id, now)
      news = true
    } else if (s.state !== 'idle') this.finishing.delete(s.id)
    // Needs you: a terminal menu with no approval open (a limit's own dialog isn't one).
    if (s.menu && !s.pendingApprovalIds.length && !s.blocked && !this.seenAsks.has(`m:${s.menu.id}:${s.id}`)) {
      this.remember(`m:${s.menu.id}:${s.id}`)
      this.events.push({ event: 'needs_you', sessionId: s.id, ask: s.menu.title, at: now, key: `m:${s.menu.id}` })
      news = true
    }
    // Runs following this session: its idle moment counts from now, not from the next tick. A run's
    // session streams many messages a turn: they wake the timer a second later, not each at once.
    let followed = false
    for (const r of this.runs.values()) {
      if (!r.f.started || !isActive(r) || !r.sessionIds.includes(s.id)) continue
      followed = true
      const seen = (r.f.seen[s.id] ??= { worked: false, idleAt: null, since: now, finished: false })
      // What it was doing before it ended, if it does: an end mid-turn isn't a finish.
      if (s.state !== 'ended') seen.last = s.state === 'idle' && s.agentsRunning > 0 ? 'working' : s.state
      // Since when it has waited on a menu with no approval behind it (sessionStatus gives it 10 minutes).
      if ((s.menu || s.state === 'needs_you') && !s.pendingApprovalIds.length && !s.blocked) seen.needsAt ??= now
      else seen.needsAt = null
      if (s.state === 'working' || s.state === 'needs_you' || s.agentsRunning > 0) {
        seen.worked = true
        seen.idleAt = null
      } else if (s.state === 'idle' && seen.idleAt == null) seen.idleAt = now
    }
    if (news) this.io.wake(0)
    else if (followed || prev !== s.state) this.io.wake(1000)
  }
  private onApproval(a: Approval): void {
    if (this.seenAsks.has(`a:${a.id}`)) return
    if (a.agentId) {
      // An agent's: only if it still waits after 10 s, as the phone does.
      this.agentAsks.set(a.id, a.createdAt + AGENT_ASK_MS)
      this.io.wake(AGENT_ASK_MS)
      return
    }
    this.remember(`a:${a.id}`)
    this.events.push({ event: 'needs_you', sessionId: a.sessionId, ask: a.summary, at: this.io.now(), key: `a:${a.id}` })
    this.io.wake(0)
  }
  /**
   * The session still needs you for what [key] names: its approval still open, or turned away for
   * nobody answering (an approval answers itself "no" after 10 minutes and the session waits for
   * you, `stoppedForAway`); its terminal menu still open. One answered meanwhile was dropped (tap).
   */
  private stillNeeds(s: Session, key: string): boolean {
    if (s.state === 'ended') return false
    if (key.startsWith('a:')) return this.io.approvals().some((x) => x.id === key.slice(2)) || s.stoppedForAway
    return !!s.menu && `m:${s.menu.id}` === key
  }
  private remember(key: string): void {
    this.seenAsks.add(key)
    if (this.seenAsks.size > 2000) this.seenAsks.delete(this.seenAsks.values().next().value!)
  }

  // ── The tick ────────────────────────────────────────────────────────────
  /** Look at everything due: time triggers, resets, limits, events, power, and the runs. One at a time. */
  async tick(): Promise<void> {
    if (this.ticking) {
      this.again = true
      return this.ticking
    }
    this.ticking = (async () => {
      do {
        this.again = false
        try {
          await this.tickOnce()
        } catch (e) {
          log('automations tick failed', e)
        }
      } while (this.again)
    })()
    try {
      await this.ticking
    } finally {
      this.ticking = null
    }
  }
  private async tickOnce(): Promise<void> {
    const now = this.io.now()
    // A gap in the ticks is the Mac asleep; the first tick after start finds the daemon was down.
    const lateWhy: Run['lateWhy'] = this.firstTick ? 'down' : 'asleep'
    this.firstTick = false
    // A session a limit stopped a week ago isn't one to carry on any more.
    for (const [id, x] of Object.entries(this.s.limitStopped)) {
      if (now - x.at <= 7 * DAY_MS) continue
      delete this.s.limitStopped[id]
      this.save()
    }
    // Each part on its own: one that fails (a definition that trips it) never stops the others,
    // nor following the runs.
    const step = async (name: string, f: () => Promise<void>) => {
      try {
        await f()
      } catch (e) {
        log('automations:', name, 'failed', e)
      }
    }
    await step('time triggers', () => this.timeTriggers(now, lateWhy))
    await step('reset triggers', () => this.resetTriggers(now, lateWhy))
    await step('limit triggers', () => this.limitTriggers(now))
    await step('session events', () => this.sessionEvents(now))
    await step('hook events', () => this.hookTriggers(now))
    await step('power triggers', () => this.powerTriggers())
    await step('file watches', () => this.fileTriggers(now))
    await step('github watches', async () => this.githubTriggers(now))
    await step('following runs', () => this.followRuns(now))
    this.settle()
  }

  private async timeTriggers(now: number, lateWhy: Run['lateWhy']): Promise<void> {
    if (this.s.paused) {
      // Paused time is never caught up and records nothing, but a once has then had its time: it
      // turns off, rather than staying on with nothing next.
      for (const d of this.s.automations) if (d.enabled && d.trigger.kind === 'once' && d.trigger.at <= now) this.turnOff(d)
      return
    }
    const tz = this.io.tz()
    for (const d of this.s.automations) {
      if (!d.enabled || !isTimeTrigger(d.trigger)) continue
      let due: ReturnType<typeof dueBetween>
      try {
        due = dueBetween(d.trigger, Math.max(d.armedAt, d.firedAt ?? 0), now, tz, d.firedFor)
      } catch (e) {
        // One that trips the clock is passed over; the others still fire.
        log('automation', d.id, 'due time failed', (e as Error).message)
        continue
      }
      if (due.kind === 'none') continue
      d.firedFor = due.occ.key
      d.firedAt = due.occ.at
      this.save()
      const ctx = this.ctxFor(d, null, null, due.occ.at)
      if (due.kind === 'missed') this.record(d, 'schedule', due.occ.at, ctx, 'skipped', 'missed', { missed: due.missed, lateWhy })
      else await this.fire(d, 'schedule', due.occ.at, ctx, { late: due.late, missed: due.missed, lateWhy: due.late || due.missed ? lateWhy : null })
      // A once has had its time, whatever came of it: the card shows when it ran.
      if (d.trigger.kind === 'once') this.turnOff(d)
    }
  }

  /**
   * Resets: each window's reset as last read, fired 60 s after it passes, once per reset. A reset
   * the Mac slept through (or the daemon missed) still fires within 6 hours; later, it's missed.
   */
  private async resetTriggers(now: number, lateWhy: Run['lateWhy']): Promise<void> {
    const u = this.io.usage()
    const acc = this.io.account()
    const passed: { window: Window; resetsAt: number; pct: number }[] = []
    let changed = false
    for (const w of ['five_hour', 'seven_day'] as const) {
      // The account's limit names the window and its reset ("after I hit it").
      if (acc.state === 'limited' && acc.limit?.window === w && acc.limit.resetsAt) this.noteLimit(w, acc.limit.resetsAt, acc.since ?? now)
      const kept = this.s.windows[w]
      // A minute past its reset: it fires, and the window is read afresh.
      if (kept && kept.resetsAt + AFTER_RESET_MS <= now) {
        passed.push({ window: w, ...kept })
        delete this.s.windows[w]
        changed = true
      }
      const cur = u.source === 'official' ? (w === 'five_hour' ? u.fiveHour : u.sevenDay) : null
      const k = this.s.windows[w]
      if (!cur || cur.resetsAt <= now) continue
      // One whose reset has passed waits for its minute: the meter has already rolled on to the
      // next window, which mustn't take its place before it fires.
      if (k && k.resetsAt <= now) continue
      if (!k || Math.abs(k.resetsAt - cur.resetsAt) > 120_000) this.s.windows[w] = { resetsAt: cur.resetsAt, pct: cur.pct }
      else if (cur.pct > k.pct) this.s.windows[w] = { resetsAt: k.resetsAt, pct: cur.pct }
      else continue
      changed = true
    }
    if (changed) this.save()
    // Signed out (stale usage): no reset is known for sure, so none fires.
    if (this.s.paused || u.source === 'stale') return
    const tz = this.io.tz()
    for (const p of passed) {
      const hit = this.s.limits.some((l) => l.window === p.window && l.resetsAt != null && Math.abs(l.resetsAt - p.resetsAt) <= 120_000)
      for (const d of this.s.automations) {
        const t = d.trigger
        if (!d.enabled || t.kind !== 'reset' || t.window !== p.window || d.resetFiredFor === p.resetsAt) continue
        // After I hit it: the account's limit had that reset. Else: the window had been used at all.
        if (t.afterLimit ? !hit : p.pct <= 0) continue
        if (!inWindow(p.resetsAt, parseHm(t.from), parseHm(t.to), tz)) continue
        d.resetFiredFor = p.resetsAt
        this.save()
        const ctx = this.ctxFor(d, null, null, p.resetsAt)
        if (now - p.resetsAt > WAIT_MAX_MS) {
          this.record(d, 'reset', p.resetsAt, ctx, 'skipped', 'missed', { missed: 1, lateWhy })
          continue
        }
        const ago = now - p.resetsAt - AFTER_RESET_MS
        const late = ago > ON_TIME_MS ? ago : 0
        await this.fire(d, 'reset', p.resetsAt, ctx, { late, lateWhy: late ? lateWhy : null })
      }
    }
  }
  /** The account's 5-hour or weekly limit, remembered for "after I hit it" and the limit trigger. */
  private noteLimit(window: Window, resetsAt: number, since: number): void {
    if (this.s.limits.some((l) => l.window === window && l.resetsAt != null && Math.abs(l.resetsAt - resetsAt) <= 120_000)) return
    this.s.limits = [...this.s.limits, { window, resetsAt, since }].slice(-6)
    this.save()
  }
  /**
   * Hitting a limit fires 60 s after the episode began (the other sessions hit it too), once per
   * episode; never caught up. Also when Claude carries on past it on usage credits: that's when
   * money starts being spent, so "tell me" and "switch provider" matter most (start and continue
   * on the subscription are still held back by the gate).
   */
  private async limitTriggers(now: number): Promise<void> {
    const a = this.io.account()
    const l = a.limit
    if (a.state !== 'limited' || !l || !isWindow(l.window) || a.since == null) return
    if (l.resetsAt) this.noteLimit(l.window, l.resetsAt, a.since)
    if (now - a.since < AFTER_LIMIT_MS) {
      this.io.wake(a.since + AFTER_LIMIT_MS - now)
      return
    }
    // A limit first seen long after it began (the daemon was down) is an event missed, not caught up.
    const fresh = now - a.since <= AFTER_LIMIT_MS + 5 * MIN_MS
    for (const d of this.s.automations) {
      const t = d.trigger
      if (t.kind !== 'limit' || t.window !== l.window || d.limitFiredFor === a.since || !d.enabled || this.s.paused) continue
      d.limitFiredFor = a.since
      this.save()
      if (fresh) await this.fire(d, 'limit', a.since, this.ctxFor(d, null, null, a.since))
    }
  }
  /** A session finishing (5 s settled) or needing you, to the automations listening for it. */
  private async sessionEvents(now: number): Promise<void> {
    for (const [sid, at] of [...this.finishing]) {
      if (now - at < SETTLE_MS) continue
      this.finishing.delete(sid)
      const s = this.io.session(sid)
      if (s && s.state === 'idle' && s.agentsRunning === 0 && !s.queued && !s.pendingApprovalIds.length && !s.blocked) this.events.push({ event: 'finished', sessionId: sid, ask: null, at: now, key: '' })
    }
    for (const [id, at] of [...this.agentAsks]) {
      if (now < at) continue
      this.agentAsks.delete(id)
      const a = this.io.approvals().find((x) => x.id === id)
      if (!a) continue
      this.remember(`a:${id}`)
      this.events.push({ event: 'needs_you', sessionId: a.sessionId, ask: a.summary, at: now, key: `a:${id}` })
    }
    const events = this.events.splice(0)
    for (const e of this.s.paused ? [] : events) {
      const s = this.io.session(e.sessionId)
      if (!s) continue
      for (const d of this.s.automations) {
        const t = d.trigger
        if (!d.enabled || t.kind !== 'session' || t.event !== e.event) continue
        if (t.projectPath && !(s.cwd === t.projectPath || s.cwd.startsWith(t.projectPath + '/'))) continue
        if ((t.from === 'mine' && s.automation) || (t.from === 'automated' && !s.automation)) continue
        if (s.automation?.id === d.id) continue
        // "Still needs you after N minutes": looked at again then (once per approval or menu; the
        // once-a-minute rule then).
        const after = t.event === 'needs_you' ? (t.after ?? null) : null
        if (after) {
          if (!this.waits.some((w) => w.automationId === d.id && w.key === e.key)) this.waits.push({ automationId: d.id, sessionId: s.id, key: e.key, ask: e.ask, due: e.at + after * MIN_MS })
          if (this.waits.length > 500) this.waits.shift()
          continue
        }
        // At most once a minute per automation per session.
        const key = `${d.id}|${s.id}`
        if (now - (this.sessionFired.get(key) ?? -Infinity) < SESSION_EVENT_GAP_MS) continue
        this.sessionFired.set(key, now)
        await this.fire(d, 'session', e.at, this.ctxFor(d, s, e.ask, e.at), { sessionId: s.id })
      }
    }
    // A needs-you that waited `after`: it fires if the session still needs you (paused or off: nothing, as fire says).
    for (const w of [...this.waits]) {
      if (now < w.due) continue
      this.waits.splice(this.waits.indexOf(w), 1)
      const d = this.s.automations.find((x) => x.id === w.automationId)
      const s = this.io.session(w.sessionId)
      if (!d || d.trigger.kind !== 'session' || d.trigger.event !== 'needs_you' || !s || !this.stillNeeds(s, w.key)) continue
      const key = `${d.id}|${s.id}`
      if (now - (this.sessionFired.get(key) ?? -Infinity) < SESSION_EVENT_GAP_MS) continue
      this.sessionFired.set(key, now)
      await this.fire(d, 'session', w.due, this.ctxFor(d, s, w.ask, w.due), { sessionId: s.id })
    }
  }
  /** Plugged in (once per 10 minutes), and the battery low (once per discharge). */
  private async powerTriggers(): Promise<void> {
    const b = this.io.battery()
    const prev = this.prevBattery
    this.prevBattery = b
    if (!b) return
    const now = this.io.now()
    for (const d of this.s.automations) {
      const t = d.trigger
      if (t.kind !== 'power') continue
      if (t.event === 'plugged_in') {
        if (!prev || prev.onAC || !b.onAC) continue
        if (d.lastPluggedAt != null && now - d.lastPluggedAt < PLUG_DEBOUNCE_MS) continue
        d.lastPluggedAt = now
        this.save()
        if (d.enabled && !this.s.paused) await this.fire(d, 'power', now, this.ctxFor(d, null, null))
        continue
      }
      // Re-armed by charging, or once it's back 5 points above.
      if (!d.lowArmed && (b.charging || b.onAC || b.percent >= t.below + 5)) {
        d.lowArmed = true
        this.save()
      }
      if (d.lowArmed && !b.charging && !b.onAC && b.percent <= t.below) {
        d.lowArmed = false
        this.save()
        if (d.enabled && !this.s.paused) await this.fire(d, 'power', now, this.ctxFor(d, null, null))
      }
    }
  }
  /**
   * Past a limit on usage credits Claude Code doesn't stop (nothing blocks it), so a run at work then
   * would go on spending them, and runs never do: its sessions at work on the built-in provider get
   * Esc, and it ends `stopped`, `limited`, with a note to the phone.
   */
  private async offCredits(now: number): Promise<void> {
    const a = this.io.account()
    const l = a.limit
    if (a.state !== 'limited' || !l?.usingCredits || (l.resetsAt != null && l.resetsAt <= now)) return
    for (const r of [...this.runs.values()]) {
      if (!r.f.started || !(r.state === 'running' || r.state === 'needs_you')) continue
      const atWork = r.sessionIds.filter((sid) => {
        const s = this.io.session(sid)
        return !!s && this.io.providerKind(s.providerId) === 'anthropic' && (s.state === 'working' || s.agentsRunning > 0)
      })
      if (!atWork.length) continue
      for (const sid of atWork) await this.io.interrupt(sid).catch(() => {})
      log('automation run', r.id, 'stopped: the account is on usage credits')
      this.end(r, 'stopped', 'limited')
      this.addNote({ automationId: r.automationId, runId: r.id, name: r.name, text: 'It stopped: your usage limit is reached, and going on would spend usage credits.', to: 'phone' })
    }
  }
  /** Waiting runs gated again, stale queued ones dropped, slots filled, running ones followed, terminals tidied. */
  private async followRuns(now: number): Promise<void> {
    for (const r of [...this.runs.values()]) {
      if (r.state === 'waiting' && r.f.regateAt != null && now >= r.f.regateAt) await this.regate(r)
      else if (r.state === 'queued' && r.f.queuedAt != null && now - r.f.queuedAt > STALE_MS) this.end(r, 'skipped', 'stale')
    }
    await this.dequeue()
    await this.offCredits(now)
    for (const r of [...this.runs.values()]) {
      if (!r.f.started || !(r.state === 'running' || r.state === 'needs_you' || r.state === 'waiting')) continue
      // Waiting for a reset before a later step: regate's, above. Waiting on the daemon: its own.
      if (r.f.regateAt != null || this.inFlight.has(r.id)) continue
      if (r.f.next) await this.advance(r)
      else if (r.f.targets.some((t) => t.state === 'pending' && t.nextAt <= now)) await this.sendPending(r)
      else this.followRun(r)
      // A step that ended just now: the next one begins in this pass.
      await this.advance(r)
    }
    await this.budgets(now)
    await this.dequeue()
    // Webhook outputs tried once more.
    for (const r of [...this.runs.values()]) {
      for (const [id, at] of Object.entries(r.f.retryAt ?? {})) {
        if (now < at) continue
        delete r.f.retryAt![id]
        const o = r.spec.outputs.find((x) => x.id === id)
        if (o) await this.deliver(r, o, true)
      }
    }
    // A done run's terminal is closed once nobody has had it open for 15 minutes; the session stays
    // in History. Only one the run started: never a session it only continued (the owner's own).
    for (const r of this.runs.values()) {
      if (r.f.tidyAt == null || now < r.f.tidyAt) continue
      let later = false
      for (const sid of r.sessionIds) {
        const s = this.io.session(sid)
        if (!s?.term || s.automation?.runId !== r.id) continue
        if (s.term.mac > 0 || s.term.phone > 0 || s.state !== 'idle') later = true
        else this.io.endTerm(s.term.id)
      }
      r.f.tidyAt = later && now - (r.f.doneAt ?? now) < TIMEOUT_MS ? now + TIDY_MS : null
      this.dirtyRuns()
    }
  }

  /** The next moment something may be due: the timer is set to it, 30 s at most. */
  private nextWake(): number {
    const now = this.io.now()
    let at = now + MAX_TICK_MS
    const soon = (t: number | null | undefined) => {
      if (t != null && t < at) at = t
    }
    for (const d of this.s.automations) soon(this.nextAt(d))
    for (const t of this.finishing.values()) soon(t + SETTLE_MS)
    for (const t of this.agentAsks.values()) soon(t)
    for (const w of this.waits) soon(w.due)
    for (const w of Object.values(this.s.windows)) if (w) soon(w.resetsAt + AFTER_RESET_MS)
    for (const at of this.fileChanges.values()) soon(at.at + FILE_QUIET_MS)
    for (const d of this.s.automations) if (d.trigger.kind === 'github' && d.enabled && d.ghLookAt != null) soon(d.ghLookAt + d.trigger.every * MIN_MS)
    for (const r of this.runs.values()) {
      soon(r.f.tidyAt)
      for (const at of Object.values(r.f.retryAt ?? {})) soon(at)
      if (!isActive(r)) continue
      soon(r.f.regateAt)
      if (r.ask) soon(r.ask.until)
      if (r.state === 'queued' && r.f.queuedAt != null) soon(r.f.queuedAt + STALE_MS)
      for (const t of r.f.targets) if (t.state === 'pending') soon(t.nextAt)
      for (const s of Object.values(r.f.seen)) {
        if (s.idleAt != null && !s.finished) soon(s.idleAt + SETTLE_MS)
        if (s.needsAt != null && s.escAt == null) soon(s.needsAt + NEEDS_MAX_MS)
      }
    }
    return Math.max(MIN_TICK_MS, at - now)
  }

  // ── Workflows' triggers ─────────────────────────────────────────────────
  /** A Claude Code event in a session (hooks.ts, runner.ts): the hook triggers hear it on the next pass. */
  hookEvent(event: string, sessionId: string, tool: string | null, line: string): void {
    if (!HOOK_EVENTS.includes(event as HookEvent)) return
    if (!this.s.automations.some((d) => d.enabled && d.trigger.kind === 'hook' && d.trigger.event === event)) return
    this.hookEvents.push({ event: event as HookEvent, sessionId, tool, line: line.slice(0, 300), at: this.io.now() })
    if (this.hookEvents.length > 200) this.hookEvents.splice(0, this.hookEvents.length - 200)
    this.io.wake(0)
  }
  private async hookTriggers(now: number): Promise<void> {
    const events = this.hookEvents.splice(0)
    if (this.s.paused) return
    for (const e of events) {
      const s = this.io.session(e.sessionId)
      if (!s) continue
      for (const d of this.s.automations) {
        const t = d.trigger
        if (!d.enabled || t.kind !== 'hook' || t.event !== e.event) continue
        if (t.tool && t.tool !== e.tool) continue
        if (t.projectPath && !(s.cwd === t.projectPath || s.cwd.startsWith(t.projectPath + '/'))) continue
        if ((t.from === 'mine' && s.automation) || (t.from === 'automated' && !s.automation)) continue
        // At most once a minute per automation per session; never by its own sessions.
        const key = `hook|${d.id}|${s.id}`
        if (now - (this.sessionFired.get(key) ?? -Infinity) < SESSION_EVENT_GAP_MS) continue
        if (s.automation?.id === d.id) continue
        this.sessionFired.set(key, now)
        await this.fire(d, 'hook', e.at, this.ctxFor(d, s, null, e.at, e.line), { sessionId: s.id, detail: e.line.slice(0, 120) })
      }
    }
  }
  /** The file watches as the automations want them now: started, moved or stopped. */
  private syncWatchers(): void {
    if (!this.io.watch) return
    const want = new Map<string, string>()
    if (!this.s.paused) for (const d of this.s.automations) if (d.enabled && !d.bad && d.trigger.kind === 'file' && this.io.project(d.trigger.path)) want.set(d.id, d.trigger.path)
    for (const [id, w] of [...this.watchers]) {
      if (want.get(id) === w.key) continue
      w.stop()
      this.watchers.delete(id)
      this.fileChanges.delete(id)
    }
    for (const [id, path] of want) {
      if (this.watchers.has(id)) continue
      try {
        this.watchers.set(id, { key: path, stop: this.io.watch(path, (rel, ev) => this.fileEvent(id, rel, ev)) })
      } catch (e) {
        log('automation', id, 'file watch failed', (e as Error).message)
      }
    }
  }
  /** A file under a watched folder: kept until the folder has been quiet for 10 s. */
  fileEvent(id: string, rel: string, ev: FileEvent): void {
    const d = this.s.automations.find((x) => x.id === id)
    if (!d || d.trigger.kind !== 'file' || !d.enabled || this.s.paused) return
    const t = d.trigger
    const path = rel.split(sep).join('/').replace(/^\.\//, '')
    if (ev !== t.event || !path || path.split('/').some((p) => p === '.git' || p === 'node_modules' || p === '..') || isSecretFile(join(t.path, path))) return
    if (t.glob && !globMatch(t.glob, path)) return
    // What a run of it changes (or has just changed) doesn't fire it again.
    const now = this.io.now()
    if (this.runsOf(id).some((r) => isActive(r) || (r.endedAt != null && now - r.endedAt < OWN_WRITES_MS))) return
    const c = this.fileChanges.get(id) ?? { paths: new Map<string, FileEvent>(), more: 0, at: now }
    // A checkout or a build touching 100,000 files is counted, not kept: the payload names 50.
    if (c.paths.has(path) || c.paths.size < FILE_CHANGES_KEPT) c.paths.set(path, ev)
    else c.more++
    c.at = now
    this.fileChanges.set(id, c)
    this.io.wake(FILE_QUIET_MS)
  }
  private async fileTriggers(now: number): Promise<void> {
    this.syncWatchers()
    for (const [id, c] of [...this.fileChanges]) {
      if (now - c.at < FILE_QUIET_MS) continue
      this.fileChanges.delete(id)
      const d = this.s.automations.find((x) => x.id === id)
      if (!d || !d.enabled || this.s.paused) continue
      const paths = [...c.paths.keys()].sort()
      const detail = paths.length + c.more > 1 ? `${paths[0]} and ${paths.length - 1 + c.more} more` : paths[0]!
      await this.fire(d, 'file', c.at, this.ctxFor(d, null, null, c.at, paths.slice(0, FILE_PATHS_MAX).join('\n')), { detail: detail.slice(0, 120) })
    }
  }
  /** GitHub watches due a look: each looks on its own (gh takes a while), and fires when something is new. */
  private githubTriggers(now: number): void {
    if (!this.io.gh) return
    for (const d of this.s.automations) {
      const t = d.trigger
      if (t.kind !== 'github' || !d.enabled || d.bad || this.s.paused || this.ghLooking.has(d.id)) continue
      // gh runs in its folder only while the folder is still one automations may use.
      if (!this.io.project(t.repoPath)) continue
      if (d.ghLookAt != null && now - d.ghLookAt < t.every * MIN_MS) continue
      d.ghLookAt = now
      this.save()
      this.ghLooking.add(d.id)
      void this.ghLook(d.id)
        .catch((e) => log('automation', d.id, 'github look failed', (e as Error).message))
        .finally(() => this.ghLooking.delete(d.id))
    }
  }
  /** One look: what wasn't there before fires it (the first look after a save only marks where it starts). */
  async ghLook(id: string): Promise<void> {
    const d0 = this.s.automations.find((x) => x.id === id)
    if (!d0 || d0.trigger.kind !== 'github' || !this.io.gh) return
    const want = JSON.stringify(d0.trigger)
    // In the folder as the path rule finds it now (a link put there since leads nowhere else).
    const repo = this.io.project(d0.trigger.repoPath)
    if (!repo) return
    const items = await this.io.gh(repo, d0.trigger.events).catch(() => null)
    // Changed while it looked: the next look is the new one's.
    const d = this.s.automations.find((x) => x.id === id)
    if (!d || d.trigger.kind !== 'github' || JSON.stringify(d.trigger) !== want) return
    if (!items) {
      if (!this.ghBad.has(id)) log('automation', id, 'gh is missing or signed out')
      this.ghBad.add(id)
      return this.settle()
    }
    this.ghBad.delete(id)
    const events = d.trigger.events
    const wanted: GhItem[] = items.filter((x) => events.includes(x.event))
    const seen = new Set(d.ghSeen ?? [])
    const first = d.ghSeen == null
    const fresh = first ? [] : wanted.filter((x) => !seen.has(x.key))
    // What it has seen stays seen (a list that comes back shorter for a moment doesn't fire it again).
    d.ghSeen = [...new Set([...(d.ghSeen ?? []), ...wanted.map((x) => x.key)])].slice(-1000)
    this.save()
    if (fresh.length && d.enabled && !this.s.paused) {
      const now = this.io.now()
      await this.fire(d, 'github', now, this.ctxFor(d, null, null, now, fresh.map((x) => x.line).join('\n')), { detail: fresh[0]!.line.slice(0, 120) })
    }
    this.settle()
  }
  /**
   * A fire token checked before anything else is looked at (the local route calls this before it
   * reads the body): 401 `bad_token` alike whether the automation is missing, isn't fired by a
   * webhook or has another token, so a caller without its token learns nothing about which
   * automations exist. The hashes are compared in constant time.
   */
  fireAuth(id: string, token: string): void {
    const t = this.s.automations.find((x) => x.id === id)?.trigger
    const kept = t?.kind === 'webhook' && t.tokenHash.length === 64 ? t.tokenHash : null
    const same = timingSafeEqual(Buffer.from(sha(token)), Buffer.from(kept ?? '0'.repeat(64)))
    if (!kept || !same) throw new AutomationError(401, 'bad_token', 'That isn’t this automation’s token.')
  }
  /**
   * A webhook trigger fired (`POST …/fire`, the CLI), as the event would fire it, its body as
   * {payload}. [token]: the fire token the local route was given (null: a paired device, or the
   * admin token). 401 `bad_token` (with a token: also for an automation that isn't there or isn't
   * a webhook's); 404 `no_automation`, 409 `not_webhook` (a device or the admin token); 429
   * `too_often`; 409 `paused`, `off`.
   */
  async fireWebhook(id: string, payload: string, token: string | null): Promise<Run> {
    if (token != null) this.fireAuth(id, token)
    const d = this.def(id)
    if (d.trigger.kind !== 'webhook') throw new AutomationError(409, 'not_webhook', 'It isn’t fired by a webhook: use Run now.')
    // A loop firing it (a script gone wrong, or anything holding its token) is held off: past
    // FIRES_PER_MIN a minute nothing is recorded, logged (once a minute) or told to the phone.
    const at = this.io.now()
    const recent = (this.fires.get(id) ?? []).filter((t) => at - t < MIN_MS)
    if (recent.length >= FIRES_PER_MIN) {
      this.fires.set(id, recent)
      if (at - (this.fireTold.get(id) ?? -Infinity) >= MIN_MS) {
        this.fireTold.set(id, at)
        log('automation', id, 'fired too often: refused for a moment')
      }
      throw new AutomationError(429, 'too_often', `It was fired ${FIRES_PER_MIN} times in the last minute. Wait a moment.`)
    }
    recent.push(at)
    this.fires.set(id, recent)
    if (this.s.paused) throw new AutomationError(409, 'paused', 'Automations are paused.')
    if (!d.enabled) throw new AutomationError(409, 'off', 'It’s switched off.')
    const now = this.io.now()
    const r = await this.fire(d, 'webhook', now, this.ctxFor(d, null, null, now, payload || null))
    this.settle()
    if (!r) throw new AutomationError(409, 'off', 'It didn’t run.')
    log('automation', id, 'fired by its webhook', token != null ? 'locally' : 'by a device')
    return this.runView(r)
  }
  /** `POST /v1/automations/check`: a trigger as a save would take it, and its next three due times. */
  async checkTrigger(b: any): Promise<{ trigger: object; next: number[] }> {
    const now = this.io.now()
    const t = this.trigger(b?.trigger, now, true, null)
    await this.checkGithub(b)
    const next = t.kind === 'once' ? (t.at > now ? [t.at] : []) : isTimeTrigger(t) ? nextOccurrences(t, now, this.io.tz(), 3) : []
    return { trigger: t.kind === 'webhook' ? { kind: 'webhook' } : t, next }
  }

  // ── PermissionDenied (hooks.ts, runner.ts) ──────────────────────────────
  /** "Allowed only" refused a call in a run's session: kept as the rule it would need, 10 at most. */
  denied(sessionId: string, tool: string, input: unknown): void {
    const r = [...this.runs.values()].filter((x) => x.sessionIds.includes(sessionId)).sort((a, b) => (b.startedAt ?? 0) - (a.startedAt ?? 0))[0]
    if (r && this.addRefused(r, tool, input)) this.settle()
  }
  private addRefused(r: RunRec, tool: string, input: unknown): boolean {
    const rule = ruleFor(tool, input)
    if (!tool || r.skippedTools.includes(rule) || r.skippedTools.length >= 10) return false
    r.skippedTools.push(rule)
    this.dirtyRuns(r)
    return true
  }

  // ── Routes ──────────────────────────────────────────────────────────────
  /** `/v1/automations…` (docs/PROTOCOL.md, "Automations"). */
  async api(path: string, method: string, query: URLSearchParams, body: () => Promise<any>, who: AnswerBy = 'phone'): Promise<object> {
    const saved = (automation: Automation) => {
      const token = this.takeToken()
      return token ? { automation, token } : { automation }
    }
    if (path === '/v1/automations' && method === 'GET') return { automations: this.list(), paused: this.s.paused, features: this.features() }
    if (path === '/v1/automations' && method === 'POST') {
      const b = await body()
      await this.checkModel(b)
      await this.checkGithub(b)
      return saved(this.create(b))
    }
    if (path === '/v1/automations/paused' && method === 'POST') return { paused: this.setPaused((await body())?.paused) }
    if (path === '/v1/automations/check' && method === 'POST') return this.checkTrigger(await body())
    const runOp = /^\/v1\/automations\/runs\/([\w-]+)\/(stop|retry|answer)$/.exec(path)
    if (runOp && method === 'POST') {
      const [, runId, op] = runOp
      if (op === 'stop') return { run: await this.stopRun(runId!) }
      if (op === 'retry') return { run: await this.retry(runId!, (await body().catch(() => null))?.fromStep ?? null) }
      return { run: await this.answer(runId!, await body(), who) }
    }
    const one = /^\/v1\/automations\/([\w-]+)(?:\/(enabled|run|runs|token))?$/.exec(path)
    if (one) {
      const [, id, sub] = one
      if (!sub && method === 'PUT') {
        const b = await body()
        const before = this.def(id!)
        await this.checkModel(b, before)
        await this.checkGithub(b)
        return saved(this.replace(id!, b))
      }
      if (!sub && method === 'DELETE') {
        await this.remove(id!)
        return { ok: true }
      }
      if (!sub && method === 'GET') return { automation: this.view(this.def(id!)) }
      if (sub === 'enabled' && method === 'POST') return { automation: this.setEnabled(id!, (await body())?.enabled) }
      if (sub === 'run' && method === 'POST') return { run: await this.runNow(id!) }
      if (sub === 'token' && method === 'POST') return this.newToken(id!)
      if (sub === 'runs' && method === 'GET') {
        const before = query.has('before') ? Number(query.get('before')) : null
        return this.runsList(id!, Number.isFinite(before) ? before : null, Number(query.get('limit') ?? 30))
      }
    }
    throw new AutomationError(404, 'not_found', 'Unknown endpoint.')
  }
}
