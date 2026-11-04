// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.voice.SpeakingBar

// The chat's quick wins (docs/DECISIONS.md, "Everyday quick wins"), as the session screen places
// them: the usage strip under the status line, Claude's to-do list and the quick prompts over the
// prompt box, and a message's long-press menu. Each piece lives in its own file beside this one.

/** The quick prompts, while there's nothing in the box and Claude isn't at work. */
@Composable
internal fun ChatQuickPrompts(visible: Boolean, onSend: (String) -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        QuickPromptChips(onSend, Modifier.padding(bottom = 6.dp))
    }
}

/**
 * Beside Interrupt while anything speaks: "Speaking · Stop" with the wave (a message of this chat),
 * else "Stop reading" while the older way reads one of this chat's messages. Leaving the chat stops
 * its own reading.
 */
@Composable
internal fun ReadingPill(items: List<Item>) {
    DisposableEffect(Unit) { onDispose { ReadAloud.stop() } }
    val speaking = Speaker.now
    if (speaking != null) return SpeakingBar(speaking, onStop = { if (speaking.key != null) ReadAloud.stop() else Speaker.stop("phone") })
    val key = ReadAloud.reading ?: return
    if (items.none { it.key == key }) return
    Pill(stringResource(R.string.chat_read_stop), ReadAloud::stop, kind = PillKind.Ghost, height = 40.dp)
}

