package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.AgentSettingsScreen
import com.prudnikweb.hypernova.ui.settings.AgentVersionSection
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An agent's Settings › Version (ui/settings/AgentVersionSection.kt): a check, the confirm and the fingerprint, what came of it, each server its own. */
private class VersionHub(val server: String = "z2", private val base: FakeHub = FakeHub()) : Hub by base {
    val calls = mutableListOf<String>()
    var look: Result<JSONObject> = Result.success(version())
    var checked: Result<JSONObject> = Result.success(version())
    var updated: Result<JSONObject> = Result.success(version())
    override val mac get() = base.mac.copy(name = server)
    override suspend fun agentExt(method: String, path: String, body: JSONObject?, slow: Boolean): Result<JSONObject> {
        calls += "$method $path ${body ?: ""}".trim()
        return when (path.substringBefore('?')) {
            "/v1/agents/version" -> look
            "/v1/agents/version/check" -> checked
            "/v1/agents/version/update" -> updated
            else -> Result.failure(UnsupportedOperationException())
        }
    }
}

private fun version(installed: Boolean = true, v: String? = "2.1.316", block: JSONObject.() -> Unit = {}) =
    JSONObject().put("agent", "claude").put("installed", installed).put("version", v ?: JSONObject.NULL).put("running", 0).apply(block)
private fun checked(latest: String = "2.1.320", v: String = "2.1.316", block: JSONObject.() -> Unit = {}) = version(v = v) {
    put("words", "the native installer")
    put("channel", "latest")
    put("autoUpdates", true)
    put("latest", latest)
    put("newer", true)
    put("canUpdate", true)
    put("effect", "Sessions already running stay on the version they started with.")
    block()
}
private fun job(state: String, message: String?, vararg output: String) = JSONObject().put("state", state).put("message", message ?: JSONObject.NULL).put("output", org.json.JSONArray(output.toList()))

