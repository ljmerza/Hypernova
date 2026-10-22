package com.prudnikweb.hypernova.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.TsPhase
import com.prudnikweb.hypernova.data.Tsnet
import com.prudnikweb.hypernova.data.TsnetPolicy
import com.prudnikweb.hypernova.data.TsnetStatus
import com.prudnikweb.hypernova.data.openLink
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The app's own Tailscale node (docs/PROTOCOL.md, "Tailscale built in"), one card in every state
 * ([TsnetPolicy.phase]): its state as a chip on the seam, and what to do now. Not signed in: Sign in
 * (the link opens in the browser). Opened but not finished there (Tailscale's page ends with
 * Connect): Open the sign-in again. Signed in on a tailnet that approves new devices: approve it
 * in the admin console. Coming up: a turning planet and why. Running: the account and this phone's
 * name as chips, and Sign out, or (on the pairing screen) nothing, since the pairing goes on.
 * Shown after pairing (the pairing screen) and in Settings ([quietWhenOff] there while the computer
 * is reached another way, so Off is a state, not a call to action). [account]: the account the computer
 * signed in with, so the user knows which one to use. [controlUrl]: null for tailscale.com, a
 * Headscale URL for self-hosters.
 */
@Composable
fun TailscaleSignInCard(account: String?, controlUrl: String?, modifier: Modifier = Modifier, onSignedIn: (() -> Unit)? = null, quietWhenOff: Boolean = false) {
    val context = LocalContext.current
    val status = Tsnet.status
    val phase = TsnetPolicy.phase(Tsnet.available, status, Tsnet.signInOpened)
    // Once running, tell whoever asked (the pairing screen tries again).
    LaunchedEffect(status.running) { if (status.running) onSignedIn?.invoke() }
    val phone = phoneName(status)
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Seam(
                stringResource(R.string.ts_label),
                activity = when (phase) {
                    TsPhase.Starting, TsPhase.Connecting -> Activity.Live
                    TsPhase.NeedsLogin, TsPhase.Finishing, TsPhase.Again, TsPhase.NeedsApproval, TsPhase.Failed -> Activity.Alert
                    else -> Activity.Rest
                },
                trailContent = if (phase == TsPhase.Unavailable) null else ({ TsStateChip(phase) }),
            )
            Spacer(Modifier.height(12.dp))
            // A computer's own control server is named in every state, not only once signed in.
            controlUrl?.let {
                HText(stringResource(R.string.ts_control, it), style = Type.small, color = HN.amber)
                Spacer(Modifier.height(8.dp))
            }
            // The node asks for its sign-in link by itself once it runs (android/tsnet `askForLink`).
            val startOver = { Tsnet.restart(context, controlUrl) }
            when (phase) {
                TsPhase.Unavailable -> HText(stringResource(R.string.ts_unavailable), style = Type.small, color = HN.inkMuted)
                TsPhase.Running -> {
                    TsIdentity(status, phone)
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.ts_sign_out), { Tsnet.logout() }, kind = PillKind.Ghost, height = 40.dp)
                }
                // Off while the computer is reached another way (its own network, the Tailscale app):
                // the state and a quiet way to turn it on, not a call to action.
                TsPhase.Off -> if (quietWhenOff) {
                    HText(stringResource(R.string.ts_off_body), style = Type.small, color = HN.inkMuted)
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.ts_sign_in), startOver, kind = PillKind.Ghost, height = 40.dp)
                } else Action(
                    stringResource(R.string.ts_off_title), stringResource(R.string.ts_off_body),
                ) { Pill(stringResource(R.string.ts_sign_in), startOver, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp) }
                TsPhase.NeedsLogin -> Action(
                    stringResource(R.string.ts_sign_in_title),
                    stringResource(R.string.ts_sign_in_body) + (account?.let { " " + stringResource(R.string.ts_sign_in_as, it) } ?: ""),
                ) { Pill(stringResource(R.string.ts_sign_in), { Tsnet.openSignIn(context) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp) }
                // Back from the browser with the node still waiting on that link: not finished there.
                TsPhase.Finishing -> Action(
                    stringResource(R.string.ts_finish_title),
                    if (controlUrl == null) stringResource(R.string.ts_finish_body, phone) else stringResource(R.string.ts_finish_body_custom),
                ) { Pill(stringResource(R.string.ts_open_again), { Tsnet.openSignIn(context) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp) }
                // The node started over while the browser had the sign-in: its new link needs one more pass.
                TsPhase.Again -> Action(
                    stringResource(R.string.ts_again_title), stringResource(R.string.ts_again_body),
                ) { Pill(stringResource(R.string.ts_sign_in), { Tsnet.openSignIn(context) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp) }
                TsPhase.NeedsApproval -> {
                    TsIdentity(status, phone)
                    Spacer(Modifier.height(12.dp))
                    Action(
                        stringResource(R.string.ts_approval_title),
                        if (controlUrl == null) stringResource(R.string.ts_approval_body, phone) else stringResource(R.string.ts_approval_body_custom, phone),
                    ) {
                        if (controlUrl == null) Pill(stringResource(R.string.ts_open_admin), { openLink(context, Tsnet.ADMIN_MACHINES) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp, leading = Glyph.Globe)
                    }
                }
                TsPhase.Starting, TsPhase.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Planet(Activity.Live)
                        Spacer(Modifier.width(10.dp))
                        HText(
                            stringResource(
                                when {
                                    phase == TsPhase.Connecting -> R.string.ts_connecting
                                    status.needsLogin -> R.string.ts_getting_link
                                    else -> R.string.ts_starting
                                },
                            ),
                            style = Type.small, color = HN.inkMuted,
                        )
                    }
                    status.error?.let {
                        Spacer(Modifier.height(6.dp))
                        HText(it, style = Type.small, color = HN.amber)
                    }
                }
                TsPhase.Failed -> {
                    status.error?.let { HText(it, style = Type.small, color = HN.amber) }
                    Spacer(Modifier.height(14.dp))
                    Pill(stringResource(R.string.try_again), startOver, kind = PillKind.Outline, height = 44.dp)
                }
            }
        }
    }
}

