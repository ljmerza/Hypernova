// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.data


/**
 * What the new-session sheet starts with next time: the project, provider, model and Claude Code's
 * effort the last session was started with, kept on the phone. Fork: the mode isn't the last one
 * used but what that server keeps for that agent ([NewSessionMemory.startsIn]); [codexMode] is only for
 * a server too old to keep it. With Codex (data/Codex.kt): which agent, and Codex's own model and
 * reasoning effort, kept apart from Claude's.
 */
data class Remembered(
    val project: String?,
    val provider: String?,
    val models: Map<String, String?>,
    val agent: String = AgentKind.CLAUDE,
    val codexModel: String? = null,
    val codexEffort: String? = null,
    val codexMode: String = AgentKind.READ_ONLY,
    /** An agent was saved ([agent] is the last one used); false before the first session from here. */
    val agentSaved: Boolean = false,
    /** Claude Code's effort last started with (data/Effort.kt); null: its own default. */
    val effort: String? = null,
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
    private const val START_MODE_OWED = "newSession.startModeOwed"
    private const val AGENT = "newSession.agent"
    private const val CODEX_MODEL = "newSession.codex.model"
    private const val CODEX_EFFORT = "newSession.codex.effort"
    private const val CODEX_MODE = "newSession.codex.mode"
    private const val EFFORT = "newSession.effort"

    /**
     * Fork: the modes a new Claude session can start in (owner, 2026-10-30): Ask, Accept edits, Plan
     * and Auto. Never Bypass: it's chosen each time, with its think-twice card.
     */
    val START_MODES = listOf("default", "acceptEdits", "plan", "auto")

    /** The mode new sessions start in, from what was saved: one of [START_MODES], else Ask. */
    fun startMode(saved: String?): String = saved?.takeIf { it in START_MODES } ?: "default"

    /**
     * What a form opens on for [agent] (docs/DECISIONS.md, "New sessions start in, per server and agent"):
     * what the server said ([served]), only ever one of that agent's own modes, so another agent's word,
     * Bypass or `danger-full-access` is Ask or Read only. [served] null, a server too old to keep it: for
     * Claude Code the old setting if this server is still [owed] it, else Ask; for Codex its last mode here.
     */
    fun startsIn(agent: String, served: String?, owed: String?, memory: Remembered): String = when {
        agent == AgentKind.CODEX -> if (served != null) keptCodexMode(served) else memory.codexMode
        else -> startMode(served ?: owed)
    }

    fun startsIn(agent: String, hub: Hub, memory: Remembered): String =
        startsIn(agent, if (agent == AgentKind.CODEX) hub.codex?.startMode else hub.claude?.startMode, hub.startModeOwed, memory)

    /**
     * At each start ([Core.init]): who's still owed the old phone-wide setting. The first start finds no list
     * and writes the servers paired then ([paired]). Nothing adds to it after that, so a server paired later
     * never gets it. A server forgotten leaves it, and with nobody left, or nothing worth moving, the old
     * value and the list are gone for good.
     */
    fun noteStartModeServers(storage: Storage, paired: List<String>) {
        val p = storage.prefs(PREFS)
        val old = p.getString(START_MODE, null) ?: return
        writeOwed(p, if (!worthMoving(old)) emptyList() else if (p.contains(START_MODE_OWED)) owed(p).filter { it in paired } else paired)
    }

    /** The old setting, while server [serverId] is still owed it; null otherwise. */
    fun startModeToMove(storage: Storage, serverId: String): String? {
        val p = storage.prefs(PREFS)
        return p.getString(START_MODE, null)?.takeIf { serverId in owed(p) && worthMoving(it) }
    }

    /** Server [serverId] has it: off the list, and the last one takes the old value with it. */
    fun startModeMoved(storage: Storage, serverId: String) {
        val p = storage.prefs(PREFS)
        writeOwed(p, owed(p) - serverId)
    }

    /** Ask is what nothing saved means, and anything else that isn't a start mode (Bypass, Codex's words) is never one. */
    private fun worthMoving(mode: String) = mode in START_MODES && mode != "default"

    private fun owed(p: Prefs): List<String> = p.getString(START_MODE_OWED, null)?.split(',').orEmpty()

    private fun writeOwed(p: Prefs, owed: List<String>) {
        val e = p.edit()
        if (owed.isEmpty()) e.remove(START_MODE).remove(START_MODE_OWED) else e.putString(START_MODE_OWED, owed.joinToString(","))
        e.apply()
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

    fun load(storage: Storage): Remembered {
        val p = storage.prefs(PREFS)
        val models = HashMap<String, String?>()
        for ((k, v) in p.all) if (k.startsWith(MODEL) && v is String) models[k.removePrefix(MODEL)] = v.ifEmpty { null }
        return Remembered(
            p.getString(PROJECT, null), p.getString(PROVIDER, null), models,
            agent = if (p.getString(AGENT, null) == AgentKind.CODEX) AgentKind.CODEX else AgentKind.CLAUDE,
            codexModel = p.getString(CODEX_MODEL, null)?.ifEmpty { null },
            codexEffort = p.getString(CODEX_EFFORT, null)?.ifEmpty { null },
            codexMode = keptCodexMode(p.getString(CODEX_MODE, null)),
            agentSaved = p.contains(AGENT),
            effort = ClaudeEffort.known(p.getString(EFFORT, null)),
        )
    }

    /**
     * A session started on these: the sheet opens on them next time. [project] null: a new one, which
     * isn't a path yet. Its mode isn't kept (Fork: [startsIn]); its [effort] is (null: the default).
     */
    fun save(storage: Storage, project: String?, provider: String?, model: String?, effort: String?) {
        val e = storage.prefs(PREFS).edit()
        if (project != null) e.putString(PROJECT, project)
        if (provider != null) {
            e.putString(PROVIDER, provider)
            e.putString(MODEL + provider, model.orEmpty())
        }
        e.putString(EFFORT, ClaudeEffort.known(effort).orEmpty())
        e.putString(AGENT, AgentKind.CLAUDE)
        e.apply()
    }

    /** A Codex session started on these: the sheet opens on Codex with them next time; Claude's choices stay as they were. */
    fun saveCodex(storage: Storage, project: String?, model: String?, effort: String?, mode: String) {
        val e = storage.prefs(PREFS).edit()
        if (project != null) e.putString(PROJECT, project)
        e.putString(AGENT, AgentKind.CODEX)
        e.putString(CODEX_MODEL, model.orEmpty())
        e.putString(CODEX_EFFORT, effort.orEmpty())
        e.putString(CODEX_MODE, keptCodexMode(mode))
        e.apply()
    }
}
