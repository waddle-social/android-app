package social.waddle.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.openid.appauth.AuthorizationService
import social.waddle.android.auth.AuthRepository
import social.waddle.android.call.CallSignaler
import social.waddle.android.call.JingleSdpBridge
import social.waddle.android.call.RealJingleSdpBridge
import social.waddle.android.call.XmppJingleCallSignaler
import social.waddle.android.data.db.AccountDao
import social.waddle.android.data.db.AppDatabase
import social.waddle.android.data.db.ChannelDao
import social.waddle.android.data.db.DeliveryStateDao
import social.waddle.android.data.db.DmConversationDao
import social.waddle.android.data.db.DmMessageDao
import social.waddle.android.data.db.DmReactionDao
import social.waddle.android.data.db.LinkPreviewDao
import social.waddle.android.data.db.MessageDao
import social.waddle.android.data.db.OccupantDao
import social.waddle.android.data.db.PendingOutboundDao
import social.waddle.android.data.db.ReactionDao
import social.waddle.android.data.db.WaddleDao
import social.waddle.android.xmpp.SessionProvider
import social.waddle.android.xmpp.SmackXmppClient
import social.waddle.android.xmpp.XmppClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("waddle.preferences_pb") },
        )

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, "waddle.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideAccountDao(database: AppDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideWaddleDao(database: AppDatabase): WaddleDao = database.waddleDao()

    @Provides
    fun provideChannelDao(database: AppDatabase): ChannelDao = database.channelDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideDmConversationDao(database: AppDatabase): DmConversationDao = database.dmConversationDao()

    @Provides
    fun provideDmMessageDao(database: AppDatabase): DmMessageDao = database.dmMessageDao()

    @Provides
    fun provideOccupantDao(database: AppDatabase): OccupantDao = database.occupantDao()

    @Provides
    fun provideReactionDao(database: AppDatabase): ReactionDao = database.reactionDao()

    @Provides
    fun provideDmReactionDao(database: AppDatabase): DmReactionDao = database.dmReactionDao()

    @Provides
    fun provideDeliveryStateDao(database: AppDatabase): DeliveryStateDao = database.deliveryStateDao()

    @Provides
    fun providePendingOutboundDao(database: AppDatabase): PendingOutboundDao = database.pendingOutboundDao()

    @Provides
    fun provideLinkPreviewDao(database: AppDatabase): LinkPreviewDao = database.linkPreviewDao()

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient =
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            install(Logging) {
                level = LogLevel.INFO
            }
            install(HttpTimeout) {
                // Per-request overrides drive link-preview fetches; these are
                // conservative global defaults for everything else.
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }

    @Provides
    @Singleton
    fun provideAuthorizationService(
        @ApplicationContext context: Context,
    ): AuthorizationService = AuthorizationService(context)

    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dm_conversations (
                        peerJid TEXT NOT NULL PRIMARY KEY,
                        peerUsername TEXT NOT NULL,
                        peerAvatarUrl TEXT,
                        lastMessageBody TEXT,
                        lastMessageAt TEXT,
                        unreadCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dm_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        serverId TEXT,
                        peerJid TEXT NOT NULL,
                        fromJid TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        body TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        editedAt TEXT,
                        retracted INTEGER NOT NULL,
                        pending INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dm_messages_peerJid ON dm_messages(peerJid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dm_messages_serverId ON dm_messages(serverId)")
            }
        }

    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MESSAGE_METADATA_COLUMNS.forEach { column ->
                    db.execSQL("ALTER TABLE messages ADD COLUMN $column")
                }
                DM_MESSAGE_METADATA_COLUMNS.forEach { column ->
                    db.execSQL("ALTER TABLE dm_messages ADD COLUMN $column")
                }
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dm_reactions (
                        messageId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        emoji TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        PRIMARY KEY(messageId, senderId, emoji)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dm_reactions_messageId ON dm_reactions(messageId)")
            }
        }

    private val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS link_previews (
                        url TEXT NOT NULL PRIMARY KEY,
                        title TEXT,
                        description TEXT,
                        imageUrl TEXT,
                        siteName TEXT,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        empty INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

    private val MESSAGE_METADATA_COLUMNS =
        listOf(
            "mentions TEXT",
            "broadcastMention TEXT",
            "sharedFileUrl TEXT",
            "sharedFileName TEXT",
            "sharedFileMediaType TEXT",
            "sharedFileSize INTEGER",
            "sharedFileDescription TEXT",
            "sharedFileDisposition TEXT",
            "isSticker INTEGER NOT NULL DEFAULT 0",
            "callInviteId TEXT",
            "callExternalUri TEXT",
            "callDescription TEXT",
            "callMuji INTEGER NOT NULL DEFAULT 0",
        )

    private val DM_MESSAGE_METADATA_COLUMNS = MESSAGE_METADATA_COLUMNS
}

@Module
@InstallIn(SingletonComponent::class)
abstract class XmppModule {
    @Binds
    @Singleton
    abstract fun bindXmppClient(client: SmackXmppClient): XmppClient

    @Binds
    @Singleton
    abstract fun bindSessionProvider(authRepository: AuthRepository): SessionProvider
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CallBindings {
    @Binds
    @Singleton
    abstract fun bindCallSignaler(impl: XmppJingleCallSignaler): CallSignaler

    @Binds
    @Singleton
    abstract fun bindJingleSdpBridge(impl: RealJingleSdpBridge): JingleSdpBridge
}
