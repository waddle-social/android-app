package social.waddle.android.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ReactionSummary(
    val messageId: String,
    val emoji: String,
    val count: Int,
)

data class DeliverySummary(
    val messageId: String,
    val count: Int,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE selected = 1 LIMIT 1")
    fun observeSelected(): Flow<AccountEntity?>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE accounts SET selected = 0")
    suspend fun clearSelected()

    @Query("DELETE FROM accounts")
    suspend fun clear()
}

@Dao
interface WaddleDao {
    @Query("SELECT * FROM waddles ORDER BY name COLLATE NOCASE")
    fun observeWaddles(): Flow<List<WaddleEntity>>

    @Upsert
    suspend fun upsertAll(waddles: List<WaddleEntity>)

    @Query("DELETE FROM waddles")
    suspend fun clear()
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE waddleId = :waddleId ORDER BY name COLLATE NOCASE")
    fun observeChannels(waddleId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun getChannel(channelId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE roomJid = :roomJid LIMIT 1")
    suspend fun getChannelByRoomJid(roomJid: String): ChannelEntity?

    @Upsert
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE waddleId = :waddleId")
    suspend fun clearForWaddle(waddleId: String)

    @Query("DELETE FROM channels WHERE id = :channelId")
    suspend fun delete(channelId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE channelId = :channelId ORDER BY createdAt ASC")
    fun observeMessages(channelId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE channelId = :channelId ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestMessage(channelId: String): MessageEntity?

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT id FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun getIdByServerId(serverId: String): String?

    @Query("DELETE FROM messages")
    suspend fun clear()

    @Query("UPDATE messages SET pending = 0, serverId = :serverId WHERE id = :localId")
    suspend fun markSent(
        localId: String,
        serverId: String?,
    )

    @Query("UPDATE messages SET body = :body, editedAt = :editedAt WHERE id = :messageId OR serverId = :messageId")
    suspend fun markEdited(
        messageId: String,
        body: String,
        editedAt: String,
    )

    @Query("UPDATE messages SET retracted = 1, body = '' WHERE id = :messageId OR serverId = :messageId")
    suspend fun markRetracted(messageId: String)
}

@Dao
interface DmConversationDao {
    @Query("SELECT * FROM dm_conversations ORDER BY lastMessageAt DESC")
    fun observeConversations(): Flow<List<DmConversationEntity>>

    @Upsert
    suspend fun upsert(conversation: DmConversationEntity)

    @Query("DELETE FROM dm_conversations")
    suspend fun clear()
}

@Dao
interface DmMessageDao {
    @Query("SELECT * FROM dm_messages WHERE peerJid = :peerJid ORDER BY createdAt ASC")
    fun observeMessages(peerJid: String): Flow<List<DmMessageEntity>>

    @Query("SELECT * FROM dm_messages WHERE peerJid = :peerJid ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestMessage(peerJid: String): DmMessageEntity?

    @Upsert
    suspend fun upsertAll(messages: List<DmMessageEntity>)

    @Upsert
    suspend fun upsert(message: DmMessageEntity)

    @Query("SELECT id FROM dm_messages WHERE serverId = :serverId LIMIT 1")
    suspend fun getIdByServerId(serverId: String): String?

    @Query("UPDATE dm_messages SET pending = 0, serverId = :serverId WHERE id = :localId")
    suspend fun markSent(
        localId: String,
        serverId: String?,
    )

    @Query("UPDATE dm_messages SET body = :body, editedAt = :editedAt WHERE id = :messageId OR serverId = :messageId")
    suspend fun markEdited(
        messageId: String,
        body: String,
        editedAt: String,
    )

    @Query("UPDATE dm_messages SET retracted = 1, body = '' WHERE id = :messageId OR serverId = :messageId")
    suspend fun markRetracted(messageId: String)

    @Query("DELETE FROM dm_messages")
    suspend fun clear()
}

@Dao
interface OccupantDao {
    @Query("SELECT * FROM occupants WHERE roomJid = :roomJid ORDER BY displayName COLLATE NOCASE")
    fun observeOccupants(roomJid: String): Flow<List<OccupantEntity>>

    @Upsert
    suspend fun upsertAll(occupants: List<OccupantEntity>)

    @Query("DELETE FROM occupants")
    suspend fun clear()
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE messageId = :messageId ORDER BY createdAt")
    fun observeReactions(messageId: String): Flow<List<ReactionEntity>>

    @Query(
        """
        SELECT reactions.messageId AS messageId, reactions.emoji AS emoji, COUNT(*) AS count
        FROM reactions
        INNER JOIN messages ON reactions.messageId = messages.id OR reactions.messageId = messages.serverId
        WHERE messages.channelId = :channelId
        GROUP BY reactions.messageId, reactions.emoji
        ORDER BY MIN(reactions.createdAt) ASC
        """,
    )
    fun observeReactionSummaries(channelId: String): Flow<List<ReactionSummary>>

    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND senderId = :senderId AND emoji = :emoji")
    suspend fun delete(
        messageId: String,
        senderId: String,
        emoji: String,
    )

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND senderId = :senderId")
    suspend fun deleteForSender(
        messageId: String,
        senderId: String,
    )

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM reactions
            WHERE messageId = :messageId AND senderId = :senderId AND emoji = :emoji
        )
        """,
    )
    suspend fun hasReaction(
        messageId: String,
        senderId: String,
        emoji: String,
    ): Boolean

    @Query("DELETE FROM reactions")
    suspend fun clear()
}

@Dao
interface DmReactionDao {
    @Query(
        """
        SELECT dm_reactions.messageId AS messageId, dm_reactions.emoji AS emoji, COUNT(*) AS count
        FROM dm_reactions
        INNER JOIN dm_messages ON dm_reactions.messageId = dm_messages.id OR dm_reactions.messageId = dm_messages.serverId
        WHERE dm_messages.peerJid = :peerJid
        GROUP BY dm_reactions.messageId, dm_reactions.emoji
        ORDER BY MIN(dm_reactions.createdAt) ASC
        """,
    )
    fun observeReactionSummaries(peerJid: String): Flow<List<ReactionSummary>>

    @Upsert
    suspend fun upsert(reaction: DmReactionEntity)

    @Query("DELETE FROM dm_reactions WHERE messageId = :messageId AND senderId = :senderId AND emoji = :emoji")
    suspend fun delete(
        messageId: String,
        senderId: String,
        emoji: String,
    )

    @Query("DELETE FROM dm_reactions WHERE messageId = :messageId AND senderId = :senderId")
    suspend fun deleteForSender(
        messageId: String,
        senderId: String,
    )

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM dm_reactions
            WHERE messageId = :messageId AND senderId = :senderId AND emoji = :emoji
        )
        """,
    )
    suspend fun hasReaction(
        messageId: String,
        senderId: String,
        emoji: String,
    ): Boolean

    @Query("DELETE FROM dm_reactions")
    suspend fun clear()
}

@Dao
interface DeliveryStateDao {
    @Query(
        """
        SELECT messageId AS messageId, COUNT(*) AS count
        FROM delivery_states
        GROUP BY messageId
        """,
    )
    fun observeDisplayedSummaries(): Flow<List<DeliverySummary>>

    @Upsert
    suspend fun upsert(state: DeliveryStateEntity)

    @Query("DELETE FROM delivery_states")
    suspend fun clear()
}

@Dao
interface PendingOutboundDao {
    @Query("SELECT * FROM pending_outbound_messages ORDER BY createdAt ASC")
    suspend fun pending(): List<PendingOutboundMessageEntity>

    @Upsert
    suspend fun upsert(message: PendingOutboundMessageEntity)

    @Query("DELETE FROM pending_outbound_messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_outbound_messages")
    suspend fun clear()
}
