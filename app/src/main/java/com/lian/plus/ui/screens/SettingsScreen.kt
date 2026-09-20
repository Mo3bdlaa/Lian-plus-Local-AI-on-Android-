package com.lian.plus.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.device.ComputeDevices
import com.lian.plus.data.AppSettings
import com.lian.plus.llm.ChatFormat
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.BrandChip
import com.lian.plus.ui.components.GroupLabel
import com.lian.plus.ui.components.SettingRow
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val settings by runtime.settingsStore.settings.collectAsState(initial = AppSettings())
    val report by runtime.capability.collectAsState()
    val measured by runtime.benchmark.collectAsState()
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

    Column(Modifier.fillMaxSize().background(Lian.Background)) {
        LianTopBar(title = "Settings", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            GroupLabel("General")
            BrandCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                SettingRow(
                    title = "This device",
                    subtitle = "Hardware report and what it can run",
                    icon = Icons.Default.Memory,
                    onClick = { onNavigate("device") },
                )
                SettingRow(
                    title = "Local API server",
                    subtitle = "Serve this model over an OpenAI-compatible API",
                    icon = Icons.Default.Dns,
                    value = if (settings.serverEnabled) "On" else "Off",
                    onClick = { onNavigate("server") },
                )
                SettingRow(
                    title = "Playground",
                    subtitle = "Send raw prompts with no chat template",
                    icon = Icons.Default.Science,
                    onClick = { onNavigate("playground") },
                )
                SettingRow(
                    title = "About",
                    icon = Icons.Default.Info,
                    onClick = { onNavigate("about") },
                )
            }

            GroupLabel("Assistant")
            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { v -> edit { it.copy(systemPrompt = v) } },
                label = { Text("System prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                colors = lianFieldColors(),
            )

            GroupLabel("Sampling")
            BrandCard(Modifier.fillMaxWidth()) {
                SliderRow(
                    "Temperature: %.2f".format(settings.temperature),
                    settings.temperature, 0f..2f,
                    "0 is deterministic; above about 1.2 the model starts to wander.",
                ) { v -> edit { it.copy(temperature = v) } }

                SliderRow("Top-p: %.2f".format(settings.topP), settings.topP, 0.1f..1f) { v ->
                    edit { it.copy(topP = v) }
                }

                SliderRow(
                    "Min-p: %.3f".format(settings.minP), settings.minP, 0f..0.3f,
                    "Cuts tokens far below the most likely one. Often better than top-p alone.",
                ) { v -> edit { it.copy(minP = v) } }

                SliderRow(
                    "Repetition penalty: %.2f".format(settings.repeatPenalty),
                    settings.repeatPenalty, 1f..1.5f,
                ) { v -> edit { it.copy(repeatPenalty = v) } }

                SliderRow(
                    "Reply limit: ${settings.maxTokens} tokens",
                    settings.maxTokens.toFloat(), 128f..4096f,
                ) { v -> edit { it.copy(maxTokens = v.toInt()) } }
            }

            GroupLabel("Hardware acceleration")
            BrandCard(Modifier.fillMaxWidth()) {
                val gpu = remember { ComputeDevices.gpu() }
                val reason = remember { ComputeDevices.gpuUnavailableReason() }

                if (gpu != null && !measured.gpuCrashed) {
                    Text(gpu.label, style = MaterialTheme.typography.bodyLarge, color = Lian.TextPrimary)
                    // The measurement is the honest version of this decision:
                    // a renderer string cannot tell you whether offloading will
                    // pay for the memory it takes.
                    measured.gpuSpeedup?.let { speedup ->
                        Text(
                            "Measured at %.1f GFLOP/s — %.1f× this phone's CPU."
                                .format(measured.gpuGflops, speedup),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (measured.gpuWorthUsing) Lian.Cyan else Lian.TextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SliderRow(
                        label = when (settings.gpuLayers) {
                            0 -> "GPU layers: none (CPU only)"
                            else -> "GPU layers: ${settings.gpuLayers}"
                        },
                        value = settings.gpuLayers.toFloat(),
                        range = 0f..64f,
                        hint = "Offloading is worth most for reading long prompts. Token " +
                            "generation is limited by the memory bus the GPU shares with " +
                            "the CPU, so expect a smaller gain there. Changing this " +
                            "reloads the model.",
                    ) { v -> edit { it.copy(gpuLayers = v.toInt()) } }
                } else if (measured.gpuCrashed) {
                    Text(
                        "CPU only",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Lian.TextPrimary,
                    )
                    Text(
                        "The GPU driver faulted the last time the app gave it compute " +
                            "work, taking the app down with it, so it is not used. " +
                            "\"Re-check this device\" on the Device screen tries again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        "CPU only",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Lian.TextPrimary,
                    )
                    Text(
                        reason ?: "No GPU compute device was offered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            GroupLabel("Engine")
            BrandCard(Modifier.fillMaxWidth()) {
                val maxCtx = report?.maxContext ?: 32768
                SliderRow(
                    "Context window: ${settings.contextSize} tokens",
                    settings.contextSize.toFloat(), 512f..maxCtx.toFloat(),
                    "Bigger windows use more memory. Applied the next time a model loads.",
                ) { v -> edit { it.copy(contextSize = (v.toInt() / 512) * 512) } }

                SliderRow(
                    "Threads: ${settings.threads}",
                    settings.threads.toFloat(),
                    1f..(report?.profile?.cpuCores ?: 8).toFloat(),
                    "More than the number of performance cores usually makes things slower.",
                ) { v -> edit { it.copy(threads = v.toInt()) } }

                Spacer(Modifier.height(10.dp))
                Text(
                    "KV cache precision",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "f16", 1 to "q8_0", 2 to "q4_0").forEach { (value, label) ->
                        BrandChip(label, settings.kvCacheType == value, {
                            edit { it.copy(kvCacheType = value) }
                        })
                    }
                }
                Text(
                    "Quantising the KV cache roughly halves the memory a long context " +
                        "needs, at a small cost in quality.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "Chat template",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatFormat.entries.forEach { format ->
                        BrandChip(format.label, settings.chatFormatId == format.id, {
                            edit { it.copy(chatFormatId = format.id) }
                        })
                    }
                }
            }

            GroupLabel("Tools")
            BrandCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                ToggleRow(
                    "Let the model use tools",
                    "Clock, calculator, device info, memory and document search.",
                    settings.toolsEnabled,
                ) { v -> edit { it.copy(toolsEnabled = v) } }

                ToggleRow(
                    "Web search and fetch",
                    "The only feature that sends your words off the device. Off by default.",
                    settings.webSearchEnabled,
                    icon = Icons.Default.Search,
                ) { v -> edit { it.copy(webSearchEnabled = v) } }
            }

            if (settings.webSearchEnabled) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.searxngUrl,
                    onValueChange = { v -> edit { it.copy(searxngUrl = v) } },
                    label = { Text("SearXNG instance (optional)") },
                    supportingText = {
                        Text("Leave empty to use DuckDuckGo. SearXNG is more reliable.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = lianFieldColors(),
                )
            }

            GroupLabel("Your documents")
            BrandCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                ToggleRow(
                    "Search my documents automatically",
                    "Looks for relevant passages before every reply.",
                    settings.ragEnabled,
                    icon = Icons.Default.Description,
                ) { v -> edit { it.copy(ragEnabled = v) } }

                SettingRow(
                    title = "Add a text document",
                    subtitle = docStatus ?: "Indexed locally; nothing is uploaded",
                    icon = Icons.Default.Description,
                    onClick = { pickDocument.launch(arrayOf("text/*", "application/json")) },
                )
            }

            if (!runtime.embedder.isLoaded) {
                Text(
                    "No embedding model is loaded, so document search falls back to keyword " +
                        "matching. Install one from Models → Recommended for much better results.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            GroupLabel("Hugging Face")
            OutlinedTextField(
                value = settings.huggingFaceToken,
                onValueChange = { v -> edit { it.copy(huggingFaceToken = v) } },
                label = { Text("Access token (optional)") },
                supportingText = {
                    Text("Only for gated repositories. Sent to huggingface.co and nowhere else.")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = lianFieldColors(),
            )

            GroupLabel("Privacy")
            BrandCard(Modifier.fillMaxWidth()) {
                Row {
                    androidx.compose.material3.Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Lian.TextMuted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        "Conversations, documents and generated images stay in this app's " +
                            "private storage and are never uploaded. The app reaches the " +
                            "network for exactly two things: downloading models from Hugging " +
                            "Face, and web search when you switch it on above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lian.TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
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
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Lian.TextPrimary)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = Lian.Purple,
                    uncheckedThumbColor = Lian.TextMuted,
                    uncheckedTrackColor = Lian.SurfaceRaised,
                    uncheckedBorderColor = Lian.Outline,
                ),
            )
        },
    )
}
