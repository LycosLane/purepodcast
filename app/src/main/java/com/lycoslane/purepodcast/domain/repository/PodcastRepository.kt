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
import com.lycoslane.purepodcast.core.network.ParsedEpisode
import com.lycoslane.purepodcast.core.network.ParsedPodcastFeed
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.core.network.RssFeedParser
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class PodcastRepository(
    private val context: Context,
    private val preferences: AppPreferences
) {

    private val dao = PurePodcastDatabase.getInstance(context).dao()
    private val cleanupMutex = Mutex()

    fun observePodcasts(): Flow<List<PodcastWithStats>> = dao.observePodcastsWithStats()

    fun observePodcast(podcastId: Long): Flow<PodcastEntity?> = dao.observePodcast(podcastId)

    fun observeEpisodes(podcastId: Long, ascending: Boolean): Flow<List<EpisodeEntity>> =
        if (ascending) dao.observeEpisodesAsc(podcastId) else dao.observeEpisodesDesc(podcastId)

    fun observeQueue(): Flow<List<EpisodeWithPodcast>> = dao.observeQueueEpisodes()

    fun observeAllEpisodes(): Flow<List<EpisodeWithPodcast>> = dao.observeAllEpisodesWithPodcast()

    suspend fun setFavorite(episodeId: Long, isFavorite: Boolean) = dao.setFavorite(episodeId, isFavorite)

    suspend fun searchPodcasts(query: String): List<PodcastSearchResult> =
        ItunesSearchClient.search(query)

    suspend fun subscribeByFeedUrl(feedUrl: String): Long {
        val trimmed = feedUrl.trim()
        require(NetworkSecurity.isValidHttpUrl(trimmed)) { "Invalid feed URL" }
        val existing = dao.getPodcastByFeedUrl(trimmed)
        if (existing != null) {
            if (!existing.isSubscribed) {
                dao.setPodcastSubscribed(existing.id, true)
                if (preferences.settings.value.autoDownloadNewByDefault && existing.autoDownloadFromPubDateMs == 0L) {
                    dao.setAutoDownloadFromDate(existing.id, System.currentTimeMillis())
                }
            }
            refreshPodcast(existing.id)
            return existing.id
        }

        val parsed = RssFeedParser.fetchAndParse(trimmed)
        val existingParsed = dao.getPodcastByFeedUrl(parsed.feedUrl)
        if (existingParsed != null) {
            if (!existingParsed.isSubscribed) {
                dao.setPodcastSubscribed(existingParsed.id, true)
                if (preferences.settings.value.autoDownloadNewByDefault && existingParsed.autoDownloadFromPubDateMs == 0L) {
                    dao.setAutoDownloadFromDate(existingParsed.id, System.currentTimeMillis())
                }
            }
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
                lastRefreshedMs = System.currentTimeMillis(),
                isSubscribed = true
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

    suspend fun subscribePodcast(podcastId: Long) {
        val pod = dao.getPodcast(podcastId) ?: return
        dao.setPodcastSubscribed(podcastId, true)
        if (preferences.settings.value.autoDownloadNewByDefault && pod.autoDownloadFromPubDateMs == 0L) {
            dao.setAutoDownloadFromDate(podcastId, System.currentTimeMillis())
        }
    }

    suspend fun ensurePodcastFromPreviewAndAct(
        feed: ParsedPodcastFeed,
        episode: ParsedEpisode?,
        addToQueueFront: Boolean? = null,
        download: Boolean = false
    ): Pair<Long, Long?> {
        val existing = dao.getPodcastByFeedUrl(feed.feedUrl)
        val podcastId = if (existing != null) {
            existing.id
        } else {
            val inserted = dao.insertPodcast(
                PodcastEntity(
                    feedUrl = feed.feedUrl,
                    title = feed.title,
                    author = feed.author,
                    description = feed.description,
                    artworkUrl = feed.artworkUrl,
                    autoDownloadFromPubDateMs = 0L,
                    lastRefreshedMs = System.currentTimeMillis(),
                    isSubscribed = false
                )
            )
            if (inserted > 0) inserted else dao.getPodcastByFeedUrl(feed.feedUrl)?.id ?: error("Failed to insert podcast")
        }
        insertParsedEpisodes(podcastId, feed.episodes)

        val resolvedEpisode = episode?.let { ep ->
            val guidKey = ep.guid.ifBlank { ep.audioUrl }
            dao.getEpisodeByGuid(podcastId, guidKey) ?: dao.getEpisodeByAudioUrl(podcastId, ep.audioUrl)
        }
        if (resolvedEpisode != null) {
            when (addToQueueFront) {
                true -> addToQueueFront(resolvedEpisode.id)
                false -> addToQueue(resolvedEpisode.id)
                null -> Unit
            }
            if (download) {
                enqueueDownload(resolvedEpisode.id)
            }
        }
        return podcastId to resolvedEpisode?.id
    }

    suspend fun importOpml(input: InputStream): Int = withContext(Dispatchers.IO) {
        val entries = OpmlParser.parse(input)
        val sem = Semaphore(4)
        coroutineScope {
            entries.map { entry ->
                async {
                    sem.withPermit {
                        try {
                            subscribeByFeedUrl(entry.feedUrl)
                            1
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            0
                        }
                    }
                }
            }.awaitAll().sum()
        }
    }

    suspend fun syncLocalFolder(): Int {
        return com.lycoslane.purepodcast.core.local.LocalPodcastScanner.syncLocalFolder(
            context = context,
            dao = dao,
            treeUriString = preferences.settings.value.localFolderUri
        )
    }

    suspend fun refreshPodcast(podcastId: Long) {
        val podcast = dao.getPodcast(podcastId) ?: return
        if (podcast.isLocal) {
            syncLocalFolder()
            return
        }
        val result = RssFeedParser.fetchConditional(podcast.feedUrl, podcast.etag, podcast.lastModified)
        when (result) {
            is com.lycoslane.purepodcast.core.network.FeedFetchResult.NotModified -> {
                dao.updatePodcastMetadata(
                    podcastId = podcast.id,
                    title = podcast.title,
                    author = podcast.author,
                    description = podcast.description,
                    artworkUrl = podcast.artworkUrl,
                    lastRefreshedMs = System.currentTimeMillis()
                )
            }
            is com.lycoslane.purepodcast.core.network.FeedFetchResult.Parsed -> {
                val parsed = result.feed
                insertParsedEpisodes(podcastId, parsed.episodes)
                dao.updatePodcastMetadata(
                    podcastId = podcast.id,
                    title = parsed.title.ifEmpty { podcast.title },
                    author = parsed.author.ifEmpty { podcast.author },
                    description = parsed.description.ifEmpty { podcast.description },
                    artworkUrl = parsed.artworkUrl.ifEmpty { podcast.artworkUrl },
                    lastRefreshedMs = System.currentTimeMillis()
                )
                dao.updateFeedCache(podcast.id, result.etag, result.lastModified)
                // Permanent redirect (301/308): follow the new location from now on.
                result.movedToUrl?.let { newUrl ->
                    runCatching { dao.updateFeedUrl(podcast.id, newUrl) } // unique index may reject duplicates
                }
            }
        }
        val latest = dao.getPodcast(podcastId) ?: return
        if (latest.autoDownloadFromPubDateMs > 0L) {
            val missing = dao.getNotDownloadedNewerThan(podcastId, latest.autoDownloadFromPubDateMs)
                .sortedByDescending { it.pubDateMs }
            val maxDownloads = preferences.settings.value.maxDownloadsPerPodcast
            val toQueue = if (maxDownloads > 0) missing.take(maxDownloads) else missing
            val wifiOnly = preferences.settings.value.wifiOnlyDownloads
            toQueue.forEach { ep ->
                dao.updateDownloadState(ep.id, DownloadState.QUEUED, 0, null)
                EpisodeDownloadWorker.enqueue(context, ep.id, wifiOnly = wifiOnly)
            }
        }
    }

    suspend fun refreshAllPodcasts() {
        try {
            syncLocalFolder()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val subs = dao.getAllPodcasts().filter { !it.isLocal && it.isSubscribed }
        val sem = Semaphore(4)
        coroutineScope {
            subs.map { podcast ->
                async {
                    sem.withPermit {
                        try {
                            refreshPodcast(podcast.id)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun unsubscribePodcast(podcastId: Long) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            dao.getEpisodeIdsForPodcast(podcastId).forEach { epId ->
                EpisodeDownloadWorker.cancel(context, epId)
                com.lycoslane.purepodcast.core.download.TranscodeWorker.cancelForEpisode(context, epId)
            }
            File(context.filesDir, "episodes/$podcastId").deleteRecursively()
            dao.deletePodcast(podcastId)
        }
    }

    /**
     * Startup sweep: resets downloads stuck in QUEUED/DOWNLOADING whose work no longer exists
     * (crash, force-stop, expired work) and removes stale partial files.
     */
    suspend fun sweepOrphanedDownloads() = withContext(Dispatchers.IO) {
        val wm = androidx.work.WorkManager.getInstance(context)
        val stillActive = mutableSetOf<Long>()
        dao.getActiveDownloadEpisodes().forEach { ep ->
            val alive = runCatching {
                wm.getWorkInfosForUniqueWork(EpisodeDownloadWorker.uniqueName(ep.id)).get().any {
                    !it.state.isFinished
                }
            }.getOrDefault(true)
            if (alive) stillActive.add(ep.id) else dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
        }
        val cutoff = System.currentTimeMillis() - 2L * 86_400_000L
        File(context.filesDir, "episodes").listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val isPartial = f.name.endsWith(".tmp") || f.name.endsWith(".etag")
                val id = f.name.substringBefore('.').toLongOrNull()
                if (isPartial && id !in stillActive && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    suspend fun enqueueDownload(episodeId: Long) {
        cleanupMutex.withLock {
            dao.setSkipAutoDownload(episodeId, false)
            dao.updateDownloadState(episodeId, DownloadState.QUEUED, 0, null)
        }
        EpisodeDownloadWorker.enqueue(
            context = context,
            episodeId = episodeId,
            wifiOnly = preferences.settings.value.wifiOnlyDownloads,
            replaceExisting = true
        )
    }

    suspend fun deleteDownload(
        episodeId: Long,
        activeEpisodeId: Long? = null,
        immediate: Boolean = false
    ) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            if (activeEpisodeId != null && episodeId == activeEpisodeId) return@withContext
            val episode = dao.getEpisode(episodeId) ?: return@withContext
            // Local files can only be removed with write permission; otherwise leave untouched.
            if (!canModifyLocalFile(episode)) return@withContext
            EpisodeDownloadWorker.cancel(context, episodeId)
            val deleteNow = immediate ||
                preferences.settings.value.trashRetentionHours == 0 ||
                episode.downloadState != DownloadState.DOWNLOADED
            if (deleteNow) {
                purgeEpisodeFile(episode)
            } else {
                dao.markForDeletion(episodeId, System.currentTimeMillis())
            }
        }
    }

    suspend fun emptyTrash(podcastId: Long, activeEpisodeId: Long? = null) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            dao.getTrashedDownloadsForPodcast(podcastId).forEach { ep ->
                if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                    purgeEpisodeFile(ep)
                }
            }
        }
    }

    suspend fun restoreFromTrash(episodeId: Long) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            dao.markForDeletion(episodeId, 0L)
        }
    }

    suspend fun toggleHidden(
        episodeId: Long,
        isHidden: Boolean,
        activeEpisodeId: Long? = null
    ) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            dao.setHidden(episodeId, isHidden)
            if (isHidden) {
                EpisodeDownloadWorker.cancel(context, episodeId)
                dao.removeFromQueue(episodeId)
                val ep = dao.getEpisode(episodeId)
                if (ep != null && ep.downloadState == DownloadState.DOWNLOADED &&
                    (activeEpisodeId == null || ep.id != activeEpisodeId)
                ) {
                    // Hiding never removes user-owned local files.
                    if (!isLocalPath(ep.localFilePath)) {
                        if (preferences.settings.value.trashRetentionHours == 0) {
                            purgeEpisodeFile(ep)
                        } else {
                            dao.markForDeletion(episodeId, System.currentTimeMillis())
                        }
                    }
                } else if (ep != null && (ep.downloadState == DownloadState.QUEUED || ep.downloadState == DownloadState.DOWNLOADING)) {
                    dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                    dao.setSkipAutoDownload(episodeId, true)
                }
            } else {
                val ep = dao.getEpisode(episodeId)
                if (ep != null && ep.markedForDeletionAtMs > 0L) {
                    dao.markForDeletion(episodeId, 0L)
                }
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
     * Marks local audio files for deletion (or deletes immediately if retention == 0)
     * and marks all episodes older than [beforePubDateMs] as played.
     */
    suspend fun cleanupOlderThan(
        podcastId: Long,
        beforePubDateMs: Long,
        activeEpisodeId: Long? = null
    ) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            val now = System.currentTimeMillis()
            val immediate = preferences.settings.value.trashRetentionHours == 0
            val olderDownloaded = dao.getDownloadedOlderThan(podcastId, beforePubDateMs)
            olderDownloaded.forEach { ep ->
                if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                    // Local user-owned files are never bulk-removed.
                    if (isLocalPath(ep.localFilePath)) return@forEach
                    EpisodeDownloadWorker.cancel(context, ep.id)
                    if (ep.downloadState == DownloadState.DOWNLOADED && !immediate) {
                        dao.markForDeletion(ep.id, now)
                    } else {
                        purgeEpisodeFile(ep)
                    }
                }
            }
            dao.markOlderAsPlayed(podcastId, beforePubDateMs, now)
        }
    }

    suspend fun togglePlayed(
        episodeId: Long,
        isPlayed: Boolean,
        activeEpisodeId: Long? = null
    ) {
        val playedAt = if (isPlayed) System.currentTimeMillis() else 0L
        dao.setPlayed(episodeId, isPlayed, playedAt)
        if (isPlayed && preferences.settings.value.autoRemovePlayedDays == 0) {
            val ep = dao.getEpisode(episodeId)
            if (ep != null && !isLocalPath(ep.localFilePath)) {
                deleteDownload(episodeId, activeEpisodeId, immediate = false)
            }
        }
    }

    suspend fun cleanupPlayedDownloads(
        activeEpisodeId: Long? = null,
        trimToMax: Boolean = true
    ) = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            val now = System.currentTimeMillis()
            val retentionHours = preferences.settings.value.trashRetentionHours
            val trashCutoffMs = now - retentionHours * 3_600_000L
            dao.getTrashedDownloadsOlderThan(trashCutoffMs).forEach { ep ->
                if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                    purgeEpisodeFile(ep)
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
                // Never auto-remove user-owned local files.
                targets.filter { !isLocalPath(it.localFilePath) }.forEach { ep ->
                    if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                        if (retentionHours == 0) {
                            purgeEpisodeFile(ep)
                        } else {
                            dao.markForDeletion(ep.id, now)
                        }
                    }
                }
            }

            val maxDownloads = preferences.settings.value.maxDownloadsPerPodcast
            if (trimToMax && maxDownloads > 0) {
                dao.getAllPodcasts().filter { !it.isLocal }.forEach { pod ->
                    val activeList = dao.getActiveDownloadsForPodcastNotInQueue(pod.id)
                    if (activeList.size > maxDownloads) {
                        activeList.drop(maxDownloads).forEach { ep ->
                            if (activeEpisodeId == null || ep.id != activeEpisodeId) {
                                if (retentionHours == 0) {
                                    purgeEpisodeFile(ep)
                                } else {
                                    dao.markForDeletion(ep.id, now)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isLocalPath(path: String?): Boolean = path?.startsWith("content://") == true

    /**
     * Returns true if the file is gone (or was never ours) so DB state may be reset.
     * Returns false if a user-owned local file could not be deleted (no write permission
     * or provider error); callers must then leave the episode untouched.
     */
    private fun safeDeleteEpisodeFile(path: String): Boolean {
        return runCatching {
            if (path.startsWith("content://")) {
                val treeUri = preferences.settings.value.localFolderUri
                if (com.lycoslane.purepodcast.core.local.LocalPodcastScanner.hasWritePermission(context, treeUri)) {
                    com.lycoslane.purepodcast.core.local.LocalPodcastScanner.deleteLocalDocument(context, path)
                } else {
                    false
                }
            } else {
                val episodesRoot = File(context.filesDir, "episodes").canonicalFile
                val target = File(path).canonicalFile
                if (target.path.startsWith(episodesRoot.path + File.separator)) {
                    target.delete() || !target.exists()
                } else {
                    true
                }
            }
        }.getOrDefault(false)
    }

    /** Deletes the file and resets download state. Returns false (no changes) if deletion failed. */
    private suspend fun purgeEpisodeFile(ep: EpisodeEntity): Boolean {
        val path = ep.localFilePath
        com.lycoslane.purepodcast.core.download.TranscodeWorker.cancelForEpisode(context, ep.id)
        if (path != null && !safeDeleteEpisodeFile(path)) return false
        dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
        dao.setSkipAutoDownload(ep.id, true)
        return true
    }

    private fun canModifyLocalFile(ep: EpisodeEntity): Boolean {
        if (!isLocalPath(ep.localFilePath)) return true
        return com.lycoslane.purepodcast.core.local.LocalPodcastScanner.hasWritePermission(
            context,
            preferences.settings.value.localFolderUri
        )
    }

    suspend fun toggleSortAscending(podcastId: Long, ascending: Boolean) {
        dao.setSortAscending(podcastId, ascending)
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

    suspend fun addToQueueFront(episodeId: Long) {
        dao.enqueueToQueueFront(episodeId)
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
                    put("trashRetentionHours", settings.trashRetentionHours)
                    put("maxDownloadsPerPodcast", settings.maxDownloadsPerPodcast)
                    put("smartRewindSeconds", settings.smartRewindSeconds)
                    put("audioNormalizationEnabled", settings.audioNormalizationEnabled)
                    put("bluetoothNextPrevSeeks", settings.bluetoothNextPrevSeeks)
                    put("textScale", settings.textScale.toDouble())
                    put("appLanguage", settings.appLanguage)
                    put("transcodeBitrateKbps", settings.transcodeBitrateKbps)
                    put("transcodeAutoEnabled", settings.transcodeAutoEnabled)
                    put("transcodeChargingOnly", settings.transcodeChargingOnly)
                }
            )
            val podcastsArr = org.json.JSONArray()
            dao.getAllPodcasts().filter { !it.isLocal && it.isSubscribed }.forEach { pod ->
                val epArr = org.json.JSONArray()
                dao.getEpisodesWithProgress(pod.id).forEach { ep ->
                    epArr.put(
                        org.json.JSONObject().apply {
                            put("guid", ep.guid)
                            put("positionMs", ep.positionMs)
                            put("isPlayed", ep.isPlayed)
                            put("isHidden", ep.isHidden)
                            put("isFavorite", ep.isFavorite)
                            put("playedAtMs", ep.playedAtMs)
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
            val queueArr = org.json.JSONArray()
            dao.getQueueEpisodesOnce().forEach { item ->
                if (!item.podcast.isLocal) {
                    queueArr.put(
                        org.json.JSONObject().apply {
                            put("feedUrl", item.podcast.feedUrl)
                            put("guid", item.episode.guid)
                        }
                    )
                }
            }
            put("queue", queueArr)
        }
        root.toString(2)
    }

    suspend fun importBackupJson(jsonText: String): Int = withContext(Dispatchers.IO) {
        val root = org.json.JSONObject(jsonText)
        val version = root.optInt("version", 1)
        if (version < 1 || version > 1) error("Unsupported backup version")

        root.optJSONObject("settings")?.let { s ->
            if (s.has("refreshIntervalHours")) {
                val hours = s.optInt("refreshIntervalHours", 6).let { if (it in listOf(0, 1, 3, 6, 12, 24)) it else 6 }
                preferences.setRefreshIntervalHours(hours)
                com.lycoslane.purepodcast.core.download.FeedRefreshWorker.schedulePeriodic(context, hours, replaceExisting = true)
            }
            if (s.has("wifiOnlyDownloads")) preferences.setWifiOnlyDownloads(s.optBoolean("wifiOnlyDownloads", true))
            if (s.has("autoDownloadNewByDefault")) preferences.setAutoDownloadNewByDefault(s.optBoolean("autoDownloadNewByDefault", false))
            if (s.has("downloadWhenQueued")) preferences.setDownloadWhenQueued(s.optBoolean("downloadWhenQueued", false))
            if (s.has("addToQueueWhenDownloaded")) preferences.setAddToQueueWhenDownloaded(s.optBoolean("addToQueueWhenDownloaded", false))
            if (s.has("autoRemovePlayedDays")) {
                val days = s.optInt("autoRemovePlayedDays", 1).let { if (it in listOf(-1, 0, 1, 3, 7)) it else 1 }
                preferences.setAutoRemovePlayedDays(days)
            }
            if (s.has("trashRetentionHours")) {
                val hours = s.optInt("trashRetentionHours", 24).let { if (it in listOf(0, 24, 48)) it else 24 }
                preferences.setTrashRetentionHours(hours)
            }
            if (s.has("maxDownloadsPerPodcast")) {
                preferences.setMaxDownloadsPerPodcast(s.optInt("maxDownloadsPerPodcast", 0).coerceIn(0, 30))
            }
            if (s.has("smartRewindSeconds")) {
                preferences.setSmartRewindSeconds(s.optInt("smartRewindSeconds", 10).coerceIn(0, 60))
            }
            if (s.has("audioNormalizationEnabled")) {
                preferences.setAudioNormalizationEnabled(s.optBoolean("audioNormalizationEnabled", false))
            }
            if (s.has("bluetoothNextPrevSeeks")) {
                preferences.setBluetoothNextPrevSeeks(s.optBoolean("bluetoothNextPrevSeeks", true))
            }
            if (s.has("textScale")) {
                val scale = s.optDouble("textScale", 1.0)
                if (scale.isFinite()) preferences.setTextScale(scale.toFloat())
            }
            if (s.has("appLanguage")) {
                val lang = s.optString("appLanguage", "en")
                if (lang in listOf("en", "de")) preferences.setAppLanguage(lang)
            }
            if (s.has("transcodeBitrateKbps")) {
                val kbps = s.optInt("transcodeBitrateKbps", 0).let { if (it in listOf(0, 96, 128)) it else 0 }
                preferences.setTranscodeBitrateKbps(kbps)
            }
            if (s.has("transcodeAutoEnabled")) preferences.setTranscodeAutoEnabled(s.optBoolean("transcodeAutoEnabled", false))
            if (s.has("transcodeChargingOnly")) preferences.setTranscodeChargingOnly(s.optBoolean("transcodeChargingOnly", true))
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
                    for (j in 0 until minOf(epArr.length(), 5000)) {
                        val eObj = epArr.optJSONObject(j) ?: continue
                        val guid = eObj.optString("guid", "").take(512)
                        if (guid.isEmpty()) continue
                        val ep = dao.getEpisodeByGuid(podcastId, guid) ?: continue
                        dao.updatePosition(ep.id, eObj.optLong("positionMs", ep.positionMs).coerceAtLeast(0L))
                        val restoredPlayed = eObj.optBoolean("isPlayed", ep.isPlayed)
                        val restoredPlayedAt = if (restoredPlayed) {
                            eObj.optLong("playedAtMs", 0L).takeIf { it > 0L }
                                ?: ep.playedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else 0L
                        dao.setPlayed(ep.id, restoredPlayed, restoredPlayedAt)
                        if (eObj.has("isFavorite")) {
                            dao.setFavorite(ep.id, eObj.optBoolean("isFavorite", false))
                        }
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
        val queueArr = root.optJSONArray("queue")
        if (queueArr != null && dao.getQueueEpisodesOnce().isEmpty()) {
            for (k in 0 until minOf(queueArr.length(), 500)) {
                val qObj = queueArr.optJSONObject(k) ?: continue
                val feedUrl = qObj.optString("feedUrl", "").trim()
                val guid = qObj.optString("guid", "").take(512)
                if (feedUrl.isEmpty() || guid.isEmpty()) continue
                val pod = dao.getPodcastByFeedUrl(feedUrl) ?: continue
                val ep = dao.getEpisodeByGuid(pod.id, guid) ?: continue
                dao.enqueueToQueue(ep.id)
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
                fileSizeBytes = ep.fileSizeBytes,
                // Inline chapters are stored directly; an external Podcasting 2.0 chapters URL is
                // stored as a "url:" marker and resolved lazily when the episode is played.
                chaptersJson = ep.chaptersJson.ifEmpty {
                    if (ep.chaptersUrl.isNotEmpty() && NetworkSecurity.isValidHttpUrl(ep.chaptersUrl)) {
                        "url:" + ep.chaptersUrl
                    } else {
                        ""
                    }
                }
            )
        }
        dao.insertEpisodes(entities)
    }
}
