package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.RunOutput
import com.prudnikweb.hypernova.data.RunStep
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.StepAnswer
import java.time.ZoneId

/**
 * Workflows in words: a step's guard ("only if the step before failed"), a step's short label for
 * the run's timeline, what came of each step and each output, what a placeholder is, and the
 * editor's problems. Pure (the unit test reads the real strings), never "·"-joined.
 */
object WorkflowWords {
    /** A guard as the line under its step says it; null ("as steps usually go") says the no-guard rule. */
    fun guard(w: Words, g: Guard?): String {
        if (g == null) return w.s(R.string.wf_g_none)
        return when (g.on) {
            Guard.OK -> w.s(R.string.wf_g_ok)
            Guard.FAILED -> w.s(R.string.wf_g_failed)
            Guard.QUIET -> w.s(R.string.wf_g_quiet)
            Guard.NOT_QUIET -> w.s(R.string.wf_g_not_quiet)
            Guard.CONTAINS -> g.text?.trim()?.takeIf { it.isNotEmpty() }?.let { w.s(R.string.wf_g_contains, it) } ?: w.s(R.string.wf_g_contains_any)
            Guard.ANSWER -> w.s(R.string.wf_g_answer, g.text.orEmpty())
            else -> w.s(R.string.wf_g_unknown)
        }
    }

    /** A step kind's title, as the tiles and the step rows say it. */
    fun kindTitle(w: Words, a: Action): String = w.s(
        when (a) {
            is Action.Start -> if (a.skill) R.string.au_d_skill else R.string.au_d_start
            is Action.Continue -> R.string.au_d_continue
            is Action.Ask -> R.string.wf_d_ask
            is Action.Shell -> R.string.wf_d_shell
            is Action.Notify -> R.string.au_d_notify
            is Action.Unknown -> R.string.wf_d_unknown
        },
    )

    /** A kind's title by name, for a run's step whose definition is gone. */
    fun kindTitle(w: Words, kind: String): String = w.s(
        when (kind) {
            Step.START -> R.string.au_d_start
            Step.CONTINUE -> R.string.au_d_continue
            Step.ASK -> R.string.wf_d_ask
            Step.SHELL -> R.string.wf_d_shell
            Step.NOTIFY -> R.string.au_d_notify
            else -> R.string.wf_d_unknown
        },
    )

    /**
     * What a step does, on one line: its prompt, question or command cut short; a notify its kind. Under its title on a step row, and as its name on a run's timeline.
     */
    fun detail(w: Words, a: Action, sessionName: (String) -> String? = { null }): String? = when (a) {
        is Action.Start -> Summary.cut(a.prompt).ifEmpty { null }
        is Action.Continue -> {
            val who = when {
                a.target == Action.Continue.TRIGGER -> w.s(R.string.au_t_trigger)
                a.target == Action.Continue.WORKING -> w.s(R.string.au_t_working)
                a.target == Action.Continue.LIMITED -> w.s(R.string.au_t_limited)
                a.target.startsWith(Action.Continue.STEP) -> w.s(R.string.wf_target_step_short)
                else -> sessionName(a.target) ?: w.s(R.string.au_sum_target_gone)
            }
            val p = Summary.cut(a.prompt)
            if (p.isEmpty()) who else w.s(R.string.wf_detail_continue, who, p)
        }
        is Action.Ask -> Summary.cut(a.question).ifEmpty { null }
        is Action.Shell -> a.command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        is Action.Notify -> Summary.cut(a.text).ifEmpty { null }
        is Action.Unknown -> null
    }

    /** A run's step on its timeline: its name (from the automation as it is now) or its kind's title. */
    fun stepName(w: Words, s: RunStep, def: Step?): String {
        val a = def?.action ?: return kindTitle(w, s.kind)
        return when (a) {
            is Action.Notify -> kindTitle(w, a)
            else -> detail(w, a)?.let { cutTo(it, 56) } ?: kindTitle(w, a)
        }
    }

    private fun cutTo(s: String, n: Int): String = if (s.length <= n) s else s.take(n).substringBeforeLast(' ').trimEnd(',', '.', ':', ';') + "…"

