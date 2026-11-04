package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/*
 * Every one of an agent's settings as a form (docs/PROTOCOL.md, "The settings form"; docs/DECISIONS.md, "Every setting, from the
 * agents' own schemas"). The server holds the catalog and decides: each row says its control and rules, so a new setting needs
 * nothing here. What's below is what a client does with them, and none of it is the check that counts.
 */

/** A value to pick: its [text] or [on], Codex's name for a model, and what picking it asks ([asks] null: nothing). */
@Immutable
data class FormChoice(val text: String?, val on: Boolean?, val label: String?, val asks: String?) {
    val wire: Any get() = on ?: text ?: JSONObject.NULL
}

/** What the schema says a value may be: the moment's answer here, and the server's check there. */
@Immutable
data class FormRules(
    val min: Double? = null,
    val max: Double? = null,
    val above: Double? = null,
    val integer: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val unique: Boolean = false,
    val itemMinLength: Int? = null,
)

/**
 * One setting. Of its value, what its [control] needs: [on], [string], [number] (as text), [items], or [json] for a JSON editor.
 * [hidden]: places kept back on the server, which come back as the reply's placeholder.
 */
@Immutable
data class FormRow(
    val key: String,
    val group: String,
    val groupTitle: String,
    val title: String,
    val description: String,
    val control: String,
    val state: String,
    val on: Boolean? = null,
    val string: String? = null,
    val number: String? = null,
    val items: List<String>? = null,
    val json: String? = null,
    val hidden: Int = 0,
    val defaultOn: Boolean? = null,
    val defaultWords: String? = null,
    val choices: List<FormChoice> = emptyList(),
    val rules: FormRules = FormRules(),
    val asks: String? = null,
    val readOnly: String? = null,
    val removable: Boolean = false,
    val screen: String? = null,
    val notRead: Boolean = false,
    val notInSchema: Boolean = false,
    val applies: String? = null,
) {
    val isSet: Boolean get() = state != UNSET
    /** Which way a switch is drawn: the file's, or what not being set means. */
    val showsOn: Boolean get() = on ?: defaultOn ?: false

    companion object {
        const val SWITCH = "switch"
        const val PICK = "pick"
        const val TEXT = "text"
        const val NUMBER = "number"
        const val LIST = "list"
        const val JSON = "json"
        const val LINK = "link"
        const val READONLY = "readonly"
        const val SET = "set"
        const val UNSET = "unset"
        const val OPAQUE = "opaque"
    }
}

/** What Undo would do, and whether it asks first. */
@Immutable
data class FormUndo(val key: String, val asks: Boolean)

