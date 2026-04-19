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

    data class Reconnecting(
        val attempt: Int,
        val nextDelaySeconds: Int,
        val cause: String?,
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
    /** "text" or "forum" — derived from the room's disco#info features (XEP-0508 `urn:xmpp:forums:0`). */
    val channelType: String,
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

/**
 * XEP-0317 Hat — a role badge on a message sender.
 * [uri] is the canonical role identifier (e.g. `urn:xmpp:hats:moderator`);
 * [title] is a human-readable label (e.g. `Moderator`).
 */
data class WaddleHat(
    val uri: String,
    val title: String,
)

data class XmppHistoryMessage(
    val id: String,
    val serverId: String?,
    val originStanzaId: String?,
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
    val hats: List<WaddleHat> = emptyList(),
    val threadId: String? = null,
    val parentThreadId: String? = null,
    val forumTopicTitle: String? = null,
    val forumReplyThreadId: String? = null,
    val markupRanges: List<XmppMarkupRange> = emptyList(),
)

/** XEP-0394 Message Markup: a style run over `[start, end)` of the body. */
data class XmppMarkupRange(
    val start: Int,
    val end: Int,
    val style: XmppMarkupStyle,
)

enum class XmppMarkupStyle {
    BOLD,
    ITALIC,
    STRIKE,
    CODE,
    BLOCKQUOTE,
    LINK,
}

data class XmppDirectMessage(
    val id: String,
    val serverId: String?,
    val originStanzaId: String?,
    val peerJid: String,
    val fromJid: String,
    val senderName: String,
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
    val hats: List<WaddleHat> = emptyList(),
    val threadId: String? = null,
    val parentThreadId: String? = null,
    val markupRanges: List<XmppMarkupRange> = emptyList(),
)

interface XmppClient {
    val connectionState: StateFlow<XmppConnectionState>
    val incomingMessages: Flow<XmppHistoryMessage>
    val incomingDirectMessages: Flow<XmppDirectMessage>
    val supportedFeatures: List<XepFeature>

    /** Stream of call-related stanzas (XEP-0482 invite/reject/left, XEP-0166 Jingle IQs). */
    val callSignals: Flow<InboundCallSignal>

    /** Map of bare JID → true when we've observed an `available` presence for that peer. */
    val presences: StateFlow<Map<String, Boolean>>

    /**
     * XEP-0502 MUC Activity Indicator: set of room JIDs the server has flagged
     * as recently active. Callers clear entries when the user visits a room
     * (see [clearRoomActivity]) so sidebar badges reflect unseen activity only.
     */
    val activeRoomJids: StateFlow<Set<String>>

    /** Drop [roomJid] from the active-room set (does not notify the server). */
    fun clearRoomActivity(roomJid: String)

    /**
     * XEP-0486 MUC Avatars: map of room JID → avatar hash. The hash is the
     * SHA-1 announced in the room's presence update; the actual image fetch
     * is up to the caller (typically via vCard avatar publication).
     */
    val roomAvatarHashes: StateFlow<Map<String, String>>

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

    /**
     * Loads the user's whole direct-message archive (no peer filter).
     * Used to warm DM conversation previews at connect time. The returned messages
     * span all peers; callers should derive the peer from [XmppDirectMessage.peerJid].
     */
    suspend fun loadAllDirectMessageHistory(
        ownBareJid: String,
        beforeId: String? = null,
        maxResults: Int = DEFAULT_ARCHIVE_MAX_RESULTS,
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
        replyToMessageId: String? = null,
        replyToSenderJid: String? = null,
        replyToFallbackBody: String? = null,
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

    // ----- Call signaling -----

    /**
     * XEP-0215 `<services xmlns="urn:xmpp:extdisco:2">` to the user's domain.
     * Returns the parsed STUN/TURN service list. Empty list when the server
     * doesn't support ExtDisco — WebRTC falls back to host candidates.
     */
    suspend fun discoverExternalServices(): List<ExternalService>

    /**
     * Send a XEP-0482 call invite `<message>` to [peerJid] announcing the
     * Jingle [sid] and SFU [sfuJid]. If [muji] is true, the invite advertises
     * a Muji group call.
     */
    suspend fun sendCallInvite(
        peerJid: String,
        sid: String,
        sfuJid: String,
        muji: Boolean,
        video: Boolean,
    )

    /** Send a XEP-0482 `<reject>` message for [sid] to [peerJid]. */
    suspend fun sendCallReject(
        peerJid: String,
        sid: String,
    )

    /** Send a XEP-0482 `<left>` message for [sid] to [peerJid]. */
    suspend fun sendCallLeft(
        peerJid: String,
        sid: String,
    )

    /**
     * Send a Jingle IQ (`session-initiate`, `session-accept`,
     * `transport-info`, `session-terminate`, etc.) to [to]. The caller is
     * responsible for populating the [jingleXml] element with namespace
     * `urn:xmpp:jingle:1` and the appropriate action.
     */
    suspend fun sendJingleIq(
        to: String,
        jingleXml: String,
    )
}

/**
 * Parsed XEP-0215 entry from the `<services>` IQ. A hostname, a port, and
 * (for TURN) credentials + transport.
 */
data class ExternalService(
    val host: String,
    val port: Int,
    val type: String,
    val transport: String? = null,
    val username: String? = null,
    val password: String? = null,
    val restricted: Boolean = false,
    val expires: String? = null,
)

/**
 * An inbound call-signalling stanza we've parsed off the wire but haven't
 * yet mapped to a [social.waddle.android.call.CallSignalEvent]. The signaler
 * owns that mapping so it can stay focused on the wire ↔ domain seam.
 */
sealed interface InboundCallSignal {
    /** XEP-0482 invite message arrived. */
    data class Invite(
        val fromJid: String,
        val sid: String,
        val muji: Boolean,
        val jingleJid: String?,
        val video: Boolean,
        val meetingDescription: String?,
    ) : InboundCallSignal

    /** XEP-0482 reject message arrived. */
    data class Reject(
        val fromJid: String,
        val sid: String,
    ) : InboundCallSignal

    /** XEP-0482 left message arrived. */
    data class Left(
        val fromJid: String,
        val sid: String,
    ) : InboundCallSignal

    /**
     * A Jingle IQ arrived. [jingleXml] is the serialized `<jingle>` element
     * (namespace `urn:xmpp:jingle:1`); the signaler parses it using the
     * bridge's JingleElement model. Responding with an IQ result is the
     * XmppClient's responsibility — the caller only needs to react to the
     * payload.
     */
    data class Jingle(
        val fromJid: String,
        val jingleXml: String,
    ) : InboundCallSignal
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

const val DEFAULT_ARCHIVE_MAX_RESULTS = 200
