package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/*
 * Workflows (docs/PROTOCOL.md, "Automations", "Workflows"; docs/specs/workflows.md): an automation
 * with a trigger, 1 to 5 steps in order (each with at most one guard), 0 to 2 outputs and a
 * budget. The same automations, in the same routes; this file holds the shapes the phone adds and
 * the rules for reading an automation from a daemon with or without them.
 */

/** What a daemon can do of workflows (`Automations.features`); an editor offers only these. */
object Features {
    const val STEPS = "steps"
    const val ASK = "ask"
    const val SHELL = "shell"
    const val BUDGET = "budget"
    /** `{step:<id>}` in a text, `HYPERNOVA_STEP_<id>` for a command: any earlier step's result. */
    const val STEP_OUTPUT = "step.output"
    const val T_WEBHOOK = "trigger.webhook"
    const val T_CRON = "trigger.cron"
    const val T_FILE = "trigger.file"
    const val T_GITHUB = "trigger.github"
    const val T_HOOK = "trigger.hook"
    /** The session trigger's `after`: only if it still needs you after a while. */
    const val T_AFTER = "trigger.after"
    const val O_WEBHOOK = "output.webhook"
    const val O_FILE = "output.file"
    val ALL = setOf(STEPS, ASK, SHELL, BUDGET, STEP_OUTPUT, T_WEBHOOK, T_CRON, T_FILE, T_GITHUB, T_HOOK, T_AFTER, O_WEBHOOK, O_FILE)
}

/**
 * A step's condition, about the step before it (the nearest earlier one that ran and isn't a
 * notify or say): [on] is one of [ALL]; [text] for [CONTAINS] (plain text, any case) and [ANSWER]
 * (one of the nearest earlier ask's answers).
 */
@Immutable
data class Guard(val on: String, val text: String? = null) {
    companion object {
        const val OK = "ok"
        const val FAILED = "failed"
        const val QUIET = "quiet"
        const val NOT_QUIET = "not_quiet"
        const val CONTAINS = "contains"
        const val ANSWER = "answer"
        val ALL = listOf(OK, FAILED, QUIET, NOT_QUIET, CONTAINS, ANSWER)
        /** The guards that need [text]. */
        val WITH_TEXT = setOf(CONTAINS, ANSWER)
    }
}

/**
 * One step of a workflow: [id] stays across edits so runs can name it; [guard] is null on the first
 * step; [action] is what it does (a start's or continue's `then` means nothing here: the outputs
 * say where the result goes).
 */
@Immutable
data class Step(val id: String, val guard: Guard?, val action: Action) {
    val kind: String get() = kindOf(action)

    companion object {
        const val START = "start"
        const val CONTINUE = "continue"
        const val ASK = "ask"
        const val SHELL = "shell"
        const val NOTIFY = "notify"

        fun kindOf(a: Action): String = when (a) {
            is Action.Start -> START
            is Action.Continue -> CONTINUE
            is Action.Ask -> ASK
            is Action.Shell -> SHELL
            is Action.Notify -> NOTIFY
            is Action.Unknown -> a.kind
        }

        /** Notify only tells: guards and `{prev}` look past it. */
        fun tells(a: Action): Boolean = a is Action.Notify
    }
}

/** Where a run's result goes when it ends done and isn't quiet. [text] null: the run's result. */
sealed interface Output {
    val id: String
    val text: String?

    @Immutable data class Notify(override val id: String, override val text: String? = null) : Output

    /**
     * A POST to Slack, Discord, Telegram, ntfy or any https address ([preset]). [host] and [last4] are
     * what the Mac says of the secret it keeps; [url], [botToken] and [chatId] only a secret typed
     * in this draft, sent once and never shown back.
     */
    @Immutable data class Webhook(
        override val id: String,
        override val text: String? = null,
        val preset: String,
        val host: String = "",
        val last4: String = "",
        val url: String? = null,
        val botToken: String? = null,
        val chatId: String? = null,
    ) : Output {
        /** The Mac keeps a secret for it already. */
        val kept: Boolean get() = last4.isNotEmpty()
        /** A new secret was typed here. */
        val typed: Boolean get() = !url.isNullOrBlank() || !botToken.isNullOrBlank() || !chatId.isNullOrBlank()
    }

