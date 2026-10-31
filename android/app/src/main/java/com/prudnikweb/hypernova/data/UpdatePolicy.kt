package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/*
 * The in-app update (docs/PROTOCOL.md, "The app update"), the parts with nothing of Android in them
 * (unit-tested): where the APK comes from, whether a browser could open the Mac's page instead,
 * what the downloaded archive must be, and the flow from "Get the update" to Android's own
 * "Update this app?" dialog. AppUpdater.kt does the downloading and installing.
 */

/** Where an update stands. [versionCode]: the one the Mac offered. */
@Immutable
sealed interface UpdateState {
    /** Nothing under way. */
    data object Idle : UpdateState

    /** Android must let Hypernova install apps first ("Install unknown apps"); asked once. */
    data class NeedsPermission(val versionCode: Int) : UpdateState

    /** [read] of [total] bytes ([total] -1 while the Mac hasn't said). */
    data class Downloading(val versionCode: Int, val read: Long, val total: Long) : UpdateState {
        /** 0–1, or null while the size is unknown. */
        val fraction: Float? get() = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** The archive is here; checking it is Hypernova and newer. */
    data class Checking(val versionCode: Int) : UpdateState

    /** Checked and handed to Android: its "Update this app?" dialog is up ([shown]) or waits for a tap on Install. */
    data class Confirming(val versionCode: Int, val shown: Boolean) : UpdateState

    data class Failed(val versionCode: Int, val failure: UpdateFailure, val detail: String? = null) : UpdateState
}

/** Why an update stopped, in terms the app can explain. */
enum class UpdateFailure {
    /** The download broke off (the route went, the Mac slept). */
    Network,
    /** The Mac has no APK to hand out (404). */
    Missing,
    /** Not an APK Android can read. */
    Damaged,
    /** An APK, but another app's. */
    OtherApp,
    /** Hypernova, but not newer than the one installed. */
    NotNewer,
    /** Not enough room on the phone. */
    Storage,
    /** Signed with another key than the installed app: Android refuses it (the update's integrity). */
    OtherKey,
    /** Android (or a policy on the phone) blocked the install. */
    Blocked,
    /** Android couldn't install it for another reason (its message is the detail). */
    Install,
}

/** What the downloaded archive turned out to be. */
enum class ArchiveVerdict { Ok, Unreadable, OtherApp, NotNewer }

object UpdatePolicy {
    /** The APK's path on the daemon (daemon/src/http.ts): served without a token, before the auth check. */
    const val APK_PATH = "/download/android/Hypernova.apk"

    /** The Mac's page for the APK, for a browser. */
    const val PAGE_PATH = "/download/android"

    /**
     * The APK on the route the app uses right now: the Mac's own address on the local network
     * (pinned), or the paired base URL.
     */
    fun apkUrl(routeBase: String): String = routeBase.trimEnd('/') + APK_PATH

    /**
     * The Mac's download page, when a browser outside the app could open it: an https name with a
     * certificate a browser checks. Never an address (the computer's own certificate, which only
     * the app pins).
     */
    fun browserPage(pairedBase: String): String? {
        val o = RoutePolicy.originOf(pairedBase) ?: return null
        if (o.scheme != "https" || isAddress(o.host)) return null
        return pairedBase.trimEnd('/') + PAGE_PATH
    }

    /** An IPv4 or IPv6 literal, not a name. */
    fun isAddress(host: String): Boolean = host.isNotEmpty() && (host.all { it.isDigit() || it == '.' } || host.contains(':'))

    /**
     * The archive must be Hypernova ([mine], this app's package) and newer than the one installed.
     * A different signing key is Android's to refuse at install time (that's the update's
     * integrity: only an APK signed like the installed app replaces it).
     */
    fun verify(archivePackage: String?, archiveVersion: Long?, mine: String, installed: Long): ArchiveVerdict = when {
        archivePackage == null || archiveVersion == null -> ArchiveVerdict.Unreadable
        archivePackage != mine -> ArchiveVerdict.OtherApp
        archiveVersion <= installed -> ArchiveVerdict.NotNewer
        else -> ArchiveVerdict.Ok
    }

