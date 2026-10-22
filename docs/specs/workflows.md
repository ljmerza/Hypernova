# Workflows: automations with steps (design; being built)

The owner wants automations to grow toward an n8n-style builder (approved 2026-10-18: "build it",
in this order of work). This is the shape it takes, so the pieces fit the store and the contract
that exist (`docs/specs/spec-automations.md`, `docs/PROTOCOL.md` "Automations", `daemon/src/automations/`).
The exact contract is `docs/PROTOCOL.md`, "Automations", "Workflows" (types in
`daemon/src/automations/contract.ts`); §6 lists what it settled that this design left open.

The bar stays the one automations set: a basic user picks a template and is done; a developer can
wire Hypernova into git hooks, cron, CI and a folder on disk without learning a canvas.

## 1. The model: trigger, steps, conditions, outputs

A workflow is still one card. Its body is a short, linear list, not a graph:

```
When  <trigger>                       (one, as today)
Then  1. <step>                       (1 to 5 steps, in order)
      2. <step>   only if <condition>  (each step may have one guard)
      …
Tell  <output>                        (0 to 2: phone, glasses, webhook, a file)
```

- **Linear, not a graph.** n8n's canvas is powerful and is also why people get lost in it. Five
  steps in order, each with at most one guard, covers what owners asked for (do X, then if it
  failed do Y, then tell me) and keeps the phone editor a list, as the Automations tab already is.
  Branches are expressed as guards ("only if the last step failed"), not as edges.
- **Steps** (`kind`):
  - `start`: today's start action (project, prompt, model, provider, mode, allow rules).
  - `continue`: today's continue action (a session, the one that fired it, every working one,
    every one a limit stopped; optionally on another provider).
  - `ask`: a pause for a human, n8n's "Wait" step: the run goes `needs_you` with a question and
    two or three answers (Approve / Skip / custom), shown as an attention card on the phone and on
    the glasses, answered there (or after 10 minutes taken as its default answer). Its answer is
    the step's output. This is the "needs approval" step competitors have.
  - `shell`: a command in the project folder, no TTY, a timeout (60 s default, 10 min at most),
    stdout and stderr masked like transcripts and cut to 8 KB. The risky one: saving a workflow
    with a shell step asks for the fingerprint, like "No asking" does; never with a placeholder
    from another session in it (the A6 rule).
  - `notify`, `say`: today's actions, as steps (so a workflow can tell you mid-way).
- **Conditions** (`if`): `{on: "ok" | "failed" | "quiet" | "not_quiet" | "contains", text?}`,
  about the step just before: a session step is `ok` when its run finished, `failed` when it
  stopped or failed, `quiet` when its reply was exactly "Nothing new."; a shell step `ok` on exit
  0; `contains` matches the step's output (case-insensitive, plain text, not a regex). A step whose
  guard is false is `skipped` and the next one is looked at.
- **Outputs**: where the result goes when the workflow ends. Today's `then` (notify, say) plus
  `webhook` (ntfy, Slack, Telegram and Discord presets and a plain POST; https only, 10 s timeout,
  the URL kept in a 0600 secrets file, never in automations.json) and `file` (append the result as
  a Markdown line to a file inside the project, never a secret file, `secretfiles.ts`).
