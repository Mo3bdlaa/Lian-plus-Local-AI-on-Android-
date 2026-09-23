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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lian.plus.core.LianRuntime.ModelLoadState
import com.lian.plus.core.model.FitLevel
import com.lian.plus.core.model.ImagePipelineResolver
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import com.lian.plus.core.model.label
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.CuratedPipeline
import com.lian.plus.hub.DownloadStatus
import com.lian.plus.hub.HfAsset
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
                pipelines = vm.curatedPipelines(),
                busy = ui.loadingRepo,
                fitOf = { vm.fitFor(it.approxSizeBytes, it.kind) },
                onDownload = vm::downloadCurated,
                onDownloadPipeline = vm::downloadPipeline,
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
                    if (detail.assets.isEmpty()) {
                        item {
                            Text(
                                "This repository has no GGUF or safetensors files, so none " +
                                    "of the engines here can read it.",
                                color = Lian.TextMuted,
                            )
                        }
                    }
                    items(detail.assets, key = { it.displayName }) { asset ->
                        AssetRow(
                            asset = asset,
                            fit = vm.fitFor(asset, detail.summary),
                            pipelineNote = vm.pipelineNote(asset, detail),
                            recommended = asset == best,
                            onPick = {
                                vm.download(asset, vm.kindFor(asset.primary, detail.summary))
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
            // Resolving against the list we already have avoids a database
            // round trip per row; the resolver is a pure function over it.
            val pipeline = remember(model, installed) {
                if (model.kind == ModelKind.IMAGE) {
                    ImagePipelineResolver.resolve(model, installed)
                } else null
            }
            val incomplete = pipeline?.isComplete == false
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
                                append(if (model.role.isLoadable) kindLabel(model.kind) else model.role.label)
                                append(" · ").append(model.sizeLabel)
                                append(" · ").append(model.quant.tag)
                                model.contextTrained?.let { append(" · ${it / 1024}K ctx") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (model.role.isLoadable) Lian.TextMuted else Color(0xFFFFB454),
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
                if (pipeline != null && pipeline.arch.needsAssembly) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (incomplete) {
                            "${pipeline.arch.label} pipeline · still needs its " +
                                "${pipeline.missingLabel}. Look for it in the same " +
                                "repository as the checkpoint."
                        } else {
                            "${pipeline.arch.label} pipeline · complete, " +
                                pipeline.partLabel
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (incomplete) Color(0xFFFFB454) else Lian.Cyan,
                    )
                }
                if (model.role.isLoadable && !incomplete) {
                    Spacer(Modifier.height(12.dp))
                    GradientButton(
                        text = if (active) "Reload" else "Use this model",
                        onClick = { onActivate(model) },
                        leadingIcon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (incomplete) {
                    // Deliberately no button. Offering one here would load two
                    // thirds of a model and fail inside the engine, which is
                    // the failure this whole path exists to prevent.
                } else {
                    // Downloaded, but nothing can load it. Say why here rather
                    // than offering a button that only produces an error.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        model.role.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                }
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
    pipelines: List<CuratedPipeline>,
    busy: Boolean,
    fitOf: (CuratedModel) -> com.lian.plus.core.model.ModelFit,
    onDownload: (CuratedModel) -> Unit,
    onDownloadPipeline: (CuratedPipeline) -> Unit,
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
        items(pipelines, key = { it.id }) { pipeline ->
            // Judged on the whole set. A 3 GB transformer that needs a 2.5 GB
            // encoder beside it is a 6 GB decision, and showing the first
            // number alone is how a phone ends up with a model it cannot run.
            val fit = fitOf(
                CuratedModel(
                    id = pipeline.id,
                    title = pipeline.title,
                    repoId = pipeline.parts.first().repoId,
                    preferredFileHint = "",
                    kind = ModelKind.IMAGE,
                    approxSizeBytes = pipeline.totalBytes,
                    minTier = pipeline.minTier,
                    blurb = pipeline.blurb,
                    strengths = pipeline.strengths,
                ),
            )
            BrandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientIconTile(iconFor(ModelKind.IMAGE), size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pipeline.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Lian.TextPrimary,
                        )
                        Text(
                            "${pipeline.parts.size} files · ${formatBytes(pipeline.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                    }
                    FitBadge(fit)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    pipeline.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = Lian.TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                pipeline.parts.forEach { part ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            part.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                        Text(
                            formatBytes(part.approxSizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = Lian.TextMuted,
                        )
                    }
                }
                if (fit.isDownloadable) {
                    Spacer(Modifier.height(10.dp))
                    GradientButton(
                        text = "Download all ${pipeline.parts.size}",
                        onClick = { onDownloadPipeline(pipeline) },
                        leadingIcon = Icons.Default.Download,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
private fun AssetRow(
    asset: HfAsset,
    fit: com.lian.plus.core.model.ModelFit,
    pipelineNote: String?,
    recommended: Boolean,
    onPick: () -> Unit,
) {
    // A pipeline component - a VAE, a text encoder - is a deliberate download,
    // not a mistake: the newer families need one and it is a separate file.
    // What stays untappable is a companion that genuinely cannot be used, like
    // a vision projector or a lone shard.
    val selectable = (asset.role.isLoadable || asset.component != null) && fit.isDownloadable
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onPick)
            .padding(vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectable) Lian.TextPrimary else Lian.TextMuted,
                    fontWeight = if (recommended) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    buildString {
                        append(formatBytes(asset.totalBytes))
                        if (asset.isSplit) append(" · ${asset.files.size} parts")
                        if (recommended) append(" · best fit for this phone")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recommended) Lian.Cyan else Lian.TextMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                asset.component != null -> Text(
                    asset.component.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.Cyan,
                )

                asset.role.isLoadable -> FitBadge(fit)

                else -> Text(
                    asset.role.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )
            }
        }
        val note = when {
            asset.note != null -> asset.note
            asset.component != null ->
                "Part of an image pipeline. Download it alongside the checkpoint " +
                    "it belongs to."
            !asset.role.isLoadable -> asset.role.explanation
            pipelineNote != null -> pipelineNote
            fit.level != FitLevel.FITS -> fit.detail
            else -> null
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
