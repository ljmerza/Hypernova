package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutomationErrors
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.WorkflowShape
import java.util.Locale

/**
 * Where an error belongs in the workflow editor: the daemon's `field` ("steps[2].command",
 * "trigger.expr", "outputs[0].url") as the block it marks ([key]: "name", "when", "steps[2]",
 * "outputs[0]", "budget", or [OTHER]) and the field inside it ([sub]: "command", "if", "url"). Pure.
 */
object FieldPath {
    const val NAME = "name"
    const val WHEN = "when"
    const val BUDGET = "budget"
    const val OTHER = "other"

    fun step(i: Int) = "steps[$i]"
    fun output(i: Int) = "outputs[$i]"

    private val INDEXED = Regex("^(steps|outputs)\\[(\\d+)\\](?:\\.(.+))?$")

    /** The block [field] marks; with no field, the block [code] belongs to (an older daemon's refusal). */
    fun key(field: String?, code: String? = null): String {
        if (field.isNullOrBlank()) return when (AutomationErrors.block(code)) {
            AutomationErrors.Block.Name -> NAME
            AutomationErrors.Block.When -> WHEN
            AutomationErrors.Block.Do, AutomationErrors.Block.Permissions -> step(0)
            AutomationErrors.Block.Other -> OTHER
        }
        val f = field.trim()
        INDEXED.find(f)?.let { return "${it.groupValues[1]}[${it.groupValues[2]}]" }
        val head = f.substringBefore('.').substringBefore('[')
        return when (head) {
            "name" -> NAME
            "trigger" -> WHEN
            "budget" -> BUDGET
            // An old client's save names the action: the one step.
            "action" -> step(0)
            // "steps" or "outputs" alone: their count. The THEN or TELL seam says it.
            "steps" -> "steps"
            "outputs" -> "outputs"
            "template" -> NAME
            else -> OTHER
        }
    }

    /** The field inside the block: "command", "if", "url", "expr"; null for the block as a whole. */
    fun sub(field: String?): String? {
        if (field.isNullOrBlank()) return null
        val f = field.trim()
        INDEXED.find(f)?.let { m -> return m.groupValues[3].takeIf { it.isNotEmpty() }?.substringBefore('.')?.substringBefore('[') }
        val rest = f.substringAfter('.', "")
        return rest.takeIf { it.isNotEmpty() }?.substringBefore('.')?.substringBefore('[')
    }

    /** "steps[3]" → 3; null for any other key. */
    fun index(key: String): Int? = Regex("^(?:steps|outputs)\\[(\\d+)\\]$").find(key)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * The placeholders a text may hold where it is (docs/PROTOCOL.md, "Workflows", Placeholders): what
 * the trigger fills, what the steps before it give, and only the safe ones in a shell command or a
 * No asking prompt. [insert] puts one at the cursor. Pure.
 */
object Placeholders {
    enum class Where { Prompt, Text, Question, Shell, Output }

    /** Words from another session or from outside Hypernova: quoted in prompts, never in No asking or a command. */
    val OUTSIDE = setOf("{session}", "{result}", "{ask}", "{prev}", "{output}", "{payload}")

    /** [token] is outside words: one of [OUTSIDE], or an earlier step's result (`{step:<id>}`). */
    fun outside(token: String): Boolean = token in OUTSIDE || token.startsWith("{step:")
    /** The only ones a shell command may hold (filled as single-quoted shell words). */
    val SHELL = setOf("{project}", "{time}", "{battery}", "{exit}", "{answer}")
    /** What a shell step finds in its environment, never pasted into its command line. */
    val ENV = listOf("\"\$HYPERNOVA_PREV\"", "\"\$HYPERNOVA_PAYLOAD\"", "\"\$HYPERNOVA_OUTPUT\"", "\"\$HYPERNOVA_ANSWER\"")

