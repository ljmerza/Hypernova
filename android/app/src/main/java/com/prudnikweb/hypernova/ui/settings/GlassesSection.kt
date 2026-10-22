package com.prudnikweb.hypernova.ui.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.bridge.BridgeAccess
import com.prudnikweb.hypernova.bridge.GlassesSettings
import com.prudnikweb.hypernova.bridge.LensBridge
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Settings → Glasses: Hypernova Lens on Even glasses, which reaches the Mac through this app
 * (bridge/LensBridge.kt). The Lenses allowed here, each with Disconnect, then what the glasses do by
 * themselves: reading, how text arrives, pop-ups, the road modes, which sessions show, the language
 * spoken to them. Lens reads these when it connects and hears each change at once; its own phone
 * page is only a status card now (the owner: "all the visuals are in the app for a phone").
 */
@Composable
fun GlassesSection(hub: Hub?) {
    val s = LensBridge.settings
    val grants = LensBridge.grants
    val live = LensBridge.live
    val confirm = LocalConfirm.current
    val toast = LocalToast.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val res = context.resources
    fun set(change: (GlassesSettings) -> GlassesSettings) = LensBridge.updateSettings(change)
    // No glasses allowed here yet (and nothing in the way): one card on what Lens is and how to get
    // it, the settings folded under it (P18, B6).
    val none = grants.isEmpty() && !LensBridge.failed
    var howOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    Column {
        Seam(stringResource(R.string.glasses), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), trail = if (live.isNotEmpty()) stringResource(R.string.glasses_connected_now).uppercase() else null)
        // The Lenses allowed on this phone, or what Lens is.
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    LensBridge.failed -> HText(stringResource(R.string.glasses_bridge_failed), style = Type.small, color = HN.amber, modifier = Modifier.padding(vertical = 10.dp))
                    none -> LensIntro(onHow = { howOpen = true })
                }
                // Allowed here, and the glasses haven't finished with their tap yet (B2a).
                if (LensBridge.collecting) {
                    if (none) Hairline(Modifier.fillMaxWidth())
                    WaitingRow()
                    if (grants.isNotEmpty()) Hairline(Modifier.fillMaxWidth())
                }
                for ((i, g) in grants.withIndex()) {
                    if (i > 0) Hairline(Modifier.fillMaxWidth())
                    GrantRow(g, g.id in live) {
                        // With several, the date tells them apart (B7).
                        val title = if (grants.size > 1) res.getString(R.string.glasses_disconnect_title_dated, allowedOn(context, g.createdAt)) else res.getString(R.string.glasses_disconnect_title, g.name)
                        confirm(Confirm(title, res.getString(R.string.glasses_disconnect_body), res.getString(R.string.glasses_disconnect), danger = true) {
                            LensBridge.disconnect(g.id)
                            toast(res.getString(R.string.glasses_disconnected))
                        })
                    }
                }
            }
        }
        if (none) {
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                FoldRow(stringResource(R.string.glasses_settings_fold), stringResource(R.string.glasses_settings_fold_desc), settingsOpen, Modifier.padding(horizontal = 16.dp)) { settingsOpen = !settingsOpen }
            }
            AnimatedVisibility(
                settingsOpen,
                enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
            ) {
                Column { SettingsCards(hub, s, ::set) }
            }
        } else SettingsCards(hub, s, ::set)
    }
    if (howOpen) HowToConnect(onDismiss = { howOpen = false })
}

