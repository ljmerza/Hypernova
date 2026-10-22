package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/*
 * Automations (docs/specs/spec-automations.md §3; docs/PROTOCOL.md, "Automations"): "When … → Do …"
 * that run on the Mac, so they work with the phone off. The phone edits them and shows their runs;
 * the Mac decides when they fire. Times of day are "HH:MM" on the Mac's clock (Snapshot.mac.tz).
 */

/** Session.automation: the automation (and its run) that started a session. */
@Immutable
data class SessionAutomation(val id: String, val name: String, val runId: String?)

/** "When …": what fires an automation. */
sealed interface Trigger {
    /** At [at] on [days] (ISO 1 = Monday … 7 = Sunday). */
    @Immutable data class Time(val at: String, val days: List<Int>) : Trigger
    /** Once, at [at] (epoch ms on a whole minute). */
    @Immutable data class Once(val at: Long) : Trigger
    /** Every [hours] from [from], up to and including [to] (null: around the clock), on [days]. */
    @Immutable data class Every(val hours: Int, val from: String, val to: String?, val days: List<Int>) : Trigger
    /**
     * A usage window resets: [window] is "five_hour" or "seven_day"; [afterLimit]: only when the
     * owner hit it. [from] and [to] narrow it to resets between two times (Night shift).
     */
    @Immutable data class Reset(val window: String, val afterLimit: Boolean, val from: String? = null, val to: String? = null) : Trigger
    /** The account hits a limit ([window]): Switch provider at the limit. */
    @Immutable data class Limit(val window: String) : Trigger
    /**
     * A session finishes or needs the owner: in [projectPath] (null: any), started by [from]. [after]
     * (needs you only, 1–120 minutes): only if it still needs the owner that long after; null: at once.
     */
    @Immutable data class SessionEvent(val event: String, val projectPath: String?, val from: String, val after: Int? = null) : Trigger
    /** The Mac is plugged in. */
    data object Plugged : Trigger
    /** The Mac's battery drops to [below]% (5–50, step 5). */
    @Immutable data class BatteryLow(val below: Int) : Trigger
    /**
     * Fired by a POST with its token (or `hypernova automation fire`): what the Mac says of it. The
     * token itself is shown once, in the save's answer ([Automations.freshToken]); saved, only the
     * kind goes (the Mac keeps its token across saves).
     */
    @Immutable data class Webhook(val last4: String = "", val createdAt: Long = 0L, val url: String = "", val command: String = "") : Trigger
    /** Five cron fields on the Mac's clock: minute, hour, day of the month, month, day of the week. */
    @Immutable data class Cron(val expr: String) : Trigger
    /** Files in [path] (a folder inside the projects folder) matching [glob] (null: any) are [event]: created, changed or deleted. */
    @Immutable data class FileWatch(val path: String, val glob: String?, val event: String) : Trigger
    /** GitHub, through `gh` on the Mac, every [every] minutes: [events] in the repository at [repoPath]. */
    @Immutable data class Github(val repoPath: String, val events: List<String>, val every: Int) : Trigger
    /** A Claude Code hook [event] in a session ([tool]: PostToolUseFailure's one tool, null any), filtered as the session trigger is. */
    @Immutable data class Hook(val event: String, val tool: String?, val projectPath: String?, val from: String) : Trigger
    /** A kind this app doesn't know: kept as the Mac sent it, shown but not edited. */
    @Immutable data class Unknown(val kind: String, val json: String) : Trigger

    companion object {
        const val FIVE_HOUR = "five_hour"
        const val SEVEN_DAY = "seven_day"
        const val FINISHED = "finished"
        const val NEEDS_YOU = "needs_you"
        const val MINE = "mine"
        const val AUTOMATED = "automated"
        const val ANY = "any"
        val WEEKDAYS = listOf(1, 2, 3, 4, 5)
        val ALL_DAYS = listOf(1, 2, 3, 4, 5, 6, 7)
        /** The hours "Every few hours" offers: each divides 24, so the grid is the same every day. */
        val HOURS = listOf(1, 2, 3, 4, 6, 8, 12)
        const val CREATED = "created"
        const val CHANGED = "changed"
        const val DELETED = "deleted"
        const val PR_OPENED = "pr_opened"
        const val REVIEW_COMMENT = "review_comment"
        const val CHECKS_FAILED = "checks_failed"
        val GITHUB_EVENTS = listOf(PR_OPENED, REVIEW_COMMENT, CHECKS_FAILED)
        const val H_STOP = "Stop"
        const val H_STOP_FAILURE = "StopFailure"
        const val H_TOOL_FAILURE = "PostToolUseFailure"
        const val H_PRE_COMPACT = "PreCompact"
        const val H_SESSION_END = "SessionEnd"
        val HOOK_EVENTS = listOf(H_STOP, H_TOOL_FAILURE, H_STOP_FAILURE, H_PRE_COMPACT, H_SESSION_END)
    }
}

/** Fires on the clock (it has a next time), rather than on something happening. */
val Trigger.timed: Boolean get() = this is Trigger.Time || this is Trigger.Once || this is Trigger.Every || this is Trigger.Cron

