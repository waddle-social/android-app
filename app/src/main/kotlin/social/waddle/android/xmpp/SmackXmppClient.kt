package social.waddle.android.xmpp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.SASLAuthentication
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.c2s.ModularXmppClientToServerConnection
import org.jivesoftware.smack.c2s.ModularXmppClientToServerConnectionConfiguration
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.StringUtils
import org.jivesoftware.smack.websocket.XmppWebSocketTransportModuleDescriptor
import org.jivesoftware.smack.websocket.okhttp.OkHttpWebSocketFactory
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.mam.element.MamElements
import org.jivesoftware.smackx.mam.element.MamFinIQ
import org.jivesoftware.smackx.mam.provider.MamFinIQProvider
import org.jivesoftware.smackx.mam.provider.MamResultProvider
import org.jivesoftware.smackx.muc.packet.MUCInitialPresence
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import social.waddle.android.BuildConfig
import social.waddle.android.data.model.ChatFileAttachment
import social.waddle.android.data.model.ChatMessageDraft
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.model.XepFeature
import social.waddle.android.util.WaddleLog
import java.net.URI
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LargeClass")
class SmackXmppClient
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : XmppClient {
        private val mutableConnectionState = MutableStateFlow<XmppConnectionState>(XmppConnectionState.Disconnected)
        private val mutableIncomingMessages = MutableSharedFlow<XmppHistoryMessage>(extraBufferCapacity = INCOMING_BUFFER_SIZE)
        private val mutableIncomingDirectMessages = MutableSharedFlow<XmppDirectMessage>(extraBufferCapacity = INCOMING_BUFFER_SIZE)
        private val roomJoinMutex = Mutex()
        private val joinedRoomJids = Collections.synchronizedSet(mutableSetOf<String>())
        private var connection: AbstractXMPPConnection? = null
        private var mucNickname: Resourcepart? = null
        private var accountUserId: String? = null
        private var accountUsername: String? = null
        private var accountBareJid: String? = null

        override val connectionState: StateFlow<XmppConnectionState> = mutableConnectionState.asStateFlow()
        override val incomingMessages: Flow<XmppHistoryMessage> = mutableIncomingMessages.asSharedFlow()
        override val incomingDirectMessages: Flow<XmppDirectMessage> = mutableIncomingDirectMessages.asSharedFlow()
        override val supportedFeatures: List<XepFeature> = XmppFeatureRegistry.supported

        override suspend fun connect(
            session: StoredSession,
            environment: WaddleEnvironment,
        ) {
            withContext(Dispatchers.IO) {
                mutableConnectionState.value = XmppConnectionState.Connecting
                runCatching {
                    AndroidSmackInitializer.initialize(context)
                    registerMamProviders()
                    registerOAuthBearer()
                    val nextNickname = nicknameFor(session)
                    buildConnection(session, environment).also { next ->
                        mucNickname = nextNickname
                        accountUserId = session.userId
                        accountUsername = session.username
                        accountBareJid = session.jid
                        joinedRoomJids.clear()
                        configureConnection(next)
                        next.connect()
                        next.login()
                        connection = next
                        mutableConnectionState.value = XmppConnectionState.Connected(session.jid)
                    }
                }.onFailure { throwable ->
                    WaddleLog.error("XMPP connection failed.", throwable)
                    mutableConnectionState.value = XmppConnectionState.Failed(throwable.message ?: throwable::class.java.simpleName)
                    throw throwable
                }
            }
        }

        override suspend fun disconnect() {
            withContext(Dispatchers.IO) {
                connection?.let { activeConnection ->
                    ReconnectionManager.getInstanceFor(activeConnection).disableAutomaticReconnection()
                    activeConnection.disconnect()
                }
                connection = null
                mucNickname = null
                accountUserId = null
                accountUsername = null
                accountBareJid = null
                joinedRoomJids.clear()
                mutableConnectionState.value = XmppConnectionState.Disconnected
            }
        }

        override suspend fun discoverWaddles(session: StoredSession): List<XmppWaddleRef> =
            withConnection { activeConnection ->
                val domain = JidCreate.domainBareFrom("spaces.${session.xmppDomain}")
                ServiceDiscoveryManager
                    .getInstanceFor(activeConnection)
                    .discoverItems(domain)
                    .items
                    .map { item ->
                        XmppWaddleRef(
                            id = item.node ?: item.entityID.toString(),
                            name = item.name ?: item.node ?: item.entityID.toString(),
                        )
                    }
            }

        override suspend fun discoverChannels(
            session: StoredSession,
            waddleId: String,
        ): List<XmppChannelRef> =
            withConnection { activeConnection ->
                val domain = JidCreate.domainBareFrom("spaces.${session.xmppDomain}")
                ServiceDiscoveryManager
                    .getInstanceFor(activeConnection)
                    .discoverItems(domain, waddleId)
                    .items
                    .map { item ->
                        val roomJid = item.entityID.toString()
                        val roomLocalpart = roomJid.substringBefore('@')
                        XmppChannelRef(
                            id = roomLocalpart.removePrefix("${waddleId}_"),
                            name = item.name ?: roomLocalpart.removePrefix("${waddleId}_"),
                            roomJid = roomJid,
                        )
                    }
            }

        override suspend fun sendGroupMessage(draft: ChatMessageDraft): String =
            withConnection { activeConnection ->
                ensureJoined(activeConnection, draft.roomJid)
                val stanzaId = nextStanzaId()
                val builder =
                    StanzaBuilder
                        .buildMessage(draft.stanzaId ?: stanzaId)
                        .to(JidCreate.entityBareFrom(draft.roomJid))
                        .ofType(Message.Type.groupchat)
                        .setBody(draft.body)
                        .addExtension(XmppExtensions.markable())
                        .addExtension(XmppExtensions.storeHint())
                DeliveryReceiptRequest.addTo(builder)
                draft.replyToMessageId?.let { builder.addExtension(XmppExtensions.reply(it)) }
                draft.correctionMessageId?.let { builder.addExtension(XmppExtensions.correction(it)) }
                mentionReferences(draft.body, draft.mentions).forEach { mention ->
                    builder.addExtension(XmppExtensions.referenceMention(mention.uri, mention.begin, mention.end))
                }
                explicitMentionTypes(draft.body).takeIf { it.isNotEmpty() }?.let { mentionTypes ->
                    builder.addExtension(XmppExtensions.explicitMentions(mentionTypes))
                }
                draft.sharedFile?.let { sharedFile ->
                    builder.addExtension(sharedFile.toFileSharingExtension())
                }
                val message = builder.build()
                activeConnection.sendStanza(message)
                message.stanzaId
            }

        override suspend fun loadMessageHistory(
            roomJid: String,
            afterId: String?,
            beforeId: String?,
        ): XmppMessagePage<XmppHistoryMessage> =
            queryRoomHistory(
                roomJid = roomJid,
                afterId = afterId,
                beforeId = beforeId,
                fullText = null,
                maxResults = MAM_PAGE_SIZE,
            )

        override suspend fun searchMessageHistory(
            roomJid: String,
            query: String,
        ): List<XmppHistoryMessage> {
            val fullText = query.trim()
            if (fullText.isBlank()) {
                return emptyList()
            }
            return queryRoomHistory(
                roomJid = roomJid,
                afterId = null,
                beforeId = null,
                fullText = fullText,
                maxResults = MAM_SEARCH_PAGE_SIZE,
            ).messages
        }

        private suspend fun queryRoomHistory(
            roomJid: String,
            afterId: String?,
            beforeId: String?,
            fullText: String?,
            maxResults: Int,
        ): XmppMessagePage<XmppHistoryMessage> =
            withConnection { activeConnection ->
                ensureJoined(activeConnection, roomJid)
                val room = JidCreate.entityBareFrom(roomJid)
                val queryId = StringUtils.secureUniqueRandomString()
                val iqId = StringUtils.secureUniqueRandomString()
                val query =
                    MamHistoryQueryIq(
                        queryId = queryId,
                        maxResults = maxResults,
                        afterId = afterId,
                        beforeId = beforeId,
                        fullText = fullText,
                    ).apply {
                        stanzaId = iqId
                        type = IQ.Type.set
                        to = room
                    }
                val history = mutableListOf<XmppHistoryMessage>()
                var fin: MamFinIQ? = null
                val collector =
                    activeConnection.createStanzaCollector(
                        StanzaCollector
                            .newConfiguration()
                            .setStanzaFilter(
                                MamHistoryFilter(
                                    queryId = queryId,
                                    iqId = iqId,
                                ),
                            ).setSize(MAM_COLLECTOR_SIZE),
                    )
                try {
                    activeConnection.sendStanza(query)
                    var finished = false
                    while (!finished) {
                        val stanza: Stanza =
                            collector.nextResult(MAM_QUERY_TIMEOUT_MILLIS)
                                ?: error("Timed out waiting for MAM response from $roomJid.")
                        when (stanza) {
                            is Message -> {
                                MamElements.MamResultExtension.from(stanza)?.let { result ->
                                    historyMessageFromMamResult(result, roomJid)?.let(history::add)
                                }
                            }

                            is MamFinIQ -> {
                                fin = stanza
                                finished = true
                            }

                            is IQ -> {
                                if (stanza.type == IQ.Type.error) {
                                    error("MAM query failed for $roomJid: ${stanza.error}")
                                }
                                finished = true
                            }
                        }
                    }
                } finally {
                    collector.cancel()
                }

                val messages =
                    history
                        .distinctBy { it.id }
                        .sortedBy { it.createdAt }
                WaddleLog.info("Loaded ${messages.size} MAM messages from $roomJid.")
                XmppMessagePage(
                    messages = messages,
                    firstId = fin?.getRSMSet()?.first ?: messages.firstOrNull()?.serverId ?: messages.firstOrNull()?.id,
                    lastId = fin?.getRSMSet()?.last ?: messages.lastOrNull()?.serverId ?: messages.lastOrNull()?.id,
                    complete = fin?.isComplete ?: (messages.size < maxResults),
                )
            }

        override suspend fun loadDirectMessageHistory(
            ownBareJid: String,
            peerJid: String,
            afterId: String?,
            beforeId: String?,
        ): XmppMessagePage<XmppDirectMessage> =
            queryDirectHistory(
                ownBareJid = ownBareJid,
                peerJid = peerJid,
                afterId = afterId,
                beforeId = beforeId,
                fullText = null,
                maxResults = MAM_PAGE_SIZE,
            )

        override suspend fun searchDirectMessageHistory(
            ownBareJid: String,
            peerJid: String,
            query: String,
        ): List<XmppDirectMessage> {
            val fullText = query.trim()
            if (fullText.isBlank()) {
                return emptyList()
            }
            return queryDirectHistory(
                ownBareJid = ownBareJid,
                peerJid = peerJid,
                afterId = null,
                beforeId = null,
                fullText = fullText,
                maxResults = MAM_SEARCH_PAGE_SIZE,
            ).messages
        }

        private suspend fun queryDirectHistory(
            ownBareJid: String,
            peerJid: String,
            afterId: String?,
            beforeId: String?,
            fullText: String?,
            maxResults: Int,
        ): XmppMessagePage<XmppDirectMessage> =
            withConnection { activeConnection ->
                val queryId = StringUtils.secureUniqueRandomString()
                val iqId = StringUtils.secureUniqueRandomString()
                val query =
                    MamDirectHistoryQueryIq(
                        queryId = queryId,
                        maxResults = maxResults,
                        peerJid = peerJid,
                        afterId = afterId,
                        beforeId = beforeId,
                        fullText = fullText,
                    ).apply {
                        stanzaId = iqId
                        type = IQ.Type.set
                        to = JidCreate.entityBareFrom(ownBareJid)
                    }
                val history = mutableListOf<XmppDirectMessage>()
                var fin: MamFinIQ? = null
                val collector =
                    activeConnection.createStanzaCollector(
                        StanzaCollector
                            .newConfiguration()
                            .setStanzaFilter(MamHistoryFilter(queryId = queryId, iqId = iqId))
                            .setSize(MAM_COLLECTOR_SIZE),
                    )
                try {
                    activeConnection.sendStanza(query)
                    var finished = false
                    while (!finished) {
                        val stanza: Stanza =
                            collector.nextResult(MAM_QUERY_TIMEOUT_MILLIS)
                                ?: error("Timed out waiting for DM MAM response for $peerJid.")
                        when (stanza) {
                            is Message -> {
                                MamElements.MamResultExtension.from(stanza)?.let { result ->
                                    directMessageFromMamResult(result, ownBareJid, peerJid)?.let(history::add)
                                }
                            }

                            is MamFinIQ -> {
                                fin = stanza
                                finished = true
                            }

                            is IQ -> {
                                if (stanza.type == IQ.Type.error) {
                                    error("DM MAM query failed for $peerJid: ${stanza.error}")
                                }
                                finished = true
                            }
                        }
                    }
                } finally {
                    collector.cancel()
                }
                val messages = history.distinctBy { it.id }.sortedBy { it.createdAt }
                XmppMessagePage(
                    messages = messages,
                    firstId = fin?.getRSMSet()?.first ?: messages.firstOrNull()?.serverId ?: messages.firstOrNull()?.id,
                    lastId = fin?.getRSMSet()?.last ?: messages.lastOrNull()?.serverId ?: messages.lastOrNull()?.id,
                    complete = fin?.isComplete ?: (messages.size < maxResults),
                )
            }

        override suspend fun sendDirectMessage(
            peerJid: String,
            body: String,
            stanzaId: String?,
            sharedFile: ChatFileAttachment?,
        ): String =
            withConnection { activeConnection ->
                val messageId = stanzaId ?: nextStanzaId()
                val builder =
                    StanzaBuilder
                        .buildMessage(messageId)
                        .to(JidCreate.entityBareFrom(peerJid))
                        .ofType(Message.Type.chat)
                        .setBody(body)
                        .addExtension(XmppExtensions.markable())
                        .addExtension(XmppExtensions.storeHint())
                DeliveryReceiptRequest.addTo(builder)
                sharedFile?.let { builder.addExtension(it.toFileSharingExtension()) }
                val message = builder.build()
                activeConnection.sendStanza(message)
                message.stanzaId
            }

        override suspend fun markDirectDisplayed(
            peerJid: String,
            messageId: String,
        ) {
            sendDirectMarker(peerJid, XmppExtensions.displayed(messageId), store = false)
        }

        override suspend fun setDirectChatState(
            peerJid: String,
            state: ChatState,
        ) {
            val element =
                org.jivesoftware.smackx.chatstates.packet.ChatStateExtension(
                    org.jivesoftware.smackx.chatstates.ChatState
                        .valueOf(state.name.lowercase()),
                )
            sendDirectMarker(peerJid, element, store = false)
        }

        override suspend fun correctDirectMessage(
            peerJid: String,
            messageId: String,
            replacement: String,
        ) {
            withConnection { activeConnection ->
                val message =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(peerJid))
                        .ofType(Message.Type.chat)
                        .setBody(replacement)
                        .addExtension(XmppExtensions.correction(messageId))
                        .addExtension(XmppExtensions.storeHint())
                        .build()
                activeConnection.sendStanza(message)
            }
        }

        override suspend fun retractDirectMessage(
            peerJid: String,
            messageId: String,
        ) {
            sendDirectMarker(peerJid, XmppExtensions.retract(messageId), store = true)
        }

        override suspend fun reactDirect(
            peerJid: String,
            messageId: String,
            emojis: List<String>,
        ) {
            sendDirectMarker(peerJid, XmppExtensions.reaction(messageId, emojis), store = true)
        }

        override suspend fun markDisplayed(
            roomJid: String,
            messageId: String,
        ) {
            sendMarker(roomJid, XmppExtensions.displayed(messageId), store = false)
        }

        override suspend fun setChatState(
            roomJid: String,
            state: ChatState,
        ) {
            val element =
                org.jivesoftware.smackx.chatstates.packet.ChatStateExtension(
                    org.jivesoftware.smackx.chatstates.ChatState
                        .valueOf(state.name.lowercase()),
                )
            sendMarker(roomJid, element, store = false)
        }

        override suspend fun correctMessage(
            roomJid: String,
            messageId: String,
            replacement: String,
        ) {
            withConnection { activeConnection ->
                ensureJoined(activeConnection, roomJid)
                val message =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(roomJid))
                        .ofType(Message.Type.groupchat)
                        .setBody(replacement)
                        .addExtension(XmppExtensions.correction(messageId))
                        .addExtension(XmppExtensions.storeHint())
                        .build()
                activeConnection.sendStanza(message)
            }
        }

        override suspend fun retractMessage(
            roomJid: String,
            messageId: String,
        ) {
            sendMarker(roomJid, XmppExtensions.retract(messageId), store = true)
        }

        override suspend fun react(
            roomJid: String,
            messageId: String,
            emojis: List<String>,
        ) {
            sendMarker(roomJid, XmppExtensions.reaction(messageId, emojis), store = true)
        }

        override suspend fun uploadSlot(
            filename: String,
            contentType: String,
            sizeBytes: Long,
        ): UploadSlot? =
            withConnection { activeConnection ->
                val manager = HttpFileUploadManager.getInstanceFor(activeConnection)
                if (!manager.isUploadServiceDiscovered) {
                    manager.discoverUploadService()
                }
                val slot = manager.requestSlot(filename, sizeBytes, contentType)
                UploadSlot(
                    putUrl = slot.putUrl.toString(),
                    getUrl = slot.getUrl.toString(),
                    headers = slot.headers,
                )
            }

        private suspend fun sendMarker(
            roomJid: String,
            extension: org.jivesoftware.smack.packet.XmlElement,
            store: Boolean,
        ) {
            withConnection { activeConnection ->
                ensureJoined(activeConnection, roomJid)
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(roomJid))
                        .ofType(Message.Type.groupchat)
                        .addExtension(extension)
                builder.addExtension(if (store) XmppExtensions.storeHint() else XmppExtensions.noStoreHint())
                activeConnection.sendStanza(builder.build())
            }
        }

        private suspend fun sendDirectMarker(
            peerJid: String,
            extension: org.jivesoftware.smack.packet.XmlElement,
            store: Boolean,
        ) {
            withConnection { activeConnection ->
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(peerJid))
                        .ofType(Message.Type.chat)
                        .addExtension(extension)
                builder.addExtension(if (store) XmppExtensions.storeHint() else XmppExtensions.noStoreHint())
                activeConnection.sendStanza(builder.build())
            }
        }

        private suspend fun <T> withConnection(block: suspend (AbstractXMPPConnection) -> T): T =
            withContext(Dispatchers.IO) {
                val activeConnection = requireNotNull(connection) { "XMPP is not connected." }
                block(activeConnection)
            }

        private suspend fun ensureJoined(
            activeConnection: AbstractXMPPConnection,
            roomJid: String,
        ) {
            roomJoinMutex.withLock {
                if (!joinedRoomJids.contains(roomJid)) {
                    sendJoinPresence(activeConnection, roomJid)
                    joinedRoomJids.add(roomJid)
                    WaddleLog.info("Joined XMPP room $roomJid as ${requireNotNull(mucNickname)}.")
                }
            }
        }

        private fun configureConnection(activeConnection: AbstractXMPPConnection) {
            registerIncomingMessageListener(activeConnection)
            registerIncomingDirectMessageListener(activeConnection)
            ReconnectionManager
                .getInstanceFor(activeConnection)
                .apply {
                    setFixedDelay(RECONNECT_DELAY_SECONDS)
                    enableAutomaticReconnection()
                }
            activeConnection.addConnectionListener(
                object : ConnectionListener {
                    override fun authenticated(
                        connection: XMPPConnection,
                        resumed: Boolean,
                    ) {
                        mutableConnectionState.value = XmppConnectionState.Connected(connection.user.toString())
                        rejoinRooms(activeConnection)
                    }

                    override fun connectionClosedOnError(exception: Exception) {
                        WaddleLog.error("XMPP connection closed on error.", exception)
                        mutableConnectionState.value =
                            XmppConnectionState.Failed(exception.message ?: exception::class.java.simpleName)
                    }

                    override fun connectionClosed() {
                        mutableConnectionState.value = XmppConnectionState.Disconnected
                    }
                },
            )
        }

        private fun registerIncomingMessageListener(activeConnection: AbstractXMPPConnection) {
            activeConnection.addAsyncStanzaListener(
                StanzaListener { stanza ->
                    val message = stanza as? Message
                    val roomJid =
                        message
                            ?.from
                            ?.toString()
                            ?.substringBefore('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: return@StanzaListener
                    liveMessageFrom(message, roomJid)?.let { incoming ->
                        if (!mutableIncomingMessages.tryEmit(incoming)) {
                            WaddleLog.info("Dropped incoming XMPP message for $roomJid because the buffer is full.")
                        } else {
                            WaddleLog.info("Received live XMPP message ${incoming.id} in $roomJid.")
                        }
                    }
                },
                StanzaFilter { stanza -> stanza is Message && stanza.type == Message.Type.groupchat },
            )
        }

        private fun registerIncomingDirectMessageListener(activeConnection: AbstractXMPPConnection) {
            activeConnection.addAsyncStanzaListener(
                StanzaListener { stanza ->
                    val message = stanza as? Message ?: return@StanzaListener
                    val ownBareJid = accountBareJid ?: return@StanzaListener
                    liveDirectMessageFrom(message, ownBareJid)?.let { incoming ->
                        if (!mutableIncomingDirectMessages.tryEmit(incoming)) {
                            WaddleLog.info("Dropped incoming direct XMPP message because the buffer is full.")
                        } else {
                            WaddleLog.info("Received live direct XMPP message ${incoming.id}.")
                        }
                    }
                },
                StanzaFilter { stanza -> stanza is Message && stanza.type == Message.Type.chat },
            )
        }

        private fun rejoinRooms(activeConnection: AbstractXMPPConnection) {
            val rooms =
                synchronized(joinedRoomJids) {
                    joinedRoomJids.toList()
                }
            if (rooms.isEmpty()) {
                return
            }
            joinedRoomJids.clear()
            rooms.forEach { roomJid ->
                runCatching {
                    sendJoinPresence(activeConnection, roomJid)
                    joinedRoomJids.add(roomJid)
                    WaddleLog.info("Rejoined XMPP room $roomJid as ${requireNotNull(mucNickname)} after reconnect.")
                }.onFailure { throwable ->
                    WaddleLog.error("Failed to rejoin XMPP room $roomJid after reconnect.", throwable)
                }
            }
        }

        private fun sendJoinPresence(
            activeConnection: AbstractXMPPConnection,
            roomJid: String,
        ) {
            activeConnection.sendStanza(
                StanzaBuilder
                    .buildPresence(nextStanzaId())
                    .to(JidCreate.entityFullFrom("$roomJid/${requireNotNull(mucNickname)}"))
                    .ofType(Presence.Type.available)
                    .addExtension(MUCInitialPresence(null, 0, -1, -1, null))
                    .build(),
            )
        }

        private fun liveMessageFrom(
            message: Message,
            roomJid: String,
        ): XmppHistoryMessage? = messageMapper().liveRoomMessage(message, roomJid)

        private fun historyMessageFromMamResult(
            result: MamElements.MamResultExtension,
            roomJid: String,
        ): XmppHistoryMessage? = messageMapper().roomMessageFromMam(result, roomJid)

        private fun liveDirectMessageFrom(
            message: Message,
            ownBareJid: String,
        ): XmppDirectMessage? = messageMapper().liveDirectMessage(message, ownBareJid)

        private fun directMessageFromMamResult(
            result: MamElements.MamResultExtension,
            ownBareJid: String,
            peerJid: String,
        ): XmppDirectMessage? = messageMapper().directMessageFromMam(result, ownBareJid, peerJid)

        private fun messageMapper(): XmppMessageMapper =
            XmppMessageMapper(
                accountUserId = accountUserId,
                accountUsername = accountUsername,
                mucNickname = mucNickname?.toString(),
            )

        private fun buildConnection(
            session: StoredSession,
            environment: WaddleEnvironment,
        ): AbstractXMPPConnection {
            val webSocketUrl = session.xmppWebSocketUrl?.takeIf { it.isNotBlank() }
            return if (webSocketUrl != null) {
                buildWebSocketConnection(session, environment, webSocketUrl)
            } else {
                buildTcpConnection(session, environment)
            }
        }

        private fun buildWebSocketConnection(
            session: StoredSession,
            environment: WaddleEnvironment,
            webSocketUrl: String,
        ): AbstractXMPPConnection {
            val builder =
                ModularXmppClientToServerConnectionConfiguration
                    .builder()
                    .removeAllModules()
                    .setXmppDomain(JidCreate.domainBareFrom(session.xmppDomain))
                    .setUsernameAndPassword(session.xmppLocalpart, session.xmppBearerToken)
                    .addEnabledSaslMechanism(OAuthBearerMechanism.NAME)
                    .setSecurityMode(securityMode(environment))
                    .setSendPresence(true)
            if (BuildConfig.DEBUG) {
                builder.enableDefaultDebugger()
            }
            builder
                .with(XmppWebSocketTransportModuleDescriptor.Builder::class.java)
                .explicitlySetWebSocketEndpointAndDiscovery(URI(webSocketUrl), false)
                .setWebSocketFactory(OkHttpWebSocketFactory.INSTANCE)
                .buildModule()
            return ModularXmppClientToServerConnection(builder.build())
        }

        private fun buildTcpConnection(
            session: StoredSession,
            environment: WaddleEnvironment,
        ): AbstractXMPPConnection {
            val configuration =
                XMPPTCPConnectionConfiguration
                    .builder()
                    .setXmppDomain(JidCreate.domainBareFrom(session.xmppDomain))
                    .setHost(environment.xmppHost)
                    .setPort(environment.xmppPort)
                    .setUsernameAndPassword(session.xmppLocalpart, session.xmppBearerToken)
                    .addEnabledSaslMechanism(OAuthBearerMechanism.NAME)
                    .setSecurityMode(securityMode(environment))
                    .setSendPresence(true)
                    .apply {
                        if (BuildConfig.DEBUG) {
                            enableDefaultDebugger()
                        }
                    }.build()
            return XMPPTCPConnection(configuration)
        }

        private fun securityMode(environment: WaddleEnvironment): ConnectionConfiguration.SecurityMode =
            if (environment.requireTls) {
                ConnectionConfiguration.SecurityMode.required
            } else {
                ConnectionConfiguration.SecurityMode.ifpossible
            }

        private fun registerOAuthBearer() {
            if (!SASLAuthentication.isSaslMechanismRegistered(OAuthBearerMechanism.NAME)) {
                SASLAuthentication.registerSASLMechanism(OAuthBearerMechanism())
            }
        }

        private fun registerMamProviders() {
            listOf(MAM_NAMESPACE, MAM_V1_NAMESPACE).forEach { namespace ->
                ProviderManager.addIQProvider(MAM_FIN_ELEMENT, namespace, MamFinIQProvider())
                ProviderManager.addExtensionProvider(MAM_RESULT_ELEMENT, namespace, MamResultProvider())
            }
        }

        private fun ChatFileAttachment.toFileSharingExtension(): org.jivesoftware.smack.packet.XmlElement =
            XmppExtensions.fileSharing(
                url = url,
                name = name,
                mediaType = mediaType,
                size = size,
                description = description,
                disposition = disposition,
            )

        private fun nicknameFor(session: StoredSession): Resourcepart {
            val preferredNickname = session.username.ifBlank { session.xmppLocalpart }
            val fallbackNickname = session.xmppLocalpart.ifBlank { DEFAULT_MUC_NICKNAME }
            val suffix =
                session.sessionId
                    .filter(Char::isLetterOrDigit)
                    .take(NICKNAME_SUFFIX_LENGTH)
                    .lowercase()
            val deviceNickname =
                listOf(preferredNickname, fallbackNickname)
                    .firstOrNull { it.isNotBlank() }
                    ?.let { base ->
                        if (suffix.isBlank()) {
                            "$base-$ANDROID_MUC_NICKNAME_SEGMENT"
                        } else {
                            "$base-$ANDROID_MUC_NICKNAME_SEGMENT-$suffix"
                        }
                    }
            return Resourcepart.fromOrNull(deviceNickname)
                ?: Resourcepart.fromOrNull("$fallbackNickname-$ANDROID_MUC_NICKNAME_SEGMENT")
                ?: Resourcepart.fromOrNull(preferredNickname)
                ?: Resourcepart.fromOrNull(fallbackNickname)
                ?: Resourcepart.fromOrThrowUnchecked(DEFAULT_MUC_NICKNAME)
        }

        private data class MentionReference(
            val uri: String,
            val begin: Int,
            val end: Int,
        )

        private fun mentionReferences(
            body: String,
            explicitMentions: List<String>,
        ): List<MentionReference> {
            val fromBody =
                MENTION_PATTERN
                    .findAll(body)
                    .mapNotNull { match ->
                        val nick = match.groups[1]?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val begin = match.range.last - nick.length
                        MentionReference(
                            uri = "xmpp:$nick",
                            begin = begin,
                            end = begin + nick.length + 1,
                        )
                    }.toList()
            val fromDraft =
                explicitMentions.mapIndexed { index, mention ->
                    MentionReference(
                        uri = mention,
                        begin = index,
                        end = index,
                    )
                }
            return (fromBody + fromDraft).distinctBy { "${it.uri}:${it.begin}:${it.end}" }
        }

        private fun explicitMentionTypes(body: String): List<String> =
            buildList {
                if (EVERYONE_PATTERN.containsMatchIn(body)) {
                    add("everyone")
                }
                if (HERE_PATTERN.containsMatchIn(body)) {
                    add("here")
                }
            }

        private fun nextStanzaId(): String = StringUtils.secureUniqueRandomString()

        private companion object {
            const val DEFAULT_MUC_NICKNAME = "waddle"
            const val ANDROID_MUC_NICKNAME_SEGMENT = "android"
            const val MAM_FIN_ELEMENT = "fin"
            const val MAM_RESULT_ELEMENT = "result"
            const val MAM_V1_NAMESPACE = "urn:xmpp:mam:1"
            const val INCOMING_BUFFER_SIZE = 64
            const val MAM_COLLECTOR_SIZE = 128
            const val MAM_PAGE_SIZE = 100
            const val MAM_SEARCH_PAGE_SIZE = 50
            const val MAM_QUERY_TIMEOUT_MILLIS = 15_000L
            const val NICKNAME_SUFFIX_LENGTH = 8
            const val RECONNECT_DELAY_SECONDS = 5
            val MENTION_PATTERN = Regex("(?:^|\\s)@(\\S+)")
            val EVERYONE_PATTERN = Regex("(?:^|\\s)@everyone(?:\\s|$)", RegexOption.IGNORE_CASE)
            val HERE_PATTERN = Regex("(?:^|\\s)@here(?:\\s|$)", RegexOption.IGNORE_CASE)
        }
    }
