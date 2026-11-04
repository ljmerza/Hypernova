package com.prudnikweb.hypernova.ui.components

import android.content.res.Resources
import com.prudnikweb.hypernova.data.UsageWindow
import com.prudnikweb.hypernova.data.strings

/** [usageSaid] from a screen that has the phone's Resources, as formatDuration has. */
fun usageSaid(res: Resources, name: String, p: UsageWindow, resetAt: String? = null): String = usageSaid(res.strings, name, p, resetAt)
