package com.prudnikweb.hypernova.ui.onboarding

import android.content.ActivityNotFoundException
import com.prudnikweb.hypernova.ui.components.clipboardText
import com.prudnikweb.hypernova.ui.components.glassChoice
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.PairFlow
import com.prudnikweb.hypernova.data.PairLink
import com.prudnikweb.hypernova.data.PairLinks
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.problemText
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.BlurredTop
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Z
import dev.chrisbanes.haze.hazeSource
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.NovaMark
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Wordmark
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/** Where the server package is published (README, "Getting started"): the newest release's. */
private const val SERVER_RELEASES = "https://github.com/ljmerza/Hypernova/releases/latest"

/**
 * Not paired yet: three short steps (install Hypernova on the server, run hypernova pair there, scan
 * the code or use its link) and a paste fallback for the link.
 * With [onClose] ("Add a server" from the switcher, others paired already): the same steps over the
 * app, titled for adding one, with Cancel at the bottom, and Back closes them. The bottom button
 * stays put above the navigation bar (and the keyboard) while the steps scroll.
 */
@Composable
fun Onboarding(onPaste: (String) -> Unit, onClose: (() -> Unit)? = null) {
    if (onClose != null) BackHandler(onBack = onClose)
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var pasting by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    val adding = onClose != null

    val haze = LocalHaze.current
    Box(Modifier.fillMaxSize().background(HN.bg.copy(alpha = 0.5f))) {
    Column(Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = ONBOARDING)) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = top + 14.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NovaMark(size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Wordmark()
        }
        Spacer(Modifier.height(44.dp))
        HText(stringResource(if (adding) R.string.onb_title_add else R.string.onb_title), style = Type.display, color = HN.ink)
        Spacer(Modifier.height(12.dp))
        HText(stringResource(if (adding) R.string.onb_intro_add else R.string.onb_intro), style = Type.body, color = HN.inkMuted)
        Spacer(Modifier.height(28.dp))

        Step(1, stringResource(R.string.onb_step1_title), stringResource(R.string.onb_step1_body)) {
            Spacer(Modifier.height(14.dp))
            Pill(stringResource(R.string.onb_step1_link), { openLink(context, SERVER_RELEASES) }, kind = PillKind.Outline, height = 44.dp)
        }
        Spacer(Modifier.height(10.dp))
        Step(2, stringResource(R.string.onb_step2_title), stringResource(R.string.onb_step2_body))
        Spacer(Modifier.height(10.dp))
        Step(3, stringResource(R.string.onb_step3_title), stringResource(R.string.onb_step3_body))

        Spacer(Modifier.height(26.dp))
        Seam(stringResource(R.string.onb_or))
        Spacer(Modifier.height(14.dp))
        Pill(
            stringResource(R.string.onb_paste),
            {
                val clip = clipboardText(context)
                if (PairLinks.parse(clip) != null) onPaste(clip!!) else pasting = true
            },
            Modifier.fillMaxWidth(), kind = PillKind.Outline, height = 48.dp,
        )
        AnimatedVisibility(pasting, enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.REVEAL, easing = Motion.Expo)), exit = fadeOut()) {
            Column(Modifier.padding(top = 14.dp)) {
                HText(stringResource(R.string.onb_paste_help), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(10.dp))
                Field(
                    link, { link = it }, placeholder = stringResource(R.string.onb_paste_hint), mono = true, singleLine = false, maxLines = 4,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    label = stringResource(R.string.onb_paste),
                    onDone = { if (link.isNotBlank()) onPaste(link) },
                )
                Spacer(Modifier.height(10.dp))
                Pill(
                    stringResource(R.string.onb_pair), { if (link.isNotBlank()) onPaste(link) },
                    Modifier.fillMaxWidth(),
                    kind = PillKind.Primary, height = 48.dp, enabled = link.isNotBlank(),
                )
            }
        }
    }
    // Cancel never scrolls under the navigation bar: it sits above it, and above the keyboard while
    // the link is typed in (with no Cancel, the steps still end above them).
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
    ) {
        if (onClose != null) Pill(stringResource(R.string.cancel), onClose, Modifier.fillMaxWidth(), kind = PillKind.Ghost, height = 48.dp)
    }
    }
    // Whatever scrolls under the status bar is blurred, like every other screen.
    BlurredTop(haze, ONBOARDING, Modifier.fillMaxWidth(), fade = 14.dp) { Spacer(Modifier.height(top + 18.dp)) }
    }
}

/** Opens [url] in the browser; nothing when the phone has none. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

private const val ONBOARDING = "onboarding"

@Composable
private fun Step(n: Int, title: String, body: String, extra: @Composable () -> Unit = {}) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Seam(stringResource(R.string.onb_step, n))
            Spacer(Modifier.height(12.dp))
            HText(title, style = Type.heading, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(body, style = Type.small, color = HN.inkMuted)
            extra()
        }
    }
}

/** The big round mark at the top of the pairing states. */
@Composable
private fun StateMark(color: Color, glyph: Glyph?) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .glassChoice(true, color = color),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) Icon(glyph, size = 26.dp, color = color, stroke = 2.dp) else NovaMark(size = 34.dp, color = color)
    }
}

