package com.prudnikweb.hypernova.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.AppUpdater
import com.prudnikweb.hypernova.data.AutomationNotes
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.RunAsk
import com.prudnikweb.hypernova.data.UpdatePolicy
import com.prudnikweb.hypernova.data.UpdateState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.settings.SettingsJump
import com.prudnikweb.hypernova.ui.share.LauncherShortcuts
import com.prudnikweb.hypernova.ui.share.ShareLaunch
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MainActivity is exported, for the launcher and pairing links, so any app on the phone can send it
 * any intent. A command (open a session, switch server, start an update, "+2 h", ...) counts only
 * through the non-exported alias AppCommands (docs/DECISIONS.md, "Commands only from the app itself").
 * Through the real activity, [Core], hubs and scripted daemons. The same outside path is what a
 * notification posted before the update takes: it opens the app and does nothing else.
 *
 * Robolectric doesn't refuse a non-exported target, so that Android does is the manifest test here
 * plus the emulator (the same decision records it). Here: what the activity does with each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h900dp")
class IntentGuardTest {
    private val app get() = AndroidCore.app
    private val daemons = mutableListOf<FakeDaemon>()
    private lateinit var z2: FakeDaemon
    private lateinit var mini: FakeDaemon
    private lateinit var z2Id: String
    private lateinit var miniId: String

    private val apk = ByteArray(1_000) { (it % 251).toByte() }

    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    /** A moment for anything an intent wrongly set off to reach the daemon. */
    private fun settle() {
        val until = System.nanoTime() + 300_000_000
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** A paired daemon that has a newer app on offer, so the update command has something to start. */
    private fun daemon(name: String): FakeDaemon {
        val d = FakeDaemon(name).also(daemons::add)
        d.snapshot = snapshot(name, sessions = listOf(session("s-$name", state = "idle", title = "Fix the parser"))).apply {
            getJSONObject("mac").put("app", org.json.JSONObject().put("versionCode", BuildConfig.VERSION_CODE + 1).put("versionName", "9.9.9"))
        }
        d.on("GET", UpdatePolicy.APK_PATH) { MockResponse().setResponseCode(200).setBody(Buffer().write(apk)) }
        d.ok("POST", "/v1/mac/awake")
        return d
    }

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(app.storage)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        z2 = daemon("z2")
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-z2"))
        z2Id = Core.activeId!!
        mini = daemon("mini")
        Core.paired(Pairing(mini.base, "tok-mini", "mini", "m-mini", "dev-mini"))
        miniId = Core.activeId!!
        assertNotEquals(z2Id, miniId)
        until("both linked") { Core.everyHub.size == 2 && Core.everyHub.all { it.second.link.state == LinkState.Connected } }
        until("the update on offer") { Core.live?.appUpdate != null }
        ShareLaunch.newSession = null
        SettingsJump.shown()
    }

    @After fun tearDown() {
        AppUpdater.dismiss(app)
        AppUpdater.cleanUp(app)
        ShareLaunch.newSession = null
        SettingsJump.shown()
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        daemons.forEach { it.close() }
    }

    /** What any app can address: the exported activity itself. Also what an older build's PendingIntents name. */
    private fun outside() = Intent(app, MainActivity::class.java)

    /** The app's own way in: the alias, as Android keeps it on the intent it delivers (seen on an emulator, docs/DECISIONS.md). */
    private fun own() = MainActivity.command(app).also { assertEquals(ALIAS, it.component?.className) }

    /** Each command, on [Intent] of either kind, with the extras a hostile app would choose. */
    private fun commands(): List<Pair<String, Intent.() -> Intent>> = listOf(
        "open a session" to { setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SERVER, z2Id).putExtra(MainActivity.EXTRA_SESSION, "s-z2") },
        "start the update" to { setAction(MainActivity.ACTION_UPDATE) },
        "+2 h" to { setAction(MainActivity.ACTION_EXTEND) },
        "a server's settings" to { setAction(MainActivity.ACTION_SERVER_UPDATE).putExtra(MainActivity.EXTRA_SERVER, z2Id) },
        "an automation" to { setAction(AutomationNotes.ACTION_OPEN).putExtra(AutomationNotes.EXTRA_ID, "a1") },
        "the new-session sheet" to { setAction(ShareLaunch.ACTION_NEW_SESSION).putExtra(ShareLaunch.EXTRA_PROJECT, "/somewhere/chosen") },
        "switch server, no action at all" to { putExtra(MainActivity.EXTRA_SERVER, z2Id) },
        "switch server, on a link that isn't a pairing link" to { setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://example.com/")).putExtra(MainActivity.EXTRA_SERVER, z2Id) },
        "switch server, on the launcher's own action" to { setAction(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_SERVER, z2Id) },
    )

