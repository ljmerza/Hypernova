package com.prudnikweb.hypernova.data

import android.content.res.Resources
import com.prudnikweb.hypernova.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The demo's automations: one in every state a card can be in (needs you, running, waiting for a
 * slot, done, carried on, a problem, off), with believable run histories, so the tab, the editor
 * and the AUTO chips all have something to show. It speaks the whole of workflows too
 * (docs/PROTOCOL.md, "Workflows"): every feature, steps with guards, outputs, a budget, a run
 * waiting at an ask (answered from the card, or taken as its default when its time runs out), a
 * run that failed at its second step (Retry from it), a webhook trigger with its token shown once,
 * cron checked with its next three times, and the file, GitHub and hook triggers. Runs only
 * pretend: each step works for a few seconds and "finishes".
 */
class DemoAutomations(private val scope: CoroutineScope, private val res: Resources) : AutomationBook(), Automations {
    private val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    /** Banners for the demo's notes (FakeHub hands them on as its own). */
    val notices: SharedFlow<Notice> = noticeFlow

    override val state: AutomationsState? get() = current
    override fun runs(id: String): RunPage? = page(id)

    private val now get() = System.currentTimeMillis()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val history = HashMap<String, List<Run>>()
    private var seq = 0
    /** Runs at work, by id: Stop cancels them. */
    private val working = HashMap<String, Job>()
    /** Runs waiting at an ask, by id: an answer completes it. */
    private val asking = HashMap<String, CompletableDeferred<StepAnswer>>()

    init {
        current = AutomationsState(paused = false, list = seed(), glasses = true, hasBattery = true, features = Features.ALL)
        // The review waits at its question: answered from its card, or its default once the time runs out.
        current?.byId(REVIEW)?.let { a -> a.lastRun?.let { r -> go(a, r, 1) } }
    }

    private fun runId() = "ru_demo${seq++}"

    // ─── Seed ──────────────────────────────────────────────────────────────

    private fun at(day: LocalDate, hh: Int, mm: Int): Long = ZonedDateTime.of(day, LocalTime.of(hh, mm), zone).toInstant().toEpochMilli()

    private fun seed(): List<Automation> {
        val t = now
        val today = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val hyper = "~/Programming/hypernova"
        val stems = "~/Programming/0stems"
        val site = "~/Documents/programming/prudnikov-site"

        // Tests when plugged in: the run asks before it runs the tests.
        val plugged = Run(runId(), PLUGGED, res.getString(R.string.au_t_plugged), "power", t - 3 * MIN, null, Run.NEEDS_YOU, null, null, listOf(SESSION_TESTS), t - 3 * MIN + 2_000, null, permissionMode = "default")
        history[PLUGGED] = listOf(
            plugged,
            Run(runId(), PLUGGED, res.getString(R.string.au_t_plugged), "power", t - 26 * HOUR, null, Run.DONE, null, null, listOf("s-auto-tests-old"), t - 26 * HOUR, t - 26 * HOUR + 3 * MIN + 12_000, result = "All 214 tests pass. The slowest is the renderer's, at 9 s."),
        )

        // Pull request watch: at work right now.
        val prs = Run(runId(), PRS, res.getString(R.string.au_t_prs), "schedule", t - 72_000, null, Run.RUNNING, null, null, listOf(SESSION_PRS), t - 72_000, null, permissionMode = AutoMode.ALLOWED)
        history[PRS] = listOf(prs) + (1..5).map { i ->
            val fired = t - i * 2 * HOUR
            Run(runId(), PRS, res.getString(R.string.au_t_prs), "schedule", fired, null, Run.DONE, null, null, emptyList(), fired + 4_000, fired + 48_000 + i * 7_000, quiet = i != 3, result = if (i == 3) "#212 has two new review comments, and its checks fail on lint." else "Nothing new.")
        }

        // Release notes: Run now pressed while two runs hold both slots.
        val release = Run(runId(), RELEASE, "Release notes", "manual", t - 40_000, null, Run.QUEUED, null, null, emptyList(), null, null)
        history[RELEASE] = listOf(release)

        // Morning brief: done today (or on the last weekday), with twelve runs behind it.
        var day = today
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY || at(day, 9, 0) > t) day = day.minusDays(1)
        val briefRuns = ArrayList<Run>()
        val results = listOf(
            "3 PRs merged; the parser tests fail on main since 2a41f.",
            null,
            null,
            "Two branches are ready to merge. Nothing is failing.",
            "The widget branch is 14 commits ahead; its build is green.",
            "Nothing failing. The lens scrolling work is half done on its branch.",
            "One test flakes on the daemon's restart path; worth a look.",
            "Quiet day: one commit, docs only.",
            "Main is green. Statistics landed; the sun's tap opens it.",
            "The Mac app's build broke after the SwiftPM bump; fixed in 7c1e0.",
            "Four commits on main, all green.",
            "Nothing new since Friday.",
        )
        var d = day
        for (i in 0 until 12) {
            val fired = at(d, 9, 0)
            val r = when (i) {
                1 -> Run(runId(), BRIEF, briefName(), "schedule", fired, null, Run.DONE, null, null, listOf("s-auto-brief-$i"), fired + 3_000, fired + 110_000, quiet = true, result = "Nothing new.")
                2 -> Run(runId(), BRIEF, briefName(), "schedule", fired, null, Run.SKIPPED, "limited", fired + 2 * HOUR, emptyList(), null, fired)
                3 -> Run(runId(), BRIEF, briefName(), "schedule", fired, null, Run.DONE, null, null, listOf("s-auto-brief-$i"), fired + 42 * MIN, fired + 42 * MIN + 121_000, late = 42 * MIN, result = results[i], skippedTools = listOf("Bash(npm test)"))
                else -> Run(runId(), BRIEF, briefName(), "schedule", fired, null, Run.DONE, null, null, listOf(if (i == 0) SESSION_BRIEF else "s-auto-brief-$i"), fired + 4_000, fired + 134_000 - i * 3_000L, result = results[i])
            }
            briefRuns += r
            d = d.minusDays(1)
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        }
        history[BRIEF] = briefRuns

