package com.lian.plus.tools.builtin

import com.lian.plus.data.db.MemoryDao
import com.lian.plus.data.db.MemoryEntity
import com.lian.plus.rag.RagPipeline
import com.lian.plus.tools.Tool
import com.lian.plus.tools.ToolParameter
import com.lian.plus.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/** Searches documents the user has imported. */
class DocumentSearchTool(private val rag: RagPipeline) : Tool {
    override val name = "search_documents"
    override val description =
        "Searches the documents the user has added to this app. Use it whenever they " +
            "refer to their own notes, files or previously imported text."
    override val parameters = listOf(
        ToolParameter("query", "string", "What to look for."),
        ToolParameter("count", "integer", "How many passages, 1-6. Default 4.", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.str("query")
            ?: return ToolResult("No query given.", isError = true)
        val count = (args.num("count")?.toInt() ?: 4).coerceIn(1, 6)

        val hits = rag.retrieve(query, count)
        if (hits.isEmpty()) {
            return ToolResult(
                "Nothing in the user's documents matches \"$query\". Say so instead of guessing.",
            )
        }
        val body = hits.joinToString("\n\n") { h ->
            "From \"${h.documentTitle}\":\n${h.text}"
        }
        return ToolResult(
            content = body,
            displaySummary = "Searched documents for \"$query\" (${hits.size} passages)",
        )
    }
}

/**
 * Durable notes across conversations.
 *
 * Storage is a plain key/value table, which keeps the tool obvious to the model:
 * one key holds one fact, and writing the same key again replaces it.
 */
class MemoryTool(private val dao: MemoryDao) : Tool {
    override val name = "remember"
    override val description =
        "Saves a fact about the user so it survives into later conversations. Use it " +
            "when they say to remember something, or state a lasting preference."
    override val parameters = listOf(
        ToolParameter("key", "string", "Short label, e.g. 'name' or 'preferred_language'."),
        ToolParameter("value", "string", "The fact to store. Send an empty string to forget it."),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args.str("key")?.trim()?.lowercase()
            ?: return ToolResult("No key given.", isError = true)
        val value = args.str("value")

        return if (value.isNullOrBlank()) {
            dao.deleteByKey(key)
            ToolResult("Forgot '$key'.", displaySummary = "Forgot \"$key\"")
        } else {
            val existing = dao.byKey(key)
            dao.upsert(
                MemoryEntity(
                    id = existing?.id ?: 0,
                    key = key,
                    value = value,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            ToolResult("Saved: $key = $value", displaySummary = "Remembered \"$key\"")
        }
    }
}

/** Reads back what [MemoryTool] stored. */
class RecallTool(private val dao: MemoryDao) : Tool {
    override val name = "recall"
    override val description =
        "Lists the facts previously saved about the user. Call this when they ask what " +
            "you know about them."
    override val parameters = emptyList<ToolParameter>()

    override suspend fun execute(args: JsonObject): ToolResult {
        val all = dao.recent(50)
        if (all.isEmpty()) return ToolResult("Nothing has been saved yet.")
        return ToolResult(
            content = all.joinToString("\n") { "${it.key}: ${it.value}" },
            displaySummary = "Recalled ${all.size} saved fact(s)",
        )
    }
}
