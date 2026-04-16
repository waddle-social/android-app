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
 *    ask the [ChatRepository] to connect. The XMPP singleton serialises connects
 *    via a mutex, so if the UI / service happen to bring up their own stream at
 *    the same time we will deduplicate to one connection.
 *
 * **The worker never calls `xmppClient.disconnect()`.** A live connection may
 * be owned by the foreground service or the UI; tearing it down from here
 * shoots a live stream out from under them. We let the natural teardown path
 * (logout / process death) handle it.
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
            notificationPoster.start(session.jid)
            chatRepository.connect(session)
            // Linger long enough for MAM catch-up (warmDmArchive +
            // XEP-0198 resumption) to drain new messages into the
            // notification poster.
            delay(CATCH_UP_LINGER_MILLIS)
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "waddle-catch-up"
            const val CATCH_UP_LINGER_MILLIS = 20_000L
        }
    }
