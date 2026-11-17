package com.lycoslane.purepodcast.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.network.RssFeedParser
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import java.util.concurrent.TimeUnit

class FeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val settings = AppPreferences(applicationContext).settings.value
        val podcasts = dao.getAllPodcasts()

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
                dao.updatePodcast(podcast.copy(lastRefreshedMs = System.currentTimeMillis()))

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
            } catch (_: Exception) {
                // Keep refreshing remaining feeds even if one fails
            }
        }

        // Clean up played downloads according to user retention setting
        val removeDays = settings.autoRemovePlayedDays
        if (removeDays >= 0) {
            val targets = if (removeDays == 0) {
                dao.getAllPlayedDownloads()
            } else {
                val cutoffMs = System.currentTimeMillis() - removeDays * 86_400_000L
                dao.getPlayedDownloadsOlderThan(cutoffMs)
            }
            targets.forEach { ep ->
                ep.localFilePath?.let { path -> java.io.File(path).delete() }
                dao.updateDownloadState(ep.id, DownloadState.NONE, 0, null)
            }
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "purepodcast_periodic_feed_refresh"

        fun schedulePeriodic(context: Context, intervalHours: Int) {
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
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
