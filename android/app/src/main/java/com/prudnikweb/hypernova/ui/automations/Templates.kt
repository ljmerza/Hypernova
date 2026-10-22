package com.prudnikweb.hypernova.ui.automations

import android.content.res.Resources
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger

/**
 * Automations to start from (spec §5 "Templates" and the Addendum): the five around limits lead,
 * since that's where a Mac that's always on helps most. Everything stays editable before Save.
 */
object Templates {
    class Template(
        val id: String,
        val name: Int,
        val blurb: Int,
        val glyph: AutoGlyph,
        /** It spends money (another provider's credit), and says so on its tile. */
        val costs: Boolean = false,
        /** The prompt box starts empty and focused, with this hint. */
        val hint: Int? = null,
        /** One is all an account needs (carry on, switch provider, the debrief, the battery): its tile goes once used. */
        val once: Boolean = false,
        /** What of workflows it needs the Mac to list (docs/specs/workflows.md §3): its tile shows only then. */
        val needs: Set<String> = emptySet(),
    )

    const val CARRY_ON = "carry_on"
    const val RESET = "reset"
    const val SWITCH = "switch_provider"
    const val NIGHT = "night_shift"
    const val DEBRIEF = "weekly_debrief"
    const val BRIEF = "brief"
    const val PRS = "prs"
    const val PLUGGED = "plugged"
    const val BATTERY = "battery"
    const val FIX_TESTS = "fix_tests"
    const val REVIEW = "review"
    const val DIGEST = "brief_digest"
    const val TEAM = "tell_team"

    val ALL = listOf(
        Template(CARRY_ON, R.string.au_t_carry_on, R.string.au_t_carry_on_blurb, AutoGlyph.Continue, once = true),
        Template(RESET, R.string.au_t_reset, R.string.au_t_reset_blurb, AutoGlyph.Reset, hint = R.string.au_t_reset_hint),
        Template(SWITCH, R.string.au_t_switch, R.string.au_t_switch_blurb, AutoGlyph.Switch, costs = true, once = true),
        Template(NIGHT, R.string.au_t_night, R.string.au_t_night_blurb, AutoGlyph.Moon, hint = R.string.au_t_night_hint),
        Template(DEBRIEF, R.string.au_t_debrief, R.string.au_t_debrief_blurb, AutoGlyph.Week, once = true),
        Template(BRIEF, R.string.au_t_brief, R.string.au_t_brief_blurb, AutoGlyph.Clock),
        Template(PRS, R.string.au_t_prs, R.string.au_t_prs_blurb, AutoGlyph.Repeat),
        Template(PLUGGED, R.string.au_t_plugged, R.string.au_t_plugged_blurb, AutoGlyph.Plug),
        Template(BATTERY, R.string.au_t_battery, R.string.au_t_battery_blurb, AutoGlyph.Battery, once = true),
        // Workflows (docs/specs/workflows.md §3): only where the Mac can run them.
        Template(FIX_TESTS, R.string.wf_t_fix, R.string.wf_t_fix_blurb, AutoGlyph.Moon, needs = setOf(Features.STEPS)),
        Template(REVIEW, R.string.wf_t_review, R.string.wf_t_review_blurb, AutoGlyph.Branch, needs = setOf(Features.STEPS, Features.ASK, Features.SHELL, Features.T_GITHUB, Features.STEP_OUTPUT)),
        Template(DIGEST, R.string.wf_t_digest, R.string.wf_t_digest_blurb, AutoGlyph.Page, needs = setOf(Features.O_FILE)),
        Template(TEAM, R.string.wf_t_team, R.string.wf_t_team_blurb, AutoGlyph.Webhook, needs = setOf(Features.O_WEBHOOK, Features.T_AFTER)),
    )

    fun byId(id: String?): Template? = ALL.firstOrNull { it.id == id }

    /**
     * The tiles START FROM offers: every repeatable one always (a second morning brief for another
     * project, budget again next time), the one-per-account ones until used.
     */
    fun offered(used: Set<String>, features: Set<String>? = null): List<Template> =
        ALL.filter { (!it.once || it.id !in used) && it.needs.all { f -> features?.contains(f) == true } }

    /** Switch provider at the limit has nothing to switch to without another provider: its tile asks for one first. */
    fun needsProvider(t: Template, providers: List<Provider>): Boolean = t.id == SWITCH && providers.none { !it.builtIn }

    /** The Mac fills this in with the week from Statistics. */
    const val WEEK = "{week}"

    /** The review's first step: its whole reply is the review the command posts. */
    const val REVIEW_STEP = "st_rev1"

    /**
     * The review's command: the pull request's number from what fired it (`$HYPERNOVA_PAYLOAD`, "PR #12
     * opened by …"), and Claude's review as the first step left it (`$HYPERNOVA_STEP_st_rev1`, its
     * whole reply), both read from its environment, never pasted into the command line.
     */
    const val REVIEW_COMMAND = "n=\$(printf '%s\\n' \"\$HYPERNOVA_PAYLOAD\" | sed -n 's/^PR #\\([0-9]*\\).*/\\1/p' | head -n 1)\ngh pr review \"\$n\" --comment --body \"\$HYPERNOVA_STEP_$REVIEW_STEP\""

    /** Rules that let a read-only job run under "Allowed only". */
    val GIT_READ = listOf("Bash(git log:*)", "Bash(git diff:*)", "Bash(git status:*)", "Bash(git branch:*)", "Bash(git show:*)")
    val GH_READ = listOf("Bash(gh pr list:*)", "Bash(gh pr view:*)", "Bash(gh pr checks:*)", "Bash(gh pr status:*)")