    /**
     * What a text at step [index] of [steps] (or, [Where.Output], after them all) can say. [bypass]: a
     * start in No asking, which can't take another session's words. With [Features.STEP_OUTPUT] on
     * the Mac ([features]), each earlier step with a result as `{step:<id>}` ("Step 2's result").
     */
    fun offered(trigger: Trigger, steps: List<Step>, index: Int, where: Where, bypass: Boolean = false, features: Set<String>? = null): List<String> {
        val before = steps.take(index.coerceIn(0, steps.size))
        val out = mutableListOf("{project}")
        when (trigger) {
            is Trigger.SessionEvent -> { out += "{session}"; out += "{result}"; out += "{ask}" }
            is Trigger.Hook -> { out += "{session}"; out += "{result}" }
            is Trigger.BatteryLow, Trigger.Plugged -> out += "{battery}"
            is Trigger.Reset -> if (trigger.window == Trigger.SEVEN_DAY) out += "{week}"
            else -> Unit
        }
        if (trigger is Trigger.Webhook || trigger is Trigger.FileWatch || trigger is Trigger.Github || trigger is Trigger.Hook) out += "{payload}"
        if (before.any { !Step.tells(it.action) }) out += "{prev}"
        if (before.any { it.action is Action.Shell }) { out += "{exit}"; out += "{output}" }
        if (before.any { it.action is Action.Ask }) out += "{answer}"
        if (features?.contains(Features.STEP_OUTPUT) == true) out += WorkflowShape.resultSteps(steps, index).map { WorkflowShape.stepRef(it.id) }
        out += "{time}"
        return when {
            where == Where.Shell -> out.filter { it in SHELL }
            where == Where.Prompt && bypass -> out.filter { !outside(it) }
            else -> out
        }
    }

    /** A command's environment as chips: [ENV], and each earlier step's whole result with [Features.STEP_OUTPUT]. */
    fun env(steps: List<Step>, index: Int, features: Set<String>?): List<String> =
        ENV + if (features?.contains(Features.STEP_OUTPUT) == true) WorkflowShape.resultSteps(steps, index).map { WorkflowShape.stepEnv(it.id) } else emptyList()

    /** The placeholders written in [text] ("{prev}", "{step:st_1}", in order, each once). */
    fun used(text: String): List<String> = Regex("\\{[a-z]+\\}|\\{step:[A-Za-z0-9_-]{1,24}\\}").findAll(text).map { it.value }.distinct().toList()

    /** A `{step:<id>}` in [text] that names no step [ok] (the earlier ones with a result): the daemon refuses it. */
    fun badStep(text: String, ok: List<Step>): Boolean = WorkflowShape.stepRefs(text).any { id -> ok.none { it.id == id } }

    /**
     * [token] put over the selection [start]..[end] of [text], with a space before it when it would
     * touch a word: the new text and where the cursor goes (after it).
     */
    fun insert(text: String, start: Int, end: Int, token: String): Pair<String, Int> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(a, text.length)
        val before = text.substring(0, a)
        val after = text.substring(b)
        val spaced = (if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() != '(' && before.last() != '"') " " else "") + token
        return (before + spaced + after) to (before.length + spaced.length)
    }
}

/** A choice of the guard picker: null is "always". */
object GuardOptions {
    /**
     * The guards step [index] can have: none on the first step (or without "steps"); `answer` only
     * after an ask, once per answer; the rest always.
     */
    fun offered(features: Set<String>?, steps: List<Step>, index: Int): List<Guard?> {
        if (index <= 0 || features?.contains(Features.STEPS) != true) return emptyList()
        val out = mutableListOf<Guard?>(null, Guard(Guard.FAILED), Guard(Guard.OK), Guard(Guard.NOT_QUIET), Guard(Guard.QUIET), Guard(Guard.CONTAINS, ""))
        WorkflowShape.askBefore(steps, index)?.let { ask -> for (a in ask.answers) out += Guard(Guard.ANSWER, a) }
        return out
    }
}

/**
 * What keeps a workflow from saving, as the daemon would refuse it (its codes), by where the editor
 * shows it. Checked before Save so the owner sees it at once; the Mac's own refusal (with `field`)
 * lands in the same places.
 */
object WorkflowRules {
    /** A problem: [key] and [sub] as [FieldPath] says them, [code] the daemon's (or the phone's own: "project_needed", "text_needed", "url_needed"). */
    data class Issue(val key: String, val code: String, val sub: String? = null)

    private val HTTPS = Regex("^https://[^\\s/]+(/\\S*)?$", RegexOption.IGNORE_CASE)
    private val BOT_TOKEN = Regex("^\\d{5,}:[A-Za-z0-9_-]{20,}$")
    private val CHAT = Regex("^(-?\\d+|@[A-Za-z0-9_]{4,})$")

