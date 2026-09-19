package com.lian.plus.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts tool calls from model output.
 *
 * Every family emits a different shape and local models are inconsistent even
 * within one family, so several are accepted. The parser is deliberately
 * forgiving about what surrounds the call and strict about the JSON itself: a
 * malformed call must not be silently treated as a valid one.
 */
object ToolCallParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TOOL_CALL_TAG = Regex(
        """<tool_call>\s*(\{.*?})\s*</tool_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Llama 3.1 style: `<function=get_weather>{"city": "Cairo"}</function>` */
    private val FUNCTION_TAG = Regex(
        """<function\s*=\s*([\w.-]+)\s*>\s*(\{.*?})\s*</function>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val FENCED_JSON = Regex(
        """```(?:json|tool_call)?\s*(\{.*?})\s*```""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * @param knownTools used only by the loosest heuristic, so a bare JSON
     *        object in a normal answer is not mistaken for a call.
     */
    fun parse(text: String, knownTools: Set<String> = emptySet()): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()

        for (m in TOOL_CALL_TAG.findAll(text)) {
            toCall(m.groupValues[1], m.value)?.let { calls += it }
        }
        if (calls.isNotEmpty()) return calls

        for (m in FUNCTION_TAG.findAll(text)) {
            val args = runCatching { json.parseToJsonElement(m.groupValues[2]).jsonObject }.getOrNull()
            if (args != null) {
                calls += ToolCall(m.groupValues[1], args, m.value)
            }
        }
        if (calls.isNotEmpty()) return calls

        for (m in FENCED_JSON.findAll(text)) {
            toCall(m.groupValues[1], m.value)?.let { calls += it }
        }
        if (calls.isNotEmpty()) return calls

        // Last resort: a bare object whose "name" matches a tool we actually
        // have. Without that check this would fire on any JSON the model wrote.
        if (knownTools.isNotEmpty()) {
            extractBalancedObject(text)?.let { (span, obj) ->
                toCall(obj, span)?.takeIf { it.name in knownTools }?.let { calls += it }
            }
        }
        return calls
    }

    /** Removes the call spans so only prose is shown to the user. */
    fun stripCalls(text: String, calls: List<ToolCall>): String {
        var out = text
        for (c in calls) out = out.replace(c.rawText, "")
        return out.trim()
    }

    private fun toCall(jsonText: String, raw: String): ToolCall? = runCatching {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        val name = (obj["name"] ?: obj["tool"] ?: obj["function"])
            ?.jsonPrimitive?.content ?: return null
        val argsElement: JsonElement? = obj["arguments"] ?: obj["parameters"] ?: obj["args"]
        val args = when {
            argsElement is JsonObject -> argsElement
            // Some models stringify the arguments object.
            argsElement != null -> runCatching {
                json.parseToJsonElement(argsElement.jsonPrimitive.content).jsonObject
            }.getOrElse { JsonObject(emptyMap()) }
            else -> JsonObject(emptyMap())
        }
        ToolCall(name, args, raw)
    }.getOrNull()

    /** Finds the first brace-balanced JSON object, ignoring braces inside strings. */
    private fun extractBalancedObject(text: String): Pair<String, String>? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val span = text.substring(start, i + 1)
                        return span to span
                    }
                }
            }
        }
        return null
    }
}
