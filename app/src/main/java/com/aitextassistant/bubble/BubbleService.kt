package com.aitextassistant.bubble

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
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aitextassistant.MainActivity
import com.aitextassistant.R

/**
 * Owns the bubble for as long as it is on screen.
 *
 * An overlay cannot outlive a background process, so this runs in the
 * foreground, which is what the permanent notification is for. That notification
 * is not overhead to be hidden: it is the only honest signal that something is
 * floating above every app, and it carries the switch to turn it off.
 */
class BubbleService : Service() {

    private lateinit var owner: OverlayOwner
    private var window: BubbleWindow? = null

    override fun onCreate() {
        super.onCreate()
        owner = OverlayOwner().apply { start() }
        window = BubbleWindow(
            context = this,
            owner = owner,
            textSource = ClipboardTextSource(this),
            onStopRequested = { stopSelf() },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // The permission can be revoked from settings while we are running.
        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        window?.show()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        window?.hide()
        window = null
        owner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bubble", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the rewrite bubble is floating over other apps."
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Remix bubble is on")
            .setContentText("Copy a message, then tap the bubble.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    companion object {
        private const val ACTION_STOP = "com.aitextassistant.bubble.STOP"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 1

        /** Compose-observable so the settings screen tracks the bubble without polling. */
        var isRunning by mutableStateOf(false)
            private set

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }
}
