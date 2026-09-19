package com.lian.plus.core

import android.content.Context
import android.util.Log
import com.lian.plus.core.device.CapabilityAnalyzer
import com.lian.plus.core.device.CapabilityReport
import com.lian.plus.core.device.DeviceProfiler
import com.lian.plus.core.device.ThermalLevel
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.ModelStore
import com.lian.plus.data.AppSettings
import com.lian.plus.data.SettingsStore
import com.lian.plus.data.db.AppDatabase
import com.lian.plus.hub.HuggingFaceApi
import com.lian.plus.hub.ModelDownloader
import com.lian.plus.image.ImageGenClient
import com.lian.plus.image.ImageRequest
import com.lian.plus.image.Sampler
import com.lian.plus.llm.ContextManager
import com.lian.plus.llm.EngineConfig
import com.lian.plus.llm.LlmEngine
import com.lian.plus.rag.EmbeddingEngine
import com.lian.plus.rag.RagPipeline
import com.lian.plus.tools.ToolRegistry
import com.lian.plus.tools.builtin.CalculatorTool
import com.lian.plus.tools.builtin.ClockTool
import com.lian.plus.tools.builtin.DeviceInfoTool
import com.lian.plus.tools.builtin.DocumentSearchTool
import com.lian.plus.tools.builtin.GenerateImageTool
import com.lian.plus.tools.builtin.MemoryTool
import com.lian.plus.tools.builtin.RecallTool
import com.lian.plus.tools.builtin.SearchBackend
import com.lian.plus.tools.builtin.WebFetchTool
import com.lian.plus.tools.builtin.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single place that owns the long-lived pieces: engines, stores and the tool
 * registry.
 *
 * Everything here is process-wide and survives Activity recreation, which
 * matters because reloading a model after a rotation would cost tens of
 * seconds.
 */
