package com.lian.plus.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A retrieved passage plus how well it matched. */
data class Retrieved(
    val chunkId: Long,
    val documentId: Long,
    val documentTitle: String,
    val text: String,
    val score: Float,
)

/**
 * Similarity search over stored embeddings.
 *
 * Everything the [EmbeddingEngine] produces is already L2-normalised, so cosine
 * similarity is a plain dot product. A phone's corpus is a few thousand chunks
 * at most, so a linear scan over a float array beats any index structure — and
 * unlike an index it needs no rebuild when a document is added.
 */
object VectorIndex {

    fun toBlob(vector: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun fromBlob(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.float }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var sum = 0f
        var i = 0
        // Unrolled by four: this is the inner loop of every search.
        while (i + 3 < n) {
            sum += a[i] * b[i] + a[i + 1] * b[i + 1] + a[i + 2] * b[i + 2] + a[i + 3] * b[i + 3]
            i += 4
        }
        while (i < n) { sum += a[i] * b[i]; i++ }
        return sum
    }

    /**
     * Ranks [candidates] against [query] and keeps the best [topK] above
     * [minScore].
     *
     * [minScore] matters more than it looks: without a floor, an empty corpus
     * still returns its three least-irrelevant chunks, and the model dutifully
     * builds an answer on them.
     */
    fun <T> search(
        query: FloatArray,
        candidates: List<T>,
        vectorOf: (T) -> FloatArray,
        topK: Int = 4,
        minScore: Float = 0.25f,
    ): List<Pair<T, Float>> {
        if (query.isEmpty()) return emptyList()
        return candidates
            .asSequence()
            .map { it to dot(query, vectorOf(it)) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
    }
}
