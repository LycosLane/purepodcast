package com.lycoslane.purepodcast.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class PodcastWithStats(
    @Embedded val podcast: PodcastEntity,
    val unplayedCount: Int,
    val downloadedCount: Int
)

data class EpisodeWithPodcast(
    @Embedded val episode: EpisodeEntity,
    @Relation(
        parentColumn = "podcastId",
        entityColumn = "id"
    )
    val podcast: PodcastEntity
)

@Dao
interface PurePodcastDao {

    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.isPlayed = 0 AND e.isHidden = 0) AS unplayedCount,
               (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.downloadState = 'DOWNLOADED' AND e.markedForDeletionAtMs = 0) AS downloadedCount
        FROM podcasts p
        WHERE p.isSubscribed = 1 OR p.isLocal = 1
        ORDER BY p.title COLLATE NOCASE ASC
        """
    )
    fun observePodcastsWithStats(): Flow<List<PodcastWithStats>>

    @Query("SELECT * FROM podcasts")
    suspend fun getAllPodcasts(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE id = :podcastId")
    fun observePodcast(podcastId: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :podcastId")
    suspend fun getPodcast(podcastId: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun getPodcastByFeedUrl(feedUrl: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcast(podcast: PodcastEntity): Long

    @Update
    suspend fun updatePodcast(podcast: PodcastEntity)

    @Query(
        """
        UPDATE podcasts
        SET title = :title, author = :author, description = :description, artworkUrl = :artworkUrl, lastRefreshedMs = :lastRefreshedMs
        WHERE id = :podcastId
        """
    )
    suspend fun updatePodcastMetadata(
        podcastId: Long,
        title: String,
        author: String,
        description: String,
        artworkUrl: String,
        lastRefreshedMs: Long
    )

    @Query("DELETE FROM podcasts WHERE id = :podcastId")
    suspend fun deletePodcast(podcastId: Long)

    @Query("UPDATE podcasts SET autoDownloadFromPubDateMs = :fromPubDateMs WHERE id = :podcastId")
    suspend fun setAutoDownloadFromDate(podcastId: Long, fromPubDateMs: Long)

    @Query("UPDATE podcasts SET sortAscending = :ascending WHERE id = :podcastId")
    suspend fun setSortAscending(podcastId: Long, ascending: Boolean)

    @Query("UPDATE podcasts SET playbackSpeed = :speed WHERE id = :podcastId")
    suspend fun setPlaybackSpeed(podcastId: Long, speed: Float)

    @Query("UPDATE podcasts SET skipSilence = :skipSilence WHERE id = :podcastId")
    suspend fun setSkipSilence(podcastId: Long, skipSilence: Boolean)

    @Query("UPDATE podcasts SET silenceIntensity = :intensity WHERE id = :podcastId")
    suspend fun setSilenceIntensity(podcastId: Long, intensity: Int)

    @Query("UPDATE podcasts SET isSubscribed = :isSubscribed WHERE id = :podcastId")
    suspend fun setPodcastSubscribed(podcastId: Long, isSubscribed: Boolean)

    // --- Episodes ---

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs DESC")
    fun observeEpisodesDesc(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs ASC")
    fun observeEpisodesAsc(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT id FROM episodes WHERE podcastId = :podcastId")
    suspend fun getEpisodeIdsForPodcast(podcastId: Long): List<Long>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND (isPlayed = 1 OR positionMs > 0 OR isHidden = 1 OR isFavorite = 1)")
    suspend fun getEpisodesWithProgress(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND guid = :guid LIMIT 1")
    suspend fun getEpisodeByGuid(podcastId: Long, guid: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND audioUrl = :audioUrl LIMIT 1")
    suspend fun getEpisodeByAudioUrl(podcastId: Long, audioUrl: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisode(episodeId: Long): EpisodeEntity?

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeWithPodcast(episodeId: Long): EpisodeWithPodcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long>

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE id = :episodeId")
    suspend fun updatePosition(episodeId: Long, positionMs: Long)

    @Query("UPDATE episodes SET isPlayed = :isPlayed, playedAtMs = :playedAtMs WHERE id = :episodeId")
    suspend fun setPlayed(episodeId: Long, isPlayed: Boolean, playedAtMs: Long)

    @Query("UPDATE episodes SET isHidden = :isHidden WHERE id = :episodeId")
    suspend fun setHidden(episodeId: Long, isHidden: Boolean)

    @Query("UPDATE episodes SET isFavorite = :isFavorite WHERE id = :episodeId")
    suspend fun setFavorite(episodeId: Long, isFavorite: Boolean)

    @Transaction
    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE (p.isSubscribed = 1 OR p.isLocal = 1) AND e.isHidden = 0
        ORDER BY e.pubDateMs DESC
        """
    )
    fun observeAllEpisodesWithPodcast(): Flow<List<EpisodeWithPodcast>>

    @Query(
        """
        UPDATE episodes
        SET markedForDeletionAtMs = :markedAtMs,
            skipAutoDownload = CASE WHEN :markedAtMs > 0 THEN 1 ELSE skipAutoDownload END
        WHERE id = :episodeId
        """
    )
    suspend fun markForDeletion(episodeId: Long, markedAtMs: Long)

    @Query("UPDATE episodes SET skipAutoDownload = :skip WHERE id = :episodeId")
    suspend fun setSkipAutoDownload(episodeId: Long, skip: Boolean)

    @Query(
        """
        SELECT * FROM episodes
        WHERE markedForDeletionAtMs > 0 AND markedForDeletionAtMs <= :cutoffMs
          AND downloadState = 'DOWNLOADED' AND localFilePath IS NOT NULL
        """
    )
    suspend fun getTrashedDownloadsOlderThan(cutoffMs: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND downloadState = 'DOWNLOADED'
          AND markedForDeletionAtMs = 0 AND localFilePath IS NOT NULL
          AND id NOT IN (SELECT episodeId FROM queue_items)
        ORDER BY pubDateMs DESC, id DESC
        """
    )
    suspend fun getActiveDownloadsForPodcastNotInQueue(podcastId: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE isPlayed = 1 AND downloadState = 'DOWNLOADED' AND localFilePath IS NOT NULL
          AND markedForDeletionAtMs = 0
          AND id NOT IN (SELECT episodeId FROM queue_items)
        """
    )
    suspend fun getAllPlayedDownloads(): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE isPlayed = 1 AND downloadState = 'DOWNLOADED' AND localFilePath IS NOT NULL
          AND markedForDeletionAtMs = 0
          AND playedAtMs > 0 AND playedAtMs <= :cutoffMs
          AND id NOT IN (SELECT episodeId FROM queue_items)
        """
    )
    suspend fun getPlayedDownloadsOlderThan(cutoffMs: Long): List<EpisodeEntity>

    @Query(
        """
        UPDATE episodes
        SET downloadState = :state, downloadProgress = :progress, localFilePath = :localPath, markedForDeletionAtMs = 0
        WHERE id = :episodeId
        """
    )
    suspend fun updateDownloadState(
        episodeId: Long,
        state: DownloadState,
        progress: Int,
        localPath: String?
    )

    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND pubDateMs >= :fromPubDateMs
          AND downloadState = 'NONE' AND isHidden = 0 AND skipAutoDownload = 0
        """
    )
    suspend fun getNotDownloadedNewerThan(podcastId: Long, fromPubDateMs: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND pubDateMs < :beforePubDateMs
          AND markedForDeletionAtMs = 0
          AND downloadState IN ('QUEUED', 'DOWNLOADING', 'DOWNLOADED')
          AND id NOT IN (SELECT episodeId FROM queue_items)
        """
    )
    suspend fun getDownloadedOlderThan(podcastId: Long, beforePubDateMs: Long): List<EpisodeEntity>

    @Query(
        """
        UPDATE episodes
        SET isPlayed = 1, playedAtMs = :playedAtMs
        WHERE podcastId = :podcastId AND pubDateMs < :beforePubDateMs AND isPlayed = 0
        """
    )
    suspend fun markOlderAsPlayed(podcastId: Long, beforePubDateMs: Long, playedAtMs: Long)

    // --- Queue ---

    @Transaction
    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue_items q ON q.episodeId = e.id
        ORDER BY q.sortOrder ASC
        """
    )
    fun observeQueueEpisodes(): Flow<List<EpisodeWithPodcast>>

    @Transaction
    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue_items q ON q.episodeId = e.id
        ORDER BY q.sortOrder ASC
        """
    )
    suspend fun getQueueEpisodesOnce(): List<EpisodeWithPodcast>

    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND markedForDeletionAtMs > 0
          AND downloadState = 'DOWNLOADED'
        """
    )
    suspend fun getTrashedDownloadsForPodcast(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM queue_items")
    suspend fun getMinQueueOrder(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM queue_items")
    suspend fun getMaxQueueOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueueItem(item: QueueItemEntity): Long

    @Transaction
    suspend fun enqueueToQueue(episodeId: Long) {
        removeFromQueue(episodeId)
        val nextOrder = getMaxQueueOrder() + 1
        insertQueueItem(QueueItemEntity(episodeId = episodeId, sortOrder = nextOrder))
    }

    @Transaction
    suspend fun enqueueToQueueFront(episodeId: Long) {
        removeFromQueue(episodeId)
        val firstOrder = getMinQueueOrder() - 1
        insertQueueItem(QueueItemEntity(episodeId = episodeId, sortOrder = firstOrder))
    }

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun removeFromQueue(episodeId: Long)

    @Query("SELECT COUNT(*) FROM queue_items WHERE episodeId = :episodeId")
    suspend fun queueCount(episodeId: Long): Int

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()

    @Query(
        """
        SELECT * FROM episodes
        WHERE downloadState = 'DOWNLOADED' AND localFilePath IS NOT NULL
          AND isTranscoded = 0 AND markedForDeletionAtMs = 0
        """
    )
    suspend fun getUntranscodedDownloads(): List<EpisodeEntity>

    @Query(
        """
        UPDATE episodes
        SET localFilePath = :newPath, fileSizeBytes = :newSizeBytes, isTranscoded = 1
        WHERE id = :episodeId
        """
    )
    suspend fun markEpisodeTranscoded(episodeId: Long, newPath: String, newSizeBytes: Long)

    @Query("SELECT * FROM podcasts WHERE isLocal = 1")
    suspend fun getLocalPodcasts(): List<PodcastEntity>
}
