package com.prudnikweb.hypernova.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** Modes that ask first: choosing one of them stops at the think-twice card. */
fun needsThinkTwice(mode: String) = mode == "auto" || mode == "bypassPermissions"

/**
 * The think-twice card: before Auto or Bypass, the choice in a floating glass card ([GlassDialog]):
 * the kicker as its eyebrow, one real case of an agent that was given too much freedom as its
 * title, and the line that is the point ("AI makes mistakes…"); the story itself, where and when,
 * and a link to it are behind "Why it matters". "Keep asking" is the default (primary, on the
 * right; Back and a tap outside do the same); going on is the quiet pill on the left. It shows
 * every time, with no way to switch it off. Bypass's card is lit red, like a star's warning light,
 * and warms the scrim; Auto's a calmer amber. Colour is only the eyebrow and the card's top edge.
 */
@Composable
fun ThinkTwiceCard(mode: String, incident: Incident, onKeep: () -> Unit, onGoOn: () -> Unit) {
    val bypass = mode == "bypassPermissions"
    val tint = if (bypass) HN.danger else HN.amber
    val context = LocalContext.current
    GlassDialog(
        onDismiss = onKeep,
        eyebrow = stringResource(if (bypass) R.string.think_kicker_bypass else R.string.think_kicker_auto),
        title = incident.headline,
        line = stringResource(R.string.think_ai_mistakes),
        tint = tint,
        warm = bypass,
        secondary = DialogButton(stringResource(if (bypass) R.string.think_use_bypass else R.string.think_use_auto), onGoOn, if (bypass) PillKind.Danger else PillKind.Ghost),
        primary = DialogButton(stringResource(R.string.think_keep), onKeep, PillKind.Primary),
        moreLabel = stringResource(R.string.why_it_matters),
        more = {
            HText(incident.what, style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(10.dp))
            // Where and when, drawn as two quiet stamps, not "Antigravity · November 2025".
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.Terminal, size = 12.dp, color = HN.inkDim, stroke = 1.1.dp)
                Spacer(Modifier.width(5.dp))
                HText(incident.tool, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Icon(Glyph.Clock, size = 12.dp, color = HN.inkDim, stroke = 1.1.dp)
                Spacer(Modifier.width(5.dp))
                HText(incident.month(), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            val source = incident.sources.first()
            val storyDesc = stringResource(R.string.think_story_desc, source.title)
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .heightIn(min = 44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
                    }
                    .semantics { contentDescription = storyDesc },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Globe, size = 13.dp, color = HN.accent)
                Spacer(Modifier.width(7.dp))
                HText(stringResource(R.string.think_story), style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)), color = HN.accent)
            }
        },
    )
}

/**
 * The permission-mode picker, five segments, with the think-twice card in front of Auto and
 * Bypass: tapping either opens the card first and the mode changes only on "Use … anyway". A mode
 * the session is already in changes nothing and asks nothing. Bypass is dimmed when the session
 * can't switch to it, with the reason in one line underneath.
 */
@Composable
fun ModePicker(mode: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, bypassAvailable: Boolean = true) {
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var incidentId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier) {
        Segmented(
            listOf(
                "default" to stringResource(R.string.mode_default),
                "acceptEdits" to stringResource(R.string.mode_accept),
                "plan" to stringResource(R.string.mode_plan),
                "auto" to stringResource(R.string.mode_auto),
                "bypassPermissions" to stringResource(R.string.mode_bypass),
            ),
            mode,
            { m ->
                if (m != mode && needsThinkTwice(m)) {
                    val pick = Incidents.next(context)
                    // No list to show (the file is damaged): go on rather than block the choice.
                    if (pick == null) onChange(m) else { incidentId = pick.id; asking = m }
                } else onChange(m)
            },
            enabled = { it != "bypassPermissions" || bypassAvailable },
        )
        if (!bypassAvailable) {
            Spacer(Modifier.height(8.dp))
            HText(stringResource(R.string.mode_bypass_unavailable), style = Type.small, color = HN.inkDim)
        }
    }
    val m = asking
    val incident = Incidents.byId(context, incidentId)
    if (m != null && incident != null) {
        ThinkTwiceCard(m, incident, onKeep = { asking = null }, onGoOn = { asking = null; onChange(m) })
    }
}
