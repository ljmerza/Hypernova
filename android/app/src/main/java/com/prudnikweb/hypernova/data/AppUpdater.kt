package com.prudnikweb.hypernova.data

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The in-app update (docs/PROTOCOL.md, "The app update"): the app downloads the APK the paired
 * computer hands out over the route it uses itself (the Mac's own address on the local network with
 * the pinned certificate, or the paired base URL), checks it is Hypernova and newer, and hands it
 * to Android's PackageInstaller, whose own "Update this app?" dialog finishes it. A browser can't
 * do this when the Mac is reached only through its pinned certificate, which is why it isn't the
 * browser's job.
 * Android refuses an APK signed with another key than the installed app: that is the update's
 * integrity. The downloaded file lives in the app's cache only until the install is decided.
 * The flow's steps are [UpdateFlow]'s (pure, unit-tested).
 */
object AppUpdater {
    /** Where the flow stands, for the banner and Settings (Compose state; changed on the main thread). */
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** The update the flow is for. */
    var target by mutableStateOf<AppUpdate?>(null)
        private set

    /** Android's "Update this app?", waiting to be shown (the app wasn't on screen) or to be shown again. */
    private var confirm: Intent? = null
    private var job: Job? = null

    private const val DIR = "update"
    private const val FILE = "Hypernova.apk"
    private const val PREFS = "hypernova"
    /** Set when this app hands Android an update, so the next start after it says "updated". */
    private const val KEY_SELF_UPDATE = "selfUpdateFrom"

    private fun dir(c: Context) = File(c.cacheDir, DIR)
    private fun apk(c: Context) = File(dir(c), FILE)

    private fun on(e: UpdateFlow.Event) {
        val before = state
        state = UpdateFlow.next(state, e)
        if (BuildConfig.DEBUG && state != before && e !is UpdateFlow.Event.Progress) android.util.Log.d("Hypernova", "update: $before -> $state")
    }

    /** Android lets Hypernova install apps ("Install unknown apps" for Hypernova). */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    /** "Get the update" (the banner, the notification, Settings): download, check, install. Again after a decline: install. */
    fun start(c: Context, u: AppUpdate) {
        val app = c.applicationContext
        val s = state
        if (s is UpdateState.Confirming && s.versionCode == u.versionCode) {
            install(c)
            return
        }
        if (UpdateFlow.busy(s)) return
        // A newer one than the file waiting to be installed: that file goes, this one comes.
        if (s is UpdateState.Confirming) {
            on(UpdateFlow.Event.Dismiss)
            clear(app)
        }
        target = u
        on(UpdateFlow.Event.Start(u.versionCode, canInstall(app)))
        if (state is UpdateState.Downloading) download(app)
    }

