package com.prudnikweb.hypernova.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Dictation
import com.prudnikweb.hypernova.data.DictationState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.VoicePrefs
import com.prudnikweb.hypernova.data.VoiceProblem
import com.prudnikweb.hypernova.data.VoiceRoute
import com.prudnikweb.hypernova.ui.theme.HN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voice for one text box (the session's composer, the new-session prompt): the gesture, the
 * dictation it opens, and the words it puts in the box (spec 3.1–3.2, D6).
 *
 * - While listening the words show in the capsule; the box is left alone, so typing during
 *   hands-free still works.
 * - On release the box gets everything heard at once, after what's there with a space; the part
 *   that may still change is [tail] (drawn dim). The settled words replace it silently, unless the
 *   box was edited meanwhile: an edit always wins.
 * - Whatever ends it (an error, the link, the two-minute cap) the words heard go in, and a one-line
 *   [hint] says why. The keyboard is never opened or closed by voice.
 */
@Stable
class VoiceInput internal constructor(private val hub: Hub, private val prefs: VoicePrefs, private val scope: CoroutineScope) {
    // Wired by [rememberVoiceInput] on every composition, so they always see the current box.
    internal var text: () -> String = { "" }
    internal var setText: (String) -> Unit = {}
    internal var onSend: () -> Unit = {}
    internal var askPermission: () -> Unit = {}
    internal var permitted: () -> Boolean = { false }
    internal var haptic: (Haptic) -> Unit = {}
    internal var announce: (String) -> Unit = {}

    var gesture by mutableStateOf<MicState>(MicState.Idle)
        private set
    /** The dictation being listened to or settling (null once its words are in the box). */
    var current by mutableStateOf<Dictation?>(null)
        private set
    /** Its state as last seen; kept after the end, so the capsule can fold away showing it. */
    var state by mutableStateOf<DictationState?>(null)
        private set
    /** The last one was cancelled (the capsule shrinks into the button) rather than finished (it sinks into the box). */
    var cancelled by mutableStateOf(false)
        private set
    var hint by mutableStateOf<VoiceHint?>(null)
        private set
    /** The part of the box that may still change, drawn dim while the words settle. */
    var tail by mutableStateOf<IntRange?>(null)
        private set

    /** The capsule is up: a finger holds the mic, or hands-free listens. */
    val listening: Boolean get() = gesture is MicState.Holding || gesture is MicState.Armed || gesture == MicState.HandsFree
    val armed: Boolean get() = gesture is MicState.Armed
    val handsFree: Boolean get() = gesture == MicState.HandsFree
    /** Let go, and nothing heard yet: the button turns a small planet until the words come. */
    val settlingEmpty: Boolean get() = current != null && !listening && (state as? DictationState.Settling)?.text.isNullOrEmpty()

    /** The Mac offers voice (Soniox ready, or the Companion). */
    val available: Boolean get() = hub.voice.route != VoiceRoute.None
    val linked: Boolean get() = hub.isDemo || hub.link.state == LinkState.Connected

    private var collector: Job? = null
    private var hintJob: Job? = null
    /** The box as it was when the words went in, and what it read right after. */
    private var base: String? = null
    private var expected: String? = null

    fun on(e: MicEvent) {
        val step = MicGesture.reduce(gesture, e)
        gesture = step.state
        for (fx in step.effects) when (fx) {
            MicEffect.AskPermission -> askPermission()
            MicEffect.NotLinked -> show(VoiceHint.Problem(VoiceProblem.Offline))
            is MicEffect.Start -> start()
            MicEffect.Finish -> current?.finish()
            MicEffect.Cancel -> {
                cancelled = true
                current?.cancel()
            }
            MicEffect.Send -> sendNow()
            is MicEffect.Buzz -> haptic(fx.haptic)
        }
    }

    /** Send: words still settling go in first (it waits 1.5 s at most), then the box goes. */
    fun sendNow() {
        if (current == null || listening) return onSend()
        scope.launch {
            settled()
            onSend()
        }
    }

    private fun start() {
        // A new press while the last is still settling: its text stays as it stands.
        detach()
        cancelled = false
        hint = null
        val d = hub.dictate(prefs.language, prefs.headset)
        if (d == null) {
            gesture = MicState.Idle
            show(VoiceHint.Problem(VoiceProblem.Offline))
            return
        }
        current = d
        state = d.state.value
        collector = scope.launch { d.state.collect { seen(d, it) } }
    }

    private fun seen(d: Dictation, s: DictationState) {
        if (d !== current) return
        state = s
        when (s) {
            DictationState.Opening, is DictationState.Live -> {}
            is DictationState.Settling -> place(s.text, s.unsettledFrom)
            is DictationState.Ended -> land(s.text, s.problem)
            DictationState.Cancelled -> done()
        }
    }

    /** Let go: what was heard goes into the box now, after what's there. */
    private fun place(words: String, unsettledFrom: Int) {
        if (words.isEmpty()) return
        val now = text()
        if (expected != null && now != expected) {
            // Edited while it settles: the edit wins, nothing more is changed.
            tail = null
            return
        }
        val b = base ?: now.also { base = it }
        val lead = joined(b, "")
        val next = lead + words
        expected = next
        tail = if (unsettledFrom < words.length) (lead.length + unsettledFrom) until next.length else null
        if (next != now) setText(next)
    }

    /** The end: the settled words replace what was placed (unless the box was edited), and a note says how it went. */
    private fun land(words: String, problem: VoiceProblem?) {
        val now = text()
        val b = base ?: now
        val full = joined(b, words)
        if (words.isNotEmpty() && (expected == null || now == expected) && full != now) setText(full)
        when {
            problem != null -> show(VoiceHint.Problem(problem))
            words.isEmpty() -> show(VoiceHint.Nothing)
            else -> {
                prefs.dictated()
                if (!prefs.moreHintShown) {
                    prefs.moreHintDone()
                    show(VoiceHint.More)
                }
            }
        }
        // What the box says now (an edit made while it settled wins over the words).
        if (words.isNotEmpty()) announce(text())
        done()
    }

    private fun done() {
        // Only a gesture of this dictation's is ended: a press on Send meanwhile is the next one's.
        val ours = listening
        detach()
        if (ours) on(MicEvent.Ended)
    }

    /** Stops following the current dictation (it finishes on its own if it was settling). */
    private fun detach() {
        collector?.cancel()
        collector = null
        current = null
        base = null
        expected = null
        tail = null
    }

    /** Send: words still settling are waited for first (1.5 s at most; the dictation ends itself by then). */
    suspend fun settled() {
        if (current == null || listening) return
        withTimeoutOrNull(3_500) { snapshotFlow { current }.first { it == null } }
    }

    fun show(h: VoiceHint) {
        hint = h
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(h.ms)
            if (hint == h) hint = null
        }
    }

    fun dismissHint() {
        hint = null
    }

    /** The app left the screen (home, screen off): listening ends, the words are kept. */
    internal fun leave() {
        if (!listening) return
        gesture = MicState.Idle
        current?.finish()
    }

    /** The box went away (navigating off): listening is cancelled; words already settling just stop being followed. */
    internal fun dispose() {
        if (listening) current?.cancel()
        detach()
        hintJob?.cancel()
    }

    /** Shown once ever, when the mic first appears (and again once the microphone is allowed). */
    internal fun firstSeen() {
        if (prefs.firstHintShown || hint != null) return
        prefs.firstHintDone()
        show(VoiceHint.First)
    }

    companion object {
        /** [box] then [words] with one space between, as typed text would be. */
        fun joined(box: String, words: String): String = when {
            box.isEmpty() -> words
            words.isEmpty() -> if (box.last().isWhitespace()) box else "$box "
            box.last().isWhitespace() -> box + words
            else -> "$box $words"
        }
    }
}

