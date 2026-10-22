package com.prudnikweb.hypernova.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * The fingerprint or the screen lock before a step that lets Claude act without asking, or widens
 * what a paired phone can read (docs/DECISIONS.md, "The owner's morning answers", 2 and 4): the
 * framework's BiometricPrompt, no new dependency. Every place a phone gives one of them asks here:
 * the chat's Always, the session's switch to Bypass, the start sheet's Bypass, saving an automation
 * (one check for a start in "No asking" and for a shell step, every save: docs/PROTOCOL.md,
 * "Workflows", The fingerprint), and Settings' Browse outside projects turned on. Notifications
 * and the widget offer Allow and Deny only; the glasses can't check a fingerprint, so the Lens bridge
 * refuses Always and Bypass from them (bridge/BridgeHttp.kt, [phoneOnly][com.prudnikweb.hypernova.bridge.BridgeHttp.phoneOnly]).
 */
object OwnerCheck {
    /**
     * [onOk] after a good fingerprint or screen lock; [onFail] with a line to show (null: the
     * owner cancelled). [noLock]: what to say on a phone without a screen lock, where there is
     * nothing to check; [demo] goes on anyway there, since the demo changes nothing on a Mac.
     */
    fun ask(context: Context, title: String, subtitle: String, noLock: String, demo: Boolean, onOk: () -> Unit, onFail: (String?) -> Unit) {
        val activity = context.activity()
        val secure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        if (activity == null || !secure) {
            if (demo) onOk() else onFail(noLock)
            return
        }
        val b = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
        runCatching {
            b.build().authenticate(
                CancellationSignal(), activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        onFail(if (cancelled) null else errString.toString())
                    }
                },
            )
        }.onFailure {
            // Not "no screen lock": the phone has one (checked above). Say the check itself failed.
            android.util.Log.w("OwnerCheck", "BiometricPrompt failed", it)
            onFail(context.getString(com.prudnikweb.hypernova.R.string.check_failed))
        }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
