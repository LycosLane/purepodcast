package com.lycoslane.purepodcast.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class EpisodeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        if (episodeId <= 0L) return@withContext Result.failure()

        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val episode = dao.getEpisode(episodeId) ?: return@withContext Result.failure()

        val dir = File(applicationContext.filesDir, "episodes/${episode.podcastId}")
        if (!dir.exists()) dir.mkdirs()
        val targetFile = File(dir, "${episode.id}.mp3")
        val tempFile = File(dir, "${episode.id}.tmp")

        dao.updateDownloadState(episodeId, DownloadState.DOWNLOADING, 0, null)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(episode.audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PurePodcast/0.1 (Android)")
            }
            if (conn.responseCode !in 200..299) {
                dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
                return@withContext Result.retry()
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: episode.fileSizeBytes
            var downloaded = 0L
            var lastReportedPct = -1

            conn.inputStream.use { input ->
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
            if (com.lycoslane.purepodcast.core.preferences.AppPreferences(applicationContext)
                    .settings.value.addToQueueWhenDownloaded
            ) {
                val nextOrder = dao.getMaxQueueOrder() + 1
                dao.insertQueueItem(
                    com.lycoslane.purepodcast.core.database.QueueItemEntity(
                        episodeId = episodeId,
                        sortOrder = nextOrder
                    )
                )
            }
            Result.success()
        } catch (_: Exception) {
            tempFile.delete()
            dao.updateDownloadState(episodeId, DownloadState.FAILED, 0, null)
            Result.retry()
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"

        fun enqueue(context: Context, episodeId: Long, wifiOnly: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(Data.Builder().putLong(KEY_EPISODE_ID, episodeId).build())
                .addTag("download_$episodeId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "episode_download_$episodeId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