/** "When it's done", for an action that starts or continues a session. */
object Then {
    const val NONE = "none"
    const val NOTIFY = "notify"
    const val SAY = "say"
}

/** "Do …": what an automation does. */
sealed interface Action {
    /**
     * Start a session in [projectPath] with [prompt] (a "/name args" prompt runs a skill or
     * command), on [providerId] and [model] (null: the provider's default), in [permissionMode],
     * with [allowedTools] as Claude Code rules ("Bash(git log:*)").
     */
    @Immutable data class Start(
        val projectPath: String,
        val prompt: String,
        val providerId: String,
        val model: String?,
        val permissionMode: String,
        val allowedTools: List<String>,
        val then: String,
    ) : Action {
        /** A skill or command: the prompt is "/name" with its arguments. */
        val skill: Boolean get() = prompt.trimStart().startsWith("/")
    }

    /**
     * Prompt a session: [target] is a session id, [TRIGGER] (the one that fired it), [WORKING]
     * (every working one) or [LIMITED] (every one a limit stopped). An empty [prompt] carries a
     * stopped session on as Continue does. [providerId]: move it to that provider first (Switch
     * provider at the limit; it costs money).
     */
    @Immutable data class Continue(val target: String, val prompt: String, val then: String, val providerId: String? = null) : Action {
        companion object {
            const val TRIGGER = "trigger"
            const val WORKING = "working"
            const val LIMITED = "limited"
            /** "step:<id>": the sessions an earlier start or continue step of the same run worked in. */
            const val STEP = "step:"
        }
    }

    /** A notification on the phone; [glasses]: also read aloud on them when they're connected. */
    @Immutable data class Notify(val text: String, val glasses: Boolean = false) : Action
    /** Read aloud on the glasses (on the phone when they aren't connected). */
    @Immutable data class Say(val text: String) : Action
    /**
     * A workflow's pause for a person: [question] with two or three [answers]; [default] (one of
     * them) is taken after [timeoutMin] minutes. Its answer is the step's output.
     */
    @Immutable data class Ask(
        val question: String,
        val answers: List<String> = WorkflowShape.ASK_DEFAULT_ANSWERS,
        val default: String = answers.last(),
        val timeoutMin: Int = WorkflowShape.ASK_TIMEOUT_MIN,
    ) : Action
    /** A workflow's command: `/bin/sh -c [command]` in [projectPath], no TTY, [timeoutSec] at most. */
    @Immutable data class Shell(val projectPath: String, val command: String, val timeoutSec: Int = WorkflowShape.SHELL_TIMEOUT_SEC) : Action
    @Immutable data class Unknown(val kind: String, val json: String) : Action
}

/** The permission modes an automation's session can start in, safest first. */
object AutoMode {
    /** "Allowed only" (Claude Code's dontAsk): runs what the rules allow, skips the rest, never asks. The default. */
    const val ALLOWED = "dontAsk"
    const val ASK = "default"
    const val EDITS = "acceptEdits"
    const val AUTO = "auto"
    const val NO_ASKING = "bypassPermissions"
    val ALL = listOf(ALLOWED, ASK, EDITS, AUTO, NO_ASKING)
}

@Immutable
data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** Turns itself off after its first run that starts. */
    val onlyOnce: Boolean,
    val trigger: Trigger,
    val action: Action,
    /** The template it was made from ("brief", "reset", …), so START FROM leaves it out. */
    val template: String?,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** When it's due next (time triggers); null when off, paused, or it fires on events. */
    val nextAt: Long? = null,
    val lastRun: Run? = null,
    /** Every run, skips and misses included. */
    val runCount: Int = 0,
    /**
     * "no_battery", "no_project", "no_session", "no_provider", "no_glasses", "outside_projects" (its
     * folder is outside the projects folder), "no_model" (its model is no longer offered),
     * "bad_definition" (what the Mac kept doesn't read as one: off until saved again), or
     * "mode_unavailable" (its last run ran in another mode than asked); null when fine.
     */
    val problem: String? = null,
    /** It runs on a provider other than the built-in one: that costs money. */
    val paid: Boolean = false,
    /** Its steps, from a daemon that has workflows (`features` sent); null from an older one (its [action] is all). */
    val steps: List<Step>? = null,
    /** Where a run's result goes (a daemon with workflows). */
    val outputs: List<Output> = emptyList(),
    val budget: Budget? = null,
) {
    /**
     * The mode its sessions start in: of several start steps the one that asks least (No asking
     * over Auto over Edits over Ask me), for the card's chip; null when it starts none.
     */
    val mode: String? get() = WorkflowShape.stepsOf(this).mapNotNull { (it.action as? Action.Start)?.permissionMode }.maxByOrNull { AutoMode.ALL.indexOf(it) }
}

