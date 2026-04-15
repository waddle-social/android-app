package social.waddle.android.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import social.waddle.android.data.db.ChannelEntity
import social.waddle.android.data.db.DeliverySummary
import social.waddle.android.data.db.DmConversationEntity
import social.waddle.android.data.db.DmMessageEntity
import social.waddle.android.data.db.MessageEntity
import social.waddle.android.data.db.ReactionSummary
import social.waddle.android.data.db.WaddleEntity
import social.waddle.android.data.model.AuthSession
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
        channels = channels,
        onStart = viewModel::start,
        onSelectWaddle = { viewModel.selectWaddle(session, it) },
        onSelectChannel = viewModel::selectChannel,
    )

    ChatScreenScaffold(
        session = session,
        state = state,
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
    onOpenAccount: () -> Unit,
    onShowRooms: () -> Unit,
    onShowDirectMessages: () -> Unit,
    onRefresh: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Waddle")
                        Text(
                            session.jid,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onShowRooms, enabled = state.mode != ChatMode.Rooms) {
                        Text("Rooms")
                    }
                    TextButton(onClick = onShowDirectMessages, enabled = state.mode != ChatMode.DirectMessages) {
                        Text("DMs")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = "Account")
                    }
                },
            )
        },
        snackbarHost = snackbarHost,
        content = content,
    )
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
    channels: List<ChannelEntity>,
    onStart: (AuthSession) -> Unit,
    onSelectWaddle: (String) -> Unit,
    onSelectChannel: (String, String) -> Unit,
) {
    LaunchedEffect(session.stored.sessionId) {
        onStart(session)
    }
    LaunchedEffect(waddles, state.selectedWaddleId) {
        if (state.selectedWaddleId == null && waddles.isNotEmpty()) {
            onSelectWaddle(waddles.first().id)
        }
    }
    LaunchedEffect(channels, state.selectedChannelId) {
        val selectedWaddleId = state.selectedWaddleId
        if (selectedWaddleId != null && state.selectedChannelId == null && channels.isNotEmpty()) {
            onSelectChannel(selectedWaddleId, channels.first().id)
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
)

@Composable
private fun CompactChatLayout(args: ChatLayoutArgs) {
    Column(Modifier.fillMaxSize()) {
        WaddleChips(
            waddles = args.waddles,
            selectedWaddleId = args.state.selectedWaddleId,
            onSelectWaddle = args.onSelectWaddle,
        )
        HorizontalDivider()
        ChannelChips(
            channels = args.channels,
            selectedWaddleId = args.state.selectedWaddleId,
            selectedChannelId = args.state.selectedChannelId,
            onSelectChannel = args.onSelectChannel,
        )
        HorizontalDivider()
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
            modifier = Modifier.weight(1f),
            onLoadOlder = args.onLoadOlder,
            onDisplayed = args.onDisplayed,
            onEdit = args.onEdit,
            onReact = args.onReact,
            onRetract = args.onRetract,
        )
        Composer(
            sending = args.state.sending,
            enabled = args.state.selectedChannelId != null,
            channelName = args.currentChannel?.name,
            onTyping = args.onTyping,
            onSend = args.onSend,
            onAttachmentPicked = args.onAttachmentPicked,
        )
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
                modifier = Modifier.weight(1f),
                onLoadOlder = args.onLoadOlder,
                onDisplayed = args.onDisplayed,
                onEdit = args.onEdit,
                onReact = args.onReact,
                onRetract = args.onRetract,
            )
            Composer(
                sending = args.state.sending,
                enabled = args.state.selectedChannelId != null,
                channelName = args.currentChannel?.name,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = args.currentWaddle?.name ?: "Waddle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = args.session.jid,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarSectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(action)
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
    val rowColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = rowColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    color = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SidebarEmpty(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SidebarFooter(args: ChatLayoutArgs) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
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
private fun WaddleChips(
    waddles: List<WaddleEntity>,
    selectedWaddleId: String?,
    onSelectWaddle: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(waddles, key = WaddleEntity::id) { waddle ->
            AssistChip(
                onClick = { onSelectWaddle(waddle.id) },
                label = { Text(waddle.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { SelectedDot(selected = selectedWaddleId == waddle.id) },
            )
        }
    }
}

@Composable
private fun ChannelChips(
    channels: List<ChannelEntity>,
    selectedWaddleId: String?,
    selectedChannelId: String?,
    onSelectChannel: (String, String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(channels, key = ChannelEntity::id) { channel ->
            AssistChip(
                onClick = {
                    selectedWaddleId?.let { waddleId -> onSelectChannel(waddleId, channel.id) }
                },
                label = { Text("#${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { SelectedDot(selected = selectedChannelId == channel.id) },
            )
        }
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
private fun Timeline(
    session: AuthSession,
    messages: List<MessageEntity>,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
    onDisplayed: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestReadable = messages.lastOrNull { it.senderId != session.stored.userId && !it.retracted }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(latestReadable?.id) {
        latestReadable?.messageKey?.let(onDisplayed)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (messages.isNotEmpty()) {
            item {
                TextButton(
                    onClick = onLoadOlder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Load older messages")
                }
            }
        }
        if (messages.isEmpty()) {
            item { EmptyState("No messages yet") }
        }
        items(messages, key = MessageEntity::id) { message ->
            val messageReactions =
                reactionsByMessageId[message.id].orEmpty() + reactionsByMessageId[message.serverId].orEmpty()
            MessageRow(
                session = session,
                message = message,
                reactions = messageReactions.distinctBy { it.emoji },
                displayedCount =
                    displayedByMessageId[message.id]?.count
                        ?: message.serverId?.let { displayedByMessageId[it]?.count }
                        ?: 0,
                own = message.senderId == session.stored.userId,
                onEdit = { body -> onEdit(message.messageKey, body) },
                onReact = { emoji -> onReact(message.messageKey, emoji) },
                onRetract = { onRetract(message.messageKey) },
            )
        }
    }
}

@Composable
private fun MessageRow(
    session: AuthSession,
    message: MessageEntity,
    reactions: List<ReactionSummary>,
    displayedCount: Int,
    own: Boolean,
    onEdit: (String) -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
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
                MessageBody(body = message.body)
                MessageExtras(message = message)
                ReactionStrip(reactions = reactions, onReact = onReact)
                MessageActionRow(
                    own = own,
                    pending = message.pending,
                    onStartEdit = { editing = true },
                    onReact = onReact,
                    onRetract = onRetract,
                )
            }
        }
    }
}

@Composable
private fun MessageExtras(message: MessageEntity) {
    val uriHandler = LocalUriHandler.current
    message.broadcastMention?.let { mention ->
        AssistChip(
            onClick = {},
            label = { Text("@$mention") },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    message.sharedFileUrl?.let { url ->
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
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    var body by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }
    val placeholder = channelName?.let { "Message #$it" } ?: "Message"
    val context = LocalContext.current
    val attachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val attachment = resolveAttachmentDetails(context, uri)
                onAttachmentPicked(attachment.uri, attachment.name, attachment.mimeType)
            }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = body,
            onValueChange = { next ->
                body = next
                val nextComposing = next.isNotBlank()
                if (nextComposing != composing) {
                    composing = nextComposing
                    onTyping(nextComposing)
                }
            },
            enabled = enabled && !sending,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = 5,
        )
        IconButton(
            onClick = { attachmentLauncher.launch(arrayOf("*/*")) },
            enabled = enabled && !sending,
        ) {
            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach file")
        }
        IconButton(
            onClick = {
                val trimmed = body.trim()
                if (trimmed.isNotEmpty()) {
                    onSend(trimmed)
                    body = ""
                    if (composing) {
                        composing = false
                        onTyping(false)
                    }
                }
            },
            enabled = enabled && !sending && body.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
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

@Composable
private fun SelectedDot(selected: Boolean) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(10.dp),
        content = {},
    )
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
