package com.prudnikweb.hypernova.data

import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one door into the debug log (data/DebugLog.kt): every line, whoever wrote it, passes through here
 * on its way to a file, so a call site that gets it wrong still can't put a secret on disk. It cuts
 * tokens, certificates, keys, pairing codes and links, `Authorization` and cookie headers, ticket URLs,
 * env and header values, query strings, email addresses and paths.
 *
 * What it can't recognise is what a person wrote: prompts, replies, commands, session and project names
 * have no shape. Those stay out by what's asked of it: call sites only ever log ids, sizes and fixed
 * words, an exception's message is left out unless it's the network's own ([throwable]), and
 * DebugLogHubTest plants them in a real feed to check the file.
 */
object DebugScrub {
    const val CUT = "…"
    /** One event, one line: anything longer isn't a line a person reads. */
    const val MAX_LINE = 1_500
    private const val MAX_FRAMES = 40
    private const val MAX_CAUSES = 6
    private const val MAX_KNOWN = 64

    /** The secrets this run has actually held (device tokens, pairing codes): cut wherever they turn up, whatever they look like. */
    private val known = CopyOnWriteArraySet<String>()

    /** [secret] is cut from every line from now on. Too short to be one, it would cut ordinary words. */
    fun remember(secret: String?) {
        if (secret == null || secret.length < 6 || known.size >= MAX_KNOWN) return
        known += secret
    }