    /** Appends one Markdown line to [path], a file inside the projects folder. */
    @Immutable data class File(override val id: String, override val text: String? = null, val path: String) : Output

    /** A kind this app doesn't know: kept as the Mac sent it. */
    @Immutable data class Unknown(override val id: String, val kind: String, val json: String) : Output {
        override val text: String? get() = null
    }

    companion object {
        const val NTFY = "ntfy"
        const val SLACK = "slack"
        const val DISCORD = "discord"
        const val TELEGRAM = "telegram"
        const val POST = "post"
        val PRESETS = listOf(NTFY, SLACK, DISCORD, TELEGRAM, POST)
    }
}

/** A run's budget: at least one set. [minutes] at work (1–720); [windowPct] points of the 5-hour window (1–100). */
@Immutable
data class Budget(val minutes: Int?, val windowPct: Int?) {
    val any: Boolean get() = minutes != null || windowPct != null
}

/** An ask step's answer, and who gave it ("phone", "mac", or "timeout": its default). */
@Immutable
data class StepAnswer(val text: String, val by: String, val at: Long) {
    companion object {
        const val TIMEOUT = "timeout"
    }
}

/** One step of a run: what came of it. */
@Immutable
data class RunStep(
    val id: String,
    val kind: String,
    /** [PENDING], or one of Run's states (running, needs_you, waiting, done, failed, stopped, skipped). */
    val state: String,
    /** Run's reasons, and for a skipped step [GUARD], [FAILED_BEFORE] or [NOT_REACHED]. */
    val reason: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    /** Its output for the row (a notify's: its text). */
    val result: String? = null,
    val quiet: Boolean = false,
    val sessionIds: List<String> = emptyList(),
    /** A shell step's exit code. */
    val exit: Int? = null,
    val answer: StepAnswer? = null,
    /** Retry from a later step: taken over from the run it retries. */
    val reused: Boolean = false,
) {
    val active: Boolean get() = state == Run.RUNNING || state == Run.NEEDS_YOU || state == Run.WAITING

    companion object {
        const val PENDING = "pending"
        const val GUARD = "guard"
        const val FAILED_BEFORE = "failed_before"
        const val NOT_REACHED = "not_reached"
    }
}

/** The question a run waits on: [until] is when [default] is taken. */
@Immutable
data class RunAsk(val stepId: String, val question: String, val answers: List<String>, val default: String, val until: Long)

/**
 * What became of an output once its run ended: "pending" (a webhook being sent, or waiting 30 s to
 * be tried again, [error] its first error), "sent", "failed" or "skipped".
 */
@Immutable
data class RunOutput(val id: String, val kind: String, val state: String, val error: String? = null, val at: Long? = null) {
    companion object {
        const val PENDING = "pending"
        const val SENT = "sent"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
    }
}

/** A run's budget used so far. */
@Immutable
data class BudgetUsed(val minutes: Double, val windowPct: Double?)

/** `POST /v1/automations/check`: the trigger as it would be kept, and its next three due times. */
@Immutable
data class TriggerCheck(val trigger: Trigger, val next: List<Long>)

/**
 * The contract's limits (daemon/src/automations/contract.ts), and its rules for an automation that
 * came without steps (an older daemon, or a run kept from before them): one step, its action, with
 * outputs its `then` says. Pure, so the unit test pins them.
 */
