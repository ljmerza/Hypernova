package com.prudnikweb.hypernova.ui.components

import android.content.ClipboardManager
import android.content.Context

/** The clipboard's text, if it holds any (reading it is what Paste asks for). */
fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()
