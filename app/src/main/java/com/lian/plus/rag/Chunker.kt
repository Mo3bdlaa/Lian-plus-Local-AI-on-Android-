package com.lian.plus.rag

/** One retrievable slice of a document. */
data class Chunk(
    val text: String,
    val index: Int,
    val startChar: Int,
    val endChar: Int,
)

/**
 * Splits text into overlapping chunks.
 *
 * Splitting happens at paragraph boundaries first and sentence boundaries
 * second, because a chunk cut mid-sentence embeds badly — the vector ends up
 * describing a fragment nobody would ever search for. The overlap keeps a fact
 * that straddles a boundary retrievable from either side.
 */
object Chunker {

    fun chunk(
        text: String,
        targetChars: Int = 1200,
        overlapChars: Int = 200,
    ): List<Chunk> {
        val normalised = text.replace("\r\n", "\n").trim()
        if (normalised.isEmpty()) return emptyList()
        if (normalised.length <= targetChars) {
            return listOf(Chunk(normalised, 0, 0, normalised.length))
        }

        val out = mutableListOf<Chunk>()
        var cursor = 0
        var index = 0

        while (cursor < normalised.length) {
            val hardEnd = (cursor + targetChars).coerceAtMost(normalised.length)
            val end = if (hardEnd >= normalised.length) {
                hardEnd
            } else {
                // Prefer a paragraph break in the last third of the window,
                // then a sentence end, then a space.
                val windowStart = cursor + (targetChars * 2) / 3
                findBreak(normalised, windowStart, hardEnd) ?: hardEnd
            }

            val slice = normalised.substring(cursor, end).trim()
            if (slice.isNotEmpty()) {
                out += Chunk(slice, index++, cursor, end)
            }
            if (end >= normalised.length) break
            cursor = (end - overlapChars).coerceAtLeast(cursor + 1)
        }
        return out
    }

    private fun findBreak(text: String, from: Int, to: Int): Int? {
        val paragraph = text.lastIndexOf("\n\n", to - 1)
        if (paragraph in from until to) return paragraph + 2

        for (i in to - 1 downTo from) {
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            ) return i + 1
        }
        val space = text.lastIndexOf(' ', to - 1)
        return if (space in from until to) space + 1 else null
    }
}
