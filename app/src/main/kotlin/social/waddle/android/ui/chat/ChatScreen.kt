package social.waddle.android.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import social.waddle.android.data.db.ChannelEntity
import social.waddle.android.data.db.DeliverySummary
import social.waddle.android.data.db.DmConversationEntity
import social.waddle.android.data.db.DmMessageEntity
import social.waddle.android.data.db.MessageEntity
import social.waddle.android.data.db.ReactionSummary
import social.waddle.android.data.db.WaddleEntity
import social.waddle.android.data.model.AuthSession
import social.waddle.android.ui.theme.LocalWaddleColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(
    session: AuthSession,
    viewModel: ChatViewModel,
    onOpenAccount: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    onRoomAttachmentPicked: (Uri, String?, String?) -> Unit = { _, _, _ -> },
    onDirectMessageAttachmentPicked: (Uri, String?, String?) -> Unit = { _, _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val waddles by viewModel.waddles.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val dmConversations by viewModel.dmConversations.collectAsStateWithLifecycle()
    val dmMessages by viewModel.dmMessages.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val dmReactions by viewModel.dmReactions.collectAsStateWithLifecycle()
    val displayedSummaries by viewModel.displayedSummaries.collectAsStateWithLifecycle()
    val roomTyping by viewModel.roomTyping.collectAsStateWithLifecycle()
    val directTyping by viewModel.directTyping.collectAsStateWithLifecycle()
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var activeDialog by rememberSaveable { mutableStateOf<ChatDialog?>(null) }
    val lists =
        ChatScreenLists(
            waddles = waddles,
            channels = channels,
            messages = messages,
            reactions = reactions,
            dmConversations = dmConversations,
            dmMessages = dmMessages,
            dmReactions = dmReactions,
            displayedSummaries = displayedSummaries,
            roomTyping = roomTyping,
            directTyping = directTyping,
        )
    val roomAttachmentHandler: (Uri, String?, String?) -> Unit = { uri, name, mimeType ->
        viewModel.sendAttachment(session, uri, name, mimeType)
        onRoomAttachmentPicked(uri, name, mimeType)
    }
    val directAttachmentHandler: (Uri, String?, String?) -> Unit = { uri, name, mimeType ->
        viewModel.sendDirectAttachment(session, uri, name, mimeType)
        onDirectMessageAttachmentPicked(uri, name, mimeType)
    }

    ChatSelectionEffects(
        session = session,
        state = state,
        waddles = waddles,
        onStart = viewModel::start,
        onSelectWaddle = { viewModel.selectWaddle(session, it) },
    )

    val currentWaddle = waddles.firstOrNull { it.id == state.selectedWaddleId }
    val currentChannel = channels.firstOrNull { it.id == state.selectedChannelId }
    val insideChannel = compact && state.mode == ChatMode.Rooms && state.selectedChannelId != null
    val insideDm = compact && state.mode == ChatMode.DirectMessages && state.selectedDmPeerJid != null
    val showBack = insideChannel || insideDm
    val backAction: (() -> Unit)? =
        when {
            insideChannel -> viewModel::clearSelectedChannel
            insideDm -> viewModel::clearDirectMessageSelection
            else -> null
        }
    BackHandler(enabled = showBack) { backAction?.invoke() }

    ChatScreenScaffold(
        session = session,
        state = state,
        compact = compact,
        currentWaddleName = currentWaddle?.name,
        currentChannelName = currentChannel?.name.takeIf { insideChannel },
        onBack = backAction,
        onOpenAccount = onOpenAccount,
        onShowRooms = viewModel::showRooms,
        onShowDirectMessages = viewModel::showDirectMessages,
        onRefresh = { viewModel.refresh(session) },
        snackbarHost = snackbarHost,
    ) { padding ->
        ChatScreenContent(
            session = session,
            viewModel = viewModel,
            state = state,
            lists = lists,
            compact = compact,
            activeDialog = activeDialog,
            onActiveDialogChange = { activeDialog = it },
            onRoomAttachmentPicked = roomAttachmentHandler,
            onDirectMessageAttachmentPicked = directAttachmentHandler,
            padding = padding,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenScaffold(
    session: AuthSession,
    state: ChatUiState,
    compact: Boolean,
    currentWaddleName: String?,
    currentChannelName: String?,
    onBack: (() -> Unit)?,
    onOpenAccount: () -> Unit,
    onShowRooms: () -> Unit,
    onShowDirectMessages: () -> Unit,
    onRefresh: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            ChatTopBar(
                session = session,
                compact = compact,
                mode = state.mode,
                currentWaddleName = currentWaddleName,
                currentChannelName = currentChannelName,
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenAccount = onOpenAccount,
            )
        },
        bottomBar = {
            if (compact) {
                ChatBottomBar(
                    mode = state.mode,
                    channelSelected = state.selectedChannelId != null,
                    onShowRooms = onShowRooms,
                    onShowDirectMessages = onShowDirectMessages,
                    onOpenAccount = onOpenAccount,
                )
            }
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}

private data class TopBarLabels(
    val title: String,
    val subtitle: String,
)

private fun topBarLabels(
    session: AuthSession,
    compact: Boolean,
    mode: ChatMode,
    currentWaddleName: String?,
    currentChannelName: String?,
): TopBarLabels {
    val fallbackWaddle = currentWaddleName ?: "Waddle"
    if (!compact) {
        return TopBarLabels(title = fallbackWaddle, subtitle = session.jid)
    }
    return when (mode) {
        ChatMode.Rooms -> {
            if (currentChannelName != null) {
                TopBarLabels(
                    title = "#$currentChannelName",
                    subtitle = currentWaddleName ?: session.jid,
                )
            } else {
                TopBarLabels(title = fallbackWaddle, subtitle = session.displayName)
            }
        }

        ChatMode.DirectMessages -> {
            TopBarLabels(title = "Direct messages", subtitle = session.displayName)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    session: AuthSession,
    compact: Boolean,
    mode: ChatMode,
    currentWaddleName: String?,
    currentChannelName: String?,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val labels = topBarLabels(session, compact, mode, currentWaddleName, currentChannelName)
    TopAppBar(
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        title = { ChatTopBarTitle(labels = labels) },
        navigationIcon = { ChatTopBarBack(onBack = onBack) },
        actions = { ChatTopBarActions(compact = compact, session = session, onRefresh = onRefresh, onOpenAccount = onOpenAccount) },
    )
}

@Composable
private fun ChatTopBarTitle(labels: TopBarLabels) {
    Column {
        Text(
            text = labels.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = labels.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatTopBarBack(onBack: (() -> Unit)?) {
    onBack?.let {
        IconButton(onClick = it) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
            )
        }
    }
}

@Composable
private fun ChatTopBarActions(
    compact: Boolean,
    session: AuthSession,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    if (!compact) {
        TextButton(onClick = onOpenAccount) {
            Text(session.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    IconButton(onClick = onRefresh) {
        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
    }
}

@Composable
private fun ChatBottomBar(
    mode: ChatMode,
    channelSelected: Boolean,
    onShowRooms: () -> Unit,
    onShowDirectMessages: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        val itemColors =
            NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = colors.sidebarMuted,
                unselectedTextColor = colors.sidebarMuted,
            )
        NavigationBarItem(
            selected = mode == ChatMode.Rooms,
            onClick = onShowRooms,
            icon = {
                Icon(
                    imageVector = if (mode == ChatMode.Rooms && channelSelected) Icons.Rounded.Tag else Icons.Rounded.Home,
                    contentDescription = "Home",
                )
            },
            label = { Text("Home") },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = mode == ChatMode.DirectMessages,
            onClick = onShowDirectMessages,
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "DMs") },
            label = { Text("DMs") },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = false,
            onClick = onOpenAccount,
            icon = { Icon(Icons.Rounded.Person, contentDescription = "You") },
            label = { Text("You") },
            colors = itemColors,
        )
    }
}

@Composable
private fun ChatScreenContent(
    session: AuthSession,
    viewModel: ChatViewModel,
    state: ChatUiState,
    lists: ChatScreenLists,
    compact: Boolean,
    activeDialog: ChatDialog?,
    onActiveDialogChange: (ChatDialog?) -> Unit,
    onRoomAttachmentPicked: (Uri, String?, String?) -> Unit,
    onDirectMessageAttachmentPicked: (Uri, String?, String?) -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
    ) {
        state.error?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::clearError)
        }
        Box(Modifier.weight(1f)) {
            ChatModeContent(
                session = session,
                viewModel = viewModel,
                state = state,
                lists = lists,
                compact = compact,
                onActiveDialogChange = onActiveDialogChange,
                onRoomAttachmentPicked = onRoomAttachmentPicked,
                onDirectMessageAttachmentPicked = onDirectMessageAttachmentPicked,
            )
        }
        ChatManagementDialogs(
            activeDialog = activeDialog,
            state = state,
            currentWaddle = lists.waddles.firstOrNull { it.id == state.selectedWaddleId },
            currentChannel = lists.channels.firstOrNull { it.id == state.selectedChannelId },
            onDismiss = { onActiveDialogChange(null) },
            onPublicQueryChange = viewModel::setPublicQuery,
            onRefreshPublicWaddles = { viewModel.loadPublicWaddles(session) },
            onJoinWaddle = { viewModel.joinWaddle(session, it) },
            onCreateWaddle = { name, description, isPublic ->
                viewModel.createWaddle(session, name, description, isPublic)
                onActiveDialogChange(null)
            },
            onUpdateWaddle = { name, description, isPublic ->
                viewModel.updateSelectedWaddle(session, name, description, isPublic)
                onActiveDialogChange(null)
            },
            onDeleteWaddle = {
                viewModel.deleteSelectedWaddle(session)
                onActiveDialogChange(null)
            },
            onCreateChannel = { name, description ->
                viewModel.createChannel(session, name, description)
                onActiveDialogChange(null)
            },
            onUpdateChannel = { name, description, position ->
                viewModel.updateSelectedChannel(session, name, description, position)
                onActiveDialogChange(null)
            },
            onDeleteChannel = {
                viewModel.deleteSelectedChannel(session)
                onActiveDialogChange(null)
            },
            onUserSearch = { viewModel.searchUsers(session, it) },
            onAddMember = { userId, role -> viewModel.addMember(session, userId, role) },
            onUpdateMemberRole = { userId, role -> viewModel.updateMemberRole(session, userId, role) },
            onRemoveMember = { userId -> viewModel.removeMember(session, userId) },
        )
    }
}

@Composable
private fun ChatModeContent(
    session: AuthSession,
    viewModel: ChatViewModel,
    state: ChatUiState,
    lists: ChatScreenLists,
    compact: Boolean,
    onActiveDialogChange: (ChatDialog?) -> Unit,
    onRoomAttachmentPicked: (Uri, String?, String?) -> Unit,
    onDirectMessageAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    if (state.mode == ChatMode.DirectMessages) {
        DirectMessagesPane(
            session = session,
            state = state,
            conversations = lists.dmConversations,
            messages = lists.dmMessages,
            reactions = lists.dmReactions,
            displayedSummaries = lists.displayedSummaries,
            typingByPeer = lists.directTyping,
            onSearchUsers = { viewModel.searchDmUsers(session, it) },
            onSelectPeer = { viewModel.selectDirectMessage(session, it) },
            onClearPeer = viewModel::clearDirectMessageSelection,
            onLoadOlder = { viewModel.loadOlderDirectMessages(session) },
            onSend = { viewModel.sendDirectMessage(session, it) },
            onDisplayed = viewModel::markDirectDisplayed,
            onTyping = viewModel::setDirectComposing,
            onEdit = viewModel::editDirect,
            onReact = { messageId, emoji -> viewModel.reactDirect(session, messageId, emoji) },
            onRetract = viewModel::retractDirect,
            onAttachmentPicked = onDirectMessageAttachmentPicked,
        )
    } else {
        RoomChatLayout(
            session = session,
            viewModel = viewModel,
            state = state,
            lists = lists,
            compact = compact,
            onActiveDialogChange = onActiveDialogChange,
            onAttachmentPicked = onRoomAttachmentPicked,
        )
    }
}

@Composable
private fun RoomChatLayout(
    session: AuthSession,
    viewModel: ChatViewModel,
    state: ChatUiState,
    lists: ChatScreenLists,
    compact: Boolean,
    onActiveDialogChange: (ChatDialog?) -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    val channelDraft by viewModel.channelDraft.collectAsStateWithLifecycle()
    val replyToMessageId by viewModel.replyToMessageId.collectAsStateWithLifecycle()
    val mutedConversations by viewModel.mutedConversations.collectAsStateWithLifecycle()
    val mutedRoomJids =
        mutedConversations
            .filter { it.startsWith("room:") }
            .map { it.removePrefix("room:") }
            .toSet()
    val presences by viewModel.presences.collectAsStateWithLifecycle()
    val linkPreviews by viewModel.linkPreviews.collectAsStateWithLifecycle()
    val replyPreview =
        replyToMessageId?.let { targetId ->
            val parent = lists.messages.firstOrNull { it.id == targetId || it.serverId == targetId }
            parent?.let { ReplyPreview(it.senderName ?: "Unknown", it.body.take(200)) }
        }
    val layoutArgs =
        ChatLayoutArgs(
            session = session,
            state = state,
            currentWaddle = lists.waddles.firstOrNull { it.id == state.selectedWaddleId },
            currentChannel = lists.channels.firstOrNull { it.id == state.selectedChannelId },
            waddles = lists.waddles,
            channels = lists.channels,
            messages = filteredMessages(lists.messages, state.searchQuery),
            reactionsByMessageId = lists.reactions.groupBy(ReactionSummary::messageId),
            displayedByMessageId = lists.displayedSummaries.associateBy(DeliverySummary::messageId),
            roomTyping = lists.roomTyping,
            dmConversations = lists.dmConversations,
            onSelectWaddle = { viewModel.selectWaddle(session, it) },
            onSelectChannel = viewModel::selectChannel,
            onOpenDirectMessages = viewModel::showDirectMessages,
            onSelectDirectMessage = { viewModel.selectDirectMessage(session, it) },
            onOpenBrowse = {
                onActiveDialogChange(ChatDialog.BrowsePublicWaddles)
                viewModel.loadPublicWaddles(session)
            },
            onOpenNewWaddle = { onActiveDialogChange(ChatDialog.NewWaddle) },
            onOpenWaddleSettings = { onActiveDialogChange(ChatDialog.WaddleSettings) },
            onOpenNewChannel = { onActiveDialogChange(ChatDialog.NewChannel) },
            onOpenChannelSettings = { onActiveDialogChange(ChatDialog.EditChannel) },
            onOpenMembers = {
                onActiveDialogChange(ChatDialog.Members)
                viewModel.loadMembers(session)
            },
            onToggleSearch = viewModel::toggleSearch,
            onSearchQuery = viewModel::setSearchQuery,
            onLoadOlder = { viewModel.loadOlderMessages(session) },
            onDisplayed = viewModel::markDisplayed,
            onTyping = viewModel::setComposing,
            onSend = { viewModel.send(session, it) },
            onEdit = viewModel::edit,
            onReact = { messageId, emoji -> viewModel.react(session, messageId, emoji) },
            onRetract = viewModel::retract,
            onAttachmentPicked = onAttachmentPicked,
            draftText = channelDraft,
            onDraftChange = viewModel::setChannelDraft,
            replyPreview = replyPreview,
            onStartReply = { message -> viewModel.startRoomReply(message) },
            onClearReply = viewModel::clearRoomReply,
            mutedRoomJids = mutedRoomJids,
            mutedConversationKeys = mutedConversations,
            onToggleRoomMute = viewModel::toggleRoomMute,
            presences = presences,
            mentionSuggestions = state.members.map { it.username },
            linkPreviews = linkPreviews,
            onRequestLinkPreview = viewModel::requestLinkPreview,
        )
    if (compact) {
        CompactChatLayout(args = layoutArgs)
    } else {
        WideChatLayout(args = layoutArgs)
    }
}

private data class ChatScreenLists(
    val waddles: List<WaddleEntity>,
    val channels: List<ChannelEntity>,
    val messages: List<MessageEntity>,
    val reactions: List<ReactionSummary>,
    val dmConversations: List<DmConversationEntity>,
    val dmMessages: List<DmMessageEntity>,
    val dmReactions: List<ReactionSummary>,
    val displayedSummaries: List<DeliverySummary>,
    val roomTyping: Map<String, Set<String>>,
    val directTyping: Map<String, Boolean>,
)

@Composable
private fun ChatSelectionEffects(
    session: AuthSession,
    state: ChatUiState,
    waddles: List<WaddleEntity>,
    onStart: (AuthSession) -> Unit,
    onSelectWaddle: (String) -> Unit,
) {
    LaunchedEffect(session.stored.sessionId) {
        onStart(session)
    }
    LaunchedEffect(waddles, state.selectedWaddleId) {
        if (state.selectedWaddleId == null && waddles.isNotEmpty()) {
            onSelectWaddle(waddles.first().id)
        }
    }
}

private data class ChatLayoutArgs(
    val session: AuthSession,
    val state: ChatUiState,
    val currentWaddle: WaddleEntity?,
    val currentChannel: ChannelEntity?,
    val waddles: List<WaddleEntity>,
    val channels: List<ChannelEntity>,
    val messages: List<MessageEntity>,
    val reactionsByMessageId: Map<String, List<ReactionSummary>>,
    val displayedByMessageId: Map<String, DeliverySummary>,
    val roomTyping: Map<String, Set<String>>,
    val dmConversations: List<DmConversationEntity>,
    val onSelectWaddle: (String) -> Unit,
    val onSelectChannel: (String, String) -> Unit,
    val onOpenDirectMessages: () -> Unit,
    val onSelectDirectMessage: (String) -> Unit,
    val onOpenBrowse: () -> Unit,
    val onOpenNewWaddle: () -> Unit,
    val onOpenWaddleSettings: () -> Unit,
    val onOpenNewChannel: () -> Unit,
    val onOpenChannelSettings: () -> Unit,
    val onOpenMembers: () -> Unit,
    val onToggleSearch: () -> Unit,
    val onSearchQuery: (String) -> Unit,
    val onLoadOlder: () -> Unit,
    val onDisplayed: (String) -> Unit,
    val onTyping: (Boolean) -> Unit,
    val onSend: (String) -> Unit,
    val onEdit: (String, String) -> Unit,
    val onReact: (String, String) -> Unit,
    val onRetract: (String) -> Unit,
    val onAttachmentPicked: (Uri, String?, String?) -> Unit,
    val draftText: String,
    val onDraftChange: (String) -> Unit,
    val replyPreview: ReplyPreview?,
    val onStartReply: (MessageEntity) -> Unit,
    val onClearReply: () -> Unit,
    val mutedRoomJids: Set<String>,
    val mutedConversationKeys: Set<String>,
    val onToggleRoomMute: (String) -> Unit,
    val presences: Map<String, Boolean>,
    val mentionSuggestions: List<String>,
    val linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    val onRequestLinkPreview: (String) -> Unit,
)

@Composable
private fun CompactChatLayout(args: ChatLayoutArgs) {
    if (args.state.selectedChannelId == null) {
        CompactWorkspaceHome(args)
    } else {
        CompactChannelView(args)
    }
}

@Composable
private fun CompactWorkspaceHome(args: ChatLayoutArgs) {
    val waddleSwitcherOpen = rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item { WorkspaceSwitcherHeader(args, waddleSwitcherOpen) }
        item { HorizontalDivider(color = LocalWaddleColors.current.divider) }
        item {
            MobileSectionHeader(
                title = "Channels",
                onAdd = args.onOpenNewChannel.takeIf { args.currentWaddle != null },
            )
        }
        items(args.channels, key = ChannelEntity::id) { channel ->
            MobileChannelRow(
                channel = channel,
                selected = false,
                onClick = {
                    args.state.selectedWaddleId?.let { waddleId ->
                        args.onSelectChannel(waddleId, channel.id)
                    }
                },
            )
        }
        if (args.channels.isEmpty() && args.currentWaddle != null) {
            item { MobileEmptyRow("No channels yet — tap + to create one.") }
        }
        item { MobileActionRow(label = "Browse public waddles", onClick = args.onOpenBrowse) }
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = LocalWaddleColors.current.divider)
        }
        item {
            MobileSectionHeader(
                title = "Direct messages",
                onAdd = args.onOpenDirectMessages,
            )
        }
        items(args.dmConversations, key = DmConversationEntity::peerJid) { conversation ->
            MobileDmRow(
                conversation = conversation,
                online = args.presences[conversation.peerJid] == true,
                muted = "dm:${conversation.peerJid}" in args.mutedConversationKeys,
                onClick = { args.onSelectDirectMessage(conversation.peerJid) },
            )
        }
        if (args.dmConversations.isEmpty()) {
            item { MobileEmptyRow("No direct messages yet — tap + to find someone.") }
        }
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = LocalWaddleColors.current.divider)
        }
        item { MobileActionRow(label = "Manage members", onClick = args.onOpenMembers) }
        item { MobileActionRow(label = "Waddle settings", onClick = args.onOpenWaddleSettings) }
    }
}

