package com.lian.plus.llm

import android.util.Log
import com.lian.plus.core.model.GgufInspector
import com.lian.plus.core.model.InstalledModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Which model is loaded, and what it can do. */
data class LoadedModelInfo(
    val model: InstalledModel,
    val description: String,
    val contextSize: Int,
    val contextTrained: Int,
    val embeddingDim: Int,
    val chatFormat: ChatFormat,
    val hasNativeTemplate: Boolean,
    val loadMillis: Long,
)

data class EngineConfig(
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    val threads: Int = 4,
    /** -1 auto, 0 off, 1 on. */
    val flashAttention: Int = -1,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    /** 0 = f16, 1 = q8_0, 2 = q4_0. Quantised KV halves context memory. */
    val kvCacheType: Int = 0,
    val chatFormat: ChatFormat = ChatFormat.AUTO,
)

/**
 * Owns the loaded llama.cpp model and the single context used for chat.
 *
 * All native calls are confined to one dedicated thread. llama.cpp contexts are
 * not thread-safe, and pinning them to a single thread is both simpler and
 * cheaper than locking around every entry point. A [Mutex] still guards the
 * *logical* one-generation-at-a-time rule, since the HTTP server and the UI can
 * both ask at once.
 */
class LlmEngine {

    private val nativeThread: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lian-llm") }.asCoroutineDispatcher()

    private val generationLock = Mutex()

    private var modelHandle: Long = 0
    private var contextHandle: Long = 0

    private val _loaded = MutableStateFlow<LoadedModelInfo?>(null)
    val loaded: StateFlow<LoadedModelInfo?> = _loaded.asStateFlow()

    val isLoaded: Boolean get() = contextHandle != 0L

    /** Reported by llama.cpp: which SIMD paths this build actually took. */
    suspend fun systemInfo(): String = withContext(nativeThread) {
        if (LlamaNative.isAvailable) LlamaNative.systemInfo() else "engine not built"
    }

    /**
     * Loads [model] and creates its context. Any previously loaded model is
     * released first — a phone has room for exactly one.
     */
    suspend fun load(
        model: InstalledModel,
        config: EngineConfig,
        onProgress: (Float) -> Unit = {},
    ): Result<LoadedModelInfo> = withContext(nativeThread) {
        if (!LlamaNative.isAvailable) {
            return@withContext Result.failure(IllegalStateException(ENGINE_MISSING))
        }
        val file = File(model.filePath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("file is missing: ${file.name}"))
        }

        unloadLocked()
        val started = System.currentTimeMillis()

        val mh = LlamaNative.loadModel(
            path = file.absolutePath,
            nGpuLayers = 0, // CPU-only build; see docs/ARCHITECTURE.md
            useMmap = config.useMmap,
            useMlock = config.useMlock,
            progress = { fraction -> onProgress(fraction); true },
        )
        if (mh == 0L) {
            return@withContext Result.failure(IllegalStateException("llama.cpp could not load this file"))
        }
        modelHandle = mh

        val trained = LlamaNative.modelNCtxTrain(mh).takeIf { it > 0 } ?: config.contextSize
        val requested = config.contextSize.coerceAtLeast(512).coerceAtMost(trained)

        val ch = LlamaNative.createContext(
            modelHandle = mh,
            nCtx = requested,
            nBatch = config.batchSize,
            nUbatch = config.batchSize.coerceAtMost(512),
            nThreads = config.threads,
            flashAttn = config.flashAttention,
            embeddings = false,
            pooling = -1,
            typeK = config.kvCacheType,
            typeV = config.kvCacheType,
        )
        if (ch == 0L) {
            LlamaNative.freeModel(mh)
            modelHandle = 0
            return@withContext Result.failure(
                IllegalStateException(
                    "Not enough memory for a $requested-token context. Try a smaller " +
                        "context or a quantised KV cache.",
                ),
            )
        }
        contextHandle = ch