    private val pem = Regex("""-----BEGIN[^\n]*?-----[\s\S]*?(?:-----END[^\n]*?-----|$)""")
    private val secretHeader = Regex("""(?im)\b(proxy-authorization|authorization|set-cookie|cookie)\b(["']?\s*[:=]\s*)[^\n]*""")
    private val xHeader = Regex("""(?i)\b(x-[a-z0-9-]+)(["']?\s*:\s*)[^\n]*""")
    private val titleHeader = Regex("""\b(?!Wi-Fi\b)([A-Z][a-z]+(?:-[A-Z][a-z]+)+)(\s*:\s*)[^\n]*""")
    private val url = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/\s?#]*@)?([^/\s?#]*)\S*""")
    private val fileOrData = Regex("""(?i)\b(?:file:/\S*|data:[\w/+.-]+;base64,\S+)""")
    private val query = Regex("""[?&][\w.-]+=[^\s&]*""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+\S+""")
    private val named = Regex(
        """(?i)\b((?:[a-z0-9]+[_.-])*(?:token|secret|password|passwd|pwd|passphrase|api[_-]?key|access[_-]?key|private[_-]?key|credentials?|ticket|session[_-]?key|otp))(["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&}\]]+)""",
    )
    private val code = Regex("""(?i)\b((?:pair(?:ing)?[-_ ]?)?code)(["']?\s*[:=]\s*)(?!\d{1,4}\b)("[^"]*"|'[^']*'|[^\s,;&}\]]+)""")
    private val assignment = Regex("""\b([A-Za-z_][\w.-]*)=("[^"]*"|'[^']*'|\S+)""")
    private val plainValue = Regex("""-?\d+(?:\.\d+)?|true|false""")
    private val shapes = Regex(
        """\b(?:hn[a-z]?_[A-Za-z0-9_-]{4,}|sk-[A-Za-z0-9_-]{8,}|gh[pousr]_\w{8,}|github_pat_\w+|glpat-[\w-]+|npm_\w{8,}|xox[abprs]-[\w-]+|AKIA[0-9A-Z]{12,}|AIza[\w-]{20,}|eyJ[\w-]+\.[\w-]+(?:\.[\w-]+)?)""",
    )
    private val email = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val hexPairs = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){7,}[0-9A-Fa-f]{2}\b""")
    private val windowsPath = Regex("""\b[A-Za-z]:\\\S*""")
    private val absolutePath = Regex("""(?<![\w./:-])~?/(?!v1(?:/|\b)|download/|p/…)[\w.@%+~-]+(?:/[\w.@%+~-]*)+""")
    private val relativePath = Regex("""(?<![\w./:…-])[\w.@%+~-]+(?:/[\w.@%+~-]+)+/?""")
    private val longRun = Regex("""[A-Za-z0-9_+=-]{20,}""")
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val invisible = Regex("""[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+""")
    private val spaces = Regex("""\s+""")

    /** [message] as it may go on disk: nothing secret, on one line, at most [MAX_LINE] characters. */
    fun line(message: String): String {
        var t = message
        for (k in known) if (k in t) t = t.replace(k, CUT)
        t = pem.replace(t, CUT)
        t = secretHeader.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$CUT" }
        t = xHeader.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$CUT" }
        t = titleHeader.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$CUT" }
        t = fileOrData.replace(t, CUT)
        t = url.replace(t) { m -> m.groupValues[1].ifEmpty { CUT } }
        t = query.replace(t, "")
        t = bearer.replace(t) { "${it.groupValues[1]} $CUT" }
        t = named.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$CUT" }
        t = code.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}$CUT" }
        t = assignment.replace(t) { m -> if (plainValue.matches(m.groupValues[2])) m.value else "${m.groupValues[1]}=$CUT" }
        t = shapes.replace(t, CUT)
        t = email.replace(t, CUT)
        t = hexPairs.replace(t, CUT)
        t = windowsPath.replace(t, CUT)
        t = absolutePath.replace(t, CUT)
        t = relativePath.replace(t, CUT)
        t = longRun.replace(t) { m -> if (uuid.matches(m.value) || m.value.startsWith("TLS_") || !looksRandom(m.value)) m.value else CUT }
        t = invisible.replace(t, " ")
        t = spaces.replace(t, " ").trim()
        return t.take(MAX_LINE)
    }

    /** Letters and digits mixed. A long hyphenated phrase in plain letters is words, not a key. */
    private fun looksRandom(s: String): Boolean = s.any { it.isDigit() } && s.any { it.isLetter() }

    private val segment = Regex("""[A-Za-z0-9._~-]{1,64}""")

    /**
     * A request's route with its ids, from a whole URL or a path, and nothing else of it: never its query
     * (`?path=`, `?ticket=`), and only segments that can be a route's word or an id. A preview's link
     * (`/p/<token>/<file>`) holds both a credential and a file's name, so all of it goes.
     */
    fun route(urlOrPath: String): String {
        val noHost = urlOrPath.replace(Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*"""), "")
        val path = noHost.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        val parts = path.trimStart('/').split('/')
        if (parts.first() == "p") return "/p/$CUT"
        return "/" + parts.joinToString("/") { p -> if (p.isEmpty() || segment.matches(p)) p else CUT }
    }

    /** A fixed word from a wire (a feed event's type, an error's code), or null if it isn't one. */
    fun word(s: String?): String? = s?.takeIf { Regex("""[A-Za-z0-9_.:-]{1,48}""").matches(it) }

    /** An id from a wire, or null if it doesn't look like one. */
    fun id(s: String?): String? = s?.takeIf { segment.matches(it) }

    /**
     * Where an exception came from, for a log. Only the network's own exceptions say why in words that are
     * about the network (`Failed to connect to /192.168.0.1:47323`). Any other's message can quote what it
     * failed on (org.json quotes the text it couldn't parse), so it's counted and left out, and with
     * [messages] false (a crash: stack traces only) they all are.
     */
    fun throwable(t: Throwable, messages: Boolean = true): List<String> {
        val out = ArrayList<String>()
        var cur: Throwable? = t
        val seen = ArrayList<Throwable>()
        var depth = 0
        while (cur != null && depth <= MAX_CAUSES && seen.none { it === cur }) {
            seen += cur
            out += (if (depth == 0) "" else "Caused by: ") + titleOf(cur, messages)
            val frames = cur.stackTrace
            for (f in frames.take(MAX_FRAMES)) out += frame(f)
            if (frames.size > MAX_FRAMES) out += "    ... ${frames.size - MAX_FRAMES} more"
            cur = cur.cause
            depth++
        }
        return out
    }

    private fun titleOf(t: Throwable, messages: Boolean): String {
        val name = t.javaClass.name
        val message = t.message
        return when {
            t is ApiException && messages -> line("$name: ${t.problem}, status ${t.status}${word(t.code)?.let { ", $it" }.orEmpty()}")
            message.isNullOrEmpty() -> line(name)
            messages && aboutTheNetwork(name) -> line("$name: $message")
            else -> line("$name (its message left out, ${message.length} characters)")
        }
    }

    private fun aboutTheNetwork(name: String): Boolean =
        name.startsWith("java.net.") || name.startsWith("javax.net.ssl.") || name.startsWith("java.security.cert.") || name == "java.io.EOFException"

    private val notFrame = Regex("""[^\w.$<>:()-]""")

    /** A stack frame: where in the code, which is no secret. It isn't free text, so it keeps its long names. */
    private fun frame(f: StackTraceElement): String =
        "    at " + notFrame.replace("${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})", "_")
}
