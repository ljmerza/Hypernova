# Automations v1: design spec

The owner: "a new category like scheduled tasks - where user could make some automation workflow
like in apple automation app". Agreed (DECISIONS, "The owner's second round of notes"): a tab of
"When … → Do …" cards; they run on the Mac, so they work with the phone off; every run keeps its
transcript; the permission mode is the user's choice per automation, with a clear warning for the
modes that skip asking.

The bar: you set "Every weekday at 9:00, in hypernova, ask Claude to summarise what changed" once,
and from then on your phone tells you at 9:02 what happened, with nothing to babysit.

## 1. Decisions at a glance

- **One trigger → one action, plus "when it's done".** A session action can notify you or read the
  result aloud on the glasses when it finishes. No multi-step chains in v1 (LATER).
- **Triggers:** at a time (once, chosen days), every N hours (optionally between two times), a
  limit resetting, a session finishing or needing you, the Mac plugged in, the Mac's battery low.
  Any event trigger can be "only the next time".
- **Actions:** start a session (project, prompt, model, provider, permission mode), run a skill or
  command (a start whose prompt is `/name args`), continue a session (a chosen one, the one that
  fired it, or every working one), notify me, read aloud on my glasses.
- **Runs are ordinary shared-terminal sessions** started by the daemon, titled
  `Morning brief, Thu 16 Oct`, with an `AUTO` chip. Open, watch, answer and continue them like any
  session; their transcript is Claude Code's own.
