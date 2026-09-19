package com.lian.plus.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lian.plus.core.LianRuntime
import com.lian.plus.data.AppSettings
import com.lian.plus.llm.ChatFormat
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val settings by runtime.settingsStore.settings.collectAsState(initial = AppSettings())
    val report by runtime.capability.collectAsState()
    val scope = rememberCoroutineScopeSafe()
    var docStatus by remember { mutableStateOf<String?>(null) }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                docStatus = "Indexing…"
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
                runtime.rag.addDocument(uri, name) { done, total ->
                    docStatus = "Embedding chunk $done of $total"
                }.fold(
                    onSuccess = { docStatus = "Indexed \"$name\"." },
                    onFailure = { docStatus = "Could not index it: ${it.message}" },
                )
            }
        }
    }

    fun edit(block: (AppSettings) -> AppSettings) {
        scope.launch { runtime.settingsStore.update(block) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Header("Behaviour")
        OutlinedTextField(
            value = settings.systemPrompt,
            onValueChange = { v -> edit { it.copy(systemPrompt = v) } },
            label = { Text("System prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Header("Sampling")
        SliderRow(
            label = "Temperature: %.2f".format(settings.temperature),
            value = settings.temperature,
            range = 0f..2f,
            hint = "0 makes the model deterministic; above ~1.2 it starts to wander.",
        ) { v -> edit { it.copy(temperature = v) } }

        SliderRow(
            label = "Top-p: %.2f".format(settings.topP),
            value = settings.topP,
            range = 0.1f..1f,
        ) { v -> edit { it.copy(topP = v) } }

        SliderRow(
            label = "Min-p: %.3f".format(settings.minP),
            value = settings.minP,
            range = 0f..0.3f,
            hint = "Cuts tokens far below the most likely one. Often better than top-p alone.",
        ) { v -> edit { it.copy(minP = v) } }

        SliderRow(
            label = "Repetition penalty: %.2f".format(settings.repeatPenalty),
            value = settings.repeatPenalty,
            range = 1f..1.5f,
        ) { v -> edit { it.copy(repeatPenalty = v) } }

        SliderRow(
            label = "Reply limit: ${settings.maxTokens} tokens",
            value = settings.maxTokens.toFloat(),
            range = 128f..4096f,
        ) { v -> edit { it.copy(maxTokens = v.toInt()) } }

        Header("Engine")
        val maxCtx = report?.maxContext ?: 32768
        SliderRow(
            label = "Context window: ${settings.contextSize} tokens",
            value = settings.contextSize.toFloat(),
            range = 512f..maxCtx.toFloat(),
            hint = "Bigger windows use more memory. Takes effect the next time a model loads.",
        ) { v -> edit { it.copy(contextSize = (v.toInt() / 512) * 512) } }

        SliderRow(
            label = "Threads: ${settings.threads}",
            value = settings.threads.toFloat(),
            range = 1f..(report?.profile?.cpuCores ?: 8).toFloat(),
            hint = "More than the number of performance cores usually makes things slower.",
        ) { v -> edit { it.copy(threads = v.toInt()) } }

        Text("KV cache precision", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "f16", 1 to "q8_0", 2 to "q4_0").forEach { (value, label) ->
                FilterChip(
                    selected = settings.kvCacheType == value,
                    onClick = { edit { it.copy(kvCacheType = value) } },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "Quantising the KV cache roughly halves the memory a long context needs, " +
                "at a small cost in quality.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text("Chat template", style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScrollRow(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatFormat.entries.forEach { format ->
                FilterChip(
                    selected = settings.chatFormatId == format.id,
                    onClick = { edit { it.copy(chatFormatId = format.id) } },
                    label = { Text(format.label) },
                )
            }
        }

        ToggleRow(
            title = "Lock weights in memory",
            subtitle = "Stops the system paging the model out. Faster, but the RAM is gone " +
                "for everything else — only worth it with plenty to spare.",
            checked = settings.useMlock,
        ) { v -> edit { it.copy(useMlock = v) } }

        Header("Tools")
        ToggleRow(
            title = "Let the model use tools",
            subtitle = "Clock, calculator, device info, memory and document search.",
            checked = settings.toolsEnabled,
        ) { v -> edit { it.copy(toolsEnabled = v) } }

        ToggleRow(
            title = "Web search and fetch",
            subtitle = "The only feature that sends your words off the device. Off by default.",
            checked = settings.webSearchEnabled,
        ) { v -> edit { it.copy(webSearchEnabled = v) } }

        if (settings.webSearchEnabled) {
            OutlinedTextField(
                value = settings.searxngUrl,
                onValueChange = { v -> edit { it.copy(searxngUrl = v) } },
                label = { Text("SearXNG instance (optional)") },
                supportingText = {
                    Text("Leave empty to use DuckDuckGo. A SearXNG instance is more reliable.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Header("Your documents")
        ToggleRow(
            title = "Search my documents automatically",
            subtitle = "Looks for relevant passages before every reply.",
            checked = settings.ragEnabled,
        ) { v -> edit { it.copy(ragEnabled = v) } }

        Button(
            onClick = { pickDocument.launch(arrayOf("text/*", "application/json")) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { Text("Add a text document") }
        docStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (!runtime.embedder.isLoaded) {
            Text(
                "No embedding model is loaded, so search falls back to keyword matching. " +
                    "Install one from Models → Recommended for much better results.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Header("Hugging Face")
        OutlinedTextField(
            value = settings.huggingFaceToken,
            onValueChange = { v -> edit { it.copy(huggingFaceToken = v) } },
            label = { Text("Access token (optional)") },
            supportingText = {
                Text("Only needed for gated repositories. Sent to huggingface.co and nowhere else.")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Where your data goes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Conversations, documents and generated images stay in this app's private " +
                        "storage and are never uploaded. The app reaches the network for exactly " +
                        "two things: downloading models from Hugging Face, and web search when " +
                        "you switch it on above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Header(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier =
    horizontalScroll(rememberScrollState())
