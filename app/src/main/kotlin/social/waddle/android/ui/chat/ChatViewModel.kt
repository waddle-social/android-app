package social.waddle.android.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.waddle.android.data.ChatRepository
import social.waddle.android.data.DraftRepository
import social.waddle.android.data.LinkPreview
import social.waddle.android.data.LinkPreviewRepository
import social.waddle.android.data.MuteRepository
import social.waddle.android.data.db.ChannelEntity
import social.waddle.android.data.db.DeliverySummary
import social.waddle.android.data.db.DmConversationEntity
import social.waddle.android.data.db.DmMessageEntity
import social.waddle.android.data.db.MessageEntity
import social.waddle.android.data.db.ReactionSummary
import social.waddle.android.data.db.WaddleEntity
import social.waddle.android.data.model.AuthSession
import social.waddle.android.data.model.MemberSummary
import social.waddle.android.data.model.OutboundFileAttachment
import social.waddle.android.data.model.UserSearchResult
import social.waddle.android.data.model.WaddleSummary
import social.waddle.android.notifications.ActiveConversation
import social.waddle.android.notifications.ActiveConversationTracker
import social.waddle.android.util.WaddleLog
import javax.inject.Inject

enum class ChatMode {
    Rooms,
    DirectMessages,
}

data class ChatUiState(
    val mode: ChatMode = ChatMode.Rooms,
    val selectedWaddleId: String? = null,
    val selectedChannelId: String? = null,
    val selectedDmPeerJid: String? = null,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val dmSearchQuery: String = "",
    val dmSearchResults: List<UserSearchResult> = emptyList(),
    val publicWaddles: List<WaddleSummary> = emptyList(),
    val publicQuery: String = "",
    val members: List<MemberSummary> = emptyList(),
    val userSearchQuery: String = "",
    val userSearchResults: List<UserSearchResult> = emptyList(),
    val busy: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("LargeClass")
class ChatViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val repository: ChatRepository,
        private val activeConversationTracker: ActiveConversationTracker,
        private val draftRepository: DraftRepository,
        private val muteRepository: MuteRepository,
        private val linkPreviewRepository: LinkPreviewRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(ChatUiState())
        val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

        val waddles: StateFlow<List<WaddleEntity>> =
            repository
                .observeWaddles()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val channels: StateFlow<List<ChannelEntity>> =
            mutableState
                .flatMapLatest { state ->
                    state.selectedWaddleId?.let(repository::observeChannels) ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val messages: StateFlow<List<MessageEntity>> =
            mutableState
                .flatMapLatest { state ->
                    state.selectedChannelId?.let(repository::observeMessages) ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val dmConversations: StateFlow<List<DmConversationEntity>> =
            repository
                .observeDmConversations()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val dmMessages: StateFlow<List<DmMessageEntity>> =
            mutableState
                .flatMapLatest { state ->
                    state.selectedDmPeerJid?.let(repository::observeDmMessages) ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val reactions: StateFlow<List<ReactionSummary>> =
            mutableState
                .flatMapLatest { state ->
                    state.selectedChannelId?.let(repository::observeReactionSummaries) ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val dmReactions: StateFlow<List<ReactionSummary>> =
            mutableState
                .flatMapLatest { state ->
                    state.selectedDmPeerJid?.let(repository::observeDmReactionSummaries) ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val displayedSummaries: StateFlow<List<DeliverySummary>> =
            repository
                .observeDisplayedSummaries()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val roomTyping: StateFlow<Map<String, Set<String>>> =
            repository
                .observeRoomTyping()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        val directTyping: StateFlow<Map<String, Boolean>> =
            repository
                .observeDirectTyping()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        val channelDraft: StateFlow<String> =
            mutableState
                .flatMapLatest { uiState ->
                    uiState.selectedChannelId?.let(draftRepository::observeChannelDraft) ?: flowOf("")
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

        val directDraft: StateFlow<String> =
            mutableState
                .flatMapLatest { uiState ->
                    uiState.selectedDmPeerJid?.let(draftRepository::observeDirectDraft) ?: flowOf("")
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

        fun setChannelDraft(text: String) {
            val channelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch { draftRepository.setChannelDraft(channelId, text) }
        }

        fun setDirectDraft(text: String) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch { draftRepository.setDirectDraft(peerJid, text) }
        }

        val mutedConversations: StateFlow<Set<String>> =
            muteRepository.muted
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

        val presences: StateFlow<Map<String, Boolean>> =
            repository
                .observePresences()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        val linkPreviews: StateFlow<Map<String, LinkPreview>> = linkPreviewRepository.previews

        fun requestLinkPreview(url: String) {
            linkPreviewRepository.requestPreview(url)
        }

        fun toggleRoomMute(roomJid: String) {
            val key = MuteRepository.roomKey(roomJid)
            viewModelScope.launch {
                val current = muteRepository.snapshot()
                muteRepository.setMuted(key, key !in current)
            }
        }

        fun toggleDirectMute(peerJid: String) {
            val key = MuteRepository.directKey(peerJid)
            viewModelScope.launch {
                val current = muteRepository.snapshot()
                muteRepository.setMuted(key, key !in current)
            }
        }

        private val mutableRoomReply = MutableStateFlow<String?>(null)
        val replyToMessageId: StateFlow<String?> = mutableRoomReply.asStateFlow()
        private val mutableDirectReply = MutableStateFlow<String?>(null)
        val replyToDirectMessageId: StateFlow<String?> = mutableDirectReply.asStateFlow()

        fun startRoomReply(message: MessageEntity) {
            mutableRoomReply.value = message.serverId ?: message.id
        }

        fun clearRoomReply() {
            mutableRoomReply.value = null
        }

        fun startDirectReply(message: DmMessageEntity) {
            mutableDirectReply.value = message.serverId ?: message.id
        }

        fun clearDirectReply() {
            mutableDirectReply.value = null
        }

        private var connectedSessionId: String? = null
        private var currentSession: AuthSession? = null

        // Declared at the bottom so every StateFlow this block captures
        // (in particular [channels]) has been initialized by the time the
        // coroutine body runs. viewModelScope uses Dispatchers.Main.immediate,
        // which would otherwise start the body synchronously during <init>
        // and NPE on the not-yet-assigned property.
        init {
            viewModelScope.launch {
                combine(mutableState, channels) { uiState, channelList ->
                    when {
                        uiState.mode == ChatMode.DirectMessages && uiState.selectedDmPeerJid != null -> {
                            ActiveConversation.Direct(uiState.selectedDmPeerJid)
                        }

                        uiState.mode == ChatMode.Rooms && uiState.selectedChannelId != null -> {
                            val roomJid =
                                channelList
                                    .firstOrNull { it.id == uiState.selectedChannelId }
                                    ?.roomJid
                            roomJid?.let(ActiveConversation::Room)
                        }

                        else -> {
                            null
                        }
                    }
                }.distinctUntilChanged()
                    .collect { active -> activeConversationTracker.setActive(active) }
            }
        }

        fun showRooms() {
            mutableState.update { it.copy(mode = ChatMode.Rooms, error = null) }
        }

        fun showDirectMessages() {
            mutableState.update { it.copy(mode = ChatMode.DirectMessages, error = null) }
        }

        fun start(session: AuthSession) {
            currentSession = session
            if (connectedSessionId == session.stored.sessionId) {
                return
            }
            viewModelScope.launch {
                runCatching {
                    repository.connect(session.stored)
                    repository.refreshWaddles(session.stored)
                    connectedSessionId = session.stored.sessionId
                }.onFailure(::showError)
            }
        }

        fun refresh(session: AuthSession) {
            currentSession = session
            viewModelScope.launch {
                runCatching {
                    repository.refreshWaddles(session.stored)
                    mutableState.value.selectedWaddleId?.let { waddleId ->
                        repository.refreshChannels(session.stored, waddleId)
                    }
                    mutableState.value.selectedChannelId?.let { channelId ->
                        repository.refreshMessages(channelId)
                    }
                }.onFailure(::showError)
            }
        }

        fun selectWaddle(
            session: AuthSession,
            waddleId: String,
        ) {
            currentSession = session
            mutableState.update {
                it.copy(
                    selectedWaddleId = waddleId,
                    selectedChannelId = null,
                    searchVisible = false,
                    searchQuery = "",
                    error = null,
                )
            }
            viewModelScope.launch {
                runCatching {
                    repository.refreshChannels(session.stored, waddleId)
                }.onFailure(::showError)
            }
        }

        fun clearSelectedChannel() {
            mutableState.update {
                it.copy(
                    selectedChannelId = null,
                    searchVisible = false,
                    searchQuery = "",
                    error = null,
                )
            }
        }

        fun selectChannel(
            waddleId: String,
            channelId: String,
        ) {
            mutableState.update {
                it.copy(
                    selectedWaddleId = waddleId,
                    selectedChannelId = channelId,
                    searchQuery = "",
                    error = null,
                )
            }
            viewModelScope.launch {
                runCatching {
                    val session = currentSession ?: return@runCatching
                    repository.refreshMessages(channelId)
                    // Preload members so @mention autocomplete has data to match against.
                    // Fire-and-forget; failure is non-fatal.
                    runCatching {
                        repository.members(session.stored, waddleId)
                    }.onSuccess { members ->
                        mutableState.update { it.copy(members = members) }
                    }
                }.onFailure(::showError)
            }
        }

        fun loadOlderMessages(session: AuthSession) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.loadOlderMessages(selectedChannelId)
                }.onFailure(::showError)
                currentSession = session
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun toggleSearch() {
            mutableState.update { state ->
                val nextVisible = !state.searchVisible
                state.copy(
                    searchVisible = nextVisible,
                    searchQuery = if (nextVisible) state.searchQuery else "",
                    error = null,
                )
            }
        }

        fun setSearchQuery(query: String) {
            mutableState.update { it.copy(searchQuery = query) }
            val selectedChannelId = mutableState.value.selectedChannelId
            val fullText = query.trim()
            if (selectedChannelId != null && fullText.length >= MIN_REMOTE_SEARCH_LENGTH) {
                viewModelScope.launch {
                    runCatching { repository.searchMessages(selectedChannelId, fullText) }
                        .onFailure(::showError)
                }
            }
        }

        fun setPublicQuery(query: String) {
            mutableState.update { it.copy(publicQuery = query) }
        }

        fun searchDmUsers(
            session: AuthSession,
            query: String,
        ) {
            mutableState.update { it.copy(dmSearchQuery = query) }
            viewModelScope.launch {
                runCatching {
                    repository.searchUsers(session.stored, query)
                }.onSuccess { results ->
                    mutableState.update { it.copy(dmSearchResults = results) }
                }.onFailure(::showError)
            }
        }

        fun selectDirectMessage(
            session: AuthSession,
            peerJid: String,
        ) {
            currentSession = session
            mutableState.update {
                it.copy(
                    mode = ChatMode.DirectMessages,
                    selectedDmPeerJid = peerJid,
                    dmSearchQuery = "",
                    dmSearchResults = emptyList(),
                    error = null,
                )
            }
            viewModelScope.launch {
                runCatching {
                    repository.markDmRead(peerJid)
                    repository.refreshDirectMessages(session.stored, peerJid)
                }.onFailure(::showError)
            }
        }

        fun loadOlderDirectMessages(session: AuthSession) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.loadOlderDirectMessages(session.stored, peerJid)
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun clearDirectMessageSelection() {
            mutableState.update { it.copy(selectedDmPeerJid = null, error = null) }
        }

        fun sendDirectMessage(
            session: AuthSession,
            body: String,
        ) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(sending = true, error = null) }
                runCatching {
                    repository.sendDirectMessage(session.stored, peerJid, body.trim())
                    draftRepository.setDirectDraft(peerJid, "")
                }.onFailure(::showError)
                mutableState.update { it.copy(sending = false) }
            }
        }

        fun sendDirectAttachment(
            session: AuthSession,
            uri: Uri,
            displayName: String?,
            mimeType: String?,
        ) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(sending = true, error = null) }
                runCatching {
                    val attachment = readAttachment(uri, displayName, mimeType)
                    repository.sendDirectAttachment(session.stored, peerJid, attachment)
                }.onFailure(::showError)
                mutableState.update { it.copy(sending = false) }
            }
        }

        fun markDirectDisplayed(messageId: String) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                runCatching { repository.sendDirectDisplayed(peerJid, messageId) }
                    .onFailure { throwable -> WaddleLog.error("Failed to mark direct message displayed.", throwable) }
            }
        }

        fun setDirectComposing(composing: Boolean) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                runCatching { repository.setDirectComposing(peerJid, composing) }
                    .onFailure { throwable -> WaddleLog.error("Failed to send direct chat state.", throwable) }
            }
        }

        fun editDirect(
            messageId: String,
            body: String,
        ) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                runCatching { repository.editDirect(peerJid, messageId, body.trim()) }
                    .onFailure(::showError)
            }
        }

        fun retractDirect(messageId: String) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                runCatching { repository.retractDirect(peerJid, messageId) }
                    .onFailure(::showError)
            }
        }

        fun reactDirect(
            session: AuthSession,
            messageId: String,
            emoji: String,
        ) {
            val peerJid = mutableState.value.selectedDmPeerJid ?: return
            viewModelScope.launch {
                runCatching { repository.reactDirect(peerJid, messageId, session.stored.userId, emoji) }
                    .onFailure(::showError)
            }
        }

        fun loadPublicWaddles(session: AuthSession) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.publicWaddles(session.stored, mutableState.value.publicQuery)
                }.onSuccess { publicWaddles ->
                    mutableState.update { it.copy(publicWaddles = publicWaddles) }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun joinWaddle(
            session: AuthSession,
            waddleId: String,
        ) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.joinWaddle(session.stored, waddleId)
                    repository.publicWaddles(session.stored, mutableState.value.publicQuery)
                }.onSuccess { publicWaddles ->
                    mutableState.update { it.copy(publicWaddles = publicWaddles) }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun createWaddle(
            session: AuthSession,
            name: String,
            description: String?,
            isPublic: Boolean,
        ) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.createWaddle(
                        session = session.stored,
                        name = name.trim(),
                        description = description?.trim()?.ifBlank { null },
                        isPublic = isPublic,
                    )
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun updateSelectedWaddle(
            session: AuthSession,
            name: String?,
            description: String?,
            isPublic: Boolean?,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.updateWaddle(
                        session = session.stored,
                        waddleId = selectedWaddleId,
                        name = name?.trim()?.ifBlank { null },
                        description = description?.trim()?.ifBlank { null },
                        isPublic = isPublic,
                    )
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun deleteSelectedWaddle(session: AuthSession) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.deleteWaddle(session.stored, selectedWaddleId)
                }.onSuccess {
                    mutableState.update {
                        it.copy(
                            selectedWaddleId = null,
                            selectedChannelId = null,
                            searchQuery = "",
                        )
                    }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun createChannel(
            session: AuthSession,
            name: String,
            description: String?,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.createChannel(session.stored, selectedWaddleId, name.trim(), description?.trim()?.ifBlank { null })
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun deleteSelectedChannel(session: AuthSession) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.deleteChannel(session.stored, selectedWaddleId, selectedChannelId)
                    repository.refreshChannels(session.stored, selectedWaddleId)
                }.onSuccess {
                    mutableState.update { it.copy(selectedChannelId = null, searchQuery = "") }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun updateSelectedChannel(
            session: AuthSession,
            name: String?,
            description: String?,
            position: Int?,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.updateChannel(
                        session = session.stored,
                        waddleId = selectedWaddleId,
                        channelId = selectedChannelId,
                        name = name?.trim()?.ifBlank { null },
                        description = description?.trim()?.ifBlank { null },
                        position = position,
                    )
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun loadMembers(session: AuthSession) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.members(session.stored, selectedWaddleId)
                }.onSuccess { members ->
                    mutableState.update { it.copy(members = members) }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun searchUsers(
            session: AuthSession,
            query: String,
        ) {
            mutableState.update { it.copy(userSearchQuery = query) }
            viewModelScope.launch {
                runCatching {
                    repository.searchUsers(session.stored, query)
                }.onSuccess { results ->
                    mutableState.update { it.copy(userSearchResults = results) }
                }.onFailure(::showError)
            }
        }

        fun addMember(
            session: AuthSession,
            userId: String,
            role: String,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.addMember(session.stored, selectedWaddleId, userId, role)
                }.onSuccess { members ->
                    mutableState.update { it.copy(members = members, userSearchResults = emptyList(), userSearchQuery = "") }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun updateMemberRole(
            session: AuthSession,
            userId: String,
            role: String,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.updateMemberRole(session.stored, selectedWaddleId, userId, role)
                }.onSuccess { members ->
                    mutableState.update { it.copy(members = members) }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun removeMember(
            session: AuthSession,
            userId: String,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true, error = null) }
                runCatching {
                    repository.removeMember(session.stored, selectedWaddleId, userId)
                }.onSuccess { members ->
                    mutableState.update { it.copy(members = members) }
                }.onFailure(::showError)
                mutableState.update { it.copy(busy = false) }
            }
        }

        fun send(
            session: AuthSession,
            body: String,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            val replyTo = mutableRoomReply.value
            viewModelScope.launch {
                mutableState.update { it.copy(sending = true, error = null) }
                runCatching {
                    repository.sendMessage(
                        session = session.stored,
                        waddleId = selectedWaddleId,
                        channelId = selectedChannelId,
                        body = body.trim(),
                        replyToMessageId = replyTo,
                    )
                    draftRepository.setChannelDraft(selectedChannelId, "")
                    mutableRoomReply.value = null
                }.onFailure(::showError)
                mutableState.update { it.copy(sending = false) }
            }
        }

        fun sendAttachment(
            session: AuthSession,
            uri: Uri,
            displayName: String?,
            mimeType: String?,
        ) {
            val selectedWaddleId = mutableState.value.selectedWaddleId ?: return
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                mutableState.update { it.copy(sending = true, error = null) }
                runCatching {
                    val attachment = readAttachment(uri, displayName, mimeType)
                    repository.sendAttachment(session.stored, selectedWaddleId, selectedChannelId, attachment)
                }.onFailure(::showError)
                mutableState.update { it.copy(sending = false) }
            }
        }

        fun markDisplayed(messageId: String) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                runCatching { repository.sendDisplayed(selectedChannelId, messageId) }
                    .onFailure { throwable -> WaddleLog.error("Failed to mark message displayed.", throwable) }
            }
        }

        fun setComposing(composing: Boolean) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                runCatching { repository.setComposing(selectedChannelId, composing) }
                    .onFailure { throwable -> WaddleLog.error("Failed to send chat state.", throwable) }
            }
        }

        fun react(
            session: AuthSession,
            messageId: String,
            emoji: String,
        ) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                runCatching { repository.react(selectedChannelId, messageId, session.stored.userId, emoji) }
                    .onFailure(::showError)
            }
        }

        fun edit(
            messageId: String,
            body: String,
        ) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                runCatching { repository.edit(selectedChannelId, messageId, body.trim()) }
                    .onFailure(::showError)
            }
        }

        fun retract(messageId: String) {
            val selectedChannelId = mutableState.value.selectedChannelId ?: return
            viewModelScope.launch {
                runCatching { repository.retract(selectedChannelId, messageId) }
                    .onFailure(::showError)
            }
        }

        fun clearError() {
            mutableState.update { it.copy(error = null) }
        }

        /**
         * Open a DM for the given peer from a deep link (e.g. notification tap).
         * We try to resolve a session on demand so the caller doesn't have to.
         */
        fun openDirectMessageFromIntent(peerJid: String) {
            val session = currentSession ?: return
            selectDirectMessage(session, peerJid)
        }

        /**
         * Open the channel that owns the given room JID from a deep link.
         * Requires the channel to be cached in Room; if it's not yet, we
         * kick a refresh and wait for the flow to pick it up.
         */
        fun openRoomFromIntent(roomJid: String) {
            val session = currentSession ?: return
            viewModelScope.launch {
                runCatching {
                    val channel = repository.channelByRoomJid(roomJid)
                    if (channel != null) {
                        mutableState.update {
                            it.copy(
                                mode = ChatMode.Rooms,
                                selectedWaddleId = channel.waddleId,
                                selectedChannelId = channel.id,
                                error = null,
                            )
                        }
                        repository.refreshMessages(channel.id)
                    }
                }.onFailure(::showError)
            }
        }

        fun reset() {
            connectedSessionId = null
            currentSession = null
            mutableState.value = ChatUiState()
            activeConversationTracker.setActive(null)
        }

        override fun onCleared() {
            activeConversationTracker.setActive(null)
            super.onCleared()
        }

        private fun showError(throwable: Throwable) {
            WaddleLog.error("Chat operation failed.", throwable)
            mutableState.update { it.copy(error = throwable.message ?: throwable::class.java.simpleName) }
        }

        private suspend fun readAttachment(
            uri: Uri,
            displayName: String?,
            mimeType: String?,
        ): OutboundFileAttachment =
            withContext(Dispatchers.IO) {
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes()
                    } ?: error("Could not open selected file.")
                OutboundFileAttachment(
                    name = attachmentName(uri, displayName),
                    mediaType = mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_ATTACHMENT_MEDIA_TYPE,
                    bytes = bytes,
                )
            }

        private fun attachmentName(
            uri: Uri,
            displayName: String?,
        ): String =
            displayName
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "attachment-${System.currentTimeMillis()}"

        private companion object {
            const val DEFAULT_ATTACHMENT_MEDIA_TYPE = "application/octet-stream"
            const val MIN_REMOTE_SEARCH_LENGTH = 2
        }
    }