- **Default permission mode for a new automation: "Allowed only"** (Claude Code's `dontAsk`: runs
  what your settings and the automation's own allow list permit, skips the rest without asking), so
  a run never hangs and never does anything you didn't allow. "Auto" carries an amber warning,
  "No asking" (`bypassPermissions`) a red one plus a fingerprint check.
- **No waking the Mac.** A run due while the Mac sleeps runs when it wakes, up to 6 hours late,
  else it's recorded as missed. While a run works, the daemon holds an idle-sleep assertion.
- **Two runs at once at most**; more wait in line. A run never overlaps its own last one.
- **Limits:** a run waits for a reset that comes before its next turn (6 hours at most); otherwise
  it's skipped. Never on paid usage credits. Signed out or out of credit: skipped, one notice per
  episode.
- **"Nothing new."** A run whose final reply is exactly that notifies nobody, so polling
  automations (pull requests every 2 hours) stay quiet until something matters.
- Editing is on the phone only. The Mac popover has one quiet line and a pause switch; the glasses
  read results aloud when asked to.

## 2. How this differs from Claude Code's own scheduling

| | Runs where | Survives | Triggers | Sees |
|---|---|---|---|---|
| `/schedule` (cloud routines, `RemoteTrigger`) | Anthropic's cloud, on a fresh clone of a GitHub repo | everything, the Mac can be off | cron, GitHub events | pushed code only: no local files, unpushed work, local servers, tools or keys |
| `/loop`, session cron (`CronCreate`) | inside one running Claude Code session, only while it's idle | nothing: in memory, gone when the session exits; recurring jobs expire after 7 days | cron with jitter | that session |
| `claude --bg`, `claude -p` from your own launchd | your Mac | as you build it | whatever you script | everything, with no UI |
| **Hypernova automations** | your Mac, as normal shared sessions | restarts, sleep (catch-up), reboots | time, limits, sessions, power | your real working copy, tools, keys and dev servers; watched, answered and continued from the phone and glasses |

Ours is the one for work on your own machine with your phone in the loop. Work that must run while
the Mac is off is `/schedule`'s job; a "run in the cloud" option is parked (LATER).

## 3. Protocol

All shapes follow PROTOCOL.md's rules (camelCase, ignore unknown fields and enum values). Times of
day are the **Mac's** local time; `Snapshot.mac.tz` names its zone.

### Types

```ts
Automation = {
  id: string,                    // "au_" + 10 lowercase hex
  name: string,                  // 1–60 characters
  enabled: boolean,
  onlyOnce: boolean,             // turns itself off after its first run that starts (implied for "once")
  trigger: Trigger,
  action: Action,
  template: string | null,       // "brief" | "prs" | "plugged" | "battery" | "reset", or null
  createdAt: number, updatedAt: number,          // epoch ms
  nextAt: number | null,         // next due time (time triggers); null when off, paused or event-driven
  lastRun: Run | null,
  runCount: number,              // skips and misses included
  problem: null | "no_battery" | "no_project" | "no_session" | "no_provider" | "no_glasses"
}

Trigger =
  | { kind: "time",  at: "HH:MM", days: number[] }   // ISO days 1 (Mon)–7 (Sun), sorted, 1–7 of them
  | { kind: "once",  at: number }                    // epoch ms on a whole minute; in the future when saved
  | { kind: "every", hours: 1|2|3|4|6|8|12, from: "HH:MM", to: "HH:MM" | null, days: number[] }
                     // from, from+hours, … up to and including `to` (null: around the clock), on `days`
  | { kind: "reset", window: "five_hour" | "seven_day", afterLimit: boolean }
  | { kind: "session", event: "finished" | "needs_you", projectPath: string | null,
      from: "mine" | "automated" | "any" }           // mine: sessions no automation started
  | { kind: "power", event: "plugged_in" }
  | { kind: "power", event: "low", below: number }   // 5–50, step 5, default 20

Then = "none" | "notify" | "say"

Action =
  | { kind: "start", projectPath: string, prompt: string,   // 1–8000 chars; "/name args" runs a skill or command
      providerId: string, model: string | null,
      permissionMode: "dontAsk" | "default" | "acceptEdits" | "auto" | "bypassPermissions",
      allowedTools: string[],                               // Claude Code rules, 0–20, e.g. "Bash(git log:*)"
      then: Then }
  | { kind: "continue", target: string | "trigger" | "working",  // a session id | the one that fired it | every working one
      prompt: string, then: Then }
  | { kind: "notify", text: string }                        // 1–500 chars
  | { kind: "say", text: string }                           // 1–500 chars, read aloud on the glasses

// Placeholders in prompt and text, filled when it runs (unknown ones stay as typed):
// {project} {session} (title of the session that fired it) {result} (that session's last reply,
// two sentences) {ask} (what it asks: "Bash: git push origin main") {battery} ("14%") {time} ("9:00")

AutomationInput = { name, enabled, onlyOnce, trigger, action, template?, confirmBypass?: true }

Run = {
  id: string,                    // "ru_" + 10 hex
  automationId: string, name: string,      // its name when it ran
  fired: { by: "schedule" | "reset" | "session" | "power" | "manual",
           at: number,           // when it was due or the event happened
           sessionId: string | null },     // the session that fired it
  state: "queued" | "waiting" | "running" | "needs_you" | "done" | "failed" | "skipped" | "stopped",
  reason: RunReason | null,
  until: number | null,          // waiting: the time it waits for (a reset)
  sessionIds: string[],          // sessions it started or prompted
  startedAt: number | null, endedAt: number | null,
  late: number,                  // ms it started after fired.at (0 when on time)
  missed: number,                // due times the Mac slept through, folded into this run
  result: string | null,         // final reply's first two sentences, ≤ 300 chars, keys masked
  quiet: boolean,                // the reply was exactly "Nothing new.": nobody was told
  skippedTools: string[],        // what "Allowed only" refused, as rules ("Bash(npm test)"), ≤ 10
  permissionMode: string | null, // the mode the session really ran in (hooks), when it started one
  depth: number                  // 0; or 1 + the depth of the run whose session fired it
}
RunReason = "overlap" | "queue_full" | "daily_cap" | "stale" | "limited" | "signed_out" | "no_plan"
  | "on_hold" | "no_credit" | "provider" | "missed" | "no_project" | "no_session" | "no_provider"
  | "target_busy" | "loop" | "start_failed" | "away" | "blocked" | "error" | "timeout" | "by_user"

Note = { id: string, automationId: string | null, runId: string | null, name: string, text: string,
         to: "phone" | "glasses", at: number }    // a notification an automation sends

// Additions to existing types
Snapshot.mac.tz: string                           // IANA, "Europe/Moscow"
Snapshot.automations?: { paused: boolean, list: Automation[], running: number, queued: number,
                         notes: Note[],           // the last 12 hours, ≤ 20, newest first
                         glasses: boolean }       // a Lens device is paired
Session.automation: { id, name, runId } | null
Session.permissionMode gains "dontAsk"
```

Absent `Snapshot.automations` means a daemon without automations: the phone tab says so.

### REST (phone port and, with the admin token, the Mac app's)

| Method & path | Body → Response |
|---|---|
| `GET /v1/automations` | → `{automations: Automation[], paused}` |
| `POST /v1/automations` | `AutomationInput` → `{automation}` |
| `PUT /v1/automations/{id}` | `AutomationInput` → `{automation}` (whole replace; a run in progress keeps the old definition) |
| `DELETE /v1/automations/{id}` | → `{ok}` (its runs go too; its sessions stay) |
| `POST /v1/automations/{id}/enabled` | `{enabled}` → `{automation}` |
| `POST /v1/automations/{id}/run` | `{}` → `{run}`: Run now, also while paused or off; same gates as a fired run except overlap answers 409 `running` and an account problem answers 409 `limited`, `signed_out`, `no_plan`, `on_hold`, `no_credit`, `provider` with the reason in `message` |
| `GET /v1/automations/{id}/runs?before=<ms>&limit=30` | → `{runs: Run[]}` newest first (limit 1–100) |
| `POST /v1/automations/runs/{runId}/stop` | `{}` → `{run}`: a queued or waiting run is dropped; a running one gets Esc in each of its sessions; `stopped`, `by_user` |
| `POST /v1/automations/paused` | `{paused}` → `{paused}`: pause or resume all |

Errors (400): `bad_name`, `bad_trigger`, `bad_time`, `bad_days`, `past_time`, `bad_action`,
`no_prompt`, `no_project` (path must pass the daemon's `allowedPath` rule), `no_session`,
`no_provider`, `bad_mode`, `confirm_bypass` (`bypassPermissions` without `confirmBypass: true`),
`bad_rule`, `rule_too_broad` (bare `Bash`, `Edit`, `Write`, `MultiEdit`, `NotebookEdit`, or `Bash(*)`,
`Bash(:*)`), `no_battery` (power trigger on a Mac without one), `no_glasses` (`say` with none
paired), `too_many` (50 automations). 404 `no_automation`, `no_run`.

### WebSocket

- `{t:"automation", automation}`: added or changed (also `nextAt` and `lastRun` moving).
- `{t:"automation.removed", id}`
- `{t:"automations", paused, running, queued}`
- `{t:"run", run}`: a run was created or changed.
- `{t:"note", note}`: an automation's notification. Phones show `to: "phone"`, Lens `to: "glasses"`.

### Mac app

`GET /local/status` gains `automations: {paused, count, next: {id, name, at} | null,
running: [{id, name, sessionId, startedAt}]}`. Pause uses `POST /v1/automations/paused`.

## 4. Daemon

New `daemon/src/automations.ts` (store, triggers, gates, runs, routes) and `daemon/src/schedule.ts`
(pure time maths, no I/O, an injectable clock).

### Store

- `<support>/automations.json` (0600, `writeJson`): `{version: 1, paused, maxRunning: 2,
  automations: [Automation definition + firing state: firedFor (last occurrence key),
  resetFiredFor, lowArmed, lastPluggedAt]}`. Written on every change.
- `<support>/automation-runs.json` (0600): runs, the newest 50 per automation, none older than 90
  days; written at most once a second while runs change.
- Prompts and results are never logged; the log names ids and states only.
- A run older than its session's transcript (Claude Code removes them after `cleanupPeriodDays`,
  30 by default) keeps its row; the phone says the transcript is gone.

### Time

- One timer, set to the earliest `nextAt`, never longer than 30 s, so sleep, clock and zone changes
  are seen within 30 s of waking.
- Every occurrence has a key (`2026-10-16T09:00`, Mac-local); it fires at most once, across
  restarts and clock changes.
- Daylight saving: a local time that doesn't exist that day (02:30 on the spring change) runs at the
  first minute after the gap; one that happens twice runs on the first.
- `every`: occurrences are `from + k·hours` while ≤ `to` (or all day), on `days`; all allowed
  `hours` divide 24, so the grid is the same each day.
- `once`: after its run starts (or is missed), `enabled: false`, `nextAt: null`; the card shows when
  it ran. Editing the time turns it on again.

### The Mac asleep, off, or the daemon down

- **No wake.** launchd can't wake a sleeping Mac (`StartCalendarInterval` only runs a missed job
  after it wakes, which our catch-up already does); `pmset schedule wake` needs root, so a
  privileged helper and a signed build (LATER L6). Lid-closed sleep on battery can't be held off by
  an app at all.
