package social.waddle.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.waddle.android.data.db.ChannelEntity
import social.waddle.android.data.db.WaddleEntity
import social.waddle.android.data.model.MemberSummary
import social.waddle.android.data.model.UserSearchResult
import social.waddle.android.data.model.WaddleSummary

@Composable
fun ChatManagementDialogs(
    activeDialog: ChatDialog?,
    state: ChatUiState,
    currentWaddle: WaddleEntity?,
    currentChannel: ChannelEntity?,
    onDismiss: () -> Unit,
    onPublicQueryChange: (String) -> Unit,
    onRefreshPublicWaddles: () -> Unit,
    onJoinWaddle: (String) -> Unit,
    onCreateWaddle: (String, String?, Boolean) -> Unit,
    onUpdateWaddle: (String?, String?, Boolean?) -> Unit,
    onDeleteWaddle: () -> Unit,
    onCreateChannel: (String, String?) -> Unit,
    onUpdateChannel: (String?, String?, Int?) -> Unit,
    onDeleteChannel: () -> Unit,
    onUserSearch: (String) -> Unit,
    onAddMember: (String, String) -> Unit,
    onUpdateMemberRole: (String, String) -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    when (activeDialog) {
        ChatDialog.BrowsePublicWaddles -> {
            BrowsePublicWaddlesDialog(
                state = state,
                onDismiss = onDismiss,
                onQueryChange = onPublicQueryChange,
                onRefresh = onRefreshPublicWaddles,
                onJoin = onJoinWaddle,
            )
        }

        ChatDialog.NewWaddle -> {
            WaddleEditorDialog(
                title = "Create waddle",
                busy = state.busy,
                initialName = "",
                initialDescription = "",
                initialPublic = false,
                allowDelete = false,
                onDismiss = onDismiss,
                onSave = onCreateWaddle,
                onDelete = onDeleteWaddle,
            )
        }

        ChatDialog.WaddleSettings -> {
            WaddleEditorDialog(
                title = "Waddle settings",
                busy = state.busy,
                initialName = currentWaddle?.name.orEmpty(),
                initialDescription = currentWaddle?.description.orEmpty(),
                initialPublic = false,
                allowDelete = currentWaddle != null,
                onDismiss = onDismiss,
                onSave = onCreateWaddle,
                onUpdate = onUpdateWaddle,
                onDelete = onDeleteWaddle,
            )
        }

        ChatDialog.NewChannel -> {
            NewChannelDialog(
                busy = state.busy,
                onDismiss = onDismiss,
                onCreate = onCreateChannel,
                onDeleteSelected = onDeleteChannel,
                canDeleteSelected = state.selectedChannelId != null,
            )
        }

        ChatDialog.EditChannel -> {
            EditChannelDialog(
                busy = state.busy,
                channel = currentChannel,
                onDismiss = onDismiss,
                onUpdate = onUpdateChannel,
                onDeleteSelected = onDeleteChannel,
            )
        }

        ChatDialog.Members -> {
            MembersDialog(
                state = state,
                onDismiss = onDismiss,
                onUserSearch = onUserSearch,
                onAddMember = onAddMember,
                onUpdateMemberRole = onUpdateMemberRole,
                onRemoveMember = onRemoveMember,
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun BrowsePublicWaddlesDialog(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onJoin: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse public waddles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.publicQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search") },
                    )
                    Button(onClick = onRefresh, enabled = !state.busy) {
                        Text("Search")
                    }
                }
                if (state.busy) {
                    CircularProgressIndicator()
                }
                PublicWaddleList(waddles = state.publicWaddles, onJoin = onJoin)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun PublicWaddleList(
    waddles: List<WaddleSummary>,
    onJoin: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(waddles, key = WaddleSummary::id) { waddle ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = waddle.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    waddle.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { onJoin(waddle.id) }) {
                    Text(if (waddle.role == null) "Join" else "Joined")
                }
            }
        }
        if (waddles.isEmpty()) {
            item { Text("No public waddles found.") }
        }
    }
}

