package social.waddle.android.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import social.waddle.android.data.ChatRepository
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.SessionProvider
import social.waddle.android.xmpp.XmppClient
import social.waddle.android.xmpp.XmppConnectionState

/**
 * Belt-and-suspenders periodic wake-up. WorkManager runs this every ~15 min.
 *
 * Responsibility:
 *  - If the foreground service is alive and the XMPP stream is [XmppConnectionState.Connected],
 *    there is nothing to do — return success and let the live service keep handling messages.
 *  - If the connection is down (OEM killed the service, Doze deferred it, etc.),
 *    briefly connect, let the MAM catch-up + carbons drain into [WaddleNotificationPoster],
 *    then disconnect again. The next live user interaction or the BOOT_COMPLETED
 *    receiver will re-start the foreground service for real.
 */
@HiltWorker
class WaddleCatchUpWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionProvider: SessionProvider,
        private val xmppClient: XmppClient,
        private val chatRepository: ChatRepository,
        private val notificationPoster: WaddleNotificationPoster,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            runCatching { runCatchUp() }
                .onFailure { throwable -> WaddleLog.error("Catch-up worker failed.", throwable) }
                .getOrElse { Result.retry() }

        private suspend fun runCatchUp(): Result {
            val session = sessionProvider.currentSession() ?: return Result.success()
            if (xmppClient.connectionState.value is XmppConnectionState.Connected) {
                // Live service is already handling the stream — no-op.
                return Result.success()
            }
            var weConnected = false
            try {
                notificationPoster.start(session.jid)
                chatRepository.connect(session)
                weConnected = true
                // Linger long enough for MAM catch-up (warmDmArchive +
                // XEP-0198 resumption) to drain new messages into the
                // notification poster.
                delay(CATCH_UP_LINGER_MILLIS)
            } finally {
                if (weConnected) {
                    runCatching { xmppClient.disconnect() }
                    notificationPoster.stop()
                }
            }
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "waddle-catch-up"
            const val CATCH_UP_LINGER_MILLIS = 20_000L
        }
    }
