// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.widget

import android.content.Context
import com.prudnikweb.hypernova.data.AllowRule
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Secrets
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.WApproval
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the home screen widget shows: every session that needs the owner, on every paired computer
 * (the one on screen and the ones watched in the background), grouped by computer. Built from plain
 * [Source]s, so it's pure and unit-tested without a hub. Small enough to keep on disk: a widget drawn
 * by a freshly started process shows what it last knew of each computer (to open, not to answer)
 * until that computer is heard from again.
 */
internal data class WidgetModel(
    /** Some computer is paired with this phone (and hasn't forgotten it). */
    val paired: Boolean,
    /** Something keeps the links open (the app on screen, or the background connection): changes arrive as they happen. */
    val live: Boolean,
    /** Some paired computer has been heard from, or what it last said was kept: false only while nothing is known at all. */
    val known: Boolean,
    /** The computers with something waiting, in the switcher's order (the one on screen first); none for a computer with nothing. */
    val groups: List<Group>,
    /** The paired computers that can't be reached now, by name. */
    val offline: List<String> = emptyList(),
    /** When it was last known to be current (0 when it never was); stamped when it's drawn. */
    val updatedAt: Long = 0L,
) {
    /** Everything waiting, on every computer. */
    val count: Int get() = groups.sumOf { it.rows.size }

    /**
     * One computer's sessions that need the owner. [kept]: from disk, not heard from yet in this
     * process; [offline]: it can't be reached now. Either way its rows only open, never answer.
     */
    data class Group(
        val server: String,
        val name: String,
        val rows: List<Row>,
        val kept: Boolean = false,
        val offline: Boolean = false,
    )

    /** What a session waits on. */
    enum class Kind {
        /** A tool's approval (Bash, Edit, WebFetch…). */
        Tool,
        /** Claude Code's own permission dialog in its terminal ([TerminalAsk]), answered through the menu route. */
        Terminal,
        Question,
        Plan,
        /** Needs you with nothing to answer here (a picker in its terminal, a stop for being away): opens. */
        Waiting,
    }

    /**
     * A session that needs the owner, and what it waits on: its oldest approval ([more] others behind
     * it), else Claude Code's dialog in its terminal, else just that it waits.
     */
    data class Row(
        val server: String,
        val sessionId: String,
        /** Its name, masked; "" when it has none. */
        val title: String,
        val project: String,
        val kind: Kind,
        /** The tool ("Bash"), or the terminal dialog's title ("Bash command"); "" for the other kinds. */
        val tool: String,
        /**
         * What it waits on: the command (or the one path, pattern or address a read-only tool reads),
         * the tool's summary, the question, the plan's first line. When it's [whole], exactly as it
         * will run, line breaks and all; otherwise on one line, secrets masked.
         */
        val detail: String,
        /** [detail] is everything being approved, untouched: only then is Allow offered (the notifications' rule). */
        val whole: Boolean,
        /** The approval's id, or the terminal dialog's menu id; null when there's nothing to answer here. */
        val askId: String?,
        /** The terminal dialog's options for Allow and Deny ([TerminalAsk.allow], [TerminalAsk.deny]). */
        val allowChoice: Int = -1,
        val denyChoice: Int = -1,
        /** Other approvals of the same session behind this one. */
        val more: Int = 0,
        /** The order: an approval by when it was asked, anything else by when its session started (it holds still). */
        val since: Long = 0L,
        /** When the approval leaves the phone for nobody answering; 0 if it doesn't. */
        val expiresAt: Long = 0L,
        /** Deny is offered: a tool's approval or the terminal's dialog, on a computer that can take the answer. */
        val deny: Boolean = false,
        /** Allow is offered: as [deny], and the [whole] command shows. */
        val allow: Boolean = false,
        /** This phone is sending an answer to it, or the last try failed. */
        val answering: Answering? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("server", server).put("sessionId", sessionId).put("title", title).put("project", project)
            .put("kind", kind.name).put("tool", tool).put("detail", detail).put("whole", whole).put("askId", askId)
            .put("allowChoice", allowChoice).put("denyChoice", denyChoice).put("more", more).put("since", since).put("expiresAt", expiresAt)

        companion object {
            /** A kept row: it only opens ([deny] and [allow] off) until its computer is heard from. */
            fun fromJson(r: JSONObject) = Row(
                server = r.getString("server"),
                sessionId = r.getString("sessionId"),
                title = r.optString("title"),
                project = r.optString("project"),
                kind = Kind.valueOf(r.getString("kind")),
                tool = r.optString("tool"),
                detail = r.optString("detail"),
                whole = r.optBoolean("whole"),
                askId = r.optString("askId").takeIf { r.has("askId") && !r.isNull("askId") && it.isNotEmpty() },
                allowChoice = r.optInt("allowChoice", -1),
                denyChoice = r.optInt("denyChoice", -1),
                more = r.optInt("more"),
                since = r.optLong("since"),
                expiresAt = r.optLong("expiresAt"),
            )
        }
    }

    enum class Answering { Allowing, Denying, Failed }

    /** One paired computer as its hub has it: what [of] reads. [synced]: it has had a snapshot in this process. */
    data class Source(
        val id: String,
        val name: String,
        val link: LinkState,
        val synced: Boolean,
        val sessions: List<Session>,
        val approvals: List<WApproval>,
    )

    /** Everything but the timestamp: two models that only differ in time look the same on screen. */
    fun sameContent(o: WidgetModel?): Boolean = o != null && copy(updatedAt = 0L) == o.copy(updatedAt = 0L)

    /**
     * What's still true of this model at [now], for drawing: no approval the computer has already
     * let go of for nobody answering. A live model keeps its rows: the computer's own list drops them.
     */
    fun at(now: Long): WidgetModel {
        if (live) return this
        val gs = groups.map { g -> g.copy(rows = g.rows.filter { it.expiresAt !in 1..now }) }.filter { it.rows.isNotEmpty() }
        return if (gs == groups) this else copy(groups = gs)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("paired", paired)
        .put("known", known)
        .put("updatedAt", updatedAt)
        .put("offline", JSONArray(offline))
        .put(
            "groups",
            JSONArray(groups.map { g -> JSONObject().put("server", g.server).put("name", g.name).put("rows", JSONArray(g.rows.map { it.toJson() })) }),
        )

    companion object {
        fun unpaired() = WidgetModel(paired = false, live = false, known = false, groups = emptyList())

        fun fromJson(o: JSONObject): WidgetModel? = runCatching {
            val gs = o.getJSONArray("groups")
            val off = o.optJSONArray("offline") ?: JSONArray()
            WidgetModel(
                paired = o.getBoolean("paired"),
                live = false,
                known = o.optBoolean("known"),
                groups = (0 until gs.length()).map { i ->
                    val g = gs.getJSONObject(i)
                    val rs = g.getJSONArray("rows")
                    Group(g.getString("server"), g.optString("name"), (0 until rs.length()).map { Row.fromJson(rs.getJSONObject(it)) }, kept = true)
                },
                offline = (0 until off.length()).map { off.getString(it) },
                updatedAt = o.optLong("updatedAt"),
            )
        }.getOrNull()

        /**
         * The widget's view of every paired computer right now ([sources], in the switcher's order).
         * A computer that has had its snapshot answers for itself; one that hasn't yet shows what
         * [kept] had for it, to open only. [live]: something keeps the links open. [answering]:
         * answers on their way from the widget; [done]: answered from the widget, hidden before the
         * computer confirms. A computer forgotten by its server (unpaired) isn't shown.
         */
        fun of(
            sources: List<Source>,
            live: Boolean,
            answering: Map<String, Answering> = emptyMap(),
            done: Set<String> = emptySet(),
            kept: WidgetModel? = null,
        ): WidgetModel {
            val paired = sources.filter { it.link != LinkState.Unpaired }
            if (paired.isEmpty()) return unpaired()
            var known = false
            val offline = ArrayList<String>()
            val groups = ArrayList<Group>()
            for (src in paired) {
                val down = src.link == LinkState.Offline
                if (down) offline += src.name
                if (src.synced) {
                    known = true
                    // Offline, the answer couldn't reach it: the rows open the session instead.
                    val rows = rows(src, answering, done, answer = !down)
                    if (rows.isNotEmpty()) groups += Group(src.id, src.name, rows, offline = down)
                } else {
                    val k = kept?.groups?.firstOrNull { it.server == src.id } ?: continue
                    known = true
                    val rows = k.rows.filter { it.askId == null || it.askId !in done }.map { it.copy(deny = false, allow = false, answering = null) }
                    if (rows.isNotEmpty()) groups += Group(src.id, src.name.ifEmpty { k.name }, rows, kept = true, offline = down)
                }
            }
            return WidgetModel(paired = true, live = live, known = known, groups = groups, offline = offline)
        }

        /**
         * One computer's sessions that need the owner, oldest wait first: each session with an
         * approval (its oldest), then each with Claude Code's dialog in its terminal, then any other
         * that needs you. A session whose every approval was just answered here is left out until the
         * computer's list catches up.
         */
        private fun rows(src: Source, answering: Map<String, Answering>, done: Set<String>, answer: Boolean): List<Row> {
            val byId = src.sessions.associateBy { it.id }
            val asked = src.approvals.groupBy { it.sessionId }
            val out = ArrayList<Row>()
            for ((sid, all) in asked) {
                val open = all.filter { it.id !in done }.sortedWith(compareBy<WApproval>({ it.createdAt }, { it.id }))
                val first = open.firstOrNull() ?: continue
                out += approvalRow(src.id, byId[sid], first, open.size - 1, answering[first.id], answer)
            }
            for (s in src.sessions) {
                // A conversation that switched agents is its newest session, as on Home.
                if (s.id in asked || s.phase == Phase.Ended || s.switchedTo != null) continue
                val t = TerminalAsk.of(s)
                when {
                    t != null -> if (t.menuId !in done) out += terminalRow(src.id, s, t, answering[t.menuId], answer)
                    s.state == SessionState.NeedsYou -> out += waitingRow(src.id, s)
                }
            }
            return out.sortedWith(compareBy<Row>({ it.since }, { it.sessionId }))
        }

        private fun title(s: Session?): String = Secrets.mask(s?.name.orEmpty()).trim()

        private fun approvalRow(server: String, s: Session?, a: WApproval, more: Int, answering: Answering?, answer: Boolean): Row {
            val kind = when (a.kind) {
                "question" -> Kind.Question
                "plan" -> Kind.Plan
                else -> Kind.Tool
            }
            val (raw, exact) = detail(a)
            val text = raw.trim()
            // Shown exactly as it will run, or it can't be allowed from here: a masked run could
            // hide a payload, and a cut or reordered line hides what follows. Only what can be shown
            // is masked; a question or a plan is prose.
            val whole = kind == Kind.Tool && exact && exactText(text) != null
            return Row(
                server = server,
                sessionId = a.sessionId,
                title = title(s),
                project = s?.project.orEmpty(),
                kind = kind,
                tool = if (kind == Kind.Tool) a.toolName else "",
                detail = if (whole) text else oneLine(Secrets.shown(text, command = kind == Kind.Tool)).take(DETAIL_MAX),
                whole = whole,
                askId = a.id,
                more = more,
                since = a.createdAt,
                expiresAt = a.expiresAt ?: 0L,
                // Deny for any tool's request, Allow only beside its whole command (as a notification's).
                deny = answer && kind == Kind.Tool,
                allow = answer && whole,
                answering = answering,
            )
        }

        /** Claude Code's dialog in the terminal: its command as the terminal shows it, Deny, and Allow when that's whole. */
        private fun terminalRow(server: String, s: Session, t: TerminalAsk, answering: Answering?, answer: Boolean): Row {
            val cmd = t.command?.trim()?.takeIf { it.isNotEmpty() }
            val whole = cmd != null && exactText(cmd) != null
            val detail = when {
                cmd == null -> oneLine(Secrets.shown(s.menu?.permission?.question ?: t.lines.lastOrNull().orEmpty(), command = false)).take(DETAIL_MAX)
                whole -> cmd
                else -> oneLine(Secrets.shown(cmd, command = true)).take(DETAIL_MAX)
            }
            return Row(
                server = server,
                sessionId = s.id,
                title = title(s),
                project = s.project,
                kind = Kind.Terminal,
                tool = t.tool.orEmpty(),
                detail = detail,
                whole = whole,
                askId = t.menuId,
                allowChoice = t.allow,
                denyChoice = t.deny,
                since = s.createdAt,
                deny = answer,
                allow = answer && whole,
                answering = answering,
            )
        }

        /** Needs you with nothing to answer here: what its terminal's menu asks, else its last line. Opens. */
        private fun waitingRow(server: String, s: Session): Row {
            val m = s.menu
            val said = m?.title?.takeIf { it.isNotBlank() } ?: m?.body?.lastOrNull { it.isNotBlank() }
            return Row(
                server = server,
                sessionId = s.id,
                title = title(s),
                project = s.project,
                kind = Kind.Waiting,
                tool = "",
                detail = oneLine(Secrets.shown(said ?: s.activity, command = said == null)).take(DETAIL_MAX),
                whole = false,
                askId = null,
                since = s.createdAt,
            )
        }

        /** What a row shows of its command at most ([AllowRule.DETAIL_MAX]). */
        internal const val DETAIL_MAX = AllowRule.DETAIL_MAX

        /** The one rule for answering an approval outside the app ([AllowRule.answerable]): the widget's Allow, a notification's Allow. */
        fun answerable(a: WApproval, max: Int = DETAIL_MAX): String? = AllowRule.answerable(a, max)

        /** [AllowRule.exactText]. */
        internal fun exactText(text: String, max: Int = DETAIL_MAX): String? = AllowRule.exactText(text, max)

        /** [AllowRule.detail]. */
        private fun detail(a: WApproval): Pair<String, Boolean> = AllowRule.detail(a)

        /** [AllowRule.oneLine]. */
        fun oneLine(s: String): String = AllowRule.oneLine(s)
    }
}

/** The widget's last model, kept in app-private storage for a process that starts cold. */
internal object WidgetStore {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun load(c: Context): WidgetModel? {
        val p = prefs(c)
        // The sessions-and-usage picture from before the "Needs you" list (2026-10-31): not this shape.
        if (p.contains("model")) p.edit().remove("model").apply()
        return p.getString("needs", null)?.let { runCatching { WidgetModel.fromJson(JSONObject(it)) }.getOrNull() }
    }

    fun save(c: Context, m: WidgetModel) {
        prefs(c).edit().putString("needs", m.toJson().toString()).apply()
    }
}
