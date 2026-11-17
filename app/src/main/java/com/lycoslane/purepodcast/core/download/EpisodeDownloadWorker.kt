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

        dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, 0, null)

        var conn: HttpURLConnection? = null
        try {
            conn = NetworkSecurity.openValidatedConnection(
                rawUrl = episode.audioUrl,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 30_000
            )
            val code = conn.responseCode
            if (code !in 200..299) {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                val isPermanent4xx = code in 400..499 && code != 408 && code != 429
                return@withContext if (isPermanent4xx || runAttemptCount >= 3) Result.failure() else Result.retry()
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: episode.fileSizeBytes
            if (totalBytes > MAX_EPISODE_BYTES) {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                return@withContext Result.failure()
            }
            var downloaded = 0L
            var lastReportedPct = -1

            BoundedInputStream(conn.inputStream, MAX_EPISODE_BYTES).use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (isStopped) {
                            tempFile.delete()
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
            tempFile.delete()
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
            tempFile.delete()
            if (isStopped) {
                dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
                Result.failure()
            } else {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                if (runAttemptCount >= 3) Result.failure() else Result.retry()
            }
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MAX_EPISODE_BYTES = 1536L * 1024 * 1024 // 1.5 GiB

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
