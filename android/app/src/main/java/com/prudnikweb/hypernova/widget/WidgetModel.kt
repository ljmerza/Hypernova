package com.prudnikweb.hypernova.widget

import android.content.Context
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.LiveHub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.data.WApproval
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the home screen widgets show, cut down from the live hub: a few sessions, the oldest
 * approval, usage and how fresh it all is. Small enough to keep on disk, so a widget drawn by a
 * freshly started process (after a reboot, or for the widgets' own alarm) shows the last
 * thing it knew, honestly labelled, instead of an empty card.
 */
internal data class WidgetModel(
    val conn: Conn,
    val macName: String?,
    val usage: UsageView,
    val working: Int,
    val needs: Int,
    /** Most urgent first: needs you, working, then the most recent idle ones. */
    val rows: List<Row>,
    val ask: Ask?,
    /** When the data was last known to be current (0 when it never was). */
    val updatedAt: Long = 0L,
    /**
     * The account's state when Claude can't reply until the owner acts
     * ([com.prudnikweb.hypernova.data.Account.needsYou]: "logged_out", "no_subscription",
     * "on_hold", "no_credit"); null when it's fine or only resting at a limit (which [usage] shows).
     */
    val account: String? = null,
    /**
     * Past a usage limit on usage credits Claude carries on, though the numbers still say 100%:
     * nothing to lead with, as the permanent notification has it (AccountWords.headline).
     */
    val usingCredits: Boolean = false,
    /**
     * Which agent the star, its readout and the lead follow (data/AgentLead.kt): [AgentKind.CLAUDE],
     * or [AgentKind.CODEX] where Claude Code isn't installed and Codex is. [usage] is that agent's.
     */
    val leadAgent: String = AgentKind.CLAUDE,
    /**
     * While Codex leads, what stops it until the owner acts: [CODEX_SIGNED_OUT], or the Mac's words
     * for what's wrong (its problem, on one line); null when it's fine. [account] is Claude's alone.
     */
    val codexTrouble: String? = null,
    /** The star's window is Codex's week (a Codex plan with only that): [usage]'s 5-hour numbers are the week's. */
    val weekOnly: Boolean = false,
    /** The other agent's compact readout ("CODEX 61%") while both agents are in use; null otherwise. */
    val other: Other? = null,
) {
    /** Claude Code and Codex both in use: the widgets name the agents (and only then). */
    val both: Boolean get() = other != null

    /**
     * The agent that doesn't lead, as the widgets' second readout draws it while both are in use:
     * [agent] ([AgentKind.CODEX]), how much of its window is spent ([used], 0..1 in whole percents;
     * null without numbers), when that window (or, when [limited], the window it's limited by)
     * resets ([resetAt], 0 when not known), and [trouble]: [CODEX_SIGNED_OUT] or the Mac's words
     * for what's wrong, drawn in amber.
     */
    data class Other(
        val agent: String,
        val used: Float?,
        val resetAt: Long,
        val limited: Boolean,
        val trouble: String? = null,
    ) {
        /** As it is at [now]: past its reset the window is fresh again (no number until new ones come, no limit). */
        fun at(now: Long): Other = if (resetAt in 1..now) copy(used = null, resetAt = 0L, limited = false) else this
    }

    /** How much the widget can promise about what it shows. */
    enum class Conn {
        /** The app is on screen or the background connection runs: changes arrive as they happen. */
        Live,
        /** Connected a moment ago, but nothing keeps the link open now: "Last updated hh:mm". */
        Stale,
        /**
         * The background connection is off (in Settings, or Disconnect on its notification) and
         * the app is closed: nothing keeps the widget current, by the owner's choice. Drawn as
         * [Stale], whether or not the socket happens to be up: a link that dropped then says
         * nothing about the Mac, so there's no "Not connected", only "Updates while Hypernova is open".
         */
        AppOnly,
        /** Paired, but the Mac can't be reached: "Not connected to your Mac". */
        Offline,
        /** No Mac paired (or the Mac forgot this phone). */
        Unpaired,
    }

    data class UsageView(
        val fiveHour: Float,
        val sevenDay: Float,
        /** When each window resets (0: not known, as after a reset that has passed). */
        val fiveResetAt: Long,
        val sevenResetAt: Long,
        /** False until the Mac has official numbers. */
        val known: Boolean,
        val limited: Boolean,
        val weekLimited: Boolean,
        /**
         * How fast each window is being spent (%/min, [WidgetMeter.pace]) while something works, as
         * the Mac measures it; null at rest. Drawn only on a live picture: a stale one can't say
         * whether anything still works.
         */
        val fivePace: Float? = null,
        val sevenPace: Float? = null,
        /** When, at that pace, each window is full, if that comes before its reset (0: it doesn't); whole minutes. */
        val fiveLimitAt: Long = 0L,
        val sevenLimitAt: Long = 0L,
    ) {
        /**
         * These numbers as they are at [now]. A window whose reset has passed is fresh again, as
         * LiveHub.recomputeUsage has it: no percentage until the Mac sends new numbers, and no
         * limit. A widget that isn't live may be drawn long after its numbers came in.
         */
        fun at(now: Long): UsageView {
            val over5 = fiveResetAt in 1..now
            val over7 = sevenResetAt in 1..now
            if (!over5 && !over7) return this
            return UsageView(
                fiveHour = if (over5) 0f else fiveHour,
                sevenDay = if (over7) 0f else sevenDay,
                fiveResetAt = if (over5) 0L else fiveResetAt,
                sevenResetAt = if (over7) 0L else sevenResetAt,
                known = known && !over5,
                limited = limited && !over5,
                weekLimited = weekLimited && !over7,
                fivePace = fivePace.takeIf { !over5 },
                sevenPace = sevenPace.takeIf { !over7 },
                fiveLimitAt = if (over5) 0L else fiveLimitAt,
                sevenLimitAt = if (over7) 0L else sevenLimitAt,
            )
        }

        /**
         * The 5-hour window as the meters draw it at [now] ([UsageProjection]): what's used, and on
         * a [live] picture where the pace leads by the reset, and whether the limit comes first.
         */
        fun five(now: Long, live: Boolean): UsageProjection =
            UsageProjection.of(fiveHour, fiveResetAt, now, fivePace.takeIf { live }, (fiveLimitAt - now).takeIf { live && fiveLimitAt > now }, limited)

        /** The weekly window, as [five]. */
        fun seven(now: Long, live: Boolean): UsageProjection =
            UsageProjection.of(sevenDay, sevenResetAt, now, sevenPace.takeIf { live }, (sevenLimitAt - now).takeIf { live && sevenLimitAt > now }, weekLimited)
    }

    data class Row(
        val id: String,
        val title: String,
        val project: String,
        val activity: String,
        /** As the widget draws it: a session whose main agent waits on agents it started is [SessionState.Working]. */
        val state: SessionState,
        /**
         * Agents still at work while its main agent is idle (0 otherwise): then they're what it's
         * doing, and the row says so instead of [activity] (its last words).
         */
        val agents: Int = 0,
        /** A Codex session, marked CODEX on its row: only while both agents are in use. */
        val codex: Boolean = false,
    )

    data class Ask(
        val id: String,
        val sessionId: String,
        /** "tool", "question" or "plan": only tools are answered on the widget. */
        val kind: String,
        val tool: String,
        /**
         * The exact command (or the question, or the plan's first line). When it's [whole], exactly
         * as it will run, line breaks and all; otherwise on one line, secrets masked.
         */
        val detail: String,
        val project: String,
        val title: String,
        /** Other approvals waiting behind this one. */
        val more: Int,
        /**
         * [detail] is everything being approved, untouched: a command (or the one path, pattern
         * or address a read-only tool reads) that isn't cut short, has nothing masked and draws as
         * what it is. Only then can the widget offer Allow, and only at a size that shows all of
         * it; anything else opens the app.
         */
        val whole: Boolean = false,
        /** When the Mac denies it for nobody answering (its 10-minute rule); 0 if not known. */
        val expiresAt: Long = 0L,
        /** This phone is sending an answer to it, or the last try failed. */
        val answering: Answering? = null,
    )

    enum class Answering { Allowing, Denying, Failed }

    /** Everything but the timestamp: two models that only differ in time look the same on screen. */
    fun sameContent(o: WidgetModel?): Boolean = o != null && copy(updatedAt = 0L) == o.copy(updatedAt = 0L)

    /**
     * What's still true of this model at [now], for drawing: resets that have passed (see
     * [UsageView.at]), and no approval the Mac has already denied for nobody answering. A live
     * model keeps its approval: the Mac's own list drops it, by the Mac's clock.
     */
    fun at(now: Long): WidgetModel {
        val u = usage.at(now)
        val a = ask?.takeIf { conn == Conn.Live || now < it.expiresAt }
        val o = other?.at(now)
        return if (u === usage && a === ask && o == other) this else copy(usage = u, ask = a, other = o)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("conn", conn.name)
        .put("macName", macName)
        .put("updatedAt", updatedAt)
        .put("account", account)
        .put("usingCredits", usingCredits)
        .put("working", working)
        .put("needs", needs)
        .put("leadAgent", leadAgent)
        .put("codexTrouble", codexTrouble)
        .put("weekOnly", weekOnly)
        .put(
            "other",
            other?.let {
                JSONObject().put("agent", it.agent).put("used", it.used?.toDouble()).put("resetAt", it.resetAt)
                    .put("limited", it.limited).put("trouble", it.trouble)
            },
        )
        .put(
            "usage", JSONObject()
                .put("fiveHour", usage.fiveHour.toDouble()).put("sevenDay", usage.sevenDay.toDouble())
                .put("fiveResetAt", usage.fiveResetAt).put("sevenResetAt", usage.sevenResetAt)
                .put("known", usage.known).put("limited", usage.limited).put("weekLimited", usage.weekLimited),
        )
        .put(
            "rows",
            JSONArray(rows.map { JSONObject().put("id", it.id).put("title", it.title).put("project", it.project).put("activity", it.activity).put("state", it.state.name).put("agents", it.agents).put("codex", it.codex) }),
        )
        .put(
            "ask",
            ask?.let {
                JSONObject().put("id", it.id).put("sessionId", it.sessionId).put("kind", it.kind).put("tool", it.tool)
                    .put("detail", it.detail).put("project", it.project).put("title", it.title).put("more", it.more)
                    .put("whole", it.whole).put("expiresAt", it.expiresAt)
            },
        )

    companion object {
        /** How many sessions a model keeps; the largest widget shows four. */
        const val MAX_ROWS = 6

        private val NO_USAGE = UsageView(0f, 0f, 0L, 0L, known = false, limited = false, weekLimited = false)

        fun unpaired() = WidgetModel(Conn.Unpaired, null, NO_USAGE, 0, 0, emptyList(), null)

        fun fromJson(o: JSONObject): WidgetModel? = runCatching {
            val u = o.getJSONObject("usage")
            val rows = o.optJSONArray("rows") ?: JSONArray()
            WidgetModel(
                conn = Conn.valueOf(o.getString("conn")),
                macName = o.optString("macName").takeIf { o.has("macName") && !o.isNull("macName") },
                usage = UsageView(
                    u.getDouble("fiveHour").toFloat(), u.getDouble("sevenDay").toFloat(),
                    u.getLong("fiveResetAt"), u.getLong("sevenResetAt"),
                    u.getBoolean("known"), u.getBoolean("limited"), u.getBoolean("weekLimited"),
                ),
                working = o.getInt("working"),
                needs = o.getInt("needs"),
                rows = (0 until rows.length()).map { i ->
                    val r = rows.getJSONObject(i)
                    Row(r.getString("id"), r.getString("title"), r.getString("project"), r.getString("activity"), SessionState.valueOf(r.getString("state")), r.optInt("agents"), codex = r.optBoolean("codex"))
                },
                ask = o.optJSONObject("ask")?.let { a ->
                    Ask(
                        a.getString("id"), a.getString("sessionId"), a.getString("kind"), a.getString("tool"),
                        a.getString("detail"), a.getString("project"), a.getString("title"), a.getInt("more"),
                        whole = a.optBoolean("whole"), expiresAt = a.optLong("expiresAt"),
                    )
                },
                updatedAt = o.optLong("updatedAt"),
                account = o.optString("account").takeIf { o.has("account") && !o.isNull("account") && it.isNotEmpty() },
                usingCredits = o.optBoolean("usingCredits"),
                // Kept by a build before Codex: Claude leads, nothing else.
                leadAgent = if (o.optString("leadAgent") == AgentKind.CODEX) AgentKind.CODEX else AgentKind.CLAUDE,
                codexTrouble = o.optString("codexTrouble").takeIf { o.has("codexTrouble") && !o.isNull("codexTrouble") && it.isNotEmpty() },
                weekOnly = o.optBoolean("weekOnly"),
                other = o.optJSONObject("other")?.let { x ->
                    Other(
                        agent = x.optString("agent").ifEmpty { AgentKind.CODEX },
                        used = if (x.has("used") && !x.isNull("used")) x.optDouble("used").takeIf { !it.isNaN() }?.toFloat() else null,
                        resetAt = x.optLong("resetAt"),
                        limited = x.optBoolean("limited"),
                        trouble = x.optString("trouble").takeIf { x.has("trouble") && !x.isNull("trouble") && it.isNotEmpty() },
                    )
                },
            )
        }.getOrNull()

        /** [WidgetModel.codexTrouble] and [Other.trouble] when Codex is signed out on the Mac. */
        const val CODEX_SIGNED_OUT = "signed_out"

        /** The most of the Mac's words for Codex's trouble a widget keeps. */
        private const val TROUBLE_MAX = 160

        /**
         * The agents' part of the widgets' picture (data/AgentLead.kt), pure: which agent leads, the
         * other's readout while both are in use, and what stops Codex while it leads.
         */
        internal data class Agents(val leadAgent: String, val codexTrouble: String?, val weekOnly: Boolean, val other: Other?) {
            val codexLeads: Boolean get() = leadAgent == AgentKind.CODEX
            val both: Boolean get() = other != null
        }

        /** The widgets' view of the agents on the Mac: [claude] and [codex] as the snapshot has them. */
        internal fun agents(claude: ClaudeState?, codex: CodexState?): Agents {
            val leads = AgentLead.codexLeads(claude, codex)
            val u = codex?.usage
            val other = if (AgentLead.both(claude, codex)) {
                Other(
                    agent = AgentKind.CODEX,
                    // In whole percents: finer steps wouldn't show, and would redraw the widget for nothing.
                    used = AgentLead.codexUsed(u)?.let { (it * 100f).roundToInt() / 100f },
                    resetAt = (AgentLead.codexLimitedWindow(u) ?: AgentLead.codexWindow(u))?.resetsAt ?: 0L,
                    limited = u?.limited == true,
                    trouble = codexTrouble(codex),
                )
            } else null
            return Agents(
                leadAgent = if (leads) AgentKind.CODEX else AgentKind.CLAUDE,
                codexTrouble = if (leads) codexTrouble(codex) else null,
                weekOnly = leads && u?.fiveHour == null && u?.sevenDay != null,
                other = other,
            )
        }

        /** What stops Codex until the owner acts: signed out, or the Mac's words for what's wrong; null when nothing does. */
        private fun codexTrouble(c: CodexState?): String? = when {
            c == null -> null
            c.account?.signedIn == false -> CODEX_SIGNED_OUT
            else -> c.problem?.let { oneLine(it).take(TROUBLE_MAX) }?.takeIf { it.isNotEmpty() }
        }

        /**
         * The widgets' view of [live] right now. [live] answers for sessions and usage only once it
         * has a snapshot ([LiveHub.synced]); before that the caller shows what was kept on disk.
         * [fresh]: something keeps the link open (the app on screen, or the background service).
         * [held]: the background connection is meant to keep it open while the app is closed (it's
         * on in Settings and wasn't disconnected from its notification).
         * [answering]: answers on their way from the widget; [done]: answered from the widget, hidden
         * before the Mac confirms.
         */
        fun of(live: LiveHub, fresh: Boolean, held: Boolean, answering: Map<String, Answering>, done: Set<String>): WidgetModel {
            val link = live.link.state
            // Which agent the star follows, and the other's readout while both are in use.
            val who = agents(live.claude, live.codex)
            val conn = when {
                link == LinkState.Unpaired -> return unpaired()
                // Nothing is meant to hold the link: the picture waits for the app, by choice.
                !fresh && !held -> Conn.AppOnly
                // A dropped socket comes back within seconds; only "Offline" is worth saying.
                link == LinkState.Connected || link == LinkState.Reconnecting -> if (fresh) Conn.Live else Conn.Stale
                link == LinkState.Offline -> Conn.Offline
                else -> Conn.Stale
            }
            // A conversation that switched agents is its newest session, as on Home.
            val alive = live.sessions.filter { it.phase != Phase.Ended && it.switchedTo == null }
            val byId = alive.associateBy { it.id }
            // Within a state, an order that holds still: working and waiting sessions newest first
            // by when they started, idle ones by when they last did something (which doesn't move
            // while they're idle). By latest activity, two busy sessions would swap places every
            // few seconds, and the widget would redraw each time.
            val rows = alive
                .sortedWith(
                    compareBy<com.prudnikweb.hypernova.data.Session> { rank(shown(it)) }
                        .thenByDescending { if (shown(it) == SessionState.Idle) it.updatedAt else it.createdAt }
                        .thenBy { it.id },
                )
                .take(MAX_ROWS)
                .map { s ->
                    Row(
                        id = s.id,
                        title = Secrets.mask(s.name),
                        project = s.project,
                        // Busy, it's a tool's line ("Bash: psql …"), masked as a command; idle, the
                        // session's last words, as prose. Cut to the window after masking: the row
                        // shows one line, but TalkBack reads all of it.
                        activity = oneLine(Secrets.shown(s.activity, command = s.state != SessionState.Idle)),
                        state = shown(s),
                        agents = if (s.state == SessionState.Idle) s.agentsRunning else 0,
                        codex = who.both && s.codex,
                    )
                }
            val asks = live.pendingApprovals.filter { it.id !in done }.sortedBy { it.createdAt }
            val first = asks.firstOrNull()
            val ask = first?.let { a ->
                val s = byId[a.sessionId] ?: live.session(a.sessionId)
                val (raw, exact) = detail(a)
                val text = raw.trim()
                // Only what can be shown is masked: a longer command is never whole anyway, and
                // the patterns needn't run over a megabyte of heredoc on the main thread. A
                // question or a plan is prose.
                val masked = Secrets.shown(text, command = a.kind == "tool")
                // Shown exactly as it will run, or it can't be allowed from here: a masked run could
                // hide a payload, and a cut or reordered line hides what follows.
                val whole = exact && answerableText(text) != null
                Ask(
                    id = a.id,
                    sessionId = a.sessionId,
                    kind = a.kind,
                    tool = a.toolName,
                    detail = if (whole) text else oneLine(masked).take(DETAIL_MAX),
                    project = s?.project.orEmpty(),
                    title = s?.name?.let(Secrets::mask).orEmpty(),
                    more = asks.size - 1,
                    whole = whole,
                    expiresAt = a.expiresAt ?: (a.createdAt + AUTO_DENY),
                    answering = answering[a.id],
                )
            }
            // Where the pace leads: the app's projection, kept as the pace and the limit's time.
            val now = System.currentTimeMillis()
            // The leading agent's usage: Codex's windows (no pace) where Codex leads.
            val u = if (who.codexLeads) AgentLead.codexSunUsage(live.codex, now).let {
                // No numbers from Codex yet: no resets either (its placeholders move with the clock,
                // and would redraw the widgets for nothing).
                if (it.known) it else it.copy(fiveHourResetAt = 0L, sevenDayResetAt = 0L)
            } else live.usage
            // Claude's account only where Claude Code is (it leads then): elsewhere it says nothing true.
            val claude = !who.codexLeads && AgentLead.claudeAvailable(live.claude)
            val p5 = UsageProjection.fiveHour(u, now)
            val p7 = UsageProjection.sevenDay(u, now)
            return WidgetModel(
                conn = conn,
                macName = live.mac.name,
                usage = UsageView(
                    fiveHour = u.fiveHour, sevenDay = u.sevenDay,
                    fiveResetAt = u.fiveHourResetAt, sevenResetAt = u.sevenDayResetAt,
                    known = u.known, limited = u.limited, weekLimited = u.weekLimited,
                    fivePace = WidgetMeter.pace(p5), sevenPace = WidgetMeter.pace(p7),
                    fiveLimitAt = WidgetMeter.limitAt(p5, now), sevenLimitAt = WidgetMeter.limitAt(p7, now),
                ),
                working = alive.count { shown(it) == SessionState.Working },
                needs = alive.count { it.state == SessionState.NeedsYou },
                rows = rows,
                ask = ask,
                account = live.account.takeIf { claude && it.needsYou }?.state,
                usingCredits = claude && live.account.state == Account.LIMITED && live.account.limit?.usingCredits == true,
                leadAgent = who.leadAgent,
                codexTrouble = who.codexTrouble,
                weekOnly = who.weekOnly,
                other = who.other,
            )
        }

        /** What an ask shows of its command at most; [Secrets.WINDOW] keeps 200 more behind it. */
        internal const val DETAIL_MAX = 400

        /**
         * The one rule for answering an approval outside the app (the widget's Allow, a
         * notification's Allow): a tool's exact command, shown whole and as it will run. This is
         * that text, or null when it can't be: a question or a plan, a tool whose input isn't one
         * plain line of what runs, a secret that would be masked out of it, more than [max]
         * characters, or characters that hide or reorder what follows.
         */
        fun answerable(a: WApproval, max: Int = DETAIL_MAX): String? {
            if (a.kind != "tool") return null
            val (raw, exact) = detail(a)
            return if (exact) answerableText(raw.trim(), max) else null
        }

        private fun answerableText(text: String, max: Int = DETAIL_MAX): String? =
            text.takeIf { it.isNotEmpty() && it.length <= max && Secrets.shown(it, command = true) == it && plain(it) }

        /** The Mac denies an approval nobody answered after 10 minutes (DECISIONS: Sessions). */
        private const val AUTO_DENY = 10 * 60_000L

        /**
         * A session as the widgets count and draw it, by the one rule ([Session.busy]): one whose
         * main agent is idle while agents it started still run is working, as on the Home cards and
         * in the connection notification, so the widget never reads "All quiet" while agents work.
         * [alive] sessions only: an ended one's agents have ended with it.
         */
        private fun shown(s: com.prudnikweb.hypernova.data.Session): SessionState =
            if (s.busy) SessionState.Working else s.state

        private fun rank(s: SessionState) = when (s) {
            SessionState.NeedsYou -> 0
            SessionState.Working -> 1
            SessionState.Idle -> 2
        }

        /**
         * What the approval is about, as the session screen shows it, and whether that's all of
         * it: a command, or the one path, pattern or address a read-only tool reads. An edit or a
         * new file shows only its path, an agent only its title and other tools only their name:
         * a summary that leaves out what would change, so those open the app instead.
         */
        private fun detail(a: WApproval): Pair<String, Boolean> {
            val i = a.input
            when (a.kind) {
                "question" -> a.questions.firstOrNull()?.question?.let { return it to false }
                "plan" -> a.plan?.lineSequence()?.map { it.trim().trimStart('#', ' ') }?.firstOrNull { it.isNotEmpty() }?.let { return it to false }
            }
            if (a.kind == "tool") {
                val exact = when (a.toolName) {
                    "Bash" -> i["command"]
                    "Read" -> i["file_path"]
                    "Glob" -> i["pattern"]?.let { p -> i["path"]?.let { "$p in $it" } ?: p }
                    "Grep" -> i["pattern"]?.let { p -> "“$p”" + (i["path"]?.let { " in $it" } ?: "") }
                    "WebFetch" -> i["url"]
                    "WebSearch" -> i["query"]
                    else -> null
                }
                if (!exact.isNullOrBlank()) return exact to true
            }
            i["command"]?.let { return it to false }
            val tool = a.toolName
            return (if (a.summary.startsWith("$tool: ")) a.summary.removePrefix("$tool: ") else a.summary) to false
        }

        /**
         * Text that draws as what it is: no control or invisible characters (a tab, a carriage
         * return, a zero-width space, a bidi override), no right-to-left runs that reorder the
         * characters around them, and no combining marks that can stack over others.
         */
        private fun plain(s: String): Boolean = s.codePoints().allMatch { cp ->
            // By code point, not UTF-16 unit: tag characters (U+E0000…) and supplementary
            // right-to-left letters would otherwise pass as halves of a surrogate pair.
            if (cp == '\n'.code) return@allMatch true
            if (cp in INVISIBLE_LETTERS) return@allMatch false
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.PRIVATE_USE, Character.UNASSIGNED,
                Character.SURROGATE,
                -> false
                else -> when (Character.getDirectionality(cp)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> false
                    else -> true
                }
            }
        }

        /** Letters that draw as nothing (Hangul fillers), though Unicode files them as letters. */
        private val INVISIBLE_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

        private val SPACES = Regex("\\s+")

        /** A line that has no room for line breaks: a multi-line command reads as one line. */
        fun oneLine(s: String): String = s.replace(SPACES, " ").trim()
    }
}

/** The widgets' last model, kept in app-private storage for a process that starts cold. */
internal object WidgetStore {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun load(c: Context): WidgetModel? = prefs(c).getString("model", null)?.let { runCatching { WidgetModel.fromJson(JSONObject(it)) }.getOrNull() }

    fun save(c: Context, m: WidgetModel) {
        prefs(c).edit().putString("model", m.copy(ask = m.ask?.copy(answering = null)).toJson().toString()).apply()
    }
}

/**
 * Token-like text replaced by "•••" before anything reaches the home screen, where it sits in
 * plain view for hours. The Mac's rules for session titles (daemon/src/normalize.ts, maskSecrets;
 * keep the two the same), plus curl's and wget's credentials, which only a command line carries.
 * [mask] is for prose (titles, a session's last words), [command] for a command line, where a
 * key's value is a key whatever it looks like. Every repeat is bounded, so a long line stays
 * linear to scan ([SecretsCheck] runs the worst shapes).
 */
internal object Secrets {
    /**
     * The most of one text the widget shows: an ask's [WidgetModel.DETAIL_MAX] and 200 more
     * behind it. [FETCH] reads a quoted string up to this long whole, so within the window its
     * scan never stops inside a long body and misses a "-u" after it.
     */
    const val WINDOW = WidgetModel.DETAIL_MAX + 200

    /**
     * How much more than [WINDOW] is masked, and then dropped: a little more than the longest
     * value a pattern reads whole ([NAMED]'s 400, with its quotes; [USERINFO]'s 200:200). A value
     * the slice cuts off is read as unfinished and left as it is, so it must land in this tail.
     */
    private const val TAIL = 420

    /**
     * [s] masked (as a [command] line or as prose), then cut to at most [WINDOW], back to the
     * last space. Masking comes first: cutting first could split a quoted "-u" value at a space
     * inside it, and the half left over isn't a value any more, so its password stayed in view.
     * Only a slice is masked, so the patterns needn't run over a megabyte of heredoc on the main
     * thread, and the slice's last [TAIL] characters are dropped, since anything cut off there
     * is the half-value that masking can't see.
     */
    fun shown(s: String, command: Boolean): String {
        val slice = s.take(WINDOW + TAIL)
        val masked = mask(slice, command)
        val keep = if (slice.length < s.length) (masked.length - TAIL).coerceIn(0, WINDOW) else minOf(masked.length, WINDOW)
        if (keep == masked.length) return masked
        // Back to the last space, so a cut word doesn't read as a whole one; not back past half.
        val cut = masked.take(keep)
        val space = cut.indexOfLast { it.isWhitespace() }
        return if (space >= keep / 2) cut.take(space) else cut
    }

    private val PATTERNS = listOf(
        Regex("""\b\d{6,}:[A-Za-z0-9_-]{30,}"""), // Telegram bot tokens
        Regex("""\bsk-[A-Za-z0-9_-]{20,}"""), // Anthropic, OpenAI and similar API keys
        Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"""), // GitHub tokens
        Regex("""\bxox[abeprs]-[A-Za-z0-9-]{10,}"""), // Slack tokens
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""), // AWS access key ids
    )
    /** A password in a URL ("https://user:pass@host"). */
    private val USERINFO = Regex("""://[^/\s:@'"]{1,200}:[^@\s'"]{1,200}@""")
    /**
     * One curl or wget command: not past an unquoted ";", "&", "|" or line break, so "docker run
     * -u 1000:1000" after a curl is left alone. Quoted strings count whole, since a ";", "&" or "|"
     * in them ("…?a=1&b=2", "charset=utf-8; …") ends nothing, and so do escaped characters (a line
     * continued with "\"). Inside double quotes an escaped quote doesn't close them (a JSON body,
     * "{\"text\": \"$MSG\"}"), and a quote that can't be closed is read as a plain character: the
     * scan must never stop short of a "-u" further on, which would then show in plain view. A
     * quoted string is read whole up to the whole [WINDOW]: a shorter bound let a long JSON body
     * with a "&" in it end the scan inside it. The repeats are possessive ("+"): nothing after
     * them ever needs a character back, so it's the same match, but the JVM's matcher no longer
     * nests a call per character, which ran out of stack on a long line of quoted strings.
     */
    private val FETCH = Regex("""\b(?:curl|wget)\b(?:"(?:[^"\\]|\\[\s\S]){0,$WINDOW}+"|'[^']{0,$WINDOW}+'|\\[\s\S]|[^;&|\n"'\\]|["']){0,$WINDOW}+""")
    /**
     * Within [FETCH], the value of each flag that carries credentials: curl's "-u user:pass"
     * ("-su user:pass", "-uuser:pass", "--user=…"), "-U" (its proxy's) and "--oauth2-bearer", and
     * wget's "--user". The value is one shell word: plain, quoted or escaped characters, an
     * escaped quote inside double quotes included (-u "admin:p\"w"). Before the u, only curl's
     * flags that take no value can share the dash: in "-XPUT" the U is the method's.
     */
    private val FETCH_FLAG = Regex(
        """((?:\s|\\\n)(?:-[sSfLkvigGIjJlnNOpqRaBZM0-9#]{0,6}[uU](?:\s{1,8})?|--(?:user|proxy-user|oauth2-bearer)(?:\s{1,8}|=)))""" +
            """(?:"(?:[^"\\]|\\[\s\S]){0,200}+"|'[^']{0,200}+'|\\.|[^\s"';&|\\]){1,100}+""",
    )
    private val LONG_RUN = Regex("""[A-Za-z0-9+/=_-]{32,}""")
    private val HEX = Regex("^[0-9a-fA-F]+$")
    /**
     * A value after an obvious key name ("TOKEN=…", "X-Api-Key: …", "\"api_key\": …",
     * "--password …"; the plain word "auth" isn't one, "AUTH_TOKEN", "Authorization" and
     * "BASIC_AUTH" are), or after a bare "Bearer". An Authorization scheme after the name stays
     * with it, so "Authorization: Bot <token>" keeps "Bot". Curly quotes are never part of a value:
     * a Grep pattern the widget shows as “API_KEY=” keeps its closing quote, and a value typed in
     * smart quotes right after the name ("PASSWORD=“s3cretpass”") keeps its opening one with the
     * name, so it's masked as PASSWORD=“•••”. Groups: 1 what stays, 2 the scheme after a name,
     * 3 "Bearer" when it's bare, 4 a quote around the value, 5 the value, which [mask] and
     * [command] judge.
     */
    private val NAMED = Regex(
        """(?i)((?:\b(?:[A-Z0-9_-]{0,40}(?:TOKEN|SECRET|PASSWORD|PASSWD|API[-_]?KEY|AUTHORIZATION)[A-Z0-9_-]{0,40}""" +
            """|[A-Z0-9_-]{0,40}[_-]AUTH(?:[_-][A-Z0-9_-]{0,40})?|AUTH[_-][A-Z0-9_-]{0,40})["']?\s{0,8}[=:]\s{0,8}[“‘]?""" +
            """|(?<![\w-])--(?:token|password|api-key|secret|http-password|proxy-password)(?:=|\s{1,8}))""" +
            """(?:(Bearer|Basic|Token|Bot|Digest|OAuth|ApiKey)\s{1,8})?|\b(Bearer)\s{1,8})(["']?)([^\s"'“”‘’]{1,400})\4""",
    )
    /** Punctuation that ends a sentence rather than a key ("token: abc12345."): left in view. */
    private const val TRAIL = ".,;:!?)]}>"
    /** A name that counts tokens ("tokens: ", "max_tokens=", "inputTokens: "), up to its value. */
    private val TOKENS_NAME = Regex("""(?i)tokens["']?\s{0,8}[=:]\s{0,8}[“‘]?$""")
    /** A count: digits, with "," "." or "_" between them ("1,234,567", "12_000"). */
    private val COUNT = Regex("""[0-9][0-9,._]*""")

    /** [s] with anything token-like masked, for prose: titles, a session's last words, a question. */
    fun mask(s: String): String = mask(s, command = false)

    /**
     * [s] masked as a command line (an approval's command, a tool's activity line): there a key's
     * value is a key, so any value goes but a shell variable ("$TOKEN", "${PASS}", "$(cat key)"),
     * which shows nothing.
     */
    fun command(s: String): String = mask(s, command = true)

    private fun mask(s: String, command: Boolean): String {
        var out = s
        for (re in PATTERNS) out = re.replace(out, "•••")
        out = USERINFO.replace(out, "://•••@")
        out = FETCH.replace(out) { cmd -> FETCH_FLAG.replace(cmd.value) { f -> f.groupValues[1] + "•••" } }
        out = NAMED.replace(out) { m ->
            val value = m.groupValues[5]
            val core = value.trimEnd(*TRAIL.toCharArray())
            val bare = m.groupValues[3].isNotEmpty()
            // After a key's name and an explicit scheme ("Authorization: Token …") the value is a
            // credential by construction, random-looking or not; 8 characters keeps "Bearer
            // header missing" as it is.
            val scheme = m.groupValues[2].isNotEmpty() && core.length >= 8 && core.all(::tokenChar)
            // "tokens: 1,234,567" and "max_tokens=8192" are counts, on a command line too, so
            // token counts in a title stay readable and an API call's JSON body keeps Allow.
            val count = !bare && m.groupValues[2].isEmpty() && TOKENS_NAME.containsMatchIn(m.groupValues[1]) && COUNT.matches(core)
            val hide = !count && (credential(core, bare) || scheme || (command && !bare && core.isNotEmpty() && core[0] != '$'))
            if (hide) m.groupValues[1] + "•••" + value.substring(core.length) else m.value
        }
        return LONG_RUN.replace(out) { m -> if (looksRandom(m.value)) "•••" else m.value }
    }

    /**
     * A value that looks like a credential, not a word: 8 characters or more with a digit among
     * them, or 16 or more token characters (letters, digits, "._~+/=-") in both cases. After a
     * bare "Bearer", 16 token characters with a digit or both cases ("Bearer of bad news" stays).
     */
    private fun credential(v: String, bare: Boolean): Boolean {
        val digit = v.any { it in '0'..'9' }
        val mixed = v.any { it in 'a'..'z' } && v.any { it in 'A'..'Z' }
        val token = v.length >= 16 && v.all(::tokenChar)
        return if (bare) token && (digit || mixed) else (v.length >= 8 && digit) || (token && mixed)
    }

    /** A character a token is made of: a letter, a digit or one of "._~+/=-". */
    private fun tokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._~+/=-"

    /** As the Mac decides it: a run that switches between cases and digits as often as random text does. */
    private fun looksRandom(run: String): Boolean {
        if (HEX.matches(run)) return true
        if (run.none { it in '0'..'9' }) return false
        var switches = 0
        var prev = ' '
        for (ch in run) {
            val c = when (ch) { in '0'..'9' -> 'd'; in 'a'..'z' -> 'l'; in 'A'..'Z' -> 'u'; else -> continue }
            if (prev != ' ' && c != prev) switches++
            prev = c
        }
        return switches * 3 >= run.length
    }
}