    // PackageInstaller's statuses (android.content.pm.PackageInstaller.STATUS_*), as numbers so
    // this stays testable on a JVM.
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7

    /**
     * A failed install's reason. Android says a signature mismatch as a conflict
     * ("INSTALL_FAILED_UPDATE_INCOMPATIBLE: … signatures do not match"); the message tells it from
     * other conflicts.
     */
    fun installFailure(status: Int, message: String?): UpdateFailure {
        val m = message.orEmpty()
        val key = m.contains("UPDATE_INCOMPATIBLE", true) || m.contains("signatures do not match", true) || m.contains("signature", true)
        return when {
            key -> UpdateFailure.OtherKey
            status == STATUS_FAILURE_STORAGE -> UpdateFailure.Storage
            status == STATUS_FAILURE_INVALID -> UpdateFailure.Damaged
            status == STATUS_FAILURE_BLOCKED -> UpdateFailure.Blocked
            status == STATUS_FAILURE_INCOMPATIBLE && m.contains("DOWNGRADE", true) -> UpdateFailure.NotNewer
            else -> UpdateFailure.Install
        }
    }

    /** A download's HTTP status as a failure, or null for a good one. */
    fun httpFailure(code: Int): UpdateFailure? = when {
        code in 200..299 -> null
        code == 404 -> UpdateFailure.Missing
        else -> UpdateFailure.Network
    }

    /** Whether a progress step is worth showing: a whole percent more, or the end (a 40 MB APK would otherwise redraw thousands of times). */
    fun progressWorthShowing(lastShown: Long, read: Long, total: Long): Boolean {
        if (total <= 0) return read - lastShown >= 256 * 1024
        if (read >= total) return true
        return (read * 100 / total) > (lastShown * 100 / total)
    }
}

/**
 * Settings → About's update row: "Update available · 0.6.0" and its button while the paired computer
 * offers a newer app ([update]), with the in-app update's step for that version (the banner's and
 * the card's flow, data/AppUpdater.kt). [fraction]: the download's, null while its size isn't known.
 */
@Immutable
data class AboutUpdate(
    val update: AppUpdate,
    val step: Step,
    val fraction: Float? = null,
    val failure: UpdateFailure? = null,
) {
    enum class Step { Offered, NeedsPermission, Downloading, Checking, Confirming, Failed }

    /** What the row's button does; none while the update downloads or is checked (its bar says how far). */
    enum class Action { Update, Allow, Install, TryAgain }

    val action: Action?
        get() = when (step) {
            Step.Offered -> Action.Update
            Step.NeedsPermission -> Action.Allow
            Step.Downloading, Step.Checking -> null
            Step.Confirming -> Action.Install
            Step.Failed -> Action.TryAgain
        }

    /** The version to name: its versionName, or null when the computer gave none (the row says the build then). */
    val versionName: String? get() = update.versionName.takeIf { it.isNotBlank() }

    companion object {
        /**
         * The row for [offer] (null: no update, no row) with the flow's [state] when the flow is for
         * that version ([target]); a flow for another version (an older offer) counts as not started.
         */
        fun of(offer: AppUpdate?, state: UpdateState, target: AppUpdate?): AboutUpdate? {
            val u = offer ?: return null
            val s = state.takeIf { target?.versionCode == u.versionCode } ?: UpdateState.Idle
            return when (s) {
                UpdateState.Idle -> AboutUpdate(u, Step.Offered)
                is UpdateState.NeedsPermission -> AboutUpdate(u, Step.NeedsPermission)
                is UpdateState.Downloading -> AboutUpdate(u, Step.Downloading, fraction = s.fraction)
                is UpdateState.Checking -> AboutUpdate(u, Step.Checking, fraction = 1f)
                is UpdateState.Confirming -> AboutUpdate(u, Step.Confirming)
                is UpdateState.Failed -> AboutUpdate(u, Step.Failed, failure = s.failure)
            }
        }
    }
}

/** The flow's steps as a pure function of the state and what happened. */
object UpdateFlow {
    sealed interface Event {
        /** "Get the update" for [versionCode]; [canInstall]: Android already lets Hypernova install apps. */
        data class Start(val versionCode: Int, val canInstall: Boolean) : Event
        /** Back from the system's page with installs allowed. */
        data object Allowed : Event
        data class Progress(val read: Long, val total: Long) : Event
        data object Downloaded : Event
        data class Checked(val verdict: ArchiveVerdict) : Event
        /** Android's dialog is on screen. */
        data object ConfirmShown : Event
        /** Android's dialog couldn't show (the app wasn't on screen): waits for a tap on Install. */
        data object ConfirmWaiting : Event
        /** The user said no in Android's dialog: the file stays, and Install asks again. */
        data object Declined : Event
        data class InstallFailed(val status: Int, val message: String?) : Event
        data object Installed : Event
        data class Failed(val failure: UpdateFailure, val detail: String? = null) : Event
        /** The user put it away (a failure, the permission step, an install not confirmed yet). */
        data object Dismiss : Event
    }

