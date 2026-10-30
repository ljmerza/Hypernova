package com.prudnikweb.hypernova

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.extendBackpack
import com.prudnikweb.hypernova.ui.App
import com.prudnikweb.hypernova.ui.theme.HypernovaTheme

class MainActivity : ComponentActivity() {
    // Sessions, the cosmos and its clock outlive configuration changes.
    private val vm: HubViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fully edge to edge: transparent bars with light icons, no scrims.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // No translucent scrim behind the 3-button nav bar: the cosmos shows through.
        window.isNavigationBarContrastEnforced = false
        // Draw around the camera cutout too (the punch hole on Nothing Phone 3).
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // A recreated Activity already handled its intent (the ViewModel kept the result).
        if (savedInstanceState == null) {
            // A new start with no update under way: whatever an earlier one left in the cache goes.
            if (com.prudnikweb.hypernova.data.AppUpdater.state == com.prudnikweb.hypernova.data.UpdateState.Idle) com.prudnikweb.hypernova.data.AppUpdater.cleanUp(this)
            handle(intent)
        }
        setContent { HypernovaTheme { App(vm, askNotifications = ::askNotifications) } }
        // Paired with a Linux server (or back with a Mac): made again, so every string says it right.
        lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow { com.prudnikweb.hypernova.data.ComputerWords.platform }.drop(1).collect { recreate() }
        }
    }

    /** The app's words for the paired computer: "your Mac", or "your server" for a Linux one (data/ComputerWords.kt). */
    override fun getResources(): android.content.res.Resources = com.prudnikweb.hypernova.data.ComputerWords.wrap(super.getResources())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
        // Back from the browser's Tailscale sign-in, or from "Install unknown apps": the latest word.
        com.prudnikweb.hypernova.data.Tsnet.refresh()
        com.prudnikweb.hypernova.data.AppUpdater.resumed(this)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    /** A pairing link (camera or another app), a link to the glasses' settings, or a tap on a notification. */
    private fun handle(intent: Intent?) {
        if (intent == null) return
        // Reopened from Recents: the old pairing link or notification was handled already.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = if (intent.action == Intent.ACTION_VIEW) com.prudnikweb.hypernova.data.PairLinks.kind(intent.dataString) else null
        // A notification from a computer the app doesn't show now: that one comes on screen first
        // (data/Servers.kt), then what the notification opens, there.
        intent.getStringExtra(EXTRA_SERVER)?.let { vm.switchServer(it) }
        when {
            // https://hypernova.observer/glasses, hypernova://glasses, and "Tap to open Hypernova" (Lens's
            // "Open Hypernova", bridge/BridgeOpen.kt): Settings → Glasses (an ask to connect waiting shows over it).
            link == com.prudnikweb.hypernova.data.PairLinks.Kind.Glasses || intent.action == ACTION_OPEN_GLASSES -> {
                vm.nav.reset()
                com.prudnikweb.hypernova.bridge.BridgeOpen.showGlasses()
            }
            // The Mac's QR code, https://hypernova.observer/pair#… (the details in the fragment), or the
            // app link hypernova://pair?… with the same parameters: data/Pairing.kt, PairLinks.
            link == com.prudnikweb.hypernova.data.PairLinks.Kind.Pair -> vm.handleLink(intent.dataString)
            intent.action == ACTION_OPEN_SESSION -> vm.pendingOpen = intent.getStringExtra(EXTRA_SESSION)
            // An automation's note with no session to open: the automation, or the tab for several.
            intent.action == com.prudnikweb.hypernova.data.AutomationNotes.ACTION_OPEN ->
                vm.pendingAutomation = intent.getStringExtra(com.prudnikweb.hypernova.data.AutomationNotes.EXTRA_ID).orEmpty()
            // A launcher shortcut or Share to Hypernova: the new-session sheet (ui/share).
            com.prudnikweb.hypernova.ui.share.ShareLaunch.owns(intent) -> com.prudnikweb.hypernova.ui.share.ShareLaunch.handle(intent)
            // The "update available" notification: the update, downloaded and installed in the app.
            intent.action == ACTION_UPDATE -> com.prudnikweb.hypernova.data.Core.live?.appUpdate?.let {
                com.prudnikweb.hypernova.data.AppUpdater.start(this, it)
            }
            // The Backpack notification's "+2 h" button.
            intent.action == ACTION_EXTEND -> vm.extendBackpack()
            else -> return
        }
        // Handled once: a recreated Activity or Recents must not run it again.
        intent.data = null
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(EXTRA_SESSION)
        intent.removeExtra(EXTRA_SERVER)
        intent.removeExtra(com.prudnikweb.hypernova.data.AutomationNotes.EXTRA_ID)
        setIntent(intent)
    }

    /**
     * After pairing (and from Settings): approvals can then reach you while the app is in the
     * background. Once refused, Android won't ask again, so Settings opens the system page.
     */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (!vm.askedNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            vm.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }
    }

    companion object {
        const val ACTION_OPEN_SESSION = "com.prudnikweb.hypernova.OPEN_SESSION"
        const val ACTION_UPDATE = "com.prudnikweb.hypernova.UPDATE"
        const val ACTION_EXTEND = "com.prudnikweb.hypernova.EXTEND_BACKPACK"
        /** Settings → Glasses: "Tap to open Hypernova" (bridge/BridgeNotice.kt), or Lens's ask with the app on screen. */
        const val ACTION_OPEN_GLASSES = "com.prudnikweb.hypernova.OPEN_GLASSES"
        const val EXTRA_SESSION = "session"
        /** The paired computer a notification came from (data/Servers.kt): a tap switches to it. */
        const val EXTRA_SERVER = "server"
    }
}
