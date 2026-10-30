package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read aloud, as this phone takes part (docs/PROTOCOL.md, "Read aloud"): the Mac's setting (its
 * voice, the speed), and this phone's own Read aloud played natively.
 *
 * One per connection to a Mac ([LiveHub] owns it); [send] goes over its live feed.
 */
class PhoneSpeech(
    private val pairing: Pairing,
    private val api: Api,
    /** A message on the live feed; false while it's down. */
    private val send: (String) -> Boolean,
    /** A session's project, for the notification. */
    private val labelOf: (String?) -> String?,
) {
    /** The Mac's setting; null until it says, or from a Mac too old to read aloud this way. */
    var settings by mutableStateOf<SpeechOut?>(null)
        private set

    /** The snapshot's `speech` (null from an older Mac: no native read aloud, no section in Settings). */
    fun snapshot(o: JSONObject?) {
        settings = SpeechOut.parse(o)
    }

    /** `{t:"speech"}`, `{t:"speech.stop"}` from the live feed. */
    fun message(o: JSONObject) {
        when (o.str("t")) {
            "speech" -> SpeechOut.parse(o.obj("speech"))?.let { settings = it }
            "speech.stop" -> o.str("id")?.let(Speaker::stopPlay)
        }
    }

    /** Reads [text] on this phone, with the Mac's voice setting: message [key] of session [sessionId]. */
    suspend fun read(text: String, sessionId: String?, key: String?): Result<Unit> = call {
        val body = JSONObject().put("text", text).put("to", "me")
        if (sessionId != null) body.put("sessionId", sessionId)
        val play = PlayInfo.parse(api.post("/v1/speech/play", body).obj("play")) ?: throw HubException("bad_play")
        start(play, key)
    }

    /** The Mac's voices, for the picker. */
    suspend fun voices(): Result<List<SpeechVoice>> = call { SpeechVoice.list(api.get("/v1/speech/voices")) }

    /** Saves the Mac's voice or the speed; the Mac tells every client. */
    suspend fun save(macVoice: String? = null, rate: Float? = null): Result<SpeechOut> = call {
        val body = JSONObject()
        if (macVoice != null) body.put("macVoice", macVoice)
        if (rate != null) body.put("rate", rate.toDouble())
        val s = SpeechOut.parse(api.post("/v1/speech/settings", body).obj("speech")) ?: throw HubException("bad_settings")
        settings = s
        s
    }

    private fun start(play: PlayInfo, key: String?) {
        Speaker.play(play, key, labelOf(play.sessionId), open = {
            // By the route active as it opens (data/LanRoute.kt): the Mac's own address, or the base URL.
            val route = ActiveRoutes.route(pairing.baseUrl)
            val req = Request.Builder()
                .url(route.base.trimEnd('/') + "/v1/speech/plays/${play.id}/audio")
                .header("Authorization", "Bearer ${pairing.token}")
                .get()
                .build()
            streamOf(route).newCall(req)
        }, report = { send(it) })
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        Result.failure(HubException(e.serverMessage ?: e.problem.name))
    } catch (e: Throwable) {
        Result.failure(e)
    }

    companion object {
        /** Audio comes as it's made: a quiet minute is a link that died, not a slow voice. */
        private fun streamOf(route: NetRoute): OkHttpClient = route.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    }
}