    private fun assertNothingHappened(what: String, vm: HubViewModel) {
        settle()
        assertEquals("$what: the server on screen stays", miniId, Core.activeId)
        assertNull("$what: no session opens", vm.pendingOpen)
        assertNull("$what: no other server's session opens", vm.pendingServer)
        assertNull("$what: no automation opens", vm.pendingAutomation)
        assertNull("$what: no new-session sheet", ShareLaunch.newSession)
        assertNull("$what: Settings doesn't jump", SettingsJump.asked)
        assertEquals("$what: no update starts", UpdateState.Idle, AppUpdater.state)
        assertEquals("$what: nothing is downloaded", 0, daemons.sumOf { it.count("GET", UpdatePolicy.APK_PATH) })
        assertEquals("$what: no +2 h", 0, daemons.sumOf { it.count("POST", "/v1/mac/awake") })
    }

    @Test fun anOutsideIntentStartingTheAppDoesNothingButOpenIt() {
        for ((what, build) in commands()) {
            val made = Robolectric.buildActivity(MainActivity::class.java, outside().build()).create()
            try {
                assertNothingHappened(what, ViewModelProvider(made.get())[HubViewModel::class.java])
            } finally {
                made.destroy()
            }
        }
    }

    /** singleTask: with the app already open, a start from anywhere arrives here instead. */
    @Test fun anOutsideIntentReachingTheOpenAppDoesNothing() {
        val made = Robolectric.buildActivity(MainActivity::class.java, outside().setAction(Intent.ACTION_MAIN)).create()
        try {
            val vm = ViewModelProvider(made.get())[HubViewModel::class.java]
            for ((what, build) in commands()) {
                made.newIntent(outside().build())
                assertNothingHappened(what, vm)
            }
        } finally {
            made.destroy()
        }
    }

    @Test fun thePairingLinkStaysOpenAndAsks() {
        val link = "hypernova://pair?v=1&u=https%3A%2F%2F192.0.2.1%3A47323&c=code&n=box"
        val made = Robolectric.buildActivity(MainActivity::class.java, outside().setAction(Intent.ACTION_VIEW).setData(Uri.parse(link)).putExtra(MainActivity.EXTRA_SERVER, z2Id)).create()
        try {
            val vm = ViewModelProvider(made.get())[HubViewModel::class.java]
            assertEquals("box", vm.offeredLink!!.macName)
            assertEquals(miniId, Core.activeId)
        } finally {
            made.destroy()
        }
    }

