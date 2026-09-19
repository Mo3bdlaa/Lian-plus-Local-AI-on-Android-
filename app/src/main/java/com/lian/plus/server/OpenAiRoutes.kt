package com.lian.plus.server

import android.util.Log
import com.lian.plus.core.LianRuntime
import com.lian.plus.core.TurnEvent
import com.lian.plus.core.TurnOptions
import com.lian.plus.core.model.ModelKind
import com.lian.plus.image.ImageEvent
import com.lian.plus.llm.ChatMessage
import com.lian.plus.llm.ChatTurn
import com.lian.plus.llm.FinishReason
import com.lian.plus.llm.SamplingParams
import com.lian.plus.server.HttpResponse.Companion.quote
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * OpenAI-compatible endpoints backed by the on-device engines.
 *
 * The shapes follow the OpenAI REST API closely enough that existing clients
 * work unchanged — which is the whole point of serving locally: point a tool at
 * `http://127.0.0.1:8080/v1` and nothing leaves the phone.
 */
class OpenAiRoutes(private val runtime: LianRuntime) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun handle(request: HttpRequest): HttpResponse {
        val apiKey = runtime.currentSettings.serverApiKey
        if (apiKey.isNotBlank() && request.path != "/health") {
            val provided = request.header("authorization")
                ?.removePrefix("Bearer ")?.trim()
            if (provided != apiKey) {
                return HttpResponse.error(401, "Invalid API key.", "invalid_api_key")
            }
        }

