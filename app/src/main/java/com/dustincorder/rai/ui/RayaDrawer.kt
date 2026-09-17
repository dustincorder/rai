package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.dustincorder.rai.R
import com.dustincorder.rai.domain.ActiveConversation
import com.dustincorder.rai.domain.ChatSession

@Composable
fun RayaDrawerContent(
    sessions: List<ChatSession>,
    active: ActiveConversation,
    onNewChat: () -> Unit,
    onTemporary: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 24.dp)) {
                Text(stringResource(R.string.assistant_name), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.drawer_identity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.new_chat)) },
                selected = active is ActiveConversation.NewDraft,
                onClick = onNewChat,
                icon = { Icon(Icons.Outlined.AddComment, null) },
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.temporary_chat)) },
                selected = active is ActiveConversation.Temporary,
                onClick = onTemporary,
                icon = { Icon(Icons.Outlined.VisibilityOff, null) },
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.chat_history), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sessions, key = { it.id }) { session ->
                    Row(Modifier.fillMaxWidth()) {
                        NavigationDrawerItem(
                            label = { Text(session.title ?: stringResource(R.string.new_chat), maxLines = 1) },
                            selected = active is ActiveConversation.Persistent && active.sessionId == session.id,
                            onClick = { onOpen(session.id) },
                            icon = { Icon(Icons.Outlined.Forum, null) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { deleteTarget = session }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_chat)) }
                    }
                }
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) },
                selected = false,
                onClick = onSettings,
                icon = { Icon(Icons.Outlined.Settings, null) },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_message)) },
            confirmButton = { TextButton(onClick = { onDelete(session.id); deleteTarget = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Preview(name = "Raya drawer", showBackground = true)
@Composable
private fun RayaDrawerPreview() {
    RayaDrawerContent(
        sessions = listOf(ChatSession("1", "Project planning", createdAt = 1L, updatedAt = 2L)),
        active = ActiveConversation.Persistent("1"),
        onNewChat = {}, onTemporary = {}, onOpen = {}, onDelete = {}, onSettings = {},
    )
}
