package com.prudnikweb.hypernova.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why a request failed, in terms we can explain to a person. */
enum class Problem {
    /** The phone itself has no network. */
    NoNetwork,
    /** The Mac's name doesn't resolve on this network. */
    NoName,
    /** Nothing answered: the Mac is asleep, offline, or this phone isn't on its network (nor its VPN). */
    Unreachable,
    /** The Mac answered but nothing listens on the port: Hypernova isn't running there. */
    NotRunning,
    /** The secure connection failed. */
    Tls,
    /** A plain-http address in a build that only allows https. */
    Cleartext,
    /** The token was rejected: this phone was unpaired. */
    Unauthorized,
    /** The pairing code expired or was already used. */
    CodeExpired,
    /** The daemon answered with an error. */
    Server,
}

/** A failed call. [message] is the daemon's own human text when it sent one. */
class ApiException(
    val problem: Problem,
    val status: Int = 0,
    val code: String? = null,
    message: String? = null,
    cause: Throwable? = null,
    /** The daemon's whole error object (some carry more, like the text that's in the way). */
    val error: JSONObject? = null,
) : IOException(message ?: problem.name, cause) {
    val serverMessage: String? = message
}

private val JSON = "application/json; charset=utf-8".toMediaType()

/** One client for the app: short connect timeout (a Mac on the phone's own network answers quickly or not at all). */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Detects a dead socket even if the daemon's own pings stop arriving.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Uploads queue on their own, so prompts and approvals never wait behind a big file. */
    val uploads: OkHttpClient = client.newBuilder()
        .dispatcher(Dispatcher())
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private fun mentions(e: Throwable, word: String, depth: Int = 0): Boolean {
        if (depth > 6) return false
        if (e.message?.contains(word, ignoreCase = true) == true) return true
        if (e.suppressed.any { mentions(it, word, depth + 1) }) return true
        val c = e.cause
        return c != null && c !== e && mentions(c, word, depth + 1)
    }

    fun classify(e: Throwable): ApiException = when (e) {
        is ApiException -> e
        is UnknownHostException -> ApiException(Problem.NoName, cause = e)
        is ConnectException -> {
            // "Connection refused" means a machine answered; "timed out"/"unreachable" means nobody did.
            // OkHttp keeps the errno in the cause chain (and in suppressed route attempts).
            if (mentions(e, "refused")) ApiException(Problem.NotRunning, cause = e)
            else ApiException(Problem.Unreachable, cause = e)
        }
        is NoRouteToHostException, is SocketTimeoutException -> ApiException(Problem.Unreachable, cause = e)
        is SSLException -> ApiException(Problem.Tls, cause = e)
        is UnknownServiceException -> ApiException(Problem.Cleartext, cause = e)
        else -> ApiException(Problem.Unreachable, cause = e)
    }
}

/**
 * The daemon's REST API for the Mac paired at [base] (e.g. `http://10.0.2.2:47321`),
 * authenticated with the device [token]. Every call is cancellable and throws [ApiException].
 *
 * Each request goes by the route active for that base ([ActiveRoutes]): the Mac's own address on
 * the local network with the pinned client, or [base] itself. So does every URL built with [url],
 * which is how pictures, tickets, downloads and previews follow the route. [fixed] pins one route
 * instead (pairing over the local network, before there is a hub).
 */
class Api(val base: String, private val token: String?, private val fixed: NetRoute? = null) {

    /** The route requests go by right now (the terminal's socket takes its client too: [TermTicket]). */
    fun route(): NetRoute = fixed ?: ActiveRoutes.route(base)

    fun url(path: String): String = route().base.trimEnd('/') + path

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    suspend fun get(path: String, max: Long = 8L * 1024 * 1024): JSONObject = json(request(path).get().build(), max)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = json(request(path).post(body.toString().toRequestBody(JSON)).build())
    /** A POST the Mac takes a while to answer (restarting several jobs one after another): two minutes, the uploads client's. */
    suspend fun postSlow(path: String, body: JSONObject = JSONObject()): JSONObject = json(request(path).post(body.toString().toRequestBody(JSON)).build(), client = route().uploads)
    suspend fun put(path: String, body: JSONObject): JSONObject = json(request(path).put(body.toString().toRequestBody(JSON)).build())
    suspend fun delete(path: String): JSONObject = json(request(path).delete().build())
    /** A picked file as the body, streamed (uploads). */
    suspend fun upload(path: String, file: Upload): JSONObject {
        val body = object : RequestBody() {
            override fun contentType() = file.mime.toMediaTypeOrNull()
            override fun contentLength() = file.length
            override fun writeTo(sink: BufferedSink) = file.write(sink.outputStream())
        }
        return json(request(path).post(body).build(), client = route().uploads)
    }

    /** Raw bytes (files), with the server's content type. */
    suspend fun bytes(path: String, max: Long = 20L * 1024 * 1024): FileBytes {
        val r = execute(request(path).get().build(), max)
        if (r.code !in 200..299) throw errorOf(r.code, String(r.body))
        return FileBytes(r.body, r.type)
    }

