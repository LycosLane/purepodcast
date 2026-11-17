package com.lycoslane.purepodcast.domain.repository

import android.content.Context
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PodcastWithStats
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.download.EpisodeDownloadWorker
import com.lycoslane.purepodcast.core.network.ItunesSearchClient
import com.lycoslane.purepodcast.core.network.NetworkSecurity
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
        val trimmed = feedUrl.trim()
        require(NetworkSecurity.isValidHttpUrl(trimmed)) { "Invalid feed URL" }
        val existing = dao.getPodcastByFeedUrl(trimmed)
        if (existing != null) {
            refreshPodcast(existing.id)
            return existing.id
        }

        val parsed = RssFeedParser.fetchAndParse(trimmed)
        val existingParsed = dao.getPodcastByFeedUrl(parsed.feedUrl)
        if (existingParsed != null) {
            refreshPodcast(existingParsed.id)
            return existingParsed.id
        }

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
        val resolvedId = if (podcastId > 0) {
            podcastId
        } else {
            dao.getPodcastByFeedUrl(parsed.feedUrl)?.id
                ?: dao.getPodcastByFeedUrl(trimmed)?.id
                ?: error("Failed to insert podcast")
        }
        insertParsedEpisodes(resolvedId, parsed.episodes)
        return resolvedId
    }

    suspend fun importOpml(input: InputStream): Int = withContext(Dispatchers.IO) {
        val entries = OpmlParser.parse(input)
        var imported = 0
        for (entry in entries) {
            try {
                subscribeByFeedUrl(entry.feedUrl)
                imported++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        imported
    }

    suspend fun refreshPodcast(podcastId: Long) {
        val podcast = dao.getPodcast(podcastId) ?: return
        val parsed = RssFeedParser.fetchAndParse(podcast.feedUrl)
        insertParsedEpisodes(podcastId, parsed.episodes)
        dao.updatePodcastMetadata(
            podcastId = podcast.id,
            title = parsed.title.ifEmpty { podcast.title },
            author = parsed.author.ifEmpty { podcast.author },
            description = parsed.description.ifEmpty { podcast.description },
            artworkUrl = parsed.artworkUrl.ifEmpty { podcast.artworkUrl },
            lastRefreshedMs = System.currentTimeMillis()
        )
        val latest = dao.getPodcast(podcastId) ?: return
        if (latest.autoDownloadFromPubDateMs > 0L) {
            val missing = dao.getNotDownloadedNewerThan(podcastId, latest.autoDownloadFromPubDateMs)
            val wifiOnly = preferences.settings.value.wifiOnlyDownloads
            missing.forEach { ep ->
                dao.updateDownloadState(ep.id, DownloadState.QUEUED, 0, null)
                EpisodeDownloadWorker.enqueue(context, ep.id, wifiOnly = wifiOnly)
            }
        }
    }

    suspend fun refreshAllPodcasts() {
        dao.getAllPodcasts().forEach { podcast ->
            try {
                refreshPodcast(podcast.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    suspend fun unsubscribePodcast(podcastId: Long) = withContext(Dispatchers.IO) {
        dao.getEpisodeIdsForPodcast(podcastId).forEach { epId ->
            EpisodeDownloadWorker.cancel(context, epId)
        }
        File(context.filesDir, "episodes/$podcastId").deleteRecursively()
        dao.deletePodcast(podcastId)
    }

    suspend fun enqueueDownload(episodeId: Long) {
        dao.updateDownloadState(episodeId, DownloadState.QUEUED, 0, null)
        EpisodeDownloadWorker.enqueue(
            context = context,
            episodeId = episodeId,
            wifiOnly = preferences.settings.value.wifiOnlyDownloads
        )
    }

    suspend fun deleteDownload(
        episodeId: Long,
        activeEpisodeId: Long? = null,
        immediate: Boolean = false
    ) = withContext(Dispatchers.IO) {
        EpisodeDownloadWorker.cancel(context, episodeId)
        if (activeEpisodeId != null && episodeId == activeEpisodeId) return@withContext
        val episode = dao.getEpisode(episodeId) ?: return@withContext
        if (immediate || episode.downloadState != DownloadState.DOWNLOADED) {
            episode.localFilePath?.let { path -> safeDeleteEpisodeFile(path) }
            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
        } else {
            dao.markForDeletion(episodeId, System.currentTimeMillis())
        }
    }

    suspend fun restoreFromTrash(episodeId: Long) = withContext(Dispatchers.IO) {
        dao.markForDeletion(episodeId, 0L)
    }

    suspend fun toggleHidden(
        episodeId: Long,
        isHidden: Boolean,
        activeEpisodeId: Long? = null
    ) = withContext(Dispatchers.IO) {
        dao.setHidden(episodeId, isHidden)
        if (isHidden) {
            dao.removeFromQueue(episodeId)
            val ep = dao.getEpisode(episodeId)
            if (ep != null && ep.downloadState == DownloadState.DOWNLOADED &&
                (activeEpisodeId == null || ep.id != activeEpisodeId)
            ) {
                dao.markForDeletion(episodeId, System.currentTimeMillis())
            }
        } else {
            val ep = dao.getEpisode(episodeId)
            if (ep != null && ep.markedForDeletionAtMs > 0L) {
                dao.markForDeletion(episodeId, 0L)
            }
        }
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
     * Marks local audio files for deletion (24h trash) and marks all episodes older than [beforePubDateMs] as played.
     */
    suspend fun cleanupOlderThan(
        podcastId: Long,
        beforePubDateMs: Long,
        activeEpisodeId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val olderDownloaded = dao.getDownloadedOlderThan(podcastId, beforePubDateMs)
        olderDownloaded.forEach { ep ->
            if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                EpisodeDownloadWorker.cancel(context, ep.id)
                if (ep.downloadState == DownloadState.DOWNLOADED) {
                    dao.markForDeletion(ep.id, now)
                } else {
                    ep.localFilePath?.let { safeDeleteEpisodeFile(it) }
                    dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
                }
            }
        }
        dao.markOlderAsPlayed(podcastId, beforePubDateMs, now)
    }

    suspend fun togglePlayed(
        episodeId: Long,
        isPlayed: Boolean,
        activeEpisodeId: Long? = null
    ) {
        val playedAt = if (isPlayed) System.currentTimeMillis() else 0L
        dao.setPlayed(episodeId, isPlayed, playedAt)
        if (isPlayed && preferences.settings.value.autoRemovePlayedDays == 0) {
            deleteDownload(episodeId, activeEpisodeId, immediate = false)
        }
    }

    suspend fun cleanupPlayedDownloads(activeEpisodeId: Long? = null) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val trashCutoffMs = now - 86_400_000L // 24h grace period
        dao.getTrashedDownloadsOlderThan(trashCutoffMs).forEach { ep ->
            if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                ep.localFilePath?.let { path -> safeDeleteEpisodeFile(path) }
                dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
            }
        }

        val days = preferences.settings.value.autoRemovePlayedDays
        if (days >= 0) {
            val targets = if (days == 0) {
                dao.getAllPlayedDownloads()
            } else {
                val cutoffMs = now - days * 86_400_000L
                dao.getPlayedDownloadsOlderThan(cutoffMs)
            }
            targets.forEach { ep ->
                if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                    dao.markForDeletion(ep.id, now)
                }
            }
        }

        val maxDownloads = preferences.settings.value.maxDownloadsPerPodcast
        if (maxDownloads > 0) {
            dao.getAllPodcasts().forEach { pod ->
                val activeList = dao.getActiveDownloadsForPodcastNotInQueue(pod.id)
                if (activeList.size > maxDownloads) {
                    activeList.drop(maxDownloads).forEach { ep ->
                        if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                            dao.markForDeletion(ep.id, now)
                        }
                    }
                }
            }
        }
    }

    private fun safeDeleteEpisodeFile(path: String) {
        runCatching {
            val episodesRoot = File(context.filesDir, "episodes").canonicalFile
            val target = File(path).canonicalFile
            if (target.path.startsWith(episodesRoot.path + File.separator)) {
                target.delete()
            }
        }
    }

    suspend fun toggleSortAscending(podcastId: Long, ascending: Boolean) {
        dao.setSortAscending(podcastId, ascending)
    }

    suspend fun updatePodcastAudioPrefs(podcastId: Long, speed: Float, skipSilence: Boolean) {
        dao.setPlaybackSpeed(podcastId, speed.coerceIn(0.75f, 3.0f))
        dao.setSkipSilence(podcastId, skipSilence)
    }

    suspend fun addToQueue(episodeId: Long) {
        dao.enqueueToQueue(episodeId)
        if (preferences.settings.value.downloadWhenQueued) {
            val ep = dao.getEpisode(episodeId)
            if (ep != null && ep.downloadState == DownloadState.NONE) {
                enqueueDownload(episodeId)
            }
        }
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
                    put("downloadWhenQueued", settings.downloadWhenQueued)
                    put("addToQueueWhenDownloaded", settings.addToQueueWhenDownloaded)
                    put("autoRemovePlayedDays", settings.autoRemovePlayedDays)
                    put("maxDownloadsPerPodcast", settings.maxDownloadsPerPodcast)
                    put("smartRewindSeconds", settings.smartRewindSeconds)
                    put("audioNormalizationEnabled", settings.audioNormalizationEnabled)
                    put("bluetoothNextPrevSeeks", settings.bluetoothNextPrevSeeks)
                    put("appLanguage", settings.appLanguage)
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
                            put("isHidden", ep.isHidden)
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
                        put("silenceIntensity", pod.silenceIntensity)
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
        val version = root.optInt("version", 1)
        if (version < 1 || version > 1) error("Unsupported backup version")

        root.optJSONObject("settings")?.let { s ->
            if (s.has("refreshIntervalHours")) {
                val hours = s.getInt("refreshIntervalHours").let { if (it in listOf(0, 1, 3, 6, 12, 24)) it else 6 }
                preferences.setRefreshIntervalHours(hours)
            }
            if (s.has("wifiOnlyDownloads")) preferences.setWifiOnlyDownloads(s.getBoolean("wifiOnlyDownloads"))
            if (s.has("autoDownloadNewByDefault")) preferences.setAutoDownloadNewByDefault(s.getBoolean("autoDownloadNewByDefault"))
            if (s.has("downloadWhenQueued")) preferences.setDownloadWhenQueued(s.getBoolean("downloadWhenQueued"))
            if (s.has("addToQueueWhenDownloaded")) preferences.setAddToQueueWhenDownloaded(s.getBoolean("addToQueueWhenDownloaded"))
            if (s.has("autoRemovePlayedDays")) {
                val days = s.getInt("autoRemovePlayedDays").let { if (it in listOf(-1, 0, 1, 3, 7)) it else 1 }
                preferences.setAutoRemovePlayedDays(days)
            }
            if (s.has("maxDownloadsPerPodcast")) {
                preferences.setMaxDownloadsPerPodcast(s.getInt("maxDownloadsPerPodcast").coerceIn(0, 30))
            }
            if (s.has("smartRewindSeconds")) {
                preferences.setSmartRewindSeconds(s.getInt("smartRewindSeconds").coerceIn(0, 60))
            }
            if (s.has("audioNormalizationEnabled")) {
                preferences.setAudioNormalizationEnabled(s.getBoolean("audioNormalizationEnabled"))
            }
            if (s.has("bluetoothNextPrevSeeks")) {
                preferences.setBluetoothNextPrevSeeks(s.getBoolean("bluetoothNextPrevSeeks"))
            }
            if (s.has("appLanguage")) preferences.setAppLanguage(s.getString("appLanguage"))
        }

        val podcastsArr = root.optJSONArray("podcasts") ?: return@withContext 0
        var restored = 0
        val maxPodcasts = minOf(podcastsArr.length(), 500)
        for (i in 0 until maxPodcasts) {
            val pObj = podcastsArr.optJSONObject(i) ?: continue
            val feedUrl = pObj.optString("feedUrl", "").trim()
            if (!NetworkSecurity.isValidHttpUrl(feedUrl)) continue
            try {
                val podcastId = subscribeByFeedUrl(feedUrl)
                val existing = dao.getPodcast(podcastId)
                if (existing != null) {
                    val rawSpeed = pObj.optDouble("playbackSpeed", existing.playbackSpeed.toDouble())
                    val safeSpeed = if (rawSpeed.isFinite()) rawSpeed.toFloat().coerceIn(0.75f, 3.0f) else 1.0f
                    dao.updatePodcast(
                        existing.copy(
                            autoDownloadFromPubDateMs = pObj.optLong("autoDownloadFromPubDateMs", existing.autoDownloadFromPubDateMs).coerceAtLeast(0L),
                            sortAscending = pObj.optBoolean("sortAscending", existing.sortAscending),
                            playbackSpeed = safeSpeed,
                            skipSilence = pObj.optBoolean("skipSilence", existing.skipSilence),
                            silenceIntensity = pObj.optInt("silenceIntensity", existing.silenceIntensity).coerceIn(0, 2)
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
                        dao.updatePosition(ep.id, eObj.optLong("positionMs", ep.positionMs).coerceAtLeast(0L))
                        val restoredPlayed = eObj.optBoolean("isPlayed", ep.isPlayed)
                        val restoredPlayedAt = if (restoredPlayed) ep.playedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis() else 0L
                        dao.setPlayed(ep.id, restoredPlayed, restoredPlayedAt)
                        if (eObj.has("isHidden")) {
                            dao.setHidden(ep.id, eObj.optBoolean("isHidden", ep.isHidden))
                        }
                    }
                }
                restored++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
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
