package com.prudnikweb.hypernova.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read aloud, as this phone takes part (docs/PROTOCOL.md, "Read aloud"): the Mac's setting (Soniox
 * or the Mac's voice, the voice, the speed), this phone's own Read aloud played natively, and the
 * glasses' speech while "Glasses speak through this phone" is on (the default): the Even App's web
 * view only makes sound after a touch on the screen, so the glasses ask the Mac, the Mac asks this
 * app, and it plays them through the headphones with the screen off.
 *
 * One per connection to a Mac ([LiveHub] owns it); [send] goes over its live feed.
 */
class PhoneSpeech(
    context: Context,
    private val pairing: Pairing,
    private val api: Api,
    /** A message on the live feed; false while it's down. */
    private val send: (String) -> Boolean,
    /** A session's project, for the notification. */
    private val labelOf: (String?) -> String?,
) {
    private val prefs = context.applicationContext.getSharedPreferences("speech", Context.MODE_PRIVATE)

    /** The Mac's setting; null until it says, or from a Mac too old to read aloud this way. */
    var settings by mutableStateOf<SpeechOut?>(null)
        private set

    /** "Glasses speak through this phone" (this phone's own choice; on by default). */
    var glasses by mutableStateOf(prefs.getBoolean(KEY_GLASSES, true))
        private set

    fun glassesSpeakHere(on: Boolean) {
        glasses = on
        prefs.edit().putBoolean(KEY_GLASSES, on).apply()
        send(playerMessage(on))
        // Turned off mid-way: the glasses' speech stops here (they play their own after this).
        if (!on && Speaker.now?.play?.forGlasses == true) Speaker.stop("phone")
    }

    /** The live feed is up: offer to play the glasses' speech, or say this phone won't. */
    fun connected() {
        send(playerMessage(glasses))
    }

    /** The snapshot's `speech` (null from an older Mac: no native read aloud, no section in Settings). */
    fun snapshot(o: JSONObject?) {
        settings = SpeechOut.parse(o)
    }

    /** `{t:"speech"}`, `{t:"speech.play"}`, `{t:"speech.stop"}` from the live feed. */
    fun message(o: JSONObject) {
        when (o.str("t")) {
            "speech" -> SpeechOut.parse(o.obj("speech"))?.let { settings = it }
            "speech.play" -> {
                val play = PlayInfo.parse(o.obj("play")) ?: return
                // Offered before it was turned off: say so at once, so the glasses play it themselves.
                if (!glasses) send(progressMessage(play.id, PlayState.Failed, 0, 0f, "off"))
                else start(play, null)
            }
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

    /** Soniox's voices or the Mac's, for the picker. */
    suspend fun voices(engine: String): Result<List<SpeechVoice>> = call { SpeechVoice.list(api.get("/v1/speech/voices?engine=$engine")) }

    /** Saves any of the engine, a voice, the speed; the Mac tells every client. */
    suspend fun save(engine: String? = null, voice: String? = null, macVoice: String? = null, rate: Float? = null): Result<SpeechOut> = call {
        val body = JSONObject()
        if (engine != null) body.put("engine", engine)
        if (voice != null) body.put("voice", voice)
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
        private const val KEY_GLASSES = "glasses"

        /** Audio comes as it's made: a quiet minute is a link that died, not a slow voice. */
        private fun streamOf(route: NetRoute): OkHttpClient = route.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    }
}
