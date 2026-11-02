package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.PlayInfo
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.data.Speaking
import com.prudnikweb.hypernova.ui.voice.SpeakingBar
import com.prudnikweb.hypernova.ui.voice.SpeakingBarHost
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The speaking bar (ui/voice/Speaking.kt): "Starting…" until the voice plays, then "Speaking", and
 * Stop. The app's own bar follows the real [Speaker], whose play here waits on a server that never
 * answers; its Stop ends the play and tells the Mac it was the phone.
 */
@RunWith(RobolectricTestRunner::class)
class SpeakingBarTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val server = MockWebServer()

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(context as android.app.Application)
        server.start()
    }

    @After fun tearDown() {
        Speaker.stop("phone")
        shadowOf(Looper.getMainLooper()).idle()
        runCatching { server.shutdown() }
    }

    private val play = PlayInfo("play-0123456789", Samples.BUILD, "phone", "mac", 24_000, 42)

    @Test fun startingThenSpeakingAndStop() {
        var stops = 0
        val startedState = androidx.compose.runtime.mutableStateOf(false)
        // With motion on: "Starting to speak" until the voice plays (Reduce Motion says Speaking at once).
        rule.setContent { Harness(spy) { moving { SpeakingBar(Speaking(play, "a1", startedState.value), onStop = { stops++ }) } } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.speaking_starting).uppercase()).assertExists()
        startedState.value = true
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.speaking_label).uppercase()).assertExists()
        rule.onNodeWithText(str(R.string.speaking_stop).uppercase()).performClick()
        assertEquals(1, stops)
    }

    @androidx.compose.runtime.Composable
    private fun moving(content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.runtime.CompositionLocalProvider(com.prudnikweb.hypernova.ui.theme.LocalMotion provides com.prudnikweb.hypernova.ui.theme.MotionPrefs(reduced = false), content = content)

    @Test fun theBarAsDrawn() {
        rule.setContent { Harness(spy) { SpeakingBar(Speaking(play, "a1", started = true), onStop = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.speaking_label).uppercase()).assertExists()
        rule.snap("speaking_bar")
    }

    @Test fun theAppsBarFollowsTheSpeakerAndItsStopEndsThePlay() {
        // The audio never comes: the play stays "starting" until it's stopped.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val reports = CopyOnWriteArrayList<String>()
        rule.setContent { Harness(spy) { moving { SpeakingBarHost(hidden = false) } } }
        Speaker.play(play, "a1", "hypernova", open = { OkHttpClient().newCall(Request.Builder().url(server.url("/v1/speech/plays/play-0123456789/audio")).build()) }, report = { reports += it })
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.speaking_starting).uppercase()).assertExists()
        rule.waitUntil(5_000) { server.requestCount == 1 }
        rule.onNodeWithText(str(R.string.speaking_stop).uppercase()).performClick()
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); Speaker.now == null && reports.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.speaking_stop).uppercase()).assertDoesNotExist()
        val last = JSONObject(reports.last())
        assertEquals("speech.progress", last.getString("t"))
        assertEquals("play-0123456789", last.getString("id"))
        assertEquals("stopped", last.getString("state"))
        assertEquals("phone", last.getString("why"))
        assertNull(Speaker.now)
    }
}
