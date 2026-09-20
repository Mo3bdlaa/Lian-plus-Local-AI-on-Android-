package com.lian.plus.ui.screens

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.data.db.GeneratedImageEntity
import com.lian.plus.image.AspectRatio
import com.lian.plus.image.NativeResolution
import com.lian.plus.image.ImageEvent
import com.lian.plus.image.ImageStyle
import com.lian.plus.image.Sampler
import com.lian.plus.ui.components.BrandCard
import com.lian.plus.ui.components.BrandChip
import com.lian.plus.ui.components.CircleAction
import com.lian.plus.ui.components.GradientButton
import com.lian.plus.ui.components.GradientIconTile
import com.lian.plus.ui.components.ModelLoadProgress
import com.lian.plus.ui.components.ModelPickerSheet
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImageUiState(
    val generating: Boolean = false,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val lastFile: File? = null,
    val lastPrompt: String? = null,
    val message: String? = null,
    val elapsedSeconds: Long = 0,
)

class ImageViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = LianRuntime.get(app)

    private val _ui = MutableStateFlow(ImageUiState())
    val ui: StateFlow<ImageUiState> = _ui.asStateFlow()

    val clientState = runtime.imageClient.state
    val capability = runtime.capability

    /** Image models on the device, for the in-place picker. */
    val installedModels: StateFlow<List<InstalledModel>> =
        runtime.modelStore.observe(ModelKind.IMAGE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _loadingModelId = MutableStateFlow<String?>(null)
    val loadingModelId: StateFlow<String?> = _loadingModelId.asStateFlow()

    /** Loads [model] into the worker process without leaving this screen. */
    fun loadModel(model: InstalledModel) {
        viewModelScope.launch {
            _loadingModelId.value = model.id
            val threads = capability.value?.recommendedThreads ?: 4
            runtime.imageClient.load(model, threads = threads).fold(
                onSuccess = {
                    runtime.settingsStore.update { s -> s.copy(activeImageModelId = model.id) }
                    _ui.value = _ui.value.copy(message = "${model.displayName} loaded.")
                },
                onFailure = { _ui.value = _ui.value.copy(message = it.message) },
            )
            _loadingModelId.value = null
        }
    }

    fun dismissCrash() = runtime.imageClient.clearCrashMessage()

    val gallery: StateFlow<List<GeneratedImageEntity>> = runtime.database.images().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null
    private var lastRequest: (() -> Unit)? = null

    fun generate(
        prompt: String,
        negative: String,
        style: ImageStyle,
        ratio: AspectRatio,
        baseSize: Int,
        steps: Int,
        cfg: Float,
        sampler: Sampler,
    ) {
        if (prompt.isBlank() || _ui.value.generating) return
        lastRequest = { generate(prompt, negative, style, ratio, baseSize, steps, cfg, sampler) }

        job = viewModelScope.launch {
            if (clientState.value.loadedModelId == null) {
                _ui.value = _ui.value.copy(
                    message = "Load an image model on the Models tab first.",
                )
                return@launch
            }

            val (width, height) = ratio.dimensions(baseSize)
            val target = File(runtime.imagesDir, "img_${System.currentTimeMillis()}.png")
            _ui.value = ImageUiState(generating = true, totalSteps = steps)

            val request = runtime.defaultImageRequest().copy(
                prompt = style.apply(prompt),
                negativePrompt = style.applyNegative(negative),
                steps = steps,
                cfgScale = cfg,
                width = width,
                height = height,
                sampler = sampler,
                seed = -1,
            )

            runtime.imageClient.generate(request, target).collect { event ->
                when (event) {
                    is ImageEvent.Step -> _ui.value =
                        _ui.value.copy(step = event.step, totalSteps = event.totalSteps)

                    is ImageEvent.Done -> {
                        runtime.database.images().insert(
                            GeneratedImageEntity(
                                filePath = event.file.absolutePath,
                                prompt = request.prompt,
                                negativePrompt = request.negativePrompt.ifBlank { null },
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
                            lastPrompt = request.prompt,
                            elapsedSeconds = event.elapsedMillis / 1000,
                            message = "Done in ${event.elapsedMillis / 1000}s",
                        )
                    }

                    is ImageEvent.Failed -> _ui.value = ImageUiState(message = event.message)
                }
            }
        }
    }

    fun regenerate() = lastRequest?.invoke()

    fun cancel() {
        runtime.imageClient.cancel()
        job?.cancel()
        _ui.value = _ui.value.copy(generating = false, message = "Cancelled.")
    }

    fun show(entry: GeneratedImageEntity) {
        _ui.value = ImageUiState(
            lastFile = File(entry.filePath),
            lastPrompt = entry.prompt,
            elapsedSeconds = entry.durationMillis / 1000,
        )
    }

    /** Copies the picture into the device gallery so other apps can see it. */
    fun saveToGallery(context: Context, file: File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lian+")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                    ) ?: error("the gallery rejected the file")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("could not open the destination")
                    true
                }.getOrElse { false }
            }
            _ui.value = _ui.value.copy(
                message = if (ok) "Saved to Pictures/Lian+" else "Could not save to the gallery.",
            )
        }
    }

    fun share(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.files", file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share image"))
        }.onFailure {
            _ui.value = _ui.value.copy(message = "Could not share: ${it.message}")
        }
    }

    fun freeMemory() {
        runtime.imageClient.releaseProcess()
        _ui.value = _ui.value.copy(message = "Image engine unloaded; memory released.")
    }
}

