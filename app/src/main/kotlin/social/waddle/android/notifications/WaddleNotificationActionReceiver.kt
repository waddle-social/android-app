package social.waddle.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.waddle.android.data.ChatRepository
import social.waddle.android.util.WaddleLog
import javax.inject.Inject

/**
 * Handles notification-side actions without opening the UI:
 *  - `ACTION_REPLY`: posts the typed reply via [ChatRepository] and appends it
 *    to the existing MessagingStyle notification via [WaddleNotificationPoster.markConversationRead] flow.
 *  - `ACTION_MARK_READ`: dismisses the notification and clears the DM unread count.
 */
@AndroidEntryPoint
class WaddleNotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chatRepository: ChatRepository

    @Inject lateinit var poster: WaddleNotificationPoster

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val kind = intent.getStringExtra(EXTRA_CONVERSATION_KIND) ?: return
        val id = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val tag = intent.getStringExtra(EXTRA_TAG)
        val key = conversationKeyOf(kind, id) ?: return
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent, key, notificationId, tag)
            ACTION_MARK_READ -> handleMarkRead(context, key, notificationId, tag)
        }
    }

    private fun handleReply(
        context: Context,
        intent: Intent,
        key: ConversationKey,
        notificationId: Int,
        tag: String?,
    ) {
        val replyBody =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(WaddleNotificationPoster.REMOTE_INPUT_KEY)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (key) {
                    is ConversationKey.Direct -> {
                        chatRepository.sendDirectMessageFromNotification(
                            peerJid = key.peerJid,
                            body = replyBody,
                        )
                    }

                    is ConversationKey.Room -> {
                        chatRepository.sendRoomMessageFromNotification(
                            roomJid = key.roomJid,
                            body = replyBody,
                        )
                    }
                }
                // Collapse the notification once the user has replied; they now have context
                // that the message was sent. The next incoming message rebuilds the thread.
                NotificationManagerCompat.from(context).cancel(tag, notificationId)
                poster.markConversationRead(key)
            } catch (throwable: Throwable) {
                WaddleLog.error("Notification reply failed.", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleMarkRead(
        context: Context,
        key: ConversationKey,
        notificationId: Int,
        tag: String?,
    ) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (key is ConversationKey.Direct) {
                    runCatching { chatRepository.markDmRead(key.peerJid) }
                }
                NotificationManagerCompat.from(context).cancel(tag, notificationId)
                poster.markConversationRead(key)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun conversationKeyOf(
        kind: String,
        id: String,
    ): ConversationKey? =
        when (kind) {
            WaddleNotificationPoster.CONV_KIND_DIRECT -> ConversationKey.Direct(id)
            WaddleNotificationPoster.CONV_KIND_ROOM -> ConversationKey.Room(id)
            else -> null
        }

    companion object {
        const val ACTION_REPLY = "social.waddle.android.NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "social.waddle.android.NOTIFICATION_MARK_READ"
        const val EXTRA_CONVERSATION_KIND = WaddleNotificationPoster.EXTRA_CONVERSATION_KIND
        const val EXTRA_CONVERSATION_ID = WaddleNotificationPoster.EXTRA_CONVERSATION_ID
        const val EXTRA_NOTIFICATION_ID = "waddle.notification.id"
        const val EXTRA_TAG = "waddle.notification.tag"
    }
}
