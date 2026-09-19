package com.lian.plus.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.Quant
import com.lian.plus.hub.CuratedCatalog
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.DownloadEvent
import com.lian.plus.hub.HfFile
import com.lian.plus.hub.HfModelSummary
import com.lian.plus.hub.HfRepoDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DownloadState(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

data class ModelsUiState(
    val searching: Boolean = false,
    val searchResults: List<HfModelSummary> = emptyList(),
    val openRepo: HfRepoDetail? = null,
    val loadingRepo: Boolean = false,
    val download: DownloadState? = null,
    val message: String? = null,
    val diskUsage: Long = 0,
)

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = LianRuntime.get(app)

    val installed: StateFlow<List<InstalledModel>> = runtime.modelStore.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capability = runtime.capability
    val loadState = runtime.modelLoadState

    private val _ui = MutableStateFlow(ModelsUiState())
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { refreshDiskUsage() }
    }

    fun curatedFor(): List<CuratedModel> {
        val tier = capability.value?.tier ?: return CuratedCatalog.text
        return CuratedCatalog.forTier(tier)
    }

    fun search(query: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true, message = null)
            val result = runCatching { runtime.huggingFace.searchModels(query) }
            _ui.value = _ui.value.copy(
                searching = false,
                searchResults = result.getOrDefault(emptyList()),
                message = result.exceptionOrNull()?.let { "Search failed: ${it.message}" },
            )
        }
    }

    fun openRepo(repoId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingRepo = true, openRepo = null, message = null)
            val result = runCatching { runtime.huggingFace.repoFiles(repoId) }
            _ui.value = _ui.value.copy(
                loadingRepo = false,
                openRepo = result.getOrNull(),
                message = result.exceptionOrNull()?.let { "Could not open $repoId: ${it.message}" },
            )
        }
    }

    fun closeRepo() {
        _ui.value = _ui.value.copy(openRepo = null)
    }

    /**
     * Picks the best file in [detail] that fits the device budget: the highest
     * quality quantisation whose size still leaves headroom.
     */
    fun bestFileFor(detail: HfRepoDetail): HfFile? {
        val budget = capability.value?.maxModelFileBytes ?: Long.MAX_VALUE
        return detail.ggufFiles
            .filter { it.sizeBytes in 1..budget }
            .maxByOrNull { it.quant.quality * 1_000_000L + it.sizeBytes / 1024 }
            ?: detail.ggufFiles.minByOrNull { it.sizeBytes }
    }

    fun downloadCurated(model: CuratedModel) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingRepo = true)
            val detail = runCatching { runtime.huggingFace.repoFiles(model.repoId) }.getOrNull()
            _ui.value = _ui.value.copy(loadingRepo = false)
            if (detail == null) {
                _ui.value = _ui.value.copy(message = "Could not reach ${model.repoId}")
                return@launch
            }
            val file = detail.ggufFiles
                .firstOrNull { it.fileName.contains(model.preferredFileHint, ignoreCase = true) }
                ?: bestFileFor(detail)
            if (file == null) {
                _ui.value = _ui.value.copy(message = "No usable GGUF file in ${model.repoId}")
                return@launch
            }
            download(file, model.kind)
        }
    }

    fun download(file: HfFile, kind: ModelKind) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val target = File(runtime.modelStore.dirFor(kind), file.fileName)

            val free = capability.value?.profile?.freeStorageBytes ?: Long.MAX_VALUE
            if (file.sizeBytes > free - 300L * 1024 * 1024) {
                _ui.value = _ui.value.copy(
                    message = "Not enough free storage for ${file.fileName}.",
                )
                return@launch
            }

            _ui.value = _ui.value.copy(
                download = DownloadState(file.fileName, 0, file.sizeBytes, 0),
                message = null,
            )

            runtime.downloader.download(file.downloadUrl, target).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> _ui.value = _ui.value.copy(
                        download = DownloadState(
                            file.fileName, event.bytesDone,
                            if (event.bytesTotal > 0) event.bytesTotal else file.sizeBytes,
                            event.bytesPerSecond,
                        ),
                    )
                    is DownloadEvent.Done -> {
                        val model = runtime.modelStore.register(event.file, kind, file.repoId)
                        _ui.value = _ui.value.copy(
                            download = null,
                            message = "${model.displayName} is ready.",
                        )
                        refreshDiskUsage()
                    }
                    is DownloadEvent.Failed -> _ui.value = _ui.value.copy(
                        download = null,
                        message = "Download failed: ${event.message}" +
                            if (event.retryable) " — tap again to resume." else "",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _ui.value = _ui.value.copy(
            download = null,
            message = "Download paused. Tap the same file to resume it.",
        )
    }

    fun activate(model: InstalledModel) {
        viewModelScope.launch {
            when (model.kind) {
                ModelKind.TEXT -> runtime.loadTextModel(model)
                ModelKind.EMBEDDING -> runtime.loadEmbeddingModel(model).onFailure {
                    _ui.value = _ui.value.copy(message = it.message)
                }
                ModelKind.IMAGE -> {
                    val threads = capability.value?.recommendedThreads ?: 4
                    runtime.imageClient.load(model, threads = threads)
                        .onSuccess {
                            runtime.settingsStore.update { s -> s.copy(activeImageModelId = model.id) }
                            _ui.value = _ui.value.copy(message = "${model.displayName} loaded.")
                        }
                        .onFailure { _ui.value = _ui.value.copy(message = it.message) }
                }
                ModelKind.IMAGE_COMPONENT -> Unit
            }
        }
    }

    fun delete(model: InstalledModel) {
        viewModelScope.launch {
            runtime.modelStore.remove(model.id)
            refreshDiskUsage()
            _ui.value = _ui.value.copy(message = "${model.displayName} deleted.")
        }
    }

    fun dismissMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    /** Classifies a repo file so the right directory and engine are used. */
    fun kindFor(file: HfFile, summary: HfModelSummary?): ModelKind {
        val tags = summary?.tags.orEmpty().map { it.lowercase() }
        val name = file.fileName.lowercase()
        return when {
            tags.any { it.contains("text-to-image") || it.contains("stable-diffusion") } ||
                name.contains("stable-diffusion") || name.contains("sd_turbo") ||
                name.contains("sdxl") || name.contains("flux") -> ModelKind.IMAGE
            tags.any { it.contains("sentence-similarity") || it.contains("feature-extraction") } ||
                name.contains("embed") || name.contains("bge") || name.contains("minilm") ->
                ModelKind.EMBEDDING
            else -> ModelKind.TEXT
        }
    }

    private suspend fun refreshDiskUsage() {
        _ui.value = _ui.value.copy(diskUsage = runtime.modelStore.diskUsage())
    }

    /** True when this quantisation is a poor fit for the device. */
    fun warnsAbout(file: HfFile): String? {
        val report = capability.value ?: return null
        return when {
            file.sizeBytes > report.hardLimitModelFileBytes ->
                "Too large for this device's memory"
            file.sizeBytes > report.maxModelFileBytes ->
                "Will fit, but leaves little headroom"
            file.quant.quality <= 3 ->
                "Very aggressive quantisation — expect noticeably worse answers"
            else -> null
        }
    }

    fun quantLabel(quant: Quant): String = quant.tag
}