@Composable
private fun CompactChannelView(args: ChatLayoutArgs) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MobileChannelToolbar(args)
        if (args.state.searchVisible) {
            SearchBar(
                query = args.state.searchQuery,
                resultCount = args.messages.size,
                onQueryChange = args.onSearchQuery,
                onClose = {
                    args.onSearchQuery("")
                    args.onToggleSearch()
                },
            )
        }
        Timeline(
            session = args.session,
            messages = args.messages,
            reactionsByMessageId = args.reactionsByMessageId,
            displayedByMessageId = args.displayedByMessageId,
            linkPreviews = args.linkPreviews,
            modifier = Modifier.weight(1f),
            onLoadOlder = args.onLoadOlder,
            onDisplayed = args.onDisplayed,
            onEdit = args.onEdit,
            onReact = args.onReact,
            onRetract = args.onRetract,
            onStartReply = args.onStartReply,
            onRequestLinkPreview = args.onRequestLinkPreview,
        )
        Composer(
            sending = args.state.sending,
            enabled = args.state.selectedChannelId != null,
            channelName = args.currentChannel?.name,
            conversationKey = args.state.selectedChannelId.orEmpty(),
            initialText = args.draftText,
            replyPreview = args.replyPreview,
            mentionSuggestions = args.mentionSuggestions,
            onTextChanged = args.onDraftChange,
            onClearReply = args.onClearReply,
            onTyping = args.onTyping,
            onSend = args.onSend,
            onAttachmentPicked = args.onAttachmentPicked,
        )
    }
}

