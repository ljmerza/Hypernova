package com.prudnikweb.hypernova.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.prudnikweb.hypernova.R

/**
 * The rows of the widget's list, which scrolls: the launcher asks for them when the widget is drawn
 * and whenever [WidgetSync] says they changed (notifyAppWidgetViewDataChanged). They're made from
 * the picture last drawn ([WidgetSync.current]), so the header and the list always agree. Bound only
 * by the system (BIND_REMOTEVIEWS).
 */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)

    private class Rows(private val ctx: Context) : RemoteViewsFactory {
        @Volatile private var items: List<WidgetViews.Item> = emptyList()

        override fun onCreate() = WidgetSync.start(ctx as Application)

        override fun onDataSetChanged() {
            items = WidgetViews.items(WidgetSync.current())
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews =
            items.getOrNull(position)?.let { WidgetViews.item(ctx, it) } ?: RemoteViews(ctx.packageName, R.layout.widget_need_server)

        override fun getLoadingView(): RemoteViews? = null

        /** A computer's name and a session's row. */
        override fun getViewTypeCount(): Int = 2

        override fun getItemId(position: Int): Long = items.getOrNull(position)?.key?.hashCode()?.toLong() ?: position.toLong()

        override fun hasStableIds(): Boolean = true

        override fun onDestroy() {}
    }
}
