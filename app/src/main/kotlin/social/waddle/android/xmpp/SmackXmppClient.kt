package social.waddle.android.xmpp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.XmlElement
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.sm.StreamManagementModule
import org.jivesoftware.smack.sm.StreamManagementModuleDescriptor
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.StringUtils
import org.jivesoftware.smack.websocket.XmppWebSocketTransportModuleDescriptor
import org.jivesoftware.smack.websocket.okhttp.OkHttpWebSocketFactory
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
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
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
@Suppress("LargeClass")
class SmackXmppClient
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        // Provider<> breaks the AuthRepository ↔ SmackXmppClient construction cycle.
        // We only need the session when actually reconnecting, so lazy resolution
        // is safe and natural.
        private val sessionProvider: Provider<SessionProvider>,
    ) : XmppClient {
        private val mutableConnectionState = MutableStateFlow<XmppConnectionState>(XmppConnectionState.Disconnected)
        private val mutableIncomingMessages = MutableSharedFlow<XmppHistoryMessage>(extraBufferCapacity = INCOMING_BUFFER_SIZE)
        private val mutableIncomingDirectMessages = MutableSharedFlow<XmppDirectMessage>(extraBufferCapacity = INCOMING_BUFFER_SIZE)
        private val mutableCallSignals = MutableSharedFlow<InboundCallSignal>(extraBufferCapacity = INCOMING_BUFFER_SIZE)
        private val mutablePresences = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        private val mutableActiveRoomJids = MutableStateFlow<Set<String>>(emptySet())
        private val mutableRoomAvatarHashes = MutableStateFlow<Map<String, String>>(emptyMap())

        // Serialises connect()/disconnect() so the three callers that
        // bootstrap the session (ChatViewModel, WaddleMessagingService,
        // WaddleCatchUpWorker) cannot race each other into building three
        // concurrent Smack connections — we'd authenticate, then the server
        // would race our duplicate sessions and close one at random,
        // leaving `connection` pointing at a dead stream.
        private val lifecycleMutex = Mutex()
        private val roomJoinMutex = Mutex()
        private val joinedRoomJids = Collections.synchronizedSet(mutableSetOf<String>())
        private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var connection: AbstractXMPPConnection? = null
        private var mucNickname: Resourcepart? = null
        private var accountUserId: String? = null
        private var accountUsername: String? = null
        private var accountBareJid: String? = null
        private var reconnectionAttempt = 0
        private var lastDisconnectCause: String? = null
        private var networkCallback: ConnectivityManager.NetworkCallback? = null

        @Volatile private var intentionalDisconnect = false

        @Volatile private var reconnectJob: Job? = null

        @Volatile private var hurryReconnect: (() -> Unit)? = null

        override val connectionState: StateFlow<XmppConnectionState> = mutableConnectionState.asStateFlow()
        override val incomingMessages: Flow<XmppHistoryMessage> = mutableIncomingMessages.asSharedFlow()
        override val incomingDirectMessages: Flow<XmppDirectMessage> = mutableIncomingDirectMessages.asSharedFlow()
        override val callSignals: Flow<InboundCallSignal> = mutableCallSignals.asSharedFlow()
        override val supportedFeatures: List<XepFeature> = XmppFeatureRegistry.supported
        override val presences: StateFlow<Map<String, Boolean>> = mutablePresences.asStateFlow()
        override val activeRoomJids: StateFlow<Set<String>> = mutableActiveRoomJids.asStateFlow()
        override val roomAvatarHashes: StateFlow<Map<String, String>> = mutableRoomAvatarHashes.asStateFlow()

        override fun clearRoomActivity(roomJid: String) {
            mutableActiveRoomJids.update { current ->
                if (roomJid in current) current - roomJid else current
            }
        }

        override suspend fun connect(
            session: StoredSession,
            environment: WaddleEnvironment,
        ) {
            lifecycleMutex.withLock {
                withContext(Dispatchers.IO) {
                    doConnect(session, environment)
                }
            }
        }

        private fun doConnect(
            session: StoredSession,
            environment: WaddleEnvironment,
        ) {
            val existing = connection
            if (existing != null &&
                existing.isAuthenticated &&
                accountBareJid == session.jid
            ) {
                WaddleLog.info("XMPP already authenticated for ${session.jid}; skipping reconnect.")
                return
            }
            intentionalDisconnect = false
            reconnectionAttempt = 0
            lastDisconnectCause = null
            mutableConnectionState.value = XmppConnectionState.Connecting
            runCatching {
                AndroidSmackInitializer.initialize(context)
                registerMamProviders()
                registerCallProviders()
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
                    enablePostLoginFeatures(next)
                    connection = next
                    // Only register the ConnectivityManager callback once we
                    // have an active connection to nudge. Registering earlier
                    // and then throwing out of the connect() path leaks a
                    // system-wide callback that we'd never unregister.
                    registerNetworkCallback()
                    mutableConnectionState.value = XmppConnectionState.Connected(session.jid)
                }
            }.onFailure { throwable ->
                WaddleLog.error("XMPP connection failed.", throwable)
                unregisterNetworkCallback()
                mutableConnectionState.value = XmppConnectionState.Failed(throwable.message ?: throwable::class.java.simpleName)
                throw throwable
            }
        }

        override suspend fun disconnect() {
            lifecycleMutex.withLock {
                withContext(Dispatchers.IO) { doDisconnect() }
            }
        }

        private fun doDisconnect() {
            intentionalDisconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            hurryReconnect = null
            unregisterNetworkCallback()
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
            reconnectionAttempt = 0
            lastDisconnectCause = null
            mutableConnectionState.value = XmppConnectionState.Disconnected
        }

        @Synchronized
        private fun registerNetworkCallback() {
            if (networkCallback != null) return
            val manager = context.getSystemService<ConnectivityManager>() ?: return
            val request =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (intentionalDisconnect) return
                        // If a reconnect loop is sleeping, skip its backoff and try now.
                        hurryReconnect?.invoke()
                    }
                }
            runCatching { manager.registerNetworkCallback(request, callback) }
                .onSuccess { networkCallback = callback }
                .onFailure { throwable -> WaddleLog.error("Could not register network callback.", throwable) }
        }

        @Synchronized
        private fun unregisterNetworkCallback() {
            val callback = networkCallback ?: return
            val manager = context.getSystemService<ConnectivityManager>() ?: return
            runCatching { manager.unregisterNetworkCallback(callback) }
                .onFailure { throwable -> WaddleLog.error("Could not unregister network callback.", throwable) }
            networkCallback = null
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
                val discoManager = ServiceDiscoveryManager.getInstanceFor(activeConnection)
                discoManager
                    .discoverItems(domain, waddleId)
                    .items
                    .map { item ->
                        val roomJid = item.entityID.toString()
                        val roomLocalpart = roomJid.substringBefore('@')
                        // XEP-0030 disco#info per room so we can detect the
                        // XEP-0508 `urn:xmpp:forums:0` feature and tag the
                        // channel as a forum. Failures fall back to "text".
                        val channelType =
                            runCatching {
                                val info = discoManager.discoverInfo(item.entityID)
                                if (info.containsFeature(FORUMS_FEATURE)) "forum" else "text"
                            }.getOrDefault("text")
                        XmppChannelRef(
                            id = roomLocalpart.removePrefix("${waddleId}_"),
                            name = item.name ?: roomLocalpart.removePrefix("${waddleId}_"),
                            roomJid = roomJid,
                            channelType = channelType,
                        )
                    }
            }

        override suspend fun sendGroupMessage(draft: ChatMessageDraft): String =
            withConnection { activeConnection ->
                ensureJoined(activeConnection, draft.roomJid)
                val stanzaId = nextStanzaId()
                val wireId = draft.stanzaId ?: stanzaId
                val message = buildGroupMessage(draft, wireId)
                activeConnection.sendStanza(message)
                message.stanzaId
            }

        private fun buildGroupMessage(
            draft: ChatMessageDraft,
            wireId: String,
        ): Message {
            val preparedBody =
                draft.replyToMessageId
                    ?.let { bodyWithReplyFallback(draft.body, draft.replyToFallbackBody) }
                    ?: PreparedBody(draft.body)
            val builder =
                StanzaBuilder
                    .buildMessage(wireId)
                    .to(JidCreate.entityBareFrom(draft.roomJid))
                    .ofType(Message.Type.groupchat)
                    .setBody(preparedBody.body)
                    .addExtension(XmppExtensions.originId(wireId))
                    .addExtension(XmppExtensions.markable())
                    .addExtension(XmppExtensions.storeHint())
            DeliveryReceiptRequest.addTo(builder)
            addReplyExtensions(builder, draft, preparedBody)
            addContentExtensions(builder, draft, preparedBody)
            addThreadExtensions(builder, draft)
            return builder.build()
        }

        private fun addReplyExtensions(
            builder: MessageBuilder,
            draft: ChatMessageDraft,
            preparedBody: PreparedBody,
        ) {
            val replyId = draft.replyToMessageId ?: return
            builder.addExtension(XmppExtensions.reply(replyId, draft.replyToSenderJid))
            preparedBody.replyFallbackEnd?.let { end ->
                builder.addExtension(XmppExtensions.fallback(REPLY_NAMESPACE, 0, end))
            }
        }

        private fun addContentExtensions(
            builder: MessageBuilder,
            draft: ChatMessageDraft,
            preparedBody: PreparedBody,
        ) {
            draft.correctionMessageId?.let { builder.addExtension(XmppExtensions.correction(it)) }
            addMentionExtensions(builder, draft)
            addSharedFileExtension(builder, draft.sharedFile, preparedBody)
            draft.stickerPack?.let { pack -> builder.addExtension(XmppExtensions.sticker(pack)) }
        }

        private fun addMentionExtensions(
            builder: MessageBuilder,
            draft: ChatMessageDraft,
        ) {
            mentionReferences(draft.body, draft.mentions).forEach { mention ->
                builder.addExtension(XmppExtensions.referenceMention(mention.uri, mention.begin, mention.end))
            }
            explicitMentionTypes(draft.body).takeIf { it.isNotEmpty() }?.let { mentionTypes ->
                builder.addExtension(XmppExtensions.explicitMentions(mentionTypes))
            }
        }

        private fun addSharedFileExtension(
            builder: MessageBuilder,
            sharedFile: ChatFileAttachment?,
            preparedBody: PreparedBody,
        ) {
            sharedFile ?: return
            builder.addExtension(sharedFile.toFileSharingExtension())
            if (preparedBody.body.isBlank()) return
            builder.addExtension(
                XmppExtensions.fallback(
                    FILE_SHARING_NAMESPACE,
                    preparedBody.replyFallbackEnd ?: 0,
                    preparedBody.body.xmppCodePointLength(),
                ),
            )
        }

        private fun addThreadExtensions(
            builder: MessageBuilder,
            draft: ChatMessageDraft,
        ) {
            draft.threadId?.let { tid ->
                builder.addExtension(XmppExtensions.thread(threadId = tid, parentThreadId = draft.parentThreadId))
            }
            draft.forumTopicTitle?.let { title -> builder.addExtension(XmppExtensions.forumThreadCreate(title)) }
            draft.forumReplyThreadId?.let { tid -> builder.addExtension(XmppExtensions.forumThreadReply(tid)) }
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

        override suspend fun loadAllDirectMessageHistory(
            ownBareJid: String,
            beforeId: String?,
            maxResults: Int,
        ): XmppMessagePage<XmppDirectMessage> =
            withConnection { activeConnection ->
                val queryId = StringUtils.secureUniqueRandomString()
                val iqId = StringUtils.secureUniqueRandomString()
                // MamDirectHistoryQueryIq with blank peerJid emits no `with` filter,
                // which yields the user's entire 1:1 archive.
                val query =
                    MamDirectHistoryQueryIq(
                        queryId = queryId,
                        maxResults = maxResults,
                        peerJid = "",
                        afterId = null,
                        beforeId = beforeId,
                        fullText = null,
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
                                ?: error("Timed out waiting for DM archive MAM response.")
                        when (stanza) {
                            is Message -> {
                                MamElements.MamResultExtension.from(stanza)?.let { result ->
                                    messageMapper().directMessageFromOwnArchive(result, ownBareJid)?.let(history::add)
                                }
                            }

                            is MamFinIQ -> {
                                fin = stanza
                                finished = true
                            }

                            is IQ -> {
                                if (stanza.type == IQ.Type.error) {
                                    error("DM archive MAM query failed: ${stanza.error}")
                                }
                                finished = true
                            }
                        }
                    }
                } finally {
                    collector.cancel()
                }
                val messages = history.distinctBy { it.id }.sortedBy { it.createdAt }
                WaddleLog.info("Warmed ${messages.size} DM archive messages across all peers.")
                XmppMessagePage(
                    messages = messages,
                    firstId = fin?.getRSMSet()?.first ?: messages.firstOrNull()?.serverId ?: messages.firstOrNull()?.id,
                    lastId = fin?.getRSMSet()?.last ?: messages.lastOrNull()?.serverId ?: messages.lastOrNull()?.id,
                    complete = fin?.isComplete ?: (messages.size < maxResults),
                )
            }

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
            replyToMessageId: String?,
            replyToSenderJid: String?,
            replyToFallbackBody: String?,
        ): String =
            withConnection { activeConnection ->
                val messageId = stanzaId ?: nextStanzaId()
                val preparedBody =
                    replyToMessageId
                        ?.let { bodyWithReplyFallback(body, replyToFallbackBody) }
                        ?: PreparedBody(body)
                val builder =
                    StanzaBuilder
                        .buildMessage(messageId)
                        .to(JidCreate.entityBareFrom(peerJid))
                        .ofType(Message.Type.chat)
                        .setBody(preparedBody.body)
                        .addExtension(XmppExtensions.originId(messageId))
                        .addExtension(XmppExtensions.markable())
                        .addExtension(XmppExtensions.storeHint())
                DeliveryReceiptRequest.addTo(builder)
                replyToMessageId?.let {
                    builder.addExtension(XmppExtensions.reply(it, replyToSenderJid))
                    preparedBody.replyFallbackEnd?.let { end ->
                        builder.addExtension(XmppExtensions.fallback(REPLY_NAMESPACE, 0, end))
                    }
                }
                sharedFile?.let {
                    builder.addExtension(it.toFileSharingExtension())
                    if (preparedBody.body.isNotBlank()) {
                        builder.addExtension(
                            XmppExtensions.fallback(
                                FILE_SHARING_NAMESPACE,
                                preparedBody.replyFallbackEnd ?: 0,
                                preparedBody.body.xmppCodePointLength(),
                            ),
                        )
                    }
                }
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
            sendDirectMarker(
                peerJid = peerJid,
                extension = XmppExtensions.retract(messageId),
                store = true,
                body = RETRACTION_FALLBACK_BODY,
                fallbackForNamespace = RETRACTION_NAMESPACE,
            )
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
            sendMarker(
                roomJid = roomJid,
                extension = XmppExtensions.retract(messageId),
                store = true,
                body = RETRACTION_FALLBACK_BODY,
                fallbackForNamespace = RETRACTION_NAMESPACE,
            )
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

        // ----- XEP-0215 External Service Discovery -----

        override suspend fun discoverExternalServices(): List<ExternalService> =
            withConnection { activeConnection ->
                val bareJid = accountBareJid ?: return@withConnection emptyList<ExternalService>()
                val domain = bareJid.substringAfter('@')
                val request =
                    social.waddle.android.xmpp.call.ExtDiscoRequestIQ().apply {
                        to = JidCreate.domainBareFrom(domain)
                    }
                val response: IQ? =
                    try {
                        activeConnection.sendIqRequestAndWaitForResponse<social.waddle.android.xmpp.call.ExtDiscoResultIQ>(request)
                    } catch (cause: Exception) {
                        WaddleLog.info("XEP-0215 ExtDisco unavailable: ${cause.message}")
                        null
                    }
                (response as? social.waddle.android.xmpp.call.ExtDiscoResultIQ)?.services.orEmpty()
            }

        // ----- XEP-0482 Call Invite messages -----

        override suspend fun sendCallInvite(
            peerJid: String,
            sid: String,
            sfuJid: String,
            muji: Boolean,
            video: Boolean,
        ) {
            sendCallInviteMessage(
                peerJid = peerJid,
                sid = sid,
                sfuJid = sfuJid,
                muji = muji,
                video = video,
            )
        }

        override suspend fun sendCallReject(
            peerJid: String,
            sid: String,
        ) {
            sendCallLifecycleMessage(peerJid = peerJid, sid = sid, tag = "reject")
        }

        override suspend fun sendCallLeft(
            peerJid: String,
            sid: String,
        ) {
            sendCallLifecycleMessage(peerJid = peerJid, sid = sid, tag = "left")
        }

        // ----- Generic Jingle IQ -----

        override suspend fun sendJingleIq(
            to: String,
            jingleXml: String,
        ) {
            withConnection { activeConnection ->
                val parsed = parseJingleOuter(jingleXml)
                val iq =
                    social.waddle.android.xmpp.call
                        .JingleIQ(
                            attributes = parsed.first,
                            innerXml = parsed.second,
                        ).apply {
                            this.to = JidCreate.from(to)
                            type = IQ.Type.set
                        }
                activeConnection.sendIqRequestAndWaitForResponse<IQ>(iq)
                Unit
            }
        }

        /**
         * Strip the outer `<jingle ...>`...`</jingle>` wrapper and return
         * (attributes, innerXml). The signaler already serialized a
         * well-formed element, so we do minimal parsing here.
         */
        private fun parseJingleOuter(xml: String): Pair<Map<String, String>, String> {
            val trimmed = xml.trim()
            val openEnd = trimmed.indexOf('>')
            check(openEnd > 0) { "Invalid Jingle XML: $xml" }
            val openTag = trimmed.substring(0, openEnd + 1)
            val selfClosing = openTag.endsWith("/>")
            val inner =
                if (selfClosing) {
                    ""
                } else {
                    val closeStart = trimmed.lastIndexOf("</")
                    check(closeStart > openEnd) { "Invalid Jingle XML: missing close tag" }
                    trimmed.substring(openEnd + 1, closeStart)
                }
            val attrs = parseAttrs(openTag)
            return attrs to inner
        }

        /**
         * `openTag` looks like `<jingle xmlns='...' action='...' sid='...'>`
         * or `<jingle ... />`. A regex matches `name='value'` / `name="value"`
         * pairs robustly enough for our well-formed inputs. The `xmlns`
         * declaration is dropped — callers supply namespace separately.
         */
        private fun parseAttrs(openTag: String): Map<String, String> {
            val afterName = openTag.substringAfter(' ', "").trimEnd('>', '/')
            return ATTR_REGEX
                .findAll(afterName)
                .map { match -> match.groupValues[1] to match.groupValues[2] }
                .filter { (name, _) -> name != "xmlns" }
                .associate { it }
                .toMap(LinkedHashMap())
        }

        /**
         * Build and send a Waddle XEP-0482 call invite. Matches the format
         * the `chat/` web client emits byte-for-byte:
         *
         * ```xml
         * <invite xmlns='urn:xmpp:call-invites:0' id='<sid>'>
         *   <muji/>                                 <!-- muji=true only -->
         *   <jingle sid='<sid>' jid='<sfuJid>'/>
         *   <external uri='xmpp:<sfuJid>?jingle;sid=<sid>'/>
         * </invite>
         * <meeting xmlns='urn:xmpp:http:online-meetings:invite:0'
         *          desc='Video call'|'Audio call'
         *          type='muji'|'dm'
         *          url='xmpp:<sfuJid>?jingle;sid=<sid>'/>
         * ```
         */
        private suspend fun sendCallInviteMessage(
            peerJid: String,
            sid: String,
            sfuJid: String,
            muji: Boolean,
            video: Boolean,
        ) {
            withConnection { activeConnection ->
                val externalUri = "xmpp:$sfuJid?jingle;sid=$sid"
                val invitesNs = social.waddle.android.xmpp.call.CallNamespaces.CALL_INVITES
                val meetingNs = social.waddle.android.xmpp.call.CallNamespaces.MEETING
                val desc = if (video) "Video call" else "Audio call"

                val inviteBuilder =
                    org.jivesoftware.smack.packet.StandardExtensionElement
                        .builder("invite", invitesNs)
                        .addAttribute("id", sid)
                if (muji) {
                    inviteBuilder.addElement(
                        org.jivesoftware.smack.packet.StandardExtensionElement
                            .builder("muji", invitesNs)
                            .build(),
                    )
                }
                inviteBuilder.addElement(
                    org.jivesoftware.smack.packet.StandardExtensionElement
                        .builder("jingle", invitesNs)
                        .addAttribute("sid", sid)
                        .addAttribute("jid", sfuJid)
                        .build(),
                )
                inviteBuilder.addElement(
                    org.jivesoftware.smack.packet.StandardExtensionElement
                        .builder("external", invitesNs)
                        .addAttribute("uri", externalUri)
                        .build(),
                )

                val meeting =
                    org.jivesoftware.smack.packet.StandardExtensionElement
                        .builder("meeting", meetingNs)
                        .addAttribute("type", if (muji) "muji" else "dm")
                        .addAttribute("url", externalUri)
                        .addAttribute("desc", desc)
                        .build()

                val messageType = if (muji) Message.Type.groupchat else Message.Type.chat
                val addressee = if (muji) JidCreate.entityBareFrom(peerJid) else JidCreate.from(peerJid)
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(addressee)
                        .ofType(messageType)
                        .setBody("$desc started")
                        .addExtension(inviteBuilder.build())
                        .addExtension(meeting)
                        .addExtension(XmppExtensions.storeHint())
                activeConnection.sendStanza(builder.build())
                WaddleLog.info("Sent XEP-0482 invite sid=$sid to $peerJid (muji=$muji video=$video).")
            }
        }

        private suspend fun sendCallLifecycleMessage(
            peerJid: String,
            sid: String,
            tag: String,
        ) {
            withConnection { activeConnection ->
                val extension =
                    org.jivesoftware.smack.packet.StandardExtensionElement
                        .builder(tag, social.waddle.android.xmpp.call.CallNamespaces.CALL_INVITES)
                        .addAttribute("id", sid)
                        .build()
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.from(peerJid))
                        .ofType(Message.Type.chat)
                        .addExtension(extension)
                        .addExtension(XmppExtensions.noStoreHint())
                activeConnection.sendStanza(builder.build())
                WaddleLog.info("Sent XEP-0482 <$tag> sid=$sid to $peerJid.")
            }
        }

        private fun registerCallSignalListeners(activeConnection: AbstractXMPPConnection) {
            // Own the IQ-set response flow for Jingle IQs. Without this,
            // Smack's default processor replies with <service-unavailable/>
            // before our async listener sees the stanza — the SFU then
            // thinks our client can't speak Jingle and stops sending.
            activeConnection.registerIQRequestHandler(
                object : org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler(
                    social.waddle.android.xmpp.call.JingleIQ.ELEMENT,
                    social.waddle.android.xmpp.call.CallNamespaces.JINGLE,
                    IQ.Type.set,
                    org.jivesoftware.smack.iqrequest.IQRequestHandler.Mode.async,
                ) {
                    override fun handleIQRequest(iqRequest: IQ): IQ {
                        val jingle = iqRequest as social.waddle.android.xmpp.call.JingleIQ
                        val fromJid = jingle.from?.toString().orEmpty()
                        val jingleXml = buildIncomingJingleXml(jingle)
                        if (!mutableCallSignals.tryEmit(
                                InboundCallSignal.Jingle(fromJid = fromJid, jingleXml = jingleXml),
                            )
                        ) {
                            WaddleLog.info("Dropped Jingle IQ from $fromJid — call-signal buffer full.")
                        }
                        return IQ.createResultIQ(jingle)
                    }
                },
            )

            activeConnection.addAsyncStanzaListener(
                StanzaListener { stanza ->
                    val message = stanza as? Message ?: return@StanzaListener
                    val fromJid = message.from?.toString()?.takeIf { it.isNotBlank() } ?: return@StanzaListener
                    val signal = parseCallInviteExtensions(message, fromJid) ?: return@StanzaListener
                    if (!mutableCallSignals.tryEmit(signal)) {
                        WaddleLog.info("Dropped XEP-0482 call signal from $fromJid — buffer full.")
                    }
                },
                StanzaFilter { stanza ->
                    stanza is Message &&
                        (stanza.type == Message.Type.chat || stanza.type == Message.Type.groupchat)
                },
            )

            // ----- XEP-0502 MUC Activity Indicator -----
            // Server pushes `<message><activity xmlns='urn:xmpp:muc-activity:0'><active jid='…'/></activity></message>`
            // for rooms the user subscribes to but may not have joined — used
            // for lightweight sidebar badges.
            activeConnection.addAsyncStanzaListener(
                StanzaListener { stanza ->
                    val message = stanza as? Message ?: return@StanzaListener
                    val activity =
                        message.getExtensionElement("activity", MUC_ACTIVITY_NAMESPACE) as? StandardExtensionElement
                            ?: return@StanzaListener
                    val active = activity.getElements("active").mapNotNull { it.getAttributeValue("jid") }
                    if (active.isEmpty()) return@StanzaListener
                    mutableActiveRoomJids.update { current -> current + active }
                },
                StanzaFilter { stanza ->
                    stanza is Message &&
                        stanza.getExtensionElement("activity", MUC_ACTIVITY_NAMESPACE) != null
                },
            )
        }

        private fun buildIncomingJingleXml(iq: social.waddle.android.xmpp.call.JingleIQ): String {
            val sb = StringBuilder()
            sb
                .append("<jingle xmlns='")
                .append(social.waddle.android.xmpp.call.CallNamespaces.JINGLE)
                .append('\'')
            for ((k, v) in iq.attributes) {
                sb
                    .append(' ')
                    .append(k)
                    .append("='")
                    .append(v)
                    .append('\'')
            }
            if (iq.innerXml.isEmpty()) {
                sb.append("/>")
            } else {
                sb.append('>').append(iq.innerXml).append("</jingle>")
            }
            return sb.toString()
        }

        /**
         * Parse Waddle's XEP-0482 invite / reject / left message extensions.
         *
         * The real wire format (confirmed from the `chat/` client and live
         * captures) is:
         *
         * ```xml
         * <invite xmlns='urn:xmpp:call-invites:0' id='<msgId>'>
         *   <muji/>                                    <!-- only for group calls -->
         *   <jingle sid='<sid>' jid='<sfuJid>'/>
         *   <external uri='xmpp:...?jingle;sid=...'/>  <!-- optional -->
         * </invite>
         * <meeting xmlns='urn:xmpp:http:online-meetings:invite:0'
         *          desc='Video call'|'Audio call'
         *          type='muji'|'dm'
         *          url='xmpp:...?jingle;sid=...'/>
         * ```
         *
         * Key gotchas the earlier parser got wrong:
         *  - `muji` is an empty child element, not a boolean attribute.
         *  - The Jingle SID + SFU jid live on a child `<jingle>` element,
         *    not on the invite's own attributes.
         *  - Video/audio is signalled by `<meeting desc=>`, not a flag on
         *    the invite itself.
         */
        private fun parseCallInviteExtensions(
            message: Message,
            fromJid: String,
        ): InboundCallSignal? {
            val invitesNs = social.waddle.android.xmpp.call.CallNamespaces.CALL_INVITES
            val invite =
                message.getExtensionElement("invite", invitesNs) as?
                    org.jivesoftware.smack.packet.StandardExtensionElement
            if (invite != null) return parseInviteElement(invite, message, fromJid)
            val reject =
                message.getExtensionElement("reject", invitesNs) as?
                    org.jivesoftware.smack.packet.StandardExtensionElement
            if (reject != null) {
                val sid = reject.getAttributeValue("id") ?: return null
                return InboundCallSignal.Reject(fromJid = fromJid, sid = sid)
            }
            val left =
                message.getExtensionElement("left", invitesNs) as?
                    org.jivesoftware.smack.packet.StandardExtensionElement
            if (left != null) {
                val sid = left.getAttributeValue("id") ?: return null
                return InboundCallSignal.Left(fromJid = fromJid, sid = sid)
            }
            return null
        }

        private fun parseInviteElement(
            invite: org.jivesoftware.smack.packet.StandardExtensionElement,
            message: Message,
            fromJid: String,
        ): InboundCallSignal.Invite? {
            val inviteId = invite.getAttributeValue("id") ?: return null
            val jingleChild = invite.getFirstElement("jingle")
            val sid = jingleChild?.getAttributeValue("sid") ?: inviteId
            val jingleJid = jingleChild?.getAttributeValue("jid")
            val muji = invite.getFirstElement("muji") != null

            val meeting =
                message.getExtensionElement(
                    "meeting",
                    social.waddle.android.xmpp.call.CallNamespaces.MEETING,
                ) as? org.jivesoftware.smack.packet.StandardExtensionElement
            val meetingDesc = meeting?.getAttributeValue("desc")
            val video = meetingDesc?.contains("video", ignoreCase = true) ?: true

            return InboundCallSignal.Invite(
                fromJid = fromJid,
                sid = sid,
                muji = muji,
                jingleJid = jingleJid,
                video = video,
                meetingDescription = meetingDesc,
            )
        }

        private suspend fun sendMarker(
            roomJid: String,
            extension: XmlElement,
            store: Boolean,
            body: String? = null,
            fallbackForNamespace: String? = null,
        ) {
            withConnection { activeConnection ->
                ensureJoined(activeConnection, roomJid)
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(roomJid))
                        .ofType(Message.Type.groupchat)
                        .addExtension(extension)
                body?.takeIf(String::isNotBlank)?.let { fallbackBody ->
                    builder.setBody(fallbackBody)
                    fallbackForNamespace?.let {
                        builder.addExtension(XmppExtensions.fallback(it, 0, fallbackBody.xmppCodePointLength()))
                    }
                }
                builder.addExtension(if (store) XmppExtensions.storeHint() else XmppExtensions.noStoreHint())
                activeConnection.sendStanza(builder.build())
            }
        }

        private suspend fun sendDirectMarker(
            peerJid: String,
            extension: XmlElement,
            store: Boolean,
            body: String? = null,
            fallbackForNamespace: String? = null,
        ) {
            withConnection { activeConnection ->
                val builder =
                    StanzaBuilder
                        .buildMessage(nextStanzaId())
                        .to(JidCreate.entityBareFrom(peerJid))
                        .ofType(Message.Type.chat)
                        .addExtension(extension)
                body?.takeIf(String::isNotBlank)?.let { fallbackBody ->
                    builder.setBody(fallbackBody)
                    fallbackForNamespace?.let {
                        builder.addExtension(XmppExtensions.fallback(it, 0, fallbackBody.xmppCodePointLength()))
                    }
                }
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
            advertiseDiscoFeatures(activeConnection)
            registerIncomingMessageListener(activeConnection)
            registerIncomingDirectMessageListener(activeConnection)
            registerPresenceListener(activeConnection)
            registerCallSignalListeners(activeConnection)
            ReconnectionManager.getInstanceFor(activeConnection).disableAutomaticReconnection()
            activeConnection.addConnectionListener(
                object : ConnectionListener {
                    override fun authenticated(
                        connection: XMPPConnection,
                        resumed: Boolean,
                    ) {
                        reconnectionAttempt = 0
                        lastDisconnectCause = null
                        mutableConnectionState.value = XmppConnectionState.Connected(connection.user.toString())
                        rejoinRooms(activeConnection)
                    }

                    override fun connectionClosedOnError(exception: Exception) {
                        WaddleLog.error("XMPP connection closed on error.", exception)
                        lastDisconnectCause = exception.message ?: exception::class.java.simpleName
                        maybeScheduleReconnect(activeConnection)
                    }

                    override fun connectionClosed() {
                        if (intentionalDisconnect) {
                            mutableConnectionState.value = XmppConnectionState.Disconnected
                            return
                        }
                        WaddleLog.info("XMPP connection closed unexpectedly.")
                        maybeScheduleReconnect(activeConnection)
                    }
                },
            )
        }

        private fun advertiseDiscoFeatures(activeConnection: AbstractXMPPConnection) {
            val discoManager = ServiceDiscoveryManager.getInstanceFor(activeConnection)
            CLIENT_DISCO_FEATURES.forEach { feature ->
                if (!discoManager.includesFeature(feature)) {
                    discoManager.addFeature(feature)
                }
            }
        }

        private fun maybeScheduleReconnect(activeConnection: AbstractXMPPConnection) {
            if (intentionalDisconnect) {
                return
            }
            if (reconnectJob?.isActive == true) {
                // A reconnect loop is already running; it will handle the retry schedule.
                return
            }
            scheduleReconnect(activeConnection)
        }

        private fun scheduleReconnect(activeConnection: AbstractXMPPConnection) {
            reconnectJob =
                reconnectScope.launch {
                    while (isActive && !intentionalDisconnect) {
                        reconnectionAttempt += 1
                        if (reconnectionAttempt > MAX_RECONNECT_ATTEMPTS) {
                            WaddleLog.info(
                                "Giving up XMPP reconnect after $MAX_RECONNECT_ATTEMPTS attempts.",
                            )
                            mutableConnectionState.value =
                                XmppConnectionState.Failed(
                                    "Reconnect gave up after $MAX_RECONNECT_ATTEMPTS attempts.",
                                )
                            return@launch
                        }
                        val delaySeconds = nextBackoffDelaySeconds(reconnectionAttempt)
                        WaddleLog.info(
                            "XMPP reconnect attempt $reconnectionAttempt scheduled in ${delaySeconds}s.",
                        )
                        mutableConnectionState.value =
                            XmppConnectionState.Reconnecting(
                                attempt = reconnectionAttempt,
                                nextDelaySeconds = delaySeconds,
                                cause = lastDisconnectCause,
                            )
                        cancellableBackoffDelay(delaySeconds * MILLIS_PER_SECOND)
                        if (intentionalDisconnect) {
                            return@launch
                        }
                        mutableConnectionState.value = XmppConnectionState.Connecting
                        val success =
                            runCatching {
                                // Before calling login() again, let the session provider refresh the
                                // OAuth access token if it has expired during the outage. If the
                                // bearer token changed, we must rebuild the connection — Smack's
                                // SASL layer uses the credentials captured at ConnectionConfiguration
                                // build time, so we can't just re-login on the existing instance.
                                val fresh = runCatching { sessionProvider.get().currentSession() }.getOrNull()
                                val rebuilt = maybeRebuildConnection(activeConnection, fresh)
                                // When we land here the connection has already been closed
                                // by the ConnectionListener that scheduled us, so we do not
                                // call disconnect() again (which would re-enter the listener
                                // and recursively schedule another reconnect) unless we just
                                // built a fresh instance above.
                                val target = rebuilt ?: activeConnection
                                target.connect()
                                target.login()
                                enablePostLoginFeatures(target)
                                if (rebuilt != null) {
                                    connection = rebuilt
                                }
                            }.onFailure { throwable ->
                                WaddleLog.error(
                                    "XMPP reconnect attempt $reconnectionAttempt failed.",
                                    throwable,
                                )
                                lastDisconnectCause = throwable.message ?: throwable::class.java.simpleName
                            }.isSuccess
                        if (success) {
                            return@launch
                        }
                    }
                }
        }

        /**
         * If the session provider handed us a session whose bearer token differs
         * from the one baked into the current connection, build a new Smack
         * connection with the fresh credentials so SASL authentication has a
         * chance of succeeding. Returns the new connection, or null if no rebuild
         * was needed.
         */
        private fun maybeRebuildConnection(
            current: AbstractXMPPConnection,
            fresh: StoredSession?,
        ): AbstractXMPPConnection? {
            if (fresh == null) return null
            val currentPassword = runCatching { current.configuration?.password }.getOrNull()
            if (currentPassword == fresh.xmppBearerToken) return null
            WaddleLog.info("XMPP bearer token changed during outage — rebuilding connection.")
            registerOAuthBearer()
            val next = buildConnection(fresh, fresh.environment)
            configureConnection(next)
            accountBareJid = fresh.jid
            accountUserId = fresh.userId
            accountUsername = fresh.username
            mucNickname = nicknameFor(fresh)
            joinedRoomJids.clear()
            // Swap active pointer so disconnect() / onDestroy routes find the fresh instance.
            // We intentionally do not call current.disconnect() here — the ConnectionListener
            // fired connectionClosed* before scheduling us, so `current` is already dead.
            return next
        }

        /**
         * Waits for [millis] unless [hurryReconnect] is invoked — typically from a
         * ConnectivityManager onAvailable callback — in which case we wake up
         * immediately. This turns a passive backoff into an opportunistic retry
         * that reacts the instant the OS hands us a usable network.
         */
        private suspend fun cancellableBackoffDelay(millis: Long) {
            val waiter = CompletableDeferred<Unit>()
            hurryReconnect = { if (!waiter.isCompleted) waiter.complete(Unit) }
            try {
                withTimeoutOrNull(millis) { waiter.await() }
            } finally {
                hurryReconnect = null
            }
        }

        private fun nextBackoffDelaySeconds(attempt: Int): Int {
            val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
            val base = RECONNECT_BASE_DELAY_SECONDS.toLong() shl exponent
            val capped = base.coerceAtMost(MAX_RECONNECT_DELAY_SECONDS.toLong())
            val jitterRange = (capped * RECONNECT_JITTER_RATIO).toLong().coerceAtLeast(1L)
            val jitter = kotlin.random.Random.nextLong(-jitterRange, jitterRange + 1)
            return (capped + jitter)
                .coerceAtLeast(RECONNECT_BASE_DELAY_SECONDS.toLong())
                .coerceAtMost(MAX_RECONNECT_DELAY_SECONDS.toLong())
                .toInt()
        }

        private fun registerPresenceListener(activeConnection: AbstractXMPPConnection) {
            activeConnection.addAsyncStanzaListener(
                StanzaListener { stanza ->
                    val presence = stanza as? Presence ?: return@StanzaListener
                    val bareJid =
                        presence.from
                            ?.toString()
                            ?.substringBefore('/')
                            ?.takeIf { it.isNotBlank() }
                            ?: return@StanzaListener
                    val available =
                        when (presence.type) {
                            Presence.Type.available -> true

                            Presence.Type.unavailable -> false

                            // subscribe / subscribed / unsubscribe / unsubscribed / probe / error
                            // are roster-management signals, not online-status updates.
                            else -> return@StanzaListener
                        }
                    mutablePresences.update { current ->
                        if (current[bareJid] == available) current else current + (bareJid to available)
                    }
                    // XEP-0486 MUC Avatars: rooms advertise their avatar hash via
                    // the legacy vcard-temp:x:update payload on presence. Track the
                    // latest hash so the UI can bust caches when it changes.
                    if (available) {
                        val vcardUpdate =
                            presence.getExtensionElement("x", VCARD_UPDATE_NAMESPACE) as? StandardExtensionElement
                        val photoHash =
                            vcardUpdate
                                ?.getFirstElement("photo", VCARD_UPDATE_NAMESPACE)
                                ?.text
                                ?.takeIf { it.isNotBlank() }
                        if (photoHash != null) {
                            mutableRoomAvatarHashes.update { current ->
                                if (current[bareJid] == photoHash) current else current + (bareJid to photoHash)
                            }
                        }
                    }
                },
                StanzaFilter { stanza -> stanza is Presence },
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
                    val effectiveMessage =
                        CarbonExtension
                            .from(message)
                            ?.forwarded
                            ?.forwardedStanza
                            ?: message
                    if (effectiveMessage.type != Message.Type.chat || effectiveMessage.isKnownRoomPrivateMessage()) {
                        return@StanzaListener
                    }
                    liveDirectMessageFrom(effectiveMessage, ownBareJid)?.let { incoming ->
                        if (!mutableIncomingDirectMessages.tryEmit(incoming)) {
                            WaddleLog.info("Dropped incoming direct XMPP message because the buffer is full.")
                        } else {
                            WaddleLog.info("Received live direct XMPP message ${incoming.id}.")
                        }
                    }
                },
                StanzaFilter { stanza ->
                    stanza is Message && (stanza.type == Message.Type.chat || CarbonExtension.from(stanza) != null)
                },
            )
        }

        private fun Message.isKnownRoomPrivateMessage(): Boolean {
            val fromBare = from?.toString()?.bareJid()
            val toBare = to?.toString()?.bareJid()
            return (fromBare != null && joinedRoomJids.contains(fromBare)) ||
                (toBare != null && joinedRoomJids.contains(toBare))
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
                    .addModule(StreamManagementModuleDescriptor::class.java)
            if (BuildConfig.DEBUG) {
                builder.enableDefaultDebugger()
            }
            builder
                .with(XmppWebSocketTransportModuleDescriptor.Builder::class.java)
                .explicitlySetWebSocketEndpointAndDiscovery(URI(webSocketUrl), false)
                .setWebSocketFactory(OkHttpWebSocketFactory.INSTANCE)
                .buildModule()
            return ModularXmppClientToServerConnection(builder.build()).apply {
                val streamManagement = getConnectionModuleFor<StreamManagementModule>(StreamManagementModuleDescriptor::class.java)
                streamManagement.setStreamManagementEnabled(true)
                streamManagement.setStreamResumptionEnabled(true)
            }
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
            return XMPPTCPConnection(configuration).apply {
                // XEP-0198 Stream Management — Smack gives the server a chance to resume
                // the stream within ~5 min after a network wobble instead of rebuilding
                // from scratch, and acks every stanza so nothing is silently dropped.
                setUseStreamManagement(true)
                setUseStreamManagementResumption(true)
            }
        }

        /**
         * Turn on XEPs that must be negotiated AFTER login (XEP-0280 Carbons and
         * — when the transport supports it — XEP-0198 resumption). Failures here
         * are logged but non-fatal: the connection is still usable without them.
         */
        private fun enablePostLoginFeatures(activeConnection: AbstractXMPPConnection) {
            runCatching {
                val carbons = CarbonManager.getInstanceFor(activeConnection)
                if (carbons.isSupportedByServer && !carbons.carbonsEnabled) {
                    carbons.enableCarbonsAsync(null)
                }
            }.onFailure { throwable ->
                WaddleLog.error("Failed to enable XEP-0280 Message Carbons.", throwable)
            }
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

        private fun registerCallProviders() {
            ProviderManager.addIQProvider(
                social.waddle.android.xmpp.call.JingleIQ.ELEMENT,
                social.waddle.android.xmpp.call.CallNamespaces.JINGLE,
                social.waddle.android.xmpp.call
                    .JingleIQProvider(),
            )
            ProviderManager.addIQProvider(
                "services",
                social.waddle.android.xmpp.call.CallNamespaces.EXT_DISCO,
                social.waddle.android.xmpp.call
                    .ExtDiscoIQProvider(),
            )
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

        private fun bodyWithReplyFallback(
            body: String,
            replyBody: String?,
        ): PreparedBody {
            val quote =
                replyBody
                    .orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(MAX_REPLY_FALLBACK_LINES)
                    .joinToString("\n")
                    .take(MAX_REPLY_FALLBACK_CHARS)
                    .ifBlank { "Previous message" }
            val prefix =
                quote
                    .lineSequence()
                    .joinToString(separator = "\n", postfix = "\n\n") { line -> "> $line" }
            return PreparedBody(body = prefix + body, replyFallbackEnd = prefix.xmppCodePointLength())
        }

        /**
         * The MUC nickname we present as. We deliberately use the user's raw
         * [StoredSession.username] with no device suffix so Android sends and
         * web-client sends land under the same room nickname — they're the
         * same user. The Waddle MUC accepts multi-session joins with the
         * same nickname (different XMPP resources), so there's no conflict.
         */
        private fun nicknameFor(session: StoredSession): Resourcepart {
            val preferred = session.username.ifBlank { session.xmppLocalpart }
            val fallback = session.xmppLocalpart.ifBlank { DEFAULT_MUC_NICKNAME }
            return Resourcepart.fromOrNull(preferred)
                ?: Resourcepart.fromOrNull(fallback)
                ?: Resourcepart.fromOrThrowUnchecked(DEFAULT_MUC_NICKNAME)
        }

        private data class MentionReference(
            val uri: String,
            val begin: Int,
            val end: Int,
        )

        private data class PreparedBody(
            val body: String,
            val replyFallbackEnd: Int? = null,
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
                        val end = begin + nick.length + 1
                        MentionReference(
                            uri = "xmpp:$nick",
                            begin = body.xmppCodePointOffset(begin),
                            end = body.xmppCodePointOffset(end),
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

        private fun String.bareJid(): String = substringBefore('/')

        private fun String.xmppCodePointLength(): Int = codePointCount(0, length)

        private fun String.xmppCodePointOffset(utf16Index: Int): Int = codePointCount(0, utf16Index.coerceIn(0, length))

        private fun nextStanzaId(): String = StringUtils.secureUniqueRandomString()

        private companion object {
            // Matches `name='value'` or `name="value"` attribute pairs from
            // our own serialized Jingle output — values never contain the
            // surrounding quote character on the generating side.
            val ATTR_REGEX: Regex = Regex("""([\w:-]+)=['"]([^'"]*)['"]""")
            const val DEFAULT_MUC_NICKNAME = "waddle"
            const val MAM_FIN_ELEMENT = "fin"
            const val MAM_RESULT_ELEMENT = "result"
            const val MAM_V1_NAMESPACE = "urn:xmpp:mam:1"
            const val CALL_INVITES_NAMESPACE = "urn:xmpp:call-invites:0"
            const val CHAT_MARKERS_NAMESPACE = "urn:xmpp:chat-markers:0"
            const val CHAT_STATES_NAMESPACE = "http://jabber.org/protocol/chatstates"
            const val CORRECTION_NAMESPACE = "urn:xmpp:message-correct:0"
            const val EXPLICIT_MENTIONS_NAMESPACE = "urn:xmpp:emn:0"
            const val FALLBACK_NAMESPACE = "urn:xmpp:fallback:0"
            const val FILE_SHARING_NAMESPACE = "urn:xmpp:sfs:0"
            const val FILE_METADATA_NAMESPACE = "urn:xmpp:file:metadata:0"
            const val MUC_ACTIVITY_NAMESPACE = "urn:xmpp:muc-activity:0"
            const val FORUMS_FEATURE = "urn:xmpp:forums:0"
            const val ORIGIN_ID_NAMESPACE = "urn:xmpp:sid:0"
            const val RECEIPTS_NAMESPACE = "urn:xmpp:receipts"
            const val REFERENCE_NAMESPACE = "urn:xmpp:reference:0"
            const val REPLY_NAMESPACE = "urn:xmpp:reply:0"
            const val REACTIONS_NAMESPACE = "urn:xmpp:reactions:0"
            const val RETRACTION_NAMESPACE = "urn:xmpp:message-retract:1"
            const val RETRACTION_FALLBACK_BODY = "This message was deleted."
            const val STICKERS_NAMESPACE = "urn:xmpp:stickers:0"
            const val URL_DATA_NAMESPACE = "http://jabber.org/protocol/url-data"
            const val VCARD_UPDATE_NAMESPACE = "vcard-temp:x:update"
            const val INCOMING_BUFFER_SIZE = 64
            const val MAM_COLLECTOR_SIZE = 128
            const val MAM_PAGE_SIZE = 100
            const val MAM_SEARCH_PAGE_SIZE = 50
            const val MAM_QUERY_TIMEOUT_MILLIS = 15_000L
            const val RECONNECT_BASE_DELAY_SECONDS = 2
            const val MAX_RECONNECT_DELAY_SECONDS = 300
            const val MAX_RECONNECT_ATTEMPTS = 12
            const val MAX_BACKOFF_EXPONENT = 16
            const val MAX_REPLY_FALLBACK_LINES = 3
            const val MAX_REPLY_FALLBACK_CHARS = 160
            const val MILLIS_PER_SECOND = 1_000L
            const val RECONNECT_JITTER_RATIO = 0.2
            val MENTION_PATTERN = Regex("(?:^|\\s)@(\\S+)")
            val EVERYONE_PATTERN = Regex("(?:^|\\s)@everyone(?:\\s|$)", RegexOption.IGNORE_CASE)
            val HERE_PATTERN = Regex("(?:^|\\s)@here(?:\\s|$)", RegexOption.IGNORE_CASE)
            val CLIENT_DISCO_FEATURES =
                listOf(
                    CALL_INVITES_NAMESPACE,
                    CHAT_MARKERS_NAMESPACE,
                    CHAT_STATES_NAMESPACE,
                    CORRECTION_NAMESPACE,
                    EXPLICIT_MENTIONS_NAMESPACE,
                    FALLBACK_NAMESPACE,
                    FILE_METADATA_NAMESPACE,
                    FILE_SHARING_NAMESPACE,
                    FORUMS_FEATURE,
                    ORIGIN_ID_NAMESPACE,
                    REACTIONS_NAMESPACE,
                    RECEIPTS_NAMESPACE,
                    REFERENCE_NAMESPACE,
                    REPLY_NAMESPACE,
                    RETRACTION_NAMESPACE,
                    STICKERS_NAMESPACE,
                    URL_DATA_NAMESPACE,
                )
        }
    }
