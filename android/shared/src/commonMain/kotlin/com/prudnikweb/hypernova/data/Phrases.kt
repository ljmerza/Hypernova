package com.prudnikweb.hypernova.data

/**
 * Words for text that can't draw (notifications): fields read as a natural phrase rather than
 * "·"-joined. Pure, unit-tested.
 */
object Phrases {
    /** "a", "a and b", "a, b and c": [and] is the language's " and ". Blank parts are left out. */
    fun list(parts: List<String>, and: String): String {
        val p = parts.filter { it.isNotBlank() }
        return when (p.size) {
            0 -> ""
            1 -> p[0]
            else -> p.dropLast(1).joinToString(", ") + and + p.last()
        }
    }
}
