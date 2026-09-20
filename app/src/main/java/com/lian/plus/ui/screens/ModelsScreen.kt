package com.lian.plus.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lian.plus.core.LianRuntime.ModelLoadState
import com.lian.plus.core.model.FitLevel
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.DownloadStatus
import com.lian.plus.hub.HfFile
import com.lian.plus.hub.HuggingFaceApi
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.BrandChip
import com.lian.plus.ui.components.FitBadge
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.components.InlineDownloadBanner
import com.lian.plus.ui.theme.Lian

private enum class ModelsTab(val label: String) {
    INSTALLED("Installed"), BROWSE("Browse"), PICKS("Picks")
}

@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val installed by vm.installed.collectAsState()
    val ui by vm.ui.collectAsState()
    val loadState by vm.loadState.collectAsState()
    val downloads by vm.downloads.collectAsState()

    var tab by remember { mutableStateOf(ModelsTab.BROWSE) }
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }

    // Land on Installed once there is something there.
    LaunchedEffect(installed.isNotEmpty()) {
        if (installed.isNotEmpty()) tab = ModelsTab.INSTALLED
    }

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
                    "${installed.size} installed · ${formatBytes(ui.diskUsage)} used · " +
                        "${formatBytes(ui.freeStorage)} free",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

        // Active transfers, visible from whichever tab is open.
        downloads.filter { it.status != DownloadStatus.DONE }.forEach { job ->
            InlineDownloadBanner(
                fileName = job.fileName,
                percent = job.percent,
                detail = when (job.status) {
                    DownloadStatus.FAILED -> job.message ?: "Failed"
                    DownloadStatus.PAUSED -> "Paused at ${formatBytes(job.doneBytes)}"
                    else -> "${formatBytes(job.doneBytes)} / ${formatBytes(job.totalBytes)}" +
                        (job.secondsRemaining?.let { " · ${it / 60}m left" } ?: "")
                },
                paused = job.status != DownloadStatus.RUNNING,
                onPauseResume = {
                    if (job.status == DownloadStatus.RUNNING) vm.pauseDownload(job.id)
                    else vm.resumeDownload(job.id)
                },
                onCancel = { vm.cancelDownload(job.id) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
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
            ModelsTab.INSTALLED -> InstalledList(
                installed = installed,
                diskUsage = ui.diskUsage,
                activeTextId = (loadState as? ModelLoadState.Ready)?.model?.id,
                onActivate = vm::activate,
                onDelete = { pendingDelete = it },
                onBrowse = { tab = ModelsTab.BROWSE },
                modifier = Modifier.weight(1f),
            )

            ModelsTab.BROWSE -> BrowseTab(vm, ui, Modifier.weight(1f))

            ModelsTab.PICKS -> PicksList(
                models = vm.curated(),
                busy = ui.loadingRepo,
                fitOf = { vm.fitFor(it.approxSizeBytes, it.kind) },
                onDownload = vm::downloadCurated,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (ui.loadingRepo) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Lian.Surface,
            title = { Text("Reading the repository…", color = Lian.TextPrimary) },
            text = {
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    CircularProgressIndicator(color = Lian.Cyan)
                }
            },
            confirmButton = {},
        )
    }

    ui.openRepo?.let { detail ->
        val best = remember(detail) { vm.bestFileFor(detail) }
        AlertDialog(
            onDismissRequest = vm::closeRepo,
            containerColor = Lian.Surface,
            title = { Text(detail.summary.name, maxLines = 2, color = Lian.TextPrimary) },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    if (detail.ggufFiles.isEmpty()) {
                        item {
                            Text(
                                "This repository has no GGUF files, so none of the engines " +
                                    "here can read it.",
                                color = Lian.TextMuted,
                            )
                        }
                    }
                    items(detail.ggufFiles, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            fit = vm.fitFor(file, detail.summary),
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
                    "Frees ${model.sizeLabel}. The file is removed from this device; you " +
                        "can download it again at any time.",
                    color = Lian.TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(model); pendingDelete = null }) {
                    Text("Delete", color = Lian.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = Lian.TextMuted)
                }
            },
        )
    }
}

