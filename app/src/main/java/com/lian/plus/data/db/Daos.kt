package com.lian.plus.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun byId(id: Long): ChatEntity?

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("UPDATE chats SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun forChat(chatId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, tokens = :tokens, statsLine = :stats WHERE id = :id")
    suspend fun finalise(id: Long, content: String, tokens: Int, stats: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChat(chatId: Long)

    /** Drops a message and everything after it, for "retry from here". */
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id >= :fromId")
    suspend fun deleteFrom(chatId: Long, fromId: Long)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM installed_models WHERE kind = :kind ORDER BY addedAt DESC")
    fun observeByKind(kind: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM installed_models WHERE id = :id")
    suspend fun byId(id: String): ModelEntity?

    @Query("SELECT * FROM installed_models")
    suspend fun all(): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE installed_models SET lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Insert
    suspend fun insert(doc: DocumentEntity): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE embedding IS NOT NULL")
    suspend fun allEmbeddedChunks(): List<ChunkEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun documentById(id: Long): DocumentEntity?

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun chunkCount(): Int

    /** Keyword fallback for when no embedding model is installed. */
    @Query("SELECT * FROM chunks WHERE text LIKE '%' || :term || '%' LIMIT :limit")
    suspend fun keywordSearch(term: String, limit: Int): List<ChunkEntity>

    @Transaction
    suspend fun insertDocumentWithChunks(doc: DocumentEntity, chunks: (Long) -> List<ChunkEntity>): Long {
        val id = insert(doc)
        insertChunks(chunks(id))
        return id
    }
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE key = :key")
    suspend fun byKey(key: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GeneratedImageEntity>>

    @Insert
    suspend fun insert(image: GeneratedImageEntity): Long

    @Query("SELECT * FROM generated_images WHERE id = :id")
    suspend fun byId(id: Long): GeneratedImageEntity?

    @Query("SELECT * FROM generated_images WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<GeneratedImageEntity>

    @Query("DELETE FROM generated_images WHERE id = :id")
    suspend fun delete(id: Long)
}
