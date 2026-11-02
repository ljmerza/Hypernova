package com.prudnikweb.hypernova.data

/**
 * A model's name as its chip shows it (ui/components/SessionMeta.kt): short, without the provider's
 * prefix, "claude-" or the release date, a Claude id as its family and version ("Opus 4.5"), a
 * million-token window ("[1m]") as a 1M mark. Pure, unit-tested.
 */
object ModelNames {
    private val DATE = Regex("-\\d{8}$")
    private val ONE_M = Regex("\\[1m]$", RegexOption.IGNORE_CASE)
    private const val FAMILIES = "opus|sonnet|haiku|fable"
    /** "opus-4-5", "sonnet-4.5", "haiku-4": Claude's ids since the 4 models. */
    private val FAMILY_FIRST = Regex("^($FAMILIES)-(\\d+)(?:[-.](\\d{1,2}))?$", RegexOption.IGNORE_CASE)
    /** "3-5-sonnet", "3-opus": the older ids, version first. */
    private val VERSION_FIRST = Regex("^(\\d+)(?:[-.](\\d{1,2}))?-($FAMILIES)$", RegexOption.IGNORE_CASE)

    /**
     * "opus" → "opus", "Opus 5.5" → "Opus 5.5", "openai/gpt-5" → "gpt-5",
     * "anthropic/claude-sonnet-4.5" → "Sonnet 4.5", "claude-opus-4-5-20251101" → "Opus 4.5",
     * "claude-opus-4-6[1m]" → "Opus 4.6 1M", "claude-3-5-sonnet-20241022" → "Sonnet 3.5".
     */
    fun chip(model: String): String {
        val raw = model.trim()
        var name = raw.substringAfterLast('/')
        val million = ONE_M.containsMatchIn(name)
        name = name.replace(ONE_M, "")
        if (name.startsWith("claude-", ignoreCase = true) && name.length > 7) name = name.substring(7)
        name = pretty(name.replace(DATE, ""))
        if (name.isBlank()) return raw
        return if (million) "$name 1M" else name
    }

    private fun pretty(id: String): String {
        FAMILY_FIRST.matchEntire(id)?.let { m ->
            val (family, major, minor) = m.destructured
            return version(family, major, minor)
        }
        VERSION_FIRST.matchEntire(id)?.let { m ->
            val (major, minor, family) = m.destructured
            return version(family, major, minor)
        }
        return id
    }

    private fun version(family: String, major: String, minor: String) =
        family.lowercase().replaceFirstChar { it.uppercase() } + " " + major + (if (minor.isNotEmpty()) ".$minor" else "")

    /**
     * Just the family, for a chip with little room: "Opus 5.5" → "Opus", "sonnet-4.5" → "Sonnet",
     * "gpt-5" → "gpt". Null when there's nothing shorter to say ("opus").
     */
    fun family(model: String): String? {
        val name = chip(model)
        val space = name.indexOf(' ')
        val cut = if (space > 0) space else Regex("-\\d").find(name)?.range?.first ?: -1
        if (cut <= 0) return null
        return name.substring(0, cut).takeIf { it.isNotBlank() }
    }

    /**
     * A model id as it compares: lower case, without the provider's prefix, "claude-", the date or
     * the 1M mark, dots as dashes ("anthropic/claude-sonnet-4.5" and "claude-sonnet-4-5-20250929"
     * are both "sonnet-4-5").
     */
    fun key(model: String): String = model.trim().substringAfterLast('/').lowercase()
        .replace(ONE_M, "").removePrefix("claude-").replace(DATE, "").replace('.', '-')

    /**
     * True when [a] and [b] name the same model: the same id, or an alias and an id of its family
     * ("opus" and "claude-opus-5-5").
     */
    fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = key(a)
        val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        fun alias(s: String) = s.none(Char::isDigit) && !s.contains('-')
        return alias(x) && y.startsWith("$x-") || alias(y) && x.startsWith("$y-")
    }
}
