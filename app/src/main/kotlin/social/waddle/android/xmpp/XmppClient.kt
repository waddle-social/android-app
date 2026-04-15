package social.waddle.android.xmpp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import social.waddle.android.data.model.ChatFileAttachment
import social.waddle.android.data.model.ChatMessageDraft
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.model.XepFeature

sealed interface XmppConnectionState {
    data object Disconnected : XmppConnectionState

    data object Connecting : XmppConnectionState

    data class Connected(
        val jid: String,
    ) : XmppConnectionState

    data class Failed(
        val message: String,
    ) : XmppConnectionState
}

data class XmppWaddleRef(
    val id: String,
    val name: String,
)

data class XmppChannelRef(
    val id: String,
    val name: String,
    val roomJid: String,
)

data class XmppMessagePage<T>(
    val messages: List<T>,
    val firstId: String?,
    val lastId: String?,
    val complete: Boolean,
)

data class XmppSharedFile(
    val url: String,
    val disposition: String = "inline",
    val name: String? = null,
    val mediaType: String? = null,
    val size: Long? = null,
    val description: String? = null,
)

data class XmppCallInvite(
    val inviteId: String,
    val muji: Boolean,
    val jingleSid: String? = null,
    val jingleJid: String? = null,
    val externalUri: String? = null,
    val meetingDescription: String? = null,
)

data class XmppHistoryMessage(
    val id: String,
    val serverId: String?,
    val roomJid: String,
    val senderId: String?,
    val senderName: String?,
    val body: String,
    val createdAt: String,
    val editedAt: String? = null,
    val replyToMessageId: String? = null,
    val mentions: List<String> = emptyList(),
    val broadcastMention: String? = null,
    val sharedFile: XmppSharedFile? = null,
    val isSticker: Boolean = false,
    val callInvite: XmppCallInvite? = null,
    val replacesId: String? = null,
    val retractsId: String? = null,
    val reactionTargetId: String? = null,
    val reactionEmojis: List<String> = emptyList(),
    val displayedId: String? = null,
    val chatState: ChatState? = null,
)

data class XmppDirectMessage(
    val id: String,
    val serverId: String?,
    val peerJid: String,
    val fromJid: String,
    val senderName: String,
    val body: String,
    val createdAt: String,
    val editedAt: String? = null,
    val mentions: List<String> = emptyList(),
    val broadcastMention: String? = null,
    val sharedFile: XmppSharedFile? = null,
    val isSticker: Boolean = false,
    val callInvite: XmppCallInvite? = null,
    val replacesId: String? = null,
    val retractsId: String? = null,
    val reactionTargetId: String? = null,
    val reactionEmojis: List<String> = emptyList(),
    val displayedId: String? = null,
    val chatState: ChatState? = null,
)

interface XmppClient {
    val connectionState: StateFlow<XmppConnectionState>
    val incomingMessages: Flow<XmppHistoryMessage>
    val incomingDirectMessages: Flow<XmppDirectMessage>
    val supportedFeatures: List<XepFeature>

    suspend fun connect(
        session: StoredSession,
        environment: WaddleEnvironment,
    )

    suspend fun disconnect()

    suspend fun discoverWaddles(session: StoredSession): List<XmppWaddleRef>

    suspend fun discoverChannels(
        session: StoredSession,
        waddleId: String,
    ): List<XmppChannelRef>

    suspend fun sendGroupMessage(draft: ChatMessageDraft): String

    suspend fun loadMessageHistory(
        roomJid: String,
        afterId: String? = null,
        beforeId: String? = null,
    ): XmppMessagePage<XmppHistoryMessage>

    suspend fun loadDirectMessageHistory(
        ownBareJid: String,
        peerJid: String,
        afterId: String? = null,
        beforeId: String? = null,
    ): XmppMessagePage<XmppDirectMessage>

    suspend fun searchMessageHistory(
        roomJid: String,
        query: String,
    ): List<XmppHistoryMessage>

    suspend fun searchDirectMessageHistory(
        ownBareJid: String,
        peerJid: String,
        query: String,
    ): List<XmppDirectMessage>

    suspend fun sendDirectMessage(
        peerJid: String,
        body: String,
        stanzaId: String? = null,
        sharedFile: ChatFileAttachment? = null,
    ): String

    suspend fun markDirectDisplayed(
        peerJid: String,
        messageId: String,
    )

    suspend fun setDirectChatState(
        peerJid: String,
        state: ChatState,
    )

    suspend fun correctDirectMessage(
        peerJid: String,
        messageId: String,
        replacement: String,
    )

    suspend fun retractDirectMessage(
        peerJid: String,
        messageId: String,
    )

    suspend fun reactDirect(
        peerJid: String,
        messageId: String,
        emojis: List<String>,
    )

    suspend fun markDisplayed(
        roomJid: String,
        messageId: String,
    )

    suspend fun setChatState(
        roomJid: String,
        state: ChatState,
    )

    suspend fun correctMessage(
        roomJid: String,
        messageId: String,
        replacement: String,
    )

    suspend fun retractMessage(
        roomJid: String,
        messageId: String,
    )

    suspend fun react(
        roomJid: String,
        messageId: String,
        emojis: List<String>,
    )

    suspend fun uploadSlot(
        filename: String,
        contentType: String,
        sizeBytes: Long,
    ): UploadSlot?
}

enum class ChatState {
    Active,
    Composing,
    Paused,
    Inactive,
    Gone,
}

data class UploadSlot(
    val putUrl: String,
    val getUrl: String,
    val headers: Map<String, String> = emptyMap(),
)