class LianRuntime private constructor(private val appContext: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val database: AppDatabase = AppDatabase.get(appContext)
    val modelStore = ModelStore(appContext)

    val llm = LlmEngine()
    val embedder = EmbeddingEngine()
    val rag = RagPipeline(appContext, embedder)
    val contextManager = ContextManager(llm)
    val tools = ToolRegistry()
    val imageClient = ImageGenClient(appContext)

    val orchestrator = ChatOrchestrator(llm, contextManager, tools, rag)

    val huggingFace = HuggingFaceApi(tokenProvider = { currentSettings.huggingFaceToken.ifBlank { null } })
    val downloader = ModelDownloader(tokenProvider = { currentSettings.huggingFaceToken.ifBlank { null } })

    val imagesDir: File by lazy { File(appContext.filesDir, "images").apply { mkdirs() } }

    @Volatile
    var currentSettings: AppSettings = AppSettings()
        private set

    private val _capability = MutableStateFlow<CapabilityReport?>(null)
    val capability: StateFlow<CapabilityReport?> = _capability.asStateFlow()

    private val _modelLoadState = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    sealed interface ModelLoadState {
        data object Idle : ModelLoadState
        data class Loading(val model: InstalledModel, val fraction: Float) : ModelLoadState
        data class Ready(val model: InstalledModel) : ModelLoadState
        data class Failed(val message: String) : ModelLoadState
    }

    init {
        registerTools()
        scope.launch {
            settingsStore.settings.collect { currentSettings = it }
        }
        scope.launch {
            runCatching { modelStore.sync() }
                .onFailure { Log.w(TAG, "model sync failed: ${it.message}") }
            refreshCapability()
        }
    }

    /** Re-reads the hardware profile, including the GPU name and thermal state. */
    suspend fun refreshCapability(): CapabilityReport {
        val profile = DeviceProfiler.withGpuInfo(DeviceProfiler.profile(appContext))
        val report = CapabilityAnalyzer.analyze(profile)
        _capability.value = report
        return report
    }

    fun thermalLevel(): ThermalLevel = DeviceProfiler.thermalLevel(appContext)

    /**
     * Loads [model] using the saved settings, clamped to what the device can
     * actually take. Thread count backs off while the phone is hot.
     */
    suspend fun loadTextModel(model: InstalledModel): Result<Unit> {
        val settings = settingsStore.settings.first()
        val report = _capability.value ?: refreshCapability()

        var threads = settings.threads.coerceIn(1, report.profile.cpuCores)
        if (thermalLevel().shouldThrottle) {
            threads = (threads / 2).coerceAtLeast(1)
            Log.i(TAG, "device is warm - dropping to $threads threads")
        }

        _modelLoadState.value = ModelLoadState.Loading(model, 0f)
        val config = EngineConfig(
            contextSize = settings.contextSize.coerceAtMost(report.maxContext),
            batchSize = settings.batchSize,
            threads = threads,
            flashAttention = settings.flashAttention,
            useMmap = true,
            useMlock = settings.useMlock,
            kvCacheType = settings.kvCacheType,
            chatFormat = settings.chatFormat,
        )

        val result = llm.load(model, config) { fraction ->
            _modelLoadState.value = ModelLoadState.Loading(model, fraction)
        }
        return result.fold(
            onSuccess = {
                _modelLoadState.value = ModelLoadState.Ready(model)
                modelStore.markUsed(model.id)
                settingsStore.update { it.copy(activeTextModelId = model.id) }
                Result.success(Unit)
            },
            onFailure = {
                _modelLoadState.value = ModelLoadState.Failed(it.message ?: "could not load the model")
                Result.failure(it)
            },
        )
    }

    suspend fun loadEmbeddingModel(model: InstalledModel): Result<Int> {
        val result = embedder.load(model, threads = 2)
        if (result.isSuccess) {
            settingsStore.update { it.copy(activeEmbeddingModelId = model.id) }
        }
        return result
    }

    /** Loads whatever was selected last time, if it is still installed. */
    suspend fun restoreSelection() {
        val settings = settingsStore.settings.first()
        settings.activeTextModelId?.let { id ->
            modelStore.byId(id)?.let { model ->
                if (!llm.isLoaded) loadTextModel(model)
            }
        }
        settings.activeEmbeddingModelId?.let { id ->
            modelStore.byId(id)?.let { model ->
                if (!embedder.isLoaded) loadEmbeddingModel(model)
            }
        }
    }

    fun defaultImageRequest(): ImageRequest {
        val s = currentSettings
        return ImageRequest(
            prompt = "",
            width = s.imageSize,
            height = s.imageSize,
            steps = s.imageSteps,
            cfgScale = s.imageCfg,
            sampler = Sampler.entries.firstOrNull { it.nativeValue == s.imageSampler }
                ?: Sampler.EULER_A,
        )
    }

    private fun registerTools() {
        tools.register(ClockTool())
        tools.register(CalculatorTool())
        tools.register(DeviceInfoTool(appContext))
        tools.register(MemoryTool(database.memories()))
        tools.register(RecallTool(database.memories()))
        tools.register(DocumentSearchTool(rag))
        tools.register(
            GenerateImageTool(imageClient, imagesDir) { defaultImageRequest() },
            enabledByDefault = false,
        )
        // Network tools stay off until the user turns them on: they are the
        // only ones that send anything off the device.
        tools.register(
            WebSearchTool(backendProvider = {
                val url = currentSettings.searxngUrl
                if (url.isNotBlank()) SearchBackend(SearchBackend.Kind.SEARXNG, url)
                else SearchBackend(SearchBackend.Kind.DUCKDUCKGO)
            }),
            enabledByDefault = false,
        )
        tools.register(WebFetchTool(), enabledByDefault = false)
    }

    /** Applies settings that affect which tools are offered to the model. */
    fun syncToolsWithSettings(settings: AppSettings) {
        tools.setEnabled("web_search", settings.webSearchEnabled)
        tools.setEnabled("fetch_url", settings.webSearchEnabled)
        tools.setEnabled("search_documents", settings.ragEnabled)
        tools.setEnabled("generate_image", imageClient.state.value.loadedModelId != null)
    }

    suspend fun installedTextModels(): List<InstalledModel> =
        modelStore.observe(ModelKind.TEXT).first()

    companion object {
        private const val TAG = "LianRuntime"

        @Volatile private var instance: LianRuntime? = null

        fun get(context: Context): LianRuntime = instance ?: synchronized(this) {
            instance ?: LianRuntime(context.applicationContext).also { instance = it }
        }
    }
}
