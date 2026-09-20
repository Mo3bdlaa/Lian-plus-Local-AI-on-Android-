package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lian.plus.core.LianRuntime.ModelLoadState
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.HfFile
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.BrandChip
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.theme.Lian

private enum class ModelsTab(val label: String) {
    DOWNLOADED("Downloaded"), RECOMMENDED("Recommended"), SEARCH("Search")
}

@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val installed by vm.installed.collectAsState()
    val ui by vm.ui.collectAsState()
    val loadState by vm.loadState.collectAsState()
    val report by vm.capability.collectAsState()

    var tab by remember { mutableStateOf(ModelsTab.DOWNLOADED) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }

    Column(Modifier.fillMaxSize().background(Lian.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientIconTile(Icons.Default.Tune, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Models", style = MaterialTheme.typography.titleMedium, color = Lian.TextPrimary)
                Text(
                    "${installed.size} installed · ${formatBytes(ui.diskUsage)} on disk",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelsTab.entries.forEach { entry ->
                BrandChip(entry.label, tab == entry, { tab = entry })
            }
        }

        (loadState as? ModelLoadState.Loading)?.let { state ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Loading ${state.model.displayName}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    color = Lian.Cyan,
                    trackColor = Lian.SurfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ui.download?.let { d ->
            BrandCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                highlighted = true,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lian.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(d.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = Lian.Cyan,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { d.fraction },
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
                    Text(
                        "${formatBytes(d.bytesDone)} / ${formatBytes(d.bytesTotal)}" +
                            if (d.bytesPerSecond > 0) " · ${formatBytes(d.bytesPerSecond)}/s" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                    TextButton(onClick = vm::cancelDownload) {
                        Text("Cancel", color = Lian.Danger)
                    }
                }
            }
        }

        ui.message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = Lian.Cyan,
                modifier = Modifier
                    .clickable { vm.dismissMessage() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        when (tab) {
            ModelsTab.DOWNLOADED -> InstalledList(
                installed = installed,
                activeTextId = (loadState as? ModelLoadState.Ready)?.model?.id,
                onActivate = vm::activate,
                onDelete = { pendingDelete = it },
                modifier = Modifier.weight(1f),
            )

            ModelsTab.RECOMMENDED -> CuratedList(
                models = vm.curatedFor(),
                busy = ui.loadingRepo || ui.download != null,
                onDownload = vm::downloadCurated,
                modifier = Modifier.weight(1f),
            )

            ModelsTab.SEARCH -> Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search Hugging Face…", color = Lian.TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        IconButton(onClick = { vm.search(query) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Lian.Cyan)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = lianFieldColors(),
                )
                if (ui.searching) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Lian.Cyan)
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ui.searchResults, key = { it.id }) { summary ->
                        BrandCard(
                            Modifier.fillMaxWidth(),
                            onClick = { vm.openRepo(summary.id) },
                        ) {
                            Text(
                                summary.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Lian.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${summary.owner} · ${summary.downloads} downloads",
                                style = MaterialTheme.typography.labelSmall,
                                color = Lian.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }

    ui.openRepo?.let { detail ->
        val best = remember(detail) { vm.bestFileFor(detail) }
        AlertDialog(
            onDismissRequest = vm::closeRepo,
            containerColor = Lian.Surface,
            title = {
                Text(detail.summary.name, maxLines = 2, color = Lian.TextPrimary)
            },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    if (detail.ggufFiles.isEmpty()) {
                        item { Text("This repository has no GGUF files.", color = Lian.TextMuted) }
                    }
                    items(detail.ggufFiles, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            warning = vm.warnsAbout(file),
                            recommended = file == best,
                            onPick = {
                                vm.download(file, vm.kindFor(file, detail.summary))
                                vm.closeRepo()
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::closeRepo) { Text("Close", color = Lian.Cyan) }
            },
        )
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Lian.Surface,
            title = { Text("Delete ${model.displayName}?", color = Lian.TextPrimary) },
            text = {
                Text(
                    "This frees ${model.sizeLabel}. You can download it again later.",
                    color = Lian.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(model); pendingDelete = null }) {
                    Text("Delete", color = Lian.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep", color = Lian.TextMuted) }
            },
        )
    }
}

private fun iconFor(kind: ModelKind): ImageVector = when (kind) {
    ModelKind.IMAGE, ModelKind.IMAGE_COMPONENT -> Icons.Default.Image
    else -> Icons.Default.Chat
}

private fun kindLabel(kind: ModelKind): String = when (kind) {
    ModelKind.TEXT -> "Chat"
    ModelKind.EMBEDDING -> "Embedding"
    ModelKind.IMAGE -> "Image"
    ModelKind.IMAGE_COMPONENT -> "Component"
}

@Composable
private fun InstalledList(
    installed: List<InstalledModel>,
    activeTextId: String?,
    onActivate: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (installed.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientIconTile(Icons.Default.Download, size = 56.dp)
            Spacer(Modifier.height(14.dp))
            Text("No models yet", style = MaterialTheme.typography.titleMedium, color = Lian.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Open Recommended — it is already filtered to what this phone can run.",
                style = MaterialTheme.typography.bodySmall,
                color = Lian.TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(installed, key = { it.id }) { model ->
            val active = model.id == activeTextId
            BrandCard(Modifier.fillMaxWidth(), highlighted = active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientIconTile(iconFor(model.kind), size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Lian.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(kindLabel(model.kind))
                                append(" · ").append(model.sizeLabel)
                                append(" · ").append(model.quant.tag)
                                model.contextTrained?.let { append(" · ${it / 1024}K ctx") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                    }
                    IconButton(onClick = { onDelete(model) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Lian.TextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (active) "Reload" else "Use this model",
                    onClick = { onActivate(model) },
                    leadingIcon = Icons.Default.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CuratedList(
    models: List<CuratedModel>,
    busy: Boolean,
    onDownload: (CuratedModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Filtered to what this device can actually run.",
                style = MaterialTheme.typography.bodySmall,
                color = Lian.TextMuted,
            )
        }
        items(models, key = { it.id }) { model ->
            BrandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientIconTile(iconFor(model.kind), size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Lian.TextPrimary,
                        )
                        Text(
                            "${kindLabel(model.kind)} · ${formatBytes(model.approxSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(model.blurb, style = MaterialTheme.typography.bodySmall, color = Lian.TextMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.strengths.take(3).forEach {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Lian.SurfaceRaised)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = Lian.TextMuted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = "Download",
                    onClick = { onDownload(model) },
                    enabled = !busy,
                    leadingIcon = Icons.Default.Download,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HfFile,
    warning: String?,
    recommended: Boolean,
    onPick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                    fontWeight = if (recommended) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    formatBytes(file.sizeBytes) +
                        if (recommended) " · best fit for this phone" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recommended) Lian.Cyan else Lian.TextMuted,
                )
            }
            Icon(Icons.Default.Download, contentDescription = "Download", tint = Lian.Cyan)
        }
        warning?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Lian.Danger,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.Danger)
            }
        }
    }
}
