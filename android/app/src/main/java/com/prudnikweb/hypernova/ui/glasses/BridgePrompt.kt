package com.prudnikweb.hypernova.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.bridge.BridgeAccess
import com.prudnikweb.hypernova.bridge.BridgeNotice
import com.prudnikweb.hypernova.bridge.LensBridge
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * "Allow Hypernova Lens to connect?" in the app, while it's on screen (the same question the
 * notification asks when it isn't: bridge/BridgeNotice.kt). The six-digit code is the one Lens
 * shows on the glasses (each side works it out from the key agreement: bridge/BridgeCrypto.kt), so
 * the owner allows what's on the glasses in front of them, not something else on the phone asking
 * in between. Nothing the asker chose is shown (not its name, not its version: it always says
 * "Hypernova Lens"), so the code is the only number on the screen. Back or a tap outside puts it
 * aside unanswered: it waits in a notification until it runs out. The house pop-up (GlassDialog),
 * whose glass is dense enough that nothing behind reads through. After Allow, the glasses still
 * need a tap to finish: a toast says so, and Settings → Glasses until they do.
 */
@Composable
fun BridgePromptHost() {
    val context = LocalContext.current
    val toast = LocalToast.current
    val asked = LensBridge.pending
    // Put aside with Back or a tap outside: then it waits in a notification instead.
    var aside by remember { mutableStateOf<String?>(null) }
    if (asked == null || asked.id == aside) return
    BridgePrompt(
        asked,
        onAnswer = { allow ->
            LensBridge.answer(asked.id, allow)
            if (allow) toast(context.getString(R.string.glasses_allowed_now))
        },
        onAside = {
            aside = asked.id
            BridgeNotice.ask(context, asked)
        },
    )
}

@Composable
private fun BridgePrompt(r: BridgeAccess.Request, onAnswer: (Boolean) -> Unit, onAside: () -> Unit) {
    // A new ask can take this one's place while the owner looks (its code changes in place):
    // Allow wakes only a moment after each code shows, so a tap meant for the one before can't
    // allow this one; until then it's disabled, as TalkBack says. Deny is never a risk.
    var armed by remember(r.id) { mutableStateOf(false) }
    LaunchedEffect(r.id) {
        delay(BridgeNotice.REPLACED_DELAY_MS)
        armed = true
    }
    // No version here (it's in Settings → Glasses once allowed): the code must be the only number on this screen.
    GlassDialog(
        onDismiss = onAside,
        eyebrow = stringResource(R.string.glasses),
        title = stringResource(R.string.glasses_ask_title),
        tint = HN.accent,
        line = stringResource(R.string.glasses_ask_detail),
        secondary = DialogButton(stringResource(R.string.glasses_deny), { onAnswer(false) }, PillKind.Ghost),
        primary = DialogButton(stringResource(R.string.glasses_allow), { if (armed) onAnswer(true) }, PillKind.Primary, enabled = armed),
        body = {
            // The code, large and spaced, as the glasses show it: six digits, in two threes.
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = r.code.toList().joinToString(" ") },
                horizontalArrangement = Arrangement.Center,
            ) {
                for ((i, ch) in r.code.withIndex()) {
                    if (i > 0) Spacer(Modifier.width(if (i == 3) 14.dp else 6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(HN.accent.copy(alpha = 0.07f))
                            .border(1.dp, HN.accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 8.dp),
                    ) {
                        HText(ch.toString(), style = Type.mono.copy(fontSize = 26.sp, lineHeight = 32.sp), color = HN.accent)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        },
    )
}
