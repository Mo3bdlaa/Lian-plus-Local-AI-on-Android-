package com.lian.plus.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lian.plus.data.db.AppDatabase
import com.lian.plus.data.db.ChunkEntity
import com.lian.plus.data.db.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Indexes the user's documents and retrieves passages for a query. */
class RagPipeline(
    private val context: Context,
    private val embedder: EmbeddingEngine,
) {
    private val db = AppDatabase.get(context)
    private val docs = db.documents()

    fun observeDocuments(): Flow<List<DocumentEntity>> = docs.observeAll()

    suspend fun chunkCount(): Int = withContext(Dispatchers.IO) { docs.chunkCount() }

    /**
     * Reads [uri], splits it and stores the chunks with their embeddings.
     *
     * Embedding happens at import rather than at query time: doing it per query
     * would mean re-encoding the whole corpus on every message, which on a
     * phone is the difference between a search that takes milliseconds and one
     * that takes minutes.
     */
    suspend fun addDocument(
        uri: Uri,
        title: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val text = readText(uri)
            if (text.isBlank()) error("that file has no readable text in it")
            addText(text, title, onProgress).getOrThrow()
        }
    }

    suspend fun addText(
        text: String,
        title: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val chunks = Chunker.chunk(text)
            if (chunks.isEmpty()) error("nothing to index")

            val vectors = if (embedder.isLoaded) {
                embedder.embedAll(chunks.map { it.text }) { done, total -> onProgress(done, total) }
            } else {
                // Without an embedding model the chunks are still stored; the
                // keyword fallback in [retrieve] can use them, and they will be
                // embeddable later.
                Log.i(TAG, "no embedding model loaded - storing chunks unembedded")
                List(chunks.size) { FloatArray(0) }
            }

            val docId = docs.insert(
                DocumentEntity(
                    title = title,
                    sourceUri = null,
                    charCount = text.length,
                    chunkCount = chunks.size,
                    embeddingModelId = embedder.loadedModel?.id,
                ),
            )
            docs.insertChunks(
                chunks.mapIndexed { i, c ->
                    ChunkEntity(
                        documentId = docId,
                        ordinal = c.index,
                        text = c.text,
                        embedding = vectors.getOrNull(i)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { VectorIndex.toBlob(it) },
                    )
                },
            )
            docId
        }
    }

    suspend fun removeDocument(id: Long) = withContext(Dispatchers.IO) { docs.delete(id) }

    /**
     * Finds passages relevant to [query].
     *
     * Falls back to a keyword LIKE scan when no embedding model is loaded, so
     * the feature degrades instead of disappearing.
     */
    suspend fun retrieve(query: String, topK: Int = 4): List<Retrieved> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            if (embedder.isLoaded) {
                val queryVector = embedder.embed(query)
                if (queryVector.isNotEmpty()) {
                    val candidates = docs.allEmbeddedChunks()
                    val hits = VectorIndex.search(
                        query = queryVector,
                        candidates = candidates,
                        vectorOf = { VectorIndex.fromBlob(it.embedding!!) },
                        topK = topK,
                    )
                    if (hits.isNotEmpty()) return@withContext hits.map { (chunk, score) ->
                        Retrieved(
                            chunkId = chunk.id,
                            documentId = chunk.documentId,
                            documentTitle = docs.documentById(chunk.documentId)?.title ?: "document",
                            text = chunk.text,
                            score = score,
                        )
                    }
                }
            }

            // Keyword fallback: the longest words carry the most signal.
            val terms = query.split(Regex("\\W+"))
                .filter { it.length > 3 }
                .sortedByDescending { it.length }
                .take(3)
            val seen = LinkedHashMap<Long, ChunkEntity>()
            for (term in terms) {
                docs.keywordSearch(term, topK).forEach { seen.putIfAbsent(it.id, it) }
            }
            seen.values.take(topK).map { chunk ->
                Retrieved(
                    chunkId = chunk.id,
                    documentId = chunk.documentId,
                    documentTitle = docs.documentById(chunk.documentId)?.title ?: "document",
                    text = chunk.text,
                    score = 0f,
                )
            }
        }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: error("could not open that file")

    private companion object {
        const val TAG = "LianRag"
    }
}
