package com.lian.plus.llm

/** Decoding settings for one generation request. */
data class SamplingParams(
    val maxTokens: Int = 1024,
    /** 0 or below switches to greedy decoding. */
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,
    /** Negative means a fresh random seed each run. */
    val seed: Int = -1,
    /** GBNF grammar constraining the output, or null. */
    val grammar: String? = null,
    /** Text sequences that end generation. Matched on the decoded stream. */
    val stopSequences: List<String> = emptyList(),
) {
    companion object {
        /** Balanced default for chat. */
        val Chat = SamplingParams()

        /** Deterministic, for tool arguments and structured output. */
        val Precise = SamplingParams(
            temperature = 0.1f,
            topP = 0.9f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
        )

        /** Looser, for open-ended writing. */
        val Creative = SamplingParams(
            temperature = 1.0f,
            topK = 80,
            topP = 0.98f,
            minP = 0.02f,
        )
    }
}

/** How a generation run ended. */
enum class FinishReason { STOP, LENGTH, CANCELLED, ERROR, TOOL_CALL }

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMillis: Long,
    val decodeMillis: Long,
    val cachedPromptTokens: Int,
) {
    val tokensPerSecond: Double
        get() = if (decodeMillis > 0) generatedTokens * 1000.0 / decodeMillis else 0.0

    val prefillTokensPerSecond: Double
        get() {
            val fresh = promptTokens - cachedPromptTokens
            return if (prefillMillis > 0 && fresh > 0) fresh * 1000.0 / prefillMillis else 0.0
        }
}

/** Incremental output from [LlmEngine.generate]. */
sealed interface LlmEvent {
    /** Prompt ingestion progress, emitted before any token. */
    data class Prefill(val done: Int, val total: Int) : LlmEvent

    /** A chunk of newly decoded text. */
    data class Token(val text: String) : LlmEvent

    data class Finished(val reason: FinishReason, val stats: GenerationStats) : LlmEvent

    data class Error(val message: String) : LlmEvent
}
