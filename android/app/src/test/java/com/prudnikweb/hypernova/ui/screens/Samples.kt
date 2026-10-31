package com.prudnikweb.hypernova.ui.screens

import com.prudnikweb.hypernova.data.CommandRun
import com.prudnikweb.hypernova.data.DiffLine
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.PermissionRules
import com.prudnikweb.hypernova.data.Project
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.RuleSource
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.TermMenu
import com.prudnikweb.hypernova.data.TodoNow
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.data.Why

/**
 * The screen tests' sample data: a few sessions in each of Home's groups, their approvals and a
 * transcript. Times are relative to [NOW] (set again as each test starts, by [resetPrefs]), so
 * "2m ago" and "for 12 min" read the same on every run and in every snapshot. Nothing in a
 * snapshot may show a wall-clock time made from them ("Started 3:05 PM").
 */
object Samples {
    var NOW: Long = System.currentTimeMillis()
    private const val MIN = 60_000L

    // Ids, so tests name what they mean.
    const val BUILD = "s-build" // Needs input: a Bash ask, answerable from its card.
    const val DEPLOY = "s-deploy" // Needs input: Claude Code's own dialog in its terminal.
    const val PARSER = "s-parser" // Working: a to-do under way, 12 minutes into its turn.
    const val WIDGETS = "s-widgets" // Working.
    const val ZETA = "s-zeta" // Completed 2 minutes ago.
    const val MID = "s-mid" // Completed 20 minutes ago.
    const val ALPHA = "s-alpha" // Completed an hour ago.

    fun mac() = Mac(name = "z2", online = true, via = Mac.VIA_LOCAL, url = "https://192.168.1.20:47323", daemonVersion = "0.9.0", claudeVersion = "2.1.316", platform = Mac.LINUX)

    /** 42% of the 5-hour window (resets in 2h 10m), 18% of the week (resets in 3 days). */
    fun usage(fiveHourReported: Boolean = true) = Usage(
        fiveHour = 0.42f, sevenDay = 0.18f,
        fiveHourResetAt = NOW + 130 * MIN + 30_000, sevenDayResetAt = NOW + 3 * 24 * 60 * MIN + 30_000,
        fiveHourReported = fiveHourReported,
    )

    fun session(
        id: String, name: String, state: SessionState, updatedAgoMin: Long, project: String = "hypernova",
        activity: String = "Idle", hex: String = id.takeLast(2), mode: String = "default",
    ) = Session(
        hex = hex, id = id, name = name, project = project, state = state, model = "claude-opus-5-5", mode = mode,
        activity = activity, updatedAt = NOW - updatedAgoMin * MIN, cwd = "/home/owner/projects/$project",
        origin = "hypernova", createdAt = NOW - 90 * MIN,
    )

    fun sessions(): List<Session> = listOf(
        session(BUILD, "Fix the build", SessionState.NeedsYou, 1, activity = "Bash: npm test"),
        session(DEPLOY, "Deploy check", SessionState.NeedsYou, 3, project = "infra", activity = "Waiting for you").copy(
            term = TermLink("t-deploy", mac = 0, phone = 0),
            menu = deployMenu(),
        ),
        session(PARSER, "Refactor parser", SessionState.Working, 0, activity = "Bash: gradle test").copy(
            turnStartedAt = NOW - 12 * MIN, todo = TodoNow("Running the tests", 3, 7),
        ),
        session(WIDGETS, "Add widgets", SessionState.Working, 0, activity = "Edit: Widget.kt").copy(turnStartedAt = NOW - 4 * MIN),
        // A `claude agents` job whose process still runs: Stop on its menu (and Smart's swipe).
        session(ZETA, "Zeta cleanup", SessionState.Idle, 2, activity = "Done: removed the old flags").copy(agentJob = "j-zeta", agentRunning = true),
        session(MID, "Mid sprint notes", SessionState.Idle, 20, project = "notes", activity = "Wrote the summary"),
        // A job that has stopped: Delete.
        session(ALPHA, "Alpha release", SessionState.Idle, 60, activity = "Tagged v0.9.0").copy(agentJob = "j-alpha", agentRunning = false),
    )

    /** Claude Code's permission dialog in Deploy check's terminal. */
    fun deployMenu() = TermMenu(
        id = "m-deploy", title = "Bash command", body = listOf("Check the cluster", "Do you want to proceed?"),
        options = listOf(
            TermMenu.Option("Yes", null, true),
            TermMenu.Option("Yes, and don't ask again for kubectl get", null, false),
            TermMenu.Option("No, and tell Claude what to do differently", null, false),
        ),
        cursor = 0, more = false, cancel = true, alt = null,
        permission = TermMenu.Permission("Bash command", "kubectl get pods -n prod", "Do you want to proceed?", why = askRuleWhy("Bash(kubectl:*)")),
    )