/** A title, a line under it, and the step's button. */
@Composable
private fun Action(title: String, body: String, actions: @Composable () -> Unit) {
    HText(title, style = Type.heading, color = HN.ink)
    Spacer(Modifier.height(4.dp))
    HText(body, style = Type.small, color = HN.inkMuted)
    Spacer(Modifier.height(14.dp))
    actions()
}

/** This phone's name on the tailnet ("hypernova-pixel-9"), the first part of its MagicDNS name, or the name it asks for. */
private fun phoneName(s: TsnetStatus): String = s.dnsName?.substringBefore('.')?.takeIf { it.isNotEmpty() } ?: Tsnet.hostname()

/** The node's state as a chip: mint once connected, amber while it waits on the user, turning while it comes up. */
@Composable
fun TsStateChip(phase: TsPhase, modifier: Modifier = Modifier) {
    val (word, color) = when (phase) {
        TsPhase.Running -> R.string.ts_chip_connected to HN.accent
        TsPhase.NeedsLogin -> R.string.ts_chip_not_signed_in to HN.amber
        TsPhase.Finishing -> R.string.ts_chip_finishing to HN.amber
        TsPhase.Again -> R.string.ts_chip_again to HN.amber
        TsPhase.NeedsApproval -> R.string.ts_chip_approval to HN.amber
        TsPhase.Starting -> R.string.ts_chip_starting to HN.accentDim
        TsPhase.Connecting -> R.string.ts_chip_connecting to HN.accentDim
        TsPhase.Failed -> R.string.ts_chip_failed to HN.danger
        TsPhase.Off, TsPhase.Unavailable -> R.string.ts_chip_off to HN.inkDim
    }
    StatusChip(stringResource(word), color, modifier, live = phase == TsPhase.Starting || phase == TsPhase.Connecting)
}

/**
 * Who the node is, drawn: the account after a person glyph and this phone's tailnet name after a
 * phone glyph, as quiet chips (not "you@… · hypernova-pixel-9"). TalkBack hears one sentence.
 */
