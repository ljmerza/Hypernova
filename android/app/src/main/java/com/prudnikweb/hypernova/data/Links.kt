package com.prudnikweb.hypernova.data

/**
 * Links in the chat (owner, 2026-10-30: "every URL in the chat should be tappable"): bare
 * `http(s)://` and `www.` addresses found in text, and where a tap on one goes. Only the web ever
 * opens, and only after the owner confirms it (ui/components/LinkConfirm.kt); a dev server on the
 * Mac (`localhost:5173`) opens as the in-app preview, as its pill does; Hypernova's own ports and
 * any other scheme (`intent:`, `file:`, `javascript:`, `content:`) never open.
 */
object Links {
    /** Where a tap on a link goes. */
    sealed interface Target {
        /** The web, after a confirm: [url] with its scheme ("www.x.org" gets https). */
        data class Web(val url: String) : Target
        /** A dev server on the Mac: the in-app preview of [port]. */
        data class Local(val port: Int) : Target
        /** Nowhere: another scheme, Hypernova's own ports, or not an address at all. */
        data object Never : Target
    }

    /** A link found in text: [range] in it, [url] as written there. */
    data class Found(val range: IntRange, val url: String)

    private val OWN_PORTS = 47321..47323
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]")
    // Not glued to a word, a path or an address before it ("x@www.y", "/www.").
    private val BARE = Regex("""(?<![\w@/.\-])(?:https?://|www\.)[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""^(https?)://([^/?#\s]*)(.*)$""", RegexOption.IGNORE_CASE)
    // What a sentence or Markdown puts after an address, not part of it.
    private const val TRAILING = ".,;:!?*_~'\""

    /** The bare addresses in [text], each trimmed of the punctuation after it ([trim]). */
    fun find(text: String): List<Found> {
        if (!text.contains("://") && !text.contains("www.", ignoreCase = true)) return emptyList()
        return BARE.findAll(text).mapNotNull { m ->
            val url = trim(m.value)
            // "www." alone, or "https://" with nothing after it.
            if (url.length <= 4 || url.endsWith("://") || (url.startsWith("www.", true) && !url.substring(4).contains('.'))) null
            else Found(m.range.first until m.range.first + url.length, url)
        }.toList()
    }

    /**
     * [raw] without what follows an address in prose: ".,;:!?" and quotes, Markdown's "*" and "_",
     * and a closing ")" or "]" only when it has no opening one in the address
     * ("https://en.wikipedia.org/wiki/Foo_(bar)" keeps its own).
     */
    fun trim(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            val c = s.last()
            s = when {
                c in TRAILING -> s.dropLast(1)
                c == ')' && s.count { it == ')' } > s.count { it == '(' } -> s.dropLast(1)
                c == ']' && s.count { it == ']' } > s.count { it == '[' } -> s.dropLast(1)
                else -> return s
            }
        }
        return s
    }

    /** A Markdown link, "[label](url)", that starts at an index in text: [end] is just past it. */
    data class MarkdownLink(val label: String, val url: String, val end: Int)

    private val MD = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")

    /** The Markdown link at [at] in [src], or null when there's none there. */
    fun markdownLink(src: String, at: Int): MarkdownLink? {
        if (at !in src.indices || src[at] != '[') return null
        val m = MD.matchAt(src, at) ?: return null
        return MarkdownLink(m.groupValues[1], m.groupValues[2], m.range.last + 1)
    }

    /** Where [raw] goes when tapped ([Target]). */
    fun target(raw: String): Target {
        val t = raw.trim()
        val url = if (t.startsWith("www.", ignoreCase = true)) "https://$t" else t
        val m = URL.find(url) ?: return Target.Never
        val scheme = m.groupValues[1].lowercase()
        val host = hostOf(m.groupValues[2]) ?: return Target.Never
        if (host.name.lowercase() in LOCAL_HOSTS) {
            val port = host.port ?: if (scheme == "https") 443 else 80
            return if (port in OWN_PORTS) Target.Never else Target.Local(port)
        }
        return Target.Web(url)
    }

    /**
     * [url] in its parts for the confirm, so a lookalike shows: "https://", anything before an "@"
     * (which hides the real host), the host (and port) the browser will go to, then the rest.
     */
    data class Parts(val scheme: String, val userInfo: String, val host: String, val rest: String)

    fun parts(url: String): Parts? {
        val m = URL.find(url) ?: return null
        val authority = m.groupValues[2]
        val at = authority.lastIndexOf('@')
        return Parts(
            scheme = url.substring(0, m.groupValues[1].length + 3),
            userInfo = if (at >= 0) authority.substring(0, at + 1) else "",
            host = if (at >= 0) authority.substring(at + 1) else authority,
            rest = m.groupValues[3],
        )
    }

    private class Host(val name: String, val port: Int?)

    /** The host and port of an authority ("user@host:8080", "[::1]:5173"); null when it has none or a port that isn't one. */
    private fun hostOf(authority: String): Host? {
        val a = authority.substringAfterLast('@')
        val (name, port) = if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0) return null
            a.substring(0, end + 1) to a.substring(end + 1).removePrefix(":").takeIf { it.isNotEmpty() }
        } else {
            a.substringBefore(':') to (if (a.contains(':')) a.substringAfter(':') else null)
        }
        if (name.isEmpty() || name == "[]") return null
        val p = port?.let { it.toIntOrNull()?.takeIf { n -> n in 1..65535 } ?: return null }
        return Host(name, p)
    }
}
