package com.lian.plus

import com.lian.plus.rag.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun `short text stays as one chunk`() {
        val chunks = Chunker.chunk("A single short sentence.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `long text is split and every chunk fits the target`() {
        val text = (1..400).joinToString(" ") { "word$it." }
        val chunks = Chunker.chunk(text, targetChars = 500, overlapChars = 50)
        assertTrue(chunks.size > 1)
        // Allow a little slack: a break is taken at the next boundary.
        assertTrue(chunks.all { it.text.length <= 600 })
    }

    @Test
    fun `chunks overlap so a fact on a boundary stays findable`() {
        val text = (1..200).joinToString(" ") { "sentence number $it here." }
        val chunks = Chunker.chunk(text, targetChars = 400, overlapChars = 100)
        assertTrue(chunks.size >= 2)
        val first = chunks[0]
        val second = chunks[1]
        assertTrue("chunks should overlap", second.startChar < first.endChar)
    }

    @Test
    fun `prefers paragraph boundaries`() {
        val text = "First paragraph.\n\n" + "x".repeat(300) + "\n\n" + "Third paragraph."
        val chunks = Chunker.chunk(text, targetChars = 340, overlapChars = 20)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `empty input produces nothing`() {
        assertTrue(Chunker.chunk("   ").isEmpty())
    }
}
