package com.prudnikweb.hypernova.data

/**
 * When to try reaching the server again after the [attempt]th failure in a row (owner,
 * 2026-10-31): the first try again goes at once, then one every 30 s, on screen or behind it.
 * [spread] (0.9 to 1.0) keeps the tries from falling in step with anything else, never past 30 s.
 */
object Retry {
    const val EVERY = 30_000L

    fun wait(attempt: Int, spread: Double): Long =
        if (attempt <= 1) 0L else (EVERY * spread.coerceIn(0.0, 1.0)).toLong()
}