    /**
     * What came of a run's step, for its row on the timeline: running, waiting for an answer, an
     * answer and who gave it, a command's exit, a result, or why it didn't run. [late] carries
     * "taken over from the run it retried".
     */
    fun step(w: Words, s: RunStep, now: Long, codex: Boolean = false): RunLine {
        val took = RunWords.ranStep(s, now)?.let { RunWords.duration(w, it) }
        val line = when (s.state) {
            RunStep.PENDING -> RunLine(w.s(R.string.wf_rs_pending), RunTone.Pending)
            Run.RUNNING -> RunLine(took?.let { w.s(R.string.wf_rs_running_for, it) } ?: w.s(R.string.wf_rs_running), RunTone.Active)
            Run.WAITING -> RunLine(w.s(if (s.reason == "target_busy") R.string.au_run_waiting_busy else R.string.au_run_waiting_limit_plain), RunTone.Active)
            Run.NEEDS_YOU -> RunLine(w.s(if (s.kind == Step.ASK) R.string.wf_rs_asking else R.string.au_state_needs), RunTone.Alert)
            Run.DONE -> when {
                s.answer != null -> RunLine(answered(w, s.answer), RunTone.Ok)
                s.kind == Step.SHELL -> RunLine(s.result?.let(::lastLine)?.let { w.s(R.string.wf_rs_exit_said, s.exit ?: 0, it) } ?: w.s(R.string.wf_rs_exit, s.exit ?: 0), if (s.quiet) RunTone.Quiet else RunTone.Ok)
                s.quiet -> RunLine(w.s(R.string.au_run_nothing_new), RunTone.Quiet)
                s.kind == Step.NOTIFY -> RunLine(w.s(R.string.wf_rs_told), RunTone.Ok)
                else -> RunLine(s.result ?: w.s(R.string.au_run_done), RunTone.Ok)
            }
            Run.FAILED -> when {
                s.reason == "exit" || (s.kind == Step.SHELL && s.exit != null && s.exit != 0) ->
                    RunLine(s.result?.let(::lastLine)?.let { w.s(R.string.wf_rs_exit_said, s.exit ?: 1, it) } ?: w.s(R.string.wf_rs_exit, s.exit ?: 1), RunTone.Failed)
                s.reason == "timeout" && s.kind == Step.SHELL -> RunLine(w.s(R.string.wf_rs_shell_timeout), RunTone.Failed)
                else -> reasonLine(w, s, now, codex)
            }
            // A command the daemon lost to its own restart, not a session that ended.
            Run.STOPPED -> if (s.kind == Step.SHELL && s.reason == "ended") RunLine(w.s(R.string.wf_rs_shell_lost), RunTone.Stopped) else reasonLine(w, s, now, codex)
            Run.SKIPPED -> when (s.reason) {
                RunStep.GUARD -> RunLine(w.s(R.string.wf_rs_guard), RunTone.Skipped)
                RunStep.FAILED_BEFORE -> RunLine(w.s(R.string.wf_rs_failed_before), RunTone.Skipped)
                RunStep.NOT_REACHED -> RunLine(w.s(R.string.wf_rs_not_reached), RunTone.Skipped)
                else -> reasonLine(w, s, now, codex)
            }
            else -> RunLine(s.state, RunTone.Quiet)
        }
        return if (s.reused) line.copy(late = w.s(R.string.wf_rs_reused)) else line
    }

    /** A step that stopped or failed for one of the run's reasons, in the run line's words. */
    private fun reasonLine(w: Words, s: RunStep, now: Long, codex: Boolean = false): RunLine {
        val r = Run(
            id = s.id, automationId = "", name = "", firedBy = "", firedAt = s.startedAt ?: now, firedSessionId = null,
            state = s.state, reason = s.reason, until = null, sessionIds = s.sessionIds, startedAt = s.startedAt, endedAt = s.endedAt, result = s.result,
        )
        return RunWords.line(w, r, now, ZoneId.systemDefault(), codex = codex)
    }

    /** "Approve", "Approve on the Mac", "Nobody answered: taken as Skip". */
    fun answered(w: Words, a: StepAnswer): String = when (a.by) {
        StepAnswer.TIMEOUT -> w.s(R.string.wf_rs_answer_timeout, a.text)
        "mac" -> w.s(R.string.wf_rs_answer_mac, a.text)
        else -> w.s(R.string.wf_rs_answer, a.text)
    }

    private fun lastLine(text: String): String? = text.trimEnd().lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.let { cutTo(it, 80) }

    /** An output's name on its row and in the run's TOLD lines: "Notify me", "Slack", "briefs.md". */
    fun outputName(w: Words, o: Output?, kind: String): String = when (o) {
        is Output.Notify -> w.s(R.string.wf_o_notify)
        is Output.Webhook -> Summary.presetName(w, o.preset).replaceFirstChar { it.uppercaseChar() }
        is Output.File -> o.path.trimEnd('/').substringAfterLast('/').ifEmpty { w.s(R.string.wf_o_file) }
        is Output.Unknown -> o.kind
        null -> w.s(
            when (kind) {
                "notify" -> R.string.wf_o_notify
                "webhook" -> R.string.wf_o_webhook
                "file" -> R.string.wf_o_file
                else -> R.string.wf_d_unknown
            },
        )
    }

