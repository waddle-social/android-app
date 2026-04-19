package social.waddle.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.waddle.android.MainActivity
import social.waddle.android.R
import social.waddle.android.data.MuteRepository
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.XmppClient
import social.waddle.android.xmpp.XmppDirectMessage
import social.waddle.android.xmpp.XmppHistoryMessage
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Observes the [XmppClient] incoming-message streams and posts user-facing
 * notifications for DMs and channel mentions. Own messages, chat-state updates,
 * read markers, reactions, retractions and the currently-viewed conversation
 * are skipped so the user is not spammed.
 */
@Singleton
class WaddleNotificationPoster
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val xmppClient: XmppClient,
        private val activeConversation: ActiveConversationTracker,
        private val muteRepository: MuteRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val notificationManager = NotificationManagerCompat.from(context)
        private val conversations = ConcurrentHashMap<String, ConversationState>()
        private val messageGate = MessageNotificationGate()

        /**
         * Set of message keys (`serverId ?: id`) we've already turned into
         * notifications this process lifetime — protects against double-fire
         * when the same stanza arrives twice (e.g., XEP-0198 resumption
         * replay overlapping with a fresh live message).
         */
        private val postedKeys = ConcurrentHashMap.newKeySet<String>()

        // Snapshot of the muted set, hydrated eagerly once start() runs so the
        // stanza callbacks can read it synchronously without runBlocking.
        // Eagerly stateIn means the subscription is kept while the poster is
        // live; stop() tears down the whole scope which cancels it.
        private val mutedSnapshot: StateFlow<Set<String>> =
            muteRepository.muted.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptySet(),
            )

        private var roomJob: Job? = null
        private var directJob: Job? = null
        private var connectionJob: Job? = null
        private var ownJid: String? = null

        fun start(ownBareJid: String) {
            stop()
            ownJid = ownBareJid
            messageGate.resetForStart()
            connectionJob =
                scope.launch {
                    xmppClient.connectionState.collect(messageGate::onConnectionState)
                }
            roomJob =
                scope.launch {
                    xmppClient.incomingMessages.collect { message ->
                        runCatching { postRoomMessage(message, ownBareJid) }
                            .onFailure { WaddleLog.error("Failed to post room notification.", it) }
                    }
                }
            directJob =
                scope.launch {
                    xmppClient.incomingDirectMessages.collect { message ->
                        runCatching { postDirectMessage(message, ownBareJid) }
                            .onFailure { WaddleLog.error("Failed to post direct notification.", it) }
                    }
                }
        }

        fun stop() {
            roomJob?.cancel()
            directJob?.cancel()
            connectionJob?.cancel()
            roomJob = null
            directJob = null
            connectionJob = null
            ownJid = null
            conversations.clear()
            postedKeys.clear()
            notificationManager.cancelAll()
        }

        /** Drop the tracked state for a conversation the user just opened. */
        fun markConversationRead(key: ConversationKey) {
            val state = conversations.remove(key.storeKey) ?: return
            notificationManager.cancel(state.tag, state.notificationId)
        }

        /** Non-blocking muted check — reads the eagerly-hydrated snapshot. */
        private fun isMuted(key: String): Boolean = key in mutedSnapshot.value

        private fun postRoomMessage(
            message: XmppHistoryMessage,
            ownBareJid: String,
        ) {
            if (!message.isUserMessage()) return
            if (message.senderId == ownBareJid) return
            val ownLocalpart = ownBareJid.substringBefore('@')
            if (!message.mentionsUser(ownLocalpart)) return
            if (activeConversation.isActive(ActiveConversation.Room(message.roomJid))) return
            if (isMuted(MuteRepository.roomKey(message.roomJid))) return
            val messageKey = message.serverId ?: message.id
            if (!postedKeys.add(messageKey)) return
            if (messageGate.shouldSuppress(message.createdAt, activeConversation.isAppForeground())) return
            val senderName = message.senderName ?: "Someone"
            val body = message.body.takeIf { it.isNotBlank() } ?: "mentioned you"
            addAndPost(
                key = ConversationKey.Room(message.roomJid),
                senderName = senderName,
                body = body,
                timestamp = parseTimestamp(message.createdAt),
                channelId = WaddleNotificationChannels.MENTIONS,
            )
        }

        private fun postDirectMessage(
            message: XmppDirectMessage,
            ownBareJid: String,
        ) {
            if (!message.isUserMessage()) return
            if (message.fromJid == ownBareJid) return
            if (activeConversation.isActive(ActiveConversation.Direct(message.peerJid))) return
            if (isMuted(MuteRepository.directKey(message.peerJid))) return
            val messageKey = message.serverId ?: message.id
            if (!postedKeys.add(messageKey)) return
            if (messageGate.shouldSuppress(message.createdAt, activeConversation.isAppForeground())) return
            val senderName = message.senderName.ifBlank { message.peerJid.substringBefore('@') }
            val body = message.body.takeIf { it.isNotBlank() } ?: "New direct message"
            addAndPost(
                key = ConversationKey.Direct(message.peerJid),
                senderName = senderName,
                body = body,
                timestamp = parseTimestamp(message.createdAt),
                channelId = WaddleNotificationChannels.MESSAGES,
            )
        }

        private fun parseTimestamp(createdAt: String): Long =
            MessageNotificationGate.timestampMillis(createdAt) ?: System.currentTimeMillis()

        private fun addAndPost(
            key: ConversationKey,
            senderName: String,
            body: String,
            timestamp: Long,
            channelId: String,
        ) {
            val state =
                conversations.compute(key.storeKey) { _, existing ->
                    val current =
                        existing ?: ConversationState(
                            key = key,
                            notificationId = idFor(key.storeKey),
                            tag = key.tag,
                            channelId = channelId,
                        )
                    current.messages.add(StoredMessage(senderName, body, timestamp))
                    while (current.messages.size > MAX_MESSAGES_PER_CONVERSATION) {
                        current.messages.removeFirst()
                    }
                    current
                } ?: return
            postIfPermitted(state)
        }

        /**
         * Guarded entry point: inline the runtime permission check so Android
         * lint flow-analyses it and accepts the subsequent [notify] call.
         * On API < 33 the POST_NOTIFICATIONS runtime permission does not exist,
         * so we short-circuit based on the notification-manager's "are notifications enabled" flag.
         */
        private fun postIfPermitted(state: ConversationState) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            } else {
                val enabled = context.getSystemService<NotificationManager>()?.areNotificationsEnabled() ?: true
                if (!enabled) return
            }
            postMessagingStyle(state)
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        private fun postMessagingStyle(state: ConversationState) {
            val style = buildMessagingStyle(state)
            val contentIntent = buildContentIntent(state)
            val replyAction = buildReplyAction(state)
            val markReadAction = buildMarkReadAction(state)
            val notification =
                NotificationCompat
                    .Builder(context, state.channelId)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setStyle(style)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(contentIntent)
                    .addAction(replyAction)
                    .addAction(markReadAction)
                    .setGroup(GROUP_KEY)
                    .build()
            runCatching { notificationManager.notify(state.tag, state.notificationId, notification) }
                .onFailure { throwable -> WaddleLog.error("notify() failed.", throwable) }
            postGroupSummary()
        }

        private fun buildMessagingStyle(state: ConversationState): NotificationCompat.MessagingStyle {
            val you =
                Person
                    .Builder()
                    .setName("You")
                    .setKey(ownJid ?: "me")
                    .build()
            val style = NotificationCompat.MessagingStyle(you)
            val conversationTitle =
                when (val key = state.key) {
                    is ConversationKey.Room -> "#${key.roomJid.substringBefore('@')}"
                    is ConversationKey.Direct -> state.messages.lastOrNull()?.sender ?: key.peerJid.substringBefore('@')
                }
            style.conversationTitle = conversationTitle
            style.isGroupConversation = state.key is ConversationKey.Room
            state.messages.forEach { stored ->
                val sender =
                    Person
                        .Builder()
                        .setName(stored.sender)
                        .setKey(stored.sender)
                        .build()
                style.addMessage(stored.body, stored.timestamp, sender)
            }
            return style
        }

        private fun buildContentIntent(state: ConversationState): PendingIntent {
            val tapIntent = deepLinkIntent(state.key, NotificationAction.OPEN)
            return PendingIntent.getActivity(
                context,
                state.notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun buildReplyAction(state: ConversationState): NotificationCompat.Action {
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Reply").build()
            val replyIntent = actionIntent(state, WaddleNotificationActionReceiver.ACTION_REPLY)
            val replyPending =
                PendingIntent.getBroadcast(
                    context,
                    state.notificationId + 1,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            return NotificationCompat.Action
                .Builder(R.drawable.ic_launcher, "Reply", replyPending)
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(true)
                .setShowsUserInterface(false)
                .build()
        }

        private fun buildMarkReadAction(state: ConversationState): NotificationCompat.Action {
            val markReadIntent = actionIntent(state, WaddleNotificationActionReceiver.ACTION_MARK_READ)
            val markReadPending =
                PendingIntent.getBroadcast(
                    context,
                    state.notificationId + 2,
                    markReadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat.Action
                .Builder(R.drawable.ic_launcher, "Mark as read", markReadPending)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        }

        private fun actionIntent(
            state: ConversationState,
            action: String,
        ): Intent =
            Intent(context, WaddleNotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(WaddleNotificationActionReceiver.EXTRA_CONVERSATION_KIND, state.key.kind)
                putExtra(WaddleNotificationActionReceiver.EXTRA_CONVERSATION_ID, state.key.value)
                putExtra(WaddleNotificationActionReceiver.EXTRA_NOTIFICATION_ID, state.notificationId)
                putExtra(WaddleNotificationActionReceiver.EXTRA_TAG, state.tag)
            }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        private fun postGroupSummary() {
            if (conversations.size < 2) return
            val summary =
                NotificationCompat
                    .Builder(context, WaddleNotificationChannels.MESSAGES)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setContentTitle("Waddle")
                    .setContentText("${conversations.size} conversations")
                    .setAutoCancel(true)
                    .build()
            runCatching { notificationManager.notify("summary", SUMMARY_ID, summary) }
        }

        private fun deepLinkIntent(
            key: ConversationKey,
            action: NotificationAction,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                this.action = action.raw
                putExtra(EXTRA_CONVERSATION_KIND, key.kind)
                putExtra(EXTRA_CONVERSATION_ID, key.value)
            }

        private fun idFor(key: String): Int = key.hashCode().absoluteValue.coerceAtLeast(1)

        companion object {
            const val REMOTE_INPUT_KEY = "waddle.reply.text"
            const val EXTRA_CONVERSATION_KIND = "waddle.conv.kind"
            const val EXTRA_CONVERSATION_ID = "waddle.conv.id"
            const val CONV_KIND_ROOM = "room"
            const val CONV_KIND_DIRECT = "direct"
            const val ACTION_OPEN = "social.waddle.android.OPEN"
            private const val GROUP_KEY = "waddle.messages"
            private const val SUMMARY_ID = 0x5355_4d4d // "SUMM"
            private const val MAX_MESSAGES_PER_CONVERSATION = 25
        }

        enum class NotificationAction(
            val raw: String,
        ) {
            OPEN(ACTION_OPEN),
        }
    }

sealed interface ConversationKey {
    val kind: String
    val value: String
    val storeKey: String
    val tag: String

    data class Room(
        val roomJid: String,
    ) : ConversationKey {
        override val kind: String = WaddleNotificationPoster.CONV_KIND_ROOM
        override val value: String = roomJid
        override val storeKey: String = "room:$roomJid"
        override val tag: String = "room"
    }

    data class Direct(
        val peerJid: String,
    ) : ConversationKey {
        override val kind: String = WaddleNotificationPoster.CONV_KIND_DIRECT
        override val value: String = peerJid
        override val storeKey: String = "dm:$peerJid"
        override val tag: String = "dm"
    }
}

internal class ConversationState(
    val key: ConversationKey,
    val notificationId: Int,
    val tag: String,
    val channelId: String,
) {
    val messages: ArrayDeque<StoredMessage> = ArrayDeque()
}

internal data class StoredMessage(
    val sender: String,
    val body: String,
    val timestamp: Long,
)

private fun XmppHistoryMessage.isUserMessage(): Boolean =
    retractsId == null &&
        reactionTargetId == null &&
        replacesId == null &&
        displayedId == null &&
        chatState == null &&
        body.isNotBlank()

private fun XmppDirectMessage.isUserMessage(): Boolean =
    retractsId == null &&
        reactionTargetId == null &&
        replacesId == null &&
        displayedId == null &&
        chatState == null &&
        body.isNotBlank()

private fun XmppHistoryMessage.mentionsUser(username: String): Boolean {
    if (mentions.any { it.equals(username, ignoreCase = true) }) return true
    if (body.contains("@$username", ignoreCase = true)) return true
    if (body.contains("@here", ignoreCase = true)) return true
    if (body.contains("@everyone", ignoreCase = true)) return true
    return false
}
