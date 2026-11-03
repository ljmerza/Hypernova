package com.prudnikweb.hypernova.ui.components

import android.content.res.Resources
import com.prudnikweb.hypernova.R

/** "Model: Opus. Permission mode: ask first." */
fun modelModeSaid(res: Resources, model: String, mode: String): String =
    res.getString(R.string.meta_model_said, model) + " " + res.getString(R.string.meta_mode_said, mode)

/** "Active 4 minutes ago." */
fun sessionSaid(res: Resources, updatedAt: Long, now: Long): String {
    val s = ((now - updatedAt) / 1000).coerceAtLeast(0)
    val ago = when {
        s < 45 -> res.getString(R.string.meta_ago_now)
        s < 3600 -> maxOf(1L, s / 60).toInt().let { res.getQuantityString(R.plurals.meta_ago_minutes, it, it) }
        s < 86400 -> (s / 3600).toInt().let { res.getQuantityString(R.plurals.meta_ago_hours, it, it) }
        else -> (s / 86400).toInt().let { res.getQuantityString(R.plurals.meta_ago_days, it, it) }
    }
    return res.getString(R.string.meta_session_said, ago)
}