/**
 * The [VoiceInput] for a text box: [text] and [setText] reach the box, [onSend] is Send (the
 * session's composer). It asks for the microphone at the first press (never in the demo), buzzes
 * at the start, cancel and end, finishes on leaving the app and cancels on leaving the screen,
 * and Back while hands-free is Done.
 */
@Composable
fun rememberVoiceInput(hub: Hub, text: () -> String, setText: (String) -> Unit, onSend: () -> Unit = {}): VoiceInput {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { VoicePrefs.of(context) }
    val voice = remember(hub) { VoiceInput(hub, prefs, scope) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.show(VoiceHint.First)
        else voice.show(VoiceHint.Permission(forGood = !rationale(context)))
    }
    SideEffect {
        voice.text = text
        voice.setText = setText
        voice.onSend = onSend
        voice.permitted = { hub.isDemo || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        voice.askPermission = {
            // The first press asks; after a "no", the note offers it again (or Settings, once Android won't).
            if (!prefs.micAsked) {
                prefs.askedMic()
                ask.launch(Manifest.permission.RECORD_AUDIO)
            } else voice.show(VoiceHint.Permission(forGood = !rationale(context)))
        }
        voice.haptic = { view.buzz(it) }
        voice.announce = { words -> view.announce(context.getString(R.string.voice_a11y_ready, words)) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, voice) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) voice.leave() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            voice.dispose()
        }
    }
    BackHandler(enabled = voice.listening) { voice.on(MicEvent.Done) }
    return voice
}

/**
 * The box's text as drawn: [tail], the words that may still change, dim over whatever [base] does
 * (the "/command" mark). Offsets are untouched, so the cursor and selection stay as typed.
 */
fun voiceTail(tail: IntRange?, base: VisualTransformation): VisualTransformation = if (tail == null) base else TailDim(tail, base)

private data class TailDim(val tail: IntRange, val base: VisualTransformation) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = base.filter(text)
        val end = minOf(tail.last + 1, t.text.length)
        val start = tail.first.coerceIn(0, end)
        if (start >= end) return t
        val dimmed = AnnotatedString.Builder(t.text).apply { addStyle(SpanStyle(color = HN.inkMuted), start, end) }.toAnnotatedString()
        return TransformedText(dimmed, t.offsetMapping)
    }
}

/** The note's Allow: ask again. */
internal fun askAgain(voice: VoiceInput) {
    voice.dismissHint()
    voice.askPermission()
}

/** The note's Settings: Android won't ask again, so the app's own page, where the microphone is switched on. */
internal fun openAppSettings(context: Context) {
    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }
}

/** After a "no", Android shows its dialog again only while this is true; once false, only Settings can allow it. */
private fun rationale(context: Context): Boolean {
    val a = context.activity() ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.RECORD_AUDIO)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * TalkBack's one polite line at the end ("Ready to send: …"). Nothing is announced while the mic
 * is open: it would be transcribed.
 */
@Suppress("DEPRECATION")
private fun View.announce(text: String) = announceForAccessibility(text)

/** Start, armed to cancel, done and cancelled are felt, never heard (no sounds: a design rule). */
internal fun View.buzz(h: Haptic) {
    val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    performHapticFeedback(
        when (h) {
            Haptic.Start -> if (r) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm -> if (r) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (r) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        },
    )
}