object WorkflowShape {
    const val FIRST_STEP = "st_0"
    const val FIRST_OUTPUT = "ou_0"
    const val MAX_STEPS = 5
    const val MAX_OUTPUTS = 2
    const val ASK_QUESTION_MAX = 200
    const val ASK_ANSWER_MAX = 24
    val ASK_DEFAULT_ANSWERS = listOf("Approve", "Skip")
    const val ASK_TIMEOUT_MIN = 10
    const val ASK_TIMEOUT_MAX = 60
    const val SHELL_COMMAND_MAX = 2000
    const val SHELL_TIMEOUT_SEC = 60
    const val SHELL_TIMEOUT_MAX = 600
    const val GUARD_TEXT_MAX = 200
    const val OUTPUT_TEXT_MAX = 500
    const val BUDGET_MINUTES_MAX = 720
    /** The session trigger's `after`, in minutes. */
    const val AFTER_MAX = 120
    const val GLOB_MAX = 200
    val GITHUB_EVERY = listOf(5, 10, 15)
    private val ID = Regex("^[A-Za-z0-9_-]{1,24}$")

    fun validId(id: String): Boolean = ID.matches(id)

    /** A fresh id ("st_" or "ou_" and 8 hex) that isn't in [taken]. */
    fun newId(prefix: String, taken: Collection<String>, random: () -> Long = { kotlin.random.Random.nextLong() }): String {
        while (true) {
            val id = prefix + java.lang.Long.toHexString(random()).padStart(16, '0').takeLast(8)
            if (id !in taken) return id
        }
    }

    /** `then` as outputs: "notify" a plain notify, "none" nothing. */
    fun outputsOfThen(then: String): List<Output> = when (then) {
        Then.NOTIFY -> listOf(Output.Notify(FIRST_OUTPUT, null))
        else -> emptyList()
    }

    /** An old client's action as one step (with [id]) and the outputs its `then` says. */
    fun stepOfAction(a: Action, id: String = FIRST_STEP): Pair<Step, List<Output>> = when (a) {
        is Action.Start -> Step(id, null, a.copy(then = Then.NONE)) to outputsOfThen(a.then)
        is Action.Continue -> Step(id, null, a.copy(then = Then.NONE)) to outputsOfThen(a.then)
        else -> Step(id, null, a) to emptyList()
    }

    /** The `then` that says [outputs], or null when it can't (more than one, a webhook, a file, a text of its own). */
    fun thenOf(outputs: List<Output>): String? {
        if (outputs.isEmpty()) return Then.NONE
        val o = outputs[0]
        if (outputs.size > 1 || o.text != null) return null
        return when {
            o is Output.Notify -> Then.NOTIFY
            else -> null
        }
    }

    /**
     * The action an old client reads ([mirrorAction] in the contract): the one step as an Action
     * when it shows the automation exactly (start, continue but not `step:…`, notify; no
     * guard; outputs `then` can say, none at all for notify); null when it can't, the
     * daemon's `{kind: "workflow"}`. The phone also uses it to say a simple one as today's sentence.
     */
    fun mirror(steps: List<Step>, outputs: List<Output>): Action? {
        val s = steps.singleOrNull() ?: return null
        if (s.guard != null) return null
        val then = thenOf(outputs)
        return when (val a = s.action) {
            is Action.Start -> then?.let { a.copy(then = it) }
            is Action.Continue -> if (then == null || a.target.startsWith(Action.Continue.STEP)) null else a.copy(then = then)
            is Action.Notify -> a.takeIf { outputs.isEmpty() }
            else -> null
        }
    }

    /** An automation's steps: as the Mac sent them, or (an older daemon) its one action. */
    fun stepsOf(a: Automation): List<Step> = a.steps ?: listOf(stepOfAction(a.action).first)

    /** An automation's outputs: as the Mac sent them, or (an older daemon) what its action's `then` says. */
    fun outputsOf(a: Automation): List<Output> = if (a.steps != null) a.outputs else stepOfAction(a.action).second

    /** It does more than today's one action can say: several steps, a guard, outputs `then` can't say. */
    fun isWorkflow(steps: List<Step>, outputs: List<Output>): Boolean = mirror(steps, outputs) == null