@Composable
fun ImageScreen(onBrowseModels: () -> Unit, vm: ImageViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val client by vm.clientState.collectAsState()
    val report by vm.capability.collectAsState()
    val gallery by vm.gallery.collectAsState()
    val installedModels by vm.installedModels.collectAsState()
    val loadingModelId by vm.loadingModelId.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("blurry, low quality, watermark") }
    var style by remember { mutableStateOf(ImageStyle.NONE) }
    var ratio by remember { mutableStateOf(AspectRatio.SQUARE) }
    var steps by remember { mutableStateOf(4f) }
    var cfg by remember { mutableStateOf(1.5f) }
    var sampler by remember { mutableStateOf(Sampler.EULER_A) }
    var advanced by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    // The device sets a ceiling, but the checkpoint sets the useful size: asking
    // a 512-native model for 768 costs more than twice the time for a worse
    // picture.
    val deviceMax = report?.recommendedImageSize ?: 512
    val baseSize = NativeResolution.cap(client.modelVersion, deviceMax)
    val (outW, outH) = ratio.dimensions(baseSize)

    Column(
        Modifier
            .fillMaxSize()
            .background(Lian.Background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientIconTile(Icons.Default.AutoAwesome, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f).clickable { showModelPicker = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        installedModels.firstOrNull { it.id == client.loadedModelId }?.displayName
                            ?: "No image model",
                        style = MaterialTheme.typography.titleMedium,
                        color = Lian.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "Change model",
                        tint = Lian.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    client.loadedModelId?.let { client.modelVersion?.ifBlank { "Loaded" } ?: "Loaded" }
                        ?: "Tap to choose one",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (client.loadedModelId != null) Lian.Cyan else Lian.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A killed worker process is not the same as the user unloading, and
        // saying "no model loaded" would hide the difference.
        client.crashMessage?.let { crash ->
            BrandCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), highlighted = true) {
                Text(
                    "Image engine stopped",
                    style = MaterialTheme.typography.titleSmall,
                    color = Lian.Danger,
                )
                Spacer(Modifier.height(4.dp))
                Text(crash, style = MaterialTheme.typography.bodySmall, color = Lian.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.Cyan,
                    modifier = Modifier.clickable { vm.dismissCrash() },
                )
            }
        }

        if (report?.canRunImageGen == false) {
            BrandCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    "This device has too little memory for image generation — it needs " +
                        "about 6 GB of RAM. Text models still work normally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lian.TextMuted,
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = {
                Text(
                    "A beautiful mountain landscape at sunset, cinematic, ultra detailed",
                    color = Lian.TextMuted,
                )
            },
            minLines = 3,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = lianFieldColors(),
        )

        Spacer(Modifier.height(18.dp))
        Text("Style", style = MaterialTheme.typography.titleSmall, color = Lian.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ImageStyle.entries.forEach { entry ->
                StyleTile(
                    style = entry,
                    selected = style == entry,
                    onClick = { style = entry },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Aspect ratio", style = MaterialTheme.typography.titleSmall, color = Lian.TextPrimary)
            Text("$outW × $outH", style = MaterialTheme.typography.labelSmall, color = Lian.Cyan)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AspectRatio.entries.forEach { entry ->
                BrandChip(
                    label = entry.label,
                    selected = ratio == entry,
                    onClick = { ratio = entry },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        client.modelVersion?.takeIf { it.isNotBlank() }?.let { version ->
            Spacer(Modifier.height(6.dp))
            Text(
                "$version · trained at ${NativeResolution.forVersion(version)}px. " +
                    "Each step costs roughly the square of the side, so larger is " +
                    "slower as well as worse.",
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Advanced settings",
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextPrimary,
            )
            Icon(
                if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = Lian.TextMuted,
            )
        }

        AnimatedVisibility(visible = advanced) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    "Steps: ${steps.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
                Slider(value = steps, onValueChange = { steps = it }, valueRange = 1f..30f, steps = 28)
                Text(
                    "Turbo checkpoints need 1-4. A standard SD 1.5 model needs 20-30, " +
                        "which is a few minutes on a phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    "Guidance: %.1f".format(cfg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Lian.TextPrimary,
                )
                Slider(value = cfg, onValueChange = { cfg = it }, valueRange = 1f..12f)
                Text(
                    "Turbo models want roughly 1.0; the usual 7.5 washes them out.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Lian.TextMuted,
                )

                Spacer(Modifier.height(12.dp))
                Text("Sampler", style = MaterialTheme.typography.bodyMedium, color = Lian.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(Sampler.EULER_A, Sampler.EULER, Sampler.DPMPP2M, Sampler.LCM).forEach {
                        BrandChip(it.label, sampler == it, { sampler = it })
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("Negative prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = lianFieldColors(),
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        if (ui.generating) {
            var elapsed by remember(ui.generating) { mutableStateOf(0) }
            LaunchedEffect(ui.generating) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    elapsed += 1
                }
            }
            Text(
                if (ui.step == 0) {
                    "Preparing — ${elapsed}s"
                } else {
                    "Step ${ui.step} of ${ui.totalSteps} — ${elapsed}s"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Lian.TextPrimary,
            )
            Text(
                if (ui.step == 0) {
                    "The first step loads the weights it needs, so it takes the longest. " +
                        "At ${outW}px expect a minute or more per step on a phone."
                } else {
                    "%.0fs per step so far.".format(
                        if (ui.step > 0) elapsed.toFloat() / ui.step else 0f,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = Lian.TextMuted,
            )
            LinearProgressIndicator(
                progress = { if (ui.totalSteps > 0) ui.step.toFloat() / ui.totalSteps else 0f },
                color = Lian.Cyan,
                trackColor = Lian.SurfaceRaised,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            GradientButton("Cancel", vm::cancel, Modifier.fillMaxWidth())
        } else {
            GradientButton(
                text = "Generate",
                onClick = {
                    vm.generate(prompt, negative, style, ratio, baseSize, steps.toInt(), cfg, sampler)
                },
                enabled = prompt.isNotBlank() && client.loadedModelId != null,
                leadingIcon = Icons.Default.AutoAwesome,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ui.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Lian.TextMuted)
        }

        ui.lastFile?.let { file ->
            Spacer(Modifier.height(18.dp))
            AsyncImage(
                model = file,
                contentDescription = "Generated image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Lian.Outline, RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CircleAction(Icons.Default.Download, "Save") { vm.saveToGallery(context, file) }
                CircleAction(Icons.Default.Share, "Share") { vm.share(context, file) }
                CircleAction(Icons.Default.Refresh, "Regenerate", enabled = !ui.generating) {
                    vm.regenerate()
                }
            }
            ui.lastPrompt?.let { used ->
                Spacer(Modifier.height(14.dp))
                BrandCard(Modifier.fillMaxWidth()) {
                    Text("Prompt", style = MaterialTheme.typography.labelSmall, color = Lian.TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(used, style = MaterialTheme.typography.bodySmall, color = Lian.TextPrimary)
                }
            }
        }

        if (gallery.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("Recent", style = MaterialTheme.typography.titleSmall, color = Lian.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gallery.take(12).forEach { entry ->
                    AsyncImage(
                        model = File(entry.filePath),
                        contentDescription = entry.prompt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Lian.Outline, RoundedCornerShape(12.dp))
                            .clickable { vm.show(entry) },
                    )
                }
            }
        }

        if (client.loadedModelId != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Unload image model and free memory",
                style = MaterialTheme.typography.bodySmall,
                color = Lian.Cyan,
                modifier = Modifier.clickable { vm.freeMemory() },
            )
        }

        Spacer(Modifier.height(28.dp))
    }

    if (showModelPicker) {
        ImageModelSheet(
            installed = installedModels,
            activeId = client.loadedModelId,
            loadingId = loadingModelId,
            onPick = { vm.loadModel(it) },
            onBrowse = { showModelPicker = false; onBrowseModels() },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun ImageModelSheet(
    installed: List<InstalledModel>,
    activeId: String?,
    loadingId: String?,
    onPick: (InstalledModel) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModelPickerSheet(
        kind = ModelKind.IMAGE,
        installed = installed,
        activeId = activeId,
        loading = loadingId?.let { ModelLoadProgress(it, 0f) },
        onPick = onPick,
        onBrowse = onBrowse,
        onDismiss = onDismiss,
    )
}

/** A square style swatch; the gradient stands in for a preview thumbnail. */
@Composable
private fun StyleTile(style: ImageStyle, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(shape)
                .background(styleWash(style))
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) Lian.Cyan else Lian.Outline,
                    shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            style.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Lian.TextPrimary else Lian.TextMuted,
        )
    }
}

private fun styleWash(style: ImageStyle) = when (style) {
    ImageStyle.NONE -> androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Lian.SurfaceRaised, Lian.Surface),
    )
    ImageStyle.ANIME -> androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Color(0xFFFF8DC7), Color(0xFF9D7BFF)),
    )
    ImageStyle.CINEMATIC -> androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Color(0xFF2B3A67), Color(0xFFE8833A)),
    )
    ImageStyle.PHOTO -> androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(Color(0xFF3C5A6B), Color(0xFFA9C4CF)),
    )
}