@Composable
private fun WaddleEditorDialog(
    title: String,
    busy: Boolean,
    initialName: String,
    initialDescription: String,
    initialPublic: Boolean,
    allowDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String?, Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: ((String?, String?, Boolean?) -> Unit)? = null,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var description by rememberSaveable(initialDescription) { mutableStateOf(initialDescription) }
    var isPublic by rememberSaveable(initialPublic) { mutableStateOf(initialPublic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Description") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Public")
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                if (allowDelete) {
                    HorizontalDivider()
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text("Delete waddle")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanDescription = description.trim().ifBlank { null }
                    if (onUpdate == null) {
                        onSave(name.trim(), cleanDescription, isPublic)
                    } else {
                        onUpdate(name.trim(), cleanDescription, isPublic)
                    }
                },
                enabled = name.isNotBlank() && !busy,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NewChannelDialog(
    busy: Boolean,
    canDeleteSelected: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Description") },
                )
                if (canDeleteSelected) {
                    HorizontalDivider()
                    TextButton(onClick = onDeleteSelected, enabled = !busy) {
                        Text("Delete selected channel")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank() && !busy,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditChannelDialog(
    busy: Boolean,
    channel: ChannelEntity?,
    onDismiss: () -> Unit,
    onUpdate: (String?, String?, Int?) -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var name by rememberSaveable(channel?.id) { mutableStateOf(channel?.name.orEmpty()) }
    var description by rememberSaveable(channel?.id) { mutableStateOf(channel?.topic.orEmpty()) }
    var position by rememberSaveable(channel?.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Channel settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Description") },
                )
                OutlinedTextField(
                    value = position,
                    onValueChange = { next -> position = next.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Position") },
                )
                HorizontalDivider()
                TextButton(onClick = onDeleteSelected, enabled = channel != null && !busy) {
                    Text("Delete channel")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdate(
                        name.trim().ifBlank { null },
                        description.trim().ifBlank { null },
                        position.toIntOrNull(),
                    )
                },
                enabled = channel != null && name.isNotBlank() && !busy,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MembersDialog(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onUserSearch: (String) -> Unit,
    onAddMember: (String, String) -> Unit,
    onUpdateMemberRole: (String, String) -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.userSearchQuery,
                    onValueChange = onUserSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Add people") },
                )
                UserSearchResults(results = state.userSearchResults, onAddMember = onAddMember)
                MemberList(
                    members = state.members,
                    onUpdateMemberRole = onUpdateMemberRole,
                    onRemoveMember = onRemoveMember,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun UserSearchResults(
    results: List<UserSearchResult>,
    onAddMember: (String, String) -> Unit,
) {
    if (results.isEmpty()) {
        return
    }
    LazyColumn(
        modifier = Modifier.heightIn(max = 140.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(results, key = UserSearchResult::id) { user ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(user.username, fontWeight = FontWeight.SemiBold)
                    Text(user.jid, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onAddMember(user.id, "member") }) {
                    Text("Add")
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun MemberList(
    members: List<MemberSummary>,
    onUpdateMemberRole: (String, String) -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(members, key = MemberSummary::userId) { member ->
            MemberRow(
                member = member,
                onUpdateRole = { role -> onUpdateMemberRole(member.userId, role) },
                onRemove = { onRemoveMember(member.userId) },
            )
        }
        if (members.isEmpty()) {
            item { Text("No members loaded.") }
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberSummary,
    onUpdateRole: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(member.username, fontWeight = FontWeight.SemiBold)
                Text(member.role, style = MaterialTheme.typography.bodySmall)
            }
            if (member.role != "owner") {
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
        if (member.role != "owner") {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MEMBER_ROLES.forEach { role ->
                    FilterChip(
                        selected = member.role == role,
                        onClick = { onUpdateRole(role) },
                        label = { Text(role) },
                    )
                }
            }
        }
    }
}

private val MEMBER_ROLES = listOf("member", "moderator", "admin")