- **Placeholders** grow by the steps' outputs: `{prev}` (the last step's output, two sentences),
  `{exit}` (a shell step's exit code), `{output}` (its masked output), `{answer}` (an `ask` step's
  answer), `{payload}` (a webhook trigger's body, 8 KB at most). Everything from outside Hypernova
  or from another session goes into prompts quoted as information (`fill(…, {quote: true})`).

## 2. Triggers

Today's triggers stay (time, once, every, reset, limit, session, power). New ones, for developers:

- **Webhook / local fire** (S/M): `POST /local/automations/{id}/fire` on the admin port (127.0.0.1)
  with a per-automation token (`{kind: "webhook", tokenHash, last4}`; shown once, like a device
  token), and `hypernova automation fire <id> [--data-file <file>]` for shell scripts. Over the tailnet,
  only with a paired device's token. Never on a public address. Its body fills `{payload}`.
  This is what git hooks, cron and CI on the same machine use.
- **Cron** (S): `{kind: "cron", expr: "*/15 9-18 * * 1-5"}` for people who think in cron;
  `schedule.ts` already does the time maths (`every` and `time` are cron's common cases, and the
  phone keeps offering those; cron is typed text, checked at save, with the next three times shown).
- **File watch** (M): `{kind: "file", path, glob, event: "created" | "changed" | "deleted"}`,
  `fs.watch` recursive (FSEvents, inotify), 10 s debounce, ignoring `.git`, `node_modules` and
  changes made during its own run (so a step that writes the folder doesn't fire it again).
- **GitHub watch** (M): `{kind: "github", repoPath, events: ["review_comment", "checks_failed",
  "pr_opened"], every}`: `gh` against a cursor every 5 to 15 minutes, firing only on a change. The
  "PRs every 2 hours" template then stops spending usage when nothing happened.
- **Claude Code hooks** (S): a session event Hypernova already receives (Stop, a failed tool,
  PreCompact) as a trigger, filtered like `session` is (project, mine/automated/any).

## 3. Templates for basic users

Templates stay the front door; a workflow template is a filled-in card, as today. Ones that need
steps:

- **Fix the tests while I sleep**: at the night reset → start "run the tests and fix what fails"
  (Allowed only + `Bash(npm test:*)`, `Edit(src/**)`) → only if failed: notify "Tests still
  failing: {prev}".
- **Review before merge**: GitHub watch, PR opened → start a review → ask "Post this review?"
  (Approve / Skip) → only if approved: shell `gh pr review --comment …`.
- **Morning brief with a digest**: weekdays 9:00 → start the brief → output to a Markdown file in
  the project and to the phone.
- **Tell my team**: a session needs me for 10 minutes (the session trigger's `after: 10`) →
  webhook to Slack.

Templates that are one-per-account (carry on, switch provider, weekly debrief, battery) hide once
used; the repeatable ones never do, and every card gets Duplicate (phone A5).

## 4. How it maps onto the store we have

Nothing about today's automations changes; workflows are a superset, in the same files.

- **Definition.** `Def` gains `steps?: Step[]` and `outputs?: Output[]`. `action` stays and is
  mirrored as `steps[0]` for clients that don't know steps (an old phone sees and edits a
  one-step automation exactly as now; a multi-step one shows "Edit on a newer Hypernova" and can
  still be switched on and off, run and deleted). `then` maps to an output.
  `Step = {id, kind, …the action's fields…, if?: {on, text?}}`, `id` stable across edits so runs can
  name it.
- **Runs.** `Run` gains `steps: {id, state, reason, startedAt, endedAt, result, sessionIds}[]` and
  `step: number` (the one at work). The run's own `state` keeps its meaning (the whole workflow's),
  so the run line, notes, Pause all, Stop, Retry and the 2-slot limit work unchanged. Retry gains
  "from the step that failed" (`POST /v1/automations/runs/{id}/retry {fromStep}`), reusing the
  outputs of the steps before it, which the run keeps (`ctx` today holds the placeholders' values).
- **Engine.** `begin()` runs `steps[0]`; when a step ends, `end()` becomes `stepEnded()`: it
  records the step, evaluates the next step's guard, and either begins it or ends the run. Every
  gate (paid, account, overlap) is checked per step that needs a session, as `regate()` does now
  for a queued run. A run holds its slot across its steps. The 12-hour watch covers the whole run.
- **Contract.** `docs/PROTOCOL.md` "Automations" gains `Step`, `Output`, `Run.steps`, the new
  trigger kinds and the fire route; `GET /v1/automations` adds `features: ["steps", "webhook", …]`
  so clients show only what the daemon can do.
- **Security.** The webhook and shell steps are the new attack surface: tokens hashed at rest,
  webhooks never public, shell steps behind the fingerprint at save, outputs to https only and
  never to this computer or a private network, nothing from outside ever pasted unquoted into a
  prompt (nor into a No asking session or a shell command line at all), and a per-run budget
  (`budget: {minutes?, windowPct?}`: Esc and `stopped`, `budget` when exceeded) so a runaway
  workflow can't eat a window. The review of what shipped: docs/SECURITY-MODEL.md, "Automations and
  words from outside".

## 5. Order of work

1. Webhook / local fire and outputs (webhook, file): small, and useful on their own.
2. Steps with guards and the `ask` step, with Retry from a step; the phone editor as a list.
3. Cron, file watch, GitHub watch.
4. The per-run budget, then shell steps.

## 6. What the contract settled

Points this design left open, as `docs/PROTOCOL.md` "Workflows" now says them:

- **Old clients.** `action` mirrors `steps[0]` only when an old client can show the automation
  exactly; otherwise it is `{kind: "workflow", steps: n}`, a kind today's phone doesn't know, so it
  shows its "update the app" note and can't overwrite the steps. An old client's save over a
  workflow is refused (409 `has_steps`).
- **"The step before"** is the nearest earlier step that ran and isn't a notify or say; guards and
  `{prev}` look at it. A step with no guard runs unless that step failed (as GitHub Actions' steps
  do); a guarded one runs when its guard holds. A run ends `done` when no step failed, else as its
  first failed step.
- **Two small additions**: a guard `answer` (the ask before was answered exactly that), and a
  continue target `step:<id>` (the sessions an earlier step worked in), so "start, ask, then carry
  on in that session" needs no shell step.
- **Ask**: 2 or 3 answers (Approve and Skip by default), the last one is the default, 10 minutes by
  default and an hour at most (a run keeps its slot while it asks); any paired device or the Mac
  answers, the first answer counts.
- **Shell**: `/bin/sh -c`; only `{project}`, `{time}`, `{battery}`, `{exit}` and `{answer}` in the
  command, each as a quoted reference to its `HYPERNOVA_*` variable; every value, and words from
  outside, reach it only through the environment, never in the command line. The fingerprint is asked on every save with a shell step, as for No asking.
- **`{payload}`** also carries what fired a file watch, a GitHub watch or a hook.
- **Outputs** go when a run ends done and isn't quiet, each with an optional text, retried once.
- **Features** are named `steps`, `ask`, `shell`, `budget`, `step.output`, `trigger.*` and
  `output.*`, and every 400 names its `field`, so an editor marks it.
- **Any earlier step's output** (from the phone editor's gaps, 2026-10-18): `{step:<id>}` in a
  later step's text or an output (quoted in a prompt, refused in a No asking prompt and in a shell
  command), and `HYPERNOVA_STEP_<id>` for a shell step (each `-` as `_`), holding that step's whole
  output (a session's reply, its first 8 KB). The "Review before merge" template posts Claude's
  review with it (`gh pr review … --body "$HYPERNOVA_STEP_st_rev1"`), no file in between.
- **Still needing you**: `after` (1–120 minutes) on the session trigger's `needs_you`; it fires
  only if that approval or menu still waits (or the approval was turned away for nobody answering).
- **A hook trigger and "the session that fired it"**: every hook event names a session Hypernova
  knows (others fire nothing), so the target always has one.
- **An output with no text** says the run's result; with none, the last notification's text, else
  "Done." (beside the automation's name, as every output shows it).
- **An ask's question** is cut to 200 characters between words once filled; the run keeps
  `{prev}` whole.

