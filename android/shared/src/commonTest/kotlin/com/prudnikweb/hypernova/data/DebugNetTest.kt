package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.data.live.MapPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Each request as one line (data/DebugNet.kt): method, route with its ids, status, how long, sizes, a request id. No body or header. */
class DebugNetTest {
    private val dir: File = Files.createTempDirectory("debug-net").toFile()
    private val server = MockWebServer()
    private lateinit var l: DebugLogger

    @Before fun setUp() {
        server.start()
        l = DebugLogger(File(dir, "logs"), MapPrefs(), DebugHead("1", "b", "o", "d"))
        DebugLog.use(l)
    }

    @After fun tearDown() {
        l.turnOff()
        l.flush()
        DebugLog.use(null)
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private fun turnOn() {
        l.turnOn()
        DebugLog.use(l)
    }

    private fun text(): String {
        assertTrue(l.flush(10_000))
        return File(l.folder, "debug.log").readText()
    }

    @Test fun aRequestIsOneLineWithNoBodyNoHeaderNoQuery() {
        turnOn()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("X-Request-Id", "abc-123").setHeader("Set-Cookie", "s=PLANTEDCOOKIE").setBody("PLANTEDANSWER".repeat(100)))
        val req = Request.Builder()
            .url(server.url("/v1/sessions/s1/prompt?path=/home/me/PLANTEDPATH.txt&ticket=PLANTEDTICKET"))
            .header("Authorization", "Bearer PLANTEDTOKEN")
            .post("""{"text":"PLANTEDPROMPT"}""".toRequestBody("application/json".toMediaType()))
            .build()
        Net.client.newCall(req).execute().use { it.body!!.string() }
        // The line comes when the call is done, on OkHttp's side of closing the body.
        waitFor { text().contains("/v1/sessions/s1/prompt") }
        val text = text()
        assertTrue(text, Regex("""POST /v1/sessions/s1/prompt: 200 in \d+ ms, sent 24 B, got 1\.3 KB, request abc-123""").containsMatchIn(text))
        assertFalse(text, text.contains("PLANTED"))
    }

    @Test fun aRefusalAndAFailureSaySo() {
        turnOn()
        server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        Net.client.newCall(Request.Builder().url(server.url("/v1/a")).build()).execute().use { it.body!!.string() }
        runCatching { Net.client.newCall(Request.Builder().url(server.url("/v1/b")).build()).execute().use { it.body!!.string() } }
        waitFor { text().contains("GET /v1/b") }
        val text = text()
        assertTrue(text, text.contains("GET /v1/a: 409 in "))
        assertTrue(text, Regex("""W http +GET /v1/b: failed after \d+ ms""").containsMatchIn(text))
    }

    @Test fun offACallHasNoListenerAtAll() {
        assertFalse(DebugLog.on)
        val call = Net.client.newCall(Request.Builder().url(server.url("/v1/a")).build())
        assertSame(okhttp3.EventListener.NONE, DebugNet.events.create(call))
        server.enqueue(MockResponse().setBody("x"))
        call.execute().use { it.body!!.string() }
        assertFalse(l.folder.exists())
    }

    @Test fun thePinnedRoutesClientIsWatchedToo() {
        val pinned = PinnedTls.client("ab".repeat(32), null)
        assertSame(DebugNet.events, pinned.eventListenerFactory)
        assertSame(DebugNet.events, Net.uploads.eventListenerFactory)
    }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError(text())
            Thread.sleep(5)
        }
    }
}
