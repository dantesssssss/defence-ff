package com.da4a.smartcity.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.da4a.smartcity.MainActivity
import com.da4a.smartcity.R
import com.da4a.smartcity.beacon.BeaconService

/** Home-screen SOS button; turns red while this phone is in emergency mode. */
class EmergencyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {

        /** Shows the current emergency state on every placed widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EmergencyWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        fun views(context: Context, active: Boolean = BeaconService.engine?.emergency == true): RemoteViews {
            // Both states open the app in emergency mode; when already active that simply shows it.
            val open = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_EMERGENCY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val layout = if (active) R.layout.widget_emergency_active else R.layout.widget_emergency_idle
            return RemoteViews(context.packageName, layout).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }
    }
}
