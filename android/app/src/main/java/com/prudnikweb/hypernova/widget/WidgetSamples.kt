package com.prudnikweb.hypernova.widget

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.widget.WidgetModel.Conn

/**
 * Debug builds: believable pictures for the widgets without a Mac, for screenshots (the demo
 * doesn't reach the widgets, which follow the live hub only). Drawn by [WidgetSync.sample] into
 * the widgets on the home screen, or by [WidgetSync.render] into files:
 *
 *     adb shell run-as com.prudnikweb.hypernova am broadcast --user 0 \
 *       -n com.prudnikweb.hypernova/.widget.WidgetActions -a com.prudnikweb.hypernova.widget.SAMPLE \
 *       --es sample limit
 *
 * [which]: "limit" (spending fast: at this pace the 5-hour limit comes before the reset), "pace"
 * (spending, but the window lasts), "limited" (at the 5-hour limit, back in 42 minutes), "week" (the weekly limit),
 * "agents" (Claude Code and Codex both in use: Codex's readout beside the sun, a Codex row marked), "codex" (Codex
 * alone, where Claude Code isn't installed: the star is Codex's), anything else at rest.
 */
internal object WidgetSamples {
    fun of(which: String?, now: Long): WidgetModel {
        val working = which == "limit" || which == "pace"
        val limited = which == "limited"
        val week = which == "week"
        val both = which == "agents"
        val codexLeads = which == "codex"
        val rows = listOfNotNull(
            WidgetModel.Row("c7", "release landing copy", "0stems", "Bash: npm run deploy", SessionState.NeedsYou),
            WidgetModel.Row("d2", "lens settings sync", "hypernova", "Edit: lens/src/settings.ts", SessionState.Working, codex = true).takeIf { both },
            WidgetModel.Row("a3", "cosmos renderer", "hypernova", "Bash: ./gradlew assembleDebug", SessionState.Working).takeIf { working },
            WidgetModel.Row("b8", "haze edges", "hypernova", "Done: edges blur under the bars", SessionState.Idle),
            WidgetModel.Row("1f", "work strip card", "prudnikov-site", "Waiting for your next message", SessionState.Idle),
        )
        return WidgetModel(
            conn = Conn.Live,
            macName = "Studio",
            usage = WidgetModel.UsageView(
                fiveHour = if (limited || week) 1f else if (which == "limit") 0.62f else 0.42f,
                sevenDay = if (week) 1f else 0.18f,
                fiveResetAt = if (limited) (now + 42 * 60_000L) / 60_000L * 60_000L else now + 121 * 60_000L,
                sevenResetAt = if (week) now + 30 * 3_600_000L else now + 61 * 3_600_000L,
                known = true, limited = limited, weekLimited = week,
                // 62% at 0.5%/min is full in 76 minutes, 45 before the reset; 42% at 0.15%/min
                // reaches 60% by it.
                fivePace = if (which == "limit") 0.5f else if (which == "pace") 0.15f else null,
                fiveLimitAt = if (which == "limit") (now + 76 * 60_000L) / 60_000L * 60_000L else 0L,
                sevenPace = if (working) 0.01f else null,
            ),
            working = if (working || both) 1 else 0,
            needs = 1,
            rows = rows,
            ask = WidgetModel.Ask(
                id = "sample-ask", sessionId = "c7", kind = "tool", tool = "Bash", detail = "npm run deploy",
                project = "0stems", title = "release landing copy", more = 0, whole = true, expiresAt = now + 9 * 60_000L,
            ),
            updatedAt = now,
            leadAgent = if (codexLeads) AgentKind.CODEX else AgentKind.CLAUDE,
            other = if (both) WidgetModel.Other(AgentKind.CODEX, used = 0.61f, resetAt = now + 3 * 3_600_000L, limited = false) else null,
        )
    }
}
