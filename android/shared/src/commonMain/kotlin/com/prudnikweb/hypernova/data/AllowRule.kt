package com.prudnikweb.hypernova.data

/**
 * The one rule for answering an approval away from its chat (a notification's Allow, the widget's, the
 * desktop app's card): a tool's exact command, shown whole and as it will run. It came down from the
 * home screen widget (WidgetModel) so the desktop app runs this very rule and not a copy of it.
 */
object AllowRule {
    /** What a row shows of its command at most; [Secrets.WINDOW] keeps 200 more behind it. */
    const val DETAIL_MAX = 400

    /**
     * This is that text, or null when it can't be: a question or a plan, a tool whose input isn't one
     * plain line of what runs, a secret that would be masked out of it, more than [max] characters,
     * or characters that hide or reorder what follows.
     */
    fun answerable(a: WApproval, max: Int = DETAIL_MAX): String? {
        if (a.kind != "tool") return null
        val (raw, exact) = detail(a)
        return if (exact) exactText(raw.trim(), max) else null
    }

    /** [text] when it can be allowed as it shows ([answerable]'s rule for the text itself), else null. */
    fun exactText(text: String, max: Int = DETAIL_MAX): String? =
        text.takeIf { it.isNotEmpty() && it.length <= max && Secrets.shown(it, command = true) == it && plain(it) }

    /**
     * What the approval is about, as the session screen shows it, and whether that's all of
     * it: a command, or the one path, pattern or address a read-only tool reads. An edit or a
     * new file shows only its path, an agent only its title and other tools only their name:
     * a summary that leaves out what would change, so those open the app instead.
     */
    fun detail(a: WApproval): Pair<String, Boolean> {
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

/**
 * Token-like text replaced by "•••" before anything reaches the home screen, where it sits in
 * plain view for hours. The Mac's rules for session titles (daemon/src/normalize.ts, maskSecrets;
 * keep the two the same), plus curl's and wget's credentials, which only a command line carries.
 * [mask] is for prose (titles, a session's last words), [command] for a command line, where a
 * key's value is a key whatever it looks like. Every repeat is bounded, so a long line stays
 * linear to scan (the app's widget/SecretsCheck.kt runs the worst shapes).
 */
object Secrets {
    /**
     * The most of one text the widget shows: an ask's [AllowRule.DETAIL_MAX] and 200 more
     * behind it. [FETCH] reads a quoted string up to this long whole, so within the window its
     * scan never stops inside a long body and misses a "-u" after it.
     */
    const val WINDOW = AllowRule.DETAIL_MAX + 200

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

    /** [s] masked as a command line, whole: the caller bounds how long it is. */
    fun maskCommand(s: String): String = mask(s, command = true)

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
