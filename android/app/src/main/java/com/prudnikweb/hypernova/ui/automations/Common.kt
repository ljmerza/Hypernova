package com.prudnikweb.hypernova.ui.automations

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.strings
import java.time.ZoneId

/** The app's words for the summary and the run lines, in the phone's 12- or 24-hour style. */
@Composable
fun rememberWords(): Words {
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    return remember(context, h24) { ResWords(context.resources, h24) }
}

/** The Mac's time zone, which every automation time is in; the phone's when the Mac doesn't say. */
fun zoneOf(tz: String?): ZoneId = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/** Names the summary mentions, from what the phone knows of the Mac. */
fun summaryContext(hub: Hub, now: Long): SummaryContext = SummaryContext(
    zone = zoneOf(hub.automations.state?.tz),
    now = now,
    projectName = { path -> hub.projects.firstOrNull { it.path == path }?.name ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { path } },
    sessionName = { id -> (hub.session(id) ?: hub.replacedBy(id)?.let(hub::session))?.name },
    providerName = { id -> if (id == com.prudnikweb.hypernova.data.AgentKind.CODEX_PROVIDER) hub.codex?.let { com.prudnikweb.hypernova.data.Core.app.getString(R.string.cx_agent_codex) } else hub.providers.firstOrNull { it.id == id }?.name },
)

/** What a run that needs you asks: an ask step's question, or its session's line ("Bash: git push"). */
fun askOf(hub: Hub, r: Run?): String? {
    if (r?.state != Run.NEEDS_YOU) return null
    r.ask?.let { return it.question }
    return r.sessionIds.firstNotNullOfOrNull { id -> hub.session(id)?.takeIf { it.state == SessionState.NeedsYou }?.activity?.takeIf { it.isNotBlank() } }
}

/** "OpenRouter is out of credits", for a run skipped over its provider's trouble ("Skipped because …"). */
fun providerTrouble(context: Context, hub: Hub, a: Automation?): String? {
    val steps = a?.let { com.prudnikweb.hypernova.data.WorkflowShape.stepsOf(it) }.orEmpty()
    val id = steps.firstNotNullOfOrNull { (it.action as? Action.Start)?.providerId?.takeIf { p -> hub.providers.firstOrNull { x -> x.id == p }?.builtIn == false } ?: (it.action as? Action.Continue)?.providerId }
        ?: (a?.action as? Action.Start)?.providerId ?: return null
    val p = hub.providers.firstOrNull { it.id == id } ?: return null
    val st = p.status ?: return context.getString(R.string.provider_card_other, p.name)
    return ProviderWords.sentence(context.strings, p.name, st)
}

/**
 * Codex runs the automation's first session (its first start step is Codex's): its run lines say
 * Codex where they'd say Claude Code (signed out, didn't start, not set up).
 */
fun startsOnCodex(a: Automation?): Boolean =
    a != null && (com.prudnikweb.hypernova.data.WorkflowShape.stepsOf(a).firstOrNull { it.action is Action.Start }?.action as? Action.Start)?.providerId == com.prudnikweb.hypernova.data.AgentKind.CODEX_PROVIDER

/** The session a run's line opens: its first one the phone knows. */
fun runSession(hub: Hub, r: Run?): String? = r?.sessionIds?.firstOrNull { hub.session(it) != null }

/** Which provider a session is on, as far as the phone knows (null: the built-in one, or not known). */
fun providerOf(hub: Hub): (String) -> String? = { sid -> hub.sessions.firstOrNull { it.id == sid }?.providerId }

/**
 * Whether running on a provider costs money: the owner can say one doesn't (Provider.paid). Codex
 * costs money on an API key, not on a ChatGPT plan (data/Codex.kt).
 */
fun costs(hub: Hub): (String) -> Boolean = { pid ->
    if (pid == com.prudnikweb.hypernova.data.AgentKind.CODEX_PROVIDER) hub.codex?.account?.paid == true
    else hub.providers.firstOrNull { it.id == pid }?.paid ?: true
}