    /**
     * The template filled in: [project] is the most recently active one (null: none yet, the editor
     * asks); another provider for Switch; Read aloud for the debrief when glasses are paired.
     */
    fun input(id: String, res: Resources, project: String?, providers: List<Provider>, glasses: Boolean): AutomationInput? {
        val t = byId(id) ?: return null
        val name = res.getString(t.name)
        val builtIn = providers.firstOrNull { it.builtIn }?.id ?: "anthropic"
        fun start(prompt: String, mode: String, rules: List<String> = emptyList(), then: String = Then.NOTIFY) =
            Action.Start(project.orEmpty(), prompt, builtIn, null, mode, rules, then)
        val (trigger, action, once) = when (id) {
            CARRY_ON -> Triple(Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = true), Action.Continue(Action.Continue.LIMITED, "", Then.NONE), false)
            RESET -> Triple(Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = true), start("", AutoMode.ASK), true)
            SWITCH -> Triple(
                Trigger.Limit(Trigger.FIVE_HOUR),
                Action.Continue(Action.Continue.LIMITED, "", Then.NONE, providerId = providers.firstOrNull { !it.builtIn }?.id.orEmpty()),
                false,
            )
            NIGHT -> Triple(Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = false, from = "00:00", to = "07:00"), start("", AutoMode.ALLOWED), false)
            // On the phone, and read aloud on the glasses too when they're on.
            DEBRIEF -> Triple(Trigger.Reset(Trigger.SEVEN_DAY, afterLimit = false), Action.Notify(WEEK, glasses = glasses), false)
            BRIEF -> Triple(Trigger.Time("09:00", Trigger.WEEKDAYS), start(res.getString(R.string.au_t_brief_prompt), AutoMode.ALLOWED, GIT_READ), false)
            PRS -> Triple(Trigger.Every(2, "09:00", "19:00", Trigger.WEEKDAYS), start(res.getString(R.string.au_t_prs_prompt), AutoMode.ALLOWED, GH_READ), false)
            PLUGGED -> Triple(Trigger.Plugged, start(res.getString(R.string.au_t_plugged_prompt), AutoMode.ASK), false)
            BATTERY -> Triple(Trigger.BatteryLow(15), Action.Continue(Action.Continue.WORKING, res.getString(R.string.au_t_battery_prompt), Then.NOTIFY), false)
            else -> return flow(id, name, res, project, builtIn)
        }
        return AutomationInput(name, enabled = true, onlyOnce = once, trigger = trigger, action = action, template = id)
    }

    /** The workflow templates, filled in: steps with their conditions, outputs where the result goes. */
    private fun flow(id: String, name: String, res: Resources, project: String?, builtIn: String): AutomationInput? {
        val path = project.orEmpty()
        fun start(prompt: String, rules: List<String>) = Action.Start(path, prompt, builtIn, null, AutoMode.ALLOWED, rules, Then.NONE)
        fun input(trigger: Trigger, steps: List<Step>, outputs: List<Output> = emptyList()) =
            AutomationInput(name, enabled = true, onlyOnce = false, trigger = trigger, action = steps.first().action, template = id, steps = steps, outputs = outputs)
        return when (id) {
            // At the night reset: fix what fails; only if that failed, say so.
            FIX_TESTS -> input(
                Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = false, from = "00:00", to = "07:00"),
                listOf(
                    Step("st_fix1", null, start(res.getString(R.string.wf_t_fix_prompt), listOf("Bash(npm test:*)", "Edit(src/**)"))),
                    Step("st_fix2", Guard(Guard.FAILED), Action.Notify(res.getString(R.string.wf_t_fix_notify))),
                ),
            )
            // A pull request opens: a review, a question, and the review posted only when approved.
            REVIEW -> input(
                Trigger.Github(path, listOf(Trigger.PR_OPENED), 10),
                listOf(
                    Step(REVIEW_STEP, null, start(res.getString(R.string.wf_t_review_prompt), GH_READ + listOf("Bash(gh pr diff:*)"))),
                    Step("st_rev2", null, Action.Ask(res.getString(R.string.wf_t_review_ask), listOf("Approve", "Skip"), "Skip", 10)),
                    Step("st_rev3", Guard(Guard.ANSWER, "Approve"), Action.Shell(path, REVIEW_COMMAND, 60)),
                ),
            )
            // Weekdays at 9:00: the brief on the phone, and a line in a Markdown file in the project.
            DIGEST -> input(
                Trigger.Time("09:00", Trigger.WEEKDAYS),
                listOf(Step("st_dig1", null, start(res.getString(R.string.au_t_brief_prompt), GIT_READ))),
                listOf(Output.Notify("ou_dig1", null, glasses = false), Output.File("ou_dig2", null, if (path.isEmpty()) "" else "${path.trimEnd('/')}/${res.getString(R.string.wf_t_digest_file)}")),
            )
            // A session still needs me after 10 minutes: the phone, and Slack (its address is the owner's to paste).
            TEAM -> input(
                Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE, after = 10),
                listOf(Step("st_team1", null, Action.Notify(res.getString(R.string.wf_t_team_notify)))),
                listOf(Output.Webhook("ou_team1", res.getString(R.string.wf_t_team_text), Output.SLACK)),
            )
            else -> null
        }
    }
}
