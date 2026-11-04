package com.prudnikweb.hypernova.desktop

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.Net
import com.prudnikweb.hypernova.data.PairLink
import com.prudnikweb.hypernova.data.PairLinks
import com.prudnikweb.hypernova.data.Pairer
import com.prudnikweb.hypernova.data.Platform
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.problemText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where pairing stands. */
sealed interface PairState {
    data object Idle : PairState
    data class Working(val name: String) : PairState
    /** It didn't pair: what to say, and whether another try with the same link can work. */
    data class Failed(val title: String, val body: String, val link: PairLink?) : PairState
}

/** What's under the top bar: what's alive, History, or Statistics. Settings and pairing go over any of them and come back to it. */
enum class Page { Sessions, History, Statistics }

/** One server's History as it was left, for as long as the window is open: nothing here is saved. */
class HistoryView {
    var query by mutableStateOf("")
    var project by mutableStateOf<String?>(null)
    /** Its own choice: going to History and back finds the list's where it was. */
    var chosen by mutableStateOf<String?>(null)
    val list = LazyListState()
}

/**
 * The window's own state, over [Core], which has the servers and their links. The pairing is the real one, the
 * phone's ([Pairer], then [Core.paired]): the link's code over the pinned local route, then a device certificate on
 * this computer's own key, then mutual TLS for everything after it. HubViewModel does the same on a phone.
 */
class Shell(private val platform: Platform, var desk: DeskActions = RealDesk) {
    /** "Pair another" over a server that's already paired. */
    var adding by mutableStateOf(false)
    /** The Settings screen is up: it goes over whatever was there, and goes back to it. */
    var settings by mutableStateOf(false)
    /** Whose permission rules are open from Settings, or null. */
    var rulesAgent by mutableStateOf<String?>(null)
    var pair by mutableStateOf<PairState>(PairState.Idle)
        private set
    /** What's typed or pasted in the pairing field. */
    var link by mutableStateOf("")
    var toast by mutableStateOf<String?>(null)
        private set

    /** The owner's own scale, if he has chosen one. */
    val zoom = Zoom(platform.storage.prefs(Zoom.PREFS))
    /** The level, on screen for a moment after it changes. */
    var zoomNote by mutableStateOf<String?>(null)
        private set

    /** The session on screen beside the list, per server. Not saved: the window opens on nothing chosen. */
    private val chosen = mutableStateMapOf<String, String>()
    /** Where each server's list is scrolled, so Settings and a switch of server come back to the same place. */
    private val lists = HashMap<String, LazyListState>()

    var page by mutableStateOf(Page.Sessions)
    /** New session is up in the right pane. */
    var newSession by mutableStateOf(false)
    /** Statistics adds up every server, rather than the one on screen's. */
    var statsAll by mutableStateOf(false)

    /** The logo was pressed: the list goes to its top once it's on screen ([goHome]). */
    var toTop by mutableStateOf(false)

    /**
     * The logo, wherever it's shown (owner, 2026-11-03: "reset back to the home screen"): the Sessions list at its top, with
     * what was over it or beside it gone. The server on screen stays, and History keeps its own place for when it's next opened.
     */
    fun goHome() {
        settings = false
        newSession = false
        page = Page.Sessions
        Core.activeId?.let { choose(it, null) }
        toTop = true
    }

    /** A session started from the form: its form goes, and it's what's on screen. */
    fun started(server: String, session: String) {
        newSession = false
        page = Page.Sessions
        choose(server, session)
    }

    private val histories = HashMap<String, HistoryView>()

    fun historyOf(server: String): HistoryView = histories.getOrPut(server) { HistoryView() }

    fun chosen(server: String): String? = chosen[server]

    fun choose(server: String, session: String?) {
        if (session == null) chosen.remove(server) else chosen[server] = session
    }

    fun listOf(server: String): LazyListState = lists.getOrPut(server) { LazyListState() }

    private var pairJob: Job? = null
    private var toastJob: Job? = null
    private var zoomNoteJob: Job? = null

    /** A zoom key, in a window whose automatic scale is [auto]. */
    fun zoom(key: ZoomKey, auto: Float) {
        val percent = Zoom.percent(zoom.press(key, auto))
        zoomNote = if (zoom.chosen == null) Ui.desk_zoom_auto.format(percent) else Ui.desk_zoom.format(percent)
        zoomNoteJob?.cancel()
        zoomNoteJob = Core.scope.launch {
            delay(ZOOM_NOTE_MS)
            zoomNote = null
        }
    }

    /** A hub's refusal or lost link, on screen for a while: a message long enough to read. */
    fun say(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = Core.scope.launch {
            delay(TOAST_MS)
            toast = null
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        toast = null
    }

    /** The pairing field's Pair. */
    fun submit() {
        val text = link.trim()
        val parsed = if (text.length > MAX_LINK) null else PairLinks.parse(text)
        if (parsed == null) {
            val body = if (text.length > MAX_LINK) Ui.desk_pair_too_long.text() else Ui.desk_bad_link_body.text()
            pair = PairState.Failed(Ui.pair_bad_link.text(), body, null)
            return
        }
        pairWith(parsed)
    }

    fun pairWith(parsed: PairLink) {
        pairJob?.cancel()
        pair = PairState.Working(parsed.macName)
        pairJob = Core.scope.launch {
            try {
                val p = Pairer.pair(platform, parsed)
                Core.paired(p)
                // A first look at this server: nothing of an earlier pairing's stands.
                Core.activeId?.let { chosen.remove(it); lists.remove(it); histories.remove(it) }
                page = Page.Sessions
                newSession = false
                // The code in the field is spent, and it was a credential until now.
                link = ""
                adding = false
                pair = PairState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                pair = failed(parsed, Net.classify(e))
            }
        }
    }

    /** Back from a failure, or out of "Pair another". */
    fun closePair() {
        pairJob?.cancel()
        pair = PairState.Idle
    }

    fun cancelAdding() {
        closePair()
        link = ""
        adding = false
    }

    /** "Pair again" after the server forgot this computer: that pairing goes, and the pairing field shows. */
    fun pairAgain() {
        Core.clear()
    }

    private fun failed(parsed: PairLink, e: ApiException): PairState.Failed {
        val problem = if (!platform.network.hasNetwork() && e.problem != Problem.CodeExpired) Problem.NoNetwork else e.problem
        // A server's words whatever is paired so far: this screen is before anything has said what it is.
        val res = ComputerWords.wordedFor(platform.strings, Mac.LINUX)
        return when {
            e.code == "pairing_closed" -> PairState.Failed(Ui.pair_closed.text(), Ui.desk_closed_body.text(), null)
            e.code == "too_many_tries" -> PairState.Failed(Ui.pair_too_many.text(), Ui.desk_too_many_body.text(), null)
            problem == Problem.CodeExpired -> PairState.Failed(Ui.pair_expired.text(), Ui.desk_expired_body.text(), null)
            problem == Problem.Server && !e.serverMessage.isNullOrBlank() -> PairState.Failed(Ui.pair_failed.format(parsed.macName), e.serverMessage.orEmpty(), parsed)
            else -> PairState.Failed(Ui.pair_failed.format(parsed.macName), problemText(res, problem), parsed)
        }
    }

    private companion object {
        const val TOAST_MS = 6_000L
        const val ZOOM_NOTE_MS = 1_500L
        /** A pairing link is a few hundred characters: no need to run its patterns over a pasted file. */
        const val MAX_LINK = 4_096
    }
}
