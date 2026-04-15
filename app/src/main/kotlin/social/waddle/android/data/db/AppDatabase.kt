package social.waddle.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        WaddleEntity::class,
        ChannelEntity::class,
        MessageEntity::class,
        DmConversationEntity::class,
        DmMessageEntity::class,
        OccupantEntity::class,
        ReactionEntity::class,
        DmReactionEntity::class,
        DeliveryStateEntity::class,
        PendingOutboundMessageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun waddleDao(): WaddleDao

    abstract fun channelDao(): ChannelDao

    abstract fun messageDao(): MessageDao

    abstract fun dmConversationDao(): DmConversationDao

    abstract fun dmMessageDao(): DmMessageDao

    abstract fun occupantDao(): OccupantDao

    abstract fun reactionDao(): ReactionDao

    abstract fun dmReactionDao(): DmReactionDao

    abstract fun deliveryStateDao(): DeliveryStateDao

    abstract fun pendingOutboundDao(): PendingOutboundDao
}