/** A full-screen state over the dimmed sky: mark, title, body, actions. */
@Composable
private fun StateScreen(
    mark: @Composable () -> Unit,
    label: String,
    live: Boolean,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .fillMaxSize()
            // Only the sky is underneath (the screen itself steps aside), dimmed for calm.
            .background(HN.bg.copy(alpha = 0.6f))
            // Swallow touches meant for the screen underneath.
            .then(Modifier.padding(0.dp)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = top + 96.dp, bottom = bottom + 24.dp, start = 24.dp, end = 24.dp),
        ) {
            mark()
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Planet(if (live) Activity.Live else Activity.Rest)
                Spacer(Modifier.width(10.dp))
                HText(label.uppercase(), style = Type.label, color = HN.inkMuted)
            }
            Spacer(Modifier.height(12.dp))
            HText(title, style = Type.display, color = HN.ink)
            Spacer(Modifier.height(12.dp))
            HText(body, style = Type.body, color = HN.inkMuted)
            Spacer(Modifier.height(32.dp))
            actions()
        }
    }
}

/** "Pairing with <Mac>…", then success or a clear error with what to do. */
@Composable
fun PairScreen(flow: PairFlow, onRetry: (PairLink) -> Unit, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    BackHandler { if (flow is PairFlow.Done) onDone() else onClose() }
    // Swallow touches so nothing underneath reacts.
    Box(Modifier.fillMaxSize().then(Modifier.background(Color.Transparent)).pointerBlock()) {
        when (flow) {
            is PairFlow.Working -> StateScreen(
                mark = { StateMark(HN.accent, null) },
                label = stringResource(R.string.pair_label), live = true,
                title = stringResource(R.string.pair_working, flow.link.macName),
                body = stringResource(R.string.pair_working_body),
            ) {
                Pill(stringResource(R.string.cancel), onClose, kind = PillKind.Ghost, height = 48.dp)
            }
            is PairFlow.Done -> {
                LaunchedEffect(flow) { delay(3200); onDone() }
                // One line on why we'll ask for notifications next (Android 13+, not yet allowed).
                val why = if (!com.prudnikweb.hypernova.data.Core.notifier.canPost()) "\n\n" + stringResource(R.string.pair_done_notif) else ""
                StateScreen(
                    mark = { StateMark(HN.accent, Glyph.Check) },
                    label = stringResource(R.string.pair_label), live = false,
                    title = stringResource(R.string.pair_done, flow.macName),
                    body = stringResource(R.string.pair_done_body) + why,
                ) {
                    Pill(stringResource(R.string.pair_open), onDone, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 50.dp)
                }
            }
            is PairFlow.Failed -> {
                val res = context.resources
                val link = flow.link
                val title = when {
                    link == null -> stringResource(R.string.pair_bad_link)
                    flow.code == "pairing_closed" -> stringResource(R.string.pair_closed)
                    flow.code == "too_many_tries" -> stringResource(R.string.pair_too_many)
                    flow.problem == Problem.CodeExpired -> stringResource(R.string.pair_expired)
                    else -> stringResource(R.string.pair_failed, link.macName)
                }
                val body = when {
                    link == null -> stringResource(R.string.pair_bad_link_body)
                    // The Mac's local listener (docs/PROTOCOL.md, "Local network"): no code on its screen, or a pause after wrong ones.
                    flow.code == "pairing_closed" -> stringResource(R.string.pair_closed_body)
                    flow.code == "too_many_tries" -> ((flow.retryAfterSeconds.takeIf { it > 0 } ?: 600) + 59).div(60).let { pluralStringResource(R.plurals.pair_too_many_body, it, it) }
                    flow.problem == Problem.CodeExpired -> problemText(res.strings, Problem.CodeExpired)
                    flow.problem == Problem.Server && !flow.message.isNullOrBlank() -> flow.message
                    else -> problemText(res.strings, flow.problem)
                }
                StateScreen(
                    mark = { StateMark(HN.danger, Glyph.Cross) },
                    label = stringResource(R.string.pair_label), live = false,
                    title = title, body = body,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (link != null && flow.problem != Problem.CodeExpired) {
                            Pill(stringResource(R.string.try_again), { onRetry(link) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp)
                        }
                        Pill(stringResource(R.string.pair_back), onClose, Modifier.fillMaxWidth(), kind = PillKind.Ghost, height = 48.dp)
                    }
                }
            }
        }
    }
}

/** The Mac answered 401: this phone's token is gone. */
@Composable
fun UnpairedScreen(macName: String, onPairAgain: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerBlock()) {
        StateScreen(
            mark = { StateMark(HN.danger, Glyph.Cross) },
            label = stringResource(R.string.link_unpaired), live = false,
            title = stringResource(R.string.unpaired_title),
            body = stringResource(R.string.unpaired_body, macName),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.unpaired_pair), onPairAgain, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp)
            }
        }
    }
}

/** Eats taps so the screen underneath doesn't get them. */
private fun Modifier.pointerBlock(): Modifier = this.then(
    Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
)
