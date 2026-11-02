package com.prudnikweb.hypernova.data

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.widget.WidgetSync
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the notifications' buttons do, after the phone is unlocked (Android asks first: each action
 * requires authentication). Allow and Deny on an approval go the widget's way
 * ([WidgetSync.answer]: one path, one rule, and the widget says "Allowing…" too); the notification
 * goes once the Mac has the answer, or says it didn't get through. Reply on a finished or waiting
 * session sends the words as a prompt, as its composer would. Each goes to the computer the
 * notification came from ([EXTRA_SERVER], data/Servers.kt; the active one for a notification from
 * before there were several), whichever one the app shows. Reached only through the
 * notifications' own PendingIntents (not exported).
 */
class NotifyActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidCore.init(context.applicationContext as Application)
        val server = intent.getStringExtra(EXTRA_SERVER)
        when (intent.action) {
            ACTION_SAMPLE -> if (com.prudnikweb.hypernova.BuildConfig.DEBUG) sample()
            ACTION_ALLOW, ACTION_DENY -> {
                val id = intent.getStringExtra(EXTRA_APPROVAL) ?: return
                val async = goAsync()
                WidgetSync.answer(context, id, intent.action == ACTION_ALLOW, server = server, finished = { async.finish() }, onResult = { ok ->
                    if (ok) Core.notifier.answered(id)
                    else {
                        // Still waiting on the Mac: shown again, saying the answer didn't get through.
                        val live = Core.hub(server)
                        val a = live?.pendingApprovals?.firstOrNull { it.id == id }
                        if (a != null) Core.notifier.approval(a, live.session(a.sessionId), failed = true, server = live.serverId)
                    }
                })
            }
            ACTION_ANSWER -> {
                // An automation's question answered from its notification: the answer route, then the
                // notification goes. Answered elsewhere first (not_asking) is fine: it goes too.
                val runId = intent.getStringExtra(EXTRA_RUN) ?: return
                val stepId = intent.getStringExtra(EXTRA_STEP) ?: return
                val answer = intent.getStringExtra(EXTRA_ANSWER) ?: return
                val notes = AutomationNotes(context.applicationContext)
                // No connection to the Mac: the notification stays, so the answer can be given again.
                val live = Core.hub(server) ?: return
                val async = goAsync()
                Core.scope.launch {
                    try {
                        val r = withTimeoutOrNull(9_000L) { live.automations.answer(runId, stepId, answer) }
                        val code = (r?.exceptionOrNull() as? AutomationError)?.code
                        // Answered, or nothing left to answer: it goes. A failure leaves it, to try again.
                        if (r?.isSuccess == true || code == "not_asking" || code == "bad_step" || code == "no_run") notes.cancelAsk(runId)
                    } finally {
                        async.finish()
                    }
                }
            }
            ACTION_REPLY -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                val live = Core.hub(server)
                val title = live?.session(sessionId)?.name ?: context.getString(R.string.app_name)
                if (text.isEmpty()) { Core.notifier.replied(sessionId, server); return }
                if (live == null) {
                    Core.notifier.replyFailed(sessionId, title, context.getString(R.string.notif_reply_failed), server)
                    return
                }
                val async = goAsync()
                Core.scope.launch {
                    try {
                        // A broadcast can't hold the process for long: what hasn't landed by then is said as not sent.
                        val sent = withTimeoutOrNull(9_000L) { live.send(sessionId, text) }
                        if (sent?.isSuccess == true) Core.notifier.replied(sessionId, server)
                        else Core.notifier.replyFailed(sessionId, title, sent?.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_reply_failed), server)
                    } finally {
                        async.finish()
                    }
                }
            }
        }
    }

    /**
     * Debug builds: an approval's and a finished session's notification on sample data, to see
     * them without a Mac (nothing is answered or sent: there's no hub):
     *
     *     adb shell run-as com.prudnikweb.hypernova am broadcast --user 0 \
     *       -n com.prudnikweb.hypernova/.data.NotifyActions -a com.prudnikweb.hypernova.notify.SAMPLE
     */
    private fun sample() {
        val now = System.currentTimeMillis()
        val s = Session(hex = "c7", id = "sample-session", name = "release landing copy", project = "0stems", state = SessionState.NeedsYou, model = "opus", mode = "default", activity = "", updatedAt = now)
        Core.notifier.approval(
            WApproval(
                id = "sample-approval", sessionId = s.id, createdAt = now, expiresAt = null, kind = "tool", toolName = "Bash",
                summary = "Bash: git push origin main", input = mapOf("command" to "git push origin main"), reason = null,
                canAlways = true, questions = emptyList(), plan = null,
            ),
            s,
        )
        Core.notifier.notice(Notice("sample-notice", Notice.Kind.Finished, "sample-finished", "hypernova-01", "Both audits are in. Want me to start on the fixes?"))
    }

    companion object {
        /** Debug builds only: the sample notifications ([sample]). */
        const val ACTION_SAMPLE = "com.prudnikweb.hypernova.notify.SAMPLE"
        const val ACTION_ALLOW = "com.prudnikweb.hypernova.notify.ALLOW"
        const val ACTION_DENY = "com.prudnikweb.hypernova.notify.DENY"
        const val ACTION_REPLY = "com.prudnikweb.hypernova.notify.REPLY"
        /** An answer to an automation's ask step, from its notification (data/AutomationsLive.kt). */
        const val ACTION_ANSWER = "com.prudnikweb.hypernova.notify.ANSWER"
        const val EXTRA_RUN = "run"
        const val EXTRA_STEP = "step"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_APPROVAL = "approval"
        const val EXTRA_SESSION = "session"
        /** The paired computer the notification came from (data/Servers.kt): the answer goes there. */
        const val EXTRA_SERVER = "server"
        const val KEY_REPLY = "reply"
    }
}
