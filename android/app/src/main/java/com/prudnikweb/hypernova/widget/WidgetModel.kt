package com.prudnikweb.hypernova.widget

import android.content.Context
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
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

        /** What a row shows of its command at most; [Secrets.WINDOW] keeps 200 more behind it. */
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
            return if (exact) exactText(raw.trim(), max) else null
        }

        /** [text] when it can be allowed as it shows ([answerable]'s rule for the text itself), else null. */
        internal fun exactText(text: String, max: Int = DETAIL_MAX): String? =
            text.takeIf { it.isNotEmpty() && it.length <= max && Secrets.shown(it, command = true) == it && plain(it) }

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
