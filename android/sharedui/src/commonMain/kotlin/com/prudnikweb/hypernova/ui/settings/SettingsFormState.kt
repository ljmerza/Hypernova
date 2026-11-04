package com.prudnikweb.hypernova.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.FoldRow
import com.prudnikweb.hypernova.data.FormGroup
import com.prudnikweb.hypernova.data.FormRow
import com.prudnikweb.hypernova.data.FormSaved
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.JsonFold
import com.prudnikweb.hypernova.data.JsonRows
import com.prudnikweb.hypernova.data.SettingsFormLogic
import com.prudnikweb.hypernova.data.SettingsFormReply
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.data.changeFormSetting
import com.prudnikweb.hypernova.data.changeFormSettingJson
import com.prudnikweb.hypernova.data.settingsForm
import com.prudnikweb.hypernova.data.undoFormSetting
import com.prudnikweb.hypernova.ui.platform.wordsNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A JSON editor's text as its rows, what's folded, and why folding is off if it is. */
data class JsonDraft(val rows: List<FoldRow>, val folded: Set<String>, val plainBecause: JsonFold.Parsed.Bad?, val opened: String) {
    val text: String get() = JsonRows.join(rows)
    val changed: Boolean get() = text != opened
}

/**
 * What the form holds between its rows: the server's answer, the search, which row is open, what's typed and not saved. Here and
 * not in a row, since a lazy list drops a row that scrolls away. The server decides: when it wants the confirm, [send] asks the
 * words it gave and sends again saying so. There's no fingerprint here, by the owner's choice (docs/DECISIONS.md).
 */
