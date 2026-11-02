package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.ClientLogTooOld
import com.prudnikweb.hypernova.data.ConnLog
import com.prudnikweb.hypernova.data.DebugHead
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.DebugLogger
import com.prudnikweb.hypernova.data.DebugSendFlow
import com.prudnikweb.hypernova.data.DebugSendState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * The debug log with the real hub, the real socket and the real requests: what a session's content does
 * to the file (nothing), and Send logs, end to end, against a daemon that takes it and one that's too old.
 */
@RunWith(RigRunner::class)
class DebugLogHubTest {
    private val daemon = FakeDaemon("z2")
    private val rig by lazy { HubRig(daemon) }
    private val root: File = Files.createTempDirectory("debug-hub").toFile()
    private lateinit var logger: DebugLogger

    @Before fun setUp() {
        logger = DebugLogger(File(root, "logs"), rig.storage.prefs("debuglog-hub-test"), DebugHead("1.2.3 (45)", "debug build", "Test OS", "Test Model"))
        DebugLog.use(logger)
    }

    @After fun tearDown() {
        logger.turnOff()
        logger.flush()
        DebugLog.use(null)
        rig.close()
        root.deleteRecursively()
    }

    private fun turnOn() {
        logger.turnOn()
        DebugLog.use(logger)
    }

    private fun text(): String {
        assertTrue(logger.flush(10_000))
        return File(logger.folder, "debug.log").readText()
    }

    /** A session whose every word of content is planted. */
    private fun planted(id: String) = session(id, state = "working", title = "PLANTEDTITLE") {
        put("cwd", "/home/me/PLANTEDFOLDER").put("projectName", "PLANTEDPROJECT").put("activity", "Bash: PLANTEDACTIVITY").put("name", "PLANTEDNAME")
    }

    @Test fun whatASessionSaysStaysOutOfTheFile() {
        turnOn()
        daemon.snapshot = snapshot(sessions = listOf(planted("s1")), approvals = listOf(approval("a1", "s1", summary = "Bash: PLANTEDCOMMAND")))
        rig.connect()
        // The live feed: a reply, a tool's arguments, a new session, an approval, a broken message.
        rig.push(msg("event") { put("sessionId", "s1").put("event", JSONObject().put("id", "e1").put("kind", "assistant").put("text", "PLANTEDREPLY")) }) { true }
        rig.push(msg("event") { put("sessionId", "s1").put("event", JSONObject().put("id", "e2").put("kind", "tool").put("tool", "Bash").put("input", JSONObject().put("command", "PLANTEDARGS")).put("output", "PLANTEDOUTPUT")) }) { true }
        rig.push(msg("session") { put("session", planted("s2")) }) { rig.hub.session("s2") != null }
        daemon.socket!!.send("{\"t\":\"event\" PLANTEDBROKEN")
        // Requests with content going out and a refusal with the server's words coming back.
        daemon.on("POST", "/v1/sessions/s1/prompt") { refusal(409, "busy", "PLANTEDSERVERWORDS") }
        rig.call { rig.hub.send("s1", "PLANTEDPROMPT") }
        daemon.on("POST", "/v1/sessions/s1/rename") { refusal(400, "bad_name", "PLANTEDSERVERWORDS") }
        rig.call { rig.hub.renameSession("s1", "PLANTEDNEWNAME") }
        daemon.on("POST", "/v1/attachments") { FakeDaemon.json(200, JSONObject().put("attachment", JSONObject().put("path", "/x/PLANTEDATTACHED").put("name", "PLANTEDATTACHED").put("size", 1))) }
        rig.call { rig.hub.upload(Upload("PLANTEDFILE.txt", "text/plain", 1) { it.write(65) }) }
        daemon.closeSocket(1001, "going away PLANTEDREASON")
        waitFor("the requests' lines") { text().contains("/v1/sessions/s1/rename") }

        val text = text()
        // It is logging: the things that should be there are.
        assertTrue(text, text.contains("\"session\","))
        assertTrue(text, text.contains("\"event\","))
        assertTrue(text, text.contains("session s1"))
        assertTrue(text, text.contains("\"unreadable\""))
        assertTrue(text, text.contains("POST /v1/sessions/s1/prompt"))
        assertTrue(text, text.contains("POST /v1/attachments"))
        assertTrue(text, text.contains("live socket to z2 open"))
        assertTrue(text, text.contains("GET /v1/snapshot: 200"))
        // The connection log's events come along.
        assertTrue(text, text.contains("starting the link") || text.contains("the live socket is open"))
        // And nothing of the content, or the token.
        assertFalse(text.lines().firstOrNull { "PLANTED" in it }.orEmpty(), text.contains("PLANTED"))
        assertFalse(text, text.contains(rig.pairing.token))
    }

    @Test fun offNothingIsWrittenHoweverBusyItIs() {
        daemon.snapshot = snapshot(sessions = listOf(planted("s1")))
        rig.connect()
        rig.push(msg("session") { put("session", planted("s2")) }) { rig.hub.session("s2") != null }
        ConnLog.clear()
        assertTrue(logger.flush())
        assertFalse(logger.folder.exists())
    }

    // ── Send logs ──

