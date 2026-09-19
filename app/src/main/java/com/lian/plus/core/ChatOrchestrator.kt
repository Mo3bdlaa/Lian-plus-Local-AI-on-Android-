package com.lian.plus.core

import android.util.Log
import com.lian.plus.llm.ChatFormat
import com.lian.plus.llm.ChatFormatter
import com.lian.plus.llm.ChatMessage
import com.lian.plus.llm.ChatTurn
import com.lian.plus.llm.ContextManager
import com.lian.plus.llm.ContextPlan
import com.lian.plus.llm.FinishReason
import com.lian.plus.llm.GenerationStats
import com.lian.plus.llm.LlmEngine
import com.lian.plus.llm.LlmEvent
import com.lian.plus.llm.SamplingParams
import com.lian.plus.rag.RagPipeline
import com.lian.plus.rag.Retrieved
import com.lian.plus.tools.ToolCall
import com.lian.plus.tools.ToolCallParser
import com.lian.plus.tools.ToolRegistry
import com.lian.plus.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What a single assistant turn emits while it runs. */
sealed interface TurnEvent {
    data object Thinking : TurnEvent
    data class Retrieved(val passages: List<com.lian.plus.rag.Retrieved>) : TurnEvent
    data class ContextPlanned(val plan: ContextPlan) : TurnEvent
    data class Prefill(val done: Int, val total: Int) : TurnEvent
    data class Delta(val text: String) : TurnEvent
    data class ToolStarted(val call: ToolCall) : TurnEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : TurnEvent
    data class Completed(val text: String, val stats: GenerationStats?, val reason: FinishReason) : TurnEvent
    data class Failed(val message: String) : TurnEvent
}

data class TurnOptions(
    val systemPrompt: String,
    val sampling: SamplingParams,
    val useTools: Boolean,
    val useRetrieval: Boolean,
    val maxToolRounds: Int = 3,
)

/**
 * Runs one assistant turn end to end: retrieval, context budgeting, generation
 * and the tool loop.
 *
 * Both the chat screen and the HTTP server go through here, so a request made
 * over the API behaves exactly like one typed into the app.
 */
class ChatOrchestrator(
    private val engine: LlmEngine,
    private val contextManager: ContextManager,
    private val tools: ToolRegistry,
    private val rag: RagPipeline,
) {

    fun run(
        history: List<ChatMessage>,
        options: TurnOptions,
    ): Flow<TurnEvent> = flow {
        val loaded = engine.loaded.value
        if (loaded == null) {
            emit(TurnEvent.Failed("No model is loaded. Pick one on the Models screen."))
            return@flow
        }
        emit(TurnEvent.Thinking)

        val lastUser = history.lastOrNull { it.role == ChatTurn.USER }?.content.orEmpty()

        // ---- retrieval ----------------------------------------------------
        var passages: List<Retrieved> = emptyList()
        if (options.useRetrieval && lastUser.isNotBlank()) {
            passages = runCatching { rag.retrieve(lastUser) }.getOrDefault(emptyList())
            if (passages.isNotEmpty()) emit(TurnEvent.Retrieved(passages))
        }

        // ---- system prompt ------------------------------------------------
        val systemPrompt = buildString {
            append(options.systemPrompt.trim())
            if (options.useTools) {
                val section = tools.promptSection()
                if (section.isNotEmpty()) append("\n\n").append(section)
            }
        }

        val format = loaded.chatFormat
        val stops = ChatFormatter.stopSequences(
            if (format == ChatFormat.AUTO) ChatFormat.CHATML else format,
        )

        val working = history.toMutableList()
        var finalText = ""
        var lastStats: GenerationStats? = null
        var reason = FinishReason.STOP

        for (round in 0..options.maxToolRounds) {
            val plan = contextManager.plan(
                systemPrompt = systemPrompt,
                history = working,
                retrieved = passages.map { "${it.documentTitle}: ${it.text}" },
                contextSize = loaded.contextSize,
                maxOutputTokens = options.sampling.maxTokens,
            )
            emit(TurnEvent.ContextPlanned(plan))

            val prompt = engine.renderPrompt(plan.turns, format, addGenerationPrompt = true)

            val buffer = StringBuilder()
            var failed: String? = null

            engine.generate(
                prompt,
                options.sampling.copy(stopSequences = stops + options.sampling.stopSequences),
            ).collect { event ->
                when (event) {
                    is LlmEvent.Prefill -> emit(TurnEvent.Prefill(event.done, event.total))
                    is LlmEvent.Token -> {
                        buffer.append(event.text)
                        // A tool call is machine-readable scaffolding, not
                        // prose; hold the stream back once one starts so the
                        // user never sees raw JSON appear and then vanish.
                        if (!options.useTools || !looksLikeToolCall(buffer)) {
                            emit(TurnEvent.Delta(event.text))
                        }
                    }
                    is LlmEvent.Finished -> { lastStats = event.stats; reason = event.reason }
                    is LlmEvent.Error -> failed = event.message
                }
            }

            if (failed != null) {
                emit(TurnEvent.Failed(failed!!))
                return@flow
            }

            val raw = buffer.toString()
            val calls = if (options.useTools) {
                ToolCallParser.parse(raw, tools.activeTools().map { it.name }.toSet())
            } else emptyList()

            if (calls.isEmpty() || round == options.maxToolRounds) {
                if (calls.isNotEmpty()) {
                    Log.w(TAG, "tool round limit reached; answering with what we have")
                }
                finalText = ToolCallParser.stripCalls(raw, calls).ifBlank { raw }.trim()
                break
            }

            // Record what the model asked for, then the results, so the next
            // round sees a coherent transcript. The call text is kept verbatim:
            // stripping it here would leave the model looking at a tool result
            // with no record of having asked for it.
            working += ChatMessage(id = -1, role = ChatTurn.ASSISTANT, content = raw.trim())

            for (call in calls) {
                emit(TurnEvent.ToolStarted(call))
                val result = tools.execute(call)
                emit(TurnEvent.ToolFinished(call, result))
                working += ChatMessage(
                    id = -1,
                    role = ChatTurn.TOOL,
                    content = "Result of ${call.name}:\n${result.content}",
                )
            }
            reason = FinishReason.TOOL_CALL
        }

        emit(TurnEvent.Completed(finalText, lastStats, reason))
    }

    /**
     * True while the buffer has started something that parses as a tool call
     * but has not finished it.
     */
    private fun looksLikeToolCall(buffer: StringBuilder): Boolean {
        val s = buffer.toString()
        val trimmed = s.trimStart()
        return trimmed.startsWith("<tool_call>") ||
            trimmed.startsWith("<function=") ||
            (trimmed.startsWith("{") && trimmed.contains("\"name\""))
    }

    private companion object {
        const val TAG = "LianTurn"
    }
}
