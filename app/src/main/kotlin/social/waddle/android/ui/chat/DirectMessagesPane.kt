package social.waddle.android.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.waddle.android.data.db.DeliverySummary
import social.waddle.android.data.db.DmConversationEntity
import social.waddle.android.data.db.DmMessageEntity
import social.waddle.android.data.db.ReactionSummary
import social.waddle.android.data.model.AuthSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DirectMessagesPane(
    session: AuthSession,
    state: ChatUiState,
    conversations: List<DmConversationEntity>,
    messages: List<DmMessageEntity>,
    reactions: List<ReactionSummary>,
    displayedSummaries: List<DeliverySummary>,
    typingByPeer: Map<String, Boolean>,
    replyPreview: ReplyPreview?,
    onSearchUsers: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    onClearPeer: () -> Unit,
    onLoadOlder: () -> Unit,
    onSend: (String) -> Unit,
    onDisplayed: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (DmMessageEntity) -> Unit,
    onClearReply: () -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit = { _, _, _ -> },
) {
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    if (compact) {
        CompactDirectMessagesPane(
            session = session,
            state = state,
            conversations = conversations,
            messages = messages,
            reactions = reactions,
            displayedSummaries = displayedSummaries,
            typingByPeer = typingByPeer,
            replyPreview = replyPreview,
            onSearchUsers = onSearchUsers,
            onSelectPeer = onSelectPeer,
            onClearPeer = onClearPeer,
            onLoadOlder = onLoadOlder,
            onSend = onSend,
            onDisplayed = onDisplayed,
            onTyping = onTyping,
            onEdit = onEdit,
            onReact = onReact,
            onRetract = onRetract,
            onStartReply = onStartReply,
            onClearReply = onClearReply,
            onAttachmentPicked = onAttachmentPicked,
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        DirectMessageSidebar(
            state = state,
            conversations = conversations,
            onSearchUsers = onSearchUsers,
            onSelectPeer = onSelectPeer,
            modifier =
                Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
        )
        VerticalDivider()
        Column(Modifier.weight(1f)) {
            DirectMessageHeader(
                peerJid = state.selectedDmPeerJid,
                typing = state.selectedDmPeerJid?.let { typingByPeer[it] } == true,
            )
            DirectMessageTimeline(
                session = session,
                messages = messages,
                reactionsByMessageId = reactions.groupBy(ReactionSummary::messageId),
                displayedByMessageId = displayedSummaries.associateBy(DeliverySummary::messageId),
                modifier = Modifier.weight(1f),
                onLoadOlder = onLoadOlder,
                onDisplayed = onDisplayed,
                onEdit = onEdit,
                onReact = onReact,
                onRetract = onRetract,
                onStartReply = onStartReply,
            )
            DirectMessageComposer(
                enabled = state.selectedDmPeerJid != null && !state.sending,
                sending = state.sending,
                replyPreview = replyPreview,
                onTyping = onTyping,
                onSend = onSend,
                onClearReply = onClearReply,
                onAttachmentPicked = onAttachmentPicked,
            )
        }
    }
}

@Composable
private fun CompactDirectMessagesPane(
    session: AuthSession,
    state: ChatUiState,
    conversations: List<DmConversationEntity>,
    messages: List<DmMessageEntity>,
    reactions: List<ReactionSummary>,
    displayedSummaries: List<DeliverySummary>,
    typingByPeer: Map<String, Boolean>,
    replyPreview: ReplyPreview?,
    onSearchUsers: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    onClearPeer: () -> Unit,
    onLoadOlder: () -> Unit,
    onSend: (String) -> Unit,
    onDisplayed: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (DmMessageEntity) -> Unit,
    onClearReply: () -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit = { _, _, _ -> },
) {
    if (state.selectedDmPeerJid == null) {
        DirectMessageSidebar(
            state = state,
            conversations = conversations,
            onSearchUsers = onSearchUsers,
            onSelectPeer = onSelectPeer,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        DirectMessageHeader(
            peerJid = state.selectedDmPeerJid,
            typing = typingByPeer[state.selectedDmPeerJid] == true,
            onBack = onClearPeer,
        )
        DirectMessageTimeline(
            session = session,
            messages = messages,
            reactionsByMessageId = reactions.groupBy(ReactionSummary::messageId),
            displayedByMessageId = displayedSummaries.associateBy(DeliverySummary::messageId),
            modifier = Modifier.weight(1f),
            onLoadOlder = onLoadOlder,
            onDisplayed = onDisplayed,
            onEdit = onEdit,
            onReact = onReact,
            onRetract = onRetract,
            onStartReply = onStartReply,
        )
        DirectMessageComposer(
            enabled = !state.sending,
            sending = state.sending,
            replyPreview = replyPreview,
            onTyping = onTyping,
            onSend = onSend,
            onClearReply = onClearReply,
            onAttachmentPicked = onAttachmentPicked,
        )
    }
}

@Composable
private fun DirectMessageSidebar(
    state: ChatUiState,
    conversations: List<DmConversationEntity>,
    onSearchUsers: (String) -> Unit,
    onSelectPeer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        OutlinedTextField(
            value = state.dmSearchQuery,
            onValueChange = onSearchUsers,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            singleLine = true,
            label = { Text("Find people") },
        )
        if (state.dmSearchResults.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.dmSearchResults, key = { it.jid }) { user ->
                    AssistChip(
                        onClick = { onSelectPeer(user.jid) },
                        label = { Text(user.username, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(conversations, key = DmConversationEntity::peerJid) { conversation ->
                DirectMessageConversationRow(
                    conversation = conversation,
                    selected = state.selectedDmPeerJid == conversation.peerJid,
                    onClick = { onSelectPeer(conversation.peerJid) },
                )
            }
            if (conversations.isEmpty()) {
                item { EmptyDmState("Search for someone to start a direct message.") }
            }
        }
    }
}

@Composable
private fun DirectMessageConversationRow(
    conversation: DmConversationEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rowColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        color = rowColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DirectMessageAvatar(name = conversation.peerUsername)
            Column(Modifier.weight(1f)) {
                Text(
                    text = conversation.peerUsername,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = conversation.lastMessageBody ?: conversation.peerJid,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (conversation.unreadCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectMessageHeader(
    peerJid: String?,
    typing: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            onBack?.let {
                TextButton(onClick = it) {
                    Text("Back")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = peerJid?.substringBefore('@') ?: "Direct messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (typing) {
                            "typing..."
                        } else {
                            peerJid ?: "Pick a conversation or search for a person."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DirectMessageTimeline(
    session: AuthSession,
    messages: List<DmMessageEntity>,
    reactionsByMessageId: Map<String, List<ReactionSummary>>,
    displayedByMessageId: Map<String, DeliverySummary>,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
    onDisplayed: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onReact: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onStartReply: (DmMessageEntity) -> Unit,
) {
    val listState = rememberLazyListState()
    val latestReadable = messages.lastOrNull { it.fromJid != session.jid && !it.retracted }
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
        items(messages, key = DmMessageEntity::id) { message ->
            val messageReactions =
                reactionsByMessageId[message.id].orEmpty() +
                    reactionsByMessageId[message.serverId].orEmpty() +
                    reactionsByMessageId[message.originStanzaId].orEmpty()
            DirectMessageRow(
                message = message,
                parentMessage = message.replyToMessageId?.let(parentLookup::get),
                own = message.fromJid == session.jid,
                reactions = messageReactions.distinctBy { it.emoji },
                displayedCount =
                    displayedByMessageId[message.id]?.count
                        ?: message.serverId?.let { displayedByMessageId[it]?.count }
                        ?: message.originStanzaId?.let { displayedByMessageId[it]?.count }
                        ?: 0,
                onEdit = { body -> onEdit(message.messageKey, body) },
                onReact = { emoji -> onReact(message.messageKey, emoji) },
                onRetract = { onRetract(message.messageKey) },
                onStartReply = { onStartReply(message) },
            )
        }
        if (messages.isEmpty()) {
            item { EmptyDmState("No messages yet.") }
        }
    }
}

@Composable
private fun DirectMessageRow(
    message: DmMessageEntity,
    parentMessage: DmMessageEntity?,
    own: Boolean,
    reactions: List<ReactionSummary>,
    displayedCount: Int,
    onEdit: (String) -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
    onStartReply: () -> Unit,
) {
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editBody by rememberSaveable(message.id) { mutableStateOf(message.body) }
    LaunchedEffect(message.body, editing) {
        if (!editing) {
            editBody = message.body
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DirectMessageAvatar(name = message.senderName)
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDmStamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (own && message.pending) {
                    Text(
                        text = "sending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (own && displayedCount > 0) {
                    Text(
                        text = "seen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (message.retracted) {
                Text(
                    text = "This message was deleted.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                )
            } else if (editing) {
                DirectEditMessageForm(
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
                message.replyToMessageId?.let {
                    DirectInlineReply(parentMessage = parentMessage)
                }
                Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
                DirectMessageExtras(message = message)
                DirectReactionStrip(reactions = reactions, onReact = onReact)
                DirectMessageActionRow(
                    own = own,
                    pending = message.pending,
                    onStartEdit = { editing = true },
                    onReact = onReact,
                    onRetract = onRetract,
                    onStartReply = onStartReply,
                )
            }
        }
    }
}

@Composable
private fun DirectInlineReply(parentMessage: DmMessageEntity?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = parentMessage?.senderName ?: "Earlier message",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = parentMessage?.body?.takeIf { it.isNotBlank() } ?: "Replying to earlier message",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DirectEditMessageForm(
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
private fun DirectMessageExtras(message: DmMessageEntity) {
    val uriHandler = LocalUriHandler.current
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
            onClick = { message.callExternalUri?.let(uriHandler::openUri) },
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
private fun DirectReactionStrip(
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
private fun DirectMessageActionRow(
    own: Boolean,
    pending: Boolean,
    onStartEdit: () -> Unit,
    onReact: (String) -> Unit,
    onRetract: () -> Unit,
    onStartReply: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DIRECT_QUICK_REACTIONS.forEach { emoji ->
            TextButton(
                onClick = { onReact(emoji) },
                enabled = !pending,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(emoji)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStartReply, enabled = !pending) {
            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply")
        }
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
private fun DirectMessageAvatar(name: String?) {
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

private fun formatDmStamp(value: String): String =
    runCatching {
        Instant
            .parse(value)
            .atZone(ZoneId.systemDefault())
            .format(DM_STAMP_FORMATTER)
    }.getOrElse { "" }

private val DM_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DIRECT_QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "👀")

private val DmMessageEntity.messageKey: String
    get() = originStanzaId ?: id

@Composable
private fun DirectMessageComposer(
    enabled: Boolean,
    sending: Boolean,
    replyPreview: ReplyPreview?,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onClearReply: () -> Unit,
    onAttachmentPicked: (Uri, String?, String?) -> Unit,
) {
    var body by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val attachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val attachment = resolveDirectAttachmentDetails(context, uri)
                onAttachmentPicked(attachment.uri, attachment.name, attachment.mimeType)
            }
        }
    Column(Modifier.fillMaxWidth()) {
        replyPreview?.let { DirectReplyPreviewCard(preview = it, onClose = onClearReply) }
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
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
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
}

@Composable
private fun DirectReplyPreviewCard(
    preview: ReplyPreview,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Replying to ${preview.senderName}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
            }
        }
    }
}

private data class DirectAttachmentDetails(
    val uri: Uri,
    val name: String?,
    val mimeType: String?,
)

private fun resolveDirectAttachmentDetails(
    context: Context,
    uri: Uri,
): DirectAttachmentDetails {
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
    return DirectAttachmentDetails(
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
private fun EmptyDmState(text: String) {
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
