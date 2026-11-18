package com.lycoslane.purepodcast.core.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.AudioTranscoder
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class TranscodeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        transcodeMutex.withLock {
            val app = applicationContext as PurePodcastApplication
            val settings = app.preferences.settings.value
            val bitrateKbps = settings.transcodeBitrateKbps
            if (bitrateKbps <= 0) return@withLock Result.success()

            val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
            val singleEpisodeId = inputData.getLong(KEY_EPISODE_ID, -1L)

            val targets = if (singleEpisodeId > 0L) {
                listOfNotNull(dao.getEpisode(singleEpisodeId))
            } else {
                dao.getUntranscodedDownloads()
            }.filter {
                !it.isTranscoded &&
                    it.downloadState == DownloadState.DOWNLOADED &&
                    it.localFilePath?.startsWith("content://") != true
            }

            val isBatch = singleEpisodeId <= 0L
            var processed = 0
            var converted = 0

            if (isBatch && targets.isEmpty()) {
                postNotification(
                    title = applicationContext.getString(R.string.app_name),
                    text = applicationContext.getString(R.string.notif_transcode_nothing),
                    progress = null
                )
                return@withLock Result.success()
            }

            if (targets.isEmpty()) return@withLock Result.success()

            // Foreground (dataSync) so batch conversion isn't killed by the 10 minute limit.
            runCatching {
                setForeground(
                    WorkerForeground.info(
                        context = applicationContext,
                        notificationId = NOTIFICATION_ID,
                        channelId = CHANNEL_ID,
                        channelName = applicationContext.getString(R.string.notif_transcode_channel),
                        title = applicationContext.getString(R.string.settings_section_compression),
                        text = applicationContext.getString(R.string.notif_transcode_progress, 1, targets.size)
                    )
                )
            }

            for (ep in targets) {
                if (isStopped) break
                processed++
                if (isBatch) {
                    postNotification(
                        title = applicationContext.getString(R.string.settings_section_compression),
                        text = applicationContext.getString(R.string.notif_transcode_progress, processed, targets.size),
                        progress = processed to targets.size
                    )
                }
                if (ep.id == app.playerController.state.value.currentEpisode?.id) continue
                val path = ep.localFilePath ?: continue

                val sourceFile = File(path)
                if (!sourceFile.exists() || sourceFile.length() <= 0L) continue

                val parentDir = sourceFile.parentFile ?: continue
                // Already a compact AAC file at or below the target bitrate: nothing to gain.
                if (AudioTranscoder.isAlreadyCompact(sourceFile, bitrateKbps)) {
                    dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length())
                    continue
                }
                // Need room for the temporary output next to the source (worst case ~ source size).
                if (parentDir.usableSpace < sourceFile.length() + MIN_FREE_BYTES) continue
                val targetM4a = File(parentDir, "${ep.id}.m4a")
                val tempFile = File(parentDir, "${ep.id}.m4a.tmp")

                val ok = AudioTranscoder.transcodeToAacM4a(
                    sourceFile = sourceFile,
                    tempOutputFile = tempFile,
                    targetBitrateKbps = bitrateKbps,
                    shouldCancel = { isStopped }
                )

                if (ok && tempFile.exists() && tempFile.length() > 0L) {
                    // Re-validate: user may have started playback, trashed or removed the episode meanwhile.
                    val fresh = dao.getEpisode(ep.id)
                    val playingNow = app.playerController.state.value.currentEpisode?.id == ep.id
                    if (playingNow || fresh == null ||
                        fresh.downloadState != DownloadState.DOWNLOADED ||
                        fresh.markedForDeletionAtMs > 0L ||
                        fresh.localFilePath != path
                    ) {
                        tempFile.delete()
                        continue
                    }
                    if (tempFile.length() < sourceFile.length()) {
                        if (targetM4a.exists()) targetM4a.delete()
                        val moved = tempFile.renameTo(targetM4a) || run {
                            tempFile.copyTo(targetM4a, overwrite = true)
                            tempFile.delete()
                            true
                        }
                        if (moved) {
                            dao.markEpisodeTranscoded(ep.id, targetM4a.absolutePath, targetM4a.length())
                            if (sourceFile.absolutePath != targetM4a.absolutePath) {
                                sourceFile.delete()
                            }
                            converted++
                        }
                    } else {
                        // Transcoded file wasn't smaller (e.g., source already low-bitrate); keep original
                        tempFile.delete()
                        dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length())
                        converted++
                    }
                } else {
                    tempFile.delete()
                }
            }

            if (isBatch) {
                val text = if (converted > 0) {
                    applicationContext.getString(R.string.notif_transcode_done, converted)
                } else {
                    applicationContext.getString(R.string.notif_transcode_nothing)
                }
                postNotification(
                    title = applicationContext.getString(R.string.settings_section_compression),
                    text = text,
                    progress = null
                )
            }

            Result.success()
        }
    }

    private fun postNotification(title: String, text: String, progress: Pair<Int, Int>?) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notif_transcode_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(progress != null)
            .setAutoCancel(progress == null)
        if (progress != null) {
            builder.setProgress(progress.second, progress.first, false)
        }
        try {
            nm.notify(if (progress == null) NOTIFICATION_ID + 1 else NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val UNIQUE_BATCH_WORK = "transcode_all_downloads"
        private const val CHANNEL_ID = "purepodcast_transcode"
        private const val NOTIFICATION_ID = 4102
        private val transcodeMutex = Mutex()

        fun cancelForEpisode(context: Context, episodeId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("transcode_ep_$episodeId")
        }

        fun enqueueSingle(context: Context, episodeId: Long, chargingOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(chargingOnly)
                .build()
            val req = OneTimeWorkRequestBuilder<TranscodeWorker>()
                .setInputData(Data.Builder().putLong(KEY_EPISODE_ID, episodeId).build())
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcode_ep_$episodeId",
                ExistingWorkPolicy.KEEP,
                req
            )
        }

        fun enqueueAllNow(context: Context, chargingOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(chargingOnly)
                .build()
            val req = OneTimeWorkRequestBuilder<TranscodeWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_BATCH_WORK,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
