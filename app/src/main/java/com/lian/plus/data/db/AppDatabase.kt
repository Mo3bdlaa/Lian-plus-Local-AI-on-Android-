package com.lian.plus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 4,
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
        /**
         * Adds `role` to installed_models.
         *
         * A real migration rather than a destructive fallback: chat history is
         * the user's, and a schema change is no reason to take it.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE installed_models ADD COLUMN role TEXT NOT NULL " +
                        "DEFAULT 'STANDALONE'",
                )
            }
        }

        /** Links a message to the image generation that produced it. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN generatedImageId INTEGER")
            }
        }

        /**
         * Records which diffusion family an image model belongs to.
         *
         * Existing rows get null and are re-detected the next time the store
         * syncs, which costs one header read per file.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE installed_models ADD COLUMN diffusionArch TEXT")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "lian.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
