package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Links
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

/** What Settings asks of the computer itself, so a test can stand in for it: a window on a display can't be had in one. */
interface DeskActions {
    /** Shows [folder] in the file manager; false when this system can't. Off the main thread. */
    fun openFolder(folder: File): Boolean
    fun copy(text: String)
    /** Opens a web page in the system's browser; false when this system can't. Off the main thread. */
    fun browse(url: String): Boolean = false
}

/** AWT's way, and on Linux the system's own way when AWT says it has none. */
object RealDesk : DeskActions {
    override fun openFolder(folder: File): Boolean {
        val awt = runCatching {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (awt) return true
        return runCatching { ProcessBuilder("xdg-open", folder.path).start().waitFor() == 0 }.getOrDefault(false)
    }

    override fun browse(url: String): Boolean {
        // Only the web, whatever asks: anything else in a link has already stopped at Links.target.
        if (Links.target(url) !is Links.Target.Web) return false
        val awt = runCatching {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (awt) return true
        return runCatching { ProcessBuilder("xdg-open", url).start().waitFor() == 0 }.getOrDefault(false)
    }

    override fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
