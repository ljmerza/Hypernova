package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.term.TermEngine
import com.prudnikweb.hypernova.ui.term.TermEnginePref

/**
 * Settings → Terminal engine (testing only, with the testing checklist): **Draw terminals with
 * Ghostty**, the trial of Ghostty's terminal core in place of xterm.js (ui/term/TermEngine.kt).
 * Off by default; kept on this phone; the next terminal opened takes it.
 */
@Composable
internal fun TerminalEngineSection() {
    val context = LocalContext.current
    val engine by remember { TermEnginePref.state(context) }
    Seam(stringResource(R.string.st_term_engine), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                stringResource(R.string.st_term_ghostty),
                stringResource(R.string.st_term_ghostty_desc),
                engine == TermEngine.Ghostty,
                { TermEnginePref.set(context, if (it) TermEngine.Ghostty else TermEngine.Xterm) },
            )
        }
    }
}
