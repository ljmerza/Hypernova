package com.prudnikweb.hypernova.ui.session

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.BlockedCall
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.home.ago
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A session's blocked calls (docs/PROTOCOL.md, "Blocked calls"; owner, 2026-10-29): calls refused
// without him, auto mode's classifier or nobody answering for 10 minutes, each with Allow & retry
// and Dismiss. They live in the session sheet's Permissions part (owner, 2026-10-30: no Chat |
// Blocked switch over the chat); the strip's mark says while any wait (UsageStrip.kt).

/** Lines of a command a row shows. */
private const val TARGET_LINES = 8

/** A blocked call's tag: "mcp__github__create_issue" → "create issue", as the approval card's band says it. */
private fun toolTag(tool: String): String = tool.substringAfterLast("__").replace('_', ' ').ifBlank { tool }

/**
 * The calls, newest first, in the session sheet (a plain column: the sheet scrolls, and a lazy
 * list can't be measured inside it). [onRetried]: once an Allow & retry went out (Claude runs it
 * again in the chat). [onLoaded]: the first answer is in, so the sheet can bring them into view.
 */
@Composable
internal fun BlockedCalls(hub: Hub, session: Session, onRetried: () -> Unit, onLoaded: () -> Unit = {}) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val calls = remember(session.id) { mutableStateListOf<BlockedCall>() }
    var loaded by remember(session.id) { mutableStateOf(false) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    var busy by remember(session.id) { mutableStateOf<String?>(null) }
    var reload by remember(session.id) { mutableLongStateOf(0L) }
    val retried = stringResource(R.string.blocked_retried)
    val loadedNow by rememberUpdatedState(onLoaded)
    fun show(list: List<BlockedCall>) {
        calls.clear()
        calls.addAll(list)
    }
    // The count changes with every call that's refused, retried or dismissed (here or elsewhere): read again.
    LaunchedEffect(session.id, session.blockedCalls, reload) {
        hub.blocked(session.id)
            .onSuccess { show(it); error = null }
            .onFailure { error = it.message }
        if (!loaded) {
            loaded = true
            loadedNow()
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(30_000) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !loaded -> LoadingLine(stringResource(R.string.blocked_loading))
            error != null && calls.isEmpty() -> Column {
                HText(error.orEmpty(), style = Type.body, color = HN.danger)
                Spacer(Modifier.height(12.dp))
                Pill(stringResource(R.string.try_again), { reload++ }, kind = PillKind.Outline, height = 44.dp)
            }
            calls.isEmpty() -> HText(stringResource(R.string.blocked_empty), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp))
            else -> HText(stringResource(R.string.blocked_intro), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
        }
        for (call in calls) key(call.id) {
            BlockedRow(
                call, now, enabled = busy == null,
                onRetry = {
                    busy = call.id
                    appScope.launch {
                        try {
                            hub.retryBlocked(session.id, call.id)
                                .onSuccess { show(it); toast(retried); onRetried() }
                                .onFailure { toast(it.message.orEmpty()) }
                        } finally {
                            busy = null
                        }
                    }
                },
                onDismiss = {
                    busy = call.id
                    appScope.launch {
                        try {
                            hub.dismissBlocked(session.id, call.id)
                                .onSuccess { show(it) }
                                .onFailure { toast(it.message.orEmpty()) }
                        } finally {
                            busy = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One blocked call: its tool, why and when, what it would run in mono, then Dismiss and Allow & retry. */
@Composable
private fun BlockedRow(call: BlockedCall, now: Long, enabled: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    GlassCard(modifier, accent = HN.amber) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(toolTag(call.tool).uppercase(), style = Type.label, color = HN.amber, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                HText(ago(call.at, now), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            HText(
                when {
                    call.reason == BlockedCall.AWAY -> stringResource(R.string.blocked_reason_away)
                    call.why != null -> stringResource(R.string.blocked_reason_auto_why, call.why)
                    else -> stringResource(R.string.blocked_reason_auto)
                },
                style = Type.small, color = HN.inkMuted,
            )
            if (call.target.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                // As the approval card draws a command: "$ " before a shell command, "› " before the rest.
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(color = HN.amber)) { append(if (call.tool == "Bash") "$ " else "› ") }
                    withStyle(SpanStyle(color = HN.ink)) { append(call.target) }
                }
                BasicText(
                    text, style = Type.mono, maxLines = TARGET_LINES, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HN.bg.copy(alpha = 0.9f))
                        .border(1.dp, HN.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.blocked_dismiss), onDismiss, Modifier.weight(1f), kind = PillKind.Ghost, enabled = enabled)
                Pill(
                    stringResource(R.string.blocked_retry),
                    {
                        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                        onRetry()
                    },
                    Modifier.weight(1.2f), kind = PillKind.Primary, enabled = enabled,
                )
            }
        }
    }
}