    fun askRuleWhy(rule: String) = Why("ask_rule", "Ask rule $rule overrides auto mode for this command", rule, "claude")

    fun bashAsk(id: String, session: String, command: String, createdAgoMin: Long = 1, why: Why? = null) = WApproval(
        id = id, sessionId = session, createdAt = NOW - createdAgoMin * MIN, expiresAt = null, kind = "tool", toolName = "Bash",
        summary = command, input = mapOf("command" to command), reason = null, canAlways = true, questions = emptyList(), plan = null,
        alwaysRules = listOf("Bash(${command.substringBefore(' ')} *)"), alwaysScope = "project", why = why,
    )

    /** Fix the build waits on two Bash asks (an agent's too): Allow these 2 in its sheet. */
    fun approvals(): Map<String, List<WApproval>> = mapOf(
        BUILD to listOf(
            bashAsk("ap-test", BUILD, "npm test", 2, why = askRuleWhy("Bash(npm:*)")),
            bashAsk("ap-lint", BUILD, "npm run lint", 1, why = Why("no_allow_rule", "No allow rule matches npm run lint", null, "hypernova")).copy(agentLabel = "Lint fixer"),
        ),
    )

    /** Fix the build's chat: one of each kind of row, and its ask waiting at the end. */
    fun buildTranscript(): List<Item> = listOf(
        Item.User("u1", "The build fails on main, can you fix it?"),
        Item.Thinking("th1", chars = 420, tookMs = 4_000),
        Item.Assistant("a1", "I'll look at the failing test first."),
        Item.Tool("t1", verb = "Read", target = "src/parser.ts", path = "/home/owner/projects/hypernova/src/parser.ts"),
        Item.Terminal("b1", command = "npm run build", lines = listOf("> tsc -p .", "src/parser.ts(12,5): error TS2322"), running = false, exitCode = 2, elapsedMs = 3_200),
        // Words between the calls, so no run of three folds them into one line (ToolRuns).
        Item.Assistant("a15", "The counter is typed as a string."),
        Item.Diff("d1", file = "src/parser.ts", added = 1, removed = 1, lines = listOf(DiffLine('-', "  let n: string = 0"), DiffLine('+', "  let n: number = 0")), path = "/home/owner/projects/hypernova/src/parser.ts"),
        Item.User("u2", "/effort high", command = CommandRun("/effort", "high", "local")),
        Item.Assistant("a2", "Fixed the type. Running the tests now."),
        Item.Approval(
            "ap-test", tool = "Bash", detail = "npm test", reason = "", approvalId = "ap-test",
            alwaysRules = listOf("Bash(npm test *)"), alwaysScope = "project",
        ),
    )

    fun transcripts(): Map<String, List<Item>> = mapOf(
        BUILD to buildTranscript(),
        DEPLOY to listOf(Item.User("u1", "Check the prod pods"), Item.Assistant("a1", "Looking at the cluster.")),
        ZETA to listOf(Item.User("u1", "Remove the old flags"), Item.Assistant("a1", "Done: removed the old flags.")),
    )

    fun projects() = listOf(
        Project("/home/owner/projects/hypernova", "hypernova", NOW - 5 * MIN, 4),
        Project("/home/owner/projects/infra", "infra", NOW - 60 * MIN, 1),
        Project("/home/owner/projects/notes", "notes", NOW - 24 * 60 * MIN, 1),
    )

    fun providers() = listOf(Provider("anthropic", "Claude", "anthropic", null, hasKey = false, models = ProviderModels(), builtIn = true))

    fun rules() = PermissionRules(
        listOf(
            RuleSource(RuleSource.PROJECT, "/home/owner/projects/hypernova/.claude/settings.json", RuleSource.OK, allow = listOf("Bash(git status)", "Read"), ask = listOf("Bash(npm:*)"), deny = listOf("Bash(rm -rf *)"), defaultMode = null, additionalDirectories = emptyList(), clipped = false),
            RuleSource(RuleSource.USER, "/home/owner/.claude/settings.json", RuleSource.OK, allow = listOf("WebSearch"), ask = emptyList(), deny = emptyList(), defaultMode = "default", additionalDirectories = emptyList(), clipped = false),
        ),
        managedOnly = false,
    )

    fun commands() = listOf(
        SlashCommand("compact", "Fold the conversation into a summary", "", emptyList(), builtin = true),
        SlashCommand("effort", "Set the reasoning effort", "low|medium|high", emptyList(), builtin = true),
        SlashCommand("review", "Review the branch", "", emptyList(), builtin = false),
    )
}
