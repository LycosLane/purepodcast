package com.lycoslane.purepodcast.domain.repository

import android.content.Context
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PodcastWithStats
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.database.QueueItemEntity
import com.lycoslane.purepodcast.core.download.EpisodeDownloadWorker
import com.lycoslane.purepodcast.core.network.ItunesSearchClient
import com.lycoslane.purepodcast.core.network.OpmlParser
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.core.network.RssFeedParser
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class PodcastRepository(
    private val context: Context,
    private val preferences: AppPreferences
) {

    private val dao = PurePodcastDatabase.getInstance(context).dao()

    fun observePodcasts(): Flow<List<PodcastWithStats>> = dao.observePodcastsWithStats()

    fun observePodcast(podcastId: Long): Flow<PodcastEntity?> = dao.observePodcast(podcastId)

    fun observeEpisodes(podcastId: Long, ascending: Boolean): Flow<List<EpisodeEntity>> =
        if (ascending) dao.observeEpisodesAsc(podcastId) else dao.observeEpisodesDesc(podcastId)

    fun observeQueue(): Flow<List<EpisodeWithPodcast>> = dao.observeQueueEpisodes()

    suspend fun searchPodcasts(query: String): List<PodcastSearchResult> =
        ItunesSearchClient.search(query)

    suspend fun subscribeByFeedUrl(feedUrl: String): Long {
        val existing = dao.getPodcastByFeedUrl(feedUrl)
        if (existing != null) {
            refreshPodcast(existing.id)
            return existing.id
        }

        val parsed = RssFeedParser.fetchAndParse(feedUrl)
        val autoFrom = if (preferences.settings.value.autoDownloadNewByDefault) {
            System.currentTimeMillis()
        } else {
            0L
        }
        val podcastId = dao.insertPodcast(
            PodcastEntity(
                feedUrl = parsed.feedUrl,
                title = parsed.title,
                author = parsed.author,
                description = parsed.description,
                artworkUrl = parsed.artworkUrl,
                autoDownloadFromPubDateMs = autoFrom,
                lastRefreshedMs = System.currentTimeMillis()
            )
        )
        val resolvedId = if (podcastId > 0) podcastId else dao.getPodcastByFeedUrl(feedUrl)!!.id
        insertParsedEpisodes(resolvedId, parsed.episodes)
        return resolvedId
    }

    suspend fun importOpml(input: InputStream): Int = withContext(Dispatchers.IO) {
        val entries = OpmlParser.parse(input)
        var imported = 0
        for (entry in entries) {
            runCatching {
                subscribeByFeedUrl(entry.feedUrl)
                imported++
            }
        }
        imported
    }

    suspend fun refreshPodcast(podcastId: Long) {
        val podcast = dao.getPodcast(podcastId) ?: return
        val parsed = RssFeedParser.fetchAndParse(podcast.feedUrl)
        insertParsedEpisodes(podcastId, parsed.episodes)
        dao.updatePodcast(
            podcast.copy(
                title = parsed.title.ifEmpty { podcast.title },
                author = parsed.author.ifEmpty { podcast.author },
                description = parsed.description.ifEmpty { podcast.description },
                artworkUrl = parsed.artworkUrl.ifEmpty { podcast.artworkUrl },
                lastRefreshedMs = System.currentTimeMillis()
            )
        )
        if (podcast.autoDownloadFromPubDateMs > 0L) {
            val missing = dao.getNotDownloadedNewerThan(podcastId, podcast.autoDownloadFromPubDateMs)
            val wifiOnly = preferences.settings.value.wifiOnlyDownloads
            missing.forEach { ep ->
                dao.updateDownloadState(ep.id, DownloadState.QUEUED, 0, null)
                EpisodeDownloadWorker.enqueue(context, ep.id, wifiOnly = wifiOnly)
            }
        }
    }

    suspend fun refreshAllPodcasts() {
        dao.getAllPodcasts().forEach { podcast ->
            runCatching { refreshPodcast(podcast.id) }
        }
    }

    suspend fun unsubscribePodcast(podcastId: Long) = withContext(Dispatchers.IO) {
        File(context.filesDir, "episodes/$podcastId").deleteRecursively()
        dao.deletePodcast(podcastId)
    }

    suspend fun enqueueDownload(episodeId: Long) {
        dao.updateDownloadState(episodeId, DownloadState.QUEUED, 0, null)
        EpisodeDownloadWorker.enqueue(context, episodeId)
    }

    suspend fun deleteDownload(episodeId: Long) = withContext(Dispatchers.IO) {
        val episode = dao.getEpisode(episodeId) ?: return@withContext
        episode.localFilePath?.let { path -> File(path).delete() }
        dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
    }

    /**
     * Enables auto-download starting from [fromPubDateMs] and immediately queues
     * all episodes >= [fromPubDateMs] that are not yet downloaded.
     */
    suspend fun downloadNewerFromEpisode(podcastId: Long, fromPubDateMs: Long) {
        dao.setAutoDownloadFromDate(podcastId, fromPubDateMs)
        val missing = dao.getNotDownloadedNewerThan(podcastId, fromPubDateMs)
        missing.forEach { enqueueDownload(it.id) }
    }

    suspend fun disableAutoDownload(podcastId: Long) {
        dao.setAutoDownloadFromDate(podcastId, 0L)
    }

    /**
     * Deletes local audio files and marks all episodes older than [beforePubDateMs] as played.
     */
    suspend fun cleanupOlderThan(podcastId: Long, beforePubDateMs: Long) = withContext(Dispatchers.IO) {
        val olderDownloaded = dao.getDownloadedOlderThan(podcastId, beforePubDateMs)
        olderDownloaded.forEach { ep ->
            ep.localFilePath?.let { File(it).delete() }
            dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
        }
        dao.markOlderAsPlayed(podcastId, beforePubDateMs)
    }

    suspend fun togglePlayed(episodeId: Long, isPlayed: Boolean) {
        dao.setPlayed(episodeId, isPlayed)
    }

    suspend fun toggleSortAscending(podcastId: Long, ascending: Boolean) {
        dao.setSortAscending(podcastId, ascending)
    }

    suspend fun updatePodcastAudioPrefs(podcastId: Long, speed: Float, skipSilence: Boolean) {
        dao.setPlaybackSpeed(podcastId, speed)
        dao.setSkipSilence(podcastId, skipSilence)
    }

    suspend fun addToQueue(episodeId: Long) {
        val nextOrder = dao.getMaxQueueOrder() + 1
        dao.insertQueueItem(QueueItemEntity(episodeId = episodeId, sortOrder = nextOrder))
    }

    suspend fun removeFromQueue(episodeId: Long) {
        dao.removeFromQueue(episodeId)
    }

    suspend fun clearQueue() {
        dao.clearQueue()
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val settings = preferences.settings.value
        val root = org.json.JSONObject().apply {
            put("version", 1)
            put("exportedAtMs", System.currentTimeMillis())
            put(
                "settings",
                org.json.JSONObject().apply {
                    put("refreshIntervalHours", settings.refreshIntervalHours)
                    put("wifiOnlyDownloads", settings.wifiOnlyDownloads)
                    put("autoDownloadNewByDefault", settings.autoDownloadNewByDefault)
                    put("smartRewindSeconds", settings.smartRewindSeconds)
                }
            )
            val podcastsArr = org.json.JSONArray()
            dao.getAllPodcasts().forEach { pod ->
                val epArr = org.json.JSONArray()
                dao.getEpisodesWithProgress(pod.id).forEach { ep ->
                    epArr.put(
                        org.json.JSONObject().apply {
                            put("guid", ep.guid)
                            put("positionMs", ep.positionMs)
                            put("isPlayed", ep.isPlayed)
                        }
                    )
                }
                podcastsArr.put(
                    org.json.JSONObject().apply {
                        put("feedUrl", pod.feedUrl)
                        put("autoDownloadFromPubDateMs", pod.autoDownloadFromPubDateMs)
                        put("sortAscending", pod.sortAscending)
                        put("playbackSpeed", pod.playbackSpeed.toDouble())
                        put("skipSilence", pod.skipSilence)
                        put("episodes", epArr)
                    }
                )
            }
            put("podcasts", podcastsArr)
        }
        root.toString(2)
    }

    suspend fun importBackupJson(jsonText: String): Int = withContext(Dispatchers.IO) {
        val root = org.json.JSONObject(jsonText)
        root.optJSONObject("settings")?.let { s ->
            if (s.has("refreshIntervalHours")) preferences.setRefreshIntervalHours(s.getInt("refreshIntervalHours"))
            if (s.has("wifiOnlyDownloads")) preferences.setWifiOnlyDownloads(s.getBoolean("wifiOnlyDownloads"))
            if (s.has("autoDownloadNewByDefault")) preferences.setAutoDownloadNewByDefault(s.getBoolean("autoDownloadNewByDefault"))
            if (s.has("smartRewindSeconds")) preferences.setSmartRewindSeconds(s.getInt("smartRewindSeconds"))
        }

        val podcastsArr = root.optJSONArray("podcasts") ?: return@withContext 0
        var restored = 0
        for (i in 0 until podcastsArr.length()) {
            val pObj = podcastsArr.optJSONObject(i) ?: continue
            val feedUrl = pObj.optString("feedUrl", "").trim()
            if (feedUrl.isEmpty()) continue
            runCatching {
                val podcastId = subscribeByFeedUrl(feedUrl)
                val existing = dao.getPodcast(podcastId)
                if (existing != null) {
                    dao.updatePodcast(
                        existing.copy(
                            autoDownloadFromPubDateMs = pObj.optLong("autoDownloadFromPubDateMs", existing.autoDownloadFromPubDateMs),
                            sortAscending = pObj.optBoolean("sortAscending", existing.sortAscending),
                            playbackSpeed = pObj.optDouble("playbackSpeed", existing.playbackSpeed.toDouble()).toFloat(),
                            skipSilence = pObj.optBoolean("skipSilence", existing.skipSilence)
                        )
                    )
                }
                val epArr = pObj.optJSONArray("episodes")
                if (epArr != null) {
                    for (j in 0 until epArr.length()) {
                        val eObj = epArr.optJSONObject(j) ?: continue
                        val guid = eObj.optString("guid", "")
                        if (guid.isEmpty()) continue
                        val ep = dao.getEpisodeByGuid(podcastId, guid) ?: continue
                        dao.updatePosition(ep.id, eObj.optLong("positionMs", ep.positionMs))
                        dao.setPlayed(ep.id, eObj.optBoolean("isPlayed", ep.isPlayed))
                    }
                }
                restored++
            }
        }
        restored
    }

    private suspend fun insertParsedEpisodes(
        podcastId: Long,
        episodes: List<com.lycoslane.purepodcast.core.network.ParsedEpisode>
    ) {
        val entities = episodes.map { ep ->
            EpisodeEntity(
                podcastId = podcastId,
                guid = ep.guid,
                title = ep.title,
                descriptionHtml = ep.descriptionHtml,
                audioUrl = ep.audioUrl,
                artworkUrl = ep.artworkUrl,
                pubDateMs = ep.pubDateMs,
                durationMs = ep.durationMs,
                fileSizeBytes = ep.fileSizeBytes
            )
        }
        dao.insertEpisodes(entities)
    }
}
