package com.lycoslane.purepodcast.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
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
               (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.isPlayed = 0) AS unplayedCount,
               (SELECT COUNT(*) FROM episodes e WHERE e.podcastId = p.id AND e.downloadState = 'DOWNLOADED') AS downloadedCount
        FROM podcasts p
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

    // --- Episodes ---

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs DESC")
    fun observeEpisodesDesc(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateMs ASC")
    fun observeEpisodesAsc(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND (isPlayed = 1 OR positionMs > 0)")
    suspend fun getEpisodesWithProgress(podcastId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND guid = :guid LIMIT 1")
    suspend fun getEpisodeByGuid(podcastId: Long, guid: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisode(episodeId: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeWithPodcast(episodeId: Long): EpisodeWithPodcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long>

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE id = :episodeId")
    suspend fun updatePosition(episodeId: Long, positionMs: Long)

    @Query("UPDATE episodes SET isPlayed = :isPlayed, playedAtMs = :playedAtMs WHERE id = :episodeId")
    suspend fun setPlayed(episodeId: Long, isPlayed: Boolean, playedAtMs: Long)

    @Query("SELECT * FROM episodes WHERE isPlayed = 1 AND localFilePath IS NOT NULL")
    suspend fun getAllPlayedDownloads(): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE isPlayed = 1 AND localFilePath IS NOT NULL AND playedAtMs > 0 AND playedAtMs <= :cutoffMs
        """
    )
    suspend fun getPlayedDownloadsOlderThan(cutoffMs: Long): List<EpisodeEntity>

    @Query(
        """
        UPDATE episodes
        SET downloadState = :state, downloadProgress = :progress, localFilePath = :localPath
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
        WHERE podcastId = :podcastId AND pubDateMs >= :fromPubDateMs AND downloadState = 'NONE'
        """
    )
    suspend fun getNotDownloadedNewerThan(podcastId: Long, fromPubDateMs: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND pubDateMs < :beforePubDateMs AND localFilePath IS NOT NULL
        """
    )
    suspend fun getDownloadedOlderThan(podcastId: Long, beforePubDateMs: Long): List<EpisodeEntity>

    @Query(
        """
        UPDATE episodes
        SET isPlayed = 1
        WHERE podcastId = :podcastId AND pubDateMs < :beforePubDateMs
        """
    )
    suspend fun markOlderAsPlayed(podcastId: Long, beforePubDateMs: Long)

    // --- Queue ---

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue_items q ON q.episodeId = e.id
        ORDER BY q.sortOrder ASC
        """
    )
    fun observeQueueEpisodes(): Flow<List<EpisodeWithPodcast>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM queue_items")
    suspend fun getMaxQueueOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueueItem(item: QueueItemEntity): Long

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun removeFromQueue(episodeId: Long)

    @Query("DELETE FROM queue_items")
    suspend fun clearQueue()
}
