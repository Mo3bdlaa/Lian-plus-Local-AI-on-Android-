package com.lian.plus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        ModelEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
        MemoryEntity::class,
        GeneratedImageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
    abstract fun models(): ModelDao
    abstract fun documents(): DocumentDao
    abstract fun memories(): MemoryDao
    abstract fun images(): ImageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "lian.db",
            )
                // There is only one schema version so far; when that changes,
                // real migrations go here rather than a destructive fallback -
                // chat history is the user's, not ours to throw away.
                .build()
                .also { instance = it }
        }
    }
}
