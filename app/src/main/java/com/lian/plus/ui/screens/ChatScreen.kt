package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lian.plus.data.db.MessageEntity
import com.lian.plus.llm.ChatTurn
import com.lian.plus.ui.ChatDeepLink
import com.lian.plus.core.LianRuntime.ModelLoadState
import com.lian.plus.core.model.ModelKind
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.components.ModelLoadProgress
import com.lian.plus.ui.components.ModelPickerSheet
import kotlinx.coroutines.launch
import com.lian.plus.ui.theme.Lian
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBrowseModels: () -> Unit, vm: ChatViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val messages by vm.messages.collectAsState()
    val loaded by vm.loadedModel.collectAsState()
    val chats by vm.chats.collectAsState()
    val installedModels by vm.installedModels.collectAsState()
    val modelLoadState by vm.modelLoadState.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var imageActionsFor by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()

    // Home can ask for a specific conversation; take it once and clear it.
    LaunchedEffect(Unit) {
        ChatDeepLink.consume()?.let(vm::selectChat)
    }

    LaunchedEffect(messages.size, ui.streamingText) {
        val target = messages.size + if (ui.streamingText.isNotEmpty() || ui.generating) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lian.Background)
            .imePadding(),
    ) {
        // Header carries the model chip, which is the one piece of state that
        // changes what every reply will look like.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { showHistory = true }) {
                GradientIconTile(Icons.Default.History, size = 38.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(
                // The model chip switches model; history is the icon beside it.
                Modifier.weight(1f).clickable { showModelPicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        loaded?.model?.displayName ?: "No model",
                        style = MaterialTheme.typography.titleSmall,
                        color = Lian.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "Change model",
                        tint = Lian.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    loaded?.let { "Local · ${it.contextSize / 1024}K context" } ?: "Local",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.Cyan,
                )
            }
            IconButton(
                onClick = vm::regenerate,
                enabled = !ui.generating && messages.isNotEmpty(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = Lian.TextMuted)
            }
            IconButton(onClick = vm::newChat) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = Lian.TextMuted)
            }
        }

        ui.contextNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }
        if (ui.retrievedFrom.isNotEmpty()) {
            Text(
                "From your documents: ${ui.retrievedFrom.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
                color = Lian.Cyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }
        ui.residencyNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = Lian.Cyan,
                modifier = Modifier
                    .clickable { vm.clearResidencyNote() }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (ui.generating && ui.imageTotalSteps > 0) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    if (ui.imageStep == 0) {
                        "Preparing the image — ${ui.imageSeconds}s"
                    } else {
                        "Step ${ui.imageStep} of ${ui.imageTotalSteps} — ${ui.imageSeconds}s"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
                LinearProgressIndicator(
                    progress = {
                        if (ui.imageTotalSteps > 0) {
                            ui.imageStep.toFloat() / ui.imageTotalSteps
                        } else 0f
                    },
                    color = Lian.Cyan,
                    trackColor = Lian.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ui.prefill?.let { (done, total) ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "Reading the prompt… $done / $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    color = Lian.Cyan,
                    trackColor = Lian.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (messages.isEmpty() && !ui.generating) {
            EmptyChat(modifier = Modifier.weight(1f), hasModel = loaded != null)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, onImageTap = { imageActionsFor = message })
                }
                if (ui.streamingText.isNotEmpty() || ui.generating) {
                    item {
                        StreamingBubble(ui.streamingText, ui.activeTool, ui.toolTrail)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The mode decides what Send does. An explicit switch beats hoping
            // the model works out that "a lion on a hill" wanted a picture.
            val imageMode = ui.mode == ComposerMode.IMAGE
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (imageMode) Lian.gradient
                        else androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Lian.Surface, Lian.Surface),
                        ),
                    )
                    .border(1.dp, if (imageMode) Color.Transparent else Lian.Outline, CircleShape)
                    .clickable {
                        vm.setMode(if (imageMode) ComposerMode.TEXT else ComposerMode.IMAGE)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = if (imageMode) "Switch to text" else "Switch to image",
                    tint = if (imageMode) Color.White else Lian.TextMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (ui.mode == ComposerMode.IMAGE) {
                            "Describe the image…"
                        } else {
                            "Message Lian+…"
                        },
                        color = Lian.TextMuted,
                    )
                },
                enabled = true,
                maxLines = 6,
                shape = RoundedCornerShape(18.dp),
                colors = lianFieldColors(),
            )
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (ui.generating || draft.isNotBlank()) Lian.gradient
                        else androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Lian.SurfaceRaised, Lian.SurfaceRaised),
                        ),
                    )
                    .clickable(enabled = ui.generating || draft.isNotBlank()) {
                        if (ui.generating) {
                            vm.stop()
                        } else {
                            vm.send(draft)
                            draft = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (ui.generating) Icons.Default.Stop else Icons.Default.Send,
                    contentDescription = if (ui.generating) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    // Tapping a generated image opens the iteration actions. This is where a
    // picture stops being an end product and becomes a draft.
    imageActionsFor?.let { message ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { imageActionsFor = null },
            containerColor = Lian.Surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    "This image",
                    style = MaterialTheme.typography.titleMedium,
                    color = Lian.TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ImageAction(Icons.Default.Refresh, "Generate again", "Same prompt, new seed") {
                    imageActionsFor = null
                    vm.regenerateImage(message)
                }
                ImageAction(Icons.Default.Edit, "Edit the prompt", "Load it back into the box") {
                    scope.launch {
                        vm.promptOf(message)?.let {
                            draft = it
                            vm.setMode(ComposerMode.IMAGE)
                        }
                        imageActionsFor = null
                    }
                }
                ImageAction(Icons.Default.Download, "Save to gallery", null) {
                    message.imagePath?.let { vm.saveToGallery(context, java.io.File(it)) }
                    imageActionsFor = null
                }
                ImageAction(Icons.Default.Share, "Share", null) {
                    message.imagePath?.let { vm.share(context, java.io.File(it)) }
                    imageActionsFor = null
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            kind = ModelKind.TEXT,
            installed = installedModels,
            activeId = loaded?.model?.id,
            loading = (modelLoadState as? ModelLoadState.Loading)?.let {
                ModelLoadProgress(it.model.id, it.fraction)
            },
            onPick = { vm.loadModel(it) },
            onBrowse = {
                showModelPicker = false
                onBrowseModels()
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = sheetState,
            containerColor = Lian.Surface,
        ) {
            Text(
                "Conversations",
                style = MaterialTheme.typography.titleMedium,
                color = Lian.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                items(chats, key = { it.id }) { chat ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectChat(chat.id)
                                showHistory = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chat.id == ui.chatId) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Lian.Cyan,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            chat.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Lian.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.deleteChat(chat.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Lian.TextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    ui.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            containerColor = Lian.Surface,
            title = { Text("Something went wrong", color = Lian.TextPrimary) },
            text = { Text(message, color = Lian.TextMuted) },
            confirmButton = {
                TextButton(onClick = vm::clearError) { Text("OK", color = Lian.Cyan) }
            },
        )
    }
}

@Composable
private fun ImageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Lian.Cyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Lian.TextPrimary)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
            }
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier, hasModel: Boolean) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientIconTile(Icons.Default.Send, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            if (hasModel) "Ask anything" else "No model loaded",
            style = MaterialTheme.typography.titleMedium,
            color = Lian.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasModel) {
                "Everything runs on this phone. Nothing is sent anywhere."
            } else {
                "Open the Models tab and pick one that suits this device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Lian.TextMuted,
            modifier = Modifier.padding(horizontal = 40.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, onImageTap: () -> Unit = {}) {
    val isUser = message.role == ChatTurn.USER
    if (message.role == ChatTurn.TOOL) return // shown as a trail, not a bubble

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = if (isUser) 18.dp else 6.dp,
            bottomEnd = if (isUser) 6.dp else 18.dp,
        )
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(
                    when {
                        message.isError -> Color(0xFF3A1620)
                        isUser -> Lian.Purple.copy(alpha = 0.22f)
                        else -> Lian.Surface
                    },
                )
                .border(
                    1.dp,
                    when {
                        message.isError -> Lian.Danger.copy(alpha = 0.5f)
                        isUser -> Lian.Purple.copy(alpha = 0.45f)
                        else -> Lian.Outline
                    },
                    shape,
                )
                .padding(12.dp),
        ) {
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
                            .clickable(onClick = onImageTap)
                            .padding(bottom = 8.dp),
                    )
                }
            }
            if (message.content.isNotBlank()) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isError) Lian.Danger else Lian.TextPrimary,
                )
            }
            message.statsLine?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, activeTool: String?, toolTrail: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        val shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(Lian.Surface)
                .border(1.dp, Lian.Outline, shape)
                .padding(12.dp),
        ) {
            toolTrail.forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = Lian.Cyan)
            }
            activeTool?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Lian.Cyan,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Running $it…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                }
            }
            if (text.isEmpty() && activeTool == null) {
                Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = Lian.TextMuted)
            } else if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Lian.TextPrimary)
            }
        }
    }
}