    /**
     * Streams [pathOrUrl] (a daemon path like `/v1/file?path=…&download=1`, or a full page URL) into [target], never
     * through memory. With [resume] it asks for the rest (`Range` + `If-Range`) and appends when the Mac answers 206
     * from exactly that byte; otherwise it starts over. [onProgress] gets (written, total) as bytes arrive.
     */
    suspend fun download(pathOrUrl: String, target: File, resume: DownloadResume?, onStart: (etag: String?) -> Unit = {}, onProgress: (done: Long, total: Long) -> Unit): Downloaded =
        suspendCancellableCoroutine { cont ->
            val req = (if (pathOrUrl.startsWith("http")) Request.Builder().url(pathOrUrl).apply { if (token != null) header("Authorization", "Bearer $token") } else request(pathOrUrl))
                .apply { if (resume != null) { header("Range", "bytes=${resume.from}-"); header("If-Range", resume.etag) } }
                .get().build()
            val call = route().client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            Thread {
                try {
                    val result = call.execute().use { r ->
                        if (r.code == 416) {
                            // The part is past the end of what the Mac has now: start over.
                            target.delete()
                            throw ApiException(Problem.Unreachable, 416, "range")
                        }
                        if (r.code !in 200..299) throw errorOf(r.code, r.body?.string().orEmpty())
                        val len = r.body?.contentLength() ?: -1L
                        val place = Downloads.place(r.code, resume?.from ?: 0L, r.header("Content-Range"), len)
                            ?: run { target.delete(); throw ApiException(Problem.Unreachable, r.code, "bad_range") }
                        val total = if (place.total > 0) place.total else -1L
                        var done = place.offset
                        onStart(r.header("ETag"))
                        target.parentFile?.mkdirs()
                        java.io.FileOutputStream(target, place.offset > 0).use { out ->
                            if (place.offset == 0L) out.channel.truncate(0)
                            val src = r.body!!.source()
                            val buf = ByteArray(64 * 1024)
                            var lastTick = 0L
                            onProgress(done, total)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - lastTick > 80_000_000L) { lastTick = now; onProgress(done, total) }
                            }
                        }
                        onProgress(done, total)
                        if (total > 0 && done < total) throw java.io.EOFException("The connection dropped.")
                        Downloaded(done, r.header("ETag"))
                    }
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(if (call.isCanceled()) e else Net.classify(e))
                }
            }.apply { isDaemon = true; start() }
        }

    fun socket(path: String, listener: WebSocketListener): WebSocket {
        val wsUrl = url(path).replaceFirst(Regex("^http"), "ws")
        val req = Request.Builder().url(wsUrl).apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        return route().client.newWebSocket(req, listener)
    }

    private suspend fun json(req: Request, max: Long = 8L * 1024 * 1024, client: OkHttpClient = route().client): JSONObject {
        val r = execute(req, max, client)
        val text = String(r.body)
        if (r.code !in 200..299) throw errorOf(r.code, text)
        if (text.isBlank()) return JSONObject()
        // Parsed off the main thread: a long history is a few hundred kilobytes.
        return withContext(Dispatchers.Default) {
            runCatching { JSONObject(text) }.getOrElse { throw ApiException(Problem.Server, r.code, "bad_json") }
        }
    }

    private class Raw(val code: Int, val type: String?, val body: ByteArray)

    /** Runs [req] and reads the whole body on OkHttp's thread, never on the caller's. */
    private suspend fun execute(req: Request, max: Long, client: OkHttpClient = route().client): Raw = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(Net.classify(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        val body = r.body
                        val len = body?.contentLength() ?: -1L
                        if (len > max) throw ApiException(Problem.Server, 413, "too_large")
                        val bytes = body?.bytes() ?: ByteArray(0)
                        if (bytes.size > max) throw ApiException(Problem.Server, 413, "too_large")
                        if (cont.isActive) cont.resume(Raw(r.code, body?.contentType()?.let { "${it.type}/${it.subtype}" }, bytes))
                    }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(Net.classify(e))
                }
            }
        })
    }

    companion object {
        /** `{"error": {"code", "message"}}` → [ApiException]. */
        fun errorOf(status: Int, body: String): ApiException {
            val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val code = err?.str("code")
            val msg = err?.str("message")
            // Pairing maps its own 4xx to CodeExpired (see Pairing.pair). A gateway error without
            // the daemon's JSON means a proxy in front answered but Hypernova isn't running behind it.
            val problem = when {
                status == 401 -> Problem.Unauthorized
                status in 502..504 && err == null -> Problem.NotRunning
                else -> Problem.Server
            }
            return ApiException(problem, status, code, msg, error = err)
        }

        /** Query-string encode a path for `?path=`. */
        fun q(path: String): String = java.net.URLEncoder.encode(path, "UTF-8")
    }
}