@Stable
class SettingsFormState internal constructor(
    val hub: Hub,
    val agent: String,
    private val scope: CoroutineScope,
    private val words: () -> Strings,
    private val confirm: (Confirm) -> Unit,
    private val toast: (String) -> Unit,
) {
    var attempt by mutableIntStateOf(0)
        private set
    var reply by mutableStateOf<Result<SettingsFormReply>?>(null)
        internal set
    var query by mutableStateOf("")
    /** The row whose control is open. */
    var open by mutableStateOf<String?>(null)
    /** The key being saved. */
    var saving by mutableStateOf<String?>(null)
        private set
    /** A failure in the server's words, under the row it's about. */
    var trouble by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** What's typed in a text or number row, by key. */
    val drafts = mutableStateMapOf<String, String>()
    /** A list being edited, by key. */
    val lists = mutableStateMapOf<String, List<String>>()
    /** What's typed to add to a list, by key. */
    val adding = mutableStateMapOf<String, String>()
    /** A JSON editor's state, by key. */
    val jsons = mutableStateMapOf<String, JsonDraft>()

    val codex: Boolean get() = agent == AgentSettingsReply.CODEX
    private val server: String get() = hub.mac.name
    val current: SettingsFormReply? get() = reply?.getOrNull()

    /** Something's typed that isn't saved: leaving asks. */
    val dirty: Boolean get() = drafts.isNotEmpty() || lists.isNotEmpty() || jsons.values.any { it.changed }

    fun again() {
        attempt++
    }

    /** The groups to show, searched over what's on screen: the app's words where it has them. */
    fun groups(strings: Strings): List<FormGroup> {
        val rows = current?.rows.orEmpty()
        return SettingsFormLogic.groups(rows, query) { r -> formWords(r.key)?.let { (t, b) -> strings.text(t) to strings.text(b) } }
    }

    fun toggle(key: String) {
        open = if (open == key) null else key
    }

    /** A JSON editor on [row] as the server sent it. Held in [jsons] only once something's been done in it. */
    fun freshDraft(row: FormRow): JsonDraft {
        val text = row.json.orEmpty()
        val parsed = JsonFold.parse(text)
        val folded = if (parsed is JsonFold.Parsed.Ok) JsonFold.opening(text, parsed.regions) else emptySet()
        val (rows, bad) = JsonRows.build(text, folded)
        return JsonDraft(rows, folded, bad, text)
    }

    fun refold(key: String, draft: JsonDraft, folded: Set<String>, text: String = draft.text) {
        val (rows, bad) = JsonRows.build(text, folded)
        jsons[key] = draft.copy(rows = rows, plainBecause = bad, folded = if (bad == null) folded else draft.folded)
    }

    fun editJson(key: String, draft: JsonDraft, index: Int, text: String) {
        val next = JsonRows.renumber(draft.rows.toMutableList().also { it[index] = it[index].copy(text = text) })
        val whole = JsonRows.join(next)
        // Valid again: folding comes back as it was.
        if (draft.plainBecause != null && JsonFold.parse(whole) is JsonFold.Parsed.Ok) refold(key, draft, draft.folded, whole)
        else jsons[key] = draft.copy(rows = next)
    }

    private fun clear(key: String) {
        drafts.remove(key)
        lists.remove(key)
        adding.remove(key)
        jsons.remove(key)
    }

    /** Sets [row] to [value]: a string, a boolean, a number or a list of strings. */
    fun save(row: FormRow, value: Any) = send(row.key) { asked -> hub.changeFormSetting(agent, row.key, value, current?.hash, asked) }

    fun saveJson(row: FormRow, text: String) = send(row.key) { asked -> hub.changeFormSettingJson(agent, row.key, text, current?.hash, asked) }

    /** Takes [row] out of the file. */
    fun remove(row: FormRow) = send(row.key) { asked -> hub.changeFormSetting(agent, row.key, JSONObject.NULL, current?.hash, asked) }

    private fun send(key: String, op: suspend (Boolean) -> Result<FormSaved>) {
        saving = key
        trouble = null
        scope.launch {
            val first = op(false)
            first.onSuccess { done(key, it) }.onFailure { e ->
                if (e is AgentSettingRefused && e.code == SettingsFormLogic.CONFIRM_NEEDED) {
                    asked(e.message.orEmpty()) {
                        saving = key
                        scope.launch {
                            op(true).onSuccess { done(key, it) }.onFailure { failed(key, it) }
                            saving = null
                        }
                    }
                } else failed(key, e)
            }
            saving = null
        }
    }

    private fun done(key: String, saved: FormSaved) {
        clear(key)
        if (saved.changed) toast(words().format(Str.as_saved, server))
        attempt++
    }

    /** One tap, saying what it does: the server's words. */
    private fun asked(what: String, go: () -> Unit) {
        confirm(Confirm(words().format(Str.raw_check_title, server), what, words().text(Str.sf_change_action), danger = true, onConfirm = go))
    }

    /** A refusal may mean the file moved on, so it's read again; what's typed stays. */
    private fun failed(key: String, e: Throwable) {
        trouble = key to (e.message?.takeIf { it.isNotBlank() } ?: words().text(Str.sf_err))
        if (e is AgentSettingRefused) attempt++
    }

    fun undo() {
        val offer = current?.undo ?: return
        val hash = current?.hash ?: return
        val back: suspend (Boolean) -> Result<JSONObject> = { asked -> hub.undoFormSetting(agent, hash, asked) }
        saving = offer.key
        trouble = null
        scope.launch {
            back(false).onSuccess { undone() }.onFailure { e ->
                if (e is AgentSettingRefused && e.code == SettingsFormLogic.CONFIRM_NEEDED) {
                    asked(e.message.orEmpty()) {
                        scope.launch { back(true).onSuccess { undone() }.onFailure { failed(offer.key, it) } }
                    }
                } else failed(offer.key, e)
            }
            saving = null
        }
    }

    private fun undone() {
        toast(words().format(Str.as_undone, server))
        attempt++
    }
}

private fun Strings.format(key: Str, vararg args: Any): String = String.format(locale, text(key), *args)

/** [confirm] and [toast] are the shell's: each system has its own way to ask and to say. */
@Composable
fun rememberSettingsForm(hub: Hub, agent: String, confirm: (Confirm) -> Unit, toast: (String) -> Unit): SettingsFormState {
    val scope = rememberCoroutineScope()
    val strings by rememberUpdatedState(wordsNow())
    val asks by rememberUpdatedState(confirm)
    val says by rememberUpdatedState(toast)
    val form = remember(hub, agent) { SettingsFormState(hub, agent, scope, { strings }, { asks(it) }, { says(it) }) }
    LaunchedEffect(form, form.attempt, hub.agentSettingsVersion) {
        // A look again after a change keeps what's on screen until the new answer.
        if (form.reply?.isSuccess != true) form.reply = null
        form.reply = hub.settingsForm(agent)
    }
    return form
}
