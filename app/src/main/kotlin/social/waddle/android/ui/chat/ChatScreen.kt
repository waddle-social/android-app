package social.waddle.android.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val activeRoomJids by viewModel.activeRoomJids.collectAsStateWithLifecycle()
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
            activeRoomJids = activeRoomJids,
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
    val navigation = compactBackNavigation(compact, state, viewModel)
    val lightbox = rememberLightboxController()
    BackHandler(enabled = navigation.backAction != null) { navigation.backAction?.invoke() }

    CompositionLocalProvider(LocalLightbox provides lightbox) {
        ChatScreenScaffold(
            session = session,
            state = state,
            compact = compact,
            currentWaddleName = currentWaddle?.name,
            currentChannelName = currentChannel?.name.takeIf { navigation.insideChannel || navigation.insideThread },
            insideThread = navigation.insideThread,
            onBack = navigation.backAction,
            onOpenAccount = onOpenAccount,
            onShowRooms = viewModel::showRooms,
            onShowDirectMessages = viewModel::showDirectMessages,
            onShowDiscover = { viewModel.showDiscover(session) },
            onRefresh = { viewModel.refresh(session) },
            snackbarHost = snackbarHost,
        ) { padding ->
            Column(modifier = Modifier.padding(top = padding.calculateTopPadding())) {
                social.waddle.android.ui.call
                    .IncomingCallBanner(onAccepted = {})
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
                    padding = PaddingValues(bottom = padding.calculateBottomPadding()),
                )
            }
        }
        LightboxOverlay(controller = lightbox)
    }
}

private data class CompactBackNavigation(
    val insideThread: Boolean,
    val insideChannel: Boolean,
    val backAction: (() -> Unit)?,
)

