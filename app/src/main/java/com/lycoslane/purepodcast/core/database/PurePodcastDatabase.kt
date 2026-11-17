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
    version = 7,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN markedForDeletionAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN silenceIntensity INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_isHidden` ON `episodes` (`podcastId`, `isHidden`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_markedForDeletionAtMs` ON `episodes` (`markedForDeletionAtMs`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN skipAutoDownload INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN isLocal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN chaptersJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE episodes ADD COLUMN isTranscoded INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN isSubscribed INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): PurePodcastDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PurePodcastDatabase::class.java,
                    "purepodcast.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
