package com.prudnikweb.hypernova.ui.session

import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.Api
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.Attachment
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.Net
import com.prudnikweb.hypernova.data.NetRoute
import com.prudnikweb.hypernova.data.Wire
import com.prudnikweb.hypernova.data.arr
import com.prudnikweb.hypernova.data.bool
import com.prudnikweb.hypernova.data.dbl
import com.prudnikweb.hypernova.data.long
import com.prudnikweb.hypernova.data.obj
import com.prudnikweb.hypernova.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A session's usage as the chat's strip shows it (docs/PROTOCOL.md, "Session usage"). [contextExact]
 * and [costExact]: Claude Code's own numbers; otherwise worked out on the Mac, so the strip says
 * "about". [costUsd] is null where no price applies (another provider).
 */
@Immutable
data class SessionUsage(
    val model: String?,
    val modelName: String?,
    val contextUsed: Long?,
    val contextWindow: Long?,
    val contextPct: Int?,
    val contextExact: Boolean,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val agentTokens: Long,
    val costUsd: Double?,
    val costExact: Boolean,
) {
    val tokens: Long get() = input + output + cacheRead + cacheWrite
}

enum class TodoStatus { Pending, Active, Done }

/** One line of Claude's to-do list: what it is, and how it reads while under way. */
@Immutable
data class Todo(val id: String, val text: String, val active: String?, val status: TodoStatus)

/** A rewind's result: the session that carries on, and the message for its prompt box. */
data class Rewound(val sessionId: String, val text: String, val files: List<Attachment>)

/**
 * The chat's own calls to the Mac: the usage strip, Claude's to-do list, "Rewind to here" and
 * the Mac's voice for "Read aloud". Demo sessions get believable answers of their own.
 */
object ChatRepo {
    private fun api(): Api {
        val p = Core.pairing ?: throw HubException(Core.app.getString(R.string.chat_err_mac))
        return Api(p.baseUrl, p.token)
    }

    private fun fail(e: Throwable, fallback: Int = R.string.chat_err_mac): Throwable = when {
        e is CancellationException -> e
        e is ApiException && e.serverMessage != null -> HubException(e.serverMessage)
        // A Mac that doesn't have these yet answers 404 with no message of its own.
        e is ApiException && e.status == 404 -> HubException(Core.app.getString(R.string.chat_err_old_mac))
        e is HubException -> e
        else -> HubException(Core.app.getString(fallback))
    }

    suspend fun usage(hub: Hub, sessionId: String): Result<SessionUsage> = try {
        if (hub.isDemo) Result.success(DemoChat.usage(sessionId, hub.session(sessionId)?.model))
        else Result.success(parseUsage(api().get("/v1/sessions/$sessionId/usage").getJSONObject("usage")))
    } catch (e: CancellationException) {
        // Left the screen: not a failure to show ("StandaloneCoroutine was cancelled").
        throw e
    } catch (e: Throwable) {
        Result.failure(fail(e))
    }

    suspend fun todos(hub: Hub, sessionId: String): Result<List<Todo>> = try {
        if (hub.isDemo) Result.success(DemoChat.todos(sessionId))
        else Result.success(parseTodos(api().get("/v1/sessions/$sessionId/todos")))
    } catch (e: CancellationException) {
        // Left the screen: not a failure to show ("StandaloneCoroutine was cancelled").
        throw e
    } catch (e: Throwable) {
        Result.failure(fail(e))
    }