@RunWith(RobolectricTestRunner::class)
class AgentVersionSectionTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = VersionHub()
    private var checks = 0

    @Before fun fingerprint() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        OwnerCheck.prompt = { pass, _ ->
            checks++
            pass()
        }
    }
    @After fun tearDown() {
        OwnerCheck.prompt = null
    }

    private fun open(agent: String = AgentSettingsReply.CLAUDE) {
        rule.setContent { Harness(spy) { AgentVersionSection(hub, agent) } }
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).assertExists()
    private fun gone(text: String) = rule.onNodeWithText(text, substring = true).assertDoesNotExist()
    private fun tap(id: Int, vararg args: Any) {
        rule.onNodeWithText(str(id, *args)).performClick()
        rule.waitForIdle()
    }

    @Test fun openingShowsWhatsInstalledAndAsksNothingOutside() {
        open()
        seen("2.1.316")
        seen(str(R.string.av_check))
        gone(str(R.string.av_update, "2.1.320"))
        assertEquals(listOf("GET /v1/agents/version?agent=claude"), hub.calls.map { it.trim() })
    }

    @Test fun aCheckNeedsNoFingerprintAndOffersTheUpdate() {
        hub.checked = Result.success(checked())
        open()
        tap(R.string.av_check)
        assertEquals(0, checks)
        assertEquals(0, spy.confirms.size)
        assertTrue(hub.calls.last().startsWith("POST /v1/agents/version/check"))
        assertFalse(hub.calls.last().contains("confirmRun"))
        seen(str(R.string.av_installed_with, "the native installer"))
        seen(str(R.string.av_newest))
        seen(str(R.string.av_also_itself, str(R.string.cx_agent_claude)))
        seen(str(R.string.av_update, "2.1.320"))
        rule.onRoot().snap("agent_version_newer")
    }

    @Test fun nothingNewerSaysSoAndOffersNoUpdate() {
        hub.checked = Result.success(checked(latest = "2.1.316") { put("newer", false); put("canUpdate", false) })
        open()
        tap(R.string.av_check)
        seen(str(R.string.av_nothing_newer))
        gone(str(R.string.av_update, "2.1.316"))
    }

    @Test fun anUpdateSaysWhatItDoesThenTheFingerprintThenSendsOnlyTheClaim() {
        hub.look = Result.success(checked { put("running", 2) })
        hub.updated = Result.success(checked(v = "2.1.320") {
            put("newer", false)
            put("canUpdate", false)
            put("job", job("updated", "Claude Code is now 2.1.320, was 2.1.316.", "Installing update..."))
        })
        open()
        tap(R.string.av_update, "2.1.320")
        // Nothing yet: the confirm says what changes and what happens to what's running.
        assertFalse(hub.calls.any { it.startsWith("POST") })
        assertEquals(0, checks)
        val ask = spy.confirms.single()
        assertEquals(str(R.string.pl_run_title, hub.mac.name), ask.title)
        assertTrue(ask.danger)
        assertTrue(ask.body, ask.body.contains("from 2.1.316 to 2.1.320, using the native installer"))
        assertTrue(ask.body, ask.body.contains(context.resources.getQuantityString(R.plurals.av_confirm_running, 2, 2)))
        assertTrue(ask.body, ask.body.contains("Sessions already running stay"))
        ask.onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        val sent = hub.calls.single { it.startsWith("POST") }
        assertTrue(sent, sent.startsWith("POST /v1/agents/version/update"))
        assertEquals(setOf("agent", "confirmRun"), JSONObject(sent.substringAfter(' ').substringAfter(' ')).keys().asSequence().toSet())
        seen("Claude Code is now 2.1.320, was 2.1.316.")
    }

    @Test fun aCodexPastWhatTheServerWasCheckedWithIsSaidOnTheConfirm() {
        hub.look = Result.success(checked { put("warning", "This Hypernova was last checked against Codex 0.164.0, and 0.166.0 is newer.") })
        open(AgentSettingsReply.CODEX)
        tap(R.string.av_update, "2.1.320")
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("last checked against Codex 0.164.0"))
        assertTrue(ask.body, ask.body.contains(str(R.string.cx_agent_codex)))
    }

    @Test fun aCancelledFingerprintUpdatesNothing() {
        OwnerCheck.prompt = { _, fail ->
            checks++
            fail(null)
        }
        hub.look = Result.success(checked())
        open()
        tap(R.string.av_update, "2.1.320")
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertFalse(hub.calls.any { it.startsWith("POST") })
    }

    @Test fun whatCantUpdateSaysWhyAndShowsTheCommandWithNoButton() {
        hub.checked = Result.success(version {
            put("words", "apt")
            put("latest", "2.1.320")
            put("newer", true)
            put("canUpdate", false)
            put("why", "A system package from apt: updating it needs root, so it can’t update from here.")
            put("command", "sudo apt update && sudo apt upgrade claude-code")
        })
        open()
        tap(R.string.av_check)
        seen("needs root")
        seen("sudo apt update && sudo apt upgrade claude-code")
        gone(str(R.string.av_update, "2.1.320"))
        rule.onRoot().snap("agent_version_cant")
    }

    @Test fun whileItRunsThereIsNoButtonAndItShowsItsLastLines() {
        // Past the first look it's ended, so this page's asking again stops by itself.
        rule.mainClock.autoAdvance = false
        hub.look = Result.success(checked { put("job", job("running", null, "Installing update...")) })
        open()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        seen(str(R.string.av_updating))
        seen("Installing update...")
        gone(str(R.string.av_check))
        gone(str(R.string.av_update, "2.1.320"))
        rule.onRoot().snap("agent_version_running")
    }

    @Test fun itAsksAgainWhileItRunsAndShowsHowItEnded() {
        hub.look = Result.success(checked { put("job", job("running", null, "working")) })
        rule.mainClock.autoAdvance = false
        open()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        seen("working")
        val before = hub.calls.size
        hub.look = Result.success(version(v = "2.1.320") { put("job", job("updated", "Claude Code is now 2.1.320, was 2.1.316.", "Done")) })
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertTrue(hub.calls.size > before)
        seen("Claude Code is now 2.1.320, was 2.1.316.")
        seen(str(R.string.av_check))
    }

    @Test fun aFailureIsPlainAndWhatItSaidOpensOnRequest() {
        hub.look = Result.success(checked { put("job", job("failed", "Claude Code’s update didn’t finish. It’s still 2.1.316.", "npm ERR! code EACCES")) })
        open()
        seen("didn’t finish")
        gone("npm ERR!")
        tap(R.string.av_output_show)
        seen("npm ERR! code EACCES")
        // It can be tried again.
        seen(str(R.string.av_update, "2.1.320"))
        rule.onRoot().snap("agent_version_failed")
    }

    @Test fun versionUnchangedIsSaidAsTheServerSaysIt() {
        hub.look = Result.success(checked { put("job", job("unchanged", "Claude Code is still 2.1.316: the update changed nothing.")) })
        open()
        seen("the update changed nothing")
        gone(str(R.string.av_output_show))
    }

    @Test fun aRefusalKeepsItsWords() {
        hub.look = Result.success(checked())
        hub.updated = Result.failure(AgentSettingRefused("update_running", "Claude Code is already updating here."))
        open()
        tap(R.string.av_update, "2.1.320")
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        seen("already updating here")
    }

    @Test fun anOlderServerSaysToUpdateItAndOffersNothing() {
        hub.look = Result.failure(AgentSettingsTooOld(str(R.string.as_edit_too_old)))
        open()
        seen(str(R.string.av_too_old, hub.mac.name, str(R.string.cx_agent_claude)))
        gone(str(R.string.av_check))
        assertEquals(1, hub.calls.size)
    }

    @Test fun notInstalledSaysSo() {
        hub.look = Result.success(version(installed = false, v = null))
        open(AgentSettingsReply.CODEX)
        seen(str(R.string.ag_not_installed, hub.mac.name))
        gone(str(R.string.av_check))
    }

    @Test fun eachServerShowsItsOwn() {
        val other = VersionHub("gym").also { it.look = Result.success(version(v = "2.1.200")) }
        hub.look = Result.success(version(v = "2.1.316"))
        rule.setContent {
            Harness(spy) {
                androidx.compose.foundation.layout.Column {
                    AgentVersionSection(hub, AgentSettingsReply.CLAUDE)
                    AgentVersionSection(other, AgentSettingsReply.CLAUDE)
                }
            }
        }
        rule.waitForIdle()
        seen("2.1.316")
        seen("2.1.200")
        assertEquals(1, hub.calls.size)
        assertEquals(1, other.calls.size)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun itsInTheAgentsSettingsAboveTheExtensions() {
        rule.setContent { Harness(spy) { AgentSettingsScreen(hub, AgentSettingsReply.CLAUDE, onBack = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.av_section), ignoreCase = true).performScrollTo().assertExists()
        rule.onNodeWithText(str(R.string.av_check)).performScrollTo().assertExists()
    }
}
