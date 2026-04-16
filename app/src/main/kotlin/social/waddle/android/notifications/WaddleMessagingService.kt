package social.waddle.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import social.waddle.android.MainActivity
import social.waddle.android.R
import social.waddle.android.auth.AuthRepository
import social.waddle.android.data.ChatRepository
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.XmppClient
import social.waddle.android.xmpp.XmppConnectionState
import javax.inject.Inject
import kotlin.math.absoluteValue

/**
 * Foreground service that keeps the Waddle XMPP connection alive while the user
 * is signed in, so incoming messages can trigger notifications even when the UI
 * is backgrounded. Started on sign-in, stopped on sign-out.
 */
@AndroidEntryPoint
class WaddleMessagingService : Service() {
    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var xmppClient: XmppClient

    @Inject
    lateinit var notificationPoster: WaddleNotificationPoster

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundCompat()
        if (connectJob?.isActive == true) {
            return START_STICKY
        }
        connectJob =
            scope.launch {
                val session = authRepository.session.value?.stored
                if (session == null) {
                    WaddleLog.info("WaddleMessagingService started with no session — stopping.")
                    stopSelf()
                    return@launch
                }
                notificationPoster.start(session.jid)
                runCatching { chatRepository.connect(session) }
                    .onFailure { throwable ->
                        WaddleLog.error("WaddleMessagingService failed to connect XMPP.", throwable)
                    }
                // Observe connection state so we update the ongoing notification text
                // (e.g., "Reconnecting…") as the state changes.
                xmppClient.connectionState.collect { state ->
                    updateForegroundNotification(stateSummary(state))
                }
            }
        return START_STICKY
    }

    override fun onDestroy() {
        connectJob?.cancel()
        scope.cancel()
        notificationPoster.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification("Connecting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(summary: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(summary))
    }

    private fun buildForegroundNotification(summary: String): Notification {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                FOREGROUND_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, WaddleNotificationChannels.SERVICE)
            .setContentTitle("Waddle")
            .setContentText(summary)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun stateSummary(state: XmppConnectionState): String =
        when (state) {
            is XmppConnectionState.Connected -> {
                "Connected"
            }

            is XmppConnectionState.Connecting -> {
                "Connecting…"
            }

            is XmppConnectionState.Reconnecting -> {
                "Reconnecting (attempt ${state.attempt}, retry in ${state.nextDelaySeconds}s)"
            }

            is XmppConnectionState.Disconnected -> {
                "Offline"
            }

            is XmppConnectionState.Failed -> {
                "Connection failed: ${state.message}"
            }
        }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 0x57_41_44_4c // "WADL"
        private val NOTIFICATION_ID = FOREGROUND_NOTIFICATION_ID.absoluteValue

        fun start(context: Context) {
            val intent = Intent(context, WaddleMessagingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WaddleMessagingService::class.java)
            context.stopService(intent)
        }
    }
}