    /** Under way: a second "Get the update" does nothing, and it can't be put away. */
    fun busy(s: UpdateState): Boolean = s is UpdateState.Downloading || s is UpdateState.Checking

    fun next(s: UpdateState, e: Event): UpdateState = when (e) {
        is Event.Start -> when {
            busy(s) || s is UpdateState.Confirming -> s
            !e.canInstall -> UpdateState.NeedsPermission(e.versionCode)
            else -> UpdateState.Downloading(e.versionCode, 0, -1)
        }
        Event.Allowed -> if (s is UpdateState.NeedsPermission) UpdateState.Downloading(s.versionCode, 0, -1) else s
        is Event.Progress -> if (s is UpdateState.Downloading) s.copy(read = e.read, total = e.total) else s
        Event.Downloaded -> if (s is UpdateState.Downloading) UpdateState.Checking(s.versionCode) else s
        is Event.Checked -> if (s !is UpdateState.Checking) s else when (e.verdict) {
            ArchiveVerdict.Ok -> UpdateState.Confirming(s.versionCode, shown = false)
            ArchiveVerdict.Unreadable -> UpdateState.Failed(s.versionCode, UpdateFailure.Damaged)
            ArchiveVerdict.OtherApp -> UpdateState.Failed(s.versionCode, UpdateFailure.OtherApp)
            ArchiveVerdict.NotNewer -> UpdateState.Failed(s.versionCode, UpdateFailure.NotNewer)
        }
        Event.ConfirmShown -> if (s is UpdateState.Confirming) s.copy(shown = true) else s
        Event.ConfirmWaiting, Event.Declined -> if (s is UpdateState.Confirming) s.copy(shown = false) else s
        is Event.InstallFailed -> if (s is UpdateState.Confirming) UpdateState.Failed(s.versionCode, UpdatePolicy.installFailure(e.status, e.message), e.message) else s
        Event.Installed -> UpdateState.Idle
        is Event.Failed -> when (s) {
            is UpdateState.Downloading -> UpdateState.Failed(s.versionCode, e.failure, e.detail)
            is UpdateState.Checking -> UpdateState.Failed(s.versionCode, e.failure, e.detail)
            is UpdateState.Confirming -> UpdateState.Failed(s.versionCode, e.failure, e.detail)
            is UpdateState.NeedsPermission -> UpdateState.Failed(s.versionCode, e.failure, e.detail)
            else -> s
        }
        Event.Dismiss -> if (busy(s)) s else UpdateState.Idle
    }
}
