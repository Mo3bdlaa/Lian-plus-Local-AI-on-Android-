package com.lian.plus.ui.screens

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lian.plus.core.LianRuntime
import com.lian.plus.data.db.GeneratedImageEntity
import com.lian.plus.image.ImageEvent
import com.lian.plus.image.Sampler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ImageUiState(
    val generating: Boolean = false,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val lastFile: File? = null,
    val message: String? = null,
    val elapsedSeconds: Long = 0,
)

class ImageViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = LianRuntime.get(app)

    private val _ui = MutableStateFlow(ImageUiState())
    val ui: StateFlow<ImageUiState> = _ui.asStateFlow()

    val clientState = runtime.imageClient.state
    val capability = runtime.capability

    val gallery: StateFlow<List<GeneratedImageEntity>> = runtime.database.images().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    fun generate(
        prompt: String,
        negative: String,
        steps: Int,
        cfg: Float,
        size: Int,
        sampler: Sampler,
    ) {
        if (prompt.isBlank() || _ui.value.generating) return
        job = viewModelScope.launch {
            if (clientState.value.loadedModelId == null) {
                _ui.value = _ui.value.copy(
                    message = "Load an image model on the Models screen first.",
                )
                return@launch
            }

            val target = File(runtime.imagesDir, "img_${System.currentTimeMillis()}.png")
            _ui.value = ImageUiState(generating = true, totalSteps = steps)

            val request = runtime.defaultImageRequest().copy(
                prompt = prompt,
                negativePrompt = negative,
                steps = steps,
                cfgScale = cfg,
                width = size,
                height = size,
                sampler = sampler,
                seed = -1,
            )

            runtime.imageClient.generate(request, target).collect { event ->
                when (event) {
                    is ImageEvent.Step -> _ui.value = _ui.value.copy(
                        step = event.step,
                        totalSteps = event.totalSteps,
                    )
                    is ImageEvent.Done -> {
                        runtime.database.images().insert(
                            GeneratedImageEntity(
                                filePath = event.file.absolutePath,
                                prompt = prompt,
                                negativePrompt = negative.ifBlank { null },
                                width = event.width,
                                height = event.height,
                                steps = steps,
                                cfgScale = cfg,
                                seed = request.seed,
                                sampler = sampler.label,
                                modelId = clientState.value.loadedModelId,
                                durationMillis = event.elapsedMillis,
                            ),
                        )
                        _ui.value = ImageUiState(
                            lastFile = event.file,
                            elapsedSeconds = event.elapsedMillis / 1000,
                            message = "Done in ${event.elapsedMillis / 1000}s",
                        )
                    }
                    is ImageEvent.Failed -> _ui.value =
                        ImageUiState(message = event.message)
                }
            }
        }
    }

    fun cancel() {
        runtime.imageClient.cancel()
        job?.cancel()
        _ui.value = _ui.value.copy(generating = false, message = "Cancelled.")
    }

    /** Unloads the model and lets the worker process exit. */
    fun freeMemory() {
        runtime.imageClient.releaseProcess()
        _ui.value = _ui.value.copy(message = "Image engine unloaded; memory released.")
    }
}

@Composable
fun ImageScreen(vm: ImageViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val client by vm.clientState.collectAsState()
    val report by vm.capability.collectAsState()
    val gallery by vm.gallery.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("blurry, low quality, watermark") }
    var steps by remember { mutableStateOf(4f) }
    var cfg by remember { mutableStateOf(1.5f) }
    var size by remember { mutableStateOf(512) }
    var sampler by remember { mutableStateOf(Sampler.EULER_A) }

    val maxSize = report?.recommendedImageSize ?: 512

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Image generation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            client.loadedModelId?.let { "Loaded: $it ${client.modelVersion.orEmpty()}" }
                ?: "No image model loaded — pick one on the Models screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (report?.canRunImageGen == false) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "This device has too little memory for image generation. It needs " +
                        "about 6 GB of RAM; text models still work fine.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = negative,
            onValueChange = { negative = it },
            label = { Text("Negative prompt") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Text("Steps: ${steps.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = steps, onValueChange = { steps = it }, valueRange = 1f..30f, steps = 28)
        Text(
            "Turbo checkpoints need 1-4 steps. A standard SD 1.5 model needs 20-30, " +
                "which takes a few minutes on a phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Guidance: %.1f".format(cfg), style = MaterialTheme.typography.bodyMedium)
        Slider(value = cfg, onValueChange = { cfg = it }, valueRange = 1f..12f)

        Spacer(Modifier.height(8.dp))
        Text("Size", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256, 512, 768, 1024).forEach { s ->
                FilterChip(
                    selected = size == s,
                    onClick = { size = s },
                    enabled = s <= maxSize,
                    label = { Text("${s}px") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Sampler", style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().horizontalScrollCompat(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(Sampler.EULER_A, Sampler.EULER, Sampler.DPMPP2M, Sampler.LCM).forEach { s ->
                FilterChip(
                    selected = sampler == s,
                    onClick = { sampler = s },
                    label = { Text(s.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        if (ui.generating) {
            Text(
                "Step ${ui.step} of ${ui.totalSteps}",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { if (ui.totalSteps > 0) ui.step.toFloat() / ui.totalSteps else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = { vm.generate(prompt, negative, steps.toInt(), cfg, size, sampler) },
                enabled = prompt.isNotBlank() && client.loadedModelId != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate") }
        }

        ui.message?.let {
            Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        }

        ui.lastFile?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = "Generated image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .padding(top = 12.dp),
            )
        }

        if (client.loadedModelId != null) {
            OutlinedButton(
                onClick = vm::freeMemory,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Unload image model and free memory") }
        }

        if (gallery.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Recent", style = MaterialTheme.typography.titleMedium)
            gallery.take(8).forEach { entry ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        AsyncImage(
                            model = File(entry.filePath),
                            contentDescription = entry.prompt,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Text(
                            entry.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "${entry.width}x${entry.height} · ${entry.steps} steps · " +
                                "${entry.durationMillis / 1000}s · ${entry.sampler}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Horizontal scroll for the sampler chip row. */
@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    horizontalScroll(rememberScrollState())
