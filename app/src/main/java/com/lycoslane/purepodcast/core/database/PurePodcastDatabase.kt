package com.lycoslane.purepodcast.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

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
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PurePodcastDatabase : RoomDatabase() {
    abstract fun dao(): PurePodcastDao

    companion object {
        @Volatile
        private var INSTANCE: PurePodcastDatabase? = null

        fun getInstance(context: Context): PurePodcastDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PurePodcastDatabase::class.java,
                    "purepodcast.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
