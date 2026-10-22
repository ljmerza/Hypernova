package com.prudnikweb.hypernova.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.SttInfo
import com.prudnikweb.hypernova.data.VoicePrefs
import com.prudnikweb.hypernova.data.VoiceRoute
import com.prudnikweb.hypernova.data.openLink
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Radio
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch
import java.util.Locale

private const val CONSOLE = "https://console.soniox.com"

/**
 * Settings → Voice (spec 3.3, D11): how the phone's mic hears you. Ready: Soniox with the key's last
 * four, Language, Headset microphone, Remove key. Off: one line and the key field (the key goes
 * straight to the Mac, which tries it before keeping it; the phone never keeps it, not even in its
 * saved state). A key the Mac refused: its words in amber, then the field for a new one. The
 * Companion found on the Mac: said so, with Soniox one tap away. A Mac too old for voice: nothing.
 */
@Composable
fun VoiceSection(hub: Hub) {
    val voice = hub.voice
    val stt = voice.stt ?: return
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val prefs = remember { VoicePrefs.of(context) }
    var info by remember { mutableStateOf<SttInfo?>(null) }
    LaunchedEffect(hub, stt) { hub.sttInfo().onSuccess { info = it } }
    // The key being pasted: plain memory, never saved state (that can reach the disk).
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sonioxInstead by remember { mutableStateOf(false) }
    val companion = COMPANION_OFFERED && voice.route == VoiceRoute.Companion && !stt.ready

    fun connect() {
        val k = key.trim()
        if (busy || k.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            hub.setSttKey(k)
                .onSuccess { key = ""; info = it; sonioxInstead = false }
                .onFailure { error = it.message }
            busy = false
        }
    }

    Row(Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Seam(stringResource(R.string.settings_voice), Modifier.weight(1f), color = if (stt.ready || companion) HN.inkMuted else HN.inkDim)
        Spacer(Modifier.width(10.dp))
        when {
            stt.ready -> StatusChip(stringResource(R.string.settings_voice_ready), HN.accent)
            companion -> StatusChip(stringResource(R.string.settings_voice_found), HN.accent)
            stt.error != null -> StatusChip(stringResource(R.string.settings_voice_off), HN.amber)
            else -> StatusChip(stringResource(R.string.settings_voice_off), HN.inkDim)
        }
    }
    GlassCard(Modifier.fillMaxWidth(), accent = if (stt.error != null && !stt.ready) HN.amber else null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            when {
                stt.ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(stringResource(R.string.settings_voice_soniox), style = Type.heading, color = HN.ink, modifier = Modifier.weight(1f))
                        info?.last4?.let { HText("••••$it", style = Type.monoSmall, color = HN.inkMuted) }
                    }
                    Spacer(Modifier.height(4.dp))
                    HText(stringResource(R.string.settings_voice_body), style = Type.small, color = HN.inkMuted)
                    Spacer(Modifier.height(12.dp))
                    Hairline(Modifier.fillMaxWidth())
                    HowItHears(prefs)
                    Hairline(Modifier.fillMaxWidth())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                confirm(
                                    Confirm(
                                        context.getString(R.string.settings_voice_remove_q),
                                        context.getString(R.string.settings_voice_remove_body),
                                        context.getString(R.string.settings_voice_remove),
                                        danger = true,
                                    ) {
                                        scope.launch { hub.setSttKey(null).onSuccess { info = it }.onFailure { toast(it.message.orEmpty()) } }
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HText(stringResource(R.string.settings_voice_remove), style = Type.body, color = HN.danger)
                    }
                }
                // Not offered until Hyperscribe Companion has a public page (owner, 2026-10-16): the key form shows instead.
                companion && !sonioxInstead -> {
                    HText(stringResource(R.string.settings_voice_companion), style = Type.heading, color = HN.ink)
                    Spacer(Modifier.height(4.dp))
                    HText(stringResource(R.string.settings_voice_companion_body), style = Type.small, color = HN.inkMuted)
                    Spacer(Modifier.height(12.dp))
                    Hairline(Modifier.fillMaxWidth())
                    HowItHears(prefs)
                    Hairline(Modifier.fillMaxWidth())
                    NavRow(stringResource(R.string.settings_voice_soniox_instead), null, { sonioxInstead = true }, color = HN.accent)
                }
                else -> {
                    HText(
                        stt.error ?: stringResource(R.string.settings_voice_off_body),
                        style = Type.small, color = if (stt.error != null) HN.amber else HN.inkMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    FieldLabel(stringResource(R.string.settings_voice_key))
                    Field(
                        key, { key = it; error = null },
                        placeholder = stringResource(if (stt.error != null || info?.last4 != null) R.string.settings_voice_key_new else R.string.settings_voice_key_hint),
                        mono = true, secret = true, label = stringResource(R.string.settings_voice_key),
                        keyboard = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        onDone = ::connect,
                    )
                    val err = error
                    if (err != null) {
                        Spacer(Modifier.height(8.dp))
                        HText(err, style = Type.small, color = HN.danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(
                            stringResource(if (busy) R.string.settings_voice_checking else R.string.settings_voice_connect),
                            ::connect, kind = PillKind.Primary, height = 40.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        HText(
                            stringResource(R.string.settings_voice_get_key), style = Type.small, color = HN.accent,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { openLink(context, info?.keysUrl ?: CONSOLE) }
                                .padding(vertical = 15.dp),
                        )
                    }
                }
            }
        }
    }
    // Speech out: Soniox or the Mac's voice, the voice, the speed, the glasses (ReadAloudSection.kt).
    ReadAloudSection(hub)
}

/** Language and the headset microphone: how the phone's mic hears you, whichever way the words go. */
@Composable
private fun HowItHears(prefs: VoicePrefs) {
    var open by remember { mutableStateOf(false) }
    val choices = languageChoices(stringResource(R.string.settings_voice_auto))
    val current = choices.firstOrNull { it.first == prefs.language } ?: choices.first()
    NavRow(stringResource(R.string.settings_voice_language), null, { open = !open }, trailing = current.second)
    AnimatedVisibility(
        open,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
            for ((code, name) in choices) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(code == current.first, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                            prefs.language(code)
                            open = false
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Radio(code == current.first)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        HText(name, style = Type.body, color = if (code == current.first) HN.ink else HN.inkMuted)
                        if (code == "auto") HText(stringResource(R.string.settings_voice_auto_body), style = Type.small, color = HN.inkDim)
                    }
                }
            }
        }
    }
    // A Bluetooth headset's microphone needs Android 12's communication device.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ToggleRow(stringResource(R.string.settings_voice_headset), stringResource(R.string.settings_voice_headset_body), prefs.headset, { prefs.headset(it) })
    }
}

/**
 * Auto first (the Mac hints English and Russian), then the phone's own languages, each once, four
 * at most, by their own names ("English", "Русский").
 */
private fun languageChoices(auto: String): List<Pair<String, String>> {
    val out = mutableListOf("auto" to auto)
    val list = LocaleListCompat.getDefault()
    for (i in 0 until list.size()) {
        val l = list[i] ?: continue
        val code = l.language.lowercase()
        if (code.isEmpty() || out.any { it.first == code }) continue
        val name = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase(Locale.forLanguageTag(code)) }
        out += code to name
        if (out.size > 4) break
    }
    return out
}

/** Flip to bring back the Hyperscribe Companion route in Settings → Voice. */
private const val COMPANION_OFFERED = false
