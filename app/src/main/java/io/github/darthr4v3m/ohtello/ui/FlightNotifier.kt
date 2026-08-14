package io.github.darthr4v3m.ohtello.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.darthr4v3m.ohtello.MainActivity
import io.github.darthr4v3m.ohtello.R

/**
 * The one notification this app posts: the app has gone to the background with
 * the drone still up, so the keepalive has stopped and the drone is about to
 * land itself.
 *
 * It exists because that warning has to reach someone who is by definition not
 * looking at the screen. Nothing here runs in the background — no service, no
 * alarm, no wakelock. It is posted from the moment the app is backgrounded and
 * cancelled when it comes back.
 */
class FlightNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    /** False on Android 13+ until the user grants POST_NOTIFICATIONS. */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun warnDroneWillLand() {
        val manager = manager ?: return
        if (!canNotify()) return
        ensureChannel(manager)

        val reopen = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("The drone is about to land itself")
            .setContentText("Oh-Tello is in the background, so it stopped holding the drone up. Reopen to keep flying.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(reopen)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun clear() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Drone about to land",
                // High: this is time-critical and the phone is likely pocketed,
                // so it needs to make a sound rather than sit silently in the shade.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Warns that the app was backgrounded while the drone was flying."
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "drone-about-to-land"
        const val NOTIFICATION_ID = 1
    }
}
