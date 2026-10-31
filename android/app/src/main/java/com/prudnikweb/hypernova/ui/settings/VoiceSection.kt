package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.DictationEngine
import com.prudnikweb.hypernova.data.VoicePrefs
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Settings → Voice: which recognizer dictation uses (data/PhoneDictation.kt, [DictationEngine]),
 * kept on this phone (VoicePrefs) and used from the next dictation on. The line says where the
 * audio goes for each.
 */
@Composable
fun VoiceRecognitionSection() {
    val context = LocalContext.current
    val prefs = remember { VoicePrefs.of(context) }
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.voice_settings), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(R.string.voice_engine), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.voice_engine_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            Segmented(
                listOf(
                    DictationEngine.Phone to stringResource(R.string.voice_engine_phone),
                    DictationEngine.OnDevice to stringResource(R.string.voice_engine_on_device),
                ),
                prefs.dictationEngine,
                { prefs.chooseDictationEngine(it) },
            )
        }
    }
}
