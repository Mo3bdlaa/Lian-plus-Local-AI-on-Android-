package com.lian.plus.tools

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Holds the tools available to the model and renders them into the prompt.
 *
 * Small local models are much more reliable with an explicit, compact
 * description than with a full JSON Schema dump, so the prompt section below is
 * deliberately terse.
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()
    private val enabled = mutableSetOf<String>()

    fun register(tool: Tool, enabledByDefault: Boolean = true) {
        tools[tool.name] = tool
        if (enabledByDefault) enabled += tool.name
    }

    fun all(): List<Tool> = tools.values.toList()
    fun activeTools(): List<Tool> = tools.values.filter { it.name in enabled }
    fun isEnabled(name: String): Boolean = name in enabled

    fun setEnabled(name: String, value: Boolean) {
        if (value) enabled += name else enabled -= name
    }

    /** The system-prompt fragment describing the active tools. Empty if none. */
    fun promptSection(): String {
        val active = activeTools()
        if (active.isEmpty()) return ""
        return buildString {
            append("You can call tools. To call one, emit exactly:\n")
            append("<tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>\n")
            append("Emit nothing else in that message. The result comes back as a ")
            append("tool message, and you then answer the user normally.\n")
            append("Only call a tool when it genuinely helps; answer directly otherwise.\n\n")
            append("Available tools:\n")
            for (t in active) {
                append("- ").append(t.name).append(": ").append(t.description).append('\n')
                for (p in t.parameters) {
                    append("    ").append(p.name).append(" (").append(p.type)
                    if (!p.required) append(", optional")
                    append("): ").append(p.description)
                    p.enumValues?.let { append(" [one of: ").append(it.joinToString("|")).append(']') }
                    append('\n')
                }
            }
        }
    }

    /**
     * Runs [call]. Unknown tools and timeouts come back as error results rather
     * than exceptions, so a bad call becomes something the model can read and
     * recover from instead of ending the turn.
     */
    suspend fun execute(call: ToolCall, timeoutMillis: Long = 30_000): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult(
                "There is no tool called '${call.name}'. Available: " +
                    activeTools().joinToString { it.name },
                isError = true,
            )
        if (tool.name !in enabled) {
            return ToolResult("The tool '${call.name}' is turned off.", isError = true)
        }

        val missing = tool.parameters
            .filter { it.required && !call.arguments.containsKey(it.name) }
            .map { it.name }
        if (missing.isNotEmpty()) {
            return ToolResult(
                "Missing required argument(s): ${missing.joinToString()}.",
                isError = true,
            )
        }

        return try {
            withTimeout(timeoutMillis) { tool.execute(call.arguments) }
        } catch (e: TimeoutCancellationException) {
            ToolResult("'${call.name}' timed out after ${timeoutMillis / 1000}s.", isError = true)
        } catch (e: Exception) {
            Log.w(TAG, "tool ${call.name} failed", e)
            ToolResult("'${call.name}' failed: ${e.message}", isError = true)
        }
    }

    private companion object {
        const val TAG = "LianTools"
    }
}