/** One firing of an automation and what came of it. */
@Immutable
data class Run(
    val id: String,
    val automationId: String,
    /** The automation's name when it ran. */
    val name: String,
    /** "schedule", "reset", "session", "power" or "manual". */
    val firedBy: String,
    /** When it was due, or when the event happened. */
    val firedAt: Long,
    /** The session that fired it. */
    val firedSessionId: String?,
    /** [QUEUED], [WAITING], [RUNNING], [NEEDS_YOU], [DONE], [FAILED], [SKIPPED], [STOPPED]. */
    val state: String,
    val reason: String?,
    /** Waiting: what it waits for (a reset). */
    val until: Long?,
    /** The sessions it started or prompted. */
    val sessionIds: List<String>,
    val startedAt: Long?,
    val endedAt: Long?,
    /** How long after [firedAt] it started (the Mac was asleep). */
    val late: Long = 0L,
    /** Due times the Mac slept through, folded into this run. */
    val missed: Int = 0,
    /** Why it ran late or was missed: "asleep" (the Mac), "down" (Hypernova wasn't running); null on time. */
    val lateWhy: String? = null,
    /** The final reply's first two sentences. */
    val result: String? = null,
    /** The reply was exactly "Nothing new.": nobody was told. */
    val quiet: Boolean = false,
    /** What "Allowed only" refused, as rules ("Bash(npm test)"). */
    val skippedTools: List<String> = emptyList(),
    /** The mode the session really ran in. */
    val permissionMode: String? = null,
    /** Retry: the run this one runs again. */
    val retryOf: String? = null,
    /** One per step of the definition it ran ([] from a daemon without workflows, or a run kept from before steps). */
    val steps: List<RunStep> = emptyList(),
    /** The step at work; once ended, the last one that ran. */
    val step: Int = 0,
    /** It waits for an answer to an ask step (state needs_you). */
    val ask: RunAsk? = null,
    /** What became of each output, once it ended. */
    val outputs: List<RunOutput> = emptyList(),
    /** Retry from a step: the step it began at. */
    val retryFrom: String? = null,
    val budgetUsed: BudgetUsed? = null,
    /** What fired it, in a few words (a file watch's "docs/plan.md and 2 more", GitHub's first change, a hook's event). */
    val firedDetail: String? = null,
) {
    val active: Boolean get() = state in ACTIVE
    /** It holds one of the Mac's two slots: Stop ends it. */
    val working: Boolean get() = state == RUNNING || state == NEEDS_YOU
    /** Over, and not as it should have: Retry runs it again with what fired it. */
    val retriable: Boolean get() = state == FAILED || state == STOPPED || state == SKIPPED

    companion object {
        const val QUEUED = "queued"
        const val WAITING = "waiting"
        const val RUNNING = "running"
        const val NEEDS_YOU = "needs_you"
        const val DONE = "done"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
        const val STOPPED = "stopped"
        val ACTIVE = setOf(QUEUED, WAITING, RUNNING, NEEDS_YOU)
    }
}

/** A notification an automation sent (`to` "phone" or "glasses"); [ask]: an ask step's question, with its answers. */
@Immutable
data class AutomationNote(val id: String, val automationId: String?, val runId: String?, val name: String, val text: String, val to: String, val at: Long, val ask: RunAsk? = null) {
    companion object {
        /** Where a note goes: the phone shows "phone" notes, Lens "glasses" ones. */
        const val PHONE = "phone"
        /** Notes older than this aren't shown on reconnect (the Mac keeps 12 hours). */
        const val KEPT = 12 * 3_600_000L
    }
}

/** Snapshot.automations, kept up to date by the socket. */
@Immutable
data class AutomationsState(
    val paused: Boolean,
    val list: List<Automation>,
    /** The last 12 hours of notes, newest first. */
    val notes: List<AutomationNote> = emptyList(),
    /** A Lens device is paired: "Read aloud on my glasses" can be chosen. */
    val glasses: Boolean = false,
    /** The Mac's time zone (Snapshot.mac.tz), which every time of day is in; null: the phone's. */
    val tz: String? = null,
    /** Power triggers can work here: false on a Mac without a battery; null while the Mac doesn't know yet (or doesn't say). */
    val hasBattery: Boolean? = null,
    /**
     * What it can do of workflows ([Features]); null from a daemon without them, whose editor is
     * today's one action. Present (even empty), automations come with steps and save that way.
     */
    val features: Set<String>? = null,
) {
    fun byId(id: String): Automation? = list.firstOrNull { it.id == id }

    /** It offers [feature] of workflows. */
    fun has(feature: String): Boolean = features?.contains(feature) == true

    /** The runs waiting for an answer to an ask step, oldest question first. */
    val asks: List<Run> get() = list.mapNotNull { it.lastRun?.takeIf { r -> r.state == Run.NEEDS_YOU && r.ask != null } }.sortedBy { it.ask!!.until }
}