        return when {
            request.path == "/health" || request.path == "/" -> health()
            request.path == "/v1/models" && request.method == "GET" -> models()
            request.path == "/v1/chat/completions" && request.method == "POST" ->
                chatCompletions(request)
            request.path == "/v1/completions" && request.method == "POST" ->
                completions(request)
            request.path == "/v1/embeddings" && request.method == "POST" ->
                embeddings(request)
            request.path == "/v1/images/generations" && request.method == "POST" ->
                imageGenerations(request)
            else -> HttpResponse.error(404, "Unknown endpoint: ${request.path}")
        }
    }

    private fun health(): HttpResponse {
        val loaded = runtime.llm.loaded.value
        return HttpResponse.json(
            """{"status":"ok","engine":${quote(if (com.lian.plus.llm.LlamaNative.isAvailable) "ready" else "missing")},""" +
                """"model":${loaded?.model?.id?.let { quote(it) } ?: "null"},""" +
                """"context":${loaded?.contextSize ?: 0}}""",
        )
    }

    private suspend fun models(): HttpResponse {
        val installed = runtime.modelStore.observeAll().first()
        val body = installed.joinToString(",") { m ->
            """{"id":${quote(m.id)},"object":"model","created":${m.addedAtMillis / 1000},""" +
                """"owned_by":${quote(m.repoId ?: "local")},""" +
                """"lian":{"kind":${quote(m.kind.name.lowercase())},"size_bytes":${m.sizeBytes},""" +
                """"quant":${quote(m.quant.tag)},"context_trained":${m.contextTrained ?: 0}}}"""
        }
        return HttpResponse.json("""{"object":"list","data":[$body]}""")
    }

    private suspend fun chatCompletions(request: HttpRequest): HttpResponse {
        val body = runCatching { json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            ?: return HttpResponse.error(400, "Body is not valid JSON.")

        val messages = body["messages"]?.jsonArray
            ?: return HttpResponse.error(400, "'messages' is required.")

        val history = mutableListOf<ChatMessage>()
        var systemPrompt = runtime.currentSettings.systemPrompt
        for (element in messages) {
            val m = element.jsonObject
            val role = m["role"]?.jsonPrimitive?.content ?: continue
            val content = extractContent(m) ?: continue
            if (role == ChatTurn.SYSTEM) {
                systemPrompt = content
            } else {
                history += ChatMessage(id = -1, role = role, content = content)
            }
        }
        if (history.isEmpty()) {
            return HttpResponse.error(400, "'messages' contains no user content.")
        }

        if (!runtime.llm.isLoaded) {
            return HttpResponse.error(
                503, "No model is loaded on the device.", "model_not_loaded",
            )
        }

        val sampling = samplingFrom(body)
        val stream = body["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        val options = TurnOptions(
            systemPrompt = systemPrompt,
            sampling = sampling,
            // Tools are driven by the device's own settings; a remote caller
            // cannot switch on web access that the user left off.
            useTools = runtime.currentSettings.toolsEnabled,
            useRetrieval = runtime.currentSettings.ragEnabled,
        )

        val modelId = runtime.llm.loaded.value?.model?.id ?: "local"
        val created = System.currentTimeMillis() / 1000
        val id = "chatcmpl-${System.nanoTime().toString(36)}"

        if (!stream) {
            val text = StringBuilder()
            var finish = FinishReason.STOP
            var promptTokens = 0
            var completionTokens = 0
            var error: String? = null

            runtime.orchestrator.run(history, options).collect { event ->
                when (event) {
                    is TurnEvent.Delta -> text.append(event.text)
                    is TurnEvent.Completed -> {
                        finish = event.reason
                        promptTokens = event.stats?.promptTokens ?: 0
                        completionTokens = event.stats?.generatedTokens ?: 0
                        if (text.isEmpty()) text.append(event.text)
                    }
                    is TurnEvent.Failed -> error = event.message
                    else -> Unit
                }
            }
            error?.let { return HttpResponse.error(500, it, "engine_error") }

            return HttpResponse.json(
                """{"id":${quote(id)},"object":"chat.completion","created":$created,""" +
                    """"model":${quote(modelId)},"choices":[{"index":0,"message":""" +
                    """{"role":"assistant","content":${quote(text.toString())}},""" +
                    """"finish_reason":${quote(finishReason(finish))}}],""" +
                    """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,""" +
                    """"total_tokens":${promptTokens + completionTokens}}}""",
            )
        }

        return HttpResponse.Stream { sse ->
            sse.send(
                """{"id":${quote(id)},"object":"chat.completion.chunk","created":$created,""" +
                    """"model":${quote(modelId)},"choices":[{"index":0,""" +
                    """"delta":{"role":"assistant"},"finish_reason":null}]}""",
            )
            var finish = FinishReason.STOP
            runCatching {
                runtime.orchestrator.run(history, options).collect { event ->
                    when (event) {
                        is TurnEvent.Delta -> sse.send(
                            """{"id":${quote(id)},"object":"chat.completion.chunk","created":$created,""" +
                                """"model":${quote(modelId)},"choices":[{"index":0,""" +
                                """"delta":{"content":${quote(event.text)}},"finish_reason":null}]}""",
                        )
                        is TurnEvent.Completed -> finish = event.reason
                        is TurnEvent.Failed -> sse.send(
                            """{"error":{"message":${quote(event.message)},"type":"engine_error"}}""",
                        )
                        else -> Unit
                    }
                }
            }.onFailure { Log.w(TAG, "stream ended: ${it.message}") }

            sse.send(
                """{"id":${quote(id)},"object":"chat.completion.chunk","created":$created,""" +
                    """"model":${quote(modelId)},"choices":[{"index":0,"delta":{},""" +
                    """"finish_reason":${quote(finishReason(finish))}}]}""",
            )
            sse.done()
        }
    }

    /** Raw completion: no chat template, no tools — the prompt goes through as written. */
    private suspend fun completions(request: HttpRequest): HttpResponse {
        val body = runCatching { json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            ?: return HttpResponse.error(400, "Body is not valid JSON.")
        val prompt = body["prompt"]?.jsonPrimitive?.content
            ?: return HttpResponse.error(400, "'prompt' is required.")
        if (!runtime.llm.isLoaded) {
            return HttpResponse.error(503, "No model is loaded.", "model_not_loaded")
        }

        val sampling = samplingFrom(body)
        val text = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0

        runtime.llm.generate(prompt, sampling).collect { event ->
            when (event) {
                is com.lian.plus.llm.LlmEvent.Token -> text.append(event.text)
                is com.lian.plus.llm.LlmEvent.Finished -> {
                    promptTokens = event.stats.promptTokens
                    completionTokens = event.stats.generatedTokens
                }
                else -> Unit
            }
        }

        val modelId = runtime.llm.loaded.value?.model?.id ?: "local"
        return HttpResponse.json(
            """{"id":"cmpl-${System.nanoTime().toString(36)}","object":"text_completion",""" +
                """"created":${System.currentTimeMillis() / 1000},"model":${quote(modelId)},""" +
                """"choices":[{"index":0,"text":${quote(text.toString())},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,""" +
                """"total_tokens":${promptTokens + completionTokens}}}""",
        )
    }

    private suspend fun embeddings(request: HttpRequest): HttpResponse {
        val body = runCatching { json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            ?: return HttpResponse.error(400, "Body is not valid JSON.")
        if (!runtime.embedder.isLoaded) {
            return HttpResponse.error(
                503,
                "No embedding model is loaded. Install one on the Models screen.",
                "model_not_loaded",
            )
        }

        val input = body["input"]
        val texts = when (input) {
            is JsonArray -> input.map { it.jsonPrimitive.content }
            null -> return HttpResponse.error(400, "'input' is required.")
            else -> listOf(input.jsonPrimitive.content)
        }

        val vectors = texts.map { runtime.embedder.embed(it) }
        val data = vectors.mapIndexed { i, v ->
            """{"object":"embedding","index":$i,"embedding":[${v.joinToString(",")}]}"""
        }.joinToString(",")

        val modelId = runtime.embedder.loadedModel?.id ?: "local-embedding"
        val tokens = texts.sumOf { runtime.llm.estimateTokens(it) }
        return HttpResponse.json(
            """{"object":"list","data":[$data],"model":${quote(modelId)},""" +
                """"usage":{"prompt_tokens":$tokens,"total_tokens":$tokens}}""",
        )
    }

    private suspend fun imageGenerations(request: HttpRequest): HttpResponse {
        val body = runCatching { json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            ?: return HttpResponse.error(400, "Body is not valid JSON.")
        val prompt = body["prompt"]?.jsonPrimitive?.content
            ?: return HttpResponse.error(400, "'prompt' is required.")

        if (runtime.imageClient.state.value.loadedModelId == null) {
            return HttpResponse.error(
                503, "No image model is loaded.", "model_not_loaded",
            )
        }

        val size = body["size"]?.jsonPrimitive?.content
            ?.substringBefore('x')?.toIntOrNull()
            ?: runtime.currentSettings.imageSize

        val base = runtime.defaultImageRequest()
        val target = File(runtime.imagesDir, "api_${System.currentTimeMillis()}.png")
        var result: ImageEvent? = null

        runtime.imageClient
            .generate(base.copy(prompt = prompt, width = size, height = size), target)
            .collect { if (it is ImageEvent.Done || it is ImageEvent.Failed) result = it }

        return when (val r = result) {
            is ImageEvent.Done -> {
                // The file is inside the app's sandbox, so a remote caller gets
                // base64 rather than a path it could never open.
                val b64 = android.util.Base64.encodeToString(
                    r.file.readBytes(), android.util.Base64.NO_WRAP,
                )
                HttpResponse.json(
                    """{"created":${System.currentTimeMillis() / 1000},""" +
                        """"data":[{"b64_json":${quote(b64)}}]}""",
                )
            }
            is ImageEvent.Failed -> HttpResponse.error(500, r.message, "engine_error")
            else -> HttpResponse.error(500, "Image generation produced no result.")
        }
    }

    /** Accepts both `"content": "text"` and the multimodal array form. */
    private fun extractContent(message: JsonObject): String? {
        val content = message["content"] ?: return null
        return when (content) {
            is JsonArray -> content.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content
            }.joinToString("\n").ifBlank { null }
            else -> content.jsonPrimitive.content
        }
    }

    private fun samplingFrom(body: JsonObject): SamplingParams {
        val defaults = runtime.currentSettings.sampling()
        return defaults.copy(
            maxTokens = body["max_tokens"]?.jsonPrimitive?.intOrNull
                ?: body["max_completion_tokens"]?.jsonPrimitive?.intOrNull
                ?: defaults.maxTokens,
            temperature = body["temperature"]?.jsonPrimitive?.floatOrNull ?: defaults.temperature,
            topP = body["top_p"]?.jsonPrimitive?.floatOrNull ?: defaults.topP,
            topK = body["top_k"]?.jsonPrimitive?.intOrNull ?: defaults.topK,
            frequencyPenalty = body["frequency_penalty"]?.jsonPrimitive?.floatOrNull ?: 0f,
            presencePenalty = body["presence_penalty"]?.jsonPrimitive?.floatOrNull ?: 0f,
            seed = body["seed"]?.jsonPrimitive?.intOrNull ?: defaults.seed,
            stopSequences = when (val stop = body["stop"]) {
                is JsonArray -> stop.map { it.jsonPrimitive.content }
                null -> emptyList()
                else -> listOf(stop.jsonPrimitive.content)
            },
        )
    }

    private fun finishReason(reason: FinishReason) = when (reason) {
        FinishReason.LENGTH -> "length"
        FinishReason.TOOL_CALL -> "tool_calls"
        else -> "stop"
    }

    private companion object {
        const val TAG = "LianRoutes"
    }
}
