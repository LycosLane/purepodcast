package com.lycoslane.purepodcast.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.NONE)
}

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        QueueItemEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PurePodcastDatabase : RoomDatabase() {
    abstract fun dao(): PurePodcastDao

    companion object {
        @Volatile
        private var INSTANCE: PurePodcastDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN playedAtMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_pubDateMs` ON `episodes` (`podcastId`, `pubDateMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_isPlayed` ON `episodes` (`podcastId`, `isPlayed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_downloadState` ON `episodes` (`podcastId`, `downloadState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isPlayed_downloadState` ON `episodes` (`isPlayed`, `downloadState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_sortOrder` ON `queue_items` (`sortOrder`)")
            }
        }

        fun getInstance(context: Context): PurePodcastDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PurePodcastDatabase::class.java,
                    "purepodcast.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