@Composable
private fun TsIdentity(s: TsnetStatus, phone: String) {
    val account = s.account?.takeIf { it.isNotBlank() }
    val said = if (account != null) stringResource(R.string.ts_said_connected, account, s.dnsName ?: phone) else stringResource(R.string.ts_said_state, phone)
    FlowRow(
        Modifier.clearAndSetSemantics { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (account != null) IdChip(TsGlyph.Person, account)
        IdChip(TsGlyph.Phone, phone)
    }
}

/** A quiet identity chip: neutral frosted glass (the model chip's family), a 12dp glyph and a name in mono. */
@Composable
private fun IdChip(glyph: TsGlyph, text: String, color: Color = HN.inkMuted) {
    Row(
        Modifier
            .frostedGlass(null, glowX = 0.dp)
            .heightIn(min = 26.dp)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TsIcon(glyph, HN.inkDim)
        Spacer(Modifier.width(6.dp))
        HText(text, style = Type.monoSmall, color = color, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

/**
 * Which route the app uses now ([Mac.via]), drawn: a capsule with its glyph and word. Local: the
 * home network's arcs. The built-in node: the tailnet's three joined points, TAILSCALE and a quieter
 * BUILT IN. The Tailscale app: the same points, TAILSCALE APP. A remote address: the globe.
 */
@Composable
fun RouteChip(via: String, modifier: Modifier = Modifier) {
    val said = stringResource(
        when (via) {
            Mac.VIA_LOCAL -> R.string.route_said_local
            Mac.VIA_BUILT_IN -> R.string.route_said_built_in
            Mac.VIA_TAILSCALE -> R.string.route_said_app
            else -> R.string.route_said_remote
        },
    )
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = said }
            .frostedGlass(if (via == Mac.VIA_REMOTE) null else HN.accent, glowX = 14.dp)
            .heightIn(min = 26.dp)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val caps = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.6f)
        when (via) {
            Mac.VIA_LOCAL -> TsIcon(TsGlyph.Home, HN.accent)
            Mac.VIA_BUILT_IN, Mac.VIA_TAILSCALE -> TsIcon(TsGlyph.Tailnet, HN.accent)
            else -> Icon(Glyph.Globe, size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
        }
        Spacer(Modifier.width(6.dp))
        val word = when (via) {
            Mac.VIA_LOCAL -> R.string.route_chip_local
            Mac.VIA_BUILT_IN -> R.string.route_chip_tailscale
            Mac.VIA_TAILSCALE -> R.string.route_chip_app
            else -> R.string.route_chip_remote
        }
        HText(stringResource(word).uppercase(), style = caps, color = HN.inkMuted, maxLines = 1)
        if (via == Mac.VIA_BUILT_IN) {
            Spacer(Modifier.width(6.dp))
            HText(stringResource(R.string.route_chip_built_in).uppercase(), style = caps, color = HN.inkDim, maxLines = 1)
        }
    }
}

/** Settings' route line: its key as an info row has it, and the [RouteChip] at the end. */
@Composable
fun RouteRow(key: String, via: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        HText(key, style = Type.small, color = HN.inkDim)
        Spacer(Modifier.weight(1f))
        RouteChip(via)
    }
}

internal enum class TsGlyph { Person, Phone, Home, Tailnet }

/** The card's own few glyphs, 12dp, in the icon set's hairline. */
@Composable
internal fun TsIcon(glyph: TsGlyph, color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val st = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
        when (glyph) {
            TsGlyph.Person -> {
                drawCircle(color, w * 0.19f, Offset(w * 0.5f, w * 0.32f), style = st)
                drawArc(color, 200f, 140f, false, Offset(w * 0.14f, w * 0.6f), Size(w * 0.72f, w * 0.7f), style = st)
            }
            TsGlyph.Phone -> {
                drawRoundRect(color, Offset(w * 0.27f, w * 0.08f), Size(w * 0.46f, w * 0.84f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = st)
                drawLine(color, Offset(w * 0.43f, w * 0.78f), Offset(w * 0.57f, w * 0.78f), st.width, StrokeCap.Round)
            }
            TsGlyph.Home -> {
                // The home network: two arcs over a point.
                drawArc(color, 225f, 90f, false, Offset(w * 0.06f, w * 0.18f), Size(w * 0.88f, w * 0.88f), style = st)
                drawArc(color, 225f, 90f, false, Offset(w * 0.26f, w * 0.4f), Size(w * 0.48f, w * 0.48f), style = st)
                drawCircle(color, w * 0.08f, Offset(w * 0.5f, w * 0.8f))
            }
            TsGlyph.Tailnet -> {
                // Three devices joined: the tailnet.
                val a = Offset(w * 0.5f, w * 0.18f)
                val b = Offset(w * 0.18f, w * 0.78f)
                val c = Offset(w * 0.82f, w * 0.78f)
                drawLine(color.copy(alpha = 0.6f), a, b, st.width * 0.8f, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.6f), b, c, st.width * 0.8f, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.6f), c, a, st.width * 0.8f, StrokeCap.Round)
                for (p in listOf(a, b, c)) drawCircle(color, w * 0.12f, p)
            }
        }
    }
}

/**
 * Whether Settings shows the Tailscale card: whenever the pairing names the computer on a tailnet
 * and this build carries the node, in every state (Off too), so the state is always to be seen.
 */
fun showTailscaleCard(pairing: com.prudnikweb.hypernova.data.Pairing): Boolean = pairing.tailnetName != null && Tsnet.available