/** What's saved: the whole definition (a PUT replaces it). */
data class AutomationInput(
    val name: String,
    val enabled: Boolean,
    val onlyOnce: Boolean,
    val trigger: Trigger,
    val action: Action,
    val template: String?,
    /** Set only after the fingerprint check, for "No asking". */
    val confirmBypass: Boolean = false,
    /** Set only after the owner agreed that it costs money (another provider). */
    val confirmPaid: Boolean = false,
    /** The new shape (a daemon with workflows): these steps, not [action]. Null: today's action and its `then`. */
    val steps: List<Step>? = null,
    val outputs: List<Output> = emptyList(),
    val budget: Budget? = null,
    /** Set only after the fingerprint check, for a shell step. */
    val confirmShell: Boolean = false,
) {
    /** Any start step in No asking: Save asks for the fingerprint. */
    val bypass: Boolean get() = flowSteps.any { (it.action as? Action.Start)?.permissionMode == AutoMode.NO_ASKING }
    /** Any shell step: Save asks for the fingerprint, every time. */
    val shell: Boolean get() = flowSteps.any { it.action is Action.Shell }

    /**
     * It runs on a provider other than the built-in one, [builtIn]: saving it needs [confirmPaid].
     * A continue without a provider of its own runs on its session's, so continuing one particular
     * session that is on another provider costs money too, as the Mac counts it; [providerOf] says
     * which provider a session is on (null: the built-in one, or not known here). [costs]: whether a
     * provider costs money (the owner can say one doesn't: Provider.paid).
     */
    fun paid(builtIn: String, providerOf: (String) -> String? = { null }, costs: (String) -> Boolean = { true }): Boolean = paidProvider(builtIn, providerOf, costs) != null

    /**
     * The provider that makes it cost money, or null when it runs on the built-in one (or one that
     * costs nothing). Every step is looked at (a workflow's), as the Mac does.
     */
    fun paidProvider(builtIn: String, providerOf: (String) -> String? = { null }, costs: (String) -> Boolean = { true }): String? =
        flowSteps.firstNotNullOfOrNull { s ->
            when (val a = s.action) {
                is Action.Start -> a.providerId.takeIf { it != builtIn }
                is Action.Continue -> a.providerId?.takeIf { it.isNotEmpty() }?.takeIf { it != builtIn }
                    ?: a.target.takeIf { a.providerId.isNullOrEmpty() && it !in setOf(Action.Continue.TRIGGER, Action.Continue.WORKING, Action.Continue.LIMITED) && !it.startsWith(Action.Continue.STEP) }
                        ?.let(providerOf)?.takeIf { it.isNotEmpty() && it != builtIn }
                else -> null
            }?.takeIf(costs)
        }
}

fun Automation.input(): AutomationInput = AutomationInput(name, enabled, onlyOnce, trigger, action, template, steps = steps, outputs = outputs, budget = budget)

/**
 * A page of an automation's runs, newest first. [total] and [skipped] count every run the Mac
 * keeps (50 at most), not just those loaded; null from an older Mac.
 */
@Immutable
data class RunPage(
    val runs: List<Run> = emptyList(),
    val more: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val total: Int? = null,
    val skipped: Int? = null,
)

/**
 * A daemon refusal about one part of the editor: [code] is the daemon's ("bad_time",
 * "rule_too_broad"…), so the editor shows it under the block it belongs to. [until]: Run now's
 * `limited`, the reset it waits for, when the Mac said.
 */
class AutomationError(
    val code: String?,
    message: String,
    val until: Long? = null,
    /** The part of the body it's about ("steps[2].command", "trigger.expr"), so the editor marks that field. */
    val field: String? = null,
    /** `not_asking`: the run as it is now (answered elsewhere, taken as its default, or stopped). */
    val run: Run? = null,
) : HubException(message)

/** What the phone asks of the Mac about automations; [Hub.automations]. */
interface Automations {
    /** Null: the Mac's Hypernova is too old to have them. */
    val state: AutomationsState?
    /** The runs loaded for [id] so far (null before the first load). */
    fun runs(id: String): RunPage?
    /** The first page, or the next one ([more]). */
    suspend fun loadRuns(id: String, more: Boolean = false): Result<Unit>
    /** Adds ([id] null) or replaces an automation. */
    suspend fun save(id: String?, input: AutomationInput): Result<Automation>
    suspend fun delete(id: String): Result<Unit>
    suspend fun setEnabled(id: String, on: Boolean): Result<Automation>
    /** Run now, also while paused or off. */
    suspend fun runNow(id: String): Result<Run>
    /** A queued or waiting run is dropped; a running one gets Esc in each of its sessions. */
    suspend fun stop(runId: String): Result<Run>
    /**
     * An ended run again, with what fired it: the new run (its [Run.retryOf] names this one).
     * [fromStep]: from that step on, the steps before it taken over from the run it retries.
     */
    suspend fun retry(runId: String, fromStep: String? = null): Result<Run>
    suspend fun setPaused(paused: Boolean): Result<Unit>
    /** An ask step answered (any device may; the first answer counts): `not_asking` carries the run as it is. */
    suspend fun answer(runId: String, stepId: String, answer: String): Result<Run>
    /** A trigger checked as Save would, with its next three due times (cron, time, every, once). */
    suspend fun check(trigger: Trigger): Result<TriggerCheck>
    /** A webhook trigger's new token: the old one stops working at once. The token is in [freshToken]. */
    suspend fun newToken(id: String): Result<Automation>
    /** A webhook trigger's token, made by the last save or [newToken], shown once: null once [forgetToken]. */
    fun freshToken(id: String): String?
    fun forgetToken(id: String)