private fun compactBackNavigation(
    compact: Boolean,
    state: ChatUiState,
    viewModel: ChatViewModel,
): CompactBackNavigation {
    if (!compact) return CompactBackNavigation(false, false, null)
    val insideThread = state.mode == ChatMode.Rooms && state.selectedThreadRootId != null
    val insideChannel = state.mode == ChatMode.Rooms && state.selectedChannelId != null && !insideThread
    val insideDm = state.mode == ChatMode.DirectMessages && state.selectedDmPeerJid != null
    val back: (() -> Unit)? =
        when {
            insideThread -> viewModel::closeThread
            insideChannel -> viewModel::clearSelectedChannel
            insideDm -> viewModel::clearDirectMessageSelection
            else -> null
        }
    return CompactBackNavigation(insideThread, insideChannel, back)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenScaffold(
    session: AuthSession,
    state: ChatUiState,
    compact: Boolean,
    currentWaddleName: String?,
    currentChannelName: String?,
    insideThread: Boolean,
    onBack: (() -> Unit)?,
    onOpenAccount: () -> Unit,
    onShowRooms: () -> Unit,
    onShowDirectMessages: () -> Unit,
    onShowDiscover: () -> Unit,
    onRefresh: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val showTopBar = !compact || shouldShowCompactTopBar(state, insideThread)
    Scaffold(
        topBar = {
            if (showTopBar) {
                ChatTopBar(
                    session = session,
                    compact = compact,
                    mode = state.mode,
                    currentWaddleName = currentWaddleName,
                    currentChannelName = currentChannelName,
                    insideThread = insideThread,
                    onBack = onBack,
                    onRefresh = onRefresh,
                    onOpenAccount = onOpenAccount,
                )
            }
        },
        bottomBar = {
            if (compact) {
                ChatBottomBar(
                    mode = state.mode,
                    onShowRooms = onShowRooms,
                    onShowDirectMessages = onShowDirectMessages,
                    onShowDiscover = onShowDiscover,
                )
            }
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}

private fun shouldShowCompactTopBar(
    state: ChatUiState,
    insideThread: Boolean,
): Boolean {
    // On compact (phone) the workspace Home screen owns its own header,
    // so we hide the scaffold's top bar there. Keep it for channels,
    // threads, DMs, and Discover where it provides back / actions.
    if (state.mode == ChatMode.Rooms) {
        return state.selectedChannelId != null || insideThread
    }
    return true
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
    insideThread: Boolean,
): TopBarLabels {
    val fallbackWaddle = currentWaddleName ?: "Waddle"
    if (!compact) {
        return TopBarLabels(title = fallbackWaddle, subtitle = session.jid)
    }
    return when (mode) {
        ChatMode.Rooms -> {
            when {
                insideThread -> {
                    TopBarLabels(
                        title = "Thread",
                        subtitle = currentChannelName?.let { "#$it" } ?: fallbackWaddle,
                    )
                }

                currentChannelName != null -> {
                    TopBarLabels(
                        title = "#$currentChannelName",
                        subtitle = currentWaddleName ?: session.jid,
                    )
                }

                else -> {
                    TopBarLabels(title = fallbackWaddle, subtitle = session.displayName)
                }
            }
        }

        ChatMode.DirectMessages -> {
            TopBarLabels(title = "Direct messages", subtitle = session.displayName)
        }

        ChatMode.Discover -> {
            TopBarLabels(title = "Discover", subtitle = "Public communities")
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
    insideThread: Boolean,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val labels = topBarLabels(session, compact, mode, currentWaddleName, currentChannelName, insideThread)
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
    if (compact) {
        IconButton(onClick = onOpenAccount) {
            Icon(Icons.Rounded.Person, contentDescription = "Account")
        }
    }
}

@Composable
private fun ChatBottomBar(
    mode: ChatMode,
    onShowRooms: () -> Unit,
    onShowDirectMessages: () -> Unit,
    onShowDiscover: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ChatBottomTab(
                label = "Home",
                icon = Icons.Rounded.Home,
                selected = mode == ChatMode.Rooms,
                mutedTint = colors.sidebarMuted,
                onClick = onShowRooms,
            )
            ChatBottomTab(
                label = "DMs",
                icon = Icons.Rounded.ChatBubbleOutline,
                selected = mode == ChatMode.DirectMessages,
                mutedTint = colors.sidebarMuted,
                onClick = onShowDirectMessages,
            )
            ChatBottomTab(
                label = "Discover",
                icon = Icons.Rounded.Explore,
                selected = mode == ChatMode.Discover,
                mutedTint = colors.sidebarMuted,
                onClick = onShowDiscover,
            )
        }
    }
}

@Composable
private fun ChatBottomTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    mutedTint: Color,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else mutedTint
    Column(
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 10.sp,
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
    if (state.mode == ChatMode.Discover) {
        DiscoverPane(
            state = state,
            onQueryChange = viewModel::setPublicQuery,
            onRefresh = { viewModel.loadPublicWaddles(session) },
            onJoin = { viewModel.joinWaddle(session, it) },
            onOpenNewWaddle = { onActiveDialogChange(ChatDialog.NewWaddle) },
        )
        return
    }
    if (state.mode == ChatMode.DirectMessages) {
        val replyToDirectMessageId by viewModel.replyToDirectMessageId.collectAsStateWithLifecycle()
        val directReplyPreview =
            replyToDirectMessageId
                ?.let { targetId ->
                    lists.dmMessages.firstOrNull { it.id == targetId || it.serverId == targetId }
                }?.let { ReplyPreview(it.senderName, it.body.take(200)) }
        DirectMessagesPane(
            session = session,
            state = state,
            conversations = lists.dmConversations,
            messages = lists.dmMessages,
            reactions = lists.dmReactions,
            displayedSummaries = lists.displayedSummaries,
            typingByPeer = lists.directTyping,
            replyPreview = directReplyPreview,
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
            onStartReply = viewModel::startDirectReply,
            onClearReply = viewModel::clearDirectReply,
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
            val parent = lists.messages.firstOrNull { it.matchesReference(targetId) }
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
            // Pass the full message list through — the forum card view and the
            // text timeline both need *all* messages visible so thread-meta
            // counts include replies. The per-view filtering (topics-only for
            // forums) happens at render time.
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
            onOpenThread = viewModel::openThread,
            onOpenDiscover = { viewModel.showDiscover(session) },
            activeRoomJids = lists.activeRoomJids,
            onSendForumTopic = { title, body -> viewModel.sendForumTopic(session, title, body) },
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
    val activeRoomJids: Set<String>,
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
    val onOpenThread: (String) -> Unit,
    val onOpenDiscover: () -> Unit,
    val activeRoomJids: Set<String>,
    val onSendForumTopic: (title: String, body: String) -> Unit,
)

@Composable
private fun CompactChatLayout(args: ChatLayoutArgs) {
    when {
        args.state.selectedThreadRootId != null -> CompactThreadView(args)
        args.state.selectedChannelId == null -> CompactWorkspaceHome(args)
        else -> CompactChannelView(args)
    }
}

@Composable
private fun CompactWorkspaceHome(args: ChatLayoutArgs) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        HomeHeader(args = args)
        WaddleRail(args = args)
        MobileSectionHeader(
            title = "Channels",
            onAdd = args.onOpenNewChannel.takeIf { args.currentWaddle != null },
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(args.channels, key = ChannelEntity::id) { channel ->
                HomeChannelRow(
                    channel = channel,
                    muted = channel.roomJid in args.mutedRoomJids,
                    hasActivity = channel.roomJid in args.activeRoomJids,
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
            if (args.currentWaddle == null && args.waddles.isEmpty()) {
                item {
                    MobileEmptyRow(
                        "You haven't joined any waddles yet — tap Discover to find one.",
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(args: ChatLayoutArgs) {
    val colors = LocalWaddleColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WaddleBadge(
            name = args.currentWaddle?.name,
            color = args.currentWaddle?.let(::waddleAccent) ?: MaterialTheme.colorScheme.primary,
            size = 36.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = args.currentWaddle?.name ?: "Waddle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    args.currentWaddle?.memberCount?.let { "$it members" }
                        ?: "Discover communities to join",
                style = MaterialTheme.typography.bodySmall,
                color = colors.sidebarMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = args.onOpenWaddleSettings, enabled = args.currentWaddle != null) {
            Icon(Icons.Rounded.Notifications, contentDescription = "Waddle settings")
        }
    }
}

@Composable
private fun WaddleRail(args: ChatLayoutArgs) {
    val scrollState = rememberScrollState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        args.waddles.forEach { waddle ->
            WaddleRailItem(
                waddle = waddle,
                active = waddle.id == args.state.selectedWaddleId,
                onClick = { args.onSelectWaddle(waddle.id) },
            )
        }
        WaddleRailAdd(onClick = args.onOpenDiscover)
    }
}

@Composable
private fun WaddleRailItem(
    waddle: WaddleEntity,
    active: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    val badgeModifier =
        if (active) {
            Modifier.border(2.dp, accent, RoundedCornerShape(14.dp))
        } else {
            Modifier
        }
    Box(
        modifier =
            Modifier
                .padding(if (active) 3.dp else 0.dp)
                .then(badgeModifier)
                .padding(if (active) 3.dp else 0.dp)
                .background(background, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
    ) {
        WaddleBadge(
            name = waddle.name,
            color = waddleAccent(waddle),
            size = 46.dp,
        )
    }
}

@Composable
private fun WaddleRailAdd(onClick: () -> Unit) {
    val colors = LocalWaddleColors.current
    Box(
        modifier =
            Modifier
                .size(46.dp)
                .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = "Add waddle",
            tint = colors.sidebarMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun WaddleBadge(
    name: String?,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(size / 3.3f),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name?.firstOrNull()?.uppercaseChar()?.toString() ?: "W",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun HomeChannelRow(
    channel: ChannelEntity,
    muted: Boolean,
    hasActivity: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWaddleColors.current
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
            imageVector = if (channel.channelType == "forum") Icons.Rounded.Forum else Icons.Rounded.Tag,
            contentDescription = if (channel.channelType == "forum") "Forum channel" else null,
            tint = if (hasActivity && !muted) MaterialTheme.colorScheme.primary else colors.sidebarMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = channel.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (hasActivity && !muted) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasActivity && !muted) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (muted) {
            Icon(
                imageVector = Icons.Rounded.NotificationsOff,
                contentDescription = "Muted",
                tint = colors.sidebarMuted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Forum topic list: a stream of tappable topic cards. Each card is a
 * self-contained unit with the topic title, an excerpt of the opening post,
 * the author / timestamp, and a participant stack + reply footer. Tapping a
 * card opens the thread. Inspired by Discord forum channels and Reddit's
 * post list — a "forum" should *feel* like a forum, not a filtered chat.
 */
@Composable
private fun ForumTopicList(
    topics: List<MessageEntity>,
    threadMetaByRoot: Map<String, ThreadMeta>,
    modifier: Modifier = Modifier,
    onOpenTopic: (String) -> Unit,
) {
    val colors = LocalWaddleColors.current
    // `topics` is actually the full message list for the channel; filter to
    // roots here (topics only) so the card feed doesn't render replies
    // inline — they belong in the thread view. We do need the full list
    // upstream so thread-meta counts include the replies.
    val roots =
        remember(topics) {
            topics.filter { it.forumTopicTitle != null || it.forumReplyThreadId == null }
        }
    if (roots.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No topics yet. Start one below 👇",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.sidebarMuted,
            )
        }
        return
    }
    val sortedTopics =
        remember(roots, threadMetaByRoot) {
            roots.sortedByDescending { topic ->
                threadMetaByRoot[topic.threadKey]?.lastReplyAt ?: topic.createdAt
            }
        }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(sortedTopics, key = MessageEntity::id) { topic ->
            ForumTopicCard(
                topic = topic,
                meta = threadMetaByRoot[topic.threadKey],
                onClick = { onOpenTopic(topic.threadKey) },
            )
        }
    }
}

@Composable
private fun ForumTopicCard(
    topic: MessageEntity,
    meta: ThreadMeta?,
    onClick: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    val replyCount = meta?.count ?: 0
    val lastActivity =
        remember(meta?.lastReplyAt, topic.createdAt) {
            formatRelativeTimestamp(meta?.lastReplyAt ?: topic.createdAt)
        }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.composerSurface,
        border = BorderStroke(1.dp, colors.divider),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            ForumTopicHeader(topic = topic)
            Spacer(Modifier.height(8.dp))
            topic.forumTopicTitle?.takeIf(String::isNotBlank)?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = topic.body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.sidebarMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            ForumTopicFooter(
                replyCount = replyCount,
                participantNames = meta?.participantNames.orEmpty(),
                lastActivity = lastActivity,
            )
        }
    }
}

@Composable
private fun ForumTopicHeader(topic: MessageEntity) {
    val colors = LocalWaddleColors.current
    val author = topic.senderName ?: "someone"
    val started = remember(topic.createdAt) { formatRelativeTimestamp(topic.createdAt) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .background(waddleSummaryAccent(author), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = author.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Text(
            text = author,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        started?.let {
            Text(
                text = "·  $it",
                style = MaterialTheme.typography.labelMedium,
                color = colors.sidebarMuted,
            )
        }
    }
}

@Composable
private fun ForumTopicFooter(
    replyCount: Int,
    participantNames: List<String>,
    lastActivity: String?,
) {
    val colors = LocalWaddleColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (participantNames.isNotEmpty()) {
            ThreadParticipantStack(names = participantNames)
        }
        val countText =
            when (replyCount) {
                0 -> "No replies yet"
                1 -> "1 reply"
                else -> "$replyCount replies"
            }
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (replyCount > 0) MaterialTheme.colorScheme.primary else colors.sidebarMuted,
        )
        lastActivity?.takeIf { replyCount > 0 }?.let {
            Text(
                text = "·  $it",
                style = MaterialTheme.typography.labelSmall,
                color = colors.sidebarMuted,
            )
        }
    }
}

private val WADDLE_ACCENT_PALETTE: List<Color> =
    listOf(
        Color(0xFF00C4AB),
        Color(0xFFF97316),
        Color(0xFF3B82F6),
        Color(0xFF8B5CF6),
        Color(0xFFEC4899),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFF6366F1),
    )

private fun waddleAccent(waddle: WaddleEntity): Color = waddleSummaryAccent(waddle.id)

private fun waddleSummaryAccent(id: String): Color {
    val index = (id.hashCode() and 0x7FFFFFFF) % WADDLE_ACCENT_PALETTE.size
    return WADDLE_ACCENT_PALETTE[index]
}

@Composable
private fun CompactChannelView(args: ChatLayoutArgs) {
    val replyCountsByRootKey = remember(args.messages) { args.messages.threadMetaByRoot() }
    val isForum = args.currentChannel?.channelType == "forum"
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
        if (isForum) {
            ForumTopicList(
                topics = args.messages,
                threadMetaByRoot = replyCountsByRootKey,
                modifier = Modifier.weight(1f),
                onOpenTopic = args.onOpenThread,
            )
        } else {
            Timeline(
                session = args.session,
                messages = args.messages,
                reactionsByMessageId = args.reactionsByMessageId,
                displayedByMessageId = args.displayedByMessageId,
                linkPreviews = args.linkPreviews,
                replyCountsByRootKey = replyCountsByRootKey,
                modifier = Modifier.weight(1f),
                onLoadOlder = args.onLoadOlder,
                onDisplayed = args.onDisplayed,
                onEdit = args.onEdit,
                onReact = args.onReact,
                onRetract = args.onRetract,
                onStartReply = args.onStartReply,
                onOpenThread = args.onOpenThread,
                onRequestLinkPreview = args.onRequestLinkPreview,
            )
        }
        Composer(
            sending = args.state.sending,
            enabled = args.state.selectedChannelId != null,
            channelName = args.currentChannel?.name,
            conversationKey = args.state.selectedChannelId.orEmpty(),
            initialText = args.draftText,
            replyPreview = args.replyPreview,
            mentionSuggestions = args.mentionSuggestions,
            forumTopicMode = isForum,
            onTextChanged = args.onDraftChange,
            onClearReply = args.onClearReply,
            onTyping = args.onTyping,
            onSend = args.onSend,
            onSendTopic = args.onSendForumTopic,
            onAttachmentPicked = args.onAttachmentPicked,
        )
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
private fun DiscoverPane(
    state: ChatUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onJoin: (String) -> Unit,
    onOpenNewWaddle: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        DiscoverHeader(onOpenNewWaddle = onOpenNewWaddle)
        DiscoverSearchField(
            query = state.publicQuery,
            busy = state.busy,
            onQueryChange = onQueryChange,
        )
        DiscoverResultsList(
            waddles = state.publicWaddles,
            busy = state.busy,
            onJoin = onJoin,
        )
    }
}

@Composable
private fun DiscoverHeader(onOpenNewWaddle: () -> Unit) {
    val colors = LocalWaddleColors.current
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Discover",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Public communities",
                style = MaterialTheme.typography.bodySmall,
                color = colors.sidebarMuted,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(36.dp).clickable(onClick = onOpenNewWaddle),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = "Create waddle")
            }
        }
    }
}

@Composable
private fun DiscoverSearchField(
    query: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalWaddleColors.current
    Surface(
        color = colors.composerSurface,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.sidebarMuted,
                modifier = Modifier.size(16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search communities", color = colors.sidebarMuted) },
                colors =
                    androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
            )
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun DiscoverResultsList(
    waddles: List<social.waddle.android.data.model.WaddleSummary>,
    busy: Boolean,
    onJoin: (String) -> Unit,
) {
    val colors = LocalWaddleColors.current
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(waddles, key = { it.id }) { waddle ->
            DiscoverRow(waddle = waddle, onJoin = { onJoin(waddle.id) })
        }
        if (waddles.isEmpty() && !busy) {
            item {
                Text(
                    text = "No public communities matched your search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.sidebarMuted,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverRow(
    waddle: social.waddle.android.data.model.WaddleSummary,
    onJoin: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.divider),
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WaddleBadge(
                name = waddle.name,
                color = waddleSummaryAccent(waddle.id),
                size = 44.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = waddle.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                waddle.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sidebarMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val joined = waddle.role != null
            Surface(
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.clickable(enabled = !joined, onClick = onJoin),
            ) {
                Text(
                    text = if (joined) "Joined" else "Join",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CompactThreadView(args: ChatLayoutArgs) {
    val rootKey = args.state.selectedThreadRootId ?: return
    // rootKey might be a stanza-id (our preferred) OR an origin-id (from a
    // legacy peer) — accept either when resolving the root and its replies.
    val root = remember(args.messages, rootKey) { args.messages.firstOrNull { it.matchesReference(rootKey) } }
    val replies =
        remember(args.messages, rootKey, root) {
            val target = root ?: return@remember emptyList()
            args.messages.filter { message ->
                val ref = message.forumReplyThreadId ?: message.replyToMessageId ?: return@filter false
                target.matchesReference(ref)
            }
        }
    val colors = LocalWaddleColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (root == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "This message is no longer available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.sidebarMuted,
                )
            }
        } else {
            ThreadViewContent(
                root = root,
                replies = replies,
                channelName = args.currentChannel?.name,
                session = args.session,
                reactionsByMessageId = args.reactionsByMessageId,
                displayedByMessageId = args.displayedByMessageId,
                linkPreviews = args.linkPreviews,
                modifier = Modifier.weight(1f),
                onEdit = args.onEdit,
                onReact = args.onReact,
                onRetract = args.onRetract,
                onStartReply = args.onStartReply,
                onRequestLinkPreview = args.onRequestLinkPreview,
            )
        }
        Composer(
            sending = args.state.sending,
            enabled = true,
            channelName = root?.senderName ?: args.currentChannel?.name,
            conversationKey = "thread:$rootKey",
            initialText = "",
            replyPreview = args.replyPreview,
            mentionSuggestions = args.mentionSuggestions,
            onTextChanged = { /* drafts not persisted for threads */ },
            onClearReply = args.onClearReply,
            onTyping = args.onTyping,
            onSend = args.onSend,
            onAttachmentPicked = args.onAttachmentPicked,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ThreadViewContent(
    root: MessageEntity,
    replies: List<MessageEntity>,
    channelName: String?,
    session: AuthSession,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    modifier: Modifier = Modifier,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    val colors = LocalWaddleColors.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ThreadViewSubheader(
                root = root,
                channelName = channelName,
                replyCount = replies.size,
                participantNames = replies.mapNotNull { it.senderName }.distinct().take(MAX_THREAD_PARTICIPANTS),
            )
        }
        item { ThreadRootCard(root = root) }
        if (replies.isEmpty()) {
            item {
                Text(
                    text = "Be the first to reply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.sidebarMuted,
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 16.dp),
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = colors.divider)
                    Text(
                        text = if (replies.size == 1) "1 reply" else "${replies.size} replies",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.sidebarMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = colors.divider)
                }
            }
            items(replies, key = MessageEntity::id) { reply ->
                ThreadReplyRow(
                    reply = reply,
                    session = session,
                    reactions =
                        (
                            reactionsByMessageId[reply.id].orEmpty() +
                                reactionsByMessageId[reply.serverId].orEmpty() +
                                reactionsByMessageId[reply.originStanzaId].orEmpty()
                        ).distinctBy { it.emoji },
                    displayedCount =
                        displayedByMessageId[reply.id]?.count
                            ?: reply.serverId?.let { displayedByMessageId[it]?.count }
                            ?: reply.originStanzaId?.let { displayedByMessageId[it]?.count }
                            ?: 0,
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
private fun ThreadViewSubheader(
    root: MessageEntity,
    channelName: String?,
    replyCount: Int,
    participantNames: List<String>,
) {
    val colors = LocalWaddleColors.current
    val started = remember(root.createdAt) { formatRelativeTimestamp(root.createdAt) }
    Column {
        root.forumTopicTitle?.takeIf(String::isNotBlank)?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (participantNames.isNotEmpty()) {
                ThreadParticipantStack(names = participantNames)
            }
            Text(
                text =
                    buildString {
                        append(
                            when (replyCount) {
                                0 -> "No replies yet"
                                1 -> "1 reply"
                                else -> "$replyCount replies"
                            },
                        )
                        val byline = root.senderName?.let { "started by $it" }
                        listOfNotNull(byline, started?.let { "· $it" }, channelName?.let { "in #$it" })
                            .forEach { append("  ·  $it") }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = colors.sidebarMuted,
            )
        }
    }
}

/**
 * The "hero" root card of a thread: visually elevated with a tinted surface
 * and accent left-bar so it reads as the anchor of the discussion rather
 * than just another message.
 */
@Composable
private fun ThreadRootCard(root: MessageEntity) {
    val colors = LocalWaddleColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.accentSoft,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AvatarInitial(name = root.senderName)
                    Column {
                        Text(
                            text = root.senderName ?: "unknown",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatMessageStamp(root.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.sidebarMuted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = root.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Compact reply row used inside the thread view. Similar to MessageRow but
 * without the swipe-to-reply wrapper (the thread composer always targets
 * the root) and without the thread chip (we're already in the thread).
 */
@Suppress("LongParameterList")
@Composable
private fun ThreadReplyRow(
    reply: MessageEntity,
    session: AuthSession,
    reactions: List<ReactionSummary>,
    displayedCount: Int,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    MessageRow(
        session = session,
        message = reply,
        parentMessage = null,
        reactions = reactions,
        displayedCount = displayedCount,
        own = reply.senderId == session.stored.userId,
        linkPreviews = linkPreviews,
        threadMeta = null,
        onEdit = { body -> onEdit(reply.messageKey, body) },
        onReact = { emoji -> onReact(reply.messageKey, emoji) },
        onRetract = { onRetract(reply.messageKey) },
        onStartReply = { onStartReply(reply) },
        onOpenThread = { /* already in thread */ },
        onRequestLinkPreview = onRequestLinkPreview,
    )
}

/**
 * Copy [text] to the system clipboard. We use the framework ClipboardManager
 * directly rather than Compose's LocalClipboardManager — the latter was
 * deprecated in favour of a suspend-capable API, but for a fire-and-forget
 * copy the framework API is simpler.
 */
private fun copyToClipboard(
    context: android.content.Context,
    text: String,
) {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("Waddle message", text))
}

/**
 * Per-thread metadata used to render the "N replies · last from X" chip.
 * [participantNames] holds up to [MAX_THREAD_PARTICIPANTS] unique replier
 * names, newest first; [lastReplyAt] is the server timestamp of the most
 * recent reply (ISO-8601 or null if unknown).
 */
private data class ThreadMeta(
    val count: Int,
    val participantNames: List<String>,
    val lastReplyAt: String?,
)

private const val MAX_THREAD_PARTICIPANTS = 3

/**
 * Build a thread-metadata map keyed by each root's [MessageEntity.threadKey].
 * Incoming replies may reference their parent by either the room-assigned
 * XEP-0359 stanza-id or the sender's origin-id, so we first resolve every ref
 * to the root's canonical threadKey and then group.
 */
private fun List<MessageEntity>.threadMetaByRoot(): Map<String, ThreadMeta> {
    // id-alias → canonical-threadKey map, populated from every message so a
    // reply can find its parent by stanza-id OR origin-id OR local row id.
    val canonicalByRef: Map<String, String> =
        buildMap {
            for (message in this@threadMetaByRoot) {
                val canonical = message.threadKey
                message.serverId?.let { put(it, canonical) }
                message.originStanzaId?.let { put(it, canonical) }
                put(message.id, canonical)
            }
        }
    val grouped =
        asSequence()
            .mapNotNull { reply ->
                val ref = reply.forumReplyThreadId ?: reply.replyToMessageId ?: return@mapNotNull null
                val canonical = canonicalByRef[ref] ?: ref
                canonical to reply
            }.groupBy({ it.first }, { it.second })
    return grouped.mapValues { (_, children) ->
        val sorted = children.sortedByDescending { it.createdAt }
        ThreadMeta(
            count = children.size,
            participantNames =
                sorted
                    .mapNotNull { it.senderName }
                    .distinct()
                    .take(MAX_THREAD_PARTICIPANTS),
            lastReplyAt = sorted.firstOrNull()?.createdAt,
        )
    }
}

/**
 * The id we put on every outgoing room wire reference to this message —
 * `<reply id=…>`, `<reactions id=…>`, `<retract id=…>`, `<replace id=…>`,
 * `<thread>…`, `<thread-reply thread-id=…>`. XEP-0461, XEP-0424, and
 * XEP-0444 require the room-assigned XEP-0359 stanza-id for groupchat
 * references when it is available. Origin-id remains an alias for resolving
 * older incoming references.
 */
private val MessageEntity.threadKey: String
    get() = serverId ?: originStanzaId ?: id

/**
 * Does this message match [key] under any of the identifiers peers might use
 * to point at it: the MUC stanza-id, the origin-id the sender chose, or the
 * local row id?
 */
private fun MessageEntity.matchesReference(key: String): Boolean = serverId == key || originStanzaId == key || id == key

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
                replyCountsByRootKey = remember(args.messages) { args.messages.threadMetaByRoot() },
                modifier = Modifier.weight(1f),
                onLoadOlder = args.onLoadOlder,
                onDisplayed = args.onDisplayed,
                onEdit = args.onEdit,
                onReact = args.onReact,
                onRetract = args.onRetract,
                onStartReply = args.onStartReply,
                onOpenThread = args.onOpenThread,
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
                forumTopicMode = args.currentChannel?.channelType == "forum",
                onTextChanged = args.onDraftChange,
                onClearReply = args.onClearReply,
                onTyping = args.onTyping,
                onSend = args.onSend,
                onSendTopic = args.onSendForumTopic,
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
    replyCountsByRootKey: Map<String, ThreadMeta>,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
    onDisplayed: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onOpenThread: (String) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var flashKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flashKey) {
        if (flashKey != null) {
            kotlinx.coroutines.delay(FLASH_DURATION_MILLIS)
            flashKey = null
        }
    }
    val navigateToMessage: (String) -> Unit = { key ->
        val index = messages.indexOfFirst { it.matchesReference(key) }
        if (index >= 0) {
            scope.launch {
                listState.animateScrollToItem(index)
                flashKey = key
            }
        }
    }
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
            replyCountsByRootKey = replyCountsByRootKey,
            flashKey = flashKey,
            onLoadOlder = onLoadOlder,
            onEdit = onEdit,
            onReact = onReact,
            onRetract = onRetract,
            onStartReply = onStartReply,
            onOpenThread = onOpenThread,
            onNavigateToMessage = navigateToMessage,
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

private const val FLASH_DURATION_MILLIS = 900L

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
    replyCountsByRootKey: Map<String, ThreadMeta>,
    flashKey: String?,
    onLoadOlder: () -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onOpenThread: (String) -> Unit,
    onNavigateToMessage: (String) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    var pullRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    val parentLookup =
        remember(messages) {
            buildMap {
                for (message in messages) {
                    put(message.id, message)
                    message.serverId?.let { put(it, message) }
                    message.originStanzaId?.let { put(it, message) }
                }
            }
        }
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
                    threadMeta = replyCountsByRootKey[message.threadKey],
                    flashed = flashKey != null && message.matchesReference(flashKey),
                    onEdit = onEdit,
                    onReact = onReact,
                    onRetract = onRetract,
                    onStartReply = onStartReply,
                    onOpenThread = onOpenThread,
                    onNavigateToMessage = onNavigateToMessage,
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
    threadMeta: ThreadMeta?,
    flashed: Boolean,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (MessageEntity) -> Unit,
    onOpenThread: (String) -> Unit,
    onNavigateToMessage: (String) -> Unit,
    onRequestLinkPreview: (String) -> Unit,
) {
    val messageReactions =
        reactionsByMessageId[message.id].orEmpty() +
            reactionsByMessageId[message.serverId].orEmpty() +
            reactionsByMessageId[message.originStanzaId].orEmpty()
    val parent = message.replyToMessageId?.let { parentLookup[it] }
    MessageRow(
        session = session,
        message = message,
        parentMessage = parent,
        reactions = messageReactions.distinctBy { it.emoji },
        displayedCount =
            displayedByMessageId[message.id]?.count
                ?: message.serverId?.let { displayedByMessageId[it]?.count }
                ?: message.originStanzaId?.let { displayedByMessageId[it]?.count }
                ?: 0,
        own = message.senderId == session.stored.userId,
        linkPreviews = linkPreviews,
        threadMeta = threadMeta,
        // Groupchat wire references use [threadKey], which prefers the
        // room-assigned stanza-id when available.
        onEdit = { body -> onEdit(message.threadKey, body) },
        onReact = { emoji -> onReact(message.threadKey, emoji) },
        onRetract = { onRetract(message.threadKey) },
        onStartReply = { onStartReply(message) },
        onOpenThread = { onOpenThread(message.threadKey) },
        onRequestLinkPreview = onRequestLinkPreview,
        flashed = flashed,
        onNavigateToMessage = onNavigateToMessage,
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

/**
 * Slim single-line reply chip: `↳ @alice "their message"`. Matches the
 * chat/ frontend's compact style — it doesn't push the reply text around
 * or take 2+ lines of vertical room. Tap → scroll to (and flash) the
 * parent if it's in the loaded window.
 */
@Composable
private fun QuotedParent(
    parent: MessageEntity,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalWaddleColors.current
    val senderName = parent.senderName ?: "unknown"
    val preview =
        remember(parent.body) {
            parent.body
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
        }
    ReplyChip(
        icon = Icons.AutoMirrored.Filled.Reply,
        onClick = onClick,
    ) {
        Text(
            text = "@$senderName",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (preview.isNotBlank()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.labelMedium,
                color = colors.sidebarMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Same chip shape as [QuotedParent] but for replies whose parent is not
 * in the currently-loaded message window. Tapping still scrolls if the
 * parent gets loaded later, but we can't preview the body yet.
 */
@Composable
private fun UnresolvedReplyChip(onClick: () -> Unit) {
    val colors = LocalWaddleColors.current
    ReplyChip(
        icon = Icons.AutoMirrored.Filled.Reply,
        onClick = onClick,
    ) {
        Text(
            text = "Replying to earlier message",
            style = MaterialTheme.typography.labelMedium,
            color = colors.sidebarMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shared layout for the compact reply/quote chip: a single horizontal row
 * with a `↳` icon, a small horizontal gap, and the caller's content.
 * Rendered in the muted foreground color so it reads as metadata, not
 * another message bubble.
 */
@Composable
private fun ReplyChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalWaddleColors.current
    Row(
        modifier =
            Modifier
                .padding(bottom = 2.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.sidebarMuted,
            modifier = Modifier.size(14.dp),
        )
        content()
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
    threadMeta: ThreadMeta?,
    onEdit: (String) -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
    onStartReply: () -> Unit,
    onOpenThread: () -> Unit,
    onRequestLinkPreview: (String) -> Unit,
    flashed: Boolean = false,
    onNavigateToMessage: (String) -> Unit = {},
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

    var actionsOpen by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
    MessageRowBody(
        message = message,
        parentMessage = parentMessage,
        reactions = reactions,
        displayedCount = displayedCount,
        own = own,
        mentioned = mentioned,
        flashed = flashed,
        linkPreviews = linkPreviews,
        threadMeta = threadMeta,
        editing = editing,
        editBody = editBody,
        onEditBodyChange = { editBody = it },
        onSaveEdit = {
            val trimmed = editBody.trim()
            if (trimmed.isNotEmpty() && trimmed != message.body) onEdit(trimmed)
            editing = false
        },
        onCancelEdit = {
            editBody = message.body
            editing = false
        },
        onLongPress = { actionsOpen = true },
        onStartReply = onStartReply,
        onReact = onReact,
        onOpenThread = onOpenThread,
        onRequestLinkPreview = onRequestLinkPreview,
        onNavigateToMessage = onNavigateToMessage,
    )

    if (actionsOpen) {
        MessageActionSheetFor(
            own = own,
            messageBody = message.body,
            onDismiss = { actionsOpen = false },
            context = context,
            onReact = onReact,
            onStartReply = onStartReply,
            onStartEdit = { editing = true },
            onRetract = onRetract,
        )
    }
}

@Composable
private fun MessageActionSheetFor(
    own: Boolean,
    messageBody: String,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onStartReply: () -> Unit,
    onStartEdit: () -> Unit,
    onRetract: () -> Unit,
) {
    fun andClose(action: () -> Unit): () -> Unit =
        {
            action()
            onDismiss()
        }
    MessageActionSheet(
        own = own,
        onDismiss = onDismiss,
        onReact = { emoji ->
            onReact(emoji)
            onDismiss()
        },
        onReply = andClose(onStartReply),
        onCopy = andClose { copyToClipboard(context, messageBody) },
        onEdit = andClose(onStartEdit),
        onRetract = andClose(onRetract),
    )
}

@Composable
private fun MessageContent(
    message: MessageEntity,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    onRequestLinkPreview: (String) -> Unit,
) {
    message.forumTopicTitle?.takeIf(String::isNotBlank)?.let { title ->
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
    // If the entire body is a GIF URL we render it inline below via
    // MessageExtras — skip the redundant plain-text link above.
    val trimmedBody = remember(message.body) { message.body.trim() }
    val bodyIsGifOnly =
        message.sharedFileUrl == null && isWebUrl(trimmedBody) && isAnimatedImageUrl(trimmedBody)
    if (!bodyIsGifOnly && message.body.isNotBlank()) {
        MessageBody(body = message.body)
    }
    val previewUrl = remember(message.body) { firstWebUrl(message.body) }
    MessageExtras(
        message = message,
        linkPreview = previewUrl?.let { linkPreviews[it] },
        onRequestLinkPreview = onRequestLinkPreview,
    )
}

/**
 * Slack-inspired thread chip — small participant avatars on the left, reply
 * count + "last reply X ago" on the right. Provides social signal, not just
 * a number, so users can tell threads apart at a glance.
 */
@Composable
private fun ThreadChip(
    meta: ThreadMeta,
    onClick: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    val accent = MaterialTheme.colorScheme.primary
    val countText = if (meta.count == 1) "1 reply" else "${meta.count} replies"
    val lastReply = remember(meta.lastReplyAt) { meta.lastReplyAt?.let(::formatRelativeTimestamp) }
    Row(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThreadParticipantStack(names = meta.participantNames)
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        lastReply?.let {
            Text(
                text = "Last reply $it",
                style = MaterialTheme.typography.labelSmall,
                color = colors.sidebarMuted,
            )
        }
    }
}

@Composable
private fun ThreadParticipantStack(names: List<String>) {
    if (names.isEmpty()) return
    val avatarSize = 20.dp
    val overlap = 6.dp
    Row {
        names.forEachIndexed { index, name ->
            Box(
                modifier =
                    Modifier
                        .offset(x = -(overlap * index))
                        .size(avatarSize)
                        .background(waddleSummaryAccent(name), CircleShape)
                        .padding(1.dp)
                        .background(waddleSummaryAccent(name), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * ISO-8601 → "2m", "3h", "5d", or "just now". Kept terse so the chip stays
 * compact. Falls back to empty string on parse failure.
 */
private fun formatRelativeTimestamp(iso: String): String? {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val diffMillis = System.currentTimeMillis() - instant.toEpochMilli()
    val minutes = diffMillis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
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
    message.callInviteId?.let { CallInviteChip(message) }
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
            if (isAnimatedImageUrl(firstUrl)) {
                // GIF / animated image: render inline via Coil's animated
                // decoder instead of showing a naked link.
                InlineImageAttachment(
                    url = firstUrl,
                    description = "Animated image",
                    onOpen = { uriHandler.openUri(firstUrl) },
                )
            } else {
                LaunchedEffect(firstUrl) { onRequestLinkPreview(firstUrl) }
                linkPreview?.let { preview ->
                    LinkPreviewCard(preview = preview, onOpen = { uriHandler.openUri(firstUrl) })
                }
            }
        }
    }
}

/**
 * Returns true when [url]'s path ends with `.gif` (query string and fragment
 * stripped first). We render these inline via Coil's animated decoder instead
 * of showing a naked link.
 */
private fun isAnimatedImageUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".gif")
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
                coil3.compose.AsyncImage(
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
    val lightbox = LocalLightbox.current
    // Prefer the lightbox controller when one is installed so taps open
    // in-app; fall back to the caller's handler (e.g. external browser)
    // for contexts outside of chat screens.
    val handleTap: () -> Unit = { lightbox?.open(url) ?: onOpen() }
    Surface(
        modifier =
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth(fraction = 0.8f)
                .clickable(onClick = handleTap),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        coil3.compose.AsyncImage(
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
        parseHats(message.hats).forEach { hat -> HatBadge(hat = hat) }
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

/**
 * Renders the body of a message row (avatar + column with edit/display
 * branch). Extracted so [MessageRow] stays short enough to pass detekt's
 * complexity caps.
 */
@Suppress("LongParameterList")
@Composable
private fun MessageRowBody(
    message: MessageEntity,
    parentMessage: MessageEntity?,
    reactions: List<ReactionSummary>,
    displayedCount: Int,
    own: Boolean,
    mentioned: Boolean,
    flashed: Boolean,
    linkPreviews: Map<String, social.waddle.android.data.LinkPreview>,
    threadMeta: ThreadMeta?,
    editing: Boolean,
    editBody: String,
    onEditBodyChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onLongPress: () -> Unit,
    onStartReply: () -> Unit,
    onReact: (String) -> Unit,
    onOpenThread: () -> Unit,
    onRequestLinkPreview: (String) -> Unit,
    onNavigateToMessage: (String) -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val background =
        when {
            flashed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            mentioned -> MaterialTheme.colorScheme.tertiaryContainer
            else -> Color.Transparent
        }
    SwipeToReplyRow(enabled = !message.pending, onTriggered = onStartReply) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(background, RoundedCornerShape(8.dp))
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = {
                                if (!message.pending) {
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    onLongPress()
                                }
                            },
                        )
                    }.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarInitial(name = message.senderName)
            Column(Modifier.weight(1f)) {
                MessageMeta(message = message, own = own, displayedCount = displayedCount)
                Spacer(Modifier.height(4.dp))
                if (editing) {
                    EditMessageForm(
                        body = editBody,
                        onBodyChange = onEditBodyChange,
                        onSave = onSaveEdit,
                        onCancel = onCancelEdit,
                    )
                } else {
                    when {
                        parentMessage != null -> {
                            QuotedParent(
                                parent = parentMessage,
                                onClick = { onNavigateToMessage(parentMessage.messageKey) },
                            )
                        }

                        message.replyToMessageId != null -> {
                            // Reply target is outside the loaded window — still
                            // show a slim chip so the reply relationship is
                            // visible, even without the parent body preview.
                            UnresolvedReplyChip(onClick = { onNavigateToMessage(message.replyToMessageId) })
                        }
                    }
                    MessageContent(
                        message = message,
                        linkPreviews = linkPreviews,
                        onRequestLinkPreview = onRequestLinkPreview,
                    )
                    ReactionStrip(reactions = reactions, onReact = onReact)
                    threadMeta?.takeIf { it.count > 0 }?.let { meta ->
                        ThreadChip(meta = meta, onClick = onOpenThread)
                    }
                }
            }
        }
    }
}

/**
 * Swipe-right-to-reply wrapper — WhatsApp / Signal / Telegram staple. The
 * user drags the message row horizontally; past [REPLY_THRESHOLD_DP] we fire
 * [onTriggered] (with haptic) and snap back. A reply-arrow icon fades in on
 * the left as the drag progresses so the gesture is discoverable.
 */
@Composable
private fun SwipeToReplyRow(
    enabled: Boolean,
    onTriggered: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { REPLY_THRESHOLD_DP.dp.toPx() }
    val maxDragPx = thresholdPx * 1.4f
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var hapticFired by remember { mutableStateOf(false) }
    val progress = (dragX.value / thresholdPx).coerceIn(0f, 1f)
    val colors = LocalWaddleColors.current
    Box(modifier = Modifier.fillMaxWidth()) {
        // Background reveal: reply arrow slides in as the drag progresses.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = progress),
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(dragX.value.toInt(), 0) }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val crossed = dragX.value >= thresholdPx
                                scope.launch { dragX.animateTo(0f) }
                                hapticFired = false
                                if (crossed) onTriggered()
                            },
                            onDragCancel = {
                                scope.launch { dragX.animateTo(0f) }
                                hapticFired = false
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                // Right-only: negative drags are ignored.
                                val next = (dragX.value + delta).coerceIn(0f, maxDragPx)
                                scope.launch { dragX.snapTo(next) }
                                if (!hapticFired && next >= thresholdPx) {
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    hapticFired = true
                                }
                            },
                        )
                    }.then(
                        if (progress > 0f) {
                            Modifier.background(colors.composerSurface)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            content()
        }
    }
}

private const val REPLY_THRESHOLD_DP = 64

/**
 * Long-press action sheet for a message. Mirrors the iMessage / WhatsApp /
 * Telegram pattern: a quick reactions row across the top, then the full
 * action list below (reply, copy, and for own messages edit + delete).
 * Hides the perma-visible per-message buttons so the timeline stays calm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    own: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRetract: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clickable { onReact(emoji) },
                    contentAlignment = Alignment.CenterVertically.let { Alignment.Center },
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        HorizontalDivider(color = LocalWaddleColors.current.divider)
        MessageActionItem(icon = Icons.AutoMirrored.Filled.Reply, label = "Reply", onClick = onReply)
        MessageActionItem(
            icon = Icons.Rounded.ContentCopy,
            label = "Copy text",
            onClick = onCopy,
        )
        if (own) {
            MessageActionItem(icon = Icons.Rounded.Edit, label = "Edit", onClick = onEdit)
            MessageActionItem(
                icon = Icons.Rounded.Delete,
                label = "Delete",
                onClick = onRetract,
                destructive = true,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MessageActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
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
    forumTopicMode: Boolean = false,
    onTextChanged: (String) -> Unit,
    onClearReply: () -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onSendTopic: (title: String, body: String) -> Unit = { _, _ -> },
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    // Key on conversationKey (channelId or peerJid) — NOT initialText — so the
    // draft flow echoing our own writes back doesn't reset local state and
    // race the user's keystrokes. initialText is only consulted when the
    // conversation first loads its draft (see LaunchedEffect below).
    var body by rememberSaveable(conversationKey) { mutableStateOf(initialText) }
    var topicTitle by rememberSaveable(conversationKey) { mutableStateOf("") }
    var composing by rememberSaveable(conversationKey) { mutableStateOf(false) }
    LaunchedEffect(conversationKey, initialText) {
        if (body.isEmpty() && initialText.isNotEmpty()) {
            body = initialText
        }
    }
    val mentionToken = remember(body) { trailingMentionToken(body) }
    val filteredMentions = filteredMentionsFor(mentionToken, mentionSuggestions)
    val state =
        buildComposerState(
            body = body,
            composing = composing,
            enabled = enabled,
            sending = sending,
            channelName = channelName,
            forumTopicMode = forumTopicMode,
            topicTitle = topicTitle,
            onBodyUpdate = { next -> body = next },
            onComposingUpdate = { next -> composing = next },
            onTextChanged = onTextChanged,
            onTyping = onTyping,
            onSend = onSend,
            onSendTopic = { title, b ->
                onSendTopic(title, b)
                topicTitle = ""
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
        if (forumTopicMode) {
            ForumTopicTitleField(title = topicTitle, onChange = { topicTitle = it }, enabled = enabled && !sending)
        }
        replyPreview?.let { ReplyPreviewCard(preview = it, onClose = onClearReply) }
        ComposerRow(state = state, onAttachmentPicked = onAttachmentPicked)
    }
}

@Suppress("LongParameterList")
private fun buildComposerState(
    body: String,
    composing: Boolean,
    enabled: Boolean,
    sending: Boolean,
    channelName: String?,
    forumTopicMode: Boolean,
    topicTitle: String,
    onBodyUpdate: (String) -> Unit,
    onComposingUpdate: (Boolean) -> Unit,
    onTextChanged: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onSendTopic: (String, String) -> Unit,
): ComposerState {
    val placeholder =
        when {
            forumTopicMode -> "Write the opening post"
            channelName != null -> "Message #$channelName"
            else -> "Message"
        }
    return ComposerState(
        body = body,
        composing = composing,
        enabled = enabled && (!forumTopicMode || topicTitle.isNotBlank()),
        sending = sending,
        placeholder = placeholder,
        onBodyChange = { next ->
            onBodyUpdate(next)
            onTextChanged(next)
            val nextComposing = next.isNotBlank()
            if (nextComposing != composing) {
                onComposingUpdate(nextComposing)
                onTyping(nextComposing)
            }
        },
        onSend = { trimmed ->
            if (forumTopicMode) {
                onSendTopic(topicTitle.trim(), trimmed)
            } else {
                onSend(trimmed)
            }
            onBodyUpdate("")
            if (composing) {
                onComposingUpdate(false)
                onTyping(false)
            }
        },
    )
}

@Composable
private fun ForumTopicTitleField(
    title: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = title,
        onValueChange = onChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text("Topic title") },
    )
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

/**
 * Pill-shaped chat composer matching the design: a rounded container holding
 * an attach chip, the text field, and a send chip that lights up only when
 * the body is non-blank. No formatting toolbar — plain text input only.
 */
@Composable
private fun ComposerRow(
    state: ComposerState,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    val context = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val colors = LocalWaddleColors.current
    val attachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val attachment = resolveAttachmentDetails(context, uri)
                onAttachmentPicked(attachment.uri, attachment.name, attachment.mimeType)
            }
        }
    val ready = state.enabled && !state.sending
    val canSend = ready && state.body.isNotBlank()
    val sendAction: () -> Unit = {
        val trimmed = state.body.trim()
        if (trimmed.isNotEmpty()) {
            haptics.performHapticFeedback(
                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
            )
            state.onSend(trimmed)
        }
    }
    Surface(
        color = colors.composerSurface,
        shape = RoundedCornerShape(22.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { attachmentLauncher.launch(arrayOf("*/*")) },
                enabled = ready,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Attach",
                    tint = colors.sidebarMuted,
                )
            }
            BasicTextField(
                value = state.body,
                onValueChange = state.onBodyChange,
                enabled = ready,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (state.body.isEmpty()) {
                        Text(
                            text = state.placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.sidebarMuted,
                        )
                    }
                    inner()
                },
            )
            ComposerSendButton(
                enabled = canSend,
                onClick = sendAction,
            )
        }
    }
}

@Composable
private fun ComposerSendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWaddleColors.current
    val background =
        if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent
    val content =
        if (enabled) MaterialTheme.colorScheme.onPrimary else colors.sidebarMuted
    Box(
        modifier =
            Modifier
                .padding(end = 2.dp)
                .size(36.dp)
                .background(background, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Send,
            contentDescription = "Send",
            tint = content,
            modifier = Modifier.size(18.dp),
        )
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
    val colors = LocalWaddleColors.current
    Surface(
        color = colors.composerSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Accent bar — matches the quoted-parent treatment on messages.
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AvatarInitial(name = preview.senderName)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Replying to ${preview.senderName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = preview.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.sidebarMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel reply",
                        tint = colors.sidebarMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
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

/** Decodes the MessageEntity.hats column (newline-separated `uri|title` pairs). */
private fun parseHats(encoded: String?): List<ParsedHat> {
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded
        .lineSequence()
        .mapNotNull { line ->
            val sep = line.indexOf('|')
            if (sep < 0) return@mapNotNull null
            val uri = line.substring(0, sep).trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = line.substring(sep + 1).trim().ifBlank { uri.substringAfterLast(':') }
            ParsedHat(uri = uri, title = title)
        }.toList()
}

private data class ParsedHat(
    val uri: String,
    val title: String,
)

@Composable
private fun HatBadge(hat: ParsedHat) {
    val role = hat.uri.substringAfterLast(':').lowercase()
    val colors = LocalWaddleColors.current
    val tint =
        when (role) {
            "owner" -> Color(0xFFF59E0B)
            "admin" -> Color(0xFFEF4444)
            "moderator" -> Color(0xFF3B82F6)
            "bot" -> Color(0xFF8B5CF6)
            "verified" -> MaterialTheme.colorScheme.primary
            else -> colors.sidebarMuted
        }
    Surface(
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = hat.title,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

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
private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "👀")
private const val TYPING_NAME_LIMIT = 2

/**
 * Chip rendered beneath a room message that advertises a Muji call. Tapping
 * it requests the mic permission (and camera for video calls), then joins
 * the existing Jingle session via [social.waddle.android.call.CallController.joinExistingCall].
 *
 * Foreground-service rules on Android 14+ require the caller to hold the
 * backing permission before `startForeground` — without this gate the
 * service crashes with InvalidForegroundServiceTypeException.
 */
@Composable
private fun CallInviteChip(message: MessageEntity) {
    val callViewModel: social.waddle.android.ui.call.CallViewModel =
        androidx.hilt.lifecycle.viewmodel.compose
            .hiltViewModel()
    val sid = parseJingleSid(message.callExternalUri)
    val sfuJid = parseJingleSfuJid(message.callExternalUri)
    val audioOnly = message.callDescription?.contains("audio", ignoreCase = true) == true

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            val micGranted = grants[android.Manifest.permission.RECORD_AUDIO] == true
            if (!micGranted) {
                social.waddle.android.util.WaddleLog.info(
                    "Call join aborted — RECORD_AUDIO denied.",
                )
                return@rememberLauncherForActivityResult
            }
            if (sid != null && sfuJid != null) {
                callViewModel.joinExistingCall(
                    roomJid = message.roomJid,
                    sfuJid = sfuJid,
                    sid = sid,
                    audioOnly = audioOnly,
                )
            }
        }

    AssistChip(
        onClick = {
            if (sid == null || sfuJid == null || !message.callMuji) {
                social.waddle.android.util.WaddleLog.info(
                    "Call chip click ignored — no parseable sid/sfu or not a Muji call.",
                )
                return@AssistChip
            }
            val permissions =
                if (audioOnly) {
                    arrayOf(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    arrayOf(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.CAMERA,
                    )
                }
            permissionLauncher.launch(permissions)
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

/**
 * Extract the Jingle session ID from a Waddle call external URI of the form
 * `xmpp:<sfuJid>?jingle;sid=<sid>`. Returns null for any other shape.
 */
private fun parseJingleSid(externalUri: String?): String? {
    val uri = externalUri ?: return null
    val marker = ";sid="
    val idx = uri.indexOf(marker)
    if (idx < 0) return null
    return uri.substring(idx + marker.length).takeIf { it.isNotBlank() }
}

/** Extract the SFU JID from `xmpp:<sfuJid>?jingle;sid=...`. */
private fun parseJingleSfuJid(externalUri: String?): String? {
    val uri = externalUri ?: return null
    val afterScheme = uri.removePrefix("xmpp:").takeIf { it != uri } ?: return null
    return afterScheme.substringBefore('?').takeIf { it.isNotBlank() }
}
