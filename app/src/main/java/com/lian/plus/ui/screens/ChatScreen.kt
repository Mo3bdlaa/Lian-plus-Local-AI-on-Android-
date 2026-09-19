package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lian.plus.data.db.MessageEntity
import com.lian.plus.llm.ChatTurn
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val loaded by vm.loadedModel.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the stream as it grows, but only while it is running - otherwise
    // scrolling back through history would keep snapping to the bottom.
    LaunchedEffect(messages.size, ui.streamingText) {
        val target = messages.size + if (ui.streamingText.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Chat", style = MaterialTheme.typography.titleMedium)
                    Text(
                        loaded?.let { "${it.model.displayName} · ${it.contextSize / 1024}K context" }
                            ?: "No model loaded",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            actions = {
                IconButton(onClick = vm::regenerate, enabled = !ui.generating && messages.isNotEmpty()) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                }
                IconButton(onClick = vm::newChat) {
                    Icon(Icons.Default.Add, contentDescription = "New chat")
                }
            },
        )

        ui.contextNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (ui.retrievedFrom.isNotEmpty()) {
            Text(
                "Using your documents: ${ui.retrievedFrom.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        ui.prefill?.let { (done, total) ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Reading the prompt… $done / $total", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }

            if (ui.streamingText.isNotEmpty() || ui.generating) {
                item {
                    StreamingBubble(
                        text = ui.streamingText,
                        activeTool = ui.activeTool,
                        toolTrail = ui.toolTrail,
                    )
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (loaded == null) "Load a model first" else "Ask anything…")
                    },
                    enabled = loaded != null,
                    maxLines = 6,
                )
                FilledIconButton(
                    onClick = {
                        if (ui.generating) {
                            vm.stop()
                        } else {
                            vm.send(draft)
                            draft = ""
                        }
                    },
                    enabled = loaded != null && (ui.generating || draft.isNotBlank()),
                ) {
                    Icon(
                        if (ui.generating) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = if (ui.generating) "Stop" else "Send",
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isUser = message.role == ChatTurn.USER
    val isTool = message.role == ChatTurn.TOOL
    if (isTool) return // tool traffic is shown as a trail, not as a bubble

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                message.imagePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        AsyncImage(
                            model = file,
                            contentDescription = "Generated image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .padding(bottom = 8.dp),
                        )
                    }
                }
                if (message.content.isNotBlank()) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
                message.statsLine?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, activeTool: String?, toolTrail: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                toolTrail.forEach { entry ->
                    Text(
                        "• $entry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                activeTool?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(12.dp).widthIn(min = 12.dp, max = 12.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.widthIn(min = 8.dp))
                        Text("Running $it…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (text.isEmpty() && activeTool == null) {
                    Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                } else if (text.isNotEmpty()) {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
