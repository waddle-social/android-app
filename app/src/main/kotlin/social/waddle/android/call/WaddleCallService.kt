package social.waddle.android.call

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
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
import social.waddle.android.notifications.WaddleNotificationChannels
import social.waddle.android.util.WaddleLog
import javax.inject.Inject

/**
 * Foreground service that owns the lifetime of an active voice/video call.
 *
 * Android mandates that a running microphone or camera capture must be paired
 * with an equivalently-typed foreground service — this class is that service.
 * It is started by [social.waddle.android.ui.WaddleApp] (or programmatically
 * from [CallController]) the moment a call enters an active state
 * (`OutgoingRinging` / `Incoming` / `Connecting` / `InCall`) and stopped as
 * soon as [CallState.Ended] or [CallState.Idle] is observed.
 *
 * The notification uses [NotificationCompat.CATEGORY_CALL] so Android renders
 * the call-style chrome (the "tap to return" banner) while the UI is
 * backgrounded.
 */
@AndroidEntryPoint
class WaddleCallService : Service() {
    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var ringer: Ringer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundSafely(callManager.state.value)
        if (observeJob?.isActive == true) return START_NOT_STICKY

        observeJob =
            scope.launch {
                callManager.state.collect { state ->
                    when (state) {
                        is CallState.Ended, CallState.Idle -> {
                            ringer.stop()
                            stopSelf()
                        }

                        is CallState.Incoming -> {
                            ringer.start()
                            updateForegroundNotification(state)
                        }

                        else -> {
                            ringer.stop()
                            updateForegroundNotification(state)
                        }
                    }
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        ringer.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely(state: CallState) {
        // API 14+ prohibits startForeground with type=0 when the manifest
        // declares a specific type. We check RECORD_AUDIO first — the call
        // literally cannot function without it — and gracefully stop if the
        // user hasn't granted it yet. The caller (CallController) is then
        // responsible for prompting before a second attempt.
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            WaddleLog.info("WaddleCallService aborting: RECORD_AUDIO not granted.")
            stopSelf()
            return
        }
        val notification = buildCallNotification(state)
        val type = resolveForegroundServiceType()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, type)
            }.onFailure { cause ->
                WaddleLog.error("startForeground failed; stopping service.", cause)
                stopSelf()
            }
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Narrow the foreground-service type to whichever capture is actually
     * running. Always claims MICROPHONE (the permission was verified in
     * [startForegroundSafely] before we got here, and the manifest declares
     * it). Adds CAMERA only when we're actively rendering local video AND
     * the user has granted camera permission — declaring CAMERA without
     * permission raises SecurityException on Android 14+.
     *
     * The FOREGROUND_SERVICE_TYPE_* constants were added in API 30 (R).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveForegroundServiceTypeR(): Int {
        val camGranted = hasPermission(Manifest.permission.CAMERA)
        val videoActive = callManager.localVideoTrack.value != null
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (camGranted && videoActive) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return type
    }

    private fun resolveForegroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolveForegroundServiceTypeR()
        } else {
            0
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updateForegroundNotification(state: CallState) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildCallNotification(state))
    }

    private fun buildCallNotification(state: CallState): Notification {
        val (title, text) = labelsFor(state)
        val returnIntent =
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val returnPending =
            PendingIntent.getActivity(
                this,
                FOREGROUND_NOTIFICATION_ID,
                returnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val hangUpPending = buildHangUpPendingIntent()
        val callStyle =
            if (state is CallState.Incoming) {
                val acceptIntent =
                    Intent(this, WaddleCallControlReceiver::class.java).apply {
                        action = WaddleCallControlReceiver.ACTION_ACCEPT
                    }
                val acceptPending =
                    PendingIntent.getBroadcast(
                        this,
                        FOREGROUND_NOTIFICATION_ID + 1,
                        acceptIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                val caller =
                    androidx.core.app.Person
                        .Builder()
                        .setName(state.peerDisplayName ?: state.peerJid.substringBefore('@'))
                        .setImportant(true)
                        .build()
                NotificationCompat.CallStyle.forIncomingCall(caller, hangUpPending, acceptPending)
            } else {
                val caller =
                    androidx.core.app.Person
                        .Builder()
                        .setName(bestPeerLabel(state))
                        .build()
                NotificationCompat.CallStyle.forOngoingCall(caller, hangUpPending)
            }

        return NotificationCompat
            .Builder(this, WaddleNotificationChannels.CALLS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(returnPending)
            .setFullScreenIntent(returnPending, state is CallState.Incoming)
            .setStyle(callStyle)
            .build()
    }

    private fun buildHangUpPendingIntent(): PendingIntent {
        val intent =
            Intent(this, WaddleCallControlReceiver::class.java).apply {
                action = WaddleCallControlReceiver.ACTION_HANG_UP
            }
        return PendingIntent.getBroadcast(
            this,
            FOREGROUND_NOTIFICATION_ID + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun bestPeerLabel(state: CallState): String =
        when (state) {
            is CallState.OutgoingRinging -> state.peerJid.substringBefore('@')
            is CallState.Connecting -> state.peerJid.substringBefore('@')
            is CallState.InCall -> state.peerJid.substringBefore('@').ifEmpty { "peer" }
            else -> "peer"
        }

    private fun labelsFor(state: CallState): Pair<String, String> =
        when (state) {
            is CallState.OutgoingRinging -> {
                "Calling ${state.peerJid.substringBefore('@')}" to "Ringing…"
            }

            is CallState.Incoming -> {
                "Incoming call" to (state.peerDisplayName ?: state.peerJid.substringBefore('@'))
            }

            is CallState.Connecting -> {
                "Call with ${state.peerJid.substringBefore('@')}" to "Connecting…"
            }

            is CallState.InCall -> {
                "Call with ${state.peerJid.substringBefore('@')}" to "In call"
            }

            is CallState.Ended -> {
                "Call ended" to (state.reason ?: "")
            }

            CallState.Idle -> {
                "Waddle" to ""
            }
        }

    companion object {
        // 0x57_41_44_43 == "WADC" — "Waddle Call", distinct from the messaging
        // service's "WADL" so both notifications can coexist.
        private const val FOREGROUND_NOTIFICATION_ID = 0x57_41_44_43

        fun start(context: Context) {
            val intent = Intent(context, WaddleCallService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { WaddleLog.error("Failed to start call service.", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WaddleCallService::class.java)
            context.stopService(intent)
        }
    }
}