@Immutable
data class SettingsFormReply(
    val agent: String,
    val from: String,
    val checkedAgainst: String,
    val placeholder: String,
    val state: String,
    val hash: String?,
    val whyNot: String?,
    val undo: FormUndo?,
    val rows: List<FormRow>,
    val hiddenKeys: Int,
) {
    /** Whether anything here can be changed: not a link, not read-only. */
    val canChange: Boolean get() = whyNot == null && (state == AgentSettingsFile.OK || state == AgentSettingsFile.MISSING)

    companion object {
        fun wire(o: JSONObject): SettingsFormReply {
            val source = o.obj("source")
            return SettingsFormReply(
                agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
                from = source?.str("from").orEmpty(),
                checkedAgainst = source?.str("checkedAgainst").orEmpty(),
                placeholder = o.str("placeholder").orEmpty(),
                state = o.str("state") ?: AgentSettingsFile.UNREADABLE,
                hash = o.str("hash"),
                whyNot = o.str("whyNot"),
                undo = o.obj("undo")?.let { u -> u.str("key")?.let { FormUndo(it, u.bool("asks")) } },
                rows = o.arr("rows").mapObjects(::row),
                hiddenKeys = o.int("hiddenKeys") ?: 0,
            )
        }

        private fun row(o: JSONObject): FormRow? {
            val key = o.str("key") ?: return null
            val value = o.opt("value")
            val fallback = o.opt("default")
            return FormRow(
                key = key,
                group = o.str("group").orEmpty(),
                groupTitle = o.str("groupTitle").orEmpty(),
                title = o.str("title") ?: key,
                description = o.str("description").orEmpty(),
                control = o.str("control") ?: FormRow.READONLY,
                state = o.str("state") ?: FormRow.UNSET,
                on = value as? Boolean,
                string = value as? String,
                number = (value as? Number)?.let(SettingsFormLogic::numberWords),
                items = (value as? JSONArray)?.let(::strings),
                json = o.str("text"),
                hidden = o.int("hidden") ?: 0,
                defaultOn = fallback as? Boolean,
                defaultWords = when (fallback) {
                    is String -> fallback
                    is Number -> SettingsFormLogic.numberWords(fallback)
                    else -> null
                },
                choices = o.arr("of").mapObjects(::choice),
                rules = o.obj("rules")?.let(::rules) ?: FormRules(),
                asks = o.str("asks"),
                readOnly = o.str("readOnly"),
                removable = o.bool("removable"),
                screen = o.str("screen"),
                notRead = o.bool("notRead"),
                notInSchema = o.bool("notInSchema"),
                applies = o.str("applies"),
            )
        }

        private fun strings(a: JSONArray): List<String>? = (0 until a.length()).map { i -> a.opt(i) as? String ?: return null }

        private fun choice(o: JSONObject): FormChoice? = when (val v = o.opt("value")) {
            is String -> FormChoice(v, null, o.str("label"), o.str("asks"))
            is Boolean -> FormChoice(null, v, null, o.str("asks"))
            else -> null
        }

        private fun rules(o: JSONObject) = FormRules(
            min = o.dbl("min"),
            max = o.dbl("max"),
            above = o.dbl("above"),
            integer = o.bool("integer"),
            minLength = o.int("minLength"),
            maxLength = o.int("maxLength"),
            pattern = o.str("pattern"),
            minItems = o.int("minItems"),
            maxItems = o.int("maxItems"),
            unique = o.bool("unique"),
            itemMinLength = o.int("itemMinLength"),
        )
    }
}

/** What came of a change. */
@Immutable
data class FormSaved(val changed: Boolean, val hash: String?) {
    companion object {
        fun wire(o: JSONObject) = FormSaved(o.bool("changed"), o.str("hash"))
    }
}

/** A group's title and its rows as they're shown. */
@Immutable
data class FormGroup(val id: String, val title: String, val rows: List<FormRow>)

/** What's wrong with what's typed, for words at the row. */
sealed interface FormProblem {
    data object Needed : FormProblem
    data class TooShort(val least: Int) : FormProblem
    data class TooLong(val most: Int) : FormProblem
    data object WrongForm : FormProblem
    data object NotANumber : FormProblem
    data object NotWhole : FormProblem
    data class Below(val least: String) : FormProblem
    data class Above(val most: String) : FormProblem
    data class NotOver(val floor: String) : FormProblem
    data class TooMany(val most: Int) : FormProblem
    data class TooFew(val least: Int) : FormProblem
    data object Twice : FormProblem
    data class BadJson(val line: Int, val column: Int) : FormProblem
}

object SettingsFormLogic {
    const val CONFIRM_NEEDED = "confirm_needed"
    const val FILE_CHANGED = AgentSettingRefused.FILE_CHANGED