    fun check(d: AutomationInput, features: Set<String>?): List<Issue> {
        val out = mutableListOf<Issue>()
        if (d.name.isBlank()) out += Issue(FieldPath.NAME, "bad_name")
        when (val t = d.trigger) {
            is Trigger.Cron -> if (t.expr.isBlank()) out += Issue(FieldPath.WHEN, "bad_cron", "expr")
            is Trigger.FileWatch -> {
                if (t.path.isBlank()) out += Issue(FieldPath.WHEN, "project_needed", "path")
                // 200 characters and four "**" at most, as the Mac takes them.
                val glob = t.glob.orEmpty()
                if (glob.length > WorkflowShape.GLOB_MAX || glob.split("**").size - 1 > 4) out += Issue(FieldPath.WHEN, "bad_glob", "glob")
            }
            is Trigger.Github -> {
                if (t.repoPath.isBlank()) out += Issue(FieldPath.WHEN, "project_needed", "repoPath")
                if (t.events.isEmpty()) out += Issue(FieldPath.WHEN, "bad_trigger", "events")
            }
            else -> Unit
        }
        val steps = d.steps ?: return out
        if (steps.isEmpty() || steps.size > WorkflowShape.MAX_STEPS) out += Issue("steps", "bad_steps")
        for ((i, s) in steps.withIndex()) out += step(d, steps, i, s, features)
        if (d.outputs.size > WorkflowShape.MAX_OUTPUTS) out += Issue("outputs", "bad_outputs")
        for ((i, o) in d.outputs.withIndex()) {
            (output(i, o) ?: Issue(FieldPath.output(i), "bad_placeholder", "text").takeIf { Placeholders.badStep(o.text.orEmpty(), WorkflowShape.resultSteps(steps, steps.size)) })?.let { out += it }
        }
        d.budget?.let { b ->
            if ((b.minutes != null && b.minutes !in 1..WorkflowShape.BUDGET_MINUTES_MAX) || (b.windowPct != null && b.windowPct !in 1..100)) out += Issue(FieldPath.BUDGET, "bad_budget")
        }
        return out
    }

    private fun step(d: AutomationInput, steps: List<Step>, i: Int, s: Step, features: Set<String>?): List<Issue> {
        val key = FieldPath.step(i)
        val out = mutableListOf<Issue>()
        // The steps a {step:<id>} here may name: earlier ones with a result.
        val earlier = WorkflowShape.resultSteps(steps, i)
        val g = s.guard
        if (g != null) {
            when {
                i == 0 -> out += Issue(key, "bad_guard", "if")
                g.on == Guard.CONTAINS && g.text.isNullOrBlank() -> out += Issue(key, "guard_text", "if")
                g.on == Guard.ANSWER -> {
                    val ask = WorkflowShape.askBefore(steps, i)
                    if (ask == null || ask.answers.none { it.equals(g.text, ignoreCase = true) }) out += Issue(key, "bad_guard", "if")
                }
            }
        }
        when (val a = s.action) {
            is Action.Start -> {
                when {
                    a.projectPath.isBlank() -> out += Issue(key, "project_needed", "projectPath")
                    a.prompt.isBlank() || a.prompt.trim() == "/" -> out += Issue(key, "no_prompt", "prompt")
                    a.permissionMode == AutoMode.NO_ASKING && Placeholders.used(a.prompt).any(Placeholders::outside) -> out += Issue(key, "bad_placeholder", "prompt")
                    Placeholders.badStep(a.prompt, earlier) -> out += Issue(key, "bad_placeholder", "prompt")
                }
                a.allowedTools.indexOfFirst { Rules.check(it) != null }.takeIf { it >= 0 }?.let { k ->
                    out += Issue(key, Rules.check(a.allowedTools[k])!!, "allowedTools")
                }
            }
            is Action.Continue -> when {
                a.providerId != null && a.providerId.isBlank() -> out += Issue(key, "provider_needed", "providerId")
                a.target == Action.Continue.TRIGGER && d.trigger !is Trigger.SessionEvent && d.trigger !is Trigger.Hook -> out += Issue(key, "bad_target", "target")
                a.target.startsWith(Action.Continue.STEP) && steps.take(i).none { b -> b.id == a.target.removePrefix(Action.Continue.STEP) && (b.action is Action.Start || b.action is Action.Continue) } ->
                    out += Issue(key, "bad_target", "target")
                a.prompt.isBlank() && a.target != Action.Continue.LIMITED && a.providerId == null -> out += Issue(key, "no_prompt", "prompt")
                Placeholders.badStep(a.prompt, earlier) -> out += Issue(key, "bad_placeholder", "prompt")
            }
            is Action.Ask -> {
                val answers = a.answers.map { it.trim() }
                when {
                    a.question.isBlank() || a.question.length > WorkflowShape.ASK_QUESTION_MAX -> out += Issue(key, "bad_question", "question")
                    Placeholders.badStep(a.question, earlier) -> out += Issue(key, "bad_placeholder", "question")
                    answers.size !in 2..3 || answers.any { it.isEmpty() || it.length > WorkflowShape.ASK_ANSWER_MAX } ||
                        answers.map { it.lowercase(Locale.ROOT) }.distinct().size != answers.size -> out += Issue(key, "bad_answers", "answers")
                    answers.none { it == a.default.trim() } -> out += Issue(key, "bad_answers", "default")
                    a.timeoutMin !in 1..WorkflowShape.ASK_TIMEOUT_MAX -> out += Issue(key, "bad_timeout", "timeoutMin")
                }
                if (features?.contains(Features.ASK) != true) out += Issue(key, "bad_step")
            }
            is Action.Shell -> {
                when {
                    a.projectPath.isBlank() -> out += Issue(key, "project_needed", "projectPath")
                    a.command.isBlank() || a.command.length > WorkflowShape.SHELL_COMMAND_MAX -> out += Issue(key, "no_command", "command")
                    Placeholders.used(a.command).any { it !in Placeholders.SHELL } -> out += Issue(key, "bad_placeholder", "command")
                    a.timeoutSec !in 1..WorkflowShape.SHELL_TIMEOUT_MAX -> out += Issue(key, "bad_timeout", "timeoutSec")
                }
                if (features?.contains(Features.SHELL) != true) out += Issue(key, "bad_step")
            }
            is Action.Notify -> if (a.text.isBlank()) out += Issue(key, "text_needed", "text") else if (Placeholders.badStep(a.text, earlier)) out += Issue(key, "bad_placeholder", "text")
            is Action.Unknown -> Unit
        }
        return out
    }