    /** The flow as Settings has it, with the rig's scope and the hubs given. */
    private fun flow(vararg hubs: Pair<String, Hub>) = DebugSendFlow(rig.scope, { logger }, {
        hubs.map { (name, h) -> ServerStatus(name, name, LinkState.Connected, 0, false) to h }
    })

    private fun untilSettled(f: DebugSendFlow) = rig.until("the send to settle") {
        val s = f.state
        s !is DebugSendState.Sending && s !is DebugSendState.Idle && s !is DebugSendState.Choosing
    }

    private val saved = JSONObject().put("log", JSONObject().put("path", "/srv/support/client-logs/dev-1/2027-x.log").put("name", "2027-x.log").put("size", 10).put("at", 1_800_000_000_000L))

    @Test fun sendGoesToTheOneServerAsPlainTextAndSaysWhereItLanded() {
        rig.connect()
        turnOn()
        logger.put(com.prudnikweb.hypernova.data.DebugLevel.I, "t", "a line to send", null)
        logger.crashed(Thread.currentThread(), IllegalStateException("x"))
        daemon.ok("POST", "/v1/client-logs", saved)
        val f = flow("z2" to rig.hub)
        f.send()
        untilSettled(f)
        val sent = f.state as DebugSendState.Sent
        assertEquals("z2", sent.server)
        assertEquals("/srv/support/client-logs/dev-1/2027-x.log", sent.saved.path)
        val req = daemon.calls("POST", "/v1/client-logs").single()
        // The client chooses nothing about where it goes.
        assertEquals("/v1/client-logs", req.path)
        assertTrue(req.headers["content-type"].orEmpty(), req.headers["content-type"].orEmpty().startsWith("text/plain"))
        assertEquals("Bearer ${rig.pairing.token}", req.auth)
        assertTrue(req.body, req.body.contains("a line to send"))
        assertTrue("the crash goes along", req.body.contains("=== Crash"))
        // Nothing's left behind to send twice.
        assertFalse(File(logger.folder, "outgoing").exists())
    }

    @Test fun withMoreThanOneServerItAsksAndOnlyThatOneGetsIt() {
        val other = FakeDaemon("shed")
        val rig2 = HubRig(other, serverId = "shed", dispatcher = rig.dispatcher)
        try {
            rig.connect()
            rig2.connect()
            turnOn()
            logger.put(com.prudnikweb.hypernova.data.DebugLevel.I, "t", "x", null)
            daemon.ok("POST", "/v1/client-logs", saved)
            other.ok("POST", "/v1/client-logs", saved)
            val f = flow("z2" to rig.hub, "shed" to rig2.hub)
            f.send()
            val choosing = f.state as DebugSendState.Choosing
            assertEquals(listOf("z2", "shed"), choosing.servers.map { it.name })
            assertEquals("nothing's sent before a server is picked", 0, daemon.count("POST", "/v1/client-logs") + other.count("POST", "/v1/client-logs"))
            f.sendTo("shed")
            untilSettled(f)
            assertEquals("shed", (f.state as DebugSendState.Sent).server)
            assertEquals(1, other.count("POST", "/v1/client-logs"))
            assertEquals(0, daemon.count("POST", "/v1/client-logs"))
        } finally {
            rig2.close()
            other.close()
        }
    }

    @Test fun anOlderServerSaysItNeedsUpdating() {
        rig.connect()
        turnOn()
        logger.put(com.prudnikweb.hypernova.data.DebugLevel.I, "t", "x", null)
        // No route for it: FakeDaemon answers 404, as a daemon from before this does.
        val f = flow("z2" to rig.hub)
        f.send()
        untilSettled(f)
        assertEquals(DebugSendState.TooOld("z2"), f.state)
        assertTrue(rig.call { rig.hub.sendClientLog(Upload("x", "text/plain", 1) { it.write(65) }) }.exceptionOrNull() is ClientLogTooOld)
    }

    @Test fun aRefusalSaysTheServersWordsAndAnUnreachableServerSaysThat() {
        rig.connect()
        turnOn()
        logger.put(com.prudnikweb.hypernova.data.DebugLevel.I, "t", "x", null)
        daemon.on("POST", "/v1/client-logs") { refusal(429, "too_many_logs", "That's a lot of logs in an hour.") }
        val f = flow("z2" to rig.hub)
        f.send()
        untilSettled(f)
        assertEquals(DebugSendState.Failed("z2", "That's a lot of logs in an hour."), f.state)
        f.dismiss()
        assertEquals(DebugSendState.Idle, f.state)

        daemon.on("POST", "/v1/client-logs") { refusal(500, "error", null) }
        f.send()
        untilSettled(f)
        assertEquals(DebugSendState.Failed("z2", rig.res.getString(Str.debuglog_send_failed)), f.state)
    }

    @Test fun nothingIsSentWithoutATap() {
        rig.connect()
        turnOn()
        logger.put(com.prudnikweb.hypernova.data.DebugLevel.I, "t", "x", null)
        logger.flush()
        rig.pump()
        assertEquals(0, daemon.count("POST", "/v1/client-logs"))
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what: ${text()}")
            rig.pump()
            Thread.sleep(5)
        }
    }
}
