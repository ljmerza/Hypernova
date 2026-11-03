// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.BlurredBottomOverScreens
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** The tabs with their words, where they stand at the bottom of the screen: Home's and Settings' pages' the same. */
@Composable
internal fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = bottom + 6.dp)) {
        HomeTabBar(
            Tab.entries.map { t ->
                when (t) {
                    Tab.Sessions -> stringResource(R.string.sessions)
                    Tab.Automations -> stringResource(R.string.au_tab)
                    Tab.History -> stringResource(R.string.history)
                    Tab.Settings -> stringResource(R.string.settings)
                }
            },
            selected.ordinal, { i -> onSelect(Tab.entries[i]) },
            Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The tabs over Settings' pages (owner, 2026-11-03), Settings selected. It stands outside the screens'
 * change, so it stays still between pages; arriving from Home it covers Home's own, in the same place,
 * and going back to Home it fades over the one coming in.
 */
@Composable
internal fun SettingsPageBar(visible: Boolean, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = EnterTransition.None, exit = fadeOut(tween(Motion.REVEAL, easing = Motion.Expo))) {
        BlurredBottomOverScreens(LocalHaze.current, Modifier.fillMaxWidth(), fade = 14.dp) {
            BottomTabs(Tab.Settings, onSelect)
        }
    }
}

/**
 * Home's text tabs (Hyperscribe's bottom nav). With four labels of very different lengths
 * ("AUTOMATIONS" next to "HISTORY"), equal cells would crowd the long one: each tab is sized to its
 * label and the free width is shared evenly between them, the whole cell tappable. If the gap would
 * fall under 8dp (a narrow phone, a large font), every label drops from 0.18em to 0.08em tracking.
 */
@Composable
fun HomeTabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val texts = labels.map { it.uppercase() }
        fun widths(style: androidx.compose.ui.text.TextStyle) = texts.map { measurer.measure(it, style, maxLines = 1).size.width.toFloat() }
        val full = Type.label
        val fullW = widths(full)
        val tooTight = (constraints.maxWidth - fullW.sum()) / texts.size < with(density) { 8.dp.toPx() }
        val style = if (tooTight) full.copy(letterSpacing = 0.08.em) else full
        val w = if (tooTight) widths(style) else fullW
        val gap = ((constraints.maxWidth - w.sum()) / texts.size).coerceAtLeast(0f)
        Row(Modifier.fillMaxWidth()) {
            texts.forEachIndexed { i, label ->
                Box(
                    Modifier
                        // Label plus an even share of what's left: the label sits centred in its cell.
                        .weight(w[i] + gap + 0.01f)
                        .selectable(
                            selected = i == selected, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { onSelect(i) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HText(label, style = style, color = if (i == selected) HN.ink else HN.inkGhost, maxLines = 1)
                }
            }
        }
    }
}
