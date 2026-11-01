package com.prudnikweb.hypernova.data

import android.speech.SpeechRecognizer

/** How one recognizer session is asked for: one session of many segments, or one utterance. */
enum class ListenMode { Segmented, Plain }

/** What [PhoneDictation] does after a recognizer's callback. */
sealed interface ListenStep {
    /** Nothing: the session goes on, or a callback for a session already over. */
    data object Wait : ListenStep
    /** Listen again in [afterMs]. [clearFirst]: the recognizer may still hold the last session, so cancel it first. */
    data class Listen(val afterMs: Long, val clearFirst: Boolean = false) : ListenStep
    data class End(val text: String, val problem: VoiceProblem?) : ListenStep
}

/**
 * What keeps a dictation listening until the owner ends it, pure, so the JVM tests it
 * (ContinuousDictationTest). Android's recognizer ends by itself at a pause; here its session ending
 * only means another one starts, its words kept. Only [released] (let go, Submit, Done, the cap), a
 * real error, or recognizer sessions that keep failing at once end the dictation.
 *
 * On Android 13 and later [first] is a segmented session, which has no gap to lose words in; a
 * recognizer that shows it doesn't do those gets single-utterance sessions from then on. Nothing
 * says in advance which it is: "depending on the recognizer implementation, this value may have no
 * effect" (RecognizerIntent.EXTRA_SEGMENTED_SESSION).
 */
class ContinuousDictation(first: ListenMode) {
    var mode = first
        private set
    /** Everything settled so far. */
    var final = ""
        private set
    /** What this session may still change. */
    var interim = ""
        private set
    var released = false
        private set
    /** A recognizer session is open: asked for, and not ended yet. */
    var listening = false
        private set
    var segments = 0
        private set
    var restarts = 0
        private set

    private var sessions = 0
    private var ready = false
    private var startedAt = 0L
    private var heard = false
    /** The words this session has settled, as the recognizer gave them. */
    private var settled = ""
    /** Sessions in a row that ended at once with nothing heard. */
    private var rapid = 0

    val text: String get() = heardText(final, interim)

    fun started(now: Long) {
        if (sessions++ > 0) restarts++
        listening = true
        ready = false
        heard = false
        settled = ""
        startedAt = now
    }

    fun ready() { ready = true }

    fun partial(words: String) {
        if (!listening) return
        // A blank partial says nothing new: keep the last words until the results replace them.
        val fresh = afterSettled(words)
        if (fresh.isEmpty()) return
        heard = true
        interim = utteranceAfter(final, fresh)
    }

    /** One finished segment of a segmented session, which goes on. Each covers only itself (RecognitionListener). */
    fun segment(words: String) {
        segments++
        settle(words)
    }

    /** The session's results. A segmented session's cover all of it (RecognitionListener), so only what's new counts. */
    fun results(words: String, now: Long): ListenStep {
        settle(if (endsWithWords(settled, words.trim())) "" else afterSettled(words))
        if (!listening) return ListenStep.Wait
        listening = false
        if (released) return ListenStep.End(final, null)
        // The recognizer answered with results and no segments: it doesn't do segmented sessions.
        fallBack()
        return again(now)
    }

    fun endOfSession(now: Long): ListenStep {
        if (!listening) return ListenStep.Wait
        settle("")
        listening = false
        if (released) return ListenStep.End(final, null)
        fallBack()
        return again(now)
    }

    fun error(code: Int, now: Long): ListenStep {
        if (!listening) return ListenStep.Wait
        listening = false
        if (released) return ListenStep.End(text, problem(code))
        return when {
            code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                settle("")
                val step = again(now)
                // Refused at once and never a segment: the segmented ask itself may be what it dislikes.
                if (rapid > 0) fallBack()
                step
            }
            // Refused before it opened: perhaps the segmented ask, and the plain one works. One try of it.
            mode == ListenMode.Segmented && segments == 0 && !ready -> {
                settle("")
                fallBack()
                ListenStep.Listen(0, clearFirst = true)
            }
            // A session just asked for, refused before it opened: usually the last one not gone yet.
            sessions > 1 && !ready && code in REFUSED -> {
                settle("")
                rapid++
                if (rapid >= MAX_RAPID) ListenStep.End(final, problem(code) ?: VoiceProblem.Mic)
                else ListenStep.Listen(backoff(), clearFirst = true)
            }
            else -> ListenStep.End(text, problem(code))
        }
    }

    /** The owner ends it: [ListenStep.Wait] for a session that answers, or the end now when none is open. */
    fun finishing(): ListenStep {
        released = true
        return if (listening) ListenStep.Wait else ListenStep.End(text, null)
    }

    private fun fallBack() { if (segments == 0) mode = ListenMode.Plain }

    /** The next session; sessions that keep ending at once with nothing heard end the dictation instead of looping. */
    private fun again(now: Long): ListenStep {
        rapid = if (!heard && now - startedAt < RAPID_MS) rapid + 1 else 0
        if (rapid >= MAX_RAPID) return ListenStep.End(final, VoiceProblem.Mic)
        return ListenStep.Listen(backoff())
    }

    /** At once while all is well (a gap loses words); 100, 200, 400, 800 ms between sessions that fail. */
    private fun backoff(): Long = if (rapid == 0) 0L else 100L shl (rapid - 1)

    /** [words] join [final], or what's still interim does when there are none: words are never lost. */
    private fun settle(words: String) {
        val w = words.trim().ifEmpty { interim }
        if (w.isNotEmpty()) heard = true
        final = heardText(final, utteranceAfter(final, w))
        settled = heardText(settled, w)
        interim = ""
    }

    /** [words] without the part of this session already settled, for recognizers that repeat it. */
    private fun afterSettled(words: String): String {
        val w = words.trim()
        if (settled.isEmpty() || !w.startsWith(settled, ignoreCase = true)) return w
        val rest = w.substring(settled.length)
        return if (rest.isEmpty() || rest.first().isWhitespace()) rest.trim() else w
    }

    private fun endsWithWords(text: String, tail: String): Boolean {
        if (tail.isEmpty() || !text.endsWith(tail, ignoreCase = true)) return false
        return text.length == tail.length || text[text.length - tail.length - 1].isWhitespace()
    }

    companion object {
        /** A session this short with nothing heard didn't really listen. */
        const val RAPID_MS = 1_000L
        /** This many such sessions in a row and the dictation ends, words kept. */
        const val MAX_RAPID = 5

        private val REFUSED = setOf(SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT)

        /** Nothing heard, or a quiet end, is no problem. */
        fun problem(code: Int): VoiceProblem? = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> null
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceProblem.MicBusy
            else -> VoiceProblem.Mic
        }

        /**
         * [words] as they read after [before]. Each utterance comes capitalised as if it began a
         * sentence, and a pause isn't always a new sentence: with no full stop before it, its first
         * letter goes small, unless it's "I" or an acronym. After a full stop it's a capital. A name
         * opening an utterance loses its capital; the recognizer doesn't say which words are names.
         */
        fun utteranceAfter(before: String, words: String): String {
            val b = before.trimEnd()
            val w = words.trim()
            if (b.isEmpty() || w.isEmpty()) return w
            val c = w.first()
            return when {
                b.last() in ".?!…" -> c.uppercase() + w.substring(1)
                !c.isUpperCase() || isI(w) || (w.length > 1 && w[1].isUpperCase()) -> w
                else -> c.lowercase() + w.substring(1)
            }
        }

        private fun isI(w: String) = w == "I" || w.startsWith("I ") || w.startsWith("I'") || w.startsWith("I’")
    }
}
