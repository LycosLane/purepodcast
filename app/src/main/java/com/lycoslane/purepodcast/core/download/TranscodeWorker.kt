package com.lycoslane.purepodcast.core.download

import android.app.NotificationManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class TranscodeStatus(
    val isActive: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val episodeTitle: String = "",
    val episodePercent: Int = 0
) {
    /** Overall progress in 0f..1f across all episodes in the current run. */
    val overallProgress: Float
        get() {
            if (totalCount <= 0) return 0f
            val completedBefore = (currentIndex - 1).coerceAtLeast(0).toFloat()
            val currentFraction = (episodePercent.coerceIn(0, 100) / 100f)
            return ((completedBefore + currentFraction) / totalCount.toFloat()).coerceIn(0f, 1f)
        }
}

class TranscodeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        transcodeMutex.withLock {
            try {
                // Dismiss any leftover legacy notifications from older app versions
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                runCatching {
                    nm?.cancel(NOTIFICATION_ID)
                    nm?.cancel(NOTIFICATION_ID + 1)
                }

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

                if (targets.isEmpty()) return@withLock Result.success()

                var processed = 0
                for (ep in targets) {
                    if (isStopped) break
                    processed++
                    _status.value = TranscodeStatus(
                        isActive = true,
                        currentIndex = processed,
                        totalCount = targets.size,
                        episodeTitle = ep.title,
                        episodePercent = 0
                    )

                    if (ep.id == app.playerController.state.value.currentEpisode?.id) continue
                    val path = ep.localFilePath ?: continue

                    val sourceFile = File(path)
                    if (!sourceFile.exists() || sourceFile.length() <= 0L) continue

                    val parentDir = sourceFile.parentFile ?: continue
                    // Already a compact AAC file at or below the target bitrate: nothing to gain.
                    if (AudioTranscoder.isAlreadyCompact(sourceFile, bitrateKbps)) {
                        dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length())
                        _status.value = _status.value.copy(episodePercent = 100)
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
                        shouldCancel = { isStopped },
                        onProgress = { pct ->
                            _status.value = TranscodeStatus(
                                isActive = true,
                                currentIndex = processed,
                                totalCount = targets.size,
                                episodeTitle = ep.title,
                                episodePercent = pct
                            )
                        }
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
            } finally {
                _status.value = TranscodeStatus()
            }
        }
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val UNIQUE_BATCH_WORK = "transcode_all_downloads"
        private const val NOTIFICATION_ID = 4102
        private val transcodeMutex = Mutex()

        private val _status = MutableStateFlow(TranscodeStatus())
        val status: StateFlow<TranscodeStatus> = _status.asStateFlow()

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