    suspend fun rewind(hub: Hub, sessionId: String, eventId: String): Result<Rewound> = try {
        if (hub.isDemo) throw HubException(Core.app.getString(R.string.chat_rewind_demo))
        val o = api().post("/v1/sessions/$sessionId/rewind", JSONObject().put("eventId", eventId))
        val id = o.obj("session")?.str("id") ?: throw HubException(Core.app.getString(R.string.chat_err_mac))
        val files = o.arr("files")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(Wire::attachment) } }.orEmpty()
        Result.success(Rewound(id, o.str("text").orEmpty(), files))
    } catch (e: CancellationException) {
        // Left the screen: not a failure to show ("StandaloneCoroutine was cancelled").
        throw e
    } catch (e: Throwable) {
        Result.failure(fail(e))
    }

    /** The Mac's voice reading [text] (docs/PROTOCOL.md, "Read aloud"): an M4A clip, whole. */
    suspend fun speech(text: String): Result<ByteArray> = try {
        val p = Core.pairing ?: throw HubException(Core.app.getString(R.string.chat_err_mac))
        // By the active route (data/LanRoute.kt): the Mac's own address on this network, or the base URL.
        val route = ActiveRoutes.route(p.baseUrl)
        val req = Request.Builder()
            .url(route.base.trimEnd('/') + "/v1/speech")
            .header("Authorization", "Bearer ${p.token}")
            .post(JSONObject().put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        Result.success(fetch(req, speechClient(route)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(fail(e, R.string.chat_read_failed))
    }

    /** A long reply takes the Mac a while to read into a clip: a minute before giving up. */
    private fun speechClient(route: NetRoute): OkHttpClient = route.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    private suspend fun fetch(req: Request, client: OkHttpClient): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(Net.classify(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        if (r.code !in 200..299) throw Api.errorOf(r.code, String(bytes))
                        if (cont.isActive) cont.resume(bytes)
                    }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(Net.classify(e))
                }
            }
        })
    }

    internal fun parseUsage(o: JSONObject): SessionUsage {
        val c = o.obj("context")
        val t = o.obj("tokens")
        return SessionUsage(
            model = o.str("model"),
            modelName = o.str("modelName"),
            contextUsed = c?.long("used"),
            contextWindow = c?.long("window"),
            contextPct = c?.long("pct")?.toInt(),
            contextExact = c?.bool("exact") == true,
            input = t?.long("input") ?: 0,
            output = t?.long("output") ?: 0,
            cacheRead = t?.long("cacheRead") ?: 0,
            cacheWrite = t?.long("cacheWrite") ?: 0,
            agentTokens = o.long("agentTokens") ?: 0,
            costUsd = o.dbl("costUsd"),
            costExact = o.bool("costExact"),
        )
    }

    internal fun parseTodos(o: JSONObject): List<Todo> {
        val a = o.arr("todos") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val text = t.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = when (t.str("status")) {
                "completed" -> TodoStatus.Done
                "in_progress" -> TodoStatus.Active
                else -> TodoStatus.Pending
            }
            Todo(t.str("id") ?: "$i", text, t.str("active")?.takeIf { it.isNotBlank() }, status)
        }
    }
}

/** The demo's numbers: one session close to a full context window, so Compact shows. */
private object DemoChat {
    fun usage(sessionId: String, model: String?): SessionUsage = when {
        sessionId == "s-haze" -> SessionUsage("claude-sonnet-4-5", "Sonnet 4.5", 176_400, 200_000, 88, true, 3_120, 61_870, 2_904_000, 211_500, 0, 2.41, true)
        sessionId == "s-hypernova" -> SessionUsage("claude-opus-5-5", "Opus 5.5", 418_000, 1_000_000, 42, false, 8_410, 148_200, 6_812_000, 402_900, 1_310_000, 7.86, false)
        model == "sonnet" -> SessionUsage("claude-sonnet-4-5", "Sonnet 4.5", 38_000, 200_000, 19, false, 900, 12_100, 310_000, 38_000, 0, 0.29, false)
        else -> SessionUsage("claude-opus-5-5", "Opus 5.5", 61_000, 1_000_000, 6, false, 1_200, 18_400, 540_000, 61_000, 0, 0.64, false)
    }

    fun todos(sessionId: String): List<Todo> = if (sessionId != "s-hypernova") emptyList() else listOf(
        Todo("1", "Port the nebula breathing from 0stems", "Porting the nebula breathing", TodoStatus.Done),
        Todo("2", "Draw the stars from the shared atlas", "Drawing the stars from the shared atlas", TodoStatus.Done),
        Todo("3", "Fade the deep-sky objects behind text", "Fading the deep-sky objects behind text", TodoStatus.Active),
        Todo("4", "Check 30 fps on battery saver", "Checking 30 fps on battery saver", TodoStatus.Pending),
        Todo("5", "Run the screenshot tests", "Running the screenshot tests", TodoStatus.Pending),
    )
}
