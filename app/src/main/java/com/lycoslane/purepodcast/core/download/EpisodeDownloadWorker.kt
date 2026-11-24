package com.lycoslane.purepodcast.core.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.network.BoundedInputStream
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import com.lycoslane.purepodcast.core.preferences.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EpisodeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId <= 0L) return@withContext Result.failure()

        // Promote to a foreground (dataSync) job so long downloads aren't killed after 10 minutes.
        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val initialEpisode = dao.getEpisode(episodeId) ?: return@withContext Result.failure()
        val localized = LocaleHelper.wrapContext(applicationContext)
        runCatching {
            setForeground(
                WorkerForeground.info(
                    context = localized,
                    notificationId = FOREGROUND_ID_BASE + (episodeId % 100_000L).toInt(),
                    channelId = DOWNLOAD_CHANNEL_ID,
                    channelName = localized.getString(R.string.notif_download_channel),
                    title = initialEpisode.title,
                    text = localized.getString(R.string.notif_downloading)
                )
            )
        }

        when (performDownload(applicationContext, episodeId, canRetry = runAttemptCount < 3) { isStopped }) {
            DownloadOutcome.SUCCESS -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILURE -> Result.failure()
        }
    }

    enum class DownloadOutcome { SUCCESS, RETRY, FAILURE }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MAX_EPISODE_BYTES = 1536L * 1024 * 1024 // 1.5 GiB
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val FOREGROUND_ID_BASE = 5000
        private const val DOWNLOAD_CHANNEL_ID = "purepodcast_downloads"
        private val activeDownloads = ConcurrentHashMap<Long, Job>()
        private val cancelledIds = ConcurrentHashMap.newKeySet<Long>()

        fun uniqueName(episodeId: Long) = "episode_download_$episodeId"

        fun enqueue(
            context: Context,
            episodeId: Long,
            wifiOnly: Boolean = false,
            replaceExisting: Boolean = false
        ) {
            cancelledIds.remove(episodeId)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putLong(KEY_EPISODE_ID, episodeId).build())
                .addTag("download_$episodeId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(episodeId),
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, episodeId: Long) {
            cancelledIds.add(episodeId)
            activeDownloads.remove(episodeId)?.cancel()
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(episodeId))
        }

        suspend fun performDownload(
            context: Context,
            episodeId: Long,
            canRetry: Boolean = false,
            isStoppedCheck: () -> Boolean = { false }
        ): DownloadOutcome = withContext(Dispatchers.IO) {
            val currentJob = coroutineContext[Job]
            if (currentJob != null) {
                while (true) {
                    val existingJob = activeDownloads.putIfAbsent(episodeId, currentJob) ?: break
                    if (existingJob.isActive) {
                        existingJob.join()
                        val ep = PurePodcastDatabase.getInstance(context).dao().getEpisode(episodeId)
                        return@withContext if (ep?.downloadState == DownloadState.DOWNLOADED) {
                            DownloadOutcome.SUCCESS
                        } else {
                            DownloadOutcome.FAILURE
                        }
                    } else {
                        activeDownloads.remove(episodeId, existingJob)
                    }
                }
            }
            val isCancelled = { isStoppedCheck() || episodeId in cancelledIds }
            val dao = PurePodcastDatabase.getInstance(context).dao()
            try {
                val episode = dao.getEpisode(episodeId) ?: return@withContext DownloadOutcome.FAILURE
                if (episode.isHidden || isCancelled()) {
                    if (episode.downloadState == DownloadState.DOWNLOADING || episode.downloadState == DownloadState.QUEUED) {
                        dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                    }
                    return@withContext DownloadOutcome.FAILURE
                }

                val dir = File(context.filesDir, "episodes/${episode.podcastId}")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, "${episode.id}.mp3")
                val tempFile = File(dir, "${episode.id}.tmp")
                val etagFile = File(dir, "${episode.id}.etag")

                dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, 0, null)

                var conn: HttpURLConnection? = null
                try {
                    // Resume a partial download only if we stored a strong ETag to validate it with If-Range.
                    val savedEtag = runCatching { etagFile.readText().trim() }.getOrDefault("")
                    var existing = if (tempFile.exists() && savedEtag.isNotEmpty() && !savedEtag.startsWith("W/")) {
                        tempFile.length()
                    } else {
                        tempFile.delete()
                        0L
                    }
                    val requestHeaders = if (existing > 0L) {
                        mapOf("Range" to "bytes=$existing-", "If-Range" to savedEtag)
                    } else {
                        emptyMap()
                    }

                    conn = NetworkSecurity.openValidatedConnection(
                        rawUrl = episode.audioUrl,
                        connectTimeoutMs = 15_000,
                        readTimeoutMs = 30_000,
                        headers = requestHeaders
                    )
                    val code = conn.responseCode
                    if (code == 416) {
                        tempFile.delete()
                        etagFile.delete()
                        dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                        return@withContext DownloadOutcome.RETRY
                    }
                    if (code !in 200..299) {
                        dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                        val isPermanent4xx = code in 400..499 && code != 408 && code != 429
                        if (isPermanent4xx || !canRetry) {
                            tempFile.delete()
                            etagFile.delete()
                            return@withContext DownloadOutcome.FAILURE
                        }
                        return@withContext DownloadOutcome.RETRY
                    }
                    if (code != 206) existing = 0L
                    conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let { etag ->
                        runCatching { etagFile.writeText(etag) }
                    }

                    val remaining = conn.contentLengthLong.takeIf { it > 0 }
                    val totalBytes = if (remaining != null) existing + remaining else episode.fileSizeBytes
                    if (totalBytes > MAX_EPISODE_BYTES) {
                        dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                        tempFile.delete()
                        etagFile.delete()
                        return@withContext DownloadOutcome.FAILURE
                    }
                    if (totalBytes > 0 && dir.usableSpace < (totalBytes - existing) + MIN_FREE_BYTES) {
                        dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                        return@withContext DownloadOutcome.FAILURE
                    }
                    var downloaded = existing
                    var lastReportedPct = -1

                    BoundedInputStream(conn.inputStream, MAX_EPISODE_BYTES).use { input ->
                        java.io.FileOutputStream(tempFile, existing > 0L).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                if (isCancelled()) {
                                    withContext(NonCancellable) {
                                        val cur = dao.getEpisode(episodeId)
                                        if (cur != null && (cur.downloadState == DownloadState.DOWNLOADING || cur.downloadState == DownloadState.QUEUED)) {
                                            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                                        }
                                    }
                                    return@withContext DownloadOutcome.FAILURE
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                                    if (pct >= lastReportedPct + 5) {
                                        if (isCancelled()) {
                                            withContext(NonCancellable) {
                                                val cur = dao.getEpisode(episodeId)
                                                if (cur != null && (cur.downloadState == DownloadState.DOWNLOADING || cur.downloadState == DownloadState.QUEUED)) {
                                                    dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                                                }
                                            }
                                            return@withContext DownloadOutcome.FAILURE
                                        }
                                        lastReportedPct = pct
                                        dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, pct, null)
                                    }
                                }
                            }
                        }
                    }

                    val latest = dao.getEpisode(episodeId)
                    if (latest == null || latest.isHidden || latest.skipAutoDownload || isCancelled()) {
                        tempFile.delete()
                        etagFile.delete()
                        if (latest != null && (latest.downloadState == DownloadState.DOWNLOADING || latest.downloadState == DownloadState.QUEUED)) {
                            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                        }
                        return@withContext DownloadOutcome.FAILURE
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }

                    etagFile.delete()
                    dao.updateDownloadState(
                        episodeId = episodeId,
                        state = DownloadState.DOWNLOADED,
                        progress = 100,
                        localPath = targetFile.absolutePath
                    )
                    val app = context.applicationContext as PurePodcastApplication
                    val settings = app.preferences.settings.value
                    if (settings.addToQueueWhenDownloaded && !latest.isHidden && dao.queueCount(episodeId) == 0) {
                        dao.enqueueToQueue(episodeId)
                    }
                    if (settings.transcodeAutoEnabled && settings.transcodeBitrateKbps > 0) {
                        TranscodeWorker.enqueueSingle(
                            context = context,
                            episodeId = episodeId,
                            chargingOnly = settings.transcodeChargingOnly
                        )
                    }
                    runCatching {
                        app.repository.cleanupPlayedDownloads(
                            app.playerController.state.value.currentEpisode?.id,
                            trimToMax = false
                        )
                    }
                    DownloadOutcome.SUCCESS
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        val current = dao.getEpisode(episodeId)
                        if (current != null &&
                            (current.downloadState == DownloadState.DOWNLOADING || current.downloadState == DownloadState.QUEUED)
                        ) {
                            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    Log.w("EpisodeDownloadWorker", "Download failed for episodeId=$episodeId url=${episode.audioUrl}", e)
                    if (isCancelled()) {
                        withContext(NonCancellable) {
                            val current = dao.getEpisode(episodeId)
                            if (current != null &&
                                (current.downloadState == DownloadState.DOWNLOADING || current.downloadState == DownloadState.QUEUED)
                            ) {
                                dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                            }
                        }
                        DownloadOutcome.FAILURE
                    } else {
                        dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                        if (!canRetry) {
                            tempFile.delete()
                            etagFile.delete()
                            DownloadOutcome.FAILURE
                        } else {
                            DownloadOutcome.RETRY
                        }
                    }
                } finally {
                    conn?.disconnect()
                }
            } finally {
                if (currentJob != null) {
                    activeDownloads.remove(episodeId, currentJob)
                }
            }
        }
    }
}