    /** The step before [index]: the nearest earlier one that isn't a notify or say (it may not run). */
    fun before(steps: List<Step>, index: Int): Step? = steps.take(index).lastOrNull { !Step.tells(it.action) }

    /** `{step:<id>}`: step [id]'s result in a text (quoted in a prompt, as `{prev}` is). */
    fun stepRef(id: String): String = "{step:$id}"

    /** A command's way to step [id]'s whole result: its environment variable, quoted ("-" in an id reads "_"). */
    fun stepEnv(id: String): String = "\"\$HYPERNOVA_STEP_${id.replace('-', '_')}\""

    private val STEP_REF = Regex("\\{step:([A-Za-z0-9_-]{1,24})\\}")

    /** The step ids [text] names as `{step:<id>}`, in order, each once. */
    fun stepRefs(text: String): List<String> = STEP_REF.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** The earlier steps (before [index]; all of them for an output) a `{step:<id>}` may name: the ones that give a result. */
    fun resultSteps(steps: List<Step>, index: Int): List<Step> = steps.take(index.coerceIn(0, steps.size)).filter { !Step.tells(it.action) && it.action !is Action.Unknown }

    /** The nearest ask before [index], whose answers an `answer` guard names. */
    fun askBefore(steps: List<Step>, index: Int): Action.Ask? = steps.take(index).lastOrNull { it.action is Action.Ask }?.action as? Action.Ask
}

/** The editor's draft in the new shape: its action as one step, its `then` as outputs (nothing changes for a draft that has steps). */
fun AutomationInput.flow(): AutomationInput {
    if (steps != null) return this
    val (step, outs) = WorkflowShape.stepOfAction(action)
    return copy(steps = listOf(step), outputs = outs)
}

/** The steps a draft runs, in either shape. */
val AutomationInput.flowSteps: List<Step> get() = steps ?: listOf(WorkflowShape.stepOfAction(action).first)

/** The wire format of workflows' parts, parsed leniently: unknown kinds are kept, never fatal. */
object WorkflowWire {
    fun guard(o: JSONObject?): Guard? {
        val on = o?.str("on") ?: return null
        return Guard(on, o.str("text"))
    }

    fun json(g: Guard): JSONObject = JSONObject().put("on", g.on).apply { if (g.text != null) put("text", g.text) }

    fun step(o: JSONObject): Step? {
        val id = o.str("id") ?: return null
        return Step(id, guard(o.obj("if")), AutomationWire.action(o))
    }

    /** A step as sent: its action's fields without `then`, its id and guard. */
    fun json(s: Step): JSONObject = AutomationWire.json(s.action).apply {
        remove("then")
        put("id", s.id)
        put("if", s.guard?.let(::json) ?: JSONObject.NULL)
    }

    fun output(o: JSONObject): Output? {
        val id = o.str("id") ?: return null
        val text = o.str("text")?.takeIf { it.isNotEmpty() }
        return when (val kind = o.str("kind").orEmpty()) {
            "notify" -> Output.Notify(id, text)
            "webhook" -> Output.Webhook(id, text, o.str("preset") ?: Output.POST, o.str("host").orEmpty(), o.str("last4").orEmpty())
            "file" -> Output.File(id, text, o.str("path").orEmpty())
            else -> Output.Unknown(id, kind, o.toString())
        }
    }