        // Carry on after the limit: yesterday's reset picked two sessions up.
        val carryFired = at(today.minusDays(1), 14, 2)
        val carry = Run(runId(), CARRY, res.getString(R.string.au_t_carry_on), "reset", carryFired, null, Run.DONE, null, null, listOf("s-hypernova", "s-site"), carryFired + 1_000, carryFired + 12_000, result = "Carried on 2 sessions.")
        history[CARRY] = listOf(carry)

        // Nightly tests: last night's run failed on an API error (Retry runs it again).
        val nightFired = at(today.minusDays(if (at(today, 2, 0) > t) 1 else 0), 2, 0)
        val night = Run(runId(), NIGHT, "Nightly tests", "schedule", nightFired, null, Run.FAILED, "error", null, listOf("s-auto-night"), nightFired + 2_000, nightFired + 3 * MIN + 40_000, result = "API Error: 529 overloaded_error")
        history[NIGHT] = listOf(
            night,
            Run(runId(), NIGHT, "Nightly tests", "schedule", nightFired - DAY, null, Run.DONE, null, null, listOf("s-auto-night-1"), nightFired - DAY + 2_000, nightFired - DAY + 6 * MIN, result = "All 214 tests pass."),
            Run(runId(), NIGHT, "Nightly tests", "schedule", nightFired - 2 * DAY, null, Run.SKIPPED, "missed", null, emptyList(), null, nightFired - 2 * DAY, lateWhy = "asleep"),
        )

        // Lens build check: its project folder is gone.
        val lensFired = at(today.minusDays(if (at(today, 7, 30) > t) 1 else 0), 7, 30)
        val lens = Run(runId(), LENS, "Lens build check", "schedule", lensFired, null, Run.FAILED, "no_project", null, emptyList(), null, lensFired)
        history[LENS] = listOf(lens)

