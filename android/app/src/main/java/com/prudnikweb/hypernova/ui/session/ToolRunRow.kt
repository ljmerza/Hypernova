package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Fork: a folded run of Claude's work (ToolRuns.kt) as one line, drawn like a tool row: "8 TOOLS
 * Read ×3 · Bash ×2 · Edit ×3", what failed in red, the planet while one still runs. A tap opens it
 * to the rows themselves ([item] draws each, as the chat would), indented under the line.
 */
@Composable
internal fun ToolRunRow(run: ChatRow.Run, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, item: @Composable (Item) -> Unit) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clickable(
                    remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                    onClickLabel = stringResource(if (open) R.string.collapse else R.string.show_all), onClick = onToggle,
                )
                .padding(vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (run.running) Planet(Activity.Live)
            else Icon(Glyph.Chevron, Modifier.rotate(if (open) 90f else 0f), size = 10.dp, color = HN.accentDim)
            Spacer(Modifier.width(8.dp))
            HText(pluralStringResource(R.plurals.run_calls, run.calls, run.calls).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            HText(
                run.verbs.joinToString(" · ") { (verb, n) -> if (n > 1) "$verb ×$n" else verb },
                style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (run.failed > 0) {
                Spacer(Modifier.width(8.dp))
                HText(stringResource(R.string.run_failed, run.failed), style = Type.monoSmall, color = HN.danger, maxLines = 1)
            }
        }
        if (open) {
            Column(Modifier.padding(start = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (it in run.items) key(it.key) { item(it) }
            }
        }
    }
}