    /** What became of an output: sent, didn't go (and why), or not sent (the run didn't end done, or was quiet). */
    fun output(w: Words, o: RunOutput): RunLine = when (o.state) {
        RunOutput.PENDING -> RunLine(o.error?.let { w.s(R.string.wf_ro_retrying, it) } ?: w.s(R.string.wf_ro_sending), RunTone.Active)
        RunOutput.SENT -> RunLine(w.s(R.string.wf_ro_sent), RunTone.Ok)
        RunOutput.FAILED -> RunLine(o.error?.let { w.s(R.string.wf_ro_failed_said, it) } ?: w.s(R.string.wf_ro_failed), RunTone.Failed)
        else -> RunLine(w.s(R.string.wf_ro_skipped), RunTone.Skipped)
    }

    /**
     * One line on what a placeholder fills in with. [step]: the number of the step a `{step:<id>}`
     * or `"$HYPERNOVA_STEP_<id>"` names (null: none before this one any more).
     */
    fun placeholder(w: Words, token: String, step: Int? = null): String = when {
        token.startsWith("{step:") -> step?.let { w.s(R.string.wf_ph_step, it) } ?: w.s(R.string.wf_ph_step_gone)
        token.startsWith("\"\$HYPERNOVA_STEP_") -> step?.let { w.s(R.string.wf_env_step, it) } ?: w.s(R.string.wf_ph_step_gone)
        else -> plain(w, token)
    }

    /** A chip's own words: "Step 2's result" for an earlier step's, the placeholder itself otherwise. */
    fun chip(w: Words, token: String, step: Int?): String =
        if (step != null && (token.startsWith("{step:") || token.startsWith("\"\$HYPERNOVA_STEP_"))) w.s(R.string.wf_step_result, step) else token.removeSurrounding("\"")

    private fun plain(w: Words, token: String): String = w.s(
        when (token) {
            "{project}" -> R.string.wf_ph_project
            "{session}" -> R.string.wf_ph_session
            "{result}" -> R.string.wf_ph_result
            "{ask}" -> R.string.wf_ph_ask
            "{battery}" -> R.string.wf_ph_battery
            "{time}" -> R.string.wf_ph_time
            "{week}" -> R.string.wf_ph_week
            "{prev}" -> R.string.wf_ph_prev
            "{exit}" -> R.string.wf_ph_exit
            "{output}" -> R.string.wf_ph_output
            "{answer}" -> R.string.wf_ph_answer
            "{payload}" -> R.string.wf_ph_payload
            "\"\$HYPERNOVA_PREV\"" -> R.string.wf_env_prev
            "\"\$HYPERNOVA_PAYLOAD\"" -> R.string.wf_env_payload
            "\"\$HYPERNOVA_OUTPUT\"" -> R.string.wf_env_output
            "\"\$HYPERNOVA_ANSWER\"" -> R.string.wf_env_answer
            else -> R.string.wf_ph_other
        },
    )

    /** An editor problem in words: the daemon's code, or the phone's own ("project_needed"). */
    fun issue(w: Words, code: String): String = w.s(
        when (code) {
            "bad_name" -> R.string.au_err_name
            "project_needed" -> R.string.au_err_project_needed
            "no_prompt" -> R.string.au_err_prompt
            "text_needed" -> R.string.au_err_text
            "provider_needed" -> R.string.au_err_provider_needed
            "bad_target" -> R.string.au_err_target
            "bad_placeholder" -> R.string.wf_err_placeholder
            "bad_rule" -> R.string.au_err_rule
            "rule_too_broad" -> R.string.au_err_rule_broad
            "bad_guard" -> R.string.wf_err_guard
            "guard_text" -> R.string.wf_err_guard_text
            "bad_question" -> R.string.wf_err_question
            "bad_answers" -> R.string.wf_err_answers
            "bad_timeout" -> R.string.wf_err_timeout
            "no_command" -> R.string.wf_err_command
            "bad_step" -> R.string.wf_err_step
            "bad_steps" -> R.string.wf_err_steps
            "bad_outputs" -> R.string.wf_err_outputs
            "bad_text" -> R.string.wf_err_text
            "url_needed" -> R.string.wf_err_url_needed
            "bad_url" -> R.string.wf_err_url
            "insecure_url" -> R.string.wf_err_insecure
            "bad_bot_token" -> R.string.wf_err_bot_token
            "bad_chat" -> R.string.wf_err_chat
            "bad_path" -> R.string.wf_err_path
            "bad_budget" -> R.string.wf_err_budget
            "bad_cron" -> R.string.wf_err_cron
            "bad_trigger" -> R.string.au_err_trigger
            "bad_glob" -> R.string.wf_err_glob
            else -> R.string.au_err_save
        },
    )
}
