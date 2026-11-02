package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.linkOf
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.takesCode
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.imageio.ImageIO

/**
 * Pictures of the screens with no window, in desktop/build/renders, for someone to look at: these tests only say
 * they drew something. The data is invented. A window on a display is another thing (docs/PLATFORMS.md, "The desktop app").
 */
@OptIn(ExperimentalTestApi::class)
class RendersTest {
    private val daemons = mutableListOf<FakeDaemon>()

    private fun daemon(name: String = "atlas") = FakeDaemon(name, lan = true).also { daemons += it }

    @Before fun fresh() = DesktopRig.reset()

    @After fun tearDown() {
        DesktopRig.reset()
        daemons.forEach { it.close() }
    }

    private fun sessions() = listOf(
        session("5e550009", state = "needs_you", title = "Add idempotency keys to the payments webhook") { put("projectName", "ledgerline").put("activity", "Bash: npm run db:migrate -- --env staging") },
        session("5e550011", state = "needs_you", title = "Rewrite the importer's error handling") { put("projectName", "parcelkit").put("activity", "Edit: src/import/errors.ts") },
        session("5e550002", state = "needs_you", title = "Conflict rules for offline deletes") { put("projectName", "orchard-sync").put("activity", "Asking you a question") },
        session("5e550001", state = "working", title = "Stream invoice exports instead of buffering") { put("projectName", "ledgerline").put("activity", "Bash: gh run watch --exit-status") },
        session("5e550003", state = "working", title = "Wrong cursor position when pasting into a list") {
            put("projectName", "quillpad").put("activity", "Bash: npm test -- editor/lists")
            put("todo", JSONObject().put("active", "Running the editor tests").put("done", 3).put("total", 7))
        },
        session("5e550006", state = "idle", title = "Table of contents in exported PDFs") { put("projectName", "quillpad").put("lastText", "Done. Headings up to level 3 now link to their pages.").put("activity", "Done. Headings up to level 3 now link to their pages.") },
        session("5e550007", state = "idle", title = "Move CI to Postgres 17") { put("projectName", "ledgerline").put("lastText", "CI is green on 17.").put("activity", "CI is green on 17.") },
    )

    private fun approvals() = listOf(
        approval("a1", "5e550009", summary = "Bash: npm run db:migrate -- --env staging") {
            put("input", JSONObject().put("command", "npm run db:migrate -- --env staging"))
        },
        approval("a2", "5e550011", summary = "Edit: src/import/errors.ts") {
            put("toolName", "Edit").put("input", JSONObject().put("file_path", "src/import/errors.ts").put("old_string", "a").put("new_string", "b"))
        },
        approval("a3", "5e550002", kind = "question", summary = "Which rule wins?") { put("toolName", "AskUserQuestion") },
    )

    private fun ok(file: java.io.File) {
        val img = ImageIO.read(file)
        val colours = HashSet<Int>()
        for (x in 0 until img.width step 7) for (y in 0 until img.height step 7) colours += img.getRGB(x, y)
        assertTrue("${file.name} has something on it", colours.size > 20)
    }

    @Test fun thePairScreen() = runDesktopComposeUiTest(960, 720) {
        setContent { App(shell, reduced = true) }
        ok(render("01-pair"))
    }

    @Test fun thePairScreenWithALink() = runDesktopComposeUiTest(960, 720) {
        val d = daemon()
        setContent { App(shell, reduced = true) }
        main { shell.link = linkOf(d) }
        ok(render("02-pair-link"))
    }

    @Test fun aPairingThatFailed() = runDesktopComposeUiTest(960, 720) {
        val d = daemon()
        d.on("POST", "/v1/pair") { refusal(403, "code_expired", "This pairing code has expired or was already used.") }
        setContent { App(shell, reduced = true) }
        main { shell.link = linkOf(d); shell.submit() }
        until("it to fail") { shell.pair is PairState.Failed }
        ok(render("03-pair-failed"))
    }

    @Test fun theListWithAnApproval() = runDesktopComposeUiTest(960, 1240) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions(), approvals = approvals())
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.size == 7 }
        ok(render("04-list-approval"))
    }

    @Test fun theListAtTheSmallestWindow() = runDesktopComposeUiTest(520, 420) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions(), approvals = approvals())
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.size == 7 }
        ok(render("05-list-smallest"))
    }

    @Test fun theListWide() = runDesktopComposeUiTest(1600, 900) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions(), approvals = approvals())
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.size == 7 }
        ok(render("06-list-wide"))
    }

    @Test fun aRefusedAnswer() = runDesktopComposeUiTest(960, 720) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions().take(1), approvals = approvals().take(1))
        d.on("POST", "/v1/approvals/a1") { refusal(500, "boom", "The hook is gone: this session stopped waiting.") }
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        onNodeWithText(Ui.allow.text()).performClick()
        until("the refusal") { shell.toast != null }
        ok(render("07-refused"))
    }

    @Test fun offlineAndWhy() = runDesktopComposeUiTest(960, 900) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions(), approvals = approvals())
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.size == 7 }
        d.down = true
        d.closeSocket()
        until("offline", timeoutMs = 90_000) { Core.live?.link?.state == LinkState.Offline }
        ok(render("08-offline"))
    }

    @Test fun theFocusShows() = runDesktopComposeUiTest(960, 420) {
        val d = daemon()
        d.snapshot = snapshot("atlas", sessions = sessions().take(1), approvals = approvals().take(1))
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Tab); pressKey(Key.Tab); pressKey(Key.Tab) }
        onNodeWithText(Ui.allow.text()).assertIsFocused()
        ok(render("10-focus"))
    }

    @Test fun twoServers() = runDesktopComposeUiTest(960, 720) {
        val a = daemon("atlas")
        val b = daemon("juniper")
        a.snapshot = snapshot("atlas", sessions = sessions().take(2), approvals = approvals().take(2))
        b.snapshot = snapshot("juniper", sessions = sessions().drop(3).take(2))
        a.takesCode()
        setContent { App(shell, reduced = true) }
        pairedWith(a)
        waitForIdle()
        pairedWith(b)
        until("juniper's sessions") { Core.live?.sessions?.size == 2 }
        ok(render("09-two-servers"))
    }
}

