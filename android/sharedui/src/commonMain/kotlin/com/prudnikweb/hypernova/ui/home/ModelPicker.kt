package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.ui.platform.wordsNow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.ModelCatalog
import com.prudnikweb.hypernova.data.ModelList
import com.prudnikweb.hypernova.data.ModelOption
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.ModelWeeks
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.UsageWindow
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.UsageMeterCompact
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.session.ChatSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// ─── Models ───

/**
 * The model picker: every provider's models (the Mac's lists, [lists]; what's known here until
 * they come, [loading]), each provider under its name with its trouble or what's left on it, the
 * subscription's earlier versions apart, another provider's own models before its catalogue. A
 * model with a week of its own ([weeks]) shows that week's meter. Search when the lists are long.
 */
@Composable
internal fun ModelPicker(
    providers: List<Provider>,
    lists: List<ModelList>?,
    loading: Boolean,
    providerId: String?,
    model: String?,
    weeks: List<ModelWeek>,
    now: Long,
    providerTrail: @Composable (Provider) -> Pair<String, Boolean>?,
    onPick: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Str.model)
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(providers, lists) { providers.map { it to ModelCatalog.of(it, lists) } }
    val total = all.sumOf { it.second.size }
    val searchable = total > 14
    // The list as it's drawn, each provider under its header: so the chosen row can be found.
    val entries = remember(all, query, lists, loading) { modelEntries(all, lists, loading, query) }
    val chosen = entries.indexOfFirst { it is ModelEntry.Choice && it.provider.id == providerId && it.option.value == model }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (chosen - 1).coerceAtLeast(0))
    // A new search starts at the top of what it found; opening starts at the one chosen.
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (searched) list.scrollToItem(0)
        searched = true
    }
    ChatSheet(title, onDismiss, height = if (total > 8) 640.dp else null, scroll = false) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(if (searchable) 12.dp else 4.dp))
        if (searchable) SearchField(query, { query = it }, stringResource(Str.ns_search_models))
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list, contentPadding = listBottom()) {
            items(entries, key = { it.key }) { e ->
                when (e) {
                    is ModelEntry.Header -> {
                        val trail = providerTrail(e.provider)
                        PartHeader(e.provider.name, trail = trail?.first, trailColor = if (trail?.second == true) HN.amber else HN.inkDim)
                    }
                    is ModelEntry.Part -> Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                        val sub = when (e.part) {
                            ModelCatalog.Part.Older -> Str.ns_earlier
                            ModelCatalog.Part.Own -> Str.ns_own_models
                            else -> Str.ns_all_models
                        }
                        HText(stringResource(sub).uppercase(), style = Type.label, color = HN.inkDim)
                        if (e.part == ModelCatalog.Part.Catalogue) {
                            Spacer(Modifier.width(8.dp))
                            HText(pluralStringResource(Plural.ns_models_count, e.count, e.count), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = HN.inkGhost)
                        }
                    }
                    is ModelEntry.Choice -> ModelRow(
                        e.option, e.all, selected = e.provider.id == providerId && e.option.value == model,
                        week = if (e.provider.builtIn) ModelCatalog.week(e.option, weeks) else null,
                        now = now,
                    ) { onPick(e.provider.id, e.option.value) }
                    is ModelEntry.Loading -> LoadingLine(stringResource(Str.ns_loading_models), Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                    is ModelEntry.Partial -> HText(stringResource(Str.ns_models_partial), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** One line of the model picker's list. */
private sealed interface ModelEntry {
    val key: String
    data class Header(val provider: Provider) : ModelEntry { override val key get() = "prov:${provider.id}" }
    data class Part(val provider: Provider, val part: ModelCatalog.Part, val count: Int) : ModelEntry { override val key get() = "part:${provider.id}:$part" }
    data class Choice(val provider: Provider, val option: ModelOption, val all: List<ModelOption>) : ModelEntry {
        override val key get() = "m:${provider.id}:${option.value ?: "\u0000default"}"
    }
    data class Loading(val provider: Provider) : ModelEntry { override val key get() = "loading:${provider.id}" }
    data class Partial(val provider: Provider) : ModelEntry { override val key get() = "partial:${provider.id}" }
}

/**
 * Every provider's models as the picker lists them, [query] found: the provider's header, its parts
 * (the subscription's earlier versions, another's own models before its catalogue), and whether its
 * list is still coming or came incomplete.
 */
private fun modelEntries(all: List<Pair<Provider, List<ModelOption>>>, lists: List<ModelList>?, loading: Boolean, query: String): List<ModelEntry> {
    val out = ArrayList<ModelEntry>()
    for ((p, options) in all) {
        val shown = ModelCatalog.search(options, query)
        if (shown.isEmpty() && query.isNotBlank()) continue
        out += ModelEntry.Header(p)
        val parts = ModelCatalog.parts(shown, p.builtIn)
        for ((part, items) in parts) {
            if (part != ModelCatalog.Part.Current && (part != ModelCatalog.Part.Own || parts.size > 1)) out += ModelEntry.Part(p, part, items.size)
            for (o in items) out += ModelEntry.Choice(p, o, options)
        }
        val l = lists?.firstOrNull { it.providerId == p.id }
        if (!p.builtIn && loading && l == null && query.isBlank()) out += ModelEntry.Loading(p)
        else if (l?.error != null && query.isBlank()) out += ModelEntry.Partial(p)
    }
    return out
}

/**
 * One model: its name (the default with the model it runs as a chip), one line on what it's for or
 * its id, its context window and, on another provider, its price; a model with its own week, that
 * week's meter.
 */
@Composable
private fun ModelRow(o: ModelOption, all: List<ModelOption>, selected: Boolean, week: ModelWeek?, now: Long, onClick: () -> Unit) {
    val res = wordsNow()
    val runs = if (o.value == null) ModelCatalog.shown(o, all).takeIf { it != o.name } else null
    val sub = o.description ?: o.model?.takeIf { it != o.name }
    val price = o.price
    val priceText = price?.let { if (ModelCatalog.free(it)) stringResource(Str.ns_free) else "${ModelCatalog.dollars(it.input)} / ${ModelCatalog.dollars(it.output)}" }
    val weekP = week?.let { w ->
        val trail = ModelWeeks.trail(w, now)
        UsageWindow.of(w.pct, if (trail == ModelWeeks.Trail.None) 0L else w.resetsAt ?: 0L, now, limited = w.full)
    }
    val said = buildString {
        append(res.getString(Str.ns_row_said, o.name))
        if (runs != null) append(' ').append(res.getString(Str.ns_runs_said, runs))
        o.description?.let { append(' ').append(it).append('.') }
        o.context?.let { append(' ').append(res.getString(Str.ns_context_said, ModelCatalog.contextText(it))) }
        if (price != null) append(' ').append(if (ModelCatalog.free(price)) res.getString(Str.ns_free_said) else res.getString(Str.ns_price_said, ModelCatalog.dollars(price.input), ModelCatalog.dollars(price.output)))
        if (week != null) append(' ').append(res.getString(Str.ns_week_said, (week.pct * 100).toInt().coerceIn(0, 100)))
    }
    Row(Modifier.pickRow(selected, said, onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(o.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val tag = runs ?: o.slot?.uppercase()
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    TagChip(tag, compact = true)
                }
            }
            if (sub != null) {
                val mono = o.description == null
                HText(sub, style = if (mono) Type.monoSmall else Type.small, color = HN.inkDim, maxLines = 1, overflow = if (mono) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis)
            }
            if (weekP != null) {
                Spacer(Modifier.height(6.dp))
                UsageMeterCompact(stringResource(Str.seven_days), weekP, barWidth = 48.dp)
            }
        }
        if (o.context != null || priceText != null) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.clearAndSetSemantics { }) {
                o.context?.let { GlyphFigure(Glyph.Token, ModelCatalog.contextText(it)) }
                if (priceText != null) {
                    Spacer(Modifier.height(4.dp))
                    GlyphFigure(Glyph.Note, priceText)
                }
            }
        }
        ChosenMark(selected)
    }
}

