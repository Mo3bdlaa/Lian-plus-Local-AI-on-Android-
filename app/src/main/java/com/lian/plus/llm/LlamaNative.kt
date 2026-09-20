package com.lian.plus.llm

import android.util.Log

/**
 * Thin, 1:1 binding to `liblian_llm.so`.
 *
 * Nothing here interprets results; [LlmEngine] owns all policy. Every handle is
 * an opaque native pointer — passing a stale one is undefined behaviour, so the
 * handles never escape the engine.
 */
object LlamaNative {

    /** False when the APK was built without the engine (see `lian.buildNative`). */
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lian_llm")
        backendInit()
        true
    }.getOrElse { t ->
        Log.w("LianLlm", "llama engine unavailable: ${t.message}")
        false
    }

    /** Invoked on the loading thread while weights are read from disk. */
    fun interface LoadProgress {
        /** @return false to abort the load. */
        fun onProgress(fraction: Float): Boolean
    }

    /** Invoked on the generating thread for every decoded chunk of text. */
    interface TokenCallback {
        /** @return false to stop generation. */
        fun onToken(piece: String, token: Int): Boolean

        /** Prompt ingestion progress, so long prompts can show a bar. */
        fun onPrefill(done: Int, total: Int) {}
    }

    private external fun backendInit()

    external fun systemInfo(): String

    /** One device per line: `name|description|type|freeBytes|totalBytes`. */
    external fun backendDevices(): String

    /** Whether the Vulkan backend was compiled into this build at all. */
    external fun hasVulkanSupport(): Boolean

    /**
     * Times an F16xF32 matmul on one device from [backendDevices] and returns
     * GFLOP/s, or -1 when the device could not run it.
     *
     * Blocking, and deliberately not cheap: call it off the main thread.
     */
    external fun benchmarkMatmul(
        deviceIndex: Int,
        dim: Int,
        threads: Int,
        budgetMs: Int,
    ): Double

    external fun loadModel(
        path: String,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        progress: LoadProgress?,
    ): Long

    external fun freeModel(handle: Long)

    external fun modelDesc(handle: Long): String
    external fun modelParamCount(handle: Long): Long
    external fun modelSizeBytes(handle: Long): Long
    external fun modelNCtxTrain(handle: Long): Int
    external fun modelNEmbd(handle: Long): Int
    external fun modelMeta(handle: Long, key: String): String?
    external fun modelChatTemplate(handle: Long): String?

    external fun createContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        /** -1 auto, 0 off, 1 on. */
        flashAttn: Int,
        embeddings: Boolean,
        /** -1 unspecified, else llama_pooling_type. */
        pooling: Int,
        /** 0 = f16, 1 = q8_0, 2 = q4_0. */
        typeK: Int,
        typeV: Int,
    ): Long

    external fun freeContext(handle: Long)
    external fun contextSize(handle: Long): Int
    external fun cachedTokenCount(handle: Long): Int
    external fun resetContext(handle: Long)

    /** Safe to call from any thread while [generate] is running. */
    external fun requestCancel(handle: Long)

    external fun tokenize(
        handle: Long,
        text: String,
        addSpecial: Boolean,
        parseSpecial: Boolean,
    ): IntArray

    external fun detokenize(handle: Long, tokens: IntArray, special: Boolean): String

    /** Returns null when the model carries no template and none was supplied. */
    external fun applyChatTemplate(
        modelHandle: Long,
        template: String?,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** @return the number of tokens generated, or a negative value on failure. */
    external fun generate(
        handle: Long,
        promptTokens: IntArray,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        freqPenalty: Float,
        presencePenalty: Float,
        seed: Int,
        grammar: String?,
        stopTokens: IntArray,
        callback: TokenCallback,
    ): Int

    /** L2-normalised embedding for [tokens]; empty when the context is not an embedding context. */
    external fun embed(handle: Long, tokens: IntArray): FloatArray
}
