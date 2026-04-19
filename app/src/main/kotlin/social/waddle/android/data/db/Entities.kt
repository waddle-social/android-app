package social.waddle.android.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val userId: String,
    val environmentId: String,
    val sessionId: String,
    val username: String,
    val xmppLocalpart: String,
    val jid: String,
    val selected: Boolean,
    val expiresAt: String?,
)

@Entity(tableName = "waddles")
data class WaddleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val avatarUrl: String?,
    val memberCount: Int,
    val isMember: Boolean,
)

@Entity(
    tableName = "channels",
    indices = [Index("waddleId")],
    foreignKeys = [
        ForeignKey(
            entity = WaddleEntity::class,
            parentColumns = ["id"],
            childColumns = ["waddleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val waddleId: String,
    val name: String,
    val topic: String?,
    val roomJid: String,
    val createdAt: String?,
    /** XEP-0508: either "text" (default) or "forum". Sourced from room disco#info. */
    val channelType: String,
)

@Entity(
    tableName = "messages",
    indices = [Index("channelId"), Index("waddleId"), Index("serverId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val waddleId: String,
    val channelId: String,
    val roomJid: String,
    val senderId: String?,
    val senderName: String?,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
    val replyToMessageId: String?,
    val mentions: String?,
    val broadcastMention: String?,
    val sharedFileUrl: String?,
    val sharedFileName: String?,
    val sharedFileMediaType: String?,
    val sharedFileSize: Long?,
    val sharedFileDescription: String?,
    val sharedFileDisposition: String?,
    val isSticker: Boolean,
    val callInviteId: String?,
    val callExternalUri: String?,
    val callDescription: String?,
    val callMuji: Boolean,
    val retracted: Boolean,
    val pending: Boolean,
    /** XEP-0317 hats — newline-separated `uri|title` pairs. Null when the sender carries no hats. */
    val hats: String?,
    /** XEP-0201 Message Thread id this message belongs to. */
    val threadId: String?,
    /** XEP-0201 parent thread id when the thread is a sub-thread. */
    val parentThreadId: String?,
    /** XEP-0508 Forums: new-topic title when this message creates a forum topic. */
    val forumTopicTitle: String?,
    /** XEP-0508 Forums: thread id this message replies to in a forum. */
    val forumReplyThreadId: String?,
    /**
     * The sender's `<message id="…">` origin id. Distinct from [serverId] (which
     * holds the MUC-assigned XEP-0359 stanza-id) — peers use the origin id when
     * cross-referencing (e.g. XEP-0508 `<thread-reply thread-id="…">`), so we
     * need both IDs available for reconciliation.
     */
    val originStanzaId: String?,
)

@Entity(tableName = "dm_conversations")
data class DmConversationEntity(
    @PrimaryKey val peerJid: String,
    val peerUsername: String,
    val peerAvatarUrl: String?,
    val lastMessageBody: String?,
    val lastMessageAt: String?,
    val unreadCount: Int,
)

@Entity(
    tableName = "dm_messages",
    indices = [Index("peerJid"), Index("serverId"), Index("originStanzaId")],
)
data class DmMessageEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    /**
     * Sender-chosen XEP-0359 origin-id / message id. Direct replies and
     * reactions reference this ID when present, while [serverId] is kept for
     * archive dedupe.
     */
    val originStanzaId: String?,
    val peerJid: String,
    val fromJid: String,
    val senderName: String,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
    val replyToMessageId: String?,
    val mentions: String?,
    val broadcastMention: String?,
    val sharedFileUrl: String?,
    val sharedFileName: String?,
    val sharedFileMediaType: String?,
    val sharedFileSize: Long?,
    val sharedFileDescription: String?,
    val sharedFileDisposition: String?,
    val isSticker: Boolean,
    val callInviteId: String?,
    val callExternalUri: String?,
    val callDescription: String?,
    val callMuji: Boolean,
    val retracted: Boolean,
    val pending: Boolean,
    /** XEP-0317 hats — newline-separated `uri|title` pairs. Null when the peer carries no hats. */
    val hats: String?,
    /** XEP-0201 Message Thread id this DM belongs to. */
    val threadId: String?,
    /** XEP-0201 parent thread id when the thread is a sub-thread. */
    val parentThreadId: String?,
)

@Entity(
    tableName = "occupants",
    primaryKeys = ["roomJid", "userId"],
    indices = [Index("roomJid")],
)
data class OccupantEntity(
    val roomJid: String,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val role: String?,
)

@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "senderId", "emoji"],
    indices = [Index("messageId")],
)
data class ReactionEntity(
    val messageId: String,
    val senderId: String,
    val emoji: String,
    val createdAt: String,
)

@Entity(
    tableName = "dm_reactions",
    primaryKeys = ["messageId", "senderId", "emoji"],
    indices = [Index("messageId")],
)
data class DmReactionEntity(
    val messageId: String,
    val senderId: String,
    val emoji: String,
    val createdAt: String,
)

@Entity(
    tableName = "delivery_states",
    primaryKeys = ["messageId", "recipientId"],
    indices = [Index("messageId")],
)
data class DeliveryStateEntity(
    val messageId: String,
    val recipientId: String,
    val deliveredAt: String?,
    val displayedAt: String?,
)

@Entity(
    tableName = "pending_outbound_messages",
    indices = [Index("channelId")],
)
data class PendingOutboundMessageEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val roomJid: String,
    val body: String,
    val operation: String,
    val createdAt: String,
    val retryCount: Int,
)

@Entity(tableName = "link_previews")
data class LinkPreviewEntity(
    @PrimaryKey val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val fetchedAtEpochMillis: Long,
    /** True when the fetch failed or the URL has no usable OG/meta data. */
    val empty: Boolean,
)
