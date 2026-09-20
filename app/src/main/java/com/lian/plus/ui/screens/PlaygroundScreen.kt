package com.lian.plus.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lian.plus.core.LianRuntime
import com.lian.plus.llm.LlmEvent
import com.lian.plus.llm.SamplingParams
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.BrandChip
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaygroundState(
    val running: Boolean = false,
    val output: String = "",
    val stats: String? = null,
    val error: String? = null,
)

class PlaygroundViewModel(app: Application) : AndroidViewModel(app) {
    private val runtime = LianRuntime.get(app)

    val loadedModel = runtime.llm.loaded

    private val _state = MutableStateFlow(PlaygroundState())
    val state: StateFlow<PlaygroundState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Sends the prompt to the model exactly as typed — no chat template, no
     * tools, no retrieval. That is the point of this screen: it is the only
     * place to see what the raw model does with raw text.
     */
    fun run(prompt: String, maxTokens: Int, temperature: Float) {
        if (prompt.isBlank() || _state.value.running) return
        job = viewModelScope.launch {
            if (!runtime.llm.isLoaded) {
                _state.value = PlaygroundState(error = "Load a text model first.")
                return@launch
            }
            _state.value = PlaygroundState(running = true)
            val sb = StringBuilder()
            runtime.llm.generate(
                prompt,
                SamplingParams(maxTokens = maxTokens, temperature = temperature),
            ).collect { event ->
                when (event) {
                    is LlmEvent.Token -> {
                        sb.append(event.text)
                        _state.value = _state.value.copy(output = sb.toString())
                    }
                    is LlmEvent.Finished -> _state.value = _state.value.copy(
                        running = false,
                        stats = "%.1f tok/s · %d generated · %d prompt".format(
                            event.stats.tokensPerSecond,
                            event.stats.generatedTokens,
                            event.stats.promptTokens,
                        ),
                    )
                    is LlmEvent.Error -> _state.value =
                        _state.value.copy(running = false, error = event.message)
                    else -> Unit
                }
            }
            _state.value = _state.value.copy(running = false)
        }
    }

    fun stop() {
        runtime.llm.cancel()
        job?.cancel()
        _state.value = _state.value.copy(running = false)
    }
}

@Composable
fun PlaygroundScreen(onBack: () -> Unit, vm: PlaygroundViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val loaded by vm.loadedModel.collectAsState()

    var prompt by remember {
        mutableStateOf("Write a short story about a robot discovering nature for the first time.")
    }
    var maxTokens by remember { mutableStateOf(512f) }
    var temperature by remember { mutableStateOf(0.7f) }
    var tab by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Lian.Background)
            .imePadding(),
    ) {
        LianTopBar(title = "Playground", onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Text", "Sampling").forEachIndexed { index, label ->
                    BrandChip(label, tab == index, { tab = index })
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Raw prompt") },
                supportingText = {
                    Text("Sent verbatim — no chat template is applied here.")
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = lianFieldColors(),
            )

            if (tab == 1) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Max tokens: ${maxTokens.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lian.TextPrimary,
                    )
                    Slider(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        valueRange = 32f..2048f,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Temperature: %.2f".format(temperature),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lian.TextPrimary,
                    )
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                    )
                    Text(
                        "0 is deterministic. This screen bypasses the app's saved " +
                            "sampling settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Lian.TextMuted,
                    )
                }
            }

            BrandCard(Modifier.fillMaxWidth()) {
                Text("Model", style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
                Text(
                    loaded?.model?.displayName ?: "None loaded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
            }

            if (state.running) {
                GradientButton(
                    text = "Stop",
                    onClick = vm::stop,
                    leadingIcon = Icons.Default.Stop,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GradientButton(
                    text = "Run",
                    onClick = { vm.run(prompt, maxTokens.toInt(), temperature) },
                    enabled = loaded != null && prompt.isNotBlank(),
                    leadingIcon = Icons.Default.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Lian.Danger)
            }

            if (state.output.isNotEmpty() || state.running) {
                BrandCard(Modifier.fillMaxWidth(), highlighted = true) {
                    Text(
                        state.output.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = Lian.TextPrimary,
                        modifier = Modifier.heightIn(min = 80.dp),
                    )
                    state.stats?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.Cyan)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Shared back-arrow header used by the screens that sit outside the tab bar. */
@Composable
fun LianTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Lian.TextPrimary,
                )
            }
        } else {
            Spacer(Modifier.padding(start = 10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Lian.TextPrimary)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
            }
        }
        actions?.invoke()
    }
}

/** Field colours that sit correctly on the dark brand surface. */
@Composable
fun lianFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Lian.Surface,
    unfocusedContainerColor = Lian.Surface,
    focusedBorderColor = Lian.Purple,
    unfocusedBorderColor = Lian.Outline,
    focusedTextColor = Lian.TextPrimary,
    unfocusedTextColor = Lian.TextPrimary,
    cursorColor = Lian.Cyan,
    focusedLabelColor = Lian.Cyan,
    unfocusedLabelColor = Lian.TextMuted,
    focusedSupportingTextColor = Lian.TextMuted,
    unfocusedSupportingTextColor = Lian.TextMuted,
    focusedPlaceholderColor = Lian.TextMuted,
    unfocusedPlaceholderColor = Lian.TextMuted,
)
