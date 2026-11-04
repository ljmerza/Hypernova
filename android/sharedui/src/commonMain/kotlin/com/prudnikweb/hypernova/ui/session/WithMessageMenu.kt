package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.chat.LocalSelectableText
import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.platform.rememberHaptics

/* A message with its menu, on both systems: what opens it is the system's (UiInput.messageMenu). */

/** The words a message's menu acts on: what was sent, or Claude's reply as written. */
internal fun Item.menuText(): String? = when (this) {
    is Item.User -> text.takeIf { it.isNotBlank() }
    is Item.Assistant -> text.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * A message as the session's chat draws it: with its long-press menu ([onMenu]) instead of text
 * selection, for the owner's messages and Claude's replies.
 */
@Composable
internal fun WithMessageMenu(item: Item, onMenu: ((Item) -> Unit)?, content: @Composable (Modifier) -> Unit) {
    if (onMenu == null || item.menuText() == null) return content(Modifier)
    val haptics = rememberHaptics()
    val label = stringResource(Str.chat_message_actions)
    // One gesture detector per message, kept while a reply streams in (a new one would drop a
    // long-press under way).
    val open by rememberUpdatedState(onMenu)
    val latest by rememberUpdatedState(item)
    val menu = remember(item.key, label) { SharedUi.platform.input.messageMenu(Modifier, label, haptics::longPress) { open?.invoke(latest) } }
    // Where a press is the menu, it can't be a selection too; where a mouse selects and the other button is the menu, it can.
    CompositionLocalProvider(LocalSelectableText provides !SharedUi.platform.input.menuTakesThePress) {
        // The message being read aloud wears the speaking wave at its corner. Always in a box, so
        // the message isn't rebuilt when reading starts or stops.
        Box {
            content(menu)
            if (SharedUi.platform.reading.now == item.key) SharedUi.platform.reading.Mark(Modifier.align(Alignment.TopEnd))
        }
    }
}

