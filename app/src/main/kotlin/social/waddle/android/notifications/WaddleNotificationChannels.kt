package social.waddle.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object WaddleNotificationChannels {
    const val MESSAGES = "waddle_messages"
    const val MENTIONS = "waddle_mentions"
    const val SERVICE = "waddle_service"
    const val CALLS = "waddle_calls"

    fun registerAll(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New direct and channel messages."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MENTIONS,
                "Mentions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Messages that @mention you."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE,
                "Connection",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps Waddle connected so you get messages in real time."
                setShowBadge(false)
            },
        )
        // Calls use a dedicated, LOW-importance channel so the ongoing
        // notification is persistent but doesn't buzz. Ringtone & vibration
        // for incoming invites are surfaced via the system call-style
        // notification the foreground service posts, not this channel.
        manager.createNotificationChannel(
            NotificationChannel(
                CALLS,
                "Calls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing voice and video calls."
                setShowBadge(false)
            },
        )
    }
}
