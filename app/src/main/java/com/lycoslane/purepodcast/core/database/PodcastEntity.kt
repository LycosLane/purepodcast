package com.lycoslane.purepodcast.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)]
)
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String = "",
    /**
     * When set (> 0), any episode with pubDateMs >= autoDownloadFromPubDateMs
     * will be automatically downloaded on feed refresh.
     */
    val autoDownloadFromPubDateMs: Long = 0L,
    val sortAscending: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val skipSilence: Boolean = false,
    val silenceIntensity: Int = 1, // 0 = Soft, 1 = Medium, 2 = Strong
    val lastRefreshedMs: Long = 0L,
    val isLocal: Boolean = false,
    val isSubscribed: Boolean = true
)
