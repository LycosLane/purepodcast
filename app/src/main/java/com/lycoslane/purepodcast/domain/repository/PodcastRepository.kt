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
    private val backupManager = BackupManager(
        context = context,
        dao = dao,
        preferences = preferences,
        subscribeByFeedUrl = ::subscribeByFeedUrl
    )

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
        val transcodeIdle = !com.lycoslane.purepodcast.core.download.TranscodeWorker.status.value.isActive
        File(context.filesDir, "episodes").listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val isTranscodeTmp = f.name.endsWith(".m4a.tmp")
                val isPartial = f.name.endsWith(".tmp") || f.name.endsWith(".etag")
                val id = f.name.substringBefore('.').toLongOrNull()
                if (isTranscodeTmp && transcodeIdle) {
                    f.delete()
                } else if (isPartial && id !in stillActive && f.lastModified() < cutoff) {
                    f.delete()
                }
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

    suspend fun setPodcastMonoModeOverride(podcastId: Long, mode: Int) {
        dao.setPodcastMonoModeOverride(podcastId, com.lycoslane.purepodcast.core.audio.AudioMonoMode.normalizeOverride(mode))
    }

    suspend fun setPodcastAudioNormalizationOverride(podcastId: Long, override: Int) {
        dao.setPodcastAudioNormalizationOverride(podcastId, com.lycoslane.purepodcast.core.audio.AudioOverride.normalize(override))
    }

    suspend fun setEpisodeMonoModeOverride(episodeId: Long, mode: Int) {
        dao.setEpisodeMonoModeOverride(episodeId, com.lycoslane.purepodcast.core.audio.AudioMonoMode.normalizeOverride(mode))
    }

    suspend fun setPodcastPlaybackSpeed(podcastId: Long, speed: Float) {
        dao.setPlaybackSpeed(podcastId, speed.coerceIn(0.50f, 3.0f))
    }

    suspend fun setPodcastSilenceMode(podcastId: Long, enabled: Boolean, intensity: Int) {
        dao.setSkipSilence(podcastId, enabled)
        dao.setSilenceIntensity(podcastId, intensity.coerceIn(0, 2))
    }

    suspend fun applyAudioSettingsToAllPodcasts(podcast: PodcastEntity) = withContext(Dispatchers.IO) {
        val global = preferences.settings.value
        val speed = com.lycoslane.purepodcast.core.audio.SpeedOverride.resolve(
            globalSpeed = global.defaultPlaybackSpeed,
            podcastOverride = podcast.speedOverride
        )
        val (skipSilence, silenceIntensity) = com.lycoslane.purepodcast.core.audio.SilenceOverride.resolve(
            globalEnabled = global.defaultSkipSilence,
            globalIntensity = global.defaultSilenceIntensity,
            podcastOverride = podcast.silenceModeOverride
        )
        val effectiveMono = com.lycoslane.purepodcast.core.audio.AudioMonoMode.resolveEffective(
            globalMonoMode = global.monoMode,
            podcastMonoOverride = podcast.monoModeOverride,
            episodeMonoOverride = com.lycoslane.purepodcast.core.audio.AudioMonoMode.INHERIT
        )
        val effectiveNorm = com.lycoslane.purepodcast.core.audio.AudioOverride.resolve(
            globalEnabled = global.audioNormalizationEnabled,
            podcastOverride = podcast.audioNormalizationOverride
        )
        val effectiveRewind = com.lycoslane.purepodcast.core.audio.SmartRewindOverride.resolve(
            globalSeconds = global.smartRewindSeconds,
            podcastOverride = podcast.smartRewindOverride
        )

        preferences.setDefaultPlaybackSpeed(speed)
        preferences.setDefaultSilenceMode(skipSilence, silenceIntensity)
        preferences.setMonoMode(effectiveMono)
        preferences.setAudioNormalizationEnabled(effectiveNorm)
        preferences.setSmartRewindSeconds(effectiveRewind)

        dao.applyAudioSettingsToAllPodcasts(
            speed = speed,
            skipSilence = skipSilence,
            silenceIntensity = silenceIntensity
        )
    }

    suspend fun exportBackupJson(): String = backupManager.exportBackupJson()

    suspend fun importBackupJson(jsonText: String): Int = backupManager.importBackupJson(jsonText)

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