/** What the glasses do by themselves: reading, pop-ups, the road, which sessions show, the language. */
@Composable
private fun SettingsCards(hub: Hub?, s: GlassesSettings, set: ((GlassesSettings) -> GlassesSettings) -> Unit) {
    val toast = LocalToast.current
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    Spacer(Modifier.height(10.dp))
    // Reading: aloud, the blip, how text arrives and how fast.
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(stringResource(R.string.glasses_read_aloud), stringResource(if (s.readAloud) R.string.glasses_read_aloud_on else R.string.glasses_read_aloud_off), s.readAloud, { on -> set { it.copy(readAloud = on) } })
            // The voice and its speed aren't here: this phone plays the glasses' speech with the
            // Mac's, set under Voice → Read aloud (Lens's own choice stays "automatic", 1×).
            if (s.readAloud && hub?.speech?.settings != null) {
                HText(stringResource(R.string.glasses_read_aloud_voice), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(bottom = 12.dp))
            }
            Hairline(Modifier.fillMaxWidth())
            ToggleRow(stringResource(R.string.glasses_blip), stringResource(R.string.glasses_blip_desc), s.blip, { on -> set { it.copy(blip = on) } })
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_look))
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("native" to stringResource(R.string.glasses_look_native), "hypernova" to stringResource(R.string.glasses_look_hypernova)), s.look, { v -> set { it.copy(look = v) } })
            Spacer(Modifier.height(8.dp))
            HText(stringResource(if (s.look == "native") R.string.glasses_look_native_desc else R.string.glasses_look_hypernova_desc), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_text_arrives))
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("all" to stringResource(R.string.glasses_text_all), "speech" to stringResource(R.string.glasses_text_speech)), s.textArrives, { v -> set { it.copy(textArrives = v) } })
            Spacer(Modifier.height(8.dp))
            val speechDesc = com.prudnikweb.hypernova.ui.components.byAgents(hub?.claude, hub?.codex, R.string.glasses_text_speech_desc, R.string.glasses_text_speech_desc_codex, R.string.glasses_text_speech_desc_both)
            HText(stringResource(if (s.textArrives == "speech") speechDesc else R.string.glasses_text_all_desc), style = Type.small, color = HN.inkDim)
            if (s.textArrives == "speech") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.glasses_slower), { set { it.copy(pace = (it.pace - 10).coerceAtLeast(GlassesSettings.PACE_MIN)) } }, kind = PillKind.Ghost, height = 36.dp)
                    HText(stringResource(R.string.glasses_pace, s.pace), style = Type.monoSmall.copy(textAlign = TextAlign.Center), color = HN.ink, modifier = Modifier.weight(1f))
                    Pill(stringResource(R.string.glasses_faster), { set { it.copy(pace = (it.pace + 10).coerceAtMost(GlassesSettings.PACE_MAX)) } }, kind = PillKind.Ghost, height = 36.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    // Pop-ups, a switch per kind.
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_popups))
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.glasses_popups_desc), style = Type.small, color = HN.inkDim)
            for (k in GlassesSettings.POP_KINDS) {
                val on = s.popups[k] ?: GlassesSettings.DEFAULT_POPS.getValue(k)
                ToggleRow(stringResource(popName(k)), null, on, { v -> set { it.copy(popups = it.popups + (k to v)) } })
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    // On a bike or a walk.
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_road))
            ToggleRow(stringResource(R.string.glasses_moving), stringResource(R.string.glasses_moving_desc), s.moving, { on -> set { it.copy(moving = on) } })
            Hairline(Modifier.fillMaxWidth())
            ToggleRow(stringResource(R.string.glasses_eclipse), stringResource(R.string.glasses_eclipse_desc), s.eclipse, { on -> set { it.copy(eclipse = on) } })
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(R.string.glasses_guide), {
                LensBridge.askGuide()
                toast(res.getString(if (LensBridge.live.isNotEmpty()) R.string.glasses_guide_sent else R.string.glasses_guide_offline))
            }, Modifier.fillMaxWidth(), kind = PillKind.Outline, height = 44.dp)
            Spacer(Modifier.height(14.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    // Which sessions the glance shows, and the language spoken to the glasses.
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_sessions))
            Spacer(Modifier.height(8.dp))
            val only = s.projects != null
            Segmented(listOf(false to stringResource(R.string.glasses_sessions_all), true to stringResource(R.string.glasses_sessions_only)), only, { v -> set { it.copy(projects = if (v) it.projects ?: emptyList() else null) } })
            Spacer(Modifier.height(10.dp))
            if (!only) HText(stringResource(R.string.glasses_sessions_all_desc), style = Type.small, color = HN.inkDim)
            else {
                val names = (hub?.projects.orEmpty().map { it.name } + s.projects.orEmpty()).distinct().take(24)
                if (names.isEmpty()) HText(stringResource(R.string.glasses_sessions_none), style = Type.small, color = HN.inkDim)
                Chips(names, { it in s.projects.orEmpty() }) { n -> set { cur -> val list = cur.projects.orEmpty(); cur.copy(projects = if (n in list) list - n else list + n) } }
            }
            Spacer(Modifier.height(14.dp))
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.glasses_language))
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.glasses_language_desc), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(8.dp))
            val names = GlassesSettings.LANGUAGES.associateWith { stringResource(langName(it)) }
            Chips(GlassesSettings.LANGUAGES, { it == s.language }, label = { names.getValue(it) }) { l -> set { it.copy(language = l) } }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** What Lens is, that it isn't in the Even Hub yet, and How to connect. */
@Composable
private fun LensIntro(onHow: () -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        HText(stringResource(R.string.glasses_lens_title), style = Type.heading, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.glasses_lens_about), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(8.dp))
        HText(stringResource(R.string.glasses_none), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(14.dp))
        Pill(stringResource(R.string.glasses_how), onHow, kind = PillKind.Outline, height = 40.dp)
        Spacer(Modifier.height(4.dp))
    }
}

