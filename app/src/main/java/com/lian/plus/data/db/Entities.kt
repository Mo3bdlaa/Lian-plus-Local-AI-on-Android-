package com.lian.plus.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String?,
    val modelId: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Cached token count, -1 when not yet measured. */
    val tokens: Int = -1,
    /** True for compressed history written by the context manager. */
    val isSummary: Boolean = false,
    /** Set on tool messages. */
    val toolName: String? = null,
    /** Absolute path to a generated image, for image replies. */
    val imagePath: String? = null,
    /** Human-readable generation stats, shown under the message. */
    val statsLine: String? = null,
    val isError: Boolean = false,
)

@Entity(tableName = "installed_models", indices = [Index(value = ["filePath"], unique = true)])
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val kind: String,
    val filePath: String,
    val sizeBytes: Long,
    val repoId: String?,
    val fileName: String,
    val quant: String,
    val architecture: String?,
    val parameterCount: Long?,
    val contextTrained: Int?,
    val embeddingDim: Int?,
    val chatTemplate: String?,
    val component: String?,
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUri: String?,
    val charCount: Int,
    val chunkCount: Int,
    val embeddingModelId: String?,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val ordinal: Int,
    val text: String,
    /** Little-endian float32 vector; see VectorIndex.toBlob. */
    val embedding: ByteArray?,
) {
    // Room data classes with an array field need these to behave sensibly.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ChunkEntity && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

/** Facts the model chose to remember, via the `remember` tool. */
@Entity(tableName = "memories", indices = [Index(value = ["key"], unique = true)])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A finished image generation, for the gallery. */
@Entity(tableName = "generated_images")
data class GeneratedImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val prompt: String,
    val negativePrompt: String?,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val sampler: String,
    val modelId: String?,
    val durationMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
)