@Composable
private fun WorkspaceSwitcherHeader(
    args: ChatLayoutArgs,
    expanded: androidx.compose.runtime.MutableState<Boolean>,
) {
    val colors = LocalWaddleColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = args.waddles.size > 1) { expanded.value = !expanded.value }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceBadge(name = args.currentWaddle?.name)
        Column(Modifier.weight(1f)) {
            Text(
                text = args.currentWaddle?.name ?: "Waddle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    args.currentWaddle?.memberCount?.let { "$it members" }
                        ?: "Browse and create waddles below",
                style = MaterialTheme.typography.bodySmall,
                color = colors.sidebarMuted.takeIf { false } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (args.waddles.size > 1) {
            IconButton(onClick = { expanded.value = !expanded.value }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Switch waddle")
            }
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                args.waddles.forEach { waddle ->
                    DropdownMenuItem(
                        text = { Text(waddle.name) },
                        onClick = {
                            expanded.value = false
                            args.onSelectWaddle(waddle.id)
                        },
                    )
                }
            }
        } else {
            TextButton(onClick = args.onOpenNewWaddle) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
    }
}

@Composable
private fun WorkspaceBadge(name: String?) {
    val colors = LocalWaddleColors.current
    Surface(
        color = colors.sidebar,
        contentColor = colors.sidebarContent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name?.firstOrNull()?.uppercaseChar()?.toString() ?: "W",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MobileSectionHeader(
    title: String,
    onAdd: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onAdd?.let {
            IconButton(onClick = it) {
                Icon(Icons.Rounded.Add, contentDescription = "Add")
            }
        }
    }
}

@Composable
private fun MobileChannelRow(
    channel: ChannelEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tag,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = channel.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        channel.topic?.takeIf(String::isNotBlank)?.let { topic ->
            Text(
                text = topic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MobileDmRow(
    conversation: DmConversationEntity,
    online: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            AvatarInitial(name = conversation.peerUsername)
            if (online) {
                Surface(
                    color = LocalWaddleColors.current.presenceOnline,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    border =
                        androidx.compose.foundation.BorderStroke(
                            2.dp,
                            MaterialTheme.colorScheme.surface,
                        ),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp),
                ) {}
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = conversation.peerUsername,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (muted) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = conversation.lastMessageBody ?: conversation.peerJid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conversation.unreadCount > 0 && !muted) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = conversation.unreadCount.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MobileActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MobileEmptyRow(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MobileChannelToolbar(args: ChatLayoutArgs) {
    val typers =
        args.currentChannel
            ?.roomJid
            ?.let { roomJid -> args.roomTyping[roomJid].orEmpty() }
            .orEmpty()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text =
                    if (typers.isNotEmpty()) {
                        "${typers.take(TYPING_NAME_LIMIT).joinToString()} typing…"
                    } else {
                        args.currentChannel?.topic?.takeIf(String::isNotBlank)
                            ?: "Channel details"
                    },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = args.onToggleSearch,
                enabled = args.currentChannel != null,
            ) {
                Icon(
                    imageVector = if (args.state.searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (args.state.searchVisible) "Close search" else "Search messages",
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Channel options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val muted = args.currentChannel?.let { channel -> channel.roomJid in args.mutedRoomJids } == true
                DropdownMenuItem(
                    text = { Text(if (muted) "Unmute channel" else "Mute channel") },
                    onClick = {
                        menuOpen = false
                        args.currentChannel?.roomJid?.let(args.onToggleRoomMute)
                    },
                    enabled = args.currentChannel != null,
                )
                DropdownMenuItem(
                    text = { Text("Channel settings") },
                    onClick = {
                        menuOpen = false
                        args.onOpenChannelSettings()
                    },
                    enabled = args.currentChannel != null,
                )
                DropdownMenuItem(
                    text = { Text("Members") },
                    onClick = {
                        menuOpen = false
                        args.onOpenMembers()
                    },
                    enabled = args.currentWaddle != null,
                )
                DropdownMenuItem(
                    text = { Text("New channel") },
                    onClick = {
                        menuOpen = false
                        args.onOpenNewChannel()
                    },
                    enabled = args.currentWaddle != null,
                )
                DropdownMenuItem(
                    text = { Text("Browse waddles") },
                    onClick = {
                        menuOpen = false
                        args.onOpenBrowse()
                    },
                )
            }
        }
    }
}

@Composable
private fun WideChatLayout(args: ChatLayoutArgs) {
    Row(Modifier.fillMaxSize()) {
        WorkspaceSidebar(
            args = args,
            modifier =
                Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
        )
        VerticalDivider()
        Column(Modifier.weight(1f)) {
            ChannelHeader(args)
            if (args.state.searchVisible) {
                SearchBar(
                    query = args.state.searchQuery,
                    resultCount = args.messages.size,
                    onQueryChange = args.onSearchQuery,
                    onClose = {
                        args.onSearchQuery("")
                        args.onToggleSearch()
                    },
                )
            }
            Timeline(
                session = args.session,
                messages = args.messages,
                reactionsByMessageId = args.reactionsByMessageId,
                displayedByMessageId = args.displayedByMessageId,
                linkPreviews = args.linkPreviews,
                modifier = Modifier.weight(1f),
                onLoadOlder = args.onLoadOlder,
                onDisplayed = args.onDisplayed,
                onEdit = args.onEdit,
                onReact = args.onReact,
                onRetract = args.onRetract,
                onStartReply = args.onStartReply,
                onRequestLinkPreview = args.onRequestLinkPreview,
            )
            Composer(
                sending = args.state.sending,
                enabled = args.state.selectedChannelId != null,
                channelName = args.currentChannel?.name,
                conversationKey = args.state.selectedChannelId.orEmpty(),
                initialText = args.draftText,
                replyPreview = args.replyPreview,
                mentionSuggestions = args.mentionSuggestions,
                onTextChanged = args.onDraftChange,
                onClearReply = args.onClearReply,
                onTyping = args.onTyping,
                onSend = args.onSend,
                onAttachmentPicked = args.onAttachmentPicked,
            )
        }
    }
}

@Composable
private fun WorkspaceSidebar(
    args: ChatLayoutArgs,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWaddleColors.current
    Surface(
        color = colors.sidebar,
        contentColor = colors.sidebarContent,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { WorkspaceHeader(args) }
            item { SidebarSectionHeader(title = "Waddles", action = "New", onAction = args.onOpenNewWaddle) }
            item { SidebarSectionHeader(title = "Discover", action = "Browse", onAction = args.onOpenBrowse) }
            items(args.waddles, key = WaddleEntity::id) { waddle ->
                SidebarItem(
                    label = waddle.name,
                    supporting = "${waddle.memberCount} members",
                    selected = args.state.selectedWaddleId == waddle.id,
                    onClick = { args.onSelectWaddle(waddle.id) },
                )
            }
            if (args.waddles.isEmpty()) {
                item { SidebarEmpty("No waddles yet") }
            }
            item { SidebarSectionHeader(title = "Channels", action = "New", onAction = args.onOpenNewChannel) }
            items(args.channels, key = ChannelEntity::id) { channel ->
                SidebarItem(
                    label = "# ${channel.name}",
                    supporting = channel.topic,
                    selected = args.state.selectedChannelId == channel.id,
                    onClick = {
                        args.state.selectedWaddleId?.let { waddleId -> args.onSelectChannel(waddleId, channel.id) }
                    },
                )
            }
            if (args.channels.isEmpty()) {
                item { SidebarEmpty("No channels yet") }
            }
            item { SidebarSectionHeader(title = "Direct messages", action = "Find", onAction = args.onOpenDirectMessages) }
            items(args.dmConversations, key = DmConversationEntity::peerJid) { conversation ->
                SidebarItem(
                    label = conversation.peerUsername,
                    supporting = conversation.lastMessageBody ?: conversation.peerJid,
                    selected = false,
                    onClick = { args.onSelectDirectMessage(conversation.peerJid) },
                )
            }
            if (args.dmConversations.isEmpty()) {
                item { SidebarEmpty("No direct messages yet") }
            }
            item { SidebarFooter(args) }
        }
    }
}

@Composable
private fun WorkspaceHeader(args: ChatLayoutArgs) {
    val colors = LocalWaddleColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceBadge(name = args.currentWaddle?.name)
        Column(Modifier.weight(1f)) {
            Text(
                text = args.currentWaddle?.name ?: "Waddle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.sidebarContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = args.session.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = colors.sidebarMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SidebarSectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.sidebarMuted,
        )
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(action, color = colors.sidebarContent)
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    val rowColor = if (selected) colors.sidebarSelected else Color.Transparent
    val contentColor = if (selected) colors.sidebarSelectedContent else colors.sidebarContent
    Surface(
        color = rowColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) contentColor else colors.sidebarMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SidebarEmpty(text: String) {
    val colors = LocalWaddleColors.current
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = colors.sidebarMuted,
    )
}

@Composable
private fun SidebarFooter(args: ChatLayoutArgs) {
    Column(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SidebarItem(
            label = "Waddle settings",
            supporting = "Name, visibility, deletion",
            selected = false,
            onClick = args.onOpenWaddleSettings,
        )
        SidebarItem(
            label = "Members",
            supporting = "Manage this waddle",
            selected = false,
            onClick = args.onOpenMembers,
        )
    }
}

@Composable
private fun ChannelHeader(args: ChatLayoutArgs) {
    val typers =
        args.currentChannel
            ?.roomJid
            ?.let { roomJid -> args.roomTyping[roomJid].orEmpty() }
            .orEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = args.currentChannel?.let { "#${it.name}" } ?: "Select a channel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (typers.isNotEmpty()) {
                            "${typers.take(TYPING_NAME_LIMIT).joinToString()} typing..."
                        } else {
                            args.currentWaddle?.name ?: "No waddle selected"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = args.onToggleSearch,
                enabled = args.currentChannel != null,
            ) {
                Icon(
                    imageVector = if (args.state.searchVisible) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (args.state.searchVisible) "Close search" else "Search messages",
                )
            }
            TextButton(onClick = args.onOpenBrowse) {
                Text("Browse")
            }
            TextButton(
                onClick = args.onOpenNewChannel,
                enabled = args.currentWaddle != null,
            ) {
                Text("New")
            }
            TextButton(
                onClick = args.onOpenChannelSettings,
                enabled = args.currentChannel != null,
            ) {
                Text("Edit")
            }
            TextButton(
                onClick = args.onOpenMembers,
                enabled = args.currentWaddle != null,
            ) {
                Text("Members")
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search loaded messages") },
            supportingText = {
                if (query.isNotBlank()) {
                    Text("$resultCount matching messages")
                }
            },
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Close search")
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Timeline(
    session: AuthSession,
    messages: List<MessageEntity>,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
    onDisplayed: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember {
        derivedStateOf {
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            lastVisible >= messages.lastIndex.coerceAtLeast(0)
        }
    }

    TimelineAutoScrollEffects(
        messages = messages,
        listState = listState,
        atBottom = atBottom,
        sessionUserId = session.stored.userId,
        onDisplayed = onDisplayed,
    )

    Box(modifier = modifier.fillMaxWidth()) {
        TimelinePullRefreshList(
            listState = listState,
            messages = messages,
            reactionsByMessageId = reactionsByMessageId,
            displayedByMessageId = displayedByMessageId,
            session = session,
            linkPreviews = linkPreviews,
            onLoadOlder = onLoadOlder,
            onEdit = onEdit,
            onReact = onReact,
            onRetract = onRetract,
            onStartReply = onStartReply,
            onRequestLinkPreview = onRequestLinkPreview,
        )
        if (!atBottom && messages.isNotEmpty()) {
            JumpToLatestButton(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
            )
        }
    }
}

@Composable
private fun TimelineAutoScrollEffects(
    messages: List<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    atBottom: Boolean,
    sessionUserId: String,
    onDisplayed: (String) -> Unit,
) {
    val latestReadable = messages.lastOrNull { it.senderId != sessionUserId && !it.retracted }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(latestReadable?.id) {
        latestReadable?.messageKey?.let(onDisplayed)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelinePullRefreshList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: List<MessageEntity>,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    session: AuthSession,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    onLoadOlder: () -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    var pullRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    val parentLookup = remember(messages) { messages.associateBy { it.serverId ?: it.id } }
    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = {
            pullRefreshing = true
            onLoadOlder()
            // No completion signal from the MAM fetch, so we drop the spinner
            // after a short window. New messages animate in independently.
            scope.launch {
                kotlinx.coroutines.delay(PULL_REFRESH_LINGER_MILLIS)
                pullRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item { EmptyState("No messages yet") }
            }
            items(messages, key = MessageEntity::id) { message ->
                TimelineMessageItem(
                    message = message,
                    parentLookup = parentLookup,
                    reactionsByMessageId = reactionsByMessageId,
                    displayedByMessageId = displayedByMessageId,
                    session = session,
                    linkPreviews = linkPreviews,
                    onEdit = onEdit,
                    onReact = onReact,
                    onRetract = onRetract,
                    onStartReply = onStartReply,
                    onRequestLinkPreview = onRequestLinkPreview,
                )
            }
        }
    }
}

@Composable
private fun TimelineMessageItem(
    message: MessageEntity,
    parentLookup: Map<String, MessageEntity>,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    session: AuthSession,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    val messageReactions =
        reactionsByMessageId[message.id].orEmpty() +
            reactionsByMessageId[message.serverId].orEmpty()
    val parent = message.replyToMessageId?.let { parentLookup[it] }
    MessageRow(
        session = session,
        message = message,
        parentMessage = parent,
        reactions = messageReactions.distinctBy { it.emoji },
        displayedCount =
            displayedByMessageId[message.id]?.count
                ?: message.serverId?.let { displayedByMessageId[it]?.count }
                ?: 0,
        own = message.senderId == session.stored.userId,
        linkPreviews = linkPreviews,
        onEdit = { body -> onEdit(message.messageKey, body) },
        onReact = { emoji -> onReact(message.messageKey, emoji) },
        onRetract = { onRetract(message.messageKey) },
        onStartReply = { onStartReply(message) },
        onRequestLinkPreview = onRequestLinkPreview,
    )
}

@Composable
private fun JumpToLatestButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(imageVector = Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        Text("Jump to latest")
    }
}

private const val PULL_REFRESH_LINGER_MILLIS = 600L

@Composable
private fun QuotedParent(parent: MessageEntity) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = parent.senderName ?: "unknown",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = parent.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageRow(
    session: AuthSession,
    message: MessageEntity,
    parentMessage: MessageEntity?,
    reactions: List<ReactionSummary>,
    displayedCount: Int,
    own: Boolean,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    onEdit: (String) -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
    onStartReply: () -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editBody by rememberSaveable(message.id) { mutableStateOf(message.body) }
    val mentioned = !own && message.mentions(session.stored.username)

    LaunchedEffect(message.body, editing) {
        if (!editing) {
            editBody = message.body
        }
    }

    if (message.retracted) {
        RetractedMessageRow(message)
        return
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (mentioned) {
                        Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarInitial(name = message.senderName)
        Column(Modifier.weight(1f)) {
            MessageMeta(message = message, own = own, displayedCount = displayedCount)
            Spacer(Modifier.height(4.dp))
            if (editing) {
                EditMessageForm(
                    body = editBody,
                    onBodyChange = { editBody = it },
                    onSave = {
                        val trimmed = editBody.trim()
                        if (trimmed.isNotEmpty() && trimmed != message.body) {
                            onEdit(trimmed)
                        }
                        editing = false
                    },
                    onCancel = {
                        editBody = message.body
                        editing = false
                    },
                )
            } else {
                parentMessage?.let { QuotedParent(it) }
                MessageBody(body = message.body)
                val previewUrl = remember(message.body) { firstWebUrl(message.body) }
                MessageExtras(
                    message = message,
                    linkPreview = previewUrl?.let { linkPreviews[it] },
                    onRequestLinkPreview = onRequestLinkPreview,
                )
                ReactionStrip(reactions = reactions, onReact = onReact)
                MessageActionRow(
                    own = own,
                    pending = message.pending,
                    onStartEdit = { editing = true },
                    onStartReply = onStartReply,
                    onReact = onReact,
                    onRetract = onRetract,
                )
            }
        }
    }
}

@Composable
private fun MessageExtras(
    message: MessageEntity,
    linkPreview: social.waddle.android.data.LinkPreview?,
    onRequestLinkPreview: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    message.broadcastMention?.let { mention ->
        AssistChip(
            onClick = {},
            label = { Text("@$mention") },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    message.sharedFileUrl?.let { url ->
        if (message.sharedFileMediaType?.startsWith("image/") == true) {
            InlineImageAttachment(
                url = url,
                description = message.sharedFileDescription ?: message.sharedFileName,
                onOpen = { uriHandler.openUri(url) },
            )
        } else {
            AssistChip(
                onClick = { uriHandler.openUri(url) },
                label = {
                    Text(
                        text = message.sharedFileName ?: message.sharedFileDescription ?: url,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    message.callInviteId?.let {
        AssistChip(
            onClick = {
                message.callExternalUri?.let(uriHandler::openUri)
            },
            label = {
                Text(
                    text = message.callDescription ?: "Call started",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (message.isSticker) {
        Text(
            text = "Sticker",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    // Only try to preview URLs in plain prose — skip if the message already
    // has a shared-file attachment that rendered an inline preview above.
    if (message.sharedFileUrl == null && !message.isSticker) {
        val firstUrl = remember(message.body) { firstWebUrl(message.body) }
        if (firstUrl != null) {
            LaunchedEffect(firstUrl) { onRequestLinkPreview(firstUrl) }
            linkPreview?.let { preview ->
                LinkPreviewCard(preview = preview, onOpen = { uriHandler.openUri(firstUrl) })
            }
        }
    }
}

@Composable
private fun LinkPreviewCard(
    preview: social.waddle.android.data.LinkPreview,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            preview.imageUrl?.let { image ->
                coil.compose.AsyncImage(
                    model = image,
                    contentDescription = preview.title ?: "Link preview image",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .padding(bottom = 8.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            preview.siteName?.let { site ->
                Text(
                    text = site,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            preview.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            preview.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun firstWebUrl(body: String): String? {
    val match = WEB_URL_PATTERN.find(body) ?: return null
    return match.value.trim().trimEnd(',', '.', ')', '!', '?')
}

private val WEB_URL_PATTERN = Regex("""https?://[^\s<>"']+""")

@Composable
private fun InlineImageAttachment(
    url: String,
    description: String?,
    onOpen: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(fraction = 0.8f)
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = description ?: "Image attachment",
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

@Composable
private fun RetractedMessageRow(message: MessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarInitial(name = message.senderName)
        Column(Modifier.weight(1f)) {
            MessageMeta(message = message, own = false)
            Text(
                text = "This message was deleted.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AvatarInitial(name: String?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MessageMeta(
    message: MessageEntity,
    own: Boolean,
    displayedCount: Int = 0,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = message.senderName ?: "unknown",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatMessageStamp(message.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message.editedAt != null) {
            Text(
                text = "edited",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (own && message.pending) {
            Text(
                text = "sending",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (own && displayedCount > 0) {
            Text(
                text = "seen by $displayedCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBody(body: String) {
    val uriHandler = LocalUriHandler.current
    val trimmed = body.trim()
    val link = trimmed.takeIf(::isWebUrl)
    Text(
        text = if (link == null) styledMessageBody(body) else buildAnnotatedString { append(link) },
        style = MaterialTheme.typography.bodyLarge,
        color = if (link == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        textDecoration = if (link == null) null else TextDecoration.Underline,
        modifier =
            if (link == null) {
                Modifier
            } else {
                Modifier.clickable { uriHandler.openUri(link) }
            },
    )
}

@Composable
private fun ReactionStrip(
    reactions: List<ReactionSummary>,
    onReact: (String) -> Unit,
) {
    if (reactions.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        reactions.forEach { reaction ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable { onReact(reaction.emoji) },
            ) {
                Text(
                    text = "${reaction.emoji} ${reaction.count}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    own: Boolean,
    pending: Boolean,
    onStartEdit: () -> Unit,
    onStartReply: () -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        QUICK_REACTIONS.forEach { emoji ->
            TextButton(
                onClick = { onReact(emoji) },
                enabled = !pending,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(emoji)
            }
        }
        IconButton(onClick = onStartReply, enabled = !pending) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Reply",
            )
        }
        Spacer(Modifier.weight(1f))
        if (own) {
            IconButton(onClick = onStartEdit, enabled = !pending) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit message")
            }
            IconButton(onClick = onRetract, enabled = !pending) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete message")
            }
        }
    }
}

@Composable
private fun EditMessageForm(
    body: String,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            modifier = Modifier.weight(1f),
            minLines = 1,
            maxLines = 5,
        )
        IconButton(onClick = onSave, enabled = body.isNotBlank()) {
            Icon(Icons.Rounded.Done, contentDescription = "Save edit")
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel edit")
        }
    }
}

@Composable
private fun Composer(
    sending: Boolean,
    enabled: Boolean,
    channelName: String?,
    conversationKey: String,
    initialText: String,
    replyPreview: ReplyPreview?,
    mentionSuggestions: List<String> = emptyList(),
    onTextChanged: (String) -> Unit,
    onClearReply: () -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    // Key on conversationKey (channelId or peerJid) — NOT initialText — so the
    // draft flow echoing our own writes back doesn't reset local state and
    // race the user's keystrokes. initialText is only consulted when the
    // conversation first loads its draft (see LaunchedEffect below).
    var body by rememberSaveable(conversationKey) { mutableStateOf(initialText) }
    var composing by rememberSaveable(conversationKey) { mutableStateOf(false) }
    LaunchedEffect(conversationKey, initialText) {
        if (body.isEmpty() && initialText.isNotEmpty()) {
            body = initialText
        }
    }
    val placeholder = channelName?.let { "Message #$it" } ?: "Message"
    val mentionToken = remember(body) { trailingMentionToken(body) }
    val filteredMentions = filteredMentionsFor(mentionToken, mentionSuggestions)
    val state =
        ComposerState(
            body = body,
            composing = composing,
            enabled = enabled,
            sending = sending,
            placeholder = placeholder,
            onBodyChange = { next ->
                body = next
                onTextChanged(next)
                val nextComposing = next.isNotBlank()
                if (nextComposing != composing) {
                    composing = nextComposing
                    onTyping(nextComposing)
                }
            },
            onSend = { trimmed ->
                onSend(trimmed)
                body = ""
                if (composing) {
                    composing = false
                    onTyping(false)
                }
            },
        )

    Column(Modifier.fillMaxWidth()) {
        if (filteredMentions.isNotEmpty() && mentionToken != null) {
            MentionSuggestionStrip(
                suggestions = filteredMentions,
                onSelect = { pick ->
                    val replaced = replaceTrailingMention(body, pick)
                    body = replaced
                    onTextChanged(replaced)
                },
            )
        }
        replyPreview?.let { ReplyPreviewCard(preview = it, onClose = onClearReply) }
        ComposerRow(state = state, onAttachmentPicked = onAttachmentPicked)
    }
}

private data class ComposerState(
    val body: String,
    val composing: Boolean,
    val enabled: Boolean,
    val sending: Boolean,
    val placeholder: String,
    val onBodyChange: (String) -> Unit,
    val onSend: (String) -> Unit,
)

@Composable
private fun filteredMentionsFor(
    mentionToken: String?,
    mentionSuggestions: List<String>,
): List<String> =
    remember(mentionToken, mentionSuggestions) {
        mentionToken
            ?.let { token ->
                mentionSuggestions
                    .filter { name -> name.contains(token, ignoreCase = true) }
                    .take(MAX_MENTION_SUGGESTIONS)
            }.orEmpty()
    }

@Composable
private fun ComposerRow(
    state: ComposerState,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val attachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val attachment = resolveAttachmentDetails(context, uri)
                onAttachmentPicked(attachment.uri, attachment.name, attachment.mimeType)
            }
        }
    val ready = state.enabled && !state.sending
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.body,
            onValueChange = state.onBodyChange,
            enabled = ready,
            modifier = Modifier.weight(1f),
            placeholder = { Text(state.placeholder) },
            maxLines = 5,
        )
        IconButton(
            onClick = { attachmentLauncher.launch(arrayOf("*/*")) },
            enabled = ready,
        ) {
            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach file")
        }
        IconButton(
            onClick = {
                val trimmed = state.body.trim()
                if (trimmed.isEmpty()) return@IconButton
                haptics.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                )
                state.onSend(trimmed)
            },
            enabled = ready && state.body.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
        }
    }
}

data class ReplyPreview(
    val senderName: String,
    val body: String,
)

private const val MAX_MENTION_SUGGESTIONS = 6

/**
 * If the last whitespace-delimited token of [body] starts with `@`, returns the
 * query portion after the `@`. Otherwise null — no mention is being composed.
 */
private fun trailingMentionToken(body: String): String? {
    if (body.isBlank()) return null
    val tail = body.substringAfterLast(' ').substringAfterLast('\n')
    if (!tail.startsWith('@')) return null
    val query = tail.drop(1)
    // Don't trigger for pure '@' with no letters yet — still trigger, but only
    // if the tail is short-ish. Keeps the popup from firing on '@http://...' URLs.
    if (query.length > 32) return null
    return query
}

private fun replaceTrailingMention(
    body: String,
    chosen: String,
): String {
    val lastSpace = maxOf(body.lastIndexOf(' '), body.lastIndexOf('\n'))
    val prefix = if (lastSpace >= 0) body.substring(0, lastSpace + 1) else ""
    return "$prefix@$chosen "
}

@Composable
private fun MentionSuggestionStrip(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            suggestions.forEach { name ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(name) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AvatarInitial(name = name)
                    Text(
                        text = "@$name",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyPreviewCard(
    preview: ReplyPreview,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Replying to ${preview.senderName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = preview.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
            }
        }
    }
}

private data class AttachmentDetails(
    val uri: Uri,
    val name: String?,
    val mimeType: String?,
)

private fun resolveAttachmentDetails(
    context: Context,
    uri: Uri,
): AttachmentDetails {
    val name =
        context.contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.displayNameOrNull()
            } ?: uri.lastPathSegment?.substringAfterLast('/')
    return AttachmentDetails(
        uri = uri,
        name = name,
        mimeType = context.contentResolver.getType(uri),
    )
}

private fun Cursor.displayNameOrNull(): String? {
    val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (columnIndex < 0 || !moveToFirst()) {
        return null
    }
    return getString(columnIndex)
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null)
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val MessageEntity.messageKey: String
    get() = serverId ?: id

private fun MessageEntity.mentions(username: String): Boolean =
    (
        mentions
            ?.lineSequence()
            ?.any { mention -> mention.equals(username, ignoreCase = true) }
            ?: false
    ) ||
        body.contains("@$username", ignoreCase = true) ||
        body.contains("@here", ignoreCase = true) ||
        body.contains("@everyone", ignoreCase = true)

private fun filteredMessages(
    messages: List<MessageEntity>,
    query: String,
): List<MessageEntity> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) {
        return messages
    }
    return messages.filter { message ->
        message.body.contains(trimmed, ignoreCase = true) ||
            message.senderName?.contains(trimmed, ignoreCase = true) == true
    }
}

private fun styledMessageBody(body: String) =
    buildAnnotatedString {
        var cursor = 0
        for (match in XEP_0393_INLINE_PATTERN.findAll(body)) {
            append(body.substring(cursor, match.range.first))
            val token = match.value
            val inner = token.substring(1, token.lastIndex)
            val style =
                when (token.first()) {
                    '*' -> SpanStyle(fontWeight = FontWeight.Bold)
                    '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    '`' -> SpanStyle(fontFamily = FontFamily.Monospace)
                    else -> SpanStyle()
                }
            withStyle(style) { append(inner) }
            cursor = match.range.last + 1
        }
        append(body.substring(cursor))
    }

private fun isWebUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")

private fun formatMessageStamp(value: String): String =
    runCatching {
        Instant
            .parse(value)
            .atZone(ZoneId.systemDefault())
            .format(MESSAGE_STAMP_FORMATTER)
    }.getOrElse { "" }

private val MESSAGE_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val XEP_0393_INLINE_PATTERN = Regex("(`[^`\\n]+`|\\*[^*\\n]+\\*|~[^~\\n]+~)")
private val QUICK_REACTIONS = listOf("+1", "❤️", "😂", "🎉", "👀")
private const val TYPING_NAME_LIMIT = 2
