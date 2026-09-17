package com.dustincorder.rai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.dustincorder.rai.R
import com.dustincorder.rai.domain.ChatSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    sessions: List<ChatSession>,
    activeId: String?,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.chat_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = onNewChat) { Icon(Icons.Outlined.Add, stringResource(R.string.new_chat)) }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { Text(stringResource(R.string.no_chats)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
                items(sessions, key = { it.id }) { session ->
                    ListItem(
                        headlineContent = { Text(session.title ?: stringResource(R.string.new_chat)) },
                        supportingContent = { if (session.id == activeId) Text(stringResource(R.string.active_chat), color = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { deleteTarget = session }) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_chat))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { onOpen(session.id) },
                    )
                }
            }
        }
    }
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_message)) },
            confirmButton = {
                TextButton(onClick = { onDelete(session.id); deleteTarget = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
