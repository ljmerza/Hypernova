package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** Dollars per million tokens, in and out. */
@Immutable
data class ModelPrice(val input: Double, val output: Double)

/**
 * A model to start a session on, as the Mac offers it (docs/PROTOCOL.md, "Models"). [value] is what
 * the session is started with (null: the provider's default); [model] the id it runs, when known;
 * [context] its context window in tokens; [older] an earlier version; [slot] the provider's setting
 * that names it ("default", "opus", "sonnet", "haiku").
 */
@Immutable
data class ModelOption(
    val value: String?,
    val name: String,
    val model: String? = null,
    val description: String? = null,
    val context: Long? = null,
    val price: ModelPrice? = null,
    val older: Boolean = false,
    val slot: String? = null,
)

/** One provider's models. [error]: the Mac couldn't read them all ("unreachable"), so this is older or partial. */
@Immutable
data class ModelList(val providerId: String, val source: String, val models: List<ModelOption>, val error: String? = null)

/** The model picker without the screen: the lists, the names shown, search, sections. Pure, unit-tested. */
object ModelCatalog {
    /** `GET /v1/models`; anything malformed reads as absent. */
    fun wire(o: JSONObject): List<ModelList> = o.arr("providers").mapObjects { p ->
        val id = p.str("providerId")?.takeIf { it.isNotBlank() } ?: return@mapObjects null
        ModelList(id, p.str("source").orEmpty(), p.arr("models").mapObjects(::option), p.str("error"))
    }

    fun option(o: JSONObject): ModelOption? {
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val price = o.optJSONObject("price")?.let { pr ->
            val i = pr.dbl("input")
            val out = pr.dbl("output")
            if (i != null && out != null && i >= 0 && out >= 0) ModelPrice(i, out) else null
        }
        return ModelOption(
            value = o.str("value")?.takeIf { it.isNotBlank() },
            name = name,
            model = o.str("model")?.takeIf { it.isNotBlank() },
            description = o.str("description")?.takeIf { it.isNotBlank() },
            context = o.long("context")?.takeIf { it > 0 },
            price = price,
            older = o.bool("older"),
            slot = o.str("slot"),
        )
    }

    /** Claude's current models, until the Mac says which this plan has (an older Mac never does). */
    val CLAUDE: List<ModelOption> = listOf(
        ModelOption(null, "Default", "claude-opus-5-5", "Claude Code’s own choice", 1_000_000),
        ModelOption("opus", "Opus 5.5", "claude-opus-5-5", "For complex work and everyday tasks", 1_000_000),
        ModelOption("fable", "Fable 5.1", "claude-fable-5-1", "For your toughest challenges", 1_000_000),
        ModelOption("sonnet", "Sonnet 5.5", "claude-sonnet-5-5", "Most efficient for simpler tasks", 1_000_000),
        ModelOption("haiku", "Haiku 5.5", "claude-haiku-5-5", "Fastest for quick answers", 1_000_000),
    )

    /** What a provider's settings name: its default, then the opus, sonnet and haiku slots. */
    fun settings(p: Provider): List<ModelOption> {
        val m = p.models
        return listOfNotNull(
            ModelOption(null, m.default?.let(ModelNames::chip) ?: "Default", m.default, slot = "default"),
            m.opus?.let { ModelOption("opus", ModelNames.chip(it), it, slot = "opus") },
            m.sonnet?.let { ModelOption("sonnet", ModelNames.chip(it), it, slot = "sonnet") },
            m.haiku?.let { ModelOption("haiku", ModelNames.chip(it), it, slot = "haiku") },
        )
    }

    /** [p]'s models: the Mac's list when it sent one with models in it, else what's known here. */
    fun of(p: Provider, lists: List<ModelList>?): List<ModelOption> {
        val sent = lists?.firstOrNull { it.providerId == p.id }?.models?.takeIf { it.isNotEmpty() }
        return sent ?: if (p.builtIn || p.kind == "anthropic") CLAUDE else settings(p)
    }

    /** The choice for [value] among [all], when it's there. */
    fun find(all: List<ModelOption>, value: String?): ModelOption? = all.firstOrNull { it.value == value }

    /**
     * The choice a session running [model] is on (its alias, or an id the Mac reports as it runs:
     * "claude-opus-5-5", dated or with "[1m]"), an alias before the default that runs the same id.
     * Null when none runs it (another provider's id, a model the list doesn't have).
     */
    fun current(all: List<ModelOption>, model: String?): ModelOption? {
        if (model.isNullOrBlank()) return null
        all.firstOrNull { it.value != null && it.value == model }?.let { return it }
        val key = ModelNames.key(model)
        return all.filter { o -> listOfNotNull(o.value, o.model).any { ModelNames.key(it) == key } }.minByOrNull { if (it.value != null) 0 else 1 }
    }

