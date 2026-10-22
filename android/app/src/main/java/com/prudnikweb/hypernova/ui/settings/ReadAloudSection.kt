package com.prudnikweb.hypernova.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.SPEECH_RATES
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.data.SpeechOut
import com.prudnikweb.hypernova.data.SpeechVoice
import com.prudnikweb.hypernova.data.nearestRate
import com.prudnikweb.hypernova.ui.LocalToast
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
import com.prudnikweb.hypernova.ui.voice.SpeakingWave
import kotlinx.coroutines.launch

/**
 * Settings → Voice, its read-aloud half (docs/PROTOCOL.md, "Read aloud"): read aloud with Soniox
 * (natural voices, Russian too) or the Mac's own voices, which voice, how fast, a Test that plays
 * here, and whether the glasses speak through this phone. The engine, voice and speed are the
 * Mac's (one setting for the phone and the glasses); the glasses switch is this phone's. Only from
 * a Mac that reads aloud this way.
 */
@Composable
fun ReadAloudSection(hub: Hub) {
    val speech = hub.speech ?: return
    val s = speech.settings ?: return
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<SpeechVoice>?>(null) }
    var voicesOpen by remember { mutableStateOf(false) }
    val showing = if (s.engine == SpeechOut.ENGINE_SONIOX && s.soniox) SpeechOut.ENGINE_SONIOX else s.using
    // The picker's list follows the engine; asked for when it opens (Soniox's needs the network).
    LaunchedEffect(showing, voicesOpen) {
        if (!voicesOpen) return@LaunchedEffect
        voices = null
        speech.voices(showing).onSuccess { voices = it }.onFailure { toast(it.message.orEmpty()); voicesOpen = false }
    }
    fun save(engine: String? = null, voice: String? = null, macVoice: String? = null, rate: Float? = null) {
        scope.launch { speech.save(engine, voice, macVoice, rate).onFailure { toast(it.message.orEmpty()) } }
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Seam(stringResource(R.string.read_aloud), Modifier.weight(1f), color = HN.inkMuted)
        Spacer(Modifier.width(10.dp))
        StatusChip(stringResource(if (s.using == SpeechOut.ENGINE_SONIOX) R.string.read_aloud_soniox_chip else R.string.read_aloud_mac_chip), if (s.note != null) HN.amber else HN.accent)
    }
    GlassCard(Modifier.fillMaxWidth(), accent = if (s.note != null) HN.amber else null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            FieldLabel(stringResource(R.string.read_aloud_with))
            EngineRow(stringResource(R.string.read_aloud_soniox), stringResource(R.string.read_aloud_soniox_body), s.engine == SpeechOut.ENGINE_SONIOX) {
                if (s.engine != SpeechOut.ENGINE_SONIOX) save(engine = SpeechOut.ENGINE_SONIOX)
            }
            // A Linux server has no voices of its own (Mac.FEATURE_MAC_VOICE): it says why instead.
            val macVoice = com.prudnikweb.hypernova.data.Mac.FEATURE_MAC_VOICE
            if (hub.mac.has(macVoice)) {
                EngineRow(stringResource(R.string.read_aloud_mac), stringResource(R.string.read_aloud_mac_body), s.engine == SpeechOut.ENGINE_MAC) {
                    if (s.engine != SpeechOut.ENGINE_MAC) save(engine = SpeechOut.ENGINE_MAC)
                }
            } else hub.mac.unavailable[macVoice]?.let { HText(it, style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 8.dp)) }
            // Why the Mac's voice reads although Soniox was chosen (no key, offline), in one line.
            if (s.note != null) {
                Spacer(Modifier.height(4.dp))
                HText(s.note, style = Type.small, color = HN.amber)
            }
            Spacer(Modifier.height(8.dp))
            Hairline(Modifier.fillMaxWidth())
            val current = if (showing == SpeechOut.ENGINE_SONIOX) s.voice else s.macVoice ?: stringResource(R.string.read_aloud_mac_default)
            NavRow(stringResource(R.string.read_aloud_voice), null, { voicesOpen = !voicesOpen }, trailing = current)
            AnimatedVisibility(
                voicesOpen,
                enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
            ) {
                Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                    val list = voices
                    if (list == null) HText(stringResource(R.string.read_aloud_voices_loading), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 12.dp))
                    else for (v in list) {
                        val picked = if (showing == SpeechOut.ENGINE_SONIOX) v.id == s.voice else v.id == s.macVoice
                        VoiceRow(v, picked) {
                            voicesOpen = false
                            if (showing == SpeechOut.ENGINE_SONIOX) save(voice = v.id) else save(macVoice = v.id)
                        }
                    }
                }
            }
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.read_aloud_speed))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val at = nearestRate(s.rate)
                for (r in SPEECH_RATES) {
                    Pill(rateLabel(r), { if (r != at) save(rate = r) }, kind = if (r == at) PillKind.Primary else PillKind.Ghost, height = 34.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Hairline(Modifier.fillMaxWidth().padding(top = 8.dp))
            ToggleRow(stringResource(R.string.read_aloud_glasses), stringResource(R.string.read_aloud_glasses_body), speech.glasses, { speech.glassesSpeakHere(it) })
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            val testing = Speaker.now?.key == TEST_KEY
            val sample = stringResource(R.string.read_aloud_sample)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    stringResource(if (testing) R.string.speaking_stop else R.string.read_aloud_test),
                    {
                        if (testing) Speaker.stop("phone")
                        else scope.launch { speech.read(sample, null, TEST_KEY).onFailure { toast(it.message.orEmpty()) } }
                    },
                    kind = PillKind.Outline, height = 40.dp,
                )
                if (testing) {
                    Spacer(Modifier.width(14.dp))
                    SpeakingWave(live = Speaker.now?.started == true)
                }
            }
        }
    }
}

private const val TEST_KEY = "settings:test"

private fun rateLabel(r: Float): String = (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0')) + "×"

@Composable
private fun EngineRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(12.dp))
        Column {
            HText(title, style = Type.body, color = if (selected) HN.ink else HN.inkMuted)
            HText(body, style = Type.small, color = HN.inkDim)
        }
    }
}

@Composable
private fun VoiceRow(v: SpeechVoice, picked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(picked, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(picked)
        Spacer(Modifier.width(12.dp))
        Column {
            HText(v.name, style = Type.body, color = if (picked) HN.ink else HN.inkMuted, maxLines = 1)
            // Who it is in words ("Female, warm"), its language as a quiet tag after them: no "·".
            val about = listOfNotNull(v.gender, v.description).filter { it.isNotBlank() }.joinToString(", ")
            val lang = v.lang.takeIf { it.isNotBlank() && it != "*" }
            if (about.isNotBlank() || lang != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (about.isNotBlank()) HText(about, style = Type.small, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (about.isNotBlank() && lang != null) Spacer(Modifier.width(8.dp))
                if (lang != null) HText(lang.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
            }
        }
    }
}
