package com.lian.plus.tools

import kotlinx.serialization.json.JsonObject

/** A JSON-schema-ish parameter description, kept small enough to fit in a prompt. */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/** The outcome of running a tool. [content] is what goes back to the model. */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    /** Shown in the UI instead of the raw content when set. */
    val displaySummary: String? = null,
)

/**
 * A capability the model can invoke.
 *
 * Tools run on the device, in the app's own process, with the app's own
 * permissions. Anything that reaches the network or the filesystem says so in
 * [requiresNetwork] / [isSensitive] so the UI can gate it.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>
    val requiresNetwork: Boolean get() = false
    val isSensitive: Boolean get() = false

    suspend fun execute(args: JsonObject): ToolResult
}

/** A parsed request from the model. */
data class ToolCall(
    val name: String,
    val arguments: JsonObject,
    /** The exact text span the call was parsed from, so it can be stripped. */
    val rawText: String,
    val id: String = "call_" + name + "_" + System.nanoTime().toString(36),
)
