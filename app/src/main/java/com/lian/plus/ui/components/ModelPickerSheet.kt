package com.lian.plus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.ui.theme.Lian

/** What the sheet needs to know about an in-flight load. */
data class ModelLoadProgress(val modelId: String, val fraction: Float)

/**
 * Switches the active model without leaving the screen.
 *
 * Sending someone to a separate Models tab to change model, then back, loses
 * whatever they were in the middle of — and changing model is something you do
 * *because* of what is on screen, not before it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    kind: ModelKind,
    installed: List<InstalledModel>,
    activeId: String?,
    loading: ModelLoadProgress?,
    onPick: (InstalledModel) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Lian.Surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                when (kind) {
                    ModelKind.IMAGE -> "Image model"
                    ModelKind.EMBEDDING -> "Embedding model"
                    else -> "Text model"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Lian.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            val candidates = installed.filter { it.kind == kind }

            if (candidates.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GradientIconTile(
                        if (kind == ModelKind.IMAGE) Icons.Default.Image else Icons.Default.Chat,
                        size = 48.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Nothing installed yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = Lian.TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Download one and it shows up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { model ->
                        val isActive = model.id == activeId
                        val isLoading = loading?.modelId == model.id
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) { onPick(model) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp), Alignment.Center) {
                                    when {
                                        isLoading -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Lian.Cyan,
                                        )
                                        isActive -> Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Active",
                                            tint = Lian.Cyan,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        model.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Lian.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append(model.sizeLabel)
                                            append(" · ").append(model.quant.tag)
                                            model.contextTrained?.let {
                                                append(" · ${it / 1024}K ctx")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Lian.TextMuted,
                                    )
                                }
                            }
                            if (isLoading) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { loading.fraction },
                                    color = Lian.Cyan,
                                    trackColor = Lian.SurfaceRaised,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .border(1.dp, Lian.Outline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onBrowse)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = Lian.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Browse and download models",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
            }
        }
    }
}

/** A compact banner for an active download, shown inside other screens. */
@Composable
fun InlineDownloadBanner(
    fileName: String,
    percent: Int,
    detail: String,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$percent%", style = MaterialTheme.typography.titleSmall, color = Lian.Cyan)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            color = Lian.Cyan,
            trackColor = Lian.SurfaceRaised,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (paused) "Resume" else "Pause",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.Cyan,
                    modifier = Modifier.clickable(onClick = onPauseResume),
                )
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.Danger,
                    modifier = Modifier.clickable(onClick = onCancel),
                )
            }
        }
    }
}
