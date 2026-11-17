package com.lycoslane.purepodcast.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState {
    NONE,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["podcastId"]),
        Index(value = ["podcastId", "guid"], unique = true)
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val descriptionHtml: String = "",
    val audioUrl: String,
    val artworkUrl: String = "",
    val pubDateMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val playedAtMs: Long = 0L,
    val localFilePath: String? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Int = 0,
    val fileSizeBytes: Long = 0L
)