    /** A number as a person writes it: `30`, not `30.0`. */
    fun numberWords(n: Number): String {
        val d = n.toDouble()
        return if (d == kotlin.math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    /** Every word of [query] somewhere in what's shown of the row, or its key. */
    fun matches(row: FormRow, query: String, title: String = row.title, description: String = row.description): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = "$title ${row.key} $description ${row.groupTitle}".lowercase()
        return words.all { it in text }
    }

    /**
     * The rows to show: the server's order of groups, a group with nothing to show left out, and in each what's set in the file
     * first. [wordsOf]: the app's own title and line for a key that has them, so a search finds what's on screen.
     */
    fun groups(rows: List<FormRow>, query: String, wordsOf: (FormRow) -> Pair<String, String>? = { null }): List<FormGroup> {
        val out = LinkedHashMap<String, MutableList<FormRow>>()
        for (r in rows) {
            val words = wordsOf(r)
            if (matches(r, query, words?.first ?: r.title, words?.second ?: r.description)) out.getOrPut(r.group) { mutableListOf() } += r
        }
        return out.map { (id, list) ->
            FormGroup(id, list.first().groupTitle, list.sortedWith(compareBy({ !it.isSet }, { (wordsOf(it)?.first ?: it.title).lowercase() })))
        }
    }

    fun textProblem(rules: FormRules, text: String): FormProblem? {
        if (text.isEmpty() && (rules.minLength ?: 0) > 0) return FormProblem.Needed
        rules.minLength?.let { if (text.length < it) return FormProblem.TooShort(it) }
        rules.maxLength?.let { if (text.length > it) return FormProblem.TooLong(it) }
        rules.pattern?.let { p ->
            // A pattern Kotlin reads differently from the server's is the server's to judge.
            val re = runCatching { Regex(p) }.getOrNull()
            if (re != null && !re.containsMatchIn(text)) return FormProblem.WrongForm
        }
        return null
    }

    /** What's typed as the number to send, or why it isn't one. */
    fun numberOf(rules: FormRules, text: String): Result<Number> {
        val t = text.trim()
        fun no(p: FormProblem) = Result.failure<Number>(ProblemException(p))
        val d = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return no(FormProblem.NotANumber)
        if (rules.integer && d != kotlin.math.floor(d)) return no(FormProblem.NotWhole)
        rules.min?.let { if (d < it) return no(FormProblem.Below(numberWords(it))) }
        rules.max?.let { if (d > it) return no(FormProblem.Above(numberWords(it))) }
        rules.above?.let { if (d <= it) return no(FormProblem.NotOver(numberWords(it))) }
        return Result.success(if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong() else d)
    }

    fun itemProblem(rules: FormRules, item: String): FormProblem? = rules.itemMinLength?.let { if (item.length < it) FormProblem.Needed else null }

    fun listProblem(rules: FormRules, items: List<String>): FormProblem? {
        rules.minItems?.let { if (items.size < it) return FormProblem.TooFew(it) }
        rules.maxItems?.let { if (items.size > it) return FormProblem.TooMany(it) }
        if (rules.unique && items.toSet().size != items.size) return FormProblem.Twice
        return null
    }

    fun jsonProblem(text: String): FormProblem? = (JsonFold.parse(text) as? JsonFold.Parsed.Bad)?.let { FormProblem.BadJson(it.line, it.column) }
}

/** Carries a [FormProblem] through a [Result]. */
class ProblemException(val problem: FormProblem) : Exception()

suspend fun Hub.settingsForm(agent: String): Result<SettingsFormReply> =
    agentExt("GET", "/v1/agents/settings/form" + McpWire.query("agent" to agent), slow = true).map(SettingsFormReply::wire)

/** [value]: a string, a boolean, a number, a list of strings, or [JSONObject.NULL] to take the key out. */
suspend fun Hub.changeFormSetting(agent: String, key: String, value: Any, expected: String?, confirm: Boolean = false): Result<FormSaved> =
    agentExt("PUT", "/v1/agents/settings/form", formBody(agent, key, expected, confirm).put("value", if (value is List<*>) JSONArray(value) else value), slow = true).map(FormSaved::wire)

/** A JSON editor's [text], sent as text so its keys stay in the order typed. */
suspend fun Hub.changeFormSettingJson(agent: String, key: String, text: String, expected: String?, confirm: Boolean = false): Result<FormSaved> =
    agentExt("PUT", "/v1/agents/settings/form", formBody(agent, key, expected, confirm).put("valueJson", text), slow = true).map(FormSaved::wire)

suspend fun Hub.undoFormSetting(agent: String, expected: String, confirm: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/settings/form/undo", JSONObject().put("agent", agent).put("expected", expected).also { if (confirm) it.put("confirm", true) }, slow = true)

private fun formBody(agent: String, key: String, expected: String?, confirm: Boolean): JSONObject =
    JSONObject().put("agent", agent).put("key", key).put("expected", expected ?: JSONObject.NULL).also { if (confirm) it.put("confirm", true) }
