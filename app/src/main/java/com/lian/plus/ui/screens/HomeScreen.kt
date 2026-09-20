package com.lian.plus.ui.screens

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lian.plus.core.LianRuntime
import com.lian.plus.data.db.ChatEntity
import com.lian.plus.data.db.GeneratedImageEntity
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.components.LianWordmark
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.concurrent.TimeUnit

/** One entry in the "Recent" list — either a conversation or a picture. */
sealed interface RecentItem {
    val timestamp: Long

    data class Conversation(val chat: ChatEntity) : RecentItem {
        override val timestamp get() = chat.updatedAt
    }

    data class Picture(val image: GeneratedImageEntity) : RecentItem {
        override val timestamp get() = image.createdAt
    }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val runtime = LianRuntime.get(app)

    val loadedModel = runtime.llm.loaded

    val recent: StateFlow<List<RecentItem>> = combine(
        runtime.database.chats().observeAll(),
        runtime.database.images().observeAll(),
    ) { chats, images ->
        // Only conversations that actually went somewhere; an empty "New chat"
        // in the recent list is noise.
        val fromChats = chats
            .filter { it.title != "New chat" }
            .map { RecentItem.Conversation(it) }
        val fromImages = images.map { RecentItem.Picture(it) }
        (fromChats + fromImages).sortedByDescending { it.timestamp }.take(8)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

private data class HomeAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenChat: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val recent by vm.recent.collectAsState()
    val loaded by vm.loadedModel.collectAsState()

    val actions = listOf(
        HomeAction("Chat", "Talk with local LLMs", Icons.Default.Chat, "chat"),
        HomeAction("Create", "Generate images", Icons.Default.Image, "create"),
        HomeAction("Models", "Manage & download", Icons.Default.Storage, "models"),
        HomeAction("Playground", "Experiment freely", Icons.Default.Science, "playground"),
    )

    Box(Modifier.fillMaxSize().background(Lian.Background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Lian.Purple.copy(alpha = 0.22f), Color.Transparent),
                        radius = 620f,
                    ),
                ),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LianWordmark(width = 96.dp)
                    IconButton(onClick = { onNavigate("settings") }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Lian.TextMuted,
                        )
                    }
                }
            }

            item {
                Text(
                    loaded?.let { "${it.model.displayName} · ${it.contextSize / 1024}K context" }
                        ?: "No model loaded yet — open Models to pick one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loaded != null) Lian.Success else Lian.TextMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Two-by-two action grid. A LazyVerticalGrid nested in a LazyColumn
            // needs a fixed height to measure, so plain Rows are simpler here.
            items(actions.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { action ->
                        BrandCard(
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(action.route) },
                        ) {
                            GradientIconTile(action.icon)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                action.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Lian.TextPrimary,
                            )
                            Text(
                                action.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Lian.TextMuted,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        color = Lian.TextPrimary,
                    )
                    if (recent.isNotEmpty()) {
                        Text(
                            "See all",
                            style = MaterialTheme.typography.bodySmall,
                            color = Lian.Cyan,
                            modifier = Modifier.clickable { onNavigate("chat") },
                        )
                    }
                }
            }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Start a conversation or generate an image and it " +
                            "will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                }
            }

            items(recent) { entry ->
                RecentRow(
                    entry = entry,
                    onClick = {
                        when (entry) {
                            is RecentItem.Conversation -> onOpenChat(entry.chat.id)
                            is RecentItem.Picture -> onNavigate("create")
                        }
                    },
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun RecentRow(entry: RecentItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (entry) {
            is RecentItem.Picture -> AsyncImage(
                model = File(entry.image.filePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)),
            )
            is RecentItem.Conversation -> GradientIconTile(Icons.Default.Chat, size = 44.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (entry) {
                    is RecentItem.Picture -> entry.image.prompt
                    is RecentItem.Conversation -> entry.chat.title
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (entry is RecentItem.Picture) "Image" else "Chat")
                    append(" · ")
                    append(relativeTime(entry.timestamp))
                },
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
            )
        }
    }
}

/** Coarse "when was this" label — precision past a day is not useful here. */
private fun relativeTime(millis: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - millis)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} week${if (days / 7 == 1L) "" else "s"} ago"
        else -> "${days / 30} month${if (days / 30 == 1L) "" else "s"} ago"
    }
}
