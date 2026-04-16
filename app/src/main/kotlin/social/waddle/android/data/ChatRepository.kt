package social.waddle.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.waddle.android.data.db.ChannelDao
import social.waddle.android.data.db.ChannelEntity
import social.waddle.android.data.db.DeliveryStateDao
import social.waddle.android.data.db.DeliveryStateEntity
import social.waddle.android.data.db.DeliverySummary
import social.waddle.android.data.db.DmConversationDao
import social.waddle.android.data.db.DmConversationEntity
import social.waddle.android.data.db.DmMessageDao
import social.waddle.android.data.db.DmMessageEntity
import social.waddle.android.data.db.DmReactionDao
import social.waddle.android.data.db.DmReactionEntity
import social.waddle.android.data.db.MessageDao
import social.waddle.android.data.db.MessageEntity
import social.waddle.android.data.db.PendingOutboundDao
import social.waddle.android.data.db.PendingOutboundMessageEntity
import social.waddle.android.data.db.ReactionDao
import social.waddle.android.data.db.ReactionEntity
import social.waddle.android.data.db.ReactionSummary
import social.waddle.android.data.db.WaddleDao
import social.waddle.android.data.db.WaddleEntity
import social.waddle.android.data.model.ChatFileAttachment
import social.waddle.android.data.model.ChatMessageDraft
import social.waddle.android.data.model.CreateChannelRequest
import social.waddle.android.data.model.CreateWaddleRequest
import social.waddle.android.data.model.MemberSummary
import social.waddle.android.data.model.OutboundFileAttachment
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.UpdateChannelRequest
import social.waddle.android.data.model.UpdateWaddleRequest
import social.waddle.android.data.model.UserSearchResult
import social.waddle.android.data.model.WaddleSummary
import social.waddle.android.data.network.WaddleApi
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.ChatState
import social.waddle.android.xmpp.SessionProvider
import social.waddle.android.xmpp.XmppClient
import social.waddle.android.xmpp.XmppConnectionState
import social.waddle.android.xmpp.XmppDirectMessage
import social.waddle.android.xmpp.XmppHistoryMessage
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LargeClass")
class ChatRepository
    @Inject
    constructor(
        private val waddleDao: WaddleDao,
        private val channelDao: ChannelDao,
        private val messageDao: MessageDao,
        private val dmConversationDao: DmConversationDao,
        private val dmMessageDao: DmMessageDao,
        private val reactionDao: ReactionDao,
        private val dmReactionDao: DmReactionDao,
        private val deliveryStateDao: DeliveryStateDao,
        private val pendingOutboundDao: PendingOutboundDao,
        private val xmppClient: XmppClient,
        private val api: WaddleApi,
        private val sessionProvider: SessionProvider,
    ) {
        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var incomingMessagesJob: Job? = null
        private var incomingDirectMessagesJob: Job? = null
        private var connectionStateJob: Job? = null
        private val mutableRoomTyping = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
        private val mutableDirectTyping = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val roomTypingTimeouts = mutableMapOf<RoomTypingKey, Job>()
        private val directTypingTimeouts = mutableMapOf<String, Job>()

        fun observeWaddles(): Flow<List<WaddleEntity>> = waddleDao.observeWaddles()

        fun observeChannels(waddleId: String): Flow<List<ChannelEntity>> = channelDao.observeChannels(waddleId)

        fun observeMessages(channelId: String): Flow<List<MessageEntity>> = messageDao.observeMessages(channelId)

        fun observeReactionSummaries(channelId: String): Flow<List<ReactionSummary>> = reactionDao.observeReactionSummaries(channelId)

        fun observeDmReactionSummaries(peerJid: String): Flow<List<ReactionSummary>> = dmReactionDao.observeReactionSummaries(peerJid)

        fun observeDisplayedSummaries(): Flow<List<DeliverySummary>> = deliveryStateDao.observeDisplayedSummaries()

        fun observeRoomTyping(): StateFlow<Map<String, Set<String>>> = mutableRoomTyping.asStateFlow()

        fun observeDirectTyping(): StateFlow<Map<String, Boolean>> = mutableDirectTyping.asStateFlow()

        fun observePresences(): StateFlow<Map<String, Boolean>> = xmppClient.presences

        fun observeDmConversations(): Flow<List<DmConversationEntity>> = dmConversationDao.observeConversations()

        fun observeDmMessages(peerJid: String): Flow<List<DmMessageEntity>> = dmMessageDao.observeMessages(peerJid)

        suspend fun connect(session: StoredSession) {
            // Start collectors BEFORE login so live messages delivered immediately
            // after authentication are not dropped into a SharedFlow without subscribers.
            startIncomingMessageCollection()
            startIncomingDirectMessageCollection()
            startConnectionStateWatch()
            xmppClient.connect(session, session.environment)
            // replayPending is also triggered by the connection-state watch on every
            // successful (re)connect, so we don't need to call it here.
            warmDmArchive(session)
        }

        private suspend fun warmDmArchive(session: StoredSession) {
            runCatching {
                val page = xmppClient.loadAllDirectMessageHistory(ownBareJid = session.jid)
                if (page.messages.isEmpty()) {
                    return@runCatching
                }
                cacheDirectMessageHistoryPage(page.messages)
                WaddleLog.info("Warmed ${page.messages.size} DM archive messages on connect.")
            }.onFailure { throwable ->
                WaddleLog.error("Failed to warm DM archive on connect.", throwable)
            }
        }

        private fun startConnectionStateWatch() {
            if (connectionStateJob?.isActive == true) {
                return
            }
            connectionStateJob =
                repositoryScope.launch {
                    var wasConnected = false
                    xmppClient.connectionState.collect { state ->
                        val nowConnected = state is XmppConnectionState.Connected
                        if (nowConnected && !wasConnected) {
                            runCatching { replayPending() }
                                .onFailure { throwable ->
                                    WaddleLog.error("Failed to replay pending outbound after connect.", throwable)
                                }
                        }
                        wasConnected = nowConnected
                    }
                }
        }

        suspend fun refreshWaddles(session: StoredSession) =
            withContext(Dispatchers.IO) {
                val waddles =
                    xmppClient.discoverWaddles(session).map { ref ->
                        WaddleEntity(
                            id = ref.id,
                            name = ref.name,
                            description = null,
                            avatarUrl = null,
                            memberCount = 0,
                            isMember = true,
                        )
                    }
                waddleDao.upsertAll(waddles)
            }

        suspend fun refreshChannels(
            session: StoredSession,
            waddleId: String,
        ) = withContext(Dispatchers.IO) {
            val channels =
                xmppClient.discoverChannels(session, waddleId).map { ref ->
                    ChannelEntity(
                        id = ref.id,
                        waddleId = waddleId,
                        name = ref.name,
                        topic = null,
                        roomJid = ref.roomJid,
                        createdAt = null,
                    )
                }
            channelDao.upsertAll(channels)
        }

        suspend fun refreshMessages(channelId: String) =
            withContext(Dispatchers.IO) {
                val channel = channelDao.getChannel(channelId) ?: return@withContext
                var afterId: String? = null
                var loadedPages = 0
                var hasMore = true
                while (hasMore) {
                    val page = xmppClient.loadMessageHistory(channel.roomJid, afterId = afterId)
                    cacheMessageHistoryPage(channel, page.messages)
                    afterId = page.lastId
                    loadedPages += 1
                    hasMore =
                        pageHasMore(
                            pageComplete = page.complete,
                            cursorId = afterId,
                            pageSize = page.messages.size,
                            loadedPages = loadedPages,
                        )
                }
            }

        suspend fun loadOlderMessages(channelId: String) =
            withContext(Dispatchers.IO) {
                val channel = channelDao.getChannel(channelId) ?: return@withContext
                val oldest = messageDao.oldestMessage(channelId) ?: return@withContext
                val beforeId = oldest.serverId ?: oldest.id
                val page = xmppClient.loadMessageHistory(channel.roomJid, beforeId = beforeId)
                cacheMessageHistoryPage(channel, page.messages)
            }

        suspend fun publicWaddles(
            session: StoredSession,
            query: String,
        ): List<WaddleSummary> =
            withContext(Dispatchers.IO) {
                api.publicWaddles(session.environment, session.sessionId, query)
            }

        suspend fun joinWaddle(
            session: StoredSession,
            waddleId: String,
        ) = withContext(Dispatchers.IO) {
            api.joinWaddle(session.environment, session.sessionId, waddleId)
            refreshWaddles(session)
        }

        suspend fun createWaddle(
            session: StoredSession,
            name: String,
            description: String?,
            isPublic: Boolean,
        ) = withContext(Dispatchers.IO) {
            api.createWaddle(
                environment = session.environment,
                sessionId = session.sessionId,
                input =
                    CreateWaddleRequest(
                        name = name,
                        description = description,
                        isPublic = isPublic,
                    ),
            )
            refreshWaddles(session)
        }

        suspend fun updateWaddle(
            session: StoredSession,
            waddleId: String,
            name: String?,
            description: String?,
            isPublic: Boolean?,
        ) = withContext(Dispatchers.IO) {
            api.updateWaddle(
                environment = session.environment,
                sessionId = session.sessionId,
                waddleId = waddleId,
                input =
                    UpdateWaddleRequest(
                        name = name,
                        description = description,
                        isPublic = isPublic,
                    ),
            )
            refreshWaddles(session)
        }

        suspend fun deleteWaddle(
            session: StoredSession,
            waddleId: String,
        ) = withContext(Dispatchers.IO) {
            api.deleteWaddle(session.environment, session.sessionId, waddleId)
            refreshWaddles(session)
        }

        suspend fun createChannel(
            session: StoredSession,
            waddleId: String,
            name: String,
            description: String?,
        ) = withContext(Dispatchers.IO) {
            api.createChannel(
                environment = session.environment,
                sessionId = session.sessionId,
                waddleId = waddleId,
                input =
                    CreateChannelRequest(
                        name = name,
                        description = description,
                    ),
            )
            refreshChannels(session, waddleId)
        }

        suspend fun deleteChannel(
            session: StoredSession,
            waddleId: String,
            channelId: String,
        ) = withContext(Dispatchers.IO) {
            api.deleteChannel(session.environment, session.sessionId, waddleId, channelId)
            channelDao.delete(channelId)
        }

        suspend fun updateChannel(
            session: StoredSession,
            waddleId: String,
            channelId: String,
            name: String?,
            description: String?,
            position: Int?,
        ) = withContext(Dispatchers.IO) {
            api.updateChannel(
                environment = session.environment,
                sessionId = session.sessionId,
                waddleId = waddleId,
                channelId = channelId,
                input =
                    UpdateChannelRequest(
                        name = name,
                        description = description,
                        position = position,
                    ),
            )
            refreshChannels(session, waddleId)
        }

        suspend fun members(
            session: StoredSession,
            waddleId: String,
        ): List<MemberSummary> =
            withContext(Dispatchers.IO) {
                api.listMembers(session.environment, session.sessionId, waddleId)
            }

        suspend fun searchUsers(
            session: StoredSession,
            query: String,
        ): List<UserSearchResult> =
            withContext(Dispatchers.IO) {
                if (query.isBlank()) {
                    emptyList()
                } else {
                    api.searchUsers(session.environment, session.sessionId, query.trim())
                }
            }

        suspend fun searchMessages(
            channelId: String,
            query: String,
        ) = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext
            }
            val channel = channelDao.getChannel(channelId) ?: return@withContext
            val results = xmppClient.searchMessageHistory(channel.roomJid, query)
            cacheMessageHistoryPage(channel, results)
        }

        suspend fun searchDirectMessages(
            session: StoredSession,
            peerJid: String,
            query: String,
        ) = withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext
            }
            val results = xmppClient.searchDirectMessageHistory(session.jid, peerJid, query)
            cacheDirectMessageHistoryPage(results)
        }

        suspend fun addMember(
            session: StoredSession,
            waddleId: String,
            userId: String,
            role: String,
        ): List<MemberSummary> =
            withContext(Dispatchers.IO) {
                api.addMember(session.environment, session.sessionId, waddleId, userId, role)
                api.listMembers(session.environment, session.sessionId, waddleId)
            }

        suspend fun updateMemberRole(
            session: StoredSession,
            waddleId: String,
            userId: String,
            role: String,
        ): List<MemberSummary> =
            withContext(Dispatchers.IO) {
                api.updateMemberRole(session.environment, session.sessionId, waddleId, userId, role)
                api.listMembers(session.environment, session.sessionId, waddleId)
            }

        suspend fun removeMember(
            session: StoredSession,
            waddleId: String,
            userId: String,
        ): List<MemberSummary> =
            withContext(Dispatchers.IO) {
                api.removeMember(session.environment, session.sessionId, waddleId, userId)
                api.listMembers(session.environment, session.sessionId, waddleId)
            }

        suspend fun refreshDirectMessages(
            session: StoredSession,
            peerJid: String,
        ) = withContext(Dispatchers.IO) {
            var afterId: String? = null
            var loadedPages = 0
            var hasMore = true
            while (hasMore) {
                val page = xmppClient.loadDirectMessageHistory(session.jid, peerJid, afterId = afterId)
                cacheDirectMessageHistoryPage(page.messages)
                afterId = page.lastId
                loadedPages += 1
                hasMore =
                    pageHasMore(
                        pageComplete = page.complete,
                        cursorId = afterId,
                        pageSize = page.messages.size,
                        loadedPages = loadedPages,
                    )
            }
        }

        suspend fun loadOlderDirectMessages(
            session: StoredSession,
            peerJid: String,
        ) = withContext(Dispatchers.IO) {
            val oldest = dmMessageDao.oldestMessage(peerJid) ?: return@withContext
            val beforeId = oldest.serverId ?: oldest.id
            val page = xmppClient.loadDirectMessageHistory(session.jid, peerJid, beforeId = beforeId)
            cacheDirectMessageHistoryPage(page.messages)
        }

        suspend fun sendDirectMessage(
            session: StoredSession,
            peerJid: String,
            body: String,
        ) = withContext(Dispatchers.IO) {
            val localId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val message =
                DmMessageEntity(
                    id = localId,
                    serverId = null,
                    peerJid = peerJid,
                    fromJid = session.jid,
                    senderName = session.username,
                    body = body,
                    createdAt = now,
                    editedAt = null,
                    mentions = null,
                    broadcastMention = null,
                    sharedFileUrl = null,
                    sharedFileName = null,
                    sharedFileMediaType = null,
                    sharedFileSize = null,
                    sharedFileDescription = null,
                    sharedFileDisposition = null,
                    isSticker = false,
                    callInviteId = null,
                    callExternalUri = null,
                    callDescription = null,
                    callMuji = false,
                    retracted = false,
                    pending = true,
                )
            dmMessageDao.upsert(message)
            upsertConversation(message)
            xmppClient.sendDirectMessage(peerJid, body, localId)
            dmMessageDao.clearPending(localId)
        }

        suspend fun sendDirectAttachment(
            session: StoredSession,
            peerJid: String,
            attachment: OutboundFileAttachment,
        ) = withContext(Dispatchers.IO) {
            val sharedFile = uploadAttachment(attachment)
            val localId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val body = sharedFile.messageBody()
            val message =
                DmMessageEntity(
                    id = localId,
                    serverId = null,
                    peerJid = peerJid,
                    fromJid = session.jid,
                    senderName = session.username,
                    body = body,
                    createdAt = now,
                    editedAt = null,
                    mentions = null,
                    broadcastMention = null,
                    sharedFileUrl = sharedFile.url,
                    sharedFileName = sharedFile.name,
                    sharedFileMediaType = sharedFile.mediaType,
                    sharedFileSize = sharedFile.size,
                    sharedFileDescription = sharedFile.description,
                    sharedFileDisposition = sharedFile.disposition,
                    isSticker = false,
                    callInviteId = null,
                    callExternalUri = null,
                    callDescription = null,
                    callMuji = false,
                    retracted = false,
                    pending = true,
                )
            dmMessageDao.upsert(message)
            upsertConversation(message)
            xmppClient.sendDirectMessage(peerJid, body, localId, sharedFile)
            dmMessageDao.clearPending(localId)
        }

        suspend fun sendDirectDisplayed(
            peerJid: String,
            messageId: String,
        ) = withContext(Dispatchers.IO) {
            xmppClient.markDirectDisplayed(peerJid, messageId)
        }

        suspend fun setDirectComposing(
            peerJid: String,
            composing: Boolean,
        ) = withContext(Dispatchers.IO) {
            xmppClient.setDirectChatState(peerJid, if (composing) ChatState.Composing else ChatState.Active)
        }

        suspend fun editDirect(
            peerJid: String,
            messageId: String,
            body: String,
        ) = withContext(Dispatchers.IO) {
            xmppClient.correctDirectMessage(peerJid, messageId, body)
            dmMessageDao.markEdited(messageId, body, Instant.now().toString())
        }

        suspend fun retractDirect(
            peerJid: String,
            messageId: String,
        ) = withContext(Dispatchers.IO) {
            xmppClient.retractDirectMessage(peerJid, messageId)
            dmMessageDao.markRetracted(messageId)
        }

        suspend fun reactDirect(
            peerJid: String,
            messageId: String,
            senderId: String,
            emoji: String,
        ) = withContext(Dispatchers.IO) {
            val hasReaction = dmReactionDao.hasReaction(messageId, senderId, emoji)
            val nextEmojis = if (hasReaction) emptyList() else listOf(emoji)
            xmppClient.reactDirect(peerJid, messageId, nextEmojis)
            dmReactionDao.deleteForSender(messageId, senderId)
            nextEmojis.forEach { nextEmoji ->
                dmReactionDao.upsert(
                    DmReactionEntity(
                        messageId = messageId,
                        senderId = senderId,
                        emoji = nextEmoji,
                        createdAt = Instant.now().toString(),
                    ),
                )
            }
        }

        suspend fun sendMessage(
            session: StoredSession,
            waddleId: String,
            channelId: String,
            body: String,
            replyToMessageId: String? = null,
        ) = withContext(Dispatchers.IO) {
            val channel = requireNotNull(channelDao.getChannel(channelId)) { "Channel $channelId is not cached." }
            val localId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val draft =
                ChatMessageDraft(
                    channelId = channelId,
                    roomJid = channel.roomJid,
                    body = body,
                    stanzaId = localId,
                    replyToMessageId = replyToMessageId,
                )
            messageDao.upsert(
                MessageEntity(
                    id = localId,
                    serverId = null,
                    waddleId = waddleId,
                    channelId = channelId,
                    roomJid = channel.roomJid,
                    senderId = session.userId,
                    senderName = session.username,
                    body = body,
                    createdAt = now,
                    editedAt = null,
                    replyToMessageId = replyToMessageId,
                    mentions = extractBodyMentions(body).joinToStringOrNull(),
                    broadcastMention = extractBroadcastMention(body),
                    sharedFileUrl = null,
                    sharedFileName = null,
                    sharedFileMediaType = null,
                    sharedFileSize = null,
                    sharedFileDescription = null,
                    sharedFileDisposition = null,
                    isSticker = false,
                    callInviteId = null,
                    callExternalUri = null,
                    callDescription = null,
                    callMuji = false,
                    retracted = false,
                    pending = true,
                ),
            )
            pendingOutboundDao.upsert(
                PendingOutboundMessageEntity(
                    id = localId,
                    channelId = channelId,
                    roomJid = channel.roomJid,
                    body = body,
                    operation = "send",
                    createdAt = now,
                    retryCount = 0,
                ),
            )
            xmppClient.sendGroupMessage(draft)
            messageDao.clearPending(localId)
            pendingOutboundDao.delete(localId)
        }

        suspend fun sendAttachment(
            session: StoredSession,
            waddleId: String,
            channelId: String,
            attachment: OutboundFileAttachment,
        ) = withContext(Dispatchers.IO) {
            val channel = requireNotNull(channelDao.getChannel(channelId)) { "Channel $channelId is not cached." }
            val sharedFile = uploadAttachment(attachment)
            val localId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val body = sharedFile.messageBody()
            val draft =
                ChatMessageDraft(
                    channelId = channelId,
                    roomJid = channel.roomJid,
                    body = body,
                    stanzaId = localId,
                    sharedFile = sharedFile,
                )
            messageDao.upsert(
                MessageEntity(
                    id = localId,
                    serverId = null,
                    waddleId = waddleId,
                    channelId = channelId,
                    roomJid = channel.roomJid,
                    senderId = session.userId,
                    senderName = session.username,
                    body = body,
                    createdAt = now,
                    editedAt = null,
                    replyToMessageId = null,
                    mentions = null,
                    broadcastMention = null,
                    sharedFileUrl = sharedFile.url,
                    sharedFileName = sharedFile.name,
                    sharedFileMediaType = sharedFile.mediaType,
                    sharedFileSize = sharedFile.size,
                    sharedFileDescription = sharedFile.description,
                    sharedFileDisposition = sharedFile.disposition,
                    isSticker = false,
                    callInviteId = null,
                    callExternalUri = null,
                    callDescription = null,
                    callMuji = false,
                    retracted = false,
                    pending = true,
                ),
            )
            xmppClient.sendGroupMessage(draft)
            messageDao.clearPending(localId)
        }

        suspend fun sendDisplayed(
            channelId: String,
            messageId: String,
        ) = withContext(Dispatchers.IO) {
            channelDao.getChannel(channelId)?.let { channel ->
                xmppClient.markDisplayed(channel.roomJid, messageId)
            }
        }

        suspend fun setComposing(
            channelId: String,
            composing: Boolean,
        ) = withContext(Dispatchers.IO) {
            channelDao.getChannel(channelId)?.let { channel ->
                xmppClient.setChatState(channel.roomJid, if (composing) ChatState.Composing else ChatState.Active)
            }
        }

        suspend fun retract(
            channelId: String,
            messageId: String,
        ) = withContext(Dispatchers.IO) {
            channelDao.getChannel(channelId)?.let { channel ->
                xmppClient.retractMessage(channel.roomJid, messageId)
                messageDao.markRetracted(messageId)
            }
        }

        suspend fun edit(
            channelId: String,
            messageId: String,
            body: String,
        ) = withContext(Dispatchers.IO) {
            channelDao.getChannel(channelId)?.let { channel ->
                xmppClient.correctMessage(channel.roomJid, messageId, body)
                messageDao.markEdited(messageId, body, Instant.now().toString())
            }
        }

        suspend fun react(
            channelId: String,
            messageId: String,
            senderId: String,
            emoji: String,
        ) = withContext(Dispatchers.IO) {
            channelDao.getChannel(channelId)?.let { channel ->
                val hasReaction = reactionDao.hasReaction(messageId, senderId, emoji)
                val nextEmojis = if (hasReaction) emptyList() else listOf(emoji)
                xmppClient.react(channel.roomJid, messageId, nextEmojis)
                reactionDao.deleteForSender(messageId, senderId)
                nextEmojis.forEach { nextEmoji ->
                    reactionDao.upsert(
                        ReactionEntity(
                            messageId = messageId,
                            senderId = senderId,
                            emoji = nextEmoji,
                            createdAt = Instant.now().toString(),
                        ),
                    )
                }
            }
        }

        private suspend fun replayPending() {
            pendingOutboundDao.pending().forEach { pending ->
                xmppClient.sendGroupMessage(
                    ChatMessageDraft(
                        channelId = pending.channelId,
                        roomJid = pending.roomJid,
                        body = pending.body,
                        stanzaId = pending.id,
                    ),
                )
                messageDao.clearPending(pending.id)
                pendingOutboundDao.delete(pending.id)
            }
        }

        private fun startIncomingMessageCollection() {
            if (incomingMessagesJob?.isActive == true) {
                return
            }
            incomingMessagesJob =
                repositoryScope.launch {
                    xmppClient.incomingMessages.collect { message ->
                        runCatching {
                            cacheIncomingMessage(message)
                        }.onFailure { throwable ->
                            WaddleLog.error("Failed to cache incoming XMPP message.", throwable)
                        }
                    }
                }
        }

        private fun startIncomingDirectMessageCollection() {
            if (incomingDirectMessagesJob?.isActive == true) {
                return
            }
            incomingDirectMessagesJob =
                repositoryScope.launch {
                    xmppClient.incomingDirectMessages.collect { message ->
                        runCatching {
                            cacheIncomingDirectMessage(message)
                        }.onFailure { throwable ->
                            WaddleLog.error("Failed to cache incoming direct XMPP message.", throwable)
                        }
                    }
                }
        }

        private suspend fun cacheMessageHistoryPage(
            channel: ChannelEntity,
            history: List<XmppHistoryMessage>,
        ) {
            history
                .filter { message -> message.isTimelineMessage() }
                .forEach { message -> upsertRoomHistoryMessage(channel, message) }
            history
                .filterNot { message -> message.isTimelineMessage() }
                .filter { message -> message.chatState == null }
                .forEach { message -> applyMessageUpdate(message) }
        }

        private suspend fun upsertRoomHistoryMessage(
            channel: ChannelEntity,
            message: XmppHistoryMessage,
        ) {
            val existingByServerId =
                message.serverId?.let { serverId -> messageDao.getIdByServerId(serverId) }
            if (existingByServerId == null) {
                message.originStanzaId
                    ?.takeIf { it != message.id }
                    ?.takeIf { messageDao.existsById(it) }
                    ?.let { pendingLocalId -> messageDao.deleteById(pendingLocalId) }
            }
            messageDao.upsert(message.toEntity(channel, id = existingByServerId ?: message.id))
        }

        private suspend fun cacheDirectMessageHistoryPage(history: List<XmppDirectMessage>) {
            history
                .filter { message -> message.isDirectTimelineMessage() }
                .forEach { message -> upsertDirectHistoryMessage(message) }
            history
                .filterNot { message -> message.isDirectTimelineMessage() }
                .filter { message -> message.chatState == null }
                .forEach { message -> applyDirectMessageUpdate(message) }
            // Update the conversation preview for the most recent message of every peer
            // in this page. Historical/archived messages must not bump unread counts.
            history
                .asSequence()
                .filter { message -> message.isDirectTimelineMessage() }
                .groupBy(XmppDirectMessage::peerJid)
                .forEach { (_, group) ->
                    group.maxByOrNull { it.createdAt }?.let { latest ->
                        upsertConversationPreview(
                            peerJid = latest.peerJid,
                            body = latest.body,
                            createdAt = latest.createdAt,
                        )
                    }
                }
        }

        private suspend fun upsertConversationPreview(
            peerJid: String,
            body: String,
            createdAt: String,
        ) {
            val existing = dmConversationDao.getConversation(peerJid)
            val updated =
                DmConversationEntity(
                    peerJid = peerJid,
                    peerUsername = existing?.peerUsername ?: peerJid.substringBefore('@'),
                    peerAvatarUrl = existing?.peerAvatarUrl,
                    lastMessageBody = body.takeIf { it.isNotBlank() } ?: existing?.lastMessageBody,
                    lastMessageAt =
                        maxOfNullable(existing?.lastMessageAt, createdAt) ?: createdAt,
                    unreadCount = existing?.unreadCount ?: 0,
                )
            dmConversationDao.upsert(updated)
        }

        private fun maxOfNullable(
            left: String?,
            right: String?,
        ): String? =
            when {
                left == null -> right
                right == null -> left
                else -> if (left >= right) left else right
            }

        private suspend fun upsertDirectHistoryMessage(message: XmppDirectMessage) {
            val existingByServerId =
                message.serverId?.let { serverId -> dmMessageDao.getIdByServerId(serverId) }
            if (existingByServerId == null) {
                message.originStanzaId
                    ?.takeIf { it != message.id }
                    ?.takeIf { dmMessageDao.existsById(it) }
                    ?.let { pendingLocalId -> dmMessageDao.deleteById(pendingLocalId) }
            }
            dmMessageDao.upsert(message.toEntity(id = existingByServerId ?: message.id))
        }

        private suspend fun cacheIncomingMessage(message: XmppHistoryMessage) {
            val channel = channelDao.getChannelByRoomJid(message.roomJid)
            if (channel == null) {
                WaddleLog.info("Skipping incoming XMPP message for uncached room ${message.roomJid}.")
                return
            }
            if (!message.isTimelineMessage()) {
                applyMessageUpdate(message)
                return
            }
            val existingByServerId =
                message.serverId?.let { serverId -> messageDao.getIdByServerId(serverId) }
            if (existingByServerId == null) {
                message.originStanzaId
                    ?.takeIf { it != message.id }
                    ?.takeIf { messageDao.existsById(it) }
                    ?.let { pendingLocalId ->
                        messageDao.deleteById(pendingLocalId)
                        WaddleLog.info(
                            "Reconciled pending outbound $pendingLocalId with server id ${message.id}.",
                        )
                    }
            }
            messageDao.upsert(message.toEntity(channel, id = existingByServerId ?: message.id))
            WaddleLog.info("Cached live XMPP message ${message.id} for channel ${channel.id}.")
        }

        private suspend fun cacheIncomingDirectMessage(message: XmppDirectMessage) {
            if (!message.isDirectTimelineMessage()) {
                applyDirectMessageUpdate(message)
                return
            }
            val existingByServerId =
                message.serverId?.let { serverId -> dmMessageDao.getIdByServerId(serverId) }
            if (existingByServerId == null) {
                message.originStanzaId
                    ?.takeIf { it != message.id }
                    ?.takeIf { dmMessageDao.existsById(it) }
                    ?.let { pendingLocalId ->
                        dmMessageDao.deleteById(pendingLocalId)
                        WaddleLog.info(
                            "Reconciled pending outbound DM $pendingLocalId with server id ${message.id}.",
                        )
                    }
            }
            val entity = message.toEntity(id = existingByServerId ?: message.id)
            dmMessageDao.upsert(entity)
            upsertConversation(entity)
            WaddleLog.info("Cached live direct XMPP message ${message.id}.")
        }

        private suspend fun applyMessageUpdate(message: XmppHistoryMessage) {
            message.chatState?.let { chatState ->
                updateRoomTyping(
                    roomJid = message.roomJid,
                    sender = message.senderName ?: message.senderId ?: message.id,
                    chatState = chatState,
                )
                return
            }
            message.retractsId?.let { retractsId ->
                messageDao.markRetracted(retractsId)
                WaddleLog.info("Applied XMPP retraction for message $retractsId.")
                return
            }
            message.replacesId?.let { replacesId ->
                messageDao.markEdited(replacesId, message.body, message.createdAt)
                WaddleLog.info("Applied XMPP correction for message $replacesId.")
                return
            }
            message.reactionTargetId?.let { targetId ->
                val senderId = message.senderId ?: message.senderName ?: message.id
                reactionDao.deleteForSender(targetId, senderId)
                message.reactionEmojis.forEach { emoji ->
                    reactionDao.upsert(
                        ReactionEntity(
                            messageId = targetId,
                            senderId = senderId,
                            emoji = emoji,
                            createdAt = message.createdAt,
                        ),
                    )
                }
                WaddleLog.info("Applied XMPP reaction update for message $targetId.")
                return
            }
            message.displayedId?.let { displayedId ->
                deliveryStateDao.upsert(
                    DeliveryStateEntity(
                        messageId = displayedId,
                        recipientId = message.senderId ?: message.senderName ?: message.id,
                        deliveredAt = null,
                        displayedAt = message.createdAt,
                    ),
                )
                WaddleLog.info("Applied XMPP displayed marker for message $displayedId.")
            }
        }

        private suspend fun applyDirectMessageUpdate(message: XmppDirectMessage) {
            message.chatState?.let { chatState ->
                updateDirectTyping(message.peerJid, chatState)
                return
            }
            message.retractsId?.let { retractsId ->
                dmMessageDao.markRetracted(retractsId)
                WaddleLog.info("Applied direct XMPP retraction for message $retractsId.")
                return
            }
            message.replacesId?.let { replacesId ->
                dmMessageDao.markEdited(replacesId, message.body, message.createdAt)
                WaddleLog.info("Applied direct XMPP correction for message $replacesId.")
                return
            }
            message.reactionTargetId?.let { targetId ->
                val senderId = message.fromJid
                dmReactionDao.deleteForSender(targetId, senderId)
                message.reactionEmojis.forEach { emoji ->
                    dmReactionDao.upsert(
                        DmReactionEntity(
                            messageId = targetId,
                            senderId = senderId,
                            emoji = emoji,
                            createdAt = message.createdAt,
                        ),
                    )
                }
                WaddleLog.info("Applied direct XMPP reaction update for message $targetId.")
                return
            }
            message.displayedId?.let { displayedId ->
                deliveryStateDao.upsert(
                    DeliveryStateEntity(
                        messageId = displayedId,
                        recipientId = message.fromJid,
                        deliveredAt = null,
                        displayedAt = message.createdAt,
                    ),
                )
                WaddleLog.info("Applied direct XMPP displayed marker for message $displayedId.")
            }
        }

        private suspend fun upsertConversation(message: XmppDirectMessage) {
            upsertConversationRow(
                peerJid = message.peerJid,
                body = message.body,
                createdAt = message.createdAt,
                incoming = message.fromJid == message.peerJid,
            )
        }

        private suspend fun upsertConversation(message: DmMessageEntity) {
            upsertConversationRow(
                peerJid = message.peerJid,
                body = message.body,
                createdAt = message.createdAt,
                incoming = message.fromJid == message.peerJid,
            )
        }

        private suspend fun upsertConversationRow(
            peerJid: String,
            body: String,
            createdAt: String,
            incoming: Boolean,
        ) {
            val existing = dmConversationDao.getConversation(peerJid)
            val baseUnread = existing?.unreadCount ?: 0
            val nextUnread = if (incoming) baseUnread + 1 else baseUnread
            dmConversationDao.upsert(
                DmConversationEntity(
                    peerJid = peerJid,
                    peerUsername = existing?.peerUsername ?: peerJid.substringBefore('@'),
                    peerAvatarUrl = existing?.peerAvatarUrl,
                    lastMessageBody = body.takeIf { it.isNotBlank() } ?: existing?.lastMessageBody,
                    lastMessageAt = createdAt,
                    unreadCount = nextUnread,
                ),
            )
        }

        suspend fun markDmRead(peerJid: String) =
            withContext(Dispatchers.IO) {
                dmConversationDao.markRead(peerJid)
            }

        /** Look up a cached channel by its MUC room JID (used for deep-link routing). */
        suspend fun channelByRoomJid(roomJid: String): ChannelEntity? =
            withContext(Dispatchers.IO) { channelDao.getChannelByRoomJid(roomJid) }

        /**
         * Called from the notification-action receiver when the user types a reply
         * in the DM notification. We don't have an [AuthSession] in a broadcast
         * context so we resolve one via the session provider (which refreshes the
         * OAuth token if needed).
         */
        suspend fun sendDirectMessageFromNotification(
            peerJid: String,
            body: String,
        ) = withContext(Dispatchers.IO) {
            val session = sessionProvider.currentSession() ?: error("No active session for reply.")
            sendDirectMessage(session, peerJid, body)
        }

        suspend fun sendRoomMessageFromNotification(
            roomJid: String,
            body: String,
        ) = withContext(Dispatchers.IO) {
            val session = sessionProvider.currentSession() ?: error("No active session for reply.")
            val channel = channelDao.getChannelByRoomJid(roomJid) ?: error("Unknown room: $roomJid")
            sendMessage(session, channel.waddleId, channel.id, body)
        }

        private fun XmppHistoryMessage.toEntity(
            channel: ChannelEntity,
            id: String = this.id,
        ): MessageEntity =
            MessageEntity(
                id = id,
                serverId = serverId,
                waddleId = channel.waddleId,
                channelId = channel.id,
                roomJid = roomJid,
                senderId = senderId,
                senderName = senderName,
                body = body,
                createdAt = createdAt,
                editedAt = editedAt,
                replyToMessageId = replyToMessageId,
                mentions = mentions.joinToStringOrNull(),
                broadcastMention = broadcastMention,
                sharedFileUrl = sharedFile?.url,
                sharedFileName = sharedFile?.name,
                sharedFileMediaType = sharedFile?.mediaType,
                sharedFileSize = sharedFile?.size,
                sharedFileDescription = sharedFile?.description,
                sharedFileDisposition = sharedFile?.disposition,
                isSticker = isSticker,
                callInviteId = callInvite?.inviteId,
                callExternalUri = callInvite?.externalUri,
                callDescription = callInvite?.meetingDescription,
                callMuji = callInvite?.muji ?: false,
                retracted = false,
                pending = false,
            )

        private fun XmppDirectMessage.toEntity(id: String = this.id): DmMessageEntity =
            DmMessageEntity(
                id = id,
                serverId = serverId,
                peerJid = peerJid,
                fromJid = fromJid,
                senderName = senderName,
                body = body,
                createdAt = createdAt,
                editedAt = editedAt,
                mentions = mentions.joinToStringOrNull(),
                broadcastMention = broadcastMention,
                sharedFileUrl = sharedFile?.url,
                sharedFileName = sharedFile?.name,
                sharedFileMediaType = sharedFile?.mediaType,
                sharedFileSize = sharedFile?.size,
                sharedFileDescription = sharedFile?.description,
                sharedFileDisposition = sharedFile?.disposition,
                isSticker = isSticker,
                callInviteId = callInvite?.inviteId,
                callExternalUri = callInvite?.externalUri,
                callDescription = callInvite?.meetingDescription,
                callMuji = callInvite?.muji ?: false,
                retracted = false,
                pending = false,
            )

        private fun XmppHistoryMessage.isTimelineMessage(): Boolean =
            reactionTargetId == null &&
                retractsId == null &&
                replacesId == null &&
                displayedId == null &&
                chatState == null

        private fun XmppDirectMessage.isDirectTimelineMessage(): Boolean =
            retractsId == null &&
                replacesId == null &&
                reactionTargetId == null &&
                displayedId == null &&
                chatState == null

        private suspend fun uploadAttachment(attachment: OutboundFileAttachment): ChatFileAttachment {
            require(attachment.bytes.isNotEmpty()) { "Cannot upload an empty file." }
            val slot =
                requireNotNull(
                    xmppClient.uploadSlot(
                        filename = attachment.name,
                        contentType = attachment.mediaType,
                        sizeBytes = attachment.bytes.size.toLong(),
                    ),
                ) { "XEP-0363 upload is not available for this account." }
            api.uploadToSlot(
                putUrl = slot.putUrl,
                bytes = attachment.bytes,
                contentType = attachment.mediaType,
                uploadHeaders = slot.headers,
            )
            return ChatFileAttachment(
                url = slot.getUrl,
                name = attachment.name,
                mediaType = attachment.mediaType,
                size = attachment.bytes.size.toLong(),
            )
        }

        private fun ChatFileAttachment.messageBody(): String = description?.takeIf { it.isNotBlank() } ?: name ?: url

        private fun updateRoomTyping(
            roomJid: String,
            sender: String,
            chatState: ChatState,
        ) {
            val key = RoomTypingKey(roomJid, sender)
            synchronized(roomTypingTimeouts) {
                roomTypingTimeouts.remove(key)?.cancel()
            }
            mutableRoomTyping.update { current ->
                val next = current.toMutableMap()
                val typers = next[roomJid].orEmpty().toMutableSet()
                if (chatState == ChatState.Composing) {
                    typers.add(sender)
                } else {
                    typers.remove(sender)
                }
                if (typers.isEmpty()) {
                    next.remove(roomJid)
                } else {
                    next[roomJid] = typers
                }
                next
            }
            if (chatState == ChatState.Composing) {
                val timeoutJob =
                    repositoryScope.launch {
                        delay(TYPING_TIMEOUT_MILLIS)
                        clearRoomTyper(key)
                    }
                synchronized(roomTypingTimeouts) {
                    roomTypingTimeouts[key] = timeoutJob
                }
            }
        }

        private fun clearRoomTyper(key: RoomTypingKey) {
            synchronized(roomTypingTimeouts) {
                roomTypingTimeouts.remove(key)
            }
            mutableRoomTyping.update { current ->
                val typers = current[key.roomJid]?.toMutableSet() ?: return@update current
                if (!typers.remove(key.sender)) {
                    return@update current
                }
                val next = current.toMutableMap()
                if (typers.isEmpty()) {
                    next.remove(key.roomJid)
                } else {
                    next[key.roomJid] = typers
                }
                next
            }
        }

        private fun updateDirectTyping(
            peerJid: String,
            chatState: ChatState,
        ) {
            synchronized(directTypingTimeouts) {
                directTypingTimeouts.remove(peerJid)?.cancel()
            }
            mutableDirectTyping.update { current ->
                val next = current.toMutableMap()
                if (chatState == ChatState.Composing) {
                    next[peerJid] = true
                } else {
                    next.remove(peerJid)
                }
                next
            }
            if (chatState == ChatState.Composing) {
                val timeoutJob =
                    repositoryScope.launch {
                        delay(TYPING_TIMEOUT_MILLIS)
                        clearDirectTyping(peerJid)
                    }
                synchronized(directTypingTimeouts) {
                    directTypingTimeouts[peerJid] = timeoutJob
                }
            }
        }

        private fun clearDirectTyping(peerJid: String) {
            synchronized(directTypingTimeouts) {
                directTypingTimeouts.remove(peerJid)
            }
            mutableDirectTyping.update { current ->
                if (current[peerJid] != true) {
                    return@update current
                }
                current.toMutableMap().apply { remove(peerJid) }
            }
        }

        private data class RoomTypingKey(
            val roomJid: String,
            val sender: String,
        )

        private fun pageHasMore(
            pageComplete: Boolean,
            cursorId: String?,
            pageSize: Int,
            loadedPages: Int,
        ): Boolean =
            !pageComplete &&
                !cursorId.isNullOrBlank() &&
                pageSize > 0 &&
                loadedPages < MAX_MAM_PAGES

        private fun List<String>.joinToStringOrNull(): String? = takeIf { it.isNotEmpty() }?.joinToString(separator = "\n")

        private fun extractBodyMentions(body: String): List<String> =
            MENTION_PATTERN
                .findAll(body)
                .mapNotNull { match -> match.groups[1]?.value?.takeIf { it.isNotBlank() } }
                .map { it.removePrefix("@") }
                .toList()

        private fun extractBroadcastMention(body: String): String? =
            when {
                EVERYONE_PATTERN.containsMatchIn(body) -> "everyone"
                HERE_PATTERN.containsMatchIn(body) -> "here"
                else -> null
            }

        private fun isWebUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")

        private companion object {
            const val MAX_MAM_PAGES = 20
            const val TYPING_TIMEOUT_MILLIS = 10_000L
            val MENTION_PATTERN = Regex("(?:^|\\s)@(\\S+)")
            val EVERYONE_PATTERN = Regex("(?:^|\\s)@everyone(?:\\s|$)", RegexOption.IGNORE_CASE)
            val HERE_PATTERN = Regex("(?:^|\\s)@here(?:\\s|$)", RegexOption.IGNORE_CASE)
        }
    }