    /**
     * An output as sent. A webhook carries its secret only when one was typed ([secrets]: false for
     * the editor's saved draft, which never keeps one); its host and last4 are the Mac's, not sent.
     */
    fun json(out: Output, secrets: Boolean = true): JSONObject = when (out) {
        is Output.Notify -> JSONObject().put("kind", "notify")
        is Output.Webhook -> JSONObject().put("kind", "webhook").put("preset", out.preset).apply {
            if (!secrets) {
                // The draft's own copy: what the card shows, never the secret.
                put("host", out.host).put("last4", out.last4)
            } else if (out.preset == Output.TELEGRAM) {
                out.botToken?.trim()?.takeIf { it.isNotEmpty() }?.let { put("botToken", it) }
                out.chatId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("chatId", it) }
            } else {
                out.url?.trim()?.takeIf { it.isNotEmpty() }?.let { put("url", it) }
            }
        }
        is Output.File -> JSONObject().put("kind", "file").put("path", out.path)
        is Output.Unknown -> runCatching { JSONObject(out.json) }.getOrElse { JSONObject().put("kind", out.kind) }
    }.apply {
        put("id", out.id)
        if (out !is Output.Unknown) put("text", out.text ?: JSONObject.NULL)
    }

    fun budget(o: JSONObject?): Budget? {
        if (o == null) return null
        val b = Budget(o.int("minutes"), o.int("windowPct"))
        return b.takeIf { it.any }
    }

    fun json(b: Budget): JSONObject = JSONObject().put("minutes", b.minutes ?: JSONObject.NULL).put("windowPct", b.windowPct ?: JSONObject.NULL)

    fun runStep(o: JSONObject): RunStep? {
        val id = o.str("id") ?: return null
        val a = o.obj("answer")
        return RunStep(
            id = id,
            kind = o.str("kind").orEmpty(),
            state = o.str("state") ?: RunStep.PENDING,
            reason = o.str("reason"),
            startedAt = o.long("startedAt"),
            endedAt = o.long("endedAt"),
            result = o.str("result")?.takeIf { it.isNotBlank() },
            quiet = o.bool("quiet"),
            sessionIds = o.arr("sessionIds").strings(),
            exit = o.int("exit"),
            answer = a?.str("text")?.let { StepAnswer(it, a.str("by") ?: "phone", a.long("at") ?: 0L) },
            reused = o.bool("reused"),
        )
    }

    fun json(s: RunStep): JSONObject = JSONObject().put("id", s.id).put("kind", s.kind).put("state", s.state).put("reason", s.reason ?: JSONObject.NULL)
        .put("startedAt", s.startedAt ?: JSONObject.NULL).put("endedAt", s.endedAt ?: JSONObject.NULL).put("result", s.result ?: JSONObject.NULL)
        .put("quiet", s.quiet).put("sessionIds", JSONArray(s.sessionIds)).put("exit", s.exit ?: JSONObject.NULL)
        .put("answer", s.answer?.let { JSONObject().put("text", it.text).put("by", it.by).put("at", it.at) } ?: JSONObject.NULL)
        .put("reused", s.reused)

    fun runAsk(o: JSONObject?): RunAsk? {
        if (o == null) return null
        val step = o.str("stepId") ?: return null
        val answers = o.arr("answers").strings().ifEmpty { WorkflowShape.ASK_DEFAULT_ANSWERS }
        return RunAsk(step, o.str("question").orEmpty(), answers, o.str("default") ?: answers.last(), o.long("until") ?: 0L)
    }

    fun json(a: RunAsk): JSONObject = JSONObject().put("stepId", a.stepId).put("question", a.question).put("answers", JSONArray(a.answers)).put("default", a.default).put("until", a.until)

    fun runOutput(o: JSONObject): RunOutput? {
        val id = o.str("id") ?: return null
        return RunOutput(id, o.str("kind").orEmpty(), o.str("state") ?: RunOutput.SKIPPED, o.str("error")?.takeIf { it.isNotBlank() }, o.long("at"))
    }

    fun budgetUsed(o: JSONObject?): BudgetUsed? {
        if (o == null) return null
        val m = o.optDouble("minutes").takeIf { !it.isNaN() } ?: 0.0
        val p = if (o.has("windowPct") && !o.isNull("windowPct")) o.optDouble("windowPct").takeIf { !it.isNaN() } else null
        return BudgetUsed(m, p)
    }

    fun features(o: JSONObject): Set<String>? = if (o.has("features") && !o.isNull("features")) o.arr("features").strings().toSet() else null
}
