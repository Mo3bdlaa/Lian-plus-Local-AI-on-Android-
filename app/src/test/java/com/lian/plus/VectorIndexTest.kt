package com.lian.plus

import com.lian.plus.rag.VectorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VectorIndexTest {

    @Test
    fun `blob round trip preserves the vector`() {
        val v = floatArrayOf(0.5f, -0.25f, 1f, 0f, 0.125f)
        val back = VectorIndex.fromBlob(VectorIndex.toBlob(v))
        assertEquals(v.size, back.size)
        v.indices.forEach { assertTrue(abs(v[it] - back[it]) < 1e-6) }
    }

    @Test
    fun `dot product handles the unrolled tail`() {
        // 5 elements exercises both the unrolled block and the remainder loop.
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val b = floatArrayOf(2f, 2f, 2f, 2f, 2f)
        assertEquals(30f, VectorIndex.dot(a, b), 1e-5f)
    }

    @Test
    fun `search ranks by similarity and honours the floor`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            "same" to floatArrayOf(1f, 0f),
            "near" to floatArrayOf(0.8f, 0.6f),
            "orthogonal" to floatArrayOf(0f, 1f),
        )
        val hits = VectorIndex.search(
            query = query,
            candidates = candidates,
            vectorOf = { it.second },
            topK = 3,
            minScore = 0.25f,
        )
        assertEquals(2, hits.size)
        assertEquals("same", hits[0].first.first)
        assertEquals("near", hits[1].first.first)
    }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        val hits = VectorIndex.search(
            query = FloatArray(0),
            candidates = listOf("a" to floatArrayOf(1f)),
            vectorOf = { it.second },
        )
        assertTrue(hits.isEmpty())
    }
}