        val brief = Automation(
            BRIEF, briefName(), true, false, Trigger.Time("09:00", Trigger.WEEKDAYS),
            Action.Start(hyper, res.getString(R.string.au_t_brief_prompt), "anthropic", null, AutoMode.ALLOWED, listOf("Bash(git log:*)", "Bash(git diff:*)", "Bash(git status:*)", "Bash(git branch:*)", "Bash(git show:*)"), Then.NOTIFY),
            "brief", lastRun = briefRuns.first(), runCount = 12,
        )
        val list = listOf(
            Automation(
                PLUGGED, res.getString(R.string.au_t_plugged), true, false, Trigger.Plugged,
                Action.Start(stems, res.getString(R.string.au_t_plugged_prompt), "anthropic", null, AutoMode.ASK, emptyList(), Then.NOTIFY),
                "plugged", lastRun = plugged, runCount = 6,
            ),
            Automation(
                PRS, res.getString(R.string.au_t_prs), true, false, Trigger.Every(2, "09:00", "19:00", Trigger.WEEKDAYS),
                Action.Start(hyper, res.getString(R.string.au_t_prs_prompt), "anthropic", "sonnet", AutoMode.ALLOWED, listOf("Bash(gh pr list:*)", "Bash(gh pr view:*)", "Bash(gh pr checks:*)", "Bash(gh pr status:*)"), Then.NOTIFY),
                "prs", lastRun = prs, runCount = 41,
            ),
            Automation(
                RELEASE, "Release notes", true, false, Trigger.Time("17:30", listOf(5)),
                Action.Start(site, "/release-notes", "anthropic", null, AutoMode.EDITS, emptyList(), Then.NOTIFY),
                null, lastRun = release, runCount = 3,
            ),
            brief,
            Automation(
                CARRY, res.getString(R.string.au_t_carry_on), true, false, Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = true),
                Action.Continue(Action.Continue.LIMITED, "", Then.NONE), "carry_on", lastRun = carry, runCount = 3,
            ),
            Automation(
                NIGHT, "Nightly tests", true, false, Trigger.Time("02:00", Trigger.ALL_DAYS),
                Action.Start(stems, "Run the test suite and tell me what fails.", "anthropic", null, AutoMode.ALLOWED, listOf("Bash(npm test:*)"), Then.NOTIFY),
                null, lastRun = night, runCount = 3,
            ),
            Automation(
                LENS, "Lens build check", true, false, Trigger.Time("07:30", Trigger.ALL_DAYS),
                Action.Start("~/Programming/old-lens", "Build the lens app and tell me if anything fails.", "anthropic", null, AutoMode.ALLOWED, listOf("Bash(npm run build)"), Then.NOTIFY),
                null, lastRun = lens, runCount = 9, problem = "no_project",
            ),
            Automation(
                BATTERY, res.getString(R.string.au_t_battery), false, false, Trigger.BatteryLow(15),
                Action.Continue(Action.Continue.WORKING, res.getString(R.string.au_t_battery_prompt), Then.NOTIFY), "battery",
            ),
        ).map(::withSteps) + workflows(t, today, hyper)
        return list.map { it.copy(nextAt = if (it.enabled) DemoClock.next(it.trigger, t, zone) else null, createdAt = t - 20 * DAY, updatedAt = t - 2 * DAY) }
    }

    /** An automation from before steps, as a daemon with workflows sends it: its action as one step, its `then` as outputs. */
    private fun withSteps(a: Automation): Automation {
        if (a.steps != null) return a
        val (step, outs) = WorkflowShape.stepOfAction(a.action)
        return a.copy(steps = listOf(step), outputs = outs)
    }

    /** The demo's workflows: one of each new trigger, steps with guards, outputs, a budget, an ask. */
    private fun workflows(t: Long, today: LocalDate, hyper: String): List<Automation> {
        // Review before merge: a pull request opened; Claude reviewed it; it asks before posting.
        val reviewSteps = listOf(
            Step("st_rv1", null, Action.Start(hyper, res.getString(R.string.wf_t_review_prompt), "anthropic", null, AutoMode.ALLOWED, listOf("Bash(gh pr view:*)", "Bash(gh pr diff:*)"), Then.NONE)),
            Step("st_rv2", null, Action.Ask(res.getString(R.string.wf_t_review_ask), listOf("Approve", "Skip"), "Skip", 10)),
            Step("st_rv3", Guard(Guard.ANSWER, "Approve"), Action.Shell(hyper, REVIEW_COMMAND, 60)),
        )
        val reviewFired = t - 4 * MIN
        val review = Run(
            runId(), REVIEW, res.getString(R.string.wf_t_review), "github", reviewFired, null, Run.NEEDS_YOU, null, null, emptyList(), reviewFired + 3_000, null,
            permissionMode = AutoMode.ALLOWED, firedDetail = "PR #214 opened by alice: Fix the parser's ranges",
            steps = listOf(
                RunStep("st_rv1", Step.START, Run.DONE, startedAt = reviewFired + 3_000, endedAt = t - 70_000, result = "Looks good: one nit on parser.ts line 88, where a range is off by one. Tests cover the rest."),
                RunStep("st_rv2", Step.ASK, Run.NEEDS_YOU, startedAt = t - 70_000),
                RunStep("st_rv3", Step.SHELL, RunStep.PENDING),
            ),
            step = 1,
            ask = RunAsk("st_rv2", "Post this review? Looks good: one nit on parser.ts line 88, where a range is off by one.", listOf("Approve", "Skip"), "Skip", t + 8 * MIN + 20_000),
        )
        history[REVIEW] = listOf(review) + (1..3).map { i ->
            val f = t - i * 26 * HOUR
            Run(
                runId(), REVIEW, res.getString(R.string.wf_t_review), "github", f, null, Run.DONE, null, null, emptyList(), f + 2_000, f + 6 * MIN,
                result = if (i == 2) "Approve" else "Posted the review on #${214 - i}.", firedDetail = "PR #${214 - i} opened by sam",
                steps = listOf(
                    RunStep("st_rv1", Step.START, Run.DONE, startedAt = f + 2_000, endedAt = f + 4 * MIN, result = "Two small fixes suggested."),
                    RunStep("st_rv2", Step.ASK, Run.DONE, startedAt = f + 4 * MIN, endedAt = f + 5 * MIN, result = if (i == 2) "Skip" else "Approve", answer = StepAnswer(if (i == 2) "Skip" else "Approve", if (i == 2) StepAnswer.TIMEOUT else "phone", f + 5 * MIN)),
                    if (i == 2) RunStep("st_rv3", Step.SHELL, Run.SKIPPED, RunStep.GUARD)
                    else RunStep("st_rv3", Step.SHELL, Run.DONE, startedAt = f + 5 * MIN, endedAt = f + 6 * MIN, result = "Reviewed #${214 - i}", exit = 0),
                ),
                step = 2,
            )
        }

        // Fix the tests while I sleep: last night it fixed them, so nobody was told.
        val fixSteps = listOf(
            Step("st_fx1", null, Action.Start(hyper, res.getString(R.string.wf_t_fix_prompt), "anthropic", null, AutoMode.ALLOWED, listOf("Bash(npm test:*)", "Edit(src/**)"), Then.NONE)),
            Step("st_fx2", Guard(Guard.FAILED), Action.Notify(res.getString(R.string.wf_t_fix_notify), glasses = false)),
        )
        val fixFired = at(today.minusDays(if (at(today, 3, 12) > t) 1 else 0), 3, 12)
        val fix = Run(
            runId(), FIX, res.getString(R.string.wf_t_fix), "reset", fixFired, null, Run.DONE, null, null, listOf("s-auto-fix"), fixFired + 60_000, fixFired + 23 * MIN,
            result = "All 214 tests pass after fixing two imports in the parser.",
            steps = listOf(
                RunStep("st_fx1", Step.START, Run.DONE, startedAt = fixFired + 60_000, endedAt = fixFired + 23 * MIN, result = "All 214 tests pass after fixing two imports in the parser."),
                RunStep("st_fx2", Step.NOTIFY, Run.SKIPPED, RunStep.GUARD),
            ),
            step = 0,
        )
        history[FIX] = listOf(fix)

        // Ship when CI is green: CI fires it; the build went, the notes failed at step 2 (Retry from it).
        val shipSteps = listOf(
            Step("st_sh1", null, Action.Shell(hyper, "npm run build", 300)),
            Step("st_sh2", null, Action.Start(hyper, "Write release notes for what CI just built: {payload}. Keep them to five lines.", "anthropic", null, AutoMode.ALLOWED, listOf("Bash(git log:*)"), Then.NONE)),
            Step("st_sh3", Guard(Guard.NOT_QUIET), Action.Say("Release notes are ready.")),
        )
        val shipOutputs = listOf<Output>(
            Output.Webhook("ou_sh1", null, Output.SLACK, "hooks.slack.com", "Xq7Z"),
            Output.File("ou_sh2", null, "$hyper/RELEASES.md"),
        )
        val shipFired = t - 3 * HOUR
        val ship = Run(
            runId(), SHIP, "Ship when CI is green", "webhook", shipFired, null, Run.FAILED, "error", null, listOf("s-auto-ship"), shipFired + 1_000, shipFired + 3 * MIN,
            result = "API Error: 529 overloaded_error", firedDetail = "main at 7c1e0, CI green",
            steps = listOf(
                RunStep("st_sh1", Step.SHELL, Run.DONE, startedAt = shipFired + 1_000, endedAt = shipFired + 42_000, result = "vite v6.4.1 building for production...\n✓ built in 41.2s", exit = 0),
                RunStep("st_sh2", Step.START, Run.FAILED, "error", startedAt = shipFired + 42_000, endedAt = shipFired + 3 * MIN, result = "API Error: 529 overloaded_error", sessionIds = listOf("s-auto-ship")),
                RunStep("st_sh3", Step.SAY, Run.SKIPPED, RunStep.FAILED_BEFORE),
            ),
            step = 1,
            outputs = listOf(RunOutput("ou_sh1", "webhook", RunOutput.SKIPPED), RunOutput("ou_sh2", "file", RunOutput.SKIPPED)),
            budgetUsed = BudgetUsed(3.0, 2.0),
        )
        val shipBefore = Run(
            runId(), SHIP, "Ship when CI is green", "webhook", shipFired - DAY, null, Run.DONE, null, null, listOf("s-auto-ship-1"), shipFired - DAY + 1_000, shipFired - DAY + 4 * MIN,
            result = "v0.42: Statistics, the lens guide and a calmer sky.", firedDetail = "main at 2a41f, CI green",
            steps = listOf(
                RunStep("st_sh1", Step.SHELL, Run.DONE, startedAt = shipFired - DAY + 1_000, endedAt = shipFired - DAY + 40_000, result = "✓ built in 39.8s", exit = 0),
                RunStep("st_sh2", Step.START, Run.DONE, startedAt = shipFired - DAY + 40_000, endedAt = shipFired - DAY + 4 * MIN, result = "v0.42: Statistics, the lens guide and a calmer sky."),
                RunStep("st_sh3", Step.SAY, Run.DONE, startedAt = shipFired - DAY + 4 * MIN, endedAt = shipFired - DAY + 4 * MIN, result = "Release notes are ready."),
            ),
            step = 2,
            outputs = listOf(RunOutput("ou_sh1", "webhook", RunOutput.SENT, at = shipFired - DAY + 4 * MIN), RunOutput("ou_sh2", "file", RunOutput.FAILED, "That file holds secrets.", shipFired - DAY + 4 * MIN)),
        )
        history[SHIP] = listOf(ship, shipBefore)

        // Docs changed: a file watch, told on the phone.
        val docsFired = t - 5 * HOUR
        val docs = Run(
            runId(), DOCS, "Check changed docs", "file", docsFired, null, Run.DONE, null, null, emptyList(), docsFired + 10_000, docsFired + 2 * MIN,
            result = "Two links in docs/PROTOCOL.md point at removed sections.", firedDetail = "docs/PROTOCOL.md and 2 more",
            steps = listOf(RunStep("st_dc1", Step.START, Run.DONE, startedAt = docsFired + 10_000, endedAt = docsFired + 2 * MIN, result = "Two links in docs/PROTOCOL.md point at removed sections.")),
            outputs = listOf(RunOutput("ou_dc1", "notify", RunOutput.SENT, at = docsFired + 2 * MIN)),
        )
        history[DOCS] = listOf(docs)

        val webhook = Trigger.Webhook("9f3c", t - 6 * DAY, fireUrl(SHIP), fireCommand(SHIP))
        return listOf(
            Automation(
                REVIEW, res.getString(R.string.wf_t_review), true, false, Trigger.Github(hyper, listOf(Trigger.PR_OPENED), 10),
                reviewSteps[0].action, "review", lastRun = review, runCount = 4, steps = reviewSteps,
            ),
            Automation(
                FIX, res.getString(R.string.wf_t_fix), true, false, Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = false, from = "00:00", to = "07:00"),
                fixSteps[0].action, "fix_tests", lastRun = fix, runCount = 5, steps = fixSteps,
            ),
            Automation(
                SHIP, "Ship when CI is green", true, false, webhook, shipSteps[0].action, null, lastRun = ship, runCount = 7,
                steps = shipSteps, outputs = shipOutputs, budget = Budget(30, null),
            ),
            Automation(
                DOCS, "Check changed docs", true, false, Trigger.FileWatch(hyper, "docs/**", Trigger.CHANGED),
                Action.Start(hyper, "Check the docs that changed ({payload}) for broken links and stale examples.", "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NOTIFY),
                null, lastRun = docs, runCount = 11,
                steps = listOf(Step("st_dc1", null, Action.Start(hyper, "Check the docs that changed ({payload}) for broken links and stale examples.", "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NONE))),
                outputs = listOf(Output.Notify("ou_dc1", null, glasses = false)),
            ),
            Automation(
                HOOK, "When a Bash call fails", true, false, Trigger.Hook(Trigger.H_TOOL_FAILURE, "Bash", hyper, Trigger.MINE),
                Action.Notify("{session}: {payload}", glasses = true), null, runCount = 0,
                steps = listOf(Step("st_hk1", null, Action.Notify("{session}: {payload}", glasses = true))),
            ),
            // Tell my team: a session still waiting for me after 10 minutes, to Slack too.
            Automation(
                TEAM, res.getString(R.string.wf_t_team), true, false, Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE, after = 10),
                Action.Notify(res.getString(R.string.wf_t_team_notify)), "tell_team", runCount = 0,
                steps = listOf(Step("st_tm1", null, Action.Notify(res.getString(R.string.wf_t_team_notify)))),
                outputs = listOf(Output.Webhook("ou_tm1", res.getString(R.string.wf_t_team_text), Output.SLACK, "hooks.slack.com", "T7mQ")),
            ),
            Automation(
                QUARTER, "Quarter-hour tests", false, false, Trigger.Cron("*/15 9-18 * * 1-5"),
                Action.Shell(hyper, "npm test --silent", 120), null, runCount = 0,
                steps = listOf(
                    Step("st_qt1", null, Action.Shell(hyper, "npm test --silent", 120)),
                    Step("st_qt2", Guard(Guard.FAILED), Action.Notify("Tests failing: {output}")),
                ),
            ),
        )
    }

    private fun fireUrl(id: String) = "http://127.0.0.1:47322/local/automations/$id/fire"
    private fun fireCommand(id: String) = "\"/Users/you/Library/Application Support/Hypernova/bin/hypernova\" automation fire $id"

    private fun briefName() = res.getString(R.string.au_t_brief)

    // ─── Calls ─────────────────────────────────────────────────────────────

    private fun changed(a: Automation) {
        val s = current ?: return
        current = s.copy(list = upsert(s.list, a))
    }

    /** A run as it is now: its automation's newest, or one in its history. */
    private fun find(runId: String): Run? =
        current?.list?.mapNotNull { it.lastRun }?.firstOrNull { it.id == runId } ?: history.values.flatten().firstOrNull { it.id == runId }

    /** A run changed: into its history and onto its card. */
    private fun put(r: Run) {
        history[r.automationId] = history[r.automationId].orEmpty().let { list -> if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else listOf(r) + list }
        apply(AutoMsg.RunChanged(r))
    }

    override suspend fun loadRuns(id: String, more: Boolean): Result<Unit> {
        delay(250)
        val a = current?.byId(id)
        val runs = (listOfNotNull(a?.lastRun) + history[id].orEmpty()).distinctBy { it.id }.sortedByDescending { it.firedAt }
        setPage(id, RunPage(runs, total = runs.size, skipped = runs.count { it.state == Run.SKIPPED }))
        return Result.success(Unit)
    }

    override suspend fun save(id: String?, input: AutomationInput): Result<Automation> {
        delay(300)
        val old = id?.let { current?.byId(it) }
        val newId = id ?: "au_demo${seq++}"
        // The Mac refuses what it would refuse, by field: a schedule that's too often, a file outside the projects.
        refusal(input)?.let { return Result.failure(it) }
        val steps = input.steps
        val outputs = input.outputs.map { o ->
            if (o !is Output.Webhook) o
            else {
                val was = old?.outputs?.firstOrNull { it.id == o.id } as? Output.Webhook
                when {
                    o.preset == Output.TELEGRAM && !o.botToken.isNullOrBlank() -> o.copy(host = "api.telegram.org", last4 = o.botToken.trim().takeLast(4), url = null, botToken = null, chatId = null)
                    !o.url.isNullOrBlank() -> o.copy(host = o.url.trim().removePrefix("https://").substringBefore('/'), last4 = o.url.trim().takeLast(4), url = null, botToken = null, chatId = null)
                    else -> o.copy(host = was?.host ?: o.host, last4 = was?.last4 ?: o.last4, url = null, botToken = null, chatId = null)
                }
            }
        }
        // A webhook trigger keeps its token across saves; a new one gets its own, shown once.
        val trigger = when (val t = input.trigger) {
            is Trigger.Webhook -> (old?.trigger as? Trigger.Webhook)?.takeIf { it.last4.isNotEmpty() } ?: run {
                val token = newTokenText()
                keepToken(newId, token)
                Trigger.Webhook(token.takeLast(4), now, fireUrl(newId), fireCommand(newId))
            }
            else -> t
        }
        val a = Automation(
            id = newId,
            name = input.name.trim(),
            enabled = input.enabled,
            onlyOnce = input.onlyOnce,
            trigger = trigger,
            action = steps?.let { WorkflowShape.mirror(it, outputs) ?: it.first().action } ?: input.action,
            template = input.template,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            nextAt = if (input.enabled && current?.paused != true) DemoClock.next(trigger, now, zone) else null,
            lastRun = old?.lastRun,
            runCount = old?.runCount ?: 0,
            // A fixed definition fixes its problem (a project picked again).
            problem = old?.problem?.takeIf { (old.action as? Action.Start)?.projectPath == (input.flowSteps.first().action as? Action.Start)?.projectPath },
            steps = steps ?: WorkflowShape.stepOfAction(input.action).first.let(::listOf),
            outputs = if (steps != null) outputs else WorkflowShape.stepOfAction(input.action).second,
            budget = input.budget?.takeIf { it.any },
        )
        changed(a)
        return Result.success(a)
    }

    /** What the Mac would refuse that the editor can't see for itself. */
    private fun refusal(input: AutomationInput): AutomationError? {
        (input.trigger as? Trigger.Cron)?.let { c ->
            val p = runCatching { Cron.parse(c.expr) }.getOrElse { return AutomationError("bad_cron", it.message.orEmpty(), field = "trigger.expr") }
            if (Cron.tooOften(p, now, zone)) return AutomationError("too_often", res.getString(R.string.wf_err_too_often), field = "trigger.expr")
        }
        for ((i, o) in input.outputs.withIndex()) {
            if (o is Output.File && !o.path.startsWith("~/") && !o.path.startsWith("/Users/")) {
                return AutomationError("outside_projects", res.getString(R.string.au_err_outside), field = "outputs[$i].path")
            }
        }
        if (input.shell && !input.confirmShell) return AutomationError("confirm_shell", res.getString(R.string.wf_err_confirm_shell), field = "steps[${input.flowSteps.indexOfFirst { it.action is Action.Shell }}].command")
        return null
    }

    private fun newTokenText(): String = "hnf_" + (1..32).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")

    override suspend fun newToken(id: String): Result<Automation> {
        delay(200)
        val a = current?.byId(id) ?: return Result.failure(AutomationError("no_automation", res.getString(R.string.au_err_gone)))
        val t = a.trigger as? Trigger.Webhook ?: return Result.failure(AutomationError("not_webhook", res.getString(R.string.wf_err_not_webhook)))
        val token = newTokenText()
        keepToken(id, token)
        val next = a.copy(trigger = t.copy(last4 = token.takeLast(4), createdAt = now), updatedAt = now)
        changed(next)
        return Result.success(next)
    }

    override fun freshToken(id: String): String? = tokenOf(id)
    override fun forgetToken(id: String) = dropToken(id)

    override suspend fun check(trigger: Trigger): Result<TriggerCheck> {
        delay(180)
        return when (trigger) {
            is Trigger.Cron -> {
                val p = runCatching { Cron.parse(trigger.expr) }.getOrElse { return Result.failure(AutomationError("bad_cron", it.message.orEmpty(), field = "trigger.expr")) }
                if (Cron.tooOften(p, now, zone)) Result.failure(AutomationError("too_often", res.getString(R.string.wf_err_too_often), field = "trigger.expr"))
                else Result.success(TriggerCheck(trigger, Cron.next(p, now, zone)))
            }
            is Trigger.Time, is Trigger.Every, is Trigger.Once -> {
                val out = ArrayList<Long>()
                var from = now
                while (out.size < 3) {
                    val n = DemoClock.next(trigger, from, zone) ?: break
                    out += n
                    from = n + 60_000
                }
                Result.success(TriggerCheck(trigger, out))
            }
            else -> Result.success(TriggerCheck(trigger, emptyList()))
        }
    }

    override suspend fun delete(id: String): Result<Unit> {
        current?.byId(id)?.lastRun?.let { working.remove(it.id)?.cancel() }
        apply(AutoMsg.Removed(id))
        return Result.success(Unit)
    }

    override suspend fun setEnabled(id: String, on: Boolean): Result<Automation> {
        val a = current?.byId(id) ?: return Result.failure(HubException(res.getString(R.string.au_err_gone)))
        val next = a.copy(enabled = on, nextAt = if (on) DemoClock.next(a.trigger, now, zone) else null)
        changed(next)
        return Result.success(next)
    }

    override suspend fun runNow(id: String): Result<Run> {
        val a = current?.byId(id) ?: return Result.failure(HubException(res.getString(R.string.au_err_gone)))
        if (a.lastRun?.working == true) return Result.failure(AutomationError("running", res.getString(R.string.au_err_running)))
        return Result.success(begin(a, null, null))
    }

    override suspend fun retry(runId: String, fromStep: String?): Result<Run> {
        val r = find(runId) ?: return Result.failure(HubException(res.getString(R.string.au_err_no_run)))
        if (r.active) return Result.failure(AutomationError("active", res.getString(R.string.au_err_active)))
        val a = current?.byId(r.automationId) ?: return Result.failure(HubException(res.getString(R.string.au_err_gone)))
        if (a.lastRun?.working == true) return Result.failure(AutomationError("running", res.getString(R.string.au_err_running)))
        if (fromStep != null) {
            if (r.steps.none { it.id == fromStep }) return Result.failure(AutomationError("bad_step", res.getString(R.string.wf_err_step), field = "fromStep"))
            val defs = WorkflowShape.stepsOf(a)
            val at = defs.indexOfFirst { it.id == fromStep }
            if (at < 0 || defs.take(at).any { d -> r.steps.none { it.id == d.id } }) return Result.failure(AutomationError("steps_changed", res.getString(R.string.wf_err_steps_changed)))
        }
        return Result.success(begin(a, r, fromStep))
    }

    /** A new run of [a]: Run now, or Retry of [from] (from its step [fromStep], the steps before it taken over). */
    private fun begin(a: Automation, from: Run?, fromStep: String?): Run {
        val defs = WorkflowShape.stepsOf(a)
        val at = fromStep?.let { id -> defs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        val steps = defs.mapIndexed { i, d ->
            if (i < at) from!!.steps.first { it.id == d.id }.copy(reused = true)
            else RunStep(d.id, d.kind, RunStep.PENDING)
        }
        val r = Run(
            runId(), a.id, a.name, "manual", now, null, Run.RUNNING, null, null, emptyList(), now, null,
            retryOf = from?.id, steps = steps, step = at, retryFrom = fromStep, firedDetail = null,
        )
        changed(a.copy(runCount = a.runCount + 1))
        put(r)
        go(a, r, at)
        return r
    }

    /**
     * The demo's engine: each step from [from] on, by the contract's rules (the step before is the
     * nearest earlier one that ran and isn't a notify or say; no guard runs unless it failed; a
     * guard runs when it holds), a few seconds each; an ask waits for its answer or its time; at
     * the end the outputs go when the run is done and not quiet.
     */
    private fun go(a: Automation, first: Run, from: Int) {
        val defs = WorkflowShape.stepsOf(a)
        working[first.id]?.cancel()
        working[first.id] = scope.launch {
            var r = first
            for (i in from until defs.size) {
                val d = defs[i]
                val before = (0 until i).reversed().firstOrNull { k -> r.steps[k].state != Run.SKIPPED && r.steps[k].state != RunStep.PENDING && !Step.tells(defs[k].action) }?.let { r.steps[it] }
                val runs = holds(d.guard, before)
                if (!runs) {
                    r = step(r, i) { it.copy(state = Run.SKIPPED, reason = if (d.guard != null) RunStep.GUARD else RunStep.FAILED_BEFORE) }
                    put(r)
                    continue
                }
                if (r.steps[i].state != Run.NEEDS_YOU) {
                    r = step(r, i) { it.copy(state = Run.RUNNING, startedAt = now) }.copy(step = i, state = Run.RUNNING)
                    put(r)
                }
                val prev = before?.result
                when (val act = d.action) {
                    is Action.Ask -> {
                        val ask = r.ask?.takeIf { it.stepId == d.id } ?: RunAsk(d.id, fill(act.question, prev), act.answers, act.default, now + act.timeoutMin * MIN).also {
                            r = step(r, i) { s -> s.copy(state = Run.NEEDS_YOU) }.copy(state = Run.NEEDS_YOU, ask = it)
                            put(r)
                            noticeFlow.tryEmit(Notice("note:demo${seq++}", Notice.Kind.Automation, null, a.name, it.question, automationId = a.id))
                        }
                        val wait = CompletableDeferred<StepAnswer>()
                        asking[r.id] = wait
                        val got = withTimeoutOrNull((ask.until - now).coerceAtLeast(0)) { wait.await() } ?: StepAnswer(ask.default, StepAnswer.TIMEOUT, now)
                        asking.remove(r.id)
                        r = find(r.id) ?: r
                        if (r.ask != null) {
                            r = step(r, i) { it.copy(state = Run.DONE, endedAt = now, result = got.text, answer = got) }.copy(state = Run.RUNNING, ask = null)
                            put(r)
                        }
                        if (got.by == StepAnswer.TIMEOUT) noticeFlow.tryEmit(Notice("note:demo${seq++}", Notice.Kind.Automation, null, a.name, res.getString(R.string.wf_rs_answer_timeout, got.text), automationId = a.id))
                    }
                    is Action.Shell -> {
                        delay(2_600)
                        r = step(r, i) { it.copy(state = Run.DONE, endedAt = now, exit = 0, result = "$ ${act.command.lineSequence().first()}\nA demo command: nothing really ran.") }
                        put(r)
                    }
                    is Action.Notify, is Action.Say -> {
                        delay(400)
                        val text = fill((act as? Action.Notify)?.text ?: (act as Action.Say).text, prev)
                        noticeFlow.tryEmit(Notice("note:demo${seq++}", Notice.Kind.Automation, null, a.name, text, automationId = a.id))
                        r = step(r, i) { it.copy(state = Run.DONE, endedAt = now, result = text) }
                        put(r)
                    }
                    else -> {
                        delay(4_500)
                        r = step(r, i) { it.copy(state = Run.DONE, endedAt = now, result = DEMO_RESULT) }
                        put(r)
                    }
                }
            }
            // The end: the first step that failed says how it went; else done, and the outputs go.
            val failed = r.steps.firstOrNull { it.state == Run.FAILED || it.state == Run.STOPPED }
            val last = r.steps.indices.reversed().firstOrNull { k -> r.steps[k].state == Run.DONE && !Step.tells(defs[k].action) }?.let { r.steps[it] }
            val done = failed == null
            val quiet = last?.quiet == true
            val outs = a.outputs.map { o ->
                if (!done || quiet) RunOutput(o.id, kindOf(o), RunOutput.SKIPPED)
                else {
                    if (o is Output.Notify || o is Output.Say) noticeFlow.tryEmit(Notice("note:demo${seq++}", Notice.Kind.Automation, null, a.name, o.text ?: last?.result ?: DEMO_RESULT, automationId = a.id))
                    RunOutput(o.id, kindOf(o), RunOutput.SENT, at = now)
                }
            }
            r = r.copy(
                state = failed?.state ?: Run.DONE, reason = failed?.reason, endedAt = now, outputs = outs, ask = null,
                result = failed?.result ?: last?.result, quiet = quiet,
                step = r.steps.indices.lastOrNull { r.steps[it].state != Run.SKIPPED && r.steps[it].state != RunStep.PENDING } ?: 0,
            )
            put(r)
            working.remove(r.id)
        }
    }

    private fun kindOf(o: Output): String = when (o) {
        is Output.Notify -> "notify"
        is Output.Say -> "say"
        is Output.Webhook -> "webhook"
        is Output.File -> "file"
        is Output.Unknown -> o.kind
    }

    /** The contract's guard rule, about [before] (null: only notifications ran, or none). */
    private fun holds(g: Guard?, before: RunStep?): Boolean {
        if (g == null) return before == null || (before.state != Run.FAILED && before.state != Run.STOPPED)
        if (before == null) return g.on == Guard.OK || g.on == Guard.NOT_QUIET
        val done = before.state == Run.DONE
        return when (g.on) {
            Guard.OK -> done
            Guard.FAILED -> before.state == Run.FAILED || before.state == Run.STOPPED
            Guard.QUIET -> done && before.quiet
            Guard.NOT_QUIET -> done && !before.quiet
            Guard.CONTAINS -> before.result?.contains(g.text.orEmpty(), ignoreCase = true) == true
            Guard.ANSWER -> before.answer?.text?.equals(g.text, ignoreCase = true) == true
            else -> false
        }
    }

    private fun fill(text: String, prev: String?): String = text.replace("{prev}", prev ?: "").replace("{week}", "31h with Claude, 412 prompts, 18 sessions").replace("{time}", "now").trim()

    private inline fun step(r: Run, i: Int, f: (RunStep) -> RunStep): Run = r.copy(steps = r.steps.toMutableList().also { it[i] = f(it[i]) })

    override suspend fun answer(runId: String, stepId: String, answer: String): Result<Run> {
        delay(250)
        val r = find(runId) ?: return Result.failure(AutomationError("no_run", res.getString(R.string.au_err_no_run)))
        val ask = r.ask
        if (r.state != Run.NEEDS_YOU || ask == null || ask.stepId != stepId) return Result.failure(AutomationError("not_asking", res.getString(R.string.wf_err_not_asking), run = r))
        val picked = ask.answers.firstOrNull { it.equals(answer.trim(), ignoreCase = true) }
            ?: return Result.failure(AutomationError("bad_answer", res.getString(R.string.wf_err_bad_answer), field = "answer"))
        val got = StepAnswer(picked, "phone", now)
        val i = r.steps.indexOfFirst { it.id == stepId }
        val next = (if (i >= 0) step(r, i) { it.copy(state = Run.DONE, endedAt = now, result = picked, answer = got) } else r).copy(state = Run.RUNNING, ask = null)
        put(next)
        asking[runId]?.complete(got)
        return Result.success(next)
    }

    override suspend fun stop(runId: String): Result<Run> {
        val r = find(runId) ?: return Result.failure(HubException(res.getString(R.string.au_err_no_run)))
        if (!r.active) return Result.failure(HubException(res.getString(R.string.au_err_no_run)))
        working.remove(runId)?.cancel()
        asking.remove(runId)
        val steps = r.steps.map {
            when {
                it.active -> it.copy(state = Run.STOPPED, reason = "by_user", endedAt = now)
                it.state == RunStep.PENDING -> it.copy(state = Run.SKIPPED, reason = RunStep.NOT_REACHED)
                else -> it
            }
        }
        val stopped = r.copy(state = Run.STOPPED, reason = "by_user", endedAt = now, steps = steps, ask = null, outputs = (current?.byId(r.automationId)?.outputs ?: emptyList()).map { RunOutput(it.id, kindOf(it), RunOutput.SKIPPED) })
        put(stopped)
        return Result.success(stopped)
    }

    override suspend fun setPaused(paused: Boolean): Result<Unit> {
        val s = current ?: return Result.success(Unit)
        current = s.copy(paused = paused, list = s.list.map { it.copy(nextAt = if (!paused && it.enabled) DemoClock.next(it.trigger, now, zone) else null) })
        return Result.success(Unit)
    }

    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR
        private const val DEMO_RESULT = "A demo run: nothing really ran. On your Mac, Claude's first sentence lands here."
        /** The review's command, as the template makes it: Claude's review is step 1's whole result. */
        private const val REVIEW_COMMAND = "n=\$(printf '%s\\n' \"\$HYPERNOVA_PAYLOAD\" | sed -n 's/^PR #\\([0-9]*\\).*/\\1/p' | head -n 1)\ngh pr review \"\$n\" --comment --body \"\$HYPERNOVA_STEP_st_rv1\""
        const val PLUGGED = "au_demo_plugged"
        const val PRS = "au_demo_prs"
        const val RELEASE = "au_demo_release"
        const val BRIEF = "au_demo_brief"
        const val CARRY = "au_demo_carry"
        const val LENS = "au_demo_lens"
        const val NIGHT = "au_demo_night"
        const val BATTERY = "au_demo_battery"
        const val REVIEW = "au_demo_review"
        const val FIX = "au_demo_fix"
        const val SHIP = "au_demo_ship"
        const val DOCS = "au_demo_docs"
        const val HOOK = "au_demo_hook"
        const val QUARTER = "au_demo_quarter"
        const val TEAM = "au_demo_team"
        /** The demo's sessions its automations started (FakeHub has them). */
        const val SESSION_TESTS = "s-auto-tests"
        const val SESSION_PRS = "s-auto-prs"
        const val SESSION_BRIEF = "s-auto-brief"
    }
}

/** When a time trigger is due next, for the demo (the Mac works this out for real ones). */
object DemoClock {
    fun next(t: Trigger, now: Long, zone: ZoneId): Long? {
        val from = Instant.ofEpochMilli(now).atZone(zone)
        return when (t) {
            is Trigger.Once -> t.at.takeIf { it > now }
            is Trigger.Time -> {
                val time = LocalTime.parse(t.at)
                (0..7L).asSequence()
                    .map { from.toLocalDate().plusDays(it) }
                    .filter { it.dayOfWeek.value in t.days }
                    .map { ZonedDateTime.of(it, time, zone) }
                    .firstOrNull { it.isAfter(from) }?.toInstant()?.toEpochMilli()
            }
            is Trigger.Every -> {
                val start = LocalTime.parse(t.from)
                val end = t.to?.let { LocalTime.parse(it) } ?: LocalTime.of(23, 59)
                (0..7L).asSequence()
                    .map { from.toLocalDate().plusDays(it) }
                    .filter { it.dayOfWeek.value in t.days }
                    .flatMap { day -> generateSequence(start) { it.plusHours(t.hours.toLong()).takeIf { n -> n.isAfter(it) } }.takeWhile { !it.isAfter(end) }.map { ZonedDateTime.of(day, it, zone) } }
                    .firstOrNull { it.isAfter(from) }?.toInstant()?.toEpochMilli()
            }
            is Trigger.Cron -> runCatching { Cron.next(Cron.parse(t.expr), now, zone, 1).firstOrNull() }.getOrNull()
            else -> null
        }
    }
}
