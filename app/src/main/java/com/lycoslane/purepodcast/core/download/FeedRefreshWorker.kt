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
import java.util.concurrent.TimeUnit

class FeedRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
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
                        EpisodeDownloadWorker.enqueue(applicationContext, ep.id, wifiOnly = true)
                    }
                }
            } catch (_: Exception) {
                // Keep refreshing remaining feeds even if one fails
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "purepodcast_periodic_feed_refresh"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