    @Test fun theAppsOwnIntentsStillDoEachThing() {
        fun run(build: Intent.() -> Intent, check: (HubViewModel) -> Unit) {
            val made = Robolectric.buildActivity(MainActivity::class.java, own().build()).create()
            try {
                check(ViewModelProvider(made.get())[HubViewModel::class.java])
            } finally {
                made.destroy()
            }
        }
        run({ setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SERVER, z2Id).putExtra(MainActivity.EXTRA_SESSION, "s-z2") }) { vm ->
            assertEquals(z2Id, Core.activeId)
            assertEquals("s-z2", vm.pendingOpen)
            vm.pendingOpen = null
        }
        Core.switchTo(miniId)
        run({ setAction(MainActivity.ACTION_SERVER_UPDATE).putExtra(MainActivity.EXTRA_SERVER, z2Id) }) {
            assertEquals(z2Id, Core.activeId)
            assertEquals(SettingsJump.SERVER, SettingsJump.asked)
            assertEquals(z2Id, SettingsJump.server)
        }
        Core.switchTo(miniId)
        run({ putExtra(MainActivity.EXTRA_SERVER, z2Id) }) { assertEquals("a widget's computer name", z2Id, Core.activeId) }
        Core.switchTo(miniId)
        run({ setAction(AutomationNotes.ACTION_OPEN).putExtra(AutomationNotes.EXTRA_ID, "a1") }) { vm ->
            assertEquals("a1", vm.pendingAutomation)
            vm.pendingAutomation = null
        }
        run({ setAction(ShareLaunch.ACTION_NEW_SESSION).putExtra(ShareLaunch.EXTRA_PROJECT, "/p/app") }) {
            assertEquals("/p/app", ShareLaunch.newSession?.projectPath)
        }
        run({ setAction(MainActivity.ACTION_EXTEND) }) {
            until("+2 h reached the server") { mini.count("POST", "/v1/mac/awake") == 1 }
        }
        run({ setAction(MainActivity.ACTION_UPDATE) }) {
            until("the update started") { AppUpdater.state != UpdateState.Idle }
        }
    }

    @Test fun anOwnIntentReachingTheOpenAppWorksToo() {
        val made = Robolectric.buildActivity(MainActivity::class.java, outside().setAction(Intent.ACTION_MAIN)).create()
        try {
            val vm = ViewModelProvider(made.get())[HubViewModel::class.java]
            made.newIntent(own().setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SERVER, z2Id).putExtra(MainActivity.EXTRA_SESSION, "s-z2"))
            assertEquals(z2Id, Core.activeId)
            assertEquals("s-z2", vm.pendingOpen)
        } finally {
            made.destroy()
        }
    }

    // ─── Everything the app builds names the alias, and nothing it builds is mutable ───

    private fun assertOwn(what: String, pending: android.app.PendingIntent?) {
        assertNotNull(what, pending)
        val shadow = shadowOf(pending)
        assertEquals("$what targets the alias", ALIAS, shadow.savedIntent.component?.className)
        assertTrue("$what is immutable", shadow.isImmutable)
    }

    @Test fun everyCommandNotificationTargetsTheAlias() {
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        // The hubs post notes of their own meanwhile, so each is found by its text.
        fun shown(text: String) = nm.allNotifications.first { it.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == text }
        fun notice(n: Notice) = Core.notifier.notice(n, server = z2Id).let { shown(n.text) }

        assertOwn("a session's tap", notice(Notice("n1", Notice.Kind.Finished, "s-z2", "z2", "Done.")).contentIntent)
        val update = notice(Notice("n2", Notice.Kind.Update, null, "z2", "9.9.9 is ready.")).contentIntent
        assertOwn("the update's tap", update)
        assertEquals(MainActivity.ACTION_UPDATE, shadowOf(update).savedIntent.action)
        assertOwn("a failed server update's tap", notice(Notice("n3", Notice.Kind.ServerUpdateFailed, null, "z2", "Couldn't update.")).contentIntent)
        assertOwn("Backpack's +2 h", notice(Notice("n4", Notice.Kind.Backpack, null, "z2", "Backpack ends soon", action = Notice.ACTION_EXTEND)).actions.single().actionIntent)

        val notes = AutomationNotes(app, z2Id)
        notes.post("k1", "Done", "Its session.", "s-z2", null)
        assertOwn("an automation's session", shown("Its session.").contentIntent)
        notes.post("k2", "Done", "Its editor.", null, "a1")
        assertOwn("an automation's editor", shown("Its editor.").contentIntent)
        notes.postAsk("r1", "a1", "Nightly", RunAsk("step", "Go on?", listOf("Yes", "No"), "No", System.currentTimeMillis() + 60_000))
        assertOwn("an automation's question", shown("Nightly").contentIntent)
    }

    @Test fun theLaunchersShortcutsTargetTheAlias() {
        LauncherShortcuts.publish(app, listOf(LauncherShortcuts.Recent("/p/app", "app")))
        val dynamic = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).single()
        assertEquals(ALIAS, dynamic.intent.component?.className)

        val xml = app.resources.getXml(R.xml.shortcuts)
        val targets = mutableListOf<String>()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "intent") targets += xml.getAttributeValue(ANDROID, "targetClass")
        }
        assertEquals(listOf(ALIAS), targets)
    }

    /** Anything new that sends a command through the exported name is caught here, whichever file it's in. */
    @Test fun nothingInTheAppSendsACommandThroughTheExportedName() {
        val commandWords = Regex("""ACTION_OPEN_SESSION|ACTION_UPDATE|ACTION_EXTEND|ACTION_SERVER_UPDATE|ACTION_OPEN\b|ACTION_NEW_SESSION|EXTRA_SERVER|EXTRA_SESSION|EXTRA_PROJECT""")
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "MainActivity.kt" }.flatMap { f ->
            val lines = f.readLines()
            lines.indices.filter { "MainActivity::class.java" in lines[it] }
                // The line and the few after it, where a builder or an `if (...) i.putExtra` goes on.
                .filter { i -> lines.subList(i, minOf(i + 4, lines.size)).any(commandWords::containsMatchIn) }
                .map { "${f.name}:${it + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    // ─── The manifest ───

    private fun manifest(file: File): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { it.tagName == name }?.let(out::add)
        return out
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    /** Fails if a command target, or anything else, becomes exported. A new exported component is a decision: docs/SECURITY-MODEL.md lists them. */
    @Test fun theManifestExportsOnlyTheLauncherAndShareEntries() {
        val application = manifest(File("src/main/AndroidManifest.xml")).children("application").single()
        val exported = listOf("activity", "activity-alias", "receiver", "service", "provider").flatMap { application.children(it) }.filter { it.android("exported") != "false" }.map { it.android("name") }
        assertEquals(listOf(".MainActivity", ".ui.share.ShareActivity"), exported)

        val alias = application.children("activity-alias").single()
        assertEquals(ALIAS.removePrefix("com.prudnikweb.hypernova"), alias.android("name"))
        assertEquals("false", alias.android("exported"))
        assertEquals(".MainActivity", alias.android("targetActivity"))
        assertTrue("a filter would make it reachable by what the filter matches", alias.children("intent-filter").isEmpty())
    }

    /** MainActivity's filters stay what a stranger may send: the launcher, and the pairing link that asks. */
    @Test fun mainActivitysFiltersAreTheLauncherAndThePairingLink() {
        val application = manifest(File("src/main/AndroidManifest.xml")).children("application").single()
        val main = application.children("activity").single { it.android("name") == ".MainActivity" }
        val filters = main.children("intent-filter").map { f ->
            Triple(f.children("action").map { it.android("name") }, f.children("data").map { it.android("scheme") + "://" + it.android("host") }, f.children("category").map { it.android("name") })
        }
        assertEquals(
            listOf(
                Triple(listOf(Intent.ACTION_MAIN), emptyList(), listOf(Intent.CATEGORY_LAUNCHER)),
                Triple(listOf(Intent.ACTION_VIEW), listOf("hypernova://pair"), listOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE)),
            ),
            filters,
        )
    }

    /**
     * What the build actually merged, libraries included. ProfileInstallReceiver stays behind the DUMP
     * permission, which only the shell and the system hold; the debug build alone adds Compose's
     * preview and test activities (debugImplementation, not in a release).
     */
    @Test fun theMergedManifestAddsNothingUnexpected() {
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS,
        )
        val all = (info.activities.orEmpty().toList() + info.receivers.orEmpty() + info.services.orEmpty() + info.providers.orEmpty())
        val exported = all.filter { it.exported }.map { it.name }.toSortedSet()
        val expected = sortedSetOf(
            MainActivity::class.java.name,
            "com.prudnikweb.hypernova.ui.share.ShareActivity",
            "androidx.profileinstaller.ProfileInstallReceiver",
        )
        if (BuildConfig.DEBUG) {
            expected += "androidx.compose.ui.tooling.PreviewActivity"
            expected += "androidx.activity.ComponentActivity"
        }
        assertEquals(expected, exported)
        val alias = app.packageManager.getActivityInfo(ComponentName(app.packageName, ALIAS), 0)
        assertFalse("the alias isn't exported", alias.exported)
        assertEquals(MainActivity::class.java.name, alias.targetActivity)
    }

    private companion object {
        const val ALIAS = "com.prudnikweb.hypernova.AppCommands"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
