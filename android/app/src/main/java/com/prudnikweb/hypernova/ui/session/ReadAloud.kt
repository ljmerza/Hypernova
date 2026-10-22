package com.prudnikweb.hypernova.ui.session

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Read aloud" for one message at a time. With a Mac that reads aloud this way, it asks the Mac for
 * a play in its voice setting (Soniox's natural voices, or the Mac's own; docs/PROTOCOL.md "Read
 * aloud") and plays it natively as it streams in ([Speaker]: the phone's current output, music
 * ducked, the screen may go off). Without one, the Mac's whole clip as before; and the phone's own
 * text-to-speech when the Mac can't (the demo, no connection), so it always reads.
 * [reading] is the message being read (or fetched), for its Stop and its wave.
 */
object ReadAloud {
    /** The Mac takes at most this much text in one clip; a play takes more. */
    private const val MAX = 8000
    private const val MAX_PLAY = 40_000

    /** Asked for, not playing yet (the Mac is starting the play). */
    private var asked by mutableStateOf<String?>(null)
    /** Read by the older ways: the Mac's whole clip, or the phone's own voice. */
    private var legacy by mutableStateOf<String?>(null)

    /** The message being read (or fetched). */
    val reading: String? get() = Speaker.now?.key ?: asked ?: legacy

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Read [markdown] (message [key] of session [sessionId]) aloud, or stop if it's the one being read. */
    fun toggle(hub: Hub, key: String, markdown: String, scope: CoroutineScope, toast: (String) -> Unit, sessionId: String? = null) {
        if (reading == key) {
            stop()
            return
        }
        stop()
        val spoken = spokenText(markdown)
        if (spoken.isBlank()) return
        // The Mac's voices only where the computer has them (a Linux server doesn't): else the phone's.
        val macVoice = hub.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_MAC_VOICE)
        val speech = hub.speech?.takeIf { it.settings != null && !hub.isDemo && macVoice }
        if (speech != null) {
            asked = key
            job = scope.launch {
                val r = speech.read(clip(spoken, MAX_PLAY), sessionId, key)
                if (asked != key) return@launch
                asked = null
                // The Mac couldn't start a play: its whole clip, or the phone's own voice.
                if (r.isFailure) olderWay(hub, key, clip(spoken, MAX), scope, toast)
            }
            return
        }
        olderWay(hub, key, clip(spoken, MAX), scope, toast)
    }

    private fun olderWay(hub: Hub, key: String, text: String, scope: CoroutineScope, toast: (String) -> Unit) {
        legacy = key
        job = scope.launch {
            val audio = if (hub.isDemo || !hub.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_MAC_VOICE)) null else ChatRepo.speech(text).getOrNull()
            if (legacy != key) return@launch
            if (audio != null && audio.isNotEmpty() && play(audio, key)) return@launch
            onPhone(text, key, toast)
        }
    }

    /** Stops this phone's own reading (the glasses' speech, played here too, goes on). */
    fun stop() {
        job?.cancel()
        job = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tts?.stop() }
        asked = null
        legacy = null
        if (Speaker.now?.key != null) Speaker.stop("phone")
    }

    private fun done(key: String) {
        if (legacy != key) return
        runCatching { player?.release() }
        player = null
        legacy = null
    }

    /** The Mac's clip; false when it won't play (then the phone reads it instead). */
    private suspend fun play(audio: ByteArray, key: String): Boolean {
        val file = withContext(Dispatchers.IO) { File(Core.app.cacheDir, "read-aloud.m4a").apply { writeBytes(audio) } }
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            withContext(Dispatchers.IO) {
                mp.setDataSource(file.path)
                mp.prepare()
            }
            if (legacy != key) {
                mp.release()
                return true
            }
            mp.setOnCompletionListener { main.post { done(key) } }
            mp.setOnErrorListener { _, _, _ -> main.post { done(key) }; true }
            player = mp
            mp.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The phone's own voice, in pieces it can take (its engine caps each one). */
    private fun onPhone(text: String, key: String, toast: (String) -> Unit) {
        val failed = { main.post { if (legacy == key) { done(key); toast(Core.app.getString(R.string.chat_read_failed)) } } }
        val go: (TextToSpeech) -> Unit = { t ->
            val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
            val parts = pieces(text, limit)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == "$key#${parts.lastIndex}") main.post { done(key) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { failed() }
            })
            parts.forEachIndexed { i, p -> t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$key#$i") }
        }
        val ready = tts
        if (ready != null) {
            go(ready)
            return
        }
        var made: TextToSpeech? = null
        made = TextToSpeech(Core.app) { status ->
            if (status == TextToSpeech.SUCCESS && made != null) {
                tts = made
                if (legacy == key) go(made!!)
            } else failed()
        }
    }

    /** Up to [max] characters, cut after a sentence where one ends near there. */
    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max - 1)
        return s.substring(0, if (cut > max / 2) cut + 1 else max)
    }

    private fun pieces(s: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > limit) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), limit - 1).takeIf { it > limit / 2 } ?: rest.lastIndexOf(' ', limit - 1).takeIf { it > 0 } ?: limit
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}

/**
 * What's worth hearing of a reply: its words without the markdown. Code blocks are left out
 * (a voice spelling out code helps nobody), links read as their text, a table's cells as a list.
 */
internal fun spokenText(markdown: String): String {
    val out = StringBuilder()
    var fence = false
    for (raw in markdown.lines()) {
        val t = raw.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            fence = !fence
            continue
        }
        if (fence) continue
        // A table's rule line ("|---|:--|") says nothing.
        if (t.startsWith("|") && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }) continue
        var line = t
            .replace(Regex("^#{1,6}\\s+"), "")
            .replace(Regex("^>\\s?"), "")
            .replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
            .replace(Regex("^\\[[ xX]]\\s+"), "")
        if (line.startsWith("|")) line = line.trim('|').split('|').joinToString(", ") { it.trim() }
        line = line
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            .replace(Regex("(?<![\\w*])[*_](\\S.*?)[*_](?![\\w*])"), "$1")
            .replace("`", "")
        if (line.isBlank()) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        } else {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
            out.append(line)
            // A heading or list item reads as its own sentence.
            if (!line.last().let { it == '.' || it == '!' || it == '?' || it == ':' || it == ',' } && (t.startsWith("#") || t.startsWith("-") || t.startsWith("*") || t.firstOrNull()?.isDigit() == true)) out.append('.')
        }
    }
    return out.toString().trim()
}
