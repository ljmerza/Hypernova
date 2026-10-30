package com.prudnikweb.hypernova.data

import android.content.Context

/**
 * What the new-session sheet starts with next time: the project, provider and model the last
 * session was started with, kept on the phone. Fork: Claude's mode isn't the last one used but the
 * one chosen in Settings → New sessions ([NewSessionMemory.startMode]; Ask until one is), every time.
 * With Codex (data/Codex.kt): which agent, and Codex's own model, reasoning effort and mode, kept
 * apart from Claude's.
 */
data class Remembered(
    val project: String?,
    val provider: String?,
    val models: Map<String, String?>,
    val mode: String,
    val agent: String = AgentKind.CLAUDE,
    val codexModel: String? = null,
    val codexEffort: String? = null,
    val codexMode: String = AgentKind.READ_ONLY,
    /** An agent was saved ([agent] is the last one used); false before the first session from here. */
    val agentSaved: Boolean = false,
) {
    /** The model last used on [providerId]; null (the default) when none was. */
    fun model(providerId: String?): String? = providerId?.let { models[it] }

    /** The agent the sheet opens on: see [NewSessionMemory.keptAgent]. */
    fun agent(lead: String, claudeAvailable: Boolean, codexAvailable: Boolean): String =
        NewSessionMemory.keptAgent(agent.takeIf { agentSaved }, lead, claudeAvailable, codexAvailable)
}

object NewSessionMemory {
    /** The sheet's words not sent yet, for this run of the app: they're back when it opens again (put away, or a start that failed after it closed). */
    @Volatile var draft: String = ""

    private const val PREFS = "hypernova"
    private const val PROJECT = "newSession.project"
    private const val PROVIDER = "newSession.provider"
    private const val MODEL = "newSession.model."
    private const val START_MODE = "newSession.startMode"
    private const val AGENT = "newSession.agent"
    private const val CODEX_MODEL = "newSession.codex.model"
    private const val CODEX_EFFORT = "newSession.codex.effort"
    private const val CODEX_MODE = "newSession.codex.mode"

    /**
     * Fork: the modes a new Claude session can start in (owner, 2026-10-30): Ask, Accept edits, Plan
     * and Auto. Never Bypass: it waits for the fingerprint or the screen lock each time it's chosen.
     */
    val START_MODES = listOf("default", "acceptEdits", "plan", "auto")

    /** The mode new sessions start in, from what was saved: one of [START_MODES], else Ask. */
    fun startMode(saved: String?): String = saved?.takeIf { it in START_MODES } ?: "default"

    /** Settings → New sessions: the mode the sheet opens on; a session's own pick doesn't change it. */
    fun startMode(context: Context): String =
        startMode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(START_MODE, null))

    fun setStartMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(START_MODE, startMode(mode)).apply()
    }

    /**
     * The agent the sheet opens on (data/AgentLead.kt): the one last used ([saved]) while it's still
     * here; before any, or when it's gone, the leading agent ([lead]: Claude Code, or Codex where
     * Claude Code isn't installed). Where Claude Code isn't installed and Codex is, always Codex.
     */
    fun keptAgent(saved: String?, lead: String, claudeAvailable: Boolean, codexAvailable: Boolean): String = when {
        !claudeAvailable && codexAvailable -> AgentKind.CODEX
        saved == AgentKind.CODEX && codexAvailable -> AgentKind.CODEX
        saved == AgentKind.CLAUDE && claudeAvailable -> AgentKind.CLAUDE
        else -> if (lead == AgentKind.CODEX && codexAvailable) AgentKind.CODEX else AgentKind.CLAUDE
    }

    /** Codex's mode carried over: either of its two (nothing it does goes without asking); anything else is Read only. */
    fun keptCodexMode(mode: String?): String = if (mode == AgentKind.CAN_EDIT) AgentKind.CAN_EDIT else AgentKind.READ_ONLY

    fun load(context: Context): Remembered {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val models = HashMap<String, String?>()
        for ((k, v) in p.all) if (k.startsWith(MODEL) && v is String) models[k.removePrefix(MODEL)] = v.ifEmpty { null }
        return Remembered(
            p.getString(PROJECT, null), p.getString(PROVIDER, null), models, startMode(p.getString(START_MODE, null)),
            agent = if (p.getString(AGENT, null) == AgentKind.CODEX) AgentKind.CODEX else AgentKind.CLAUDE,
            codexModel = p.getString(CODEX_MODEL, null)?.ifEmpty { null },
            codexEffort = p.getString(CODEX_EFFORT, null)?.ifEmpty { null },
            codexMode = keptCodexMode(p.getString(CODEX_MODE, null)),
            agentSaved = p.contains(AGENT),
        )
    }

    /**
     * A session started on these: the sheet opens on them next time. [project] null: a new one, which
     * isn't a path yet. Its mode isn't kept (Fork: [startMode]).
     */
    fun save(context: Context, project: String?, provider: String?, model: String?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (project != null) e.putString(PROJECT, project)
        if (provider != null) {
            e.putString(PROVIDER, provider)
            e.putString(MODEL + provider, model.orEmpty())
        }
        e.putString(AGENT, AgentKind.CLAUDE)
        e.apply()
    }

    /** A Codex session started on these: the sheet opens on Codex with them next time; Claude's choices stay as they were. */
    fun saveCodex(context: Context, project: String?, model: String?, effort: String?, mode: String) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (project != null) e.putString(PROJECT, project)
        e.putString(AGENT, AgentKind.CODEX)
        e.putString(CODEX_MODEL, model.orEmpty())
        e.putString(CODEX_EFFORT, effort.orEmpty())
        e.putString(CODEX_MODE, keptCodexMode(mode))
        e.apply()
    }
}