    private fun output(i: Int, o: Output): Issue? {
        val key = FieldPath.output(i)
        if ((o.text?.length ?: 0) > WorkflowShape.OUTPUT_TEXT_MAX) return Issue(key, "bad_text", "text")
        return when (o) {
            is Output.Webhook -> when {
                o.preset == Output.TELEGRAM -> when {
                    !o.kept && o.botToken.isNullOrBlank() -> Issue(key, "url_needed", "botToken")
                    !o.botToken.isNullOrBlank() && !BOT_TOKEN.matches(o.botToken.trim()) -> Issue(key, "bad_bot_token", "botToken")
                    !o.kept && o.chatId.isNullOrBlank() -> Issue(key, "url_needed", "chatId")
                    !o.chatId.isNullOrBlank() && !CHAT.matches(o.chatId.trim()) -> Issue(key, "bad_chat", "chatId")
                    else -> null
                }
                !o.kept && o.url.isNullOrBlank() -> Issue(key, "url_needed", "url")
                o.url.isNullOrBlank() -> null
                o.url.trim().startsWith("http://", ignoreCase = true) -> Issue(key, "insecure_url", "url")
                !HTTPS.matches(o.url.trim()) || !presetHost(o.preset, o.url.trim()) -> Issue(key, "bad_url", "url")
                else -> null
            }
            is Output.File -> if (o.path.isBlank() || o.path.endsWith("/")) Issue(key, "bad_path", "path") else null
            else -> null
        }
    }

    /** The address a preset expects: Slack's and Discord's own; ntfy and a plain POST any https one. */
    fun presetHost(preset: String, url: String): Boolean {
        val host = url.removePrefix("https://").removePrefix("HTTPS://").substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
        return when (preset) {
            Output.SLACK -> host == "hooks.slack.com"
            Output.DISCORD -> host == "discord.com" || host == "discordapp.com" || host.endsWith(".discord.com")
            else -> true
        }
    }

    /** The first issue for each block, as the editor shows them (one line under each). */
    fun byKey(issues: List<Issue>): Map<String, Issue> = issues.groupBy { it.key }.mapValues { it.value.first() }
}