    /** "Install unknown apps" for Hypernova, in the system's settings; [resumed] carries on once allowed. */
    fun allowInstalls(c: Context) {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** The app is on screen again: installs may have been allowed meanwhile, or Android's dialog waits. */
    fun resumed(c: Context) {
        val app = c.applicationContext
        val s = state
        if (s is UpdateState.NeedsPermission && canInstall(app)) {
            on(UpdateFlow.Event.Allowed)
            download(app)
        } else if (s is UpdateState.Confirming && !s.shown) {
            confirm?.let { show(c, it) }
        }
    }

    /** Puts a failure, the permission step or a declined install away (and the file with it). */
    fun dismiss(c: Context) {
        if (UpdateFlow.busy(state)) return
        on(UpdateFlow.Event.Dismiss)
        if (state == UpdateState.Idle) clear(c.applicationContext)
    }

    /** At the app's start: no update is under way in a new process, so whatever an earlier one left goes. */
    fun cleanUp(c: Context) = clear(c.applicationContext)

    private fun clear(app: Context) {
        job?.cancel()
        confirm = null
        target = null
        wipe(app, sessions = true)
    }

    /** The cleaning under way, which a download waits for (so it never deletes the new file). */
    private var cleaning: Job? = null

    /** Deletes the downloaded file (and, [sessions], any install session left), off the main thread. */
    private fun wipe(app: Context, sessions: Boolean = false) {
        val before = cleaning
        cleaning = Core.scope.launch(Dispatchers.IO) {
            before?.join()
            dir(app).deleteRecursively()
            if (sessions) {
                val installer = app.packageManager.packageInstaller
                for (s in runCatching { installer.mySessions }.getOrDefault(emptyList())) runCatching { installer.abandonSession(s.sessionId) }
            }
        }
    }

    private fun download(app: Context) {
        // From the computer that offers it (Settings' About with "All servers" offers the newest of any), else the one on screen.
        val pairing = target?.server?.let { id -> Core.servers.firstOrNull { it.id == id }?.pairing } ?: Core.pairing
        if (pairing == null) {
            on(UpdateFlow.Event.Failed(UpdateFailure.Network))
            return
        }
        job?.cancel()
        job = Core.scope.launch {
            cleaning?.join()
            val failure = withContext(Dispatchers.IO) { fetch(app, pairing) }
            if (failure != null) {
                on(UpdateFlow.Event.Failed(failure))
                wipe(app)
                return@launch
            }
            on(UpdateFlow.Event.Downloaded)
            val verdict = withContext(Dispatchers.IO) { check(app) }
            on(UpdateFlow.Event.Checked(verdict))
            if (verdict == ArchiveVerdict.Ok) install(app) else wipe(app)
        }
    }

    /**
     * The APK over the route in use, with that route's client (its pin, its device certificate, the
     * node's proxy), to the cache; progress as it comes. Null when it all arrived.
     */
    private suspend fun fetch(app: Context, pairing: Pairing): UpdateFailure? {
        val route = ActiveRoutes.route(pairing.baseUrl)
        val url = UpdatePolicy.apkUrl(route.base)
        val part = File(dir(app), "$FILE.part")
        val call = route.uploads.newCall(Request.Builder().url(url).build())
        val me = coroutineContext[Job]
        val stop = me?.invokeOnCompletion { call.cancel() }
        try {
            dir(app).mkdirs()
            call.execute().use { res ->
                UpdatePolicy.httpFailure(res.code)?.let { return it }
                val body = res.body ?: return UpdateFailure.Network
                val total = body.contentLength()
                var read = 0L
                var shown = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (UpdatePolicy.progressWorthShowing(shown, read, total)) {
                                shown = read
                                val r = read
                                withContext(Dispatchers.Main) { on(UpdateFlow.Event.Progress(r, total)) }
                            }
                        }
                    }
                }
                // Cut short (the Mac went away mid-way): not an APK to try.
                if (total > 0 && read != total) return UpdateFailure.Network
                if (!part.renameTo(apk(app))) return UpdateFailure.Storage
            }
            if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "update: downloaded over ${route.kind} (${apk(app).length()} bytes)")
            return null
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "update download over ${route.kind}: $e")
            return if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true) UpdateFailure.Storage else UpdateFailure.Network
        } finally {
            stop?.dispose()
            part.delete()
        }
    }

    /** What Android reads in the archive: Hypernova, and newer than the app running now. */
    private fun check(app: Context): ArchiveVerdict {
        val pm = app.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apk(app).path, 0) }.getOrNull()
        val installed = runCatching { pm.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
        return UpdatePolicy.verify(info?.packageName, info?.longVersionCode, app.packageName, installed)
    }

    /** Hands the checked APK to PackageInstaller; its answer comes to [UpdateStatusReceiver]. */
    private fun install(c: Context) {
        val app = c.applicationContext
        // Asked before and still waiting (the user left the dialog): the same dialog again.
        confirm?.let {
            show(c, it)
            return
        }
        Core.scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { commit(app) } }
            r.onFailure { e ->
                if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "update install: $e")
                on(UpdateFlow.Event.Failed(if (e is IOException && e.message?.contains("ENOSPC") == true) UpdateFailure.Storage else UpdateFailure.Install, e.message))
            }
        }
    }

    private fun commit(app: Context) {
        val file = apk(app)
        if (!file.exists()) throw IOException("the downloaded update is gone")
        val installer = app.packageManager.packageInstaller
        // An earlier try's session (declined, or left behind) goes first.
        for (s in installer.mySessions) runCatching { installer.abandonSession(s.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android's own "Update this app?" every time (no silent self-update): it's the moment the
            // user knows Hypernova is about to close and come back as the new version.
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(FILE, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val i = Intent(app, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
                // Mutable: PackageInstaller fills in the status.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SELF_UPDATE, BuildConfig.VERSION_CODE).apply()
                session.commit(PendingIntent.getBroadcast(app, 0, i, flags).intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Android's dialog, from the app on screen (a background start would be blocked); else it waits for [resumed]. */
    private fun show(c: Context, i: Intent) {
        if (!Core.visible) {
            on(UpdateFlow.Event.ConfirmWaiting)
            return
        }
        try {
            c.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            on(UpdateFlow.Event.ConfirmShown)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "update confirm: $e")
            on(UpdateFlow.Event.ConfirmWaiting)
        }
    }

    // ── PackageInstaller's answers (UpdateStatusReceiver) ─────────────────────

    internal fun answered(c: Context, status: Int, message: String?, prompt: Intent?) {
        if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "update: install status $status ${message ?: ""}")
        when (status) {
            UpdatePolicy.STATUS_PENDING_USER_ACTION -> {
                confirm = prompt
                if (prompt != null) show(c, prompt) else on(UpdateFlow.Event.Failed(UpdateFailure.Install, message))
            }
            // Seldom heard: Android ends this process to replace the app (the next start says so).
            UpdatePolicy.STATUS_SUCCESS -> {
                on(UpdateFlow.Event.Installed)
                clear(c.applicationContext)
            }
            UpdatePolicy.STATUS_FAILURE_ABORTED -> {
                confirm = null
                on(UpdateFlow.Event.Declined)
            }
            else -> {
                confirm = null
                on(UpdateFlow.Event.InstallFailed(status, message))
                wipe(c.applicationContext)
            }
        }
    }

    /**
     * This app was just replaced. When it was this app that handed Android the update, a quiet
     * notification says it's done (Android closed the app to replace it) and opens it.
     */
    internal fun replaced(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val from = prefs.getInt(KEY_SELF_UPDATE, 0)
        prefs.edit().remove(KEY_SELF_UPDATE).apply()
        if (from == 0 || from >= BuildConfig.VERSION_CODE || !Core.notifier.canPost()) return
        // The phone's own news, no server's: the default Updates switch and the master decide.
        if (!NotificationPrefsStore.get(c.storage).allows(null, Notice.Kind.Update)) return
        val open = PendingIntent.getActivity(c, 9, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, Notifier.SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(c.getString(R.string.update_done_title))
            .setContentText(c.getString(R.string.update_done_body, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(Notifier.silenceable(null, Notice.Kind.Update))
            .build()
        runCatching { c.getSystemService(android.app.NotificationManager::class.java)?.notify(UPDATED_ID, n) }
    }

    private const val UPDATED_ID = 4733
}

/** PackageInstaller's word on the session ([AppUpdater]), and the app's own replacement. Not exported. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppUpdater.replaced(context)
            ACTION -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                @Suppress("DEPRECATION")
                val prompt = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                AppUpdater.answered(context, status, message, prompt)
            }
        }
    }

    companion object {
        const val ACTION = "com.prudnikweb.hypernova.UPDATE_STATUS"
    }
}