    fun automation(id: String): Automation? = state?.byId(id)

    /** A Mac without automations: nothing to show, nothing to do. */
    object None : Automations {
        override val state: AutomationsState? = null
        override fun runs(id: String): RunPage? = null
        private fun no() = Result.failure<Nothing>(HubException(""))
        override suspend fun loadRuns(id: String, more: Boolean): Result<Unit> = no()
        override suspend fun save(id: String?, input: AutomationInput): Result<Automation> = no()
        override suspend fun delete(id: String): Result<Unit> = no()
        override suspend fun setEnabled(id: String, on: Boolean): Result<Automation> = no()
        override suspend fun runNow(id: String): Result<Run> = no()
        override suspend fun stop(runId: String): Result<Run> = no()
        override suspend fun retry(runId: String, fromStep: String?): Result<Run> = no()
        override suspend fun setPaused(paused: Boolean): Result<Unit> = no()
        override suspend fun answer(runId: String, stepId: String, answer: String): Result<Run> = no()
        override suspend fun check(trigger: Trigger): Result<TriggerCheck> = no()
        override suspend fun newToken(id: String): Result<Automation> = no()
        override fun freshToken(id: String): String? = null
        override fun forgetToken(id: String) = Unit
    }
}

/** The socket's automation messages, parsed off the main thread. */
sealed interface AutoMsg {
    class Changed(val a: Automation) : AutoMsg
    class Removed(val id: String) : AutoMsg
    class Counts(val paused: Boolean) : AutoMsg
    class RunChanged(val run: Run) : AutoMsg
    class Note(val note: AutomationNote) : AutoMsg
}

/**
 * The automations as last heard, as Compose state: the snapshot's list, then the socket's changes.
 * Shared by the live client and the demo.
 */
open class AutomationBook {
    var current by mutableStateOf<AutomationsState?>(null)
        protected set
    private val pages = mutableStateMapOf<String, RunPage>()
    /** Webhook tokens just made, by automation, until shown and put away (never kept on disk). */
    private val tokens = mutableStateMapOf<String, String>()

    fun tokenOf(id: String): String? = tokens[id]
    fun dropToken(id: String) { tokens.remove(id) }
    protected fun keepToken(id: String, token: String) { tokens[id] = token }

    fun page(id: String): RunPage? = pages[id]
    protected fun setPage(id: String, p: RunPage) { pages[id] = p }

    /** A fresh snapshot (null: a daemon without automations). Loaded runs are kept: the socket fills them in. */
    open fun applySnapshot(s: AutomationsState?) {
        current = s
        if (s == null) pages.clear() else pages.keys.retainAll(s.list.map { it.id }.toSet())
    }

    open fun apply(m: AutoMsg) {
        val s = current ?: return
        when (m) {
            is AutoMsg.Changed -> current = s.copy(list = upsert(s.list, m.a))
            is AutoMsg.Removed -> {
                current = s.copy(list = s.list.filter { it.id != m.id })
                pages.remove(m.id)
            }
            is AutoMsg.Counts -> current = s.copy(paused = m.paused)
            is AutoMsg.RunChanged -> {
                val r = m.run
                // The card's status line follows its newest run, before the automation itself says so.
                val a = s.byId(r.automationId)
                if (a != null && (a.lastRun == null || a.lastRun.id == r.id || r.firedAt >= a.lastRun.firedAt)) {
                    current = s.copy(list = upsert(s.list, a.copy(lastRun = r)))
                }
                pages[r.automationId]?.let { p -> pages[r.automationId] = withRun(p, r) }
            }
            is AutoMsg.Note -> current = s.copy(notes = (listOf(m.note) + s.notes.filter { it.id != m.note.id }).take(20))
        }
    }

    protected fun upsert(list: List<Automation>, a: Automation): List<Automation> {
        val at = list.indexOfFirst { it.id == a.id }
        return if (at >= 0) list.toMutableList().also { it[at] = a } else list + a
    }
}

/**
 * [p] with [r] in it: in its place, or new at the top. A new run counts in the Mac's totals too, and a
 * run that turned skipped in the skipped count, until the next load says them again.
 */
fun withRun(p: RunPage, r: Run): RunPage {
    val at = p.runs.indexOfFirst { it.id == r.id }
    val fresh = at < 0
    val nowSkipped = r.state == Run.SKIPPED && (fresh || p.runs[at].state != Run.SKIPPED)
    return p.copy(
        runs = if (!fresh) p.runs.toMutableList().also { it[at] = r } else listOf(r) + p.runs,
        total = p.total?.let { if (fresh) it + 1 else it },
        skipped = p.skipped?.let { if (nowSkipped) it + 1 else it },
    )
}

/** The automation wire format (spec §3), parsed leniently: unknown kinds are kept, never fatal. */
object AutomationWire {
    private val CLOCK = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /** "9:00" or "09:00" → "09:00"; anything else → null. */
    fun clock(s: String?): String? {
        val m = CLOCK.find(s?.trim() ?: return null) ?: return null
        return String.format(java.util.Locale.ROOT, "%02d:%s", m.groupValues[1].toInt(), m.groupValues[2])
    }

