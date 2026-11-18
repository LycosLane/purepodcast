package com.lycoslane.purepodcast.core.download

import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class EpisodeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId <= 0L) return@withContext Result.failure()

        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val episode = dao.getEpisode(episodeId) ?: return@withContext Result.failure()
        if (episode.isHidden) {
            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
            return@withContext Result.failure()
        }

        val dir = File(applicationContext.filesDir, "episodes/${episode.podcastId}")
        if (!dir.exists()) dir.mkdirs()
        val targetFile = File(dir, "${episode.id}.mp3")
        val tempFile = File(dir, "${episode.id}.tmp")
        val etagFile = File(dir, "${episode.id}.etag")

        dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, 0, null)

        // Promote to a foreground (dataSync) job so long downloads aren't killed after 10 minutes.
        runCatching {
            setForeground(
                WorkerForeground.info(
                    context = applicationContext,
                    notificationId = FOREGROUND_ID_BASE + (episodeId % 100_000L).toInt(),
                    channelId = DOWNLOAD_CHANNEL_ID,
                    channelName = applicationContext.getString(R.string.notif_download_channel),
                    title = episode.title,
                    text = applicationContext.getString(R.string.notif_downloading)
                )
            )
        }

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
                // Stale partial file: start over on the next attempt.
                tempFile.delete()
                etagFile.delete()
                dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                return@withContext Result.retry()
            }
            if (code !in 200..299) {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                val isPermanent4xx = code in 400..499 && code != 408 && code != 429
                if (isPermanent4xx || runAttemptCount >= 3) {
                    tempFile.delete()
                    etagFile.delete()
                    return@withContext Result.failure()
                }
                return@withContext Result.retry()
            }
            if (code != 206) existing = 0L // server ignored Range: restart from scratch
            conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let { etag ->
                runCatching { etagFile.writeText(etag) }
            }

            val remaining = conn.contentLengthLong.takeIf { it > 0 }
            val totalBytes = if (remaining != null) existing + remaining else episode.fileSizeBytes
            if (totalBytes > MAX_EPISODE_BYTES) {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                tempFile.delete()
                etagFile.delete()
                return@withContext Result.failure()
            }
            if (totalBytes > 0 && dir.usableSpace < (totalBytes - existing) + MIN_FREE_BYTES) {
                // Not enough free space: fail without endless retries.
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                return@withContext Result.failure()
            }
            var downloaded = existing
            var lastReportedPct = -1

            BoundedInputStream(conn.inputStream, MAX_EPISODE_BYTES).use { input ->
                java.io.FileOutputStream(tempFile, existing > 0L).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isStopped) {
                            // Keep the partial file so the download can resume later.
                            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                            return@withContext Result.failure()
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                            if (pct >= lastReportedPct + 5) {
                                lastReportedPct = pct
                                dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, pct, null)
                            }
                        }
                    }
                }
            }

            val latest = dao.getEpisode(episodeId)
            if (isStopped || latest == null || latest.isHidden || latest.skipAutoDownload) {
                tempFile.delete()
                etagFile.delete()
                if (latest != null) {
                    dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                }
                return@withContext Result.failure()
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
            val app = applicationContext as PurePodcastApplication
            val settings = app.preferences.settings.value
            if (settings.addToQueueWhenDownloaded && !latest.isHidden && dao.queueCount(episodeId) == 0) {
                dao.enqueueToQueue(episodeId)
            }
            if (settings.transcodeAutoEnabled && settings.transcodeBitrateKbps > 0) {
                TranscodeWorker.enqueueSingle(
                    context = applicationContext,
                    episodeId = episodeId,
                    chargingOnly = settings.transcodeChargingOnly
                )
            }
            runCatching {
                // Don't trim to max-downloads here: it could immediately remove a manually
                // downloaded older episode. Trimming happens on feed refresh / app start.
                app.repository.cleanupPlayedDownloads(
                    app.playerController.state.value.currentEpisode?.id,
                    trimToMax = false
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            // Partial file is kept for resume; orphans are swept at app start.
            withContext(NonCancellable) {
                val current = dao.getEpisode(episodeId)
                if (current != null &&
                    (current.downloadState == DownloadState.DOWNLOADING || current.downloadState == DownloadState.QUEUED)
                ) {
                    dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                }
            }
            throw e
        } catch (_: Exception) {
            if (isStopped) {
                dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                Result.failure()
            } else {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                if (runAttemptCount >= 3) {
                    tempFile.delete()
                    etagFile.delete()
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MAX_EPISODE_BYTES = 1536L * 1024 * 1024 // 1.5 GiB
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val FOREGROUND_ID_BASE = 5000
        private const val DOWNLOAD_CHANNEL_ID = "purepodcast_downloads"

        fun uniqueName(episodeId: Long) = "episode_download_$episodeId"

        fun enqueue(
            context: Context,
            episodeId: Long,
            wifiOnly: Boolean = false,
            replaceExisting: Boolean = false
        ) {
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
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(episodeId))
        }
    }
}
