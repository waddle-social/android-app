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
    indices = [Index("peerJid"), Index("serverId")],
)
data class DmMessageEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val peerJid: String,
    val fromJid: String,
    val senderName: String,
    val body: String,
    val createdAt: String,
    val editedAt: String?,
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
