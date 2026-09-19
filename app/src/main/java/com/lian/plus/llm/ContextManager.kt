package com.lian.plus.llm

import android.util.Log
import kotlinx.coroutines.flow.toList

/** A message as the app stores it, before it becomes a [ChatTurn]. */
data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    /** Cached token count; -1 until measured. */
    val tokens: Int = -1,
    val isSummary: Boolean = false,
)

data class ContextPlan(
    val turns: List<ChatTurn>,
    val promptTokens: Int,
    val droppedMessages: Int,
    val summarisedMessages: Int,
    val budgetTokens: Int,
    val usedRetrieval: Boolean,
)

data class ContextPolicy(
    /** Fraction of the window reserved for the model's reply. */
    val reserveForOutputFraction: Double = 0.25,
    /** Hard floor for the reply reservation. */
    val minOutputTokens: Int = 256,
    /** Most recent turns that are never dropped or summarised. */
    val keepRecentTurns: Int = 4,
    /** Compress overflow into a running summary instead of discarding it. */
    val summariseOverflow: Boolean = true,
    /** Target length of that summary. */
    val summaryTokenBudget: Int = 300,
)

/**
 * Decides what actually goes into the prompt.
 *
 * A phone's context window is the scarce resource here, so the rules are
 * explicit rather than "send everything and hope":
 *
 *  1. The system prompt and any retrieved passages are pinned — they are the
 *     instructions, dropping them changes the model's behaviour.
 *  2. The most recent [ContextPolicy.keepRecentTurns] turns are pinned, because
 *     a reply that has lost the question it answers is worse than useless.
 *  3. Whatever is left over is walked newest-first until the budget runs out.
 *  4. What did not fit is folded into a running summary (one cheap extra
 *     generation) so older context degrades gradually instead of vanishing.
 */
class ContextManager(
    private val engine: LlmEngine,
    private val policy: ContextPolicy = ContextPolicy(),
) {

    /**
     * Builds the prompt turn list for [history].
     *
     * [retrieved] holds passages from the retrieval pipeline; they are inserted
     * as a system turn directly before the newest user message, which is where
     * models attend to them most reliably.
     */
    suspend fun plan(
        systemPrompt: String?,
        history: List<ChatMessage>,
        retrieved: List<String> = emptyList(),
        contextSize: Int,
        maxOutputTokens: Int,
    ): ContextPlan {
        val reserve = maxOf(
            policy.minOutputTokens,
            maxOutputTokens,
            (contextSize * policy.reserveForOutputFraction).toInt(),
        )
        // The chat template adds control tokens around every turn; leave room.
        val templateOverhead = 8 * (history.size + 4)
        val budget = (contextSize - reserve - templateOverhead).coerceAtLeast(256)

        val pinned = mutableListOf<ChatTurn>()
        var used = 0

        if (!systemPrompt.isNullOrBlank()) {
            pinned += ChatTurn(ChatTurn.SYSTEM, systemPrompt)
            used += engine.countTokens(systemPrompt)
        }

        val retrievalTurn = if (retrieved.isNotEmpty()) {
            val body = buildString {
                append("Use the following extracts from the user's own documents ")
                append("when they are relevant. If they do not answer the question, ")
                append("say so rather than guessing.\n\n")
                retrieved.forEachIndexed { i, passage ->
                    append("[").append(i + 1).append("] ").append(passage.trim()).append("\n\n")
                }
            }
            used += engine.countTokens(body)
            ChatTurn(ChatTurn.SYSTEM, body)
        } else null

        // Measure the history once, newest first.
        val measured = history.map { msg ->
            msg to (if (msg.tokens >= 0) msg.tokens else engine.countTokens(msg.content))
        }

        val recentCount = policy.keepRecentTurns.coerceAtMost(measured.size)
        val recent = measured.takeLast(recentCount)
        val older = measured.dropLast(recentCount)

        for ((_, tokens) in recent) used += tokens

        // Walk the older messages newest-first while there is room.
        val kept = ArrayDeque<Pair<ChatMessage, Int>>()
        var dropped = 0
        for (entry in older.asReversed()) {
            if (used + entry.second <= budget) {
                kept.addFirst(entry)
                used += entry.second
            } else {
                dropped++
            }
        }

        var summarised = 0
        if (dropped > 0 && policy.summariseOverflow) {
            val overflow = older.take(dropped).map { it.first }
            val summary = summarise(overflow)
            if (summary != null) {
                val summaryTokens = engine.countTokens(summary)
                if (used + summaryTokens <= budget) {
                    pinned += ChatTurn(
                        ChatTurn.SYSTEM,
                        "Summary of the earlier part of this conversation:\n$summary",
                    )
                    used += summaryTokens
                    summarised = overflow.size
                }
            }
        }

        val turns = buildList {
            addAll(pinned)
            kept.forEach { (msg, _) -> add(ChatTurn(msg.role, msg.content)) }
            // Retrieved context sits as late as possible, just before the
            // final user message.
            val recentTurns = recent.map { ChatTurn(it.first.role, it.first.content) }
            if (retrievalTurn != null) {
                val lastUserIndex = recentTurns.indexOfLast { it.role == ChatTurn.USER }
                if (lastUserIndex >= 0) {
                    addAll(recentTurns.subList(0, lastUserIndex))
                    add(retrievalTurn)
                    addAll(recentTurns.subList(lastUserIndex, recentTurns.size))
                } else {
                    add(retrievalTurn)
                    addAll(recentTurns)
                }
            } else {
                addAll(recentTurns)
            }
        }

        return ContextPlan(
            turns = turns,
            promptTokens = used,
            droppedMessages = dropped - summarised,
            summarisedMessages = summarised,
            budgetTokens = budget,
            usedRetrieval = retrievalTurn != null,
        )
    }

    /**
     * Compresses [messages] with the loaded model. Returns null if the model is
     * unavailable or the summary comes back empty — the caller then falls back
     * to simply dropping them.
     */
    private suspend fun summarise(messages: List<ChatMessage>): String? {
        if (messages.isEmpty() || !engine.isLoaded) return null

        // An existing summary is folded into the new one so the compression is
        // cumulative rather than starting over each time.
        val transcript = messages.joinToString("\n") { m ->
            val who = if (m.isSummary) "Earlier summary" else m.role.replaceFirstChar { it.uppercase() }
            "$who: ${m.content.take(2000)}"
        }

        val prompt = engine.renderPrompt(
            turns = listOf(
                ChatTurn(
                    ChatTurn.SYSTEM,
                    "You compress conversations. Reply with a factual summary in at most " +
                        "${policy.summaryTokenBudget / 2} words. Keep names, numbers, " +
                        "decisions and anything the user asked to be remembered. " +
                        "Do not add commentary.",
                ),
                ChatTurn(ChatTurn.USER, transcript),
            ),
            format = engine.loaded.value?.chatFormat ?: ChatFormat.AUTO,
        )

        val out = StringBuilder()
        return runCatching {
            engine.generate(
                prompt,
                SamplingParams.Precise.copy(
                    maxTokens = policy.summaryTokenBudget,
                    stopSequences = ChatFormatter.stopSequences(
                        engine.loaded.value?.chatFormat ?: ChatFormat.CHATML,
                    ),
                ),
            ).toList().forEach { event ->
                if (event is LlmEvent.Token) out.append(event.text)
            }
            out.toString().trim().ifBlank { null }
        }.onFailure { Log.w(TAG, "summarisation failed: ${it.message}") }.getOrNull()
    }

    private companion object {
        const val TAG = "LianContext"
    }
}
