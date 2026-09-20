package com.lian.plus.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelFit
import com.lian.plus.core.model.ModelFitEvaluator
import com.lian.plus.core.model.GgufRole
import com.lian.plus.core.model.ModelKind
import com.lian.plus.hub.CuratedCatalog
import com.lian.plus.hub.CuratedModel
import com.lian.plus.hub.DownloadCenter
import com.lian.plus.hub.DownloadService
import com.lian.plus.hub.HfAsset
import com.lian.plus.hub.HfFile
import com.lian.plus.hub.HfModelSummary
import com.lian.plus.hub.HfRepoDetail
import com.lian.plus.hub.HubQuery
import com.lian.plus.hub.HuggingFaceApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(
    val query: HubQuery = HubQuery(),
    val browsing: Boolean = false,
    val loadingMore: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val nextCursor: String? = null,
    val openRepo: HfRepoDetail? = null,
    val loadingRepo: Boolean = false,
    val message: String? = null,
    val diskUsage: Long = 0,
    val freeStorage: Long = 0,
)

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = LianRuntime.get(app)
    private val appContext = app.applicationContext

    val installed: StateFlow<List<InstalledModel>> = runtime.modelStore.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capability = runtime.capability
    val benchmark = runtime.benchmark
    val loadState = runtime.modelLoadState

    /** Downloads live in the service, so they survive leaving this screen. */
    val downloads = DownloadCenter.jobs

    private val _ui = MutableStateFlow(ModelsUiState())
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()

    private var browseJob: Job? = null

    init {
        viewModelScope.launch { refreshStorage() }
        browse(HubQuery())
    }

    // ---- fit -------------------------------------------------------------

    fun fitFor(sizeBytes: Long, kind: ModelKind): ModelFit =
        ModelFitEvaluator.evaluate(sizeBytes, kind, capability.value, benchmark.value)

    fun fitFor(asset: HfAsset, summary: HfModelSummary?): ModelFit =
        fitFor(asset.totalBytes, kindFor(asset.primary, summary))

    /**
     * The curated list, unfiltered.
     *
     * It used to drop anything above the device's tier, which made the app look
     * like it only knew about four models. Everything is listed and labelled
     * with what will happen instead.
     */
    fun curated(): List<CuratedModel> = CuratedCatalog.text +
        CuratedCatalog.image + CuratedCatalog.embedding

    // ---- browsing --------------------------------------------------------

    fun setQuery(transform: (HubQuery) -> HubQuery) {
        val next = transform(_ui.value.query)
        _ui.value = _ui.value.copy(query = next)
        browse(next)
    }

    private fun browse(query: HubQuery) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(browsing = true, message = null)
            runCatching { runtime.huggingFace.browse(query) }.fold(
                onSuccess = { page ->
                    _ui.value = _ui.value.copy(
                        browsing = false,
                        results = page.models,
                        nextCursor = page.nextCursor,
                    )
                },
                onFailure = {
                    _ui.value = _ui.value.copy(
                        browsing = false,
                        message = "Could not reach Hugging Face: ${it.message}",
                    )
                },
            )
        }
    }

    /** Appends the next page; no-op when already loading or at the end. */
    fun loadMore() {
        val state = _ui.value
        val cursor = state.nextCursor ?: return
        if (state.loadingMore || state.browsing) return

        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingMore = true)
            runCatching { runtime.huggingFace.browse(state.query, cursor) }.fold(
                onSuccess = { page ->
                    _ui.value = _ui.value.copy(
                        loadingMore = false,
                        // De-duplicate: the cursor can overlap by an entry.
                        results = (_ui.value.results + page.models).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loadingMore = false) },
            )
        }
    }

    fun openRepo(repoId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingRepo = true, openRepo = null, message = null)
            runCatching { runtime.huggingFace.repoFiles(repoId) }.fold(
                onSuccess = { _ui.value = _ui.value.copy(loadingRepo = false, openRepo = it) },
                onFailure = {
                    _ui.value = _ui.value.copy(
                        loadingRepo = false,
                        message = "Could not open $repoId: ${it.message}",
                    )
                },
            )
        }
    }

    fun closeRepo() {
        _ui.value = _ui.value.copy(openRepo = null)
    }

    /** The highest-quality quantisation that still fits comfortably. */
    fun bestFileFor(detail: HfRepoDetail): HfAsset? {
        val budget = capability.value?.maxModelFileBytes ?: Long.MAX_VALUE
        val loadable = detail.assets.filter { it.role.isLoadable }
        return loadable
            .filter { it.totalBytes in 1..budget }
            .maxByOrNull { it.quant.quality * 1_000_000L + it.totalBytes / 1024 }
            ?: loadable.minByOrNull { it.totalBytes }
    }

    // ---- downloads -------------------------------------------------------

    fun download(asset: HfAsset, kind: ModelKind) {
        val fit = fitFor(asset.totalBytes, kind)
        if (!fit.isDownloadable) {
            _ui.value = _ui.value.copy(message = "${fit.headline}: ${fit.detail}")
            return
        }
        DownloadService.enqueue(appContext, asset, kind, runtime.modelStore.dirFor(kind))
        _ui.value = _ui.value.copy(
            message = if (asset.isSplit) {
                "Downloading ${asset.files.size} parts of ${asset.displayName}…"
            } else {
                "Downloading ${asset.displayName}…"
            },
        )
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
            val asset = detail.assets
                .filter { it.role.isLoadable }
                .firstOrNull { it.displayName.contains(model.preferredFileHint, ignoreCase = true) }
                ?: bestFileFor(detail)
            if (asset == null) {
                _ui.value = _ui.value.copy(message = "No loadable GGUF model in ${model.repoId}")
                return@launch
            }
            download(asset, model.kind)
        }
    }

    fun pauseDownload(id: String) = DownloadService.pause(appContext, id)
    fun resumeDownload(id: String) = DownloadService.start(appContext, id)
    fun cancelDownload(id: String) = DownloadService.cancel(appContext, id)

    // ---- installed -------------------------------------------------------

    fun activate(model: InstalledModel) {
        if (!model.role.isLoadable) {
            _ui.value = _ui.value.copy(
                message = "${model.displayName} is a ${model.role.label.lowercase()}. " +
                    model.role.explanation,
            )
            return
        }
        viewModelScope.launch {
            when (model.kind) {
                ModelKind.TEXT -> runtime.loadTextModel(model).onFailure {
                    _ui.value = _ui.value.copy(message = it.message)
                }
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
            // Unload first: deleting the file out from under a loaded model
            // leaves the engine holding a mapping to nothing.
            if (runtime.llm.loaded.value?.model?.id == model.id) runtime.llm.unload()
            if (runtime.imageClient.state.value.loadedModelId == model.id) {
                runtime.imageClient.releaseProcess()
            }
            runtime.modelStore.remove(model.id)
            refreshStorage()
            _ui.value = _ui.value.copy(
                message = "${model.displayName} deleted — ${model.sizeLabel} freed.",
            )
        }
    }

    fun dismissMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    /** Classifies a repo file so the right directory and engine are used. */
    fun kindFor(file: HfFile, summary: HfModelSummary?): ModelKind {
        val tags = summary?.tags.orEmpty().map { it.lowercase() }
        val pipeline = summary?.pipelineTag?.lowercase().orEmpty()
        val name = file.fileName.lowercase()
        return when {
            pipeline == "text-to-image" || pipeline == "image-to-image" ||
                tags.any { it.contains("text-to-image") || it.contains("stable-diffusion") } ||
                name.contains("stable-diffusion") || name.contains("sd_turbo") ||
                name.contains("sdxl") || name.contains("flux") -> ModelKind.IMAGE

            pipeline == "sentence-similarity" || pipeline == "feature-extraction" ||
                tags.any { it.contains("sentence-similarity") } ||
                name.contains("embed") || name.contains("bge") || name.contains("minilm") ->
                ModelKind.EMBEDDING

            else -> ModelKind.TEXT
        }
    }

    private suspend fun refreshStorage() {
        val report = capability.value ?: runtime.refreshCapability()
        _ui.value = _ui.value.copy(
            diskUsage = runtime.modelStore.diskUsage(),
            freeStorage = report.profile.freeStorageBytes,
        )
    }
}