    private fun days(a: JSONArray?): List<Int> {
        if (a == null) return Trigger.ALL_DAYS
        val out = (0 until a.length()).mapNotNull { a.optInt(it, 0).takeIf { d -> d in 1..7 } }.distinct().sorted()
        return out.ifEmpty { Trigger.ALL_DAYS }
    }

    fun trigger(o: JSONObject?): Trigger {
        if (o == null) return Trigger.Unknown("", "{}")
        return when (val kind = o.str("kind").orEmpty()) {
            "time" -> clock(o.str("at"))?.let { Trigger.Time(it, days(o.arr("days"))) }
            "once" -> o.long("at")?.let { Trigger.Once(it) }
            "every" -> {
                val from = clock(o.str("from")) ?: "00:00"
                Trigger.Every(o.int("hours")?.takeIf { it in 1..24 } ?: 1, from, clock(o.str("to")), days(o.arr("days")))
            }
            "reset" -> Trigger.Reset(o.str("window") ?: Trigger.FIVE_HOUR, o.optBoolean("afterLimit", true), clock(o.str("from")), clock(o.str("to")))
            "limit" -> Trigger.Limit(o.str("window") ?: Trigger.FIVE_HOUR)
            "session" -> Trigger.SessionEvent(o.str("event") ?: Trigger.FINISHED, o.str("projectPath"), o.str("from") ?: Trigger.MINE, o.int("after")?.takeIf { it > 0 })
            "power" -> when (o.str("event")) {
                "plugged_in" -> Trigger.Plugged
                "low" -> Trigger.BatteryLow((o.int("below") ?: 20).coerceIn(5, 50))
                else -> null
            }
            "webhook" -> Trigger.Webhook(o.str("last4").orEmpty(), o.long("createdAt") ?: 0L, o.str("url").orEmpty(), o.str("command").orEmpty())
            "cron" -> o.str("expr")?.let { Trigger.Cron(it) }
            "file" -> o.str("path")?.let { Trigger.FileWatch(it, o.str("glob")?.takeIf { g -> g.isNotBlank() }, o.str("event") ?: Trigger.CHANGED) }
            "github" -> o.str("repoPath")?.let { Trigger.Github(it, o.arr("events").strings().ifEmpty { listOf(Trigger.PR_OPENED) }, o.int("every") ?: 10) }
            "hook" -> o.str("event")?.let { Trigger.Hook(it, o.str("tool")?.takeIf { t -> t.isNotBlank() }, o.str("projectPath"), o.str("from") ?: Trigger.MINE) }
            else -> null
        } ?: Trigger.Unknown(o.str("kind").orEmpty(), o.toString())
    }

    fun action(o: JSONObject?): Action {
        if (o == null) return Action.Unknown("", "{}")
        return when (o.str("kind")) {
            "start" -> Action.Start(
                projectPath = o.str("projectPath").orEmpty(),
                prompt = o.str("prompt").orEmpty(),
                providerId = o.str("providerId") ?: "anthropic",
                model = o.str("model")?.takeIf { it.isNotBlank() },
                permissionMode = o.str("permissionMode") ?: AutoMode.ALLOWED,
                allowedTools = o.arr("allowedTools").strings(),
                then = o.str("then") ?: Then.NONE,
            )
            "continue" -> Action.Continue(o.str("target") ?: Action.Continue.WORKING, o.str("prompt").orEmpty(), o.str("then") ?: Then.NONE, o.str("providerId"))
            "notify" -> Action.Notify(o.str("text").orEmpty(), o.bool("glasses"))
            "say" -> Action.Say(o.str("text").orEmpty())
            "ask" -> {
                val answers = o.arr("answers").strings().ifEmpty { WorkflowShape.ASK_DEFAULT_ANSWERS }
                Action.Ask(o.str("question").orEmpty(), answers, o.str("default")?.takeIf { it in answers } ?: answers.last(), o.int("timeoutMin") ?: WorkflowShape.ASK_TIMEOUT_MIN)
            }
            "shell" -> Action.Shell(o.str("projectPath").orEmpty(), o.str("command").orEmpty(), o.int("timeoutSec") ?: WorkflowShape.SHELL_TIMEOUT_SEC)
            else -> Action.Unknown(o.str("kind").orEmpty(), o.toString())
        }
    }

