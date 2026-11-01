package com.prudnikweb.hypernova.ui.screens

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMsg
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.AutomationBook
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Automations
import com.prudnikweb.hypernova.data.AutomationsState
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.RunAsk
import com.prudnikweb.hypernova.data.RunPage
import com.prudnikweb.hypernova.data.RunStep
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.TriggerCheck
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.AutoMode

/**
 * [Automations] for screen tests: the book's own state (as the live one keeps it), no network.
 * Every call lands in [calls] as one line ("setEnabled(au-brief, false)"); [refuse] makes the next
 * calls fail with that refusal.
 */
class FakeAutomations(state: AutomationsState?) : AutomationBook(), Automations {
    val calls = mutableListOf<String>()
    var refuse: AutomationError? = null

    init {
        applySnapshot(state)
    }

    override val state: AutomationsState? get() = current
    override fun runs(id: String): RunPage? = page(id)

    private fun <T> answer(name: String, vararg args: Any?, ok: () -> T): Result<T> {
        calls += "$name(${args.joinToString(", ")})"
        refuse?.let { return Result.failure(it) }
        return Result.success(ok())
    }

    override suspend fun loadRuns(id: String, more: Boolean): Result<Unit> = answer("loadRuns", id, more) { setPage(id, page(id) ?: RunPage(listOfNotNull(automation(id)?.lastRun), more = false, total = null, skipped = null)) }

    override suspend fun save(id: String?, input: AutomationInput): Result<Automation> = answer("save", id, input.name) {
        val a = (id?.let(::automation) ?: Samples.automation("au-new", input.name)).copy(name = input.name, enabled = input.enabled, trigger = input.trigger, action = input.action, steps = input.steps, outputs = input.outputs)
        apply(AutoMsg.Changed(a))
        a
    }

    override suspend fun delete(id: String): Result<Unit> = answer("delete", id) { apply(AutoMsg.Removed(id)) }

    override suspend fun setEnabled(id: String, on: Boolean): Result<Automation> = answer("setEnabled", id, on) {
        val a = automation(id)!!.copy(enabled = on)
        apply(AutoMsg.Changed(a))
        a
    }

    override suspend fun runNow(id: String): Result<Run> = answer("runNow", id) {
        val r = Samples.run("ru-now", id, Run.RUNNING)
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun stop(runId: String): Result<Run> = answer("stop", runId) { Samples.run(runId, "au-brief", Run.STOPPED) }
    override suspend fun retry(runId: String, fromStep: String?): Result<Run> = answer("retry", runId, fromStep) { Samples.run("ru-again", "au-brief", Run.RUNNING) }
    override suspend fun setPaused(paused: Boolean): Result<Unit> = answer("setPaused", paused) { apply(AutoMsg.Counts(paused)) }

    override suspend fun answer(runId: String, stepId: String, answer: String): Result<Run> = answer("answer", runId, stepId, answer) {
        val r = Samples.run(runId, Samples.DEPLOY_GATE, Run.DONE)
        apply(AutoMsg.RunChanged(r))
        r
    }

    override suspend fun check(trigger: Trigger): Result<TriggerCheck> = answer("check", trigger) { TriggerCheck(trigger, emptyList()) }
    override suspend fun newToken(id: String): Result<Automation> = answer("newToken", id) { automation(id)!! }
    override fun freshToken(id: String): String? = null
    override fun forgetToken(id: String) = Unit
}

/** A [Hub] that is [base] in every way but its automations. */
class AutomationsHub(val base: FakeHub, override val automations: Automations) : Hub by base

/** The automations the screen tests draw: a morning brief, an ask that waits on the owner, one switched off. */
object AutoSamples {
    fun state(paused: Boolean = false, list: List<Automation> = list(), features: Set<String>? = setOf("steps", "ask")) =
        AutomationsState(paused = paused, list = list, tz = "UTC", hasBattery = true, features = features)

    fun list(): List<Automation> = listOf(
        Samples.automation("au-brief", "Nightly digest"),
        Samples.automation(Samples.DEPLOY_GATE, "Deploy gate", steps = Samples.askSteps()).copy(lastRun = Samples.run("ru-ask", Samples.DEPLOY_GATE, Run.NEEDS_YOU, asking = true)),
        Samples.automation("au-off", "Weekly cleanup", enabled = false),
    )
}

/** Automations' samples, beside [Samples]'s sessions; times relative to [Samples.NOW]. */
fun Samples.automation(id: String, name: String, enabled: Boolean = true, steps: List<Step>? = null) = Automation(
    id = id, name = name, enabled = enabled, onlyOnce = false,
    trigger = Trigger.Time("09:00", Trigger.WEEKDAYS),
    action = steps?.let { Action.Unknown("workflow", "{}") } ?: Action.Start("/home/owner/projects/hypernova", "Summarise what changed overnight", "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NOTIFY),
    template = null, steps = steps,
)

/** Ask "Deploy now?", then notify "Deploying." when the answer is Deploy (the contract fixtures' workflow). */
fun Samples.askSteps() = listOf(
    Step("st_q", null, Action.Ask("Deploy now?", listOf("Deploy", "Not now"), "Not now", 10)),
    Step("st_n", Guard("answer", "Deploy"), Action.Notify("Deploying.")),
)

/** A run of automation [automationId]; [asking]: its ask waits, with 10 minutes (and a half) left. */
fun Samples.run(id: String, automationId: String, state: String, asking: Boolean = false) = Run(
    id = id, automationId = automationId, name = "", firedBy = "manual", firedAt = NOW - 60_000L, firedSessionId = null,
    state = state, reason = null, until = null, sessionIds = emptyList(), startedAt = NOW - 60_000L, endedAt = null,
    steps = if (asking) listOf(RunStep("st_q", "ask", Run.NEEDS_YOU), RunStep("st_n", "notify", RunStep.PENDING)) else emptyList(),
    ask = if (asking) RunAsk("st_q", "Deploy now?", listOf("Deploy", "Not now"), "Not now", NOW + 10 * 60_000L + 30_000L) else null,
)

/** The ask workflow's id. */
val Samples.DEPLOY_GATE: String get() = "au-gate"