- **Catch-up.** When the timer finds `nextAt` more than 90 s past (after a gap in its ticks: the Mac
  slept; at start: the Mac was off or Hypernova wasn't running): count the occurrences missed and
  take the latest, L. If `now − L ≤ min(6 h, the trigger's period)` and the next occurrence is more
  than 15 minutes away, run once now with `late = now − L` and `missed` = the others. Otherwise
  record one `skipped` run, `reason: "missed"`, `missed` = the count, and move on. A reset that
  passed during sleep follows the same 6-hour rule; event triggers aren't caught up.
- **Awake while working.** While any run is `running` or `needs_you`, the daemon runs
  `/usr/bin/caffeinate -i -w <daemon pid>` (idle sleep only; it dies with the daemon) and stops it
  when the last such run ends.
- **Paused** time is never caught up; resuming starts from the next occurrence.

### Triggers from events

`hub.ts` gets a tap on `broadcast`: `tapBroadcast(fn)` hands every message object to automations.ts
before it's sent. Sessions, approvals, usage, account and battery messages arrive that way, so no
other module calls into automations, and the battery work (`{t:"battery"}`, being built) needs no
new API.

- **finished:** a session goes from `working` (or `needs_you`, `starting`) to `idle`, and 5 s later
  it's still idle with `agentsRunning` 0, an empty queue, no pending approvals and `blocked` null:
  the same moment the phone says "Finished".
- **needs_you:** a new approval (the main agent's at once; an agent's only if still waiting after
  10 s, as the phone does), or a new terminal `menu` with no approval open. Once per approval or
  menu id; `{ask}` is its summary or the menu's title.
- `projectPath`: the session's `cwd` is that folder or inside it. `from`: `Session.automation`
  null is "mine".
- At most once a minute per automation per session.
- **reset:** the daemon remembers each window's `resetsAt` from `Usage` (source `official` only).
  60 s after it passes, the window fires once per `resetsAt` if (`afterLimit`) an account limit
  episode named that window with that reset (within 2 minutes), else if its `pct` was above 0.
- **plugged_in:** `battery.onAC` false → true; at most once per 10 minutes (a loose cable).
- **low:** `percent ≤ below` while not charging and not on AC, once per discharge: re-armed when
  charging or at `below + 5`.
- A Mac whose snapshot has no battery: power automations get `problem: "no_battery"` and never fire.

### Gates, in order (start and continue actions)

1. **Paused** (global) or the automation off: nothing happens, nothing is recorded.
2. **Loop:** the firing session belongs to a run of the same automation → ignored silently; a run
   whose `depth` would exceed 2 → `skipped`, `loop` (recorded once per chain).
3. **Daily cap:** 24 session runs per automation per day → `skipped`, `daily_cap` (once that day).
4. **Overlap:** a run of the same automation is queued, waiting, running or needs you → `skipped`,
   `overlap`.
5. **Target:** project folder gone → `failed`, `no_project`; session gone → `failed`, `no_session`;
   provider gone → `failed`, `no_provider`.
6. **Account** (built-in provider; another provider uses its `status` the same way):
   - `ok` → go on.
   - `limited`, also while Claude would carry on on usage credits (`usingCredits`): if the reset is
     known and comes before both the next occurrence and 6 hours from now → `waiting`,
     `until = resetsAt`, gates again 60 s after it (a second limit then → `skipped`, `limited`);
     else `skipped`, `limited`.
   - `logged_out` → `signed_out`; `no_subscription` → `no_plan`; `on_hold`; `no_credit` →
     `skipped` with that reason. A provider's `bad_key` or `no_credit` → `skipped`, `provider`.
   - Account skips send one note per account episode (`account.since`) for all automations
     together: "Automations are skipping runs: Claude Code is signed out on the Mac."
7. **Slots:** fewer than `maxRunning` runs `running` or `needs_you` (a run whose session waits on a
   limit mid-run keeps its slot) → start; else `queued`, oldest first. More than 10 queued →
   `skipped`, `queue_full`; queued over 6 hours → `skipped`, `stale`.

`notify` and `say` pass only gates 1 to 3 (cap 200 a day) and finish at once. `continue` takes no
slot unless it has to resume an ended session.

### Running

- **Start:** `createSharedSession` (SDK fallback `createSession` with the same options) gains
  `automation`, `appendSystemPrompt`, `allowedTools` and `name`, which become
  `--permission-mode <mode> --allowedTools <rule> … --name "<name> · Thu 16 Oct"
  --append-system-prompt <text> --settings <PermissionDenied hook> -- <prompt>` (the SDK:
  `permissionMode`, `allowedTools`, `systemPrompt: {type: "preset", preset: "claude_code", append}`,
  and its own denial messages). Check the argv forms against `claude --help` of the installed
  version (2.1.300 has all five) before relying on them. `ManagedSession` keeps
  `automation: {id, name, runId}`, so the badge survives restarts. The appended text, exactly:

  > This session was started by a Hypernova automation named "<name>", not typed by the user just
  > now, and nobody is watching it live. Work on your own: don't ask the user questions; make
  > reasonable choices and mention them. Start your final reply with one sentence that sums up the
  > outcome: it is sent to the user's phone. If nothing needs the user's attention, make your
  > final reply exactly: Nothing new.

- **Continue:** the prompt goes the way `POST /v1/sessions/{id}/prompt` sends it (extract that
  route's body into `promptSession(id, text)` in http.ts and call it from both). A 409
  (`needs_answer`, `waiting`, `typing`, `starting`, `terminal_busy`) is retried every 30 s for 10
  minutes, then `skipped`, `target_busy` (`waiting` meanwhile). `working`: every session working or
  with agents at work, none of this automation's; none → `done`, result "No sessions were
  working." The session keeps its own permission mode.
- **Following:** the run mirrors its sessions: `needs_you` while one has approvals or a menu;
  `waiting` while one is `blocked` with a reset (`until` = `autoResumeAt` ?? `resetsAt`); `done` on
  the finished rule above (all of them, for several); `stopped`, `away` when one stops for nobody
  answering in 10 minutes; `failed`, `blocked` for a block with no reset (signed out mid-run);
  `failed`, `error` when the session ends on an error; `stopped`, `timeout` after 12 hours (the
  session is left alone). A daemon restart picks runs back up from the store.
- **Result:** the last assistant event's first two sentences, at most 300 characters, masked with
  the title rules. `quiet` when it is exactly "Nothing new." (trimmed). `skippedTools`: from Claude
  Code's `PermissionDenied` hook (2.1.300 fires it when `dontAsk`, a deny rule or the auto
  classifier refuses a call), as `Bash(<command>)` or the tool's name. The hook is passed **per run**
  in `--settings` (an http hook to `/hooks/<secret>`, like the global ones), so the owner's global
  install doesn't change and older installs never look unconnected. Capture a real payload as a
  fixture before writing the handler.
- **Then:** unless `quiet`, `notify` sends a note to the phone, `say` to the glasses (`{name}:
  {result}`); with no Lens connected right now, `say` goes to the phone instead.
- **Tidy:** a run's shared terminal is ended 15 minutes after the run is `done` if nobody has it open
  (`term.mac` and `term.phone` 0). The session stays in History and resumes on the next prompt.
- **Notes** are broadcast as `{t:"note"}` and kept 12 hours for `Snapshot.automations.notes`, so a
  phone that was off finds them when it reconnects.

## 5. Phone (Android)

### Navigation

A bottom tab between Sessions and History: `SESSIONS · AUTOMATIONS · HISTORY · STATS · SETTINGS`
(Stats is being added by another track). With five labels the row sizes each tab to its label and
shares the free width evenly; if a gap would fall under 8 dp (narrow phone, large font), all labels
drop from 0.18 em to 0.08 em tracking. The sky hides on this tab, as on History. The bottom pill is
`+ New automation` here (`+ New session` stays on Sessions). The editor is a route,
`Route.AutomationEdit(id: String?, template: String?)`.

### The tab

```
 ◉ hypernova/                              ( ● tailnet · MacBook )

 ◉ AUTOMATIONS ───────────────────── 3 on · next 9:00 ( Pause all )

 ╭───────────────────────────────────────────────────────────────╮
 │ ◉ MORNING BRIEF ──────────────────────────────────────  (●━)  │
 │ Every weekday at 9:00, in hypernova, ask Claude to            │
 │ summarise what changed in this project since yesterday…       │
 │                                                               │
 │ ✓ Today 9:00 · 2m 14s                                         │
 │   3 PRs merged; the parser tests fail on main since 2a41f.    │
 │ Next Mon 9:00                                                 │
 ╰───────────────────────────────────────────────────────────────╯
 ╭───────────────────────────────────────────────────────────────╮
 │ ◉ WRAP UP ON LOW BATTERY ─────────────────────────────  (●━)  │   planet turning, mint
 │ When the Mac's battery drops to 15%, ask every working        │
 │ session: “The Mac's battery is at {battery}. Finish the…”     │
 │ RUNNING · 1m 12s · 2 sessions                               › │
 ╰───────────────────────────────────────────────────────────────╯
 ╭───────────────────────────────────────────────────────────────╮
 │ ◉ PULL REQUEST WATCH ─────────────────────────────────  (━○)  │   off: whole card at 55%
 │ Every 2 hours, 9:00–19:00 on weekdays, in hypernova, …        │
 │ Off                                                     [AUTO]│
 ╰───────────────────────────────────────────────────────────────╯

 ◉ START FROM ─────────────────────────────────────────────────
 ╭─────────────────────────────╮ ╭─────────────────────────────╮
 │ ◷ Tests when plugged in     │ │ ✶ When my limit resets      │
 │ Run the tests when you plug │ │ Hand Claude a task for when │
 │ the Mac in                  │ │ your limit comes back       │
 ╰─────────────────────────────╯ ╰─────────────────────────────╯

                                              ( + New automation )
 SESSIONS   AUTOMATIONS   HISTORY   STATS   SETTINGS
```

- **Card** = GlassCard. Seam: the activity planet (still; turning while a run works; amber in its
  bracket when the run needs you), the name in tracked caps, a hairline, the on/off switch (48 dp
  target). Body: the summary sentence (body 15, ink, 3 lines then "…"). Then one status line:
  - running: `RUNNING · 1m 12s` in accent (`· 2 sessions` when several); needs you:
    `NEEDS YOU · Bash: git push` in amber; waiting: `Waiting for your limit · back at 14:00`;
    queued: `Waiting for a free slot`;
  - else the last run: drawn glyph (check, cross, dash) + `Today 9:00 · 2m 14s`, and its result on
    the next line in muted body 13 (2 lines), or `Nothing new` in dim;
  - then `Next Mon 9:00` (clock times, as the widgets: `today 9:00`, `Mon 9:00`, `17 Oct 9:00`), or
    for event triggers nothing; a `problem` replaces it in amber ("This Mac has no battery.").
  - A mode chip on the right for anything but the default "Allowed only": `ASKS ME`, `EDITS`,
    `AUTO` (amber outline), `NO ASKING` (red outline).
- Tap → the editor. Tap the status line of a running run → its session.
- Order: needs you, running, waiting, then by `nextAt` (soonest first), event-driven ones, off ones.
- Header trail: `3 on · next 9:00`; `Pause all` / `Resume` ghost pill. Paused: the trail reads
  `Paused` in amber and cards keep their switches but show `Paused` in place of Next.
- **START FROM** lists the templates not yet used (by `template`), two per row; with no
  automations it comes first, under one quiet paragraph: "Let Claude work on a schedule, or when
  something happens on your Mac. They run on the Mac, so your phone can be off."
- An old daemon: one line, "Update Hypernova on the Mac to use automations."

### The editor

```
 ‹  Morning brief                               ( Run now ) ( Save )

 Every weekday at 9:00, in hypernova, ask Claude to summarise what
 changed in this project since yesterday… Notify me when it's done.

 ◉ WHEN ────────────────────────────────────────────────────────
 ╭───────────────────────────────────────────────────────────────╮
 │ ◷  At a time                                         Change › │
 │ ( 9:00 )     M   T   W   T   F   S   S                        │
 │              ●   ●   ●   ●   ●   ○   ○                        │
 │ Runs while the Mac is awake. If it's asleep at 9:00, it runs  │
 │ when it wakes, up to 6 hours late.                            │
 ╰───────────────────────────────────────────────────────────────╯
 ◉ DO ──────────────────────────────────────────────────────────
 ╭───────────────────────────────────────────────────────────────╮
 │ ▶  Start a session                                   Change › │
 │ PROJECT     hypernova                                       › │
 │ ┌───────────────────────────────────────────────────────────┐ │
 │ │ Summarise what changed in this project since yesterday:   │ │
 │ │ commits, branches, open work, anything failing. Then say  │ │
 │ │ what's worth doing next. Don't change anything.           │ │
 │ └───────────────────────────────────────────────────────────┘ │
 │ MODEL       [Default]  Opus  Sonnet  Haiku                    │
 │ WHEN IT'S DONE  [Notify me]  Read aloud  Nothing              │
 ╰───────────────────────────────────────────────────────────────╯
 ◉ PERMISSIONS ─────────────────────────────────────────────────
 ╭───────────────────────────────────────────────────────────────╮
 │ ◉ Allowed only                                                │
 │   Runs only what your Claude Code settings and this list      │
 │   allow. Anything else is skipped, never asked.               │
 │   [git log:*] [git diff:*] [git status:*] [git show:*]  ( + ) │
 │ ○ Ask me                                                      │
 │ ○ Edits on its own                                            │
 │ ○ Auto                                                        │
 │ ○ No asking                                                   │
 ╰───────────────────────────────────────────────────────────────╯
 ◉ NAME ────────────────────────────────────────────────────────
   Morning brief
 ◉ RUNS ──────────────────────────────────────── 12 · 1 skipped
   ✓  Today 9:00     2m 14s   3 PRs merged; the parser tests…   ›
   ✓  Wed 9:00       1m 50s   Nothing new                        ›
   –  Tue 9:00       Skipped · usage limit until 11:00
   ✓  Mon 9:42       2m 01s   Ran late · the Mac was asleep at 9:00

                                                       ( Delete )
```

- **Summary line** (heading 16, ink) is rebuilt as you edit (see Copy). It's the screen's title
  for TalkBack too.
- **WHEN / DO** are glass blocks; `Change ›` opens a glass sheet of tiles (2 columns, 72 dp tall,
  a drawn hairline icon over a short label; tiles that can't apply are ghosted with the reason
  under them):
  - WHEN: `At a time` · `Every few hours` · `My limit resets` · `A session finishes` ·
    `A session needs me` · `The Mac is plugged in` · `The Mac's battery is low` (ghosted: "This Mac
    has no battery").
  - DO: `Start a session` · `Run a skill or command` · `Continue a session` · `Notify me` ·
    `Read aloud on my glasses` (ghosted: "Pair your glasses first").
- **Parameters**, inline in the block:
  - At a time: a time pill (opens the system time picker in a glass sheet, 12/24 h as the phone),
    seven day chips (36 dp, narrow day letters; at least one stays on), a `Once` chip that swaps the
    days for a date pill. When the phone's zone differs from the Mac's: "9:00 on the Mac
    (Europe/Moscow)".
  - Every few hours: segmented `1 2 3 4 6 8 12` hours, `From 9:00` and `To 19:00` pills, an
    `Around the clock` switch (hides To), the day chips.
  - My limit resets: segmented `5-hour | Weekly`, switch `Only after I hit it` (on).
  - A session finishes / needs me: project row (`Any project` or one), segmented
    `Mine | Automated | Any`.
  - Battery low: a slider 5–50 % (step 5), "At 20%".
  - Every event trigger: switch `Only the next time`.
  - Start / Run a skill: project row (projects, most recent first, as the new-session sheet), the
    prompt box with the same "/" list as the composer (Run a skill opens with "/" typed), model
    segmented (the provider's choices), provider row when there's more than one, WHEN IT'S DONE
    segmented. A prompt that is only "/name" shows the command's argument hint.
  - Continue: a session row that opens a list: `The session that fired it` (session triggers
    only), `Every working session`, then live sessions and the last 7 days of History. Prompt box.
    A line: "Runs in the session's own permission mode."
  - Notify / Read aloud: a text box, with the placeholders offered as chips under it
    (`{project}` `{session}` `{result}` …) that insert at the cursor.
- **PERMISSIONS** (start actions only): five radio rows, title + one line; the chosen one opens its
  detail. "Allowed only" shows its rules as removable chips plus `( + )` (a field: "Bash(npm test)").
  "Auto" and "No asking" open a warning box (8% fill, 35% ring, amber or red, as notices):

  ```
  │ ◉ Auto                                                        │
  │ ┌ ▲ ──────────────────────────────────────────────────────┐ │
  │ │ Runs commands and edits files without asking you. Claude  │ │
  │ │ Code holds back only what looks risky to it, and nobody   │ │
  │ │ is watching this run.                                     │ │
  │ └───────────────────────────────────────────────────────────┘ │
  │ ◉ No asking                                                   │
  │ ┌ ▲ ──────────────────────────────────────────────────────┐ │   red
  │ │ Runs any command and changes any file without asking,     │ │
  │ │ with nobody watching. A page it reads could talk it into  │ │
  │ │ deleting or sending things. Use it only in a project you  │ │
  │ │ can afford to lose.                                       │ │
  │ └───────────────────────────────────────────────────────────┘ │
  ```

  Saving with "No asking" asks for the fingerprint or screen lock (framework `BiometricPrompt`,
  `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, no new dependency): title "Let an automation run without
  asking", subtitle the automation's name. The request then carries `confirmBypass: true`.
- **NAME**: filled from the template, else the prompt's first four words; required.
- **RUNS** (saved automations): the last 30, `Load more` after. Row: drawn glyph, when it ran
  (`Mon 9:42`), duration, result or reason (Copy, below). Tap → the run's first session; with a
  transcript gone: toast "Claude Code has removed this transcript (it keeps 30 days)." A done run
  with `skippedTools` shows them as chips under it, `Allow next time: [Bash(npm test)]`; a tap adds
  the rule to the automation (PUT) and toasts "Allowed for this automation".
  Running or queued rows have `Stop` (ghost pill).
- **Top bar:** `Run now` only for a saved automation with no unsaved changes; it toasts "Started"
  and the card's status line follows. `Save` is primary, disabled until valid; daemon errors show in
  amber under the block they belong to. Back with changes: confirm "Discard changes?"
  (`Discard` danger / `Keep editing`). `Delete` (danger, edit only): confirm "Delete “Morning
  brief”?" / "Its run history goes too. Sessions it started stay in History."

### Automated sessions elsewhere

- **Sessions tab card:** a StatusChip `AUTO` (accent-dim outline, no dot) after the status chip,
  as the Agents chip sits. The title is already `Morning brief, Thu 16 Oct`.
- **Session screen:** its status line starts `AUTO · Morning brief`; a tap there opens the
  automation's editor.
- **History:** ended sessions of one automation fold into one row, placed by its newest run:
  planet, `Morning brief`, `12 runs · today 9:00`; a tap opens the editor at RUNS. This is their own
  grouping; the sky shows them as ordinary moons of their project.

### Notifications

- New channel `automations`, "Automations", default importance: "What your automations found, and
  runs that couldn't start."
- A `{t:"note"}` with `to: "phone"`, while connected (banner in the app, system notification in the
  background): title the name, text the note's text; a tap opens the run's session, else the
  automation. On reconnect, notes in `Snapshot.automations.notes` the phone hasn't shown (it keeps
  the last 50 ids) and under 12 hours old are shown; two or more fold into one: "3 automations ran
  while you were away" with their names.
- Approvals in automated runs are ordinary approval notifications (the session's name says which).

### Templates (in the app, `ui/automations/Templates.kt`)

Project: the most recently active one. Everything stays editable before Save.

| Id · name | When | Do | Mode | Then |
|---|---|---|---|---|
| `brief` · Morning brief | Weekdays 9:00 | Start: "Summarise what changed in this project since yesterday: commits, branches, open work, anything failing. Then say what's worth doing next. Don't change anything." | Allowed only + `Bash(git log:*)`, `Bash(git diff:*)`, `Bash(git status:*)`, `Bash(git branch:*)`, `Bash(git show:*)` | Notify |
| `prs` · Pull request watch | Every 2 hours, 9:00–19:00, weekdays | Start: "Check my open pull requests with gh: new review comments, failing checks, merge conflicts. Say what needs me, newest first." | Allowed only + `Bash(gh pr list:*)`, `Bash(gh pr view:*)`, `Bash(gh pr checks:*)`, `Bash(gh pr status:*)` | Notify (quiet when "Nothing new.") |
| `plugged` · Tests when plugged in | The Mac is plugged in | Start: "Run this project's tests. If anything fails, find the cause and tell me in a few lines. Don't change code." | Ask me | Notify |
| `battery` · Wrap up on low battery | Battery at 15% (the Mac's own warning level) | Continue every working session: "The Mac's battery is at {battery}. Finish the step you're on, commit or save your work in progress, write down what's left, and stop." | (sessions' own) | Notify |
| `reset` · When my limit resets | 5-hour limit resets, after I hit it, only the next time | Start: prompt left empty, focused, placeholder "What should Claude pick up when your limit is back?" | Ask me | Notify |

## 6. Copy (English; Android `res/values/strings_automations.xml`)

**Summary sentence** = trigger phrase + ", " + action phrase + "." + then phrase.

| Part | Text |
|---|---|
| time, all days / 1–5 / 6–7 / other | `Every day at 9:00` / `Every weekday at 9:00` / `Every weekend at 9:00` / `Every Mon, Wed and Fri at 9:00` |
| once | `Today at 15:30` / `Tomorrow at 9:00` / `On Fri 17 Oct at 9:00` |
| every | `Every hour` / `Every 2 hours` + ` from 9:00` or `, 9:00–19:00` + ` on weekdays` / ` on Mon and Thu` / nothing |
| reset | `When my 5-hour limit resets after I hit it` / `Whenever my weekly window resets` |
| session | `When a session in hypernova finishes` / `When any session finishes` / `When an automated session in hypernova needs me` / `When any session in hypernova, automated ones too, finishes` |
| power | `When the Mac is plugged in` / `When the Mac's battery drops to 15%` |
| only once | ` (next time only)` after the trigger |
| start, prompt starts with a common imperative verb | `in hypernova, ask Claude to summarise what changed…` (first letter lowered; a fixed list of ~60 verbs in code: summarise, summarize, review, check, run, fix, look, write, update, find, list, make, build, test, …) |
| start, otherwise | `in hypernova, ask Claude: “What changed overnight?”` |
| skill | `in hypernova, run /review` |
| continue | `ask “Parser refactor” to …` / `ask that session to …` / `ask every working session to …` (same verb rule, else `: “…”`) |
| notify / say | `notify me: “…”` / `read aloud on my glasses: “…”` |
| then | ` Notify me when it's done.` / ` Read the result aloud on my glasses.` / nothing |

Prompts are cut at 120 characters with "…".

**Run rows and card status lines**

| State / reason | Text |
|---|---|
| queued | `Waiting for a free slot` |
| waiting (limit) | `Waiting for your limit · back at 14:00` |
| waiting (target busy) | `Waiting for the session to be free` |
| running / needs_you | `Running · 1m 12s` / `Needs you · Bash: git push` |
| done | `2m 14s` + result, or `Done`; quiet: `Nothing new`; late: `Ran late · the Mac was asleep at 9:00` (`Ran late · Hypernova wasn't running at 9:00` after a start) |
| missed | `Missed · the Mac was asleep at 9:00` / `Missed 3 times · the Mac was asleep` |
| overlap | `Skipped · the last run was still going` |
| queue_full / stale / daily_cap | `Skipped · too many runs waiting` / `Skipped · waited over 6 hours for a free slot` / `Skipped · 24 runs today is the most` |
| limited | `Skipped · usage limit until Fri 10:00` |
| signed_out / no_plan / on_hold / no_credit | `Skipped · Claude Code is signed out on the Mac` / `Skipped · no Claude plan on the Mac` / `Skipped · the account is on hold` / `Skipped · out of credits` |
| provider | `Skipped · OpenRouter: out of credits` (the provider's name and its status) |
| no_project / no_session / no_provider | `Failed · the project folder is gone` / `Failed · that session no longer exists` / `Failed · that provider was removed` |
| target_busy / loop | `Skipped · the session was waiting for you` / `Skipped · it would have started a loop` |
| start_failed / error / blocked | `Failed · Claude Code didn't start` / `Failed · <the session's error line>` / `Stopped · Claude Code was signed out` |
| away / timeout / by_user | `Stopped · nobody answered for 10 minutes` / `Stopped watching after 12 hours` / `Stopped by you` |

**Modes** (title · line): `Allowed only` · "Runs only what your Claude Code settings and this list
allow. Anything else is skipped, never asked." — `Ask me` · "Waits for your answer before edits and
commands. Unanswered for 10 minutes, the run stops." — `Edits on its own` · "Edits files without
asking. Commands wait for your answer." — `Auto` · "Claude Code decides what's safe." — `No asking`
· "Nothing waits for you." (Warnings above.)

## 7. Mac (menu bar popover)

```
 SESSIONS ─────────────────────────────────────────── 2 working
 ◉ Morning brief, Thu 16 Oct    HYPERNOVA  [AUTO]   git log …   now
 ◉ Parser refactor               HYPERNOVA           Edit: …     2m
 ◷ Next 9:00 · Morning brief                                          ← one quiet line
 USAGE ──────────────────────────────────────────────────────────
```

- **One line under SESSIONS** (only when automations exist): 12 pt planet like the rows (turning
  while a run works), then in body 12 muted: `Next 9:00 · Morning brief` (`Next Mon 9:00 · …` past
  today), or while one runs, `Morning brief · running 3m` in accent 72% (`2 automations running`
  for several), or `Automations paused` with a `Resume` link. Hover shows the next three. A click on
  a running one opens its terminal, as session rows do.
- **Session rows:** an `AUTO` tag (tracked caps 9 pt, accent-dim) after the project.
- **Settings page:** an "Automations" row with a switch, "Run automations" (off = paused), and the
  count ("3 automations, edited on your phone").
- No editor on the Mac in v1 (LATER), no macOS notifications.
- `--render-popover` gains two states: next-run line, running line.

## 8. Glasses (Lens)

- `{t:"note"}` with `to: "glasses"`: a card `AUTOMATION · Morning brief` (header, age on the right),
  the text (paged as results are), gestures `tap read aloud · swipe back`. If reading aloud is on in
  Lens's settings, it's read at once through `POST /v1/speech`, as a result is. Nothing else
  changes: automated sessions show on the glance like any session (their title says what they
  are).

## 9. Safety

- **The owner chooses the mode per automation.** Default "Allowed only" (`dontAsk`). "Auto" shows
  an amber warning; "No asking" a red one, a fingerprint or screen-lock check on the phone, and
  the daemon refuses it without `confirmBypass: true` (so no other client sets it silently). Cards
  and run rows show the mode chip.
- Allow rules are validated (`Tool` or `Tool(spec)`, ≤ 200 characters, ≤ 20); rules that amount
  to "everything" are refused (`rule_too_broad`). "Allow next time" adds the exact command only,
  shown in full before the tap.
- Runs never spend paid usage credits; a limit makes them wait or skip.
- Loops are cut (an automation's own sessions never fire it; depth ≤ 2), runs are capped (24 a day,
  2 at once, 10 queued), and Pause all is one tap on the phone and the Mac.
- Results and notes are masked like titles before they leave the Mac (phone, glasses,
  notifications). Prompts and results are never logged. Files are 0600.
- The appended system prompt tells Claude nobody watches and not to ask; a question it asks anyway
  reaches the phone as a card, as in any session, and waits the usual 10 minutes.
- Paths go through `allowedPath`; a run's project must exist at run time.
- Any paired device can edit automations (it can already start sessions); Lens has no UI for it.

## 10. Performance and battery

- Daemon: one timer (≤ 30 s), no polling of its own: events come off the existing broadcast,
  power off the battery work's once-a-minute read. Writes only on change (runs at most 1/s).
  `caffeinate` only while a run works. Ended runs' terminals are closed after 15 idle minutes, so
  daily runs don't pile up `claude` processes.
- Phone: no new connection or timer; automations come in the snapshot and over the socket. Cards are
  static (only a running card's planet uses the ambient clock); relative times refresh once a minute
  while the tab is visible; the summary line is derived state, rebuilt only on edits.
- Mac: the status poll it already makes carries the automations line (a few hundred bytes).

## 11. Edge cases

| Case | Behaviour |
|---|---|
| Mac asleep at 9:00, wakes 9:40 | Runs at once: "Ran late · the Mac was asleep at 9:00". |
| Asleep all weekend (time trigger) | One `missed` row: "Missed 3 times · the Mac was asleep". |
| Clock or zone changes | Next times recomputed on the next tick; occurrence keys stop a second run. |
| Daemon restarts during a run | The run is picked up from the store and followed to its end. |
| Two automations due the same minute | Both run (2 slots); a third waits. |
| The same automation fires while its last run works | `skipped`, `overlap`. |
| Limited at 9:00, reset 11:00, next run tomorrow | `waiting` until 11:01, then runs. |
| Weekly limit, resets in 3 days | `skipped`, `limited`. |
| Limit hits mid-run | `waiting` until Claude Code carries on (or the owner continues); slot kept. |
| Signed out | `skipped`, one note for the whole episode. |
| Project folder deleted | Card shows "The project folder is gone." in amber; runs fail `no_project`. |
| Continue target ended | Resumed in place (as a prompt to an ended session is). Gone entirely → `no_session`. |
| Continue target needs you / has typed text | Retried 10 minutes, then `target_busy`. |
| "Allowed only" refuses what the job needs | Run ends, Claude says so; the row offers "Allow next time". |
| `auto` not available on this account | Claude Code starts in another mode; the run row shows the real `permissionMode`, the card a problem line "Auto isn't available; it ran in Ask me". |
| Editing during a run | The run finishes on the old definition. |
| Deleted during a run | The session runs on as a normal one; its badge stays (from its record). |
| Phone off all day | Notes from the last 12 hours show on reconnect, folded when several. |
| Glasses not connected for `say` | Goes to the phone instead. |
| Mac without a battery | Battery triggers ghosted in the picker; existing ones show "This Mac has no battery." |
| Several Macs | Automations belong to each Mac; the tab shows the current Mac's. |
| Daemon too old | The tab says "Update Hypernova on the Mac to use automations." |
| Transcript cleaned up by Claude Code | Run row stays; tap explains. |
| Once in the past | 400 `past_time`: "Pick a time in the future." |
| Placeholder with nothing to fill (`{result}` on a time trigger) | Left out, with the space around it. |

## 12. Test plan

**Daemon** (`node --test`, injectable clock, a fake starter so no `claude` runs):
- `schedule.test.ts`: next occurrence for daily, weekdays, custom days, once, every N (with and
  without `to`, across midnight, with days); DST in `TZ=Europe/Berlin` (missing 02:30, repeated
  02:30); catch-up decisions (asleep 40 min, 7 h, 3 days; every-1h after 3 h; next occurrence within
  15 min); paused time.
- `automations.test.ts`: validation (each 400 code, `confirm_bypass`, `rule_too_broad`); store
  round trip and restart with queued, waiting and running runs; gates in order (overlap, daily cap,
  queue full, stale, loop depth, self-trigger); account gate (ok, limited before and after next
  occurrence, `usingCredits`, signed out once per episode, provider status); finished rule (agents
  running, blocked, queue, 5 s settle); needs_you once per approval, agent approvals after 10 s;
  reset fires once per `resetsAt`, `afterLimit` both ways, stale usage never; plugged debounce;
  low hysteresis; no battery; result extraction, masking, "Nothing new." quiet; `skippedTools` from
  the recorded `PermissionDenied` payload; `say` fallback to the phone; notes kept 12 h; tidy after 15 min
  unwatched, not when watched.
- `http.test.ts`: each route, 404s, 409s of Run now, CORS on `/v1/automations*`, snapshot and
  `/local/status` fields.
- End to end on a second daemon (`HYPERNOVA_SUPPORT`, other ports) with
  `daemon/test/fixtures/fake-claude.sh`, then once with real Claude Code and
  `--settings '{"disableAllHooks":true}'`, prompts titled "[Hypernova test]", transcripts deleted
  after: a `once` automation a minute ahead starts a shared session in `dontAsk`, with
  `--allowedTools`, `--name` and the appended prompt in its argv; the run ends `done` with a result;
  a refused command shows in `skippedTools`.

**Android:** unit tests for the summary builder (every trigger × action × then, the verb rule,
truncation), parsers (`Protocol.kt` for every type, unknown enum values), clock words; FakeHub demo
automations in every state; screenshots at 360 dp and on the Phone 3: empty tab, list (running,
needs you, waiting, off, problem), each picker sheet, the Auto and No asking warnings, RUNS with
"Allow next time", the five-tab bar at font scale 1.3; TalkBack reads a card as name, summary, status,
switch state.

**Mac:** decode tests for the status additions; `--render-popover` states; `--popover-selftest`
still passes.

**Lens:** `hub.test.ts` (note routing by `to`), `lens.test.ts` (the AUTOMATION card, read aloud
only with the switch on).

**Owner's checklist** (`qa-checklist.ts`): Morning brief from the template notifies at 9:00; Run
now; the Mac asleep then woken (late run); Pause all from the Mac; No asking asks for the
fingerprint; low-battery wrap-up on battery; "Allow next time" makes the next run go through.

## 13. Tracks and files

Build B, C and D against the shapes in §3 with fake data while A builds the daemon; then integrate.
Each file belongs to one track. Work in flight elsewhere touches some of the same files (Stats:
`HomeScreen.kt`, `http.ts`, `PROTOCOL.md`; speech: `http.ts`, `PROTOCOL.md`; the battery work:
`Protocol.kt`, `Models.swift`, `LiveFeed.swift`): rebase onto it rather than around it.

**Track A: daemon and the contract**
- new `daemon/src/automations.ts`, `daemon/src/schedule.ts`, `daemon/test/automations.test.ts`,
  `daemon/test/schedule.test.ts`, `daemon/test/fixtures/permission-denied.json`
- `daemon/src/hooks.ts`: the `PermissionDenied` handler (per-run hook; hands the call to automations.ts)
- `daemon/src/hub.ts`: `tapBroadcast`; `Session.automation`; `permissionMode` union
- `daemon/src/terms.ts`: `createSharedSession` options (`automation`, `allowedTools`,
  `appendSystemPrompt`, `name`)
- `daemon/src/runner.ts`: the same for `createSession`
- `daemon/src/store.ts`: `ManagedSession.automation`
- `daemon/src/http.ts`: route `/v1/automations*` to automations.ts, `promptSession` extracted,
  snapshot `automations` and `mac.tz`, `/local/status` `automations`
- `daemon/src/main.ts`: `startAutomations()`
- `daemon/src/qa-checklist.ts`: the owner's checks
- `daemon/test/http.test.ts`: the routes
- `docs/PROTOCOL.md` (an "Automations" section, REST rows, WS messages, types),
  `docs/DECISIONS.md` (this section's decisions), `docs/LATER.md` (§14)

**Track B: Android**
- new `data/Automations.kt` (models, parse, Hub calls), `ui/automations/AutomationsTab.kt`,
  `ui/automations/AutomationEditor.kt`, `ui/automations/Pickers.kt`, `ui/automations/RunRows.kt`,
  `ui/automations/Summary.kt`, `ui/automations/Templates.kt`, `res/values/strings_automations.xml`,
  `test/…/automations/SummaryTest.kt`
- `ui/home/HomeScreen.kt`: the tab, its pill, the five-tab row, the AUTO chip on cards, History folding
- `data/AppModel.kt`: `Route.AutomationEdit`; `ui/App.kt`: the route, note banners
- `data/Hub.kt`, `data/LiveHub.kt`, `data/FakeHub.kt`, `data/Api.kt`, `data/Protocol.kt`,
  `data/Models.kt`: types, calls, socket messages, demo data
- `data/Notices.kt`: `Kind.Automation`; `ConnectionService.kt`: the channel and reconnect notes
- `ui/session/SessionScreen.kt`: `AUTO · name` in the status line
- `ui/components/Icons.kt`: clock, repeat, plug, battery, bolt-of-reset glyphs
- `docs/DESIGN.md`: an "Automations" section (tab, card, editor, warnings)

**Track C: Mac**
- `Core/Models.swift` (status additions, `Session.automation`), `Core/Actions.swift` (pause),
  `UI/MenuPopover.swift` (the line), `UI/PopoverParts.swift` (AUTO tag, line view),
  `UI/PopoverPages.swift` (the switch), `App/PopoverRender.swift` and `App/DevTools.swift` (states)

**Track D: Lens**
- `lens/src/core/types.ts`, `lens/src/data/hub.ts` (notes), `lens/src/lens/pages.ts` (the card),
  `lens/src/lens/controller.ts` (showing it, reading aloud), `lens/src/data/demo.ts`,
  `lens/test/hub.test.ts`, `lens/test/lens.test.ts`, `lens/DAEMON.md`

## 14. LATER rows to add

- Waking the Mac for a run (`pmset` through a privileged helper; needs the signed build, L6).
- Chains of several actions ("then run …"), and a "run in the cloud" option through `/schedule`
  for work that must run with the Mac off.
- An editor in the Mac app, and macOS notifications for notes.
- Automated sessions marked in the sky and on the widgets.

## Addendum (owner, 2026-10-15 night): limit templates

The owner: "we can add automation templates like the one with limits". Add these templates, in this
order, at the top of the template list (limits are where an always-on Mac helps most):
1. **Carry on after the limit**: When my 5-hour limit resets → continue every session the limit
   stopped (the daemon's own continue, for sessions Claude Code didn't auto-continue).
2. **Do it when I have budget again**: When my limit resets → start a session in <project> with
   <prompt> (a "parked" task; the editor offers it from a session's BlockedCard: "Run this at the reset").
3. **Switch provider at the limit** (opt-in, says it costs money): When I hit the 5-hour limit →
   continue working sessions on <provider>.
4. **Night shift**: When the limit resets between 00:00 and 07:00 → run <prompt> in <project>.
5. **Weekly debrief**: When the weekly limit resets → a summary of the week from Statistics,
   as a notification (and read aloud on the glasses if they're on).
