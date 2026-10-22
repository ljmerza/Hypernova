package com.prudnikweb.hypernova.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.prudnikweb.hypernova.BuildConfig

/**
 * The two widgets the owner picked: the small one is the sun (5-hour usage) with who's working;
 * the large one lists sessions, answers approvals and shows both meters. Both are drawn by
 * [WidgetSync] from the live hub; the platform's callbacks only ask it to redraw (there's no
 * update period: the app pushes every change itself). Each holds the broadcast open ([goAsync])
 * until the picture is drawn, since a process started just for this broadcast may be stopped as
 * soon as it returns.
 */
abstract class HypernovaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val async = goAsync()
        WidgetSync.redraw(context) { async.finish() }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Resized: older launchers have one layout per widget, chosen for its size.
        val async = goAsync()
        WidgetSync.redraw(context) { async.finish() }
    }

    override fun onEnabled(context: Context) = WidgetSync.redraw(context)

    override fun onDisabled(context: Context) = WidgetSync.redraw(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetSync.redraw(context)
}

/** The small widget: the sun. */
class SunWidget : HypernovaWidget()

/** The large widget: sessions, approvals and usage. */
class SessionsWidget : HypernovaWidget()

/**
 * Allow and Deny on the large widget, and the widgets' own alarm: a picture that goes out of
 * date by itself (a reset passing, an approval the Mac has given up on) is redrawn, and a live
 * one checks its process is still there (WidgetSync.schedule).
 */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            val async = goAsync()
            WidgetSync.tick(context) { async.finish() }
            return
        }
        if (intent.action == ACTION_RENDER && BuildConfig.DEBUG) {
            val async = goAsync()
            WidgetSync.render(context, intent.getStringExtra("kind"), intent.getStringExtra("sizes"), intent.getStringExtra("name"), intent.getStringExtra("sample")) { async.finish() }
            return
        }
        if (intent.action == ACTION_SAMPLE && BuildConfig.DEBUG) {
            val async = goAsync()
            WidgetSync.sample(context, intent.getStringExtra("sample")) { async.finish() }
            return
        }
        val id = intent.getStringExtra(EXTRA_APPROVAL) ?: return
        val allow = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        // Answering takes a round trip to the Mac: keep the process until the widget shows how it went.
        val async = goAsync()
        WidgetSync.answer(context, id, allow) { async.finish() }
    }

    companion object {
        const val ACTION_ALLOW = "com.prudnikweb.hypernova.widget.ALLOW"
        const val ACTION_DENY = "com.prudnikweb.hypernova.widget.DENY"
        const val ACTION_TICK = "com.prudnikweb.hypernova.widget.TICK"
        /** Debug builds: draw the widget at given sizes into the app's files (WidgetSync.render). */
        const val ACTION_RENDER = "com.prudnikweb.hypernova.widget.RENDER"
        /** Debug builds: draw a sample picture into the placed widgets (WidgetSamples). */
        const val ACTION_SAMPLE = "com.prudnikweb.hypernova.widget.SAMPLE"
        const val EXTRA_APPROVAL = "approval"
    }
}
