package com.lian.plus.core

import android.util.Log
import com.lian.plus.core.device.MemoryBudget
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.core.model.ModelKind
import com.lian.plus.core.model.formatBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which models are in memory, and says so out loud.
 *
 * The rules, in order:
 *
 *  1. Load nothing until something is actually asked for. Opening a chat and
 *     typing a prompt for a picture should not pay for a language model.
 *  2. If both fit, keep both. Evicting a model that was not in anyone's way
 *     only buys a reload later.
 *  3. Evict only when the measured budget says the new model will not fit
 *     otherwise — and then say which model is being released and why.
 *
 * Every decision is taken against [MemoryBudget], which reads the device at
 * that moment. Nothing here is a fraction of total RAM.
 */
class ModelResidency(
    private val runtime: LianRuntime,
    private val budget: MemoryBudget,
) {

    sealed interface State {
        data object Idle : State

        data class Loading(
            val model: InstalledModel,
            val fraction: Float,
            /** Set when room had to be made first. */
            val evicted: InstalledModel? = null,
        ) : State

        data class Evicting(val releasing: InstalledModel, val toLoad: InstalledModel) : State

        data class Failed(val model: InstalledModel, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Serialises load/evict so two screens cannot race each other. */
    private val lock = Mutex()

    /** Bytes currently held by loaded models, from their own file sizes. */
    fun residentBytes(): Long {
        var total = 0L
        runtime.llm.loaded.value?.let { total += it.model.sizeBytes }
        // Plus whatever companion files the image pipeline pulled in with it.
        residentImage?.let { total += it.sizeBytes + residentImageExtraBytes }
        residentEmbedding?.let { total += it.sizeBytes }
        return total
    }

    private var residentImage: InstalledModel? = null

    /** Extra bytes held by the companion files of the resident image model. */
    @Volatile private var residentImageExtraBytes: Long = 0
    private var residentEmbedding: InstalledModel? = null

    fun loadedText(): InstalledModel? = runtime.llm.loaded.value?.model
    fun loadedImage(): InstalledModel? = residentImage

    /** A plain sentence about what is in memory, for the UI to show. */
    fun residencyLine(): String {
        val parts = buildList {
            loadedText()?.let { add("${it.displayName} (${it.sizeLabel})") }
            residentImage?.let { add("${it.displayName} (${it.sizeLabel})") }
        }
        if (parts.isEmpty()) return "No model loaded"
        val snap = budget.snapshot(residentBytes())
        return parts.joinToString(" + ") + " · ${formatBytes(snap.availableBytes)} free"
    }

    /**
     * Makes [model] resident, loading it only if it is not already.
     *
     * Returns the model that had to be released, if any, so the caller can
     * explain the pause rather than letting it look like a stall.
     */
    suspend fun ensure(model: InstalledModel): Result<InstalledModel?> = lock.withLock {
        if (alreadyResident(model)) return@withLock Result.success(null)

        // The question is what has to be resident, which for a split image
        // pipeline is the transformer plus its text encoder and VAE. Judging a
        // Qwen-Image checkpoint on its own weights alone says a 4 GB file fits
        // when the set is nearer fourteen.
        val pipeline = if (model.kind == ModelKind.IMAGE) {
            runCatching { runtime.modelStore.pipelineFor(model) }.getOrNull()
        } else null
        val residentCost = pipeline?.totalBytes ?: model.sizeBytes

        val fit = budget.evaluate(residentCost, residentBytes())
        var evicted: InstalledModel? = null

        if (fit.needsEviction) {
            evicted = evictOtherThan(model.kind)
            if (evicted != null) {
                Log.i(TAG, "released ${evicted.displayName} to make room for ${model.displayName}")
            }
        } else if (!fit.canLoad) {
            // Nothing to release and still no room: try anyway only when the
            // shortfall is small, since mmap'd weights degrade to paging
            // rather than failing outright. A large shortfall is a refusal.
            val snap = fit.snapshot
            val needed = if (pipeline != null && pipeline.parts.isNotEmpty()) {
                "${formatBytes(residentCost)} for the whole ${pipeline.arch.label} " +
                    "pipeline (${pipeline.parts.size + 1} files)"
            } else {
                model.sizeLabel
            }
            val message = "${model.displayName} needs $needed but only " +
                "${formatBytes(snap.freeForNewModel)} is free. Close some apps, or " +
                "pick a smaller model."
            _state.value = State.Failed(model, message)
            return@withLock Result.failure(IllegalStateException(message))
        }

        _state.value = State.Loading(model, 0f, evicted)

        val result = when (model.kind) {
            ModelKind.TEXT -> runtime.loadTextModel(model) { fraction ->
                _state.value = State.Loading(model, fraction, evicted)
            }.map { }

            ModelKind.EMBEDDING -> runtime.loadEmbeddingModel(model)
                .onSuccess { residentEmbedding = model }
                .map { }

            ModelKind.IMAGE -> runtime.loadImageModel(model)
                .onSuccess {
                    residentImage = model
                    residentImageExtraBytes = (pipeline?.totalBytes ?: model.sizeBytes) -
                        model.sizeBytes
                }
                .map { }

            ModelKind.IMAGE_COMPONENT ->
                Result.failure(IllegalStateException("${model.displayName} is a companion file."))
        }

        return@withLock result.fold(
            onSuccess = {
                _state.value = State.Idle
                Result.success(evicted)
            },
            onFailure = {
                _state.value = State.Failed(model, it.message ?: "could not load")
                Result.failure(it)
            },
        )
    }

    /** Releases everything, for an explicit "free memory" action. */
    suspend fun releaseAll() = lock.withLock {
        runtime.llm.unload()
        runtime.imageClient.releaseProcess()
        residentImage = null
        residentImageExtraBytes = 0
        _state.value = State.Idle
    }

    suspend fun release(kind: ModelKind) = lock.withLock {
        releaseLocked(kind)
    }

    fun clearFailure() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private fun alreadyResident(model: InstalledModel): Boolean = when (model.kind) {
        ModelKind.TEXT -> runtime.llm.loaded.value?.model?.id == model.id
        ModelKind.IMAGE -> residentImage?.id == model.id
        ModelKind.EMBEDDING -> residentEmbedding?.id == model.id
        ModelKind.IMAGE_COMPONENT -> false
    }

    /**
     * Frees the largest model of a different kind.
     *
     * Different kind, because loading a text model should not evict the text
     * model it is replacing — that happens inside the engine anyway.
     */
    private suspend fun evictOtherThan(kind: ModelKind): InstalledModel? {
        val candidates = buildList {
            if (kind != ModelKind.TEXT) loadedText()?.let { add(ModelKind.TEXT to it) }
            if (kind != ModelKind.IMAGE) residentImage?.let { add(ModelKind.IMAGE to it) }
            if (kind != ModelKind.EMBEDDING) residentEmbedding?.let { add(ModelKind.EMBEDDING to it) }
        }
        val victim = candidates.maxByOrNull { it.second.sizeBytes } ?: return null
        releaseLocked(victim.first)
        return victim.second
    }

    private suspend fun releaseLocked(kind: ModelKind) {
        when (kind) {
            ModelKind.TEXT -> runtime.llm.unload()
            ModelKind.IMAGE -> {
                runtime.imageClient.releaseProcess()
                residentImage = null
                residentImageExtraBytes = 0
            }
            ModelKind.EMBEDDING -> {
                runtime.embedder.unload()
                residentEmbedding = null
            }
            ModelKind.IMAGE_COMPONENT -> Unit
        }
    }

    private companion object {
        const val TAG = "LianResidency"
    }
}
