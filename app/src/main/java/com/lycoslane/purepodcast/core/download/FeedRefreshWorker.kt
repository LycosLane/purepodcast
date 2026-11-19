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
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
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
        for (podcast in podcasts) {
            if (isStopped) break
            try {
                app.repository.refreshPodcast(podcast.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                allSucceeded = false
            }
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
