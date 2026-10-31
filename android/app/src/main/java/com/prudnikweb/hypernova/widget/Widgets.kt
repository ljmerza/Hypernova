package com.prudnikweb.hypernova.widget

import android.app.Activity
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.prudnikweb.hypernova.MainActivity

/**
 * The home screen widget: every session that needs you, on every paired computer, grouped by
 * computer, with Deny and Allow on a tool's request (data from [WidgetSync], the rows from
 * [WidgetListService]). The platform's callbacks only ask for a redraw (there's no update period:
 * the app pushes every change itself). Each holds the broadcast open ([goAsync]) until the picture
 * is drawn, since a process started just for this broadcast may be stopped as soon as it returns.
 * The class keeps its name from when this was the "Sessions" widget, so placed widgets stay.
 */
class SessionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val async = goAsync()
        WidgetSync.redraw(context) { async.finish() }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        val async = goAsync()
        WidgetSync.redraw(context) { async.finish() }
    }

    override fun onEnabled(context: Context) = WidgetSync.redraw(context)

    override fun onDisabled(context: Context) = WidgetSync.redraw(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetSync.redraw(context)
}

/**
 * Allow and Deny from the widget (sent by [WidgetTap]), and the widget's own alarm: a picture that
 * goes out of date by itself (an approval the computer has let go of) is redrawn, and a live one
 * checks its process is still there (WidgetSync.schedule). Reached only through the app's own
 * intents (not exported).
 */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            val async = goAsync()
            WidgetSync.tick(context) { async.finish() }
            return
        }
        val id = intent.getStringExtra(EXTRA_APPROVAL) ?: return
        val allow = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        // As a notification's buttons: never on a locked phone (WidgetTap opens the session then).
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val server = intent.getStringExtra(EXTRA_SERVER)
        // Answering takes a round trip to the computer: keep the process until the widget shows how it went.
        val async = goAsync()
        if (intent.getBooleanExtra(EXTRA_MENU, false)) {
            val session = intent.getStringExtra(EXTRA_SESSION)
            if (session == null) { async.finish(); return }
            WidgetSync.answerMenu(context, server, session, id, intent.getIntExtra(EXTRA_CHOICE, -1), allow) { async.finish() }
        } else {
            WidgetSync.answer(context, id, allow, server = server) { async.finish() }
        }
    }

    companion object {
        const val ACTION_ALLOW = "com.prudnikweb.hypernova.widget.ALLOW"
        const val ACTION_DENY = "com.prudnikweb.hypernova.widget.DENY"
        const val ACTION_TICK = "com.prudnikweb.hypernova.widget.TICK"
        /** The approval's id, or the terminal dialog's menu id ([EXTRA_MENU]). */
        const val EXTRA_APPROVAL = "approval"
        /** The paired computer the row is on (data/Servers.kt). */
        const val EXTRA_SERVER = "server"
        const val EXTRA_SESSION = "session"
        /** It's Claude Code's dialog in the terminal: answered with [EXTRA_CHOICE], its option, through the menu route. */
        const val EXTRA_MENU = "menu"
        const val EXTRA_CHOICE = "choice"
    }
}

/**
 * Where every tap on the widget's list lands. A list's rows can only share one PendingIntent (the
 * template, filled in per row), and a row has both a session to open and Deny and Allow to answer
 * with, so the template is this activity, which never shows ([android.R.style.Theme_NoDisplay]):
 * - Open (a row, its Open, a computer's name): the app on that computer, at that session.
 * - Deny and Allow: handed to [WidgetActions], whose broadcast keeps the process for the round trip.
 *   On a locked phone (a lock-screen widget) nothing is answered: the session opens once the phone
 *   is unlocked, as a notification's Allow needs unlocking first.
 */
class WidgetTap : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent.getStringExtra(EXTRA_DO)
        val server = intent.getStringExtra(WidgetActions.EXTRA_SERVER)
        val session = intent.getStringExtra(WidgetActions.EXTRA_SESSION)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if ((what == DO_ALLOW || what == DO_DENY) && !locked) {
            sendBroadcast(
                Intent(this, WidgetActions::class.java)
                    .setAction(if (what == DO_ALLOW) WidgetActions.ACTION_ALLOW else WidgetActions.ACTION_DENY)
                    .putExtras(intent),
            )
        } else {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (server != null) open.putExtra(MainActivity.EXTRA_SERVER, server)
            if (session != null) open.setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, session)
            runCatching { startActivity(open) }
        }
        finish()
    }

    companion object {
        /** What the tap does: [DO_OPEN], [DO_ALLOW] or [DO_DENY]. */
        const val EXTRA_DO = "do"
        const val DO_OPEN = "open"
        const val DO_ALLOW = "allow"
        const val DO_DENY = "deny"
    }
}
