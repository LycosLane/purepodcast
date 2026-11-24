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
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.network.NetworkUtils
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
        runCatching { app.repository.syncLocalFolder() }
        val podcasts = dao.getAllPodcasts().filter { !it.isLocal && it.isSubscribed }

        var allSucceeded = true
        val downloadedPairs = mutableListOf<Pair<PodcastEntity, EpisodeEntity>>()
        val availablePairs = mutableListOf<Pair<PodcastEntity, EpisodeEntity>>()

        for (podcast in podcasts) {
            if (isStopped) break
            val newEps = try {
                app.repository.refreshPodcast(podcast.id, enqueueAutoDownloads = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                allSucceeded = false
                emptyList()
            }

            val latestPod = dao.getPodcast(podcast.id) ?: podcast
            val settings = app.preferences.settings.value
            val handledIds = mutableSetOf<Long>()

            if (latestPod.autoDownloadFromPubDateMs > 0L) {
                val missing = dao.getNotDownloadedNewerThan(podcast.id, latestPod.autoDownloadFromPubDateMs)
                    .sortedByDescending { it.pubDateMs }
                val maxDownloads = settings.maxDownloadsPerPodcast
                val toProcess = if (maxDownloads > 0) missing.take(maxDownloads) else missing
                val newIdSet = newEps.map { it.id }.toSet()

                for (ep in toProcess) {
                    if (isStopped) break
                    val canDownloadNow = !settings.wifiOnlyDownloads || NetworkUtils.isOnUnmeteredWifi(applicationContext)
                    if (canDownloadNow) {
                        val outcome = EpisodeDownloadWorker.performDownload(
                            context = applicationContext,
                            episodeId = ep.id,
                            canRetry = false,
                            isStoppedCheck = { isStopped }
                        )
                        if (ep.id in newIdSet) {
                            handledIds.add(ep.id)
                            if (outcome == EpisodeDownloadWorker.DownloadOutcome.SUCCESS) {
                                downloadedPairs.add(latestPod to ep)
                            } else {
                                availablePairs.add(latestPod to ep)
                            }
                        }
                    } else {
                        dao.updateDownloadState(ep.id, DownloadState.QUEUED, 0, null)
                        EpisodeDownloadWorker.enqueue(applicationContext, ep.id, wifiOnly = true)
                        if (ep.id in newIdSet) {
                            handledIds.add(ep.id)
                            availablePairs.add(latestPod to ep)
                        }
                    }
                }
            }

            newEps.forEach { ep ->
                if (ep.id !in handledIds) {
                    availablePairs.add(latestPod to ep)
                }
            }
        }

        if (app.preferences.settings.value.notifyNewEpisodes) {
            NewEpisodeNotifier.notify(applicationContext, downloadedPairs, availablePairs)
        }

        runCatching {
            app.repository.cleanupPlayedDownloads(app.playerController.state.value.currentEpisode?.id)
        }

        if (!allSucceeded && !isStopped && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "purepodcast_periodic_feed_refresh"

        fun schedulePeriodic(
            context: Context,
            intervalMinutes: Int,
            replaceExisting: Boolean = true
        ) {
            val wm = WorkManager.getInstance(context)
            if (intervalMinutes <= 0) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
                intervalMinutes.toLong().coerceAtLeast(15L),
                TimeUnit.MINUTES
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
