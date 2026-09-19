package com.lian.plus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lian.plus.core.LianRuntime.ModelLoadState
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.HfFile
import com.lian.plus.ui.components.SectionHeader

private enum class ModelsTab(val label: String) {
    INSTALLED("Installed"), RECOMMENDED("Recommended"), SEARCH("Search the Hub")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val installed by vm.installed.collectAsState()
    val ui by vm.ui.collectAsState()
    val loadState by vm.loadState.collectAsState()
    var tab by remember { mutableStateOf(ModelsTab.INSTALLED) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelsTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label) },
                )
            }
        }

        (loadState as? ModelLoadState.Loading)?.let { state ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "Loading ${state.model.displayName}…",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ui.download?.let { d ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        IconButton(onClick = vm::cancelDownload) {
                            Icon(Icons.Default.Close, contentDescription = "Pause download")
                        }
                    }
                    LinearProgressIndicator(
                        progress = { d.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(d.bytesDone)} of ${formatBytes(d.bytesTotal)}" +
                            if (d.bytesPerSecond > 0) " · ${formatBytes(d.bytesPerSecond)}/s" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        ui.message?.let { msg ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { vm.dismissMessage() },
            ) {
                Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (tab) {
            ModelsTab.INSTALLED -> InstalledList(
                installed = installed,
                diskUsage = ui.diskUsage,
                activeTextId = (loadState as? ModelLoadState.Ready)?.model?.id,
                onActivate = vm::activate,
                onDelete = { pendingDelete = it },
            )

            ModelsTab.RECOMMENDED -> CuratedList(
                models = vm.curatedFor(),
                busy = ui.loadingRepo || ui.download != null,
                onDownload = vm::downloadCurated,
            )

            ModelsTab.SEARCH -> Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search Hugging Face for GGUF models") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { vm.search(query) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                if (ui.searching) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.searchResults, key = { it.id }) { summary ->
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { vm.openRepo(summary.id) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    summary.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${summary.owner} · ${summary.downloads} downloads · " +
                                        "${summary.likes} likes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
            title = { Text(detail.summary.name, maxLines = 2) },
            text = {
                LazyColumn(Modifier.height(400.dp)) {
                    if (detail.ggufFiles.isEmpty()) {
                        item { Text("This repository has no GGUF files.") }
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
            confirmButton = { TextButton(onClick = vm::closeRepo) { Text("Close") } },
        )
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("This frees ${model.sizeLabel}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(model); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun InstalledList(
    installed: List<InstalledModel>,
    diskUsage: Long,
    activeTextId: String?,
    onActivate: (InstalledModel) -> Unit,
    onDelete: (InstalledModel) -> Unit,
) {
    if (installed.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No models yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Open Recommended and pick one that suits this phone, or search the Hub " +
                    "for something specific.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "${installed.size} file(s) · ${formatBytes(diskUsage)} on disk",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ModelKind.entries.forEach { kind ->
            val group = installed.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            item { SectionHeader(kind.name.lowercase().replace('_', ' ')) }
            items(group, key = { it.id }) { model ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (model.id == activeTextId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append(model.sizeLabel)
                                append(" · ").append(model.quant.tag)
                                model.parameterLabel?.let { append(" · ").append(it) }
                                model.contextTrained?.let { append(" · ${it / 1024}K ctx") }
                                model.architecture?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { onActivate(model) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.padding(2.dp))
                                Text(if (model.id == activeTextId) "Reload" else "Use")
                            }
                            OutlinedButton(onClick = { onDelete(model) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuratedList(
    models: List<CuratedModel>,
    busy: Boolean,
    onDownload: (CuratedModel) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Filtered to what this device can actually run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(models, key = { it.id }) { model ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        model.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${formatBytes(model.approxSizeBytes)} · ${model.repoId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(model.blurb, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        model.strengths.take(3).forEach { s ->
                            AssistChip(onClick = {}, label = { Text(s) })
                        }
                    }
                    Button(
                        onClick = { onDownload(model) },
                        enabled = !busy,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.padding(2.dp))
                        Text("Download")
                    }
                }
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
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 8.dp),
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
                    fontWeight = if (recommended) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    formatBytes(file.sizeBytes) + if (recommended) " · best fit for this phone" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Download, contentDescription = "Download")
        }
        warning?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