        val nativeTemplate = LlamaNative.modelChatTemplate(mh)
        val format = when {
            config.chatFormat != ChatFormat.AUTO -> config.chatFormat
            nativeTemplate != null -> ChatFormat.AUTO
            else -> ChatFormat.forArchitecture(model.architecture)
        }

        val info = LoadedModelInfo(
            model = model,
            description = LlamaNative.modelDesc(mh),
            contextSize = LlamaNative.contextSize(ch),
            contextTrained = trained,
            embeddingDim = LlamaNative.modelNEmbd(mh),
            chatFormat = format,
            hasNativeTemplate = nativeTemplate != null,
            loadMillis = System.currentTimeMillis() - started,
        )
        _loaded.value = info
        Log.i(TAG, "loaded ${model.displayName} in ${info.loadMillis} ms, ctx=${info.contextSize}")
        Result.success(info)
    }

    suspend fun unload() = withContext(nativeThread) { unloadLocked() }

    private fun unloadLocked() {
        if (contextHandle != 0L) {
            LlamaNative.freeContext(contextHandle)
            contextHandle = 0
        }
        if (modelHandle != 0L) {
            LlamaNative.freeModel(modelHandle)
            modelHandle = 0
        }
        _loaded.value = null
    }

    /** Token count for [text] using the loaded model's own tokenizer. */
    suspend fun countTokens(text: String): Int = withContext(nativeThread) {
        if (contextHandle == 0L) return@withContext estimateTokens(text)
        LlamaNative.tokenize(contextHandle, text, false, true).size
    }

    /**
     * Rough token estimate for when no model is loaded. English averages close
     * to four characters per token; this errs high so budgeting stays safe.
     */
    fun estimateTokens(text: String): Int = (text.length / 3.6).toInt() + 1

    /** Renders [turns] into the exact prompt string the model expects. */
    suspend fun renderPrompt(
        turns: List<ChatTurn>,
        format: ChatFormat,
        addGenerationPrompt: Boolean = true,
    ): String = withContext(nativeThread) {
        if (format == ChatFormat.AUTO && modelHandle != 0L) {
            LlamaNative.applyChatTemplate(
                modelHandle = modelHandle,
                template = null,
                roles = turns.map { it.role }.toTypedArray(),
                contents = turns.map { it.content }.toTypedArray(),
                addAssistant = addGenerationPrompt,
            )?.let { return@withContext it }
        }
        val effective = if (format == ChatFormat.AUTO) ChatFormat.CHATML else format
        ChatFormatter.render(effective, turns, addGenerationPrompt)
    }

    /** Asks the running generation to stop at the next token boundary. */
    fun cancel() {
        if (contextHandle != 0L) LlamaNative.requestCancel(contextHandle)
    }

    /** Drops the KV cache, forcing the next prompt to be evaluated in full. */
    suspend fun resetCache() = withContext(nativeThread) {
        if (contextHandle != 0L) LlamaNative.resetContext(contextHandle)
    }

    /**
     * Streams a completion for [prompt].
     *
     * Stop sequences are matched on decoded text rather than tokens, because a
     * stop string rarely lines up with token boundaries. Text is held back
     * while it could still be the start of a stop sequence, so a partial match
     * never leaks into the output.
     */
    fun generate(prompt: String, params: SamplingParams): Flow<LlmEvent> = callbackFlow {
        if (!LlamaNative.isAvailable) {
            trySend(LlmEvent.Error(ENGINE_MISSING)); close(); return@callbackFlow
        }
        generationLock.withLock {
            withContext(nativeThread) {
                val ctx = contextHandle
                if (ctx == 0L) {
                    trySend(LlmEvent.Error("No model is loaded."))
                    return@withContext
                }

                val promptTokens = LlamaNative.tokenize(ctx, prompt, true, true)
                val cachedBefore = LlamaNative.cachedTokenCount(ctx)

                val prefillStart = System.currentTimeMillis()
                var decodeStart = 0L
                var finish = FinishReason.STOP
                var generated = 0
                var stopHit = false

                val sb = StringBuilder()
                val stops = params.stopSequences.filter { it.isNotEmpty() }
                val longestStop = stops.maxOfOrNull { it.length } ?: 0
                var emittedUpTo = 0

                val callback = object : LlamaNative.TokenCallback {
                    override fun onPrefill(done: Int, total: Int) {
                        trySendBlocking(LlmEvent.Prefill(done, total))
                    }

                    override fun onToken(piece: String, token: Int): Boolean {
                        if (decodeStart == 0L) decodeStart = System.currentTimeMillis()
                        sb.append(piece)
                        generated++

                        // Did a stop sequence complete anywhere in the new text?
                        val searchFrom = (sb.length - piece.length - longestStop).coerceAtLeast(0)
                        for (stop in stops) {
                            val at = sb.indexOf(stop, searchFrom)
                            if (at >= 0) {
                                if (at > emittedUpTo) {
                                    trySendBlocking(LlmEvent.Token(sb.substring(emittedUpTo, at)))
                                    emittedUpTo = at
                                }
                                finish = FinishReason.STOP
                                stopHit = true
                                return false
                            }
                        }

                        // Emit everything that can no longer become a stop
                        // sequence, holding back the last (longestStop - 1) chars.
                        val safeEnd = (sb.length - (longestStop - 1)).coerceAtLeast(emittedUpTo)
                        if (safeEnd > emittedUpTo) {
                            trySendBlocking(LlmEvent.Token(sb.substring(emittedUpTo, safeEnd)))
                            emittedUpTo = safeEnd
                        }

                        if (generated >= params.maxTokens) {
                            finish = FinishReason.LENGTH
                            return false
                        }
                        return isActive
                    }
                }

                val rc = runCatching {
                    LlamaNative.generate(
                        handle = ctx,
                        promptTokens = promptTokens,
                        maxTokens = params.maxTokens,
                        temperature = params.temperature,
                        topK = params.topK,
                        topP = params.topP,
                        minP = params.minP,
                        repeatPenalty = params.repeatPenalty,
                        repeatLastN = params.repeatLastN,
                        freqPenalty = params.frequencyPenalty,
                        presencePenalty = params.presencePenalty,
                        seed = params.seed,
                        grammar = params.grammar,
                        stopTokens = IntArray(0),
                        callback = callback,
                    )
                }

                rc.onFailure {
                    trySend(LlmEvent.Error(it.message ?: "generation failed"))
                    return@withContext
                }

                // Flush the tail that was held back in case it began a stop
                // sequence. If a stop actually fired, that tail is the stop
                // sequence itself and must not reach the caller.
                if (!stopHit && emittedUpTo < sb.length) {
                    trySend(LlmEvent.Token(sb.substring(emittedUpTo)))
                }

                if (!isActive) finish = FinishReason.CANCELLED

                val now = System.currentTimeMillis()
                trySend(
                    LlmEvent.Finished(
                        reason = finish,
                        stats = GenerationStats(
                            promptTokens = promptTokens.size,
                            generatedTokens = generated,
                            prefillMillis = (decodeStart.takeIf { it > 0 } ?: now) - prefillStart,
                            decodeMillis = if (decodeStart > 0) now - decodeStart else 0,
                            cachedPromptTokens = minOf(cachedBefore, promptTokens.size),
                        ),
                    ),
                )
            }
        }
        close()
        // Reached only if the collector cancels while the block is suspended;
        // the qualified call is deliberate - plain cancel() would resolve to
        // ProducerScope.cancel().
        awaitClose { this@LlmEngine.cancel() }
    }.flowOn(Dispatchers.Default)

    companion object {
        private const val TAG = "LianEngine"
        const val ENGINE_MISSING =
            "The text engine is not present in this build. Rebuild with -Plian.buildNative=true."
    }

    /** Inspects a file without loading it, for the model detail screen. */
    suspend fun inspect(file: File): GgufInspector.Info? =
        withContext(Dispatchers.IO) { GgufInspector.inspect(file) }
}
