package com.da4a.smartcity.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import com.da4a.smartcity.MainActivity
import com.da4a.smartcity.R
import com.da4a.smartcity.widget.EmergencyWidget

/**
 * Keeps the one [BeaconEngine] of this process running in the foreground, so a victim's phone
 * stays findable with the screen locked. The open app runs it in search mode; the Emergency
 * widget path switches it to emergency mode, which only "I'm safe" in the app ends.
 */
class BeaconService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = engine ?: BeaconEngine(this).also { engine = it }
        when (intent?.action) {
            ACTION_EMERGENCY -> current.emergency = true
            ACTION_SAFE -> current.emergency = false
        }
        goForeground(current.emergency)
        current.start()
        EmergencyWidget.refresh(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        EmergencyWidget.refresh(this)
        super.onDestroy()
    }

    private fun goForeground(emergency: Boolean) {
        val notification = notification(emergency)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // Declaring location without the permission throws on Android 14+.
            if (Permissions.locationGranted(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(emergency: Boolean): Notification {
        val channel = if (emergency) {
            // Not a heads-up: the victim has just pressed SOS, and a banner would cover their screen.
            NotificationChannel(CHANNEL_EMERGENCY, "Emergency mode", NotificationManager.IMPORTANCE_DEFAULT)
        } else {
            NotificationChannel(CHANNEL_SEARCH, "Searching", NotificationManager.IMPORTANCE_LOW)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // Opening the app is the only action: stopping an emergency needs the in-app confirmation.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channel.id)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(if (emergency) "Emergency mode on" else "Looking for people who need help")
            .setContentText(if (emergency) "Your phone is being searched" else null)
            .setColor(if (emergency) 0xFFFF453A.toInt() else 0xFF0A84FF.toInt())
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_SEARCH = "com.da4a.smartcity.action.SEARCH"
        const val ACTION_EMERGENCY = "com.da4a.smartcity.action.START_EMERGENCY"
        const val ACTION_SAFE = "com.da4a.smartcity.action.SAFE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_SEARCH = "search"
        private const val CHANNEL_EMERGENCY = "sos"

        /** The engine of this process while the service runs. */
        var engine by mutableStateOf<BeaconEngine?>(null)
            private set

        fun send(context: Context, action: String) {
            context.startForegroundService(Intent(context, BeaconService::class.java).setAction(action))
        }
    }
}
