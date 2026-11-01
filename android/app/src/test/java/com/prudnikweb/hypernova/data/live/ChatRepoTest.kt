package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.ui.session.ChatRepo
import com.prudnikweb.hypernova.ui.session.Rewound
import com.prudnikweb.hypernova.ui.session.TodoStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The chat's own calls (ChatRepo; docs/PROTOCOL.md, "Session usage, the to-do list and Rewind") to
 * the computer [Core] has paired, answered with the daemon's contract fixtures: the usage strip
 * (FEATURES C7), the to-do list (C8) and Rewind to here (C9), and what each says when it can't.
 */
@RunWith(RobolectricTestRunner::class)
class ChatRepoTest {
    private val daemon = FakeDaemon("z2")

    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())

    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(3)
        }
    }

    @Before fun setUp() {
        Core.init(RuntimeEnvironment.getApplication())
        daemon.snapshot = snapshot("z2", sessions = listOf(session("s1")), platform = "darwin")
        Core.paired(Pairing(daemon.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected }
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        daemon.close()
    }

    private fun words(id: Int) = Core.app.getString(id)

    @Test fun theUsageStripReadsTheSessionsUsage() = runBlocking {
        daemon.ok("GET", "/v1/sessions/s1/usage", fixture("rest-session-usage"))
        val u = ChatRepo.usage("s1").getOrThrow()
        assertEquals(ChatRepo.parseUsage(fixture("rest-session-usage").getJSONObject("usage")), u)
        assertEquals(20_100L, u.tokens)
        assertEquals("Bearer tok-z2", daemon.calls("GET", "/v1/sessions/s1/usage").single().auth)
    }

    @Test fun theToDoListReadsClaudesList() = runBlocking {
        daemon.ok("GET", "/v1/sessions/s1/todos", fixture("rest-session-todos"))
        val t = ChatRepo.todos("s1").getOrThrow()
        assertEquals(listOf(TodoStatus.Done, TodoStatus.Active, TodoStatus.Pending), t.map { it.status })
        assertEquals("Running the tests", t[1].active)
    }

    @Test fun rewindGoesOnInTheNewSessionWithTheMessageForThePromptBox() = runBlocking {
        val f = fixture("rest-session-rewind")
        daemon.ok("POST", "/v1/sessions/s1/rewind", f)
        val r = ChatRepo.rewind("s1", "u:abc").getOrThrow()
        assertEquals(Rewound(f.getJSONObject("session").getString("id"), "Now make it faster", emptyList()), r)
        assertEquals("u:abc", daemon.calls("POST", "/v1/sessions/s1/rewind").single().json.getString("eventId"))
    }

    @Test fun whatTheyCantDoIsTheServersWordsOrSaysTheServerIsOlder() = runBlocking {
        daemon.on("POST", "/v1/sessions/s1/rewind") { refusal(409, "first_message", "That’s where the conversation starts. Start a new session instead.") }
        assertEquals("That’s where the conversation starts. Start a new session instead.", ChatRepo.rewind("s1", "u:1").exceptionOrNull()?.message)
        // An older Mac has no such route: 404 with no words of its own.
        daemon.on("GET", "/v1/sessions/s1/usage") { json(404, JSONObject()) }
        assertEquals(words(R.string.chat_err_old_mac), ChatRepo.usage("s1").exceptionOrNull()?.message)
        daemon.on("GET", "/v1/sessions/s1/todos") { refusal(404, "no_session", "That session no longer exists.") }
        assertEquals("That session no longer exists.", ChatRepo.todos("s1").exceptionOrNull()?.message)
        // A rewind answer without a session is the app's own words.
        daemon.ok("POST", "/v1/sessions/s1/rewind", JSONObject())
        assertEquals(words(R.string.chat_err_mac), ChatRepo.rewind("s1", "u:1").exceptionOrNull()?.message)
    }
}
