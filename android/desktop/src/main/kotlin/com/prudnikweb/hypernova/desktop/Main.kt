package com.prudnikweb.hypernova.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Net
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.ui.components.logo
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Everything that's the same with a window and in a test: the platform up, plain http refused, [Core] started on the main thread. */
fun startCore(platform: DesktopPlatform): Shell {
    // Android's network security config does this on a phone; there's nothing else here to do it.
    Net.httpsOnly = true
    val shell = Shell(platform)
    val start = {
        // Worded for the computer last paired, before it has said anything (HypernovaApp does this on a phone).
        ComputerWords.load(platform.storage)
        Core.init(platform)
        Core.toastSink = shell::say
        // The window is this app's whole presence: with no background to fall back on, it's always "on screen".
        Core.setVisible(true)
    }
    if (SwingUtilities.isEventDispatchThread()) start() else SwingUtilities.invokeAndWait(start)
    return shell
}

/** The lock's file: dropped, it would be closed and its lock with it, whenever the collector came by. */
private var held: FileChannel? = null

/** One copy of the app per folder: two would overwrite each other's settings. Held until the process ends. */
internal fun oneCopy(home: Path): Boolean {
    val lock = FileChannel.open(home.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    held = lock
    return runCatching { lock.tryLock() }.getOrNull() != null
}

private object WindowIcon : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawRect(com.prudnikweb.hypernova.desktop.ui.HN.bg)
        logo(size.minDimension * 0.72f, Color(0xFFF1E9D6))
    }
}

fun main() {
    if (GraphicsEnvironment.isHeadless()) {
        System.err.println("$APP_NAME needs a display, and this session has none. Nothing was started.")
        exitProcess(2)
    }
    val platform = DesktopPlatform()
    if (!oneCopy(platform.folder.home)) {
        System.err.println("$APP_NAME is already running with ${platform.folder.home}.")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { platform.folder.flush() } })
    val shell = startCore(platform)
    application {
        val state = rememberWindowState(size = DpSize(960.dp, 720.dp))
        Window(onCloseRequest = ::exitApplication, state = state, title = APP_NAME, icon = remember { WindowIcon }) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
            val info = LocalWindowInfo.current
            LaunchedEffect(info) {
                // The focus coming back is this computer's "the screen came on".
                snapshotFlow { info.isWindowFocused }.collect { if (it) platform.screenWatch.ownerLooked() }
            }
            App(shell)
        }
    }
    // OkHttp's threads would keep the process a minute longer.
    runCatching { platform.folder.flush() }
    exitProcess(0)
}

private const val MIN_WIDTH = 520
private const val MIN_HEIGHT = 420