// ---------------------------------------------------------------- browse ---

@Composable
private fun BrowseTab(vm: ModelsViewModel, ui: ModelsUiState, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(ui.query.text) }
    val listState = rememberLazyListState()

    // Infinite scroll: ask for the next page as the end comes into view.
    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(atEnd) { if (atEnd) vm.loadMore() }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search Hugging Face…", color = Lian.TextMuted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                IconButton(onClick = { vm.setQuery { it.copy(text = text) } }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Lian.Cyan)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            colors = lianFieldColors(),
        )

        Text(
            "Type",
            style = MaterialTheme.typography.labelSmall,
            color = Lian.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrandChip("All", ui.query.task == null, { vm.setQuery { it.copy(task = null) } })
            HuggingFaceApi.Task.entries.forEach { task ->
                BrandChip(task.label, ui.query.task == task, { vm.setQuery { it.copy(task = task) } })
            }
        }

        Text(
            "Sort",
            style = MaterialTheme.typography.labelSmall,
            color = Lian.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HuggingFaceApi.SortOrder.entries.forEach { sort ->
                BrandChip(sort.label, ui.query.sort == sort, { vm.setQuery { it.copy(sort = sort) } })
            }
        }

        if (ui.browsing) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator(color = Lian.Cyan)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.results, key = { it.id }) { summary ->
                BrandCard(Modifier.fillMaxWidth(), onClick = { vm.openRepo(summary.id) }) {
                    Text(
                        summary.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Lian.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(summary.owner)
                            append(" · ").append(compact(summary.downloads)).append(" downloads")
                            if (summary.likes > 0) {
                                append(" · ").append(compact(summary.likes)).append(" likes")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                    summary.pipelineTag?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.Cyan)
                    }
                }
            }

            if (ui.loadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Lian.Cyan,
                        )
                    }
                }
            } else if (ui.results.isNotEmpty() && ui.nextCursor == null) {
                item {
                    Text(
                        "End of results",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

// ------------------------------------------------------------- installed ---

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
    diskUsage: Long,
    activeTextId: String?,
    onActivate: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
    onBrowse: () -> Unit,
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
                "Browse the Hub and pick one — each file says whether it fits this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = Lian.TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            GradientButton("Browse models", onBrowse, leadingIcon = Icons.Default.Search)
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
                            contentDescription = "Delete ${model.displayName}",
                            tint = Lian.Danger,
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

        item {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = Lian.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatBytes(diskUsage)} of model files. They live in this app's own " +
                        "private storage, so uninstalling Lian+ deletes every downloaded " +
                        "model with it — nothing is left behind on the device. Clearing the " +
                        "app's storage from Android settings does the same.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lian.TextMuted,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- picks ---

@Composable
private fun PicksList(
    models: List<CuratedModel>,
    busy: Boolean,
    fitOf: (CuratedModel) -> com.lian.plus.core.model.ModelFit,
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
                "Models known to behave well on phones. Everything is listed; the badge " +
                    "says what this device will make of it.",
                style = MaterialTheme.typography.bodySmall,
                color = Lian.TextMuted,
            )
        }
        items(models, key = { it.id }) { model ->
            val fit = fitOf(model)
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
                    FitBadge(fit)
                }
                Spacer(Modifier.height(10.dp))
                Text(model.blurb, style = MaterialTheme.typography.bodySmall, color = Lian.TextMuted)
                Spacer(Modifier.height(6.dp))
                Text(fit.detail, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (fit.isDownloadable) "Download" else fit.headline,
                    onClick = { onDownload(model) },
                    enabled = !busy && fit.isDownloadable,
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
    fit: com.lian.plus.core.model.ModelFit,
    recommended: Boolean,
    onPick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = fit.isDownloadable, onClick = onPick)
            .padding(vertical = 10.dp),
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
            Spacer(Modifier.width(8.dp))
            FitBadge(fit)
        }
        if (fit.level != FitLevel.FITS) {
            Text(
                fit.detail,
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