    /**
     * True when switching to [o] on [p] costs money: another provider than the subscription, and
     * not a model it lists as free. The Mac wants the owner's yes for it (`confirmPaid`).
     */
    fun paid(p: Provider, o: ModelOption): Boolean = !p.builtIn && o.price?.let(::free) != true

    /**
     * The name a choice goes by on the chip and in its row: the default as the model it runs
     * ("Opus 5.5", named after the alias that runs the same id), others by their own name.
     */
    fun shown(o: ModelOption, all: List<ModelOption>): String {
        if (o.value != null) return o.name
        val id = o.model ?: return o.name
        return all.firstOrNull { it.value != null && it.model == id }?.name ?: if (o.name == "Default") ModelNames.chip(id) else o.name
    }

    /**
     * A model's family, as a week of its own is named ("Fable", "Sonnet"): the first word of its
     * name ("Fable 5.1"), or of its id without "claude-" ("claude-fable-5-1"); the default by the
     * model it runs.
     */
    fun family(o: ModelOption): String? {
        val fromName = o.name.trim().substringBefore(' ').lowercase().takeIf { o.value != null && it.isNotEmpty() && it != "default" && it.none(Char::isDigit) }
        if (fromName != null) return fromName
        val id = o.model ?: return null
        return ModelNames.chip(id).substringBefore('-').substringBefore(' ').lowercase().takeIf { it.isNotEmpty() }
    }

    /** The week of its own [o] spends on the subscription ("Fable", "Sonnet"), when it has one. */
    fun week(o: ModelOption, weeks: List<ModelWeek>): ModelWeek? {
        val f = family(o) ?: return null
        return weeks.firstOrNull { it.label.trim().substringBefore(' ').lowercase() == f }
    }

    /** A context window in a few characters: "1M", "1.5M", "400K", "164K". */
    fun contextText(tokens: Long): String = when {
        tokens >= 1_000_000 -> {
            // Tenths of a million, cut rather than rounded: 1,050,000 is a 1M window, not 1.1M.
            val tenths = tokens / 100_000
            if (tenths % 10 == 0L) "${tenths / 10}M" else String.format(Locale.US, "%.1fM", tenths / 10.0)
        }
        tokens >= 1000 -> "${(tokens / 1000.0).roundToLong()}K"
        else -> tokens.toString()
    }

    /** Dollars per million tokens: "$1.25", "$10", "$0.075". */
    fun dollars(v: Double): String = when {
        v == Math.floor(v) && v < 10_000 -> "$" + v.toLong()
        v >= 0.01 -> String.format(Locale.US, "$%.2f", v)
        else -> "$" + String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }

    /** True for a model that costs nothing on its provider. */
    fun free(p: ModelPrice): Boolean = p.input == 0.0 && p.output == 0.0

    /**
     * The choices [query] finds in [all], best first: each of its words in the name (counting most)
     * or the id. Empty query: all of them, as they were.
     */
    fun search(all: List<ModelOption>, query: String): List<ModelOption> {
        val words = Fuzzy.words(query)
        if (words.isEmpty()) return all
        val scored = ArrayList<Triple<ModelOption, Int, Int>>()
        for ((i, o) in all.withIndex()) {
            var total = 0
            var weakest = Int.MAX_VALUE
            var ok = true
            for (w in words) {
                val name = Fuzzy.word(w, o.name)
                val id = maxOf(o.model?.let { Fuzzy.word(w, it) } ?: -1, o.value?.let { Fuzzy.word(w, it) } ?: -1)
                val best = maxOf(name?.plus(100) ?: -1, id)
                if (best < 0) { ok = false; break }
                total += best
                weakest = minOf(weakest, maxOf(name ?: -1, id))
            }
            // Ties keep the list's own order (newest first in a catalogue).
            if (ok) scored += Triple(o, total * 1000 - i.coerceAtMost(999), weakest)
        }
        return Fuzzy.keepStrong(scored.sortedByDescending { it.second }.map { it.first to it.third })
    }

    /** How a provider's list is laid out in the picker. */
    enum class Part {
        /** The subscription's current models (or everything of a provider without a catalogue). */
        Current,
        /** The subscription's earlier versions. */
        Older,
        /** A provider's own models (its settings). */
        Own,
        /** The rest of its catalogue. */
        Catalogue,
    }

    /** [all] of one provider, in its parts, empty ones left out. */
    fun parts(all: List<ModelOption>, builtIn: Boolean): List<Pair<Part, List<ModelOption>>> {
        val out = ArrayList<Pair<Part, List<ModelOption>>>()
        if (builtIn) {
            all.filter { !it.older }.takeIf { it.isNotEmpty() }?.let { out += Part.Current to it }
            all.filter { it.older }.takeIf { it.isNotEmpty() }?.let { out += Part.Older to it }
        } else {
            val own = all.filter { it.slot != null }
            val rest = all.filter { it.slot == null }
            if (rest.isEmpty()) out += Part.Current to own
            else {
                if (own.isNotEmpty()) out += Part.Own to own
                out += Part.Catalogue to rest
            }
        }
        return out
    }
}
