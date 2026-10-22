package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderStatus
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.data.Resume
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.openLink
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.home.continueLabel
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

// Carrying on after a limit, and other providers' trouble, on the session screen
// (docs/PROTOCOL.md, "Auto-continue"; docs/specs/spec-providers.md, §6.1). BlockedCard hands these
// a session whose block says whether it carries on by itself, or that waits on another provider.

private const val OPENROUTER_CREDITS = "https://openrouter.ai/settings/credits"

/** The card takes this block: another provider's, or one that's over and waits for Continue. */
internal fun Blocked.takenByLimitCard(): Boolean = kind == Blocked.PROVIDER || resume?.ready == true

/**
 * Whether the session carries on by itself, on the card's mono line: "Continues by itself at 2:00"
 * with Don't at its end, or "Back at 2:00" with "Continue automatically at 2:00" under it. The pill
 * says "Turning off…" while it's asked; a failure toasts and the pill returns to how it was. An
 * observed terminal (not shared) shows its state with "· in the terminal", and no switch.
 */
@Composable
internal fun ResumeLine(hub: Hub, s: Session, r: Resume, amber: Boolean, resetsAt: Long?, onMoved: (String) -> Unit = {}) {
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    val now = System.currentTimeMillis()
    // The state asked for, while the Mac (or Claude Code) is on it.
    var asking by remember(s.id, r.state) { mutableStateOf<Boolean?>(null) }
    var going by remember(s.id, r.state) { mutableStateOf(false) }
    val failed = stringResource(R.string.resume_failed)
    fun flip(on: Boolean) {
        if (asking != null) return
        asking = on
        appScope.launch {
            hub.autoContinue(s.id, on).onFailure { toast(it.message?.takeIf { m -> m.isNotBlank() } ?: failed) }
            asking = null
        }
    }
    val tone = if (amber) HN.amber else HN.accentDim
    val inTerminal = r.byClaude && !r.canCancel && !r.canArm
    fun observed(text: String) = if (inTerminal) context.getString(R.string.resume_in_terminal, text) else text
    when {
        r.armed -> Column {
            val text = r.at?.let { if (it > now) context.getString(R.string.resume_carries_on, AccountWords.atClock(context, it, now)) else null }
                ?: context.getString(R.string.resume_carries_on_soon)
            HText(observed(text), style = Type.monoSmall, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Both ways out of waiting (owner, 2026-10-16): leave it, or go now. Continue now is the
            // ordinary continue after a limit: nothing is interrupted, and it works for terminal and
            // SDK sessions alike.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.canCancel) Pill(stringResource(if (asking == false) R.string.resume_turning_off else R.string.resume_dont), { flip(false) }, kind = PillKind.Ghost, height = 32.dp)
                Pill(
                    stringResource(if (going) R.string.resume_continuing else R.string.resume_continue_now),
                    {
                        if (!going) {
                            going = true
                            appScope.launch {
                                hub.continueAfterLimit(s.id).onSuccess(onMoved).onFailure { toast(it.message?.takeIf { m -> m.isNotBlank() } ?: failed) }
                                going = false
                            }
                        }
                    },
                    kind = PillKind.Outline, height = 32.dp, leading = Glyph.Play,
                )
            }
        }
        r.off -> Column {
            val back = (r.at ?: resetsAt)?.takeIf { it > now }
            if (back != null) HText(observed(context.getString(R.string.resume_back, AccountWords.atClock(context, back, now))), style = Type.monoSmall, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
            whyLine(r.why)?.let {
                Spacer(Modifier.height(2.dp))
                HText(it, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (r.canArm) {
                Spacer(Modifier.height(10.dp))
                val label = when {
                    asking == true -> stringResource(R.string.resume_turning_on)
                    back != null -> stringResource(R.string.resume_arm, AccountWords.atClock(context, back, now))
                    else -> stringResource(R.string.resume_arm_plain)
                }
                Pill(label, { flip(true) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Reload)
            }
        }
    }
}

/** Why it won't carry on by itself, when the owner should know: over a day away, hit again and again, the setting, Claude Code closed. */
@Composable
private fun whyLine(why: String?): String? = when (why) {
    "far" -> stringResource(R.string.resume_why_far)
    "repeated" -> stringResource(R.string.resume_why_repeated)
    "setting" -> stringResource(R.string.resume_why_setting)
    "exited" -> stringResource(R.string.resume_why_exited)
    else -> null
}

/**
 * The card for a session another provider stops, or one whose limit is over and that waits for
 * Continue. The title is the provider's state ("OpenRouter · out of credits", amber when it needs
 * the owner); the mono line, what's left or when it carries on; then the ways on: Add credits, Try
 * again, Fix the key, and the conversation on another provider. [other]: a provider it could go on
 * with now. [onMoved]: the session that carries on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitCard(hub: Hub, s: Session, b: Blocked, other: Provider?, onMoved: (String) -> Unit) {
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    val opener = LocalOpener.current
    val now = System.currentTimeMillis()
    var busy by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Result<String>) {
        if (busy) return
        busy = true
        appScope.launch {
            block().onSuccess(onMoved).onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    }
    val provider = hub.providers.firstOrNull { it.id == s.providerId }
    val name = provider?.name ?: s.model
    val st = provider?.status?.takeIf { b.kind == Blocked.PROVIDER }
    val r = b.resume
    val ready = r?.ready == true
    val needsYou = !ready && (ProviderWords.needsYou(st) || (st == null && r == null))
    // What stopped it, back: credit (its first words were about credit), or the provider again.
    val creditBack = ready && b.kind == Blocked.PROVIDER && Regex("\\b402\\b|credit", RegexOption.IGNORE_CASE).containsMatchIn(b.text)
    val title = when {
        ready && b.kind == Blocked.PROVIDER -> stringResource(if (creditBack) R.string.provider_credit_back_title else R.string.provider_back_title, name)
        ready && r?.why == "slept" -> stringResource(R.string.resume_ready_slept)
        ready && r?.why == "dropped" -> stringResource(R.string.resume_ready_dropped)
        ready -> stringResource(R.string.resume_ready_limit)
        else -> ProviderWords.title(context, name, st)
    }
    val balance = ProviderWords.balance(context, provider?.balance, provider?.status)
    val line: String? = when {
        ready -> balance?.takeIf { b.kind == Blocked.PROVIDER } ?: r?.at?.let { stringResource(R.string.resume_ready_line, AccountWords.clock(context, it, now)) }
        r != null && (r.armed || r.off) && r.why != "no_reset" -> null // the resume line says it
        st?.state == ProviderStatus.NO_CREDIT -> balance ?: st.text.takeIf { it.isNotBlank() }
        st?.state == ProviderStatus.BAD_KEY -> stringResource(R.string.provider_key_line)
        st?.state == ProviderStatus.DOWN -> stringResource(R.string.provider_down_line)
        st?.state == ProviderStatus.LIMITED -> stringResource(R.string.provider_no_reset)
        else -> b.text.takeIf { it.isNotBlank() }
    }
    GlassCard(Modifier.fillMaxWidth(), accent = if (needsYou) HN.amber else HN.accent) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            HText(title, style = Type.heading, color = HN.ink)
            if (line != null) {
                Spacer(Modifier.height(4.dp))
                HText(line, style = Type.monoSmall, color = if (needsYou) HN.amber else HN.accentDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!ready && r != null && (r.armed || r.off) && r.why != "no_reset") {
                Spacer(Modifier.height(4.dp))
                ResumeLine(hub, s, r, amber = needsYou, resetsAt = st?.resetsAt ?: b.resetsAt, onMoved = onMoved)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready) {
                    Pill(stringResource(R.string.resume_continue), { act { hub.continueAfterLimit(s.id) } }, kind = PillKind.Primary, height = 40.dp, leading = Glyph.Play)
                    return@FlowRow
                }
                when (st?.state) {
                    ProviderStatus.NO_CREDIT -> {
                        if (provider?.kind == "openrouter") Pill(stringResource(R.string.provider_add_credits), { openLink(context, OPENROUTER_CREDITS) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Globe)
                        // Topped up: the Mac can't always tell (the account's own credit isn't readable), so the owner can.
                        Pill(stringResource(R.string.try_again), { act { hub.continueAfterLimit(s.id) } }, kind = PillKind.Ghost, height = 40.dp)
                    }
                    ProviderStatus.BAD_KEY -> Pill(stringResource(R.string.provider_fix_key), opener.providers, kind = PillKind.Outline, height = 40.dp)
                    ProviderStatus.DOWN, ProviderStatus.LIMITED -> if (r == null || r.why == "no_reset") {
                        Pill(stringResource(R.string.try_again), { act { hub.continueAfterLimit(s.id) } }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Reload)
                    }
                }
                if (other != null) Pill(continueLabel(other), { act { hub.switchProvider(s.id, other.id) } }, kind = if (needsYou) PillKind.Primary else PillKind.Outline, height = 40.dp)
            }
        }
    }
}
