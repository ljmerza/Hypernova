package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.wordsNow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.runtime.produceState
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import kotlinx.coroutines.delay
import com.prudnikweb.hypernova.data.ProviderStatus

/* What a session's screen says of it, on both systems. */

/** The Mac isn't linked yet or now (not unpaired): what's on screen may be stale. */
internal fun macAway(hub: Hub): Boolean =
    hub.link.state != LinkState.Connected && hub.link.state != LinkState.Unpaired

/** The link's state in a word, for the status line ("Reconnecting", "Offline"). */
@Composable
internal fun linkWord(state: LinkState): String = stringResource(
    when (state) {
        LinkState.Connecting -> Str.link_connecting
        LinkState.Reconnecting -> Str.link_reconnecting
        LinkState.Unpaired -> Str.link_unpaired
        else -> Str.link_offline
    },
)

/**
 * The session's model and permission mode can be changed from here: not once it has ended, and
 * not for a terminal session the Mac only watches (its terminal isn't shared). A Codex session's
 * are Codex's own, changed over its app-server wherever it was started.
 */
internal fun canChange(s: Session): Boolean =
    s.phase != Phase.Ended && (s.codex || s.term != null || s.origin == "hypernova")

/**
 * The permission mode can be changed from here: as [canChange], and a `claude agents` job's too,
 * terminal or not (the Mac attaches to the job and presses Shift+Tab there, docs/PROTOCOL.md).
 */
internal fun canChangeMode(s: Session): Boolean = canChange(s) || jobModeChangeable(s)

/**
 * The model can be changed from here: as the mode ([canChangeMode]), a `claude agents` job's too
 * (the Mac attaches to the job and types `/model` there, as for the effort).
 */
internal fun canChangeModel(s: Session): Boolean = canChangeMode(s)

/** A `claude agents` job that hasn't ended: its mode, effort and model change through `claude attach`. */
internal fun jobModeChangeable(s: Session): Boolean = s.phase != Phase.Ended && s.agentJob != null

@Composable
internal fun phaseLabel(s: Session): String = when (s.phase) {
    Phase.Starting -> stringResource(Str.state_starting)
    Phase.Error -> stringResource(Str.state_error)
    Phase.Ended -> stringResource(Str.state_ended)
    else -> when (s.state) {
        SessionState.Working -> stringResource(Str.state_working)
        SessionState.NeedsYou -> stringResource(Str.state_needs_you)
        SessionState.Idle -> stringResource(Str.state_idle)
    }
}

/**
 * What stops Claude replying in [s] now, or null. Looked at again when its limit resets (not on
 * a clock), and as the account and providers change.
 */
@Composable
internal fun activeBlock(hub: Hub, s: Session): Blocked? {
    val b = s.blocked ?: return null
    val now by produceState(System.currentTimeMillis(), b) {
        val until = b.resetsAt?.let { it - System.currentTimeMillis() }
        if (until != null && until > 0) {
            kotlinx.coroutines.delay(until + 500)
            value = System.currentTimeMillis()
        }
    }
    return s.activeBlock(hub.account, hub.providers, now)
}

/**
 * The status line's word for a block: PAUSED at a limit, UNAVAILABLE for a model with nothing to
 * wait for, else what the owner has to fix.
 */
@Composable
internal fun blockLabel(b: Blocked, provider: com.prudnikweb.hypernova.data.ProviderStatus? = null): String = stringResource(
    when (b.kind) {
        Blocked.LOGGED_OUT -> Str.state_signed_out
        Blocked.NO_SUBSCRIPTION -> Str.state_no_plan
        Blocked.ON_HOLD -> Str.state_on_hold
        Blocked.NO_CREDIT -> Str.state_no_credit
        // Another provider's own trouble ([provider]: its status).
        Blocked.PROVIDER -> when (provider?.state) {
            "no_credit" -> Str.state_out_of_credits
            "bad_key" -> Str.state_key_refused
            "limited" -> Str.state_rate_limited
            "down" -> Str.state_not_answering
            else -> Str.state_paused
        }
        else -> if (b.unavailable()) Str.state_unavailable else Str.state_paused
    },
)

/** The model a one-model limit rests ("Opus"), from the account or Claude Code's line. */
@Composable
internal fun restingModel(hub: Hub, b: Blocked): String =
    AccountWords.label(wordsNow(), b, hub.account).replace(Regex("\\s+limit$", RegexOption.IGNORE_CASE), "")

/**
 * A model block with no reset: not a limit to wait out but a model this plan can't use, or one
 * that needs usage credits ("Claude Opus is not available with the Claude Pro plan"). Not private:
 * Home's chip for the session can say the same.
 */
internal fun Blocked.unavailable(): Boolean = kind == Blocked.MODEL && resetsAt == null && autoResumeAt == null

/** The other everyday model to switch to: Opus can't reply means Sonnet, and the other way round. */
@Composable
internal fun otherModel(hub: Hub, b: Blocked): String {
    val about = if (b.unavailable()) b.text else restingModel(hub, b)
    return if (about.contains("sonnet", ignoreCase = true) && !about.contains("opus", ignoreCase = true)) "opus" else "sonnet"
}

/** The composer's hint while Claude can't reply: "Claude can't reply until 2:00". */
@Composable
internal fun blockedHint(hub: Hub, s: Session, b: Blocked): String {
    val words = wordsNow()
    return when (b.kind) {
        Blocked.LIMIT -> b.resetsAt?.let { stringResource(Str.hint_blocked_until, AccountWords.clock(words, it)) } ?: stringResource(Str.hint_blocked_limit)
        Blocked.MODEL -> b.resetsAt?.let { stringResource(Str.hint_blocked_model, restingModel(hub, b), AccountWords.clock(words, it)) } ?: stringResource(Str.hint_blocked_model_any)
        Blocked.LOGGED_OUT -> stringResource(Str.hint_blocked_sign_in)
        Blocked.SIGNED_IN -> stringResource(Str.prompt_hint)
        Blocked.NO_SUBSCRIPTION -> stringResource(Str.hint_blocked_plan)
        Blocked.ON_HOLD -> stringResource(Str.hint_blocked_hold)
        Blocked.NO_CREDIT -> stringResource(Str.hint_blocked_credits)
        else -> stringResource(Str.hint_blocked_provider, hub.providers.firstOrNull { it.id == s.providerId }?.name ?: s.model)
    }
}

/**
 * What the empty box says, on both: why a prompt won't go at once, where it goes, why Claude can't reply ([block]), or just what it's for.
 */
@Composable
internal fun chatBoxHint(hub: Hub, s: Session, block: Blocked?): String = when {
    !(hub.link.state == LinkState.Connected) -> stringResource(Str.hint_mac_away_queue)
    // Gone on with the other agent: a prompt here goes there (the send says so).
    s.switchedTo != null -> stringResource(Str.cx_hint_went_on, com.prudnikweb.hypernova.ui.components.agentName(hub.session(s.switchedTo)?.codex ?: !s.codex))
    else -> block?.let { blockedHint(hub, s, it) } ?: stringResource(if (s.phase == Phase.Ended) Str.prompt_hint_ended else Str.prompt_hint)
}