    fun run(o: JSONObject): Run? {
        val id = o.str("id") ?: return null
        val fired = o.obj("fired")
        return Run(
            id = id,
            automationId = o.str("automationId").orEmpty(),
            name = o.str("name").orEmpty(),
            firedBy = fired?.str("by") ?: "schedule",
            firedAt = fired?.long("at") ?: o.long("startedAt") ?: 0L,
            firedSessionId = fired?.str("sessionId"),
            state = o.str("state") ?: Run.DONE,
            reason = o.str("reason"),
            until = o.long("until"),
            sessionIds = o.arr("sessionIds").strings(),
            startedAt = o.long("startedAt"),
            endedAt = o.long("endedAt"),
            late = o.long("late") ?: 0L,
            missed = o.int("missed") ?: 0,
            lateWhy = o.str("lateWhy"),
            result = o.str("result")?.takeIf { it.isNotBlank() },
            quiet = o.bool("quiet"),
            skippedTools = o.arr("skippedTools").strings(),
            permissionMode = o.str("permissionMode"),
            retryOf = o.str("retryOf")?.takeIf { it.isNotBlank() },
            steps = o.arr("steps").mapObjects(WorkflowWire::runStep),
            step = o.int("step") ?: 0,
            ask = WorkflowWire.runAsk(o.obj("ask")),
            outputs = o.arr("outputs").mapObjects(WorkflowWire::runOutput),
            retryFrom = o.str("retryFrom")?.takeIf { it.isNotBlank() },
            budgetUsed = WorkflowWire.budgetUsed(o.obj("budgetUsed")),
            firedDetail = fired?.str("detail")?.takeIf { it.isNotBlank() },
        )
    }

    fun automation(o: JSONObject): Automation? {
        val id = o.str("id") ?: return null
        return Automation(
            id = id,
            name = o.str("name").orEmpty(),
            enabled = o.optBoolean("enabled", true),
            onlyOnce = o.bool("onlyOnce"),
            trigger = trigger(o.obj("trigger")),
            action = action(o.obj("action")),
            template = o.str("template")?.takeIf { it.isNotBlank() },
            createdAt = o.long("createdAt") ?: 0L,
            updatedAt = o.long("updatedAt") ?: 0L,
            nextAt = o.long("nextAt"),
            lastRun = o.obj("lastRun")?.let(::run),
            runCount = o.int("runCount") ?: 0,
            problem = o.str("problem")?.takeIf { it.isNotBlank() },
            paid = o.bool("paid"),
            steps = if (o.has("steps") && !o.isNull("steps")) o.arr("steps").mapObjects(WorkflowWire::step).takeIf { it.isNotEmpty() } else null,
            outputs = o.arr("outputs").mapObjects(WorkflowWire::output),
            budget = WorkflowWire.budget(o.obj("budget")),
        )
    }

    fun note(o: JSONObject): AutomationNote? {
        val id = o.str("id") ?: return null
        return AutomationNote(id, o.str("automationId"), o.str("runId"), o.str("name").orEmpty(), o.str("text").orEmpty(), o.str("to") ?: "phone", o.long("at") ?: 0L, WorkflowWire.runAsk(o.obj("ask")))
    }

    /** `Snapshot.automations` with `Snapshot.mac.tz`; null from a daemon without automations. */
    fun fromSnapshot(snapshot: JSONObject): AutomationsState? {
        val o = snapshot.obj("automations") ?: return null
        return AutomationsState(
            paused = o.bool("paused"),
            list = o.arr("list").mapObjects(::automation),
            notes = o.arr("notes").mapObjects(::note),
            glasses = o.bool("glasses"),
            tz = snapshot.obj("mac")?.str("tz")?.takeIf { it.isNotBlank() },
            hasBattery = if (o.has("hasBattery") && !o.isNull("hasBattery")) o.optBoolean("hasBattery") else null,
            features = WorkflowWire.features(o),
        )
    }

    /** `Session.automation`. */
    fun sessionAutomation(o: JSONObject?): SessionAutomation? {
        val id = o?.str("id") ?: return null
        return SessionAutomation(id, o.str("name").orEmpty(), o.str("runId"))
    }

    /** One of the socket's automation messages, or null for anything else. */
    fun message(o: JSONObject): AutoMsg? = when (o.str("t")) {
        "automation" -> o.obj("automation")?.let(::automation)?.let { AutoMsg.Changed(it) }
        "automation.removed" -> o.str("id")?.let { AutoMsg.Removed(it) }
        "automations" -> AutoMsg.Counts(o.bool("paused"))
        "run" -> o.obj("run")?.let(::run)?.let { AutoMsg.RunChanged(it) }
        "note" -> o.obj("note")?.let(::note)?.let { AutoMsg.Note(it) }
        else -> null
    }