/** "Waiting for a tap on the glasses": allowed here, not yet collected by Lens. */
@Composable
private fun WaitingRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp)) { Planet(Activity.Live) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            HText(stringResource(R.string.glasses_waiting), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(2.dp))
            HText(stringResource(R.string.glasses_waiting_desc), style = Type.small, color = HN.inkDim)
        }
    }
}

/** A row that folds a part open or shut, its chevron turning down while open. */
@Composable
private fun FoldRow(title: String, body: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(Motion.DIALOG), label = "fold")
    val state = stringResource(if (open) R.string.glasses_fold_open else R.string.glasses_fold_closed)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(title, style = Type.body, color = HN.ink)
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.width(10.dp))
        Icon(Glyph.Chevron, Modifier.rotate(turn), size = 12.dp, color = HN.inkDim)
    }
}

/** How to connect, as the house pop-up: one line, the four steps behind More. */
@Composable
private fun HowToConnect(onDismiss: () -> Unit) {
    GlassDialog(
        onDismiss = onDismiss,
        eyebrow = stringResource(R.string.glasses_lens_title),
        title = stringResource(R.string.glasses_how_title),
        line = stringResource(R.string.glasses_how_line),
        moreLabel = stringResource(R.string.glasses_how_steps),
        more = {
            val steps = listOf(R.string.glasses_how_1, R.string.glasses_how_2, R.string.glasses_how_3, R.string.glasses_how_4)
            for ((i, id) in steps.withIndex()) {
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    HText("${i + 1}", style = Type.monoSmall, color = HN.accentDim, modifier = Modifier.width(18.dp))
                    HText(stringResource(id), style = Type.small, color = HN.inkMuted, modifier = Modifier.weight(1f))
                }
            }
        },
        primary = DialogButton(stringResource(R.string.glasses_how_ok), onDismiss, PillKind.Outline),
    )
}

/** "16 Oct": the day a Lens was allowed. */
private fun allowedOn(context: android.content.Context, at: Long): String =
    DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)

@Composable
private fun GrantRow(g: BridgeAccess.Grant, connected: Boolean, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // "Connected now" sits under the name, not in a chip beside it: a chip and Disconnect left the
        // name a few letters wide ("Hypernova L…") on a phone.
        Column(Modifier.weight(1f)) {
            // Its name and the day it was allowed, so two Lenses read apart (B7).
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(g.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (g.createdAt > 0) {
                    Spacer(Modifier.width(8.dp))
                    HText(stringResource(R.string.glasses_allowed_on, allowedOn(androidx.compose.ui.platform.LocalContext.current, g.createdAt)), style = Type.small, color = HN.inkDim, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            val when_ = DateUtils.getRelativeTimeSpanString(g.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            // Whether it's connected (or when it was last), then its Lens version, quieter, a gap
            // apart: no "Connected now · Lens 0.1.9".
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(
                    if (connected) stringResource(R.string.glasses_connected_now) else stringResource(R.string.glasses_last_used, when_),
                    style = Type.monoSmall, color = if (connected) HN.accent else HN.inkDim, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                g.version?.let {
                    Spacer(Modifier.width(10.dp))
                    HText(stringResource(R.string.glasses_lens_version, it), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Pill(stringResource(R.string.glasses_disconnect), onDisconnect, kind = PillKind.Danger, height = 36.dp)
    }
}

/** Glass chips that toggle (projects) or pick one (a language). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(items: List<String>, selected: (String) -> Boolean, label: (String) -> String = { it }, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (it in items) {
            val on = selected(it)
            HText(
                label(it), style = Type.small, color = if (on) HN.accent else HN.inkMuted, maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .glassChoice(on, 18.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { onClick(it) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

private fun popName(k: String): Int = when (k) {
    "needs" -> R.string.glasses_pop_needs
    "done" -> R.string.glasses_pop_done
    "stopped" -> R.string.glasses_pop_stopped
    "limit" -> R.string.glasses_pop_limit
    "battery" -> R.string.glasses_pop_battery
    "sleep" -> R.string.glasses_pop_sleep
    else -> R.string.glasses_pop_note
}

private fun langName(l: String): Int = when (l) {
    "auto" -> R.string.glasses_lang_auto
    "ru" -> R.string.glasses_lang_ru
    "de" -> R.string.glasses_lang_de
    "fr" -> R.string.glasses_lang_fr
    "es" -> R.string.glasses_lang_es
    "it" -> R.string.glasses_lang_it
    "uk" -> R.string.glasses_lang_uk
    else -> R.string.glasses_lang_en
}
