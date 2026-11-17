package com.lycoslane.purepodcast.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.network.RssFeedParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class FeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PurePodcastApplication
        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val settings = app.preferences.settings.value
        val podcasts = dao.getAllPodcasts()

        var anySucceeded = podcasts.isEmpty()
        for (podcast in podcasts) {
            try {
                val parsed = RssFeedParser.fetchAndParse(podcast.feedUrl)
                val newEntities = parsed.episodes.map { ep ->
                    EpisodeEntity(
                        podcastId = podcast.id,
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
                dao.insertEpisodes(newEntities)
                dao.updatePodcastMetadata(
                    podcastId = podcast.id,
                    title = parsed.title.ifEmpty { podcast.title },
                    author = parsed.author.ifEmpty { podcast.author },
                    description = parsed.description.ifEmpty { podcast.description },
                    artworkUrl = parsed.artworkUrl.ifEmpty { podcast.artworkUrl },
                    lastRefreshedMs = System.currentTimeMillis()
                )

                if (podcast.autoDownloadFromPubDateMs > 0L) {
                    val toDownload = dao.getNotDownloadedNewerThan(
                        podcastId = podcast.id,
                        fromPubDateMs = podcast.autoDownloadFromPubDateMs
                    )
                    for (ep in toDownload) {
                        dao.updateDownloadState(ep.id, DownloadState.QUEUED, 0, null)
                        EpisodeDownloadWorker.enqueue(
                            context = applicationContext,
                            episodeId = ep.id,
                            wifiOnly = settings.wifiOnlyDownloads
                        )
                    }
                }
                anySucceeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep refreshing remaining feeds even if one fails
            }
        }

        runCatching { app.repository.cleanupPlayedDownloads() }

        if (!anySucceeded && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "purepodcast_periodic_feed_refresh"

        fun schedulePeriodic(
            context: Context,
            intervalHours: Int,
            replaceExisting: Boolean = true
        ) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
                intervalHours.toLong().coerceAtLeast(1L),
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
