package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.strings

/* The chat's clock for what still holds a Resources. */

internal fun ChatClock.scrolled(res: android.content.res.Resources, at: Long, now: Long): String = scrolled(res.strings, at, now)

internal fun ChatClock.exact(res: android.content.res.Resources, at: Long, now: Long): String = exact(res.strings, at, now)

internal fun ChatClock.exactSaid(res: android.content.res.Resources, at: Long, now: Long): String = exactSaid(res.strings, at, now)

internal fun ChatClock.quiet(res: android.content.res.Resources, at: Long, now: Long): String? = quiet(res.strings, at, now)
