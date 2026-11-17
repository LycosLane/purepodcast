package com.lycoslane.purepodcast.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.core.audio.AudioTranscoder
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscodeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PurePodcastApplication
        val settings = app.preferences.settings.value
        val bitrateKbps = settings.transcodeBitrateKbps
        if (bitrateKbps <= 0) return@withContext Result.success()

        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
        val singleEpisodeId = inputData.getLong(KEY_EPISODE_ID, -1L)

        val targets = if (singleEpisodeId > 0L) {
            listOfNotNull(dao.getEpisode(singleEpisodeId))
        } else {
            dao.getUntranscodedDownloads()
        }

        val activeId = app.playerController.state.value.currentEpisode?.id

        for (ep in targets) {
            if (isStopped) break
            if (ep.id == activeId) continue
            if (ep.isTranscoded || ep.downloadState != DownloadState.DOWNLOADED) continue
            val path = ep.localFilePath ?: continue
            if (path.startsWith("content://")) continue // Never modify external SAF local folder files

            val sourceFile = File(path)
            if (!sourceFile.exists() || sourceFile.length() <= 0L) continue

            val parentDir = sourceFile.parentFile ?: continue
            val targetM4a = File(parentDir, "${ep.id}.m4a")
            val tempFile = File(parentDir, "${ep.id}.m4a.tmp")

            val ok = AudioTranscoder.transcodeToAacM4a(
                sourceFile = sourceFile,
                tempOutputFile = tempFile,
                targetBitrateKbps = bitrateKbps,
                shouldCancel = { isStopped }
            )

            if (ok && tempFile.exists() && tempFile.length() > 0L) {
                if (tempFile.length() < sourceFile.length()) {
                    if (targetM4a.exists()) targetM4a.delete()
                    val moved = tempFile.renameTo(targetM4a) || run {
                        tempFile.copyTo(targetM4a, overwrite = true)
                        tempFile.delete()
                        true
                    }
                    if (moved) {
                        if (sourceFile.absolutePath != targetM4a.absolutePath) {
                            sourceFile.delete()
                        }
                        dao.markEpisodeTranscoded(ep.id, targetM4a.absolutePath, targetM4a.length())
                    }
                } else {
                    // Transcoded file wasn't smaller (e.g., source already low-bitrate); keep original
                    tempFile.delete()
                    dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length())
                }
            } else {
                tempFile.delete()
            }
        }

        Result.success()
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val UNIQUE_BATCH_WORK = "transcode_all_downloads"

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