    fun json(t: Trigger): JSONObject = when (t) {
        is Trigger.Time -> JSONObject().put("kind", "time").put("at", t.at).put("days", JSONArray(t.days))
        is Trigger.Once -> JSONObject().put("kind", "once").put("at", t.at)
        is Trigger.Every -> JSONObject().put("kind", "every").put("hours", t.hours).put("from", t.from).put("to", t.to ?: JSONObject.NULL).put("days", JSONArray(t.days))
        is Trigger.Reset -> JSONObject().put("kind", "reset").put("window", t.window).put("afterLimit", t.afterLimit)
            .put("from", t.from ?: JSONObject.NULL).put("to", t.to ?: JSONObject.NULL)
        is Trigger.Limit -> JSONObject().put("kind", "limit").put("window", t.window)
        // `after` always goes (null: at once), so the Mac never keeps one the owner took off.
        is Trigger.SessionEvent -> JSONObject().put("kind", "session").put("event", t.event).put("projectPath", t.projectPath ?: JSONObject.NULL).put("from", t.from)
            .put("after", t.after?.takeIf { t.event == Trigger.NEEDS_YOU } ?: JSONObject.NULL)
        Trigger.Plugged -> JSONObject().put("kind", "power").put("event", "plugged_in")
        is Trigger.BatteryLow -> JSONObject().put("kind", "power").put("event", "low").put("below", t.below)
        // The Mac makes and keeps its token: the kind is all a save says (its view fields are ignored).
        is Trigger.Webhook -> JSONObject().put("kind", "webhook")
        is Trigger.Cron -> JSONObject().put("kind", "cron").put("expr", t.expr.trim())
        is Trigger.FileWatch -> JSONObject().put("kind", "file").put("path", t.path).put("glob", t.glob?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL).put("event", t.event)
        is Trigger.Github -> JSONObject().put("kind", "github").put("repoPath", t.repoPath).put("events", JSONArray(t.events)).put("every", t.every)
        is Trigger.Hook -> JSONObject().put("kind", "hook").put("event", t.event).put("tool", t.tool?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("projectPath", t.projectPath ?: JSONObject.NULL).put("from", t.from)
        is Trigger.Unknown -> runCatching { JSONObject(t.json) }.getOrElse { JSONObject().put("kind", t.kind) }
    }

    fun json(a: Action): JSONObject = when (a) {
        is Action.Start -> JSONObject().put("kind", "start").put("projectPath", a.projectPath).put("prompt", a.prompt)
            .put("providerId", a.providerId).put("model", a.model ?: JSONObject.NULL).put("permissionMode", a.permissionMode)
            .put("allowedTools", JSONArray(a.allowedTools)).put("then", a.then)
        is Action.Continue -> JSONObject().put("kind", "continue").put("target", a.target).put("prompt", a.prompt).put("then", a.then)
            .put("providerId", a.providerId ?: JSONObject.NULL)
        is Action.Notify -> JSONObject().put("kind", "notify").put("text", a.text).put("glasses", a.glasses)
        is Action.Say -> JSONObject().put("kind", "say").put("text", a.text)
        is Action.Ask -> JSONObject().put("kind", "ask").put("question", a.question).put("answers", JSONArray(a.answers)).put("default", a.default).put("timeoutMin", a.timeoutMin)
        is Action.Shell -> JSONObject().put("kind", "shell").put("projectPath", a.projectPath).put("command", a.command).put("timeoutSec", a.timeoutSec)
        is Action.Unknown -> runCatching { JSONObject(a.json) }.getOrElse { JSONObject().put("kind", a.kind) }
    }

    /** An [AutomationInput] back from [draft] (the editor keeps its draft this way across a rotation). */
    fun input(o: JSONObject): AutomationInput {
        val steps = if (o.has("steps") && !o.isNull("steps")) o.arr("steps").mapObjects(WorkflowWire::step) else null
        return AutomationInput(
            name = o.str("name").orEmpty(),
            enabled = o.optBoolean("enabled", true),
            onlyOnce = o.bool("onlyOnce"),
            // The draft keeps a webhook trigger's view (its address, its command), which a save doesn't send.
            trigger = o.obj("triggerView")?.let(::trigger) ?: trigger(o.obj("trigger")),
            action = o.obj("action")?.let(::action) ?: steps?.firstOrNull()?.action ?: Action.Unknown("", "{}"),
            template = o.str("template")?.takeIf { it.isNotBlank() },
            steps = steps,
            outputs = o.arr("outputs").mapObjects(WorkflowWire::output),
            budget = WorkflowWire.budget(o.obj("budget")),
        )
    }

    /** The editor's draft as JSON for a rotation: as a save, with the trigger's view kept and no secret ([input] reads it). */
    fun draft(i: AutomationInput): JSONObject = json(i, secrets = false).put("name", i.name).apply {
        if (i.trigger is Trigger.Webhook) put("triggerView", JSONObject().put("kind", "webhook").put("last4", i.trigger.last4).put("createdAt", i.trigger.createdAt).put("url", i.trigger.url).put("command", i.trigger.command))
        if (i.steps != null) put("action", json(i.action))
    }

    /**
     * A save's body. With [AutomationInput.steps], the new shape (steps, outputs, budget; no
     * action); without, today's action and its `then`. [secrets]: a webhook output's typed secret.
     */
    fun json(i: AutomationInput, secrets: Boolean = true): JSONObject = JSONObject()
        .put("name", i.name.trim())
        .put("enabled", i.enabled)
        .put("onlyOnce", i.onlyOnce)
        .put("trigger", json(i.trigger))
        .put("template", i.template ?: JSONObject.NULL)
        .apply {
            val steps = i.steps
            if (steps == null) put("action", json(i.action))
            else {
                put("steps", JSONArray(steps.map(WorkflowWire::json)))
                put("outputs", JSONArray(i.outputs.map { WorkflowWire.json(it, secrets) }))
                put("budget", i.budget?.takeIf { it.any }?.let(WorkflowWire::json) ?: JSONObject.NULL)
            }
            if (i.confirmBypass) put("confirmBypass", true)
            if (i.confirmPaid) put("confirmPaid", true)
            if (i.confirmShell) put("confirmShell", true)
        }
}
