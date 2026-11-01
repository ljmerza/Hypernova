package com.prudnikweb.hypernova.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.robolectric.RuntimeEnvironment
import com.github.takahirom.roborazzi.captureRoboImage
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.Opener
import com.prudnikweb.hypernova.ui.components.EnterKeyPref
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.home.CardSwipePref
import com.prudnikweb.hypernova.ui.home.CardSwipes
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarPref
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * What a screen test watches besides the hub: what the screen asked the app to open ([opened],
 * "session:s-build", "terminal:s-deploy"), its toasts and its confirms.
 */
class AppSpy {
    val opened = mutableListOf<String>()
    val toasts = mutableListOf<String>()
    val confirms = mutableListOf<Confirm>()

    val opener = Opener(
        session = { opened += "session:$it" },
        switchSession = { opened += "switch:$it" },
        files = { root, _ -> opened += "files:$root" },
        file = { opened += "file:$it" },
        preview = { path, port, _ -> opened += "preview:${path ?: port}" },
        web = { url, _ -> opened += "web:$url" },
        terminal = { opened += "terminal:$it" },
        agent = { s, a -> opened += "agent:$s/$a" },
        providers = { opened += "providers" },
        provider = { id, _ -> opened += "provider:$id" },
        runAtReset = { opened += "runAtReset:$it" },
        automation = { opened += "automation:$it" },
        connectionLog = { opened += "connectionLog" },
        settings = { opened += "settings:$it" },
    )
}

/**
 * The app's frame for one screen, as App.kt gives it: the blur state, the opener, the app's scope,
 * toasts and confirms, over the app's background colour (the cosmos isn't drawn). Not
 * HypernovaTheme itself: Robolectric's PowerManager has no thermal listener. Its motion is the
 * reduced one, so pictures don't depend on where an animation was.
 */
@Composable
fun Harness(spy: AppSpy, content: @Composable () -> Unit) {
    val haze = remember { HazeState() }
    val scope = rememberCoroutineScope()
    run {
        CompositionLocalProvider(
            LocalMotion provides MotionPrefs(reduced = true),
            LocalHaze provides haze,
            LocalOpener provides spy.opener,
            LocalAppScope provides scope,
            LocalToast provides { spy.toasts += it },
            LocalConfirm provides { spy.confirms += it },
        ) {
            // The background is the blur's farthest source, beside the screen as the cosmos is (a
            // blur can't draw a source it sits inside).
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(HN.bg).hazeSource(haze, zIndex = Z.COSMOS))
                content()
            }
        }
    }
}

val context: Context get() = RuntimeEnvironment.getApplication()

fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

/**
 * Each test's start: the samples' clock now ([Samples.NOW]), and the settings kept on the phone
 * back to their defaults (each is one state for the whole app, so one test's choice would
 * otherwise reach the next). And FileProvider's roots forgotten: it keeps them per authority in a
 * static, for the whole process, which on a phone is right, but here each test has a temp folder of
 * its own, so the first test's roots would refuse every later test's files.
 */
fun resetPrefs() {
    Samples.NOW = System.currentTimeMillis()
    (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    EnterKeyPref.set(context, EnterKey.Send)
    TopBarPref.set(context, TopBarChoice.DEFAULT)
    CardSwipePref.set(context, CardSwipes())
    com.prudnikweb.hypernova.ui.home.HomeFilterPref.set(context, com.prudnikweb.hypernova.ui.home.HomeFilter.NONE)
    com.prudnikweb.hypernova.data.VoicePrefs.of(context).chooseDictationEngine(com.prudnikweb.hypernova.data.DictationEngine.of(null))
}

/** A node whose click TalkBack names [label] (onClickLabel): buttons drawn without text of their own. */
fun hasClickLabel(label: String) = androidx.compose.ui.test.SemanticsMatcher("click label is '$label'") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
}

/**
 * A node that is on screen: a layout that measures several variants and places one (the New session
 * sheet's chips, ChipRow) leaves the others in the semantics tree, unplaced.
 */
fun isPlaced() = androidx.compose.ui.test.SemanticsMatcher("is placed") { it.layoutInfo.isPlaced }

/** A node whose TalkBack sentence starts with [prefix] (a picker's row: "High. Thinks things through."). */
fun saidStartsWith(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("said starts with '$prefix'") { n ->
    n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) }
}

/** The TalkBack actions a node offers besides its click (a Home card's swipes), by their labels. */
fun SemanticsNodeInteraction.customActions(): List<String> =
    fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

/** Where [text]'s node starts down the screen (a card's top in the merged tree). */
fun ComposeContentTestRule.topOf(text: String): Float =
    onNode(androidx.compose.ui.test.hasText(text)).fetchSemanticsNode().boundsInRoot.top

/** Where a node TalkBack names [said] starts down the screen (a seam, which has no text of its own). */
fun ComposeContentTestRule.topOfSaid(said: String): Float =
    onNode(androidx.compose.ui.test.hasContentDescription(said)).fetchSemanticsNode().boundsInRoot.top

/** The screen as a picture, compared with (or recorded as) app/src/test/snapshots/[name].png. */
fun ComposeContentTestRule.snap(name: String) {
    waitForIdle()
    onRoot().captureRoboImage("src/test/snapshots/$name.png")
}

fun SemanticsNodeInteraction.snap(name: String) = captureRoboImage("src/test/snapshots/$name.png")
