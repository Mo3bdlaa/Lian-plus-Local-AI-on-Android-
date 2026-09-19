package com.lian.plus.rag

import android.util.Log
import com.lian.plus.core.model.InstalledModel
import com.lian.plus.llm.LlamaNative
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * A second, much smaller llama.cpp context used only for embeddings.
 *
 * It is kept separate from [com.lian.plus.llm.LlmEngine] on purpose: switching
 * one context between generation and embedding modes means tearing down the KV
 * cache each time, which would throw away the prefix cache that makes chat
 * feel responsive. Embedding models are tens of megabytes, so the second copy
 * is cheap.
 */
class EmbeddingEngine {

    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lian-embed") }.asCoroutineDispatcher()

    private var modelHandle = 0L
    private var contextHandle = 0L
    private var dimensions = 0
    private var maxTokens = 512

    var loadedModel: InstalledModel? = null
        private set

    val isLoaded: Boolean get() = contextHandle != 0L
    val dimension: Int get() = dimensions

    suspend fun load(model: InstalledModel, threads: Int = 2): Result<Int> =
        withContext(dispatcher) {
            if (!LlamaNative.isAvailable) {
                return@withContext Result.failure(IllegalStateException("engine not built"))
            }
            if (!File(model.filePath).exists()) {
                return@withContext Result.failure(IllegalStateException("file is missing"))
            }
            unloadLocked()

            val mh = LlamaNative.loadModel(model.filePath, 0, true, false, null)
            if (mh == 0L) {
                return@withContext Result.failure(IllegalStateException("could not load embedding model"))
            }
            modelHandle = mh

            val trained = LlamaNative.modelNCtxTrain(mh).takeIf { it > 0 } ?: 512
            maxTokens = trained.coerceAtMost(2048)

            // MEAN pooling matches how sentence-transformer style encoders were
            // trained, and is what the common GGUF embedders expect.
            val ch = LlamaNative.createContext(
                modelHandle = mh,
                nCtx = maxTokens,
                nBatch = maxTokens,
                nUbatch = maxTokens,
                nThreads = threads,
                flashAttn = 0,
                embeddings = true,
                pooling = POOLING_MEAN,
                typeK = 0,
                typeV = 0,
            )
            if (ch == 0L) {
                LlamaNative.freeModel(mh); modelHandle = 0
                return@withContext Result.failure(IllegalStateException("could not create embedding context"))
            }
            contextHandle = ch
            dimensions = LlamaNative.modelNEmbd(mh)
            loadedModel = model
            Log.i(TAG, "embedding model ready: ${model.displayName}, dim=$dimensions")
            Result.success(dimensions)
        }

    suspend fun unload() = withContext(dispatcher) { unloadLocked() }

    private fun unloadLocked() {
        if (contextHandle != 0L) { LlamaNative.freeContext(contextHandle); contextHandle = 0 }
        if (modelHandle != 0L) { LlamaNative.freeModel(modelHandle); modelHandle = 0 }
        dimensions = 0
        loadedModel = null
    }

    /** Returns an L2-normalised vector, or an empty array if nothing is loaded. */
    suspend fun embed(text: String): FloatArray = withContext(dispatcher) {
        if (contextHandle == 0L || text.isBlank()) return@withContext FloatArray(0)
        var tokens = LlamaNative.tokenize(contextHandle, text, true, false)
        if (tokens.size > maxTokens) tokens = tokens.copyOf(maxTokens)
        LlamaNative.embed(contextHandle, tokens)
    }

    suspend fun embedAll(texts: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }):
        List<FloatArray> = withContext(dispatcher) {
        texts.mapIndexed { i, t ->
            onProgress(i + 1, texts.size)
            embed(t)
        }
    }

    private companion object {
        const val TAG = "LianEmbed"
        /** LLAMA_POOLING_TYPE_MEAN */
        const val POOLING_MEAN = 1
    }
}
