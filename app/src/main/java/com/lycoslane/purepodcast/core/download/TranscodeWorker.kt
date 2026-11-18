package com.lycoslane.purepodcast.core.download

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class TranscodeStatus(
    val isActive: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val episodeTitle: String = "",
    val episodePercent: Int = 0,
    val statusMessage: String = ""
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
        val singleEpisodeId = inputData.getLong(KEY_EPISODE_ID, -1L)
        executeBatch(
            appContext = applicationContext,
            singleEpisodeId = singleEpisodeId,
            manualTrigger = false,
            shouldStop = { isStopped }
        )
    }

    companion object {
        private const val KEY_EPISODE_ID = "episode_id"
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024
        private const val UNIQUE_BATCH_WORK = "transcode_all_downloads"
        private const val NOTIFICATION_ID = 4102
        private val transcodeMutex = Mutex()

        private val _status = MutableStateFlow(TranscodeStatus())
        val status: StateFlow<TranscodeStatus> = _status.asStateFlow()

        private fun isDeviceCharging(context: Context): Boolean {
            return runCatching {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
            }.getOrDefault(false)
        }

        suspend fun backfillLegacySavedBytes(dao: com.lycoslane.purepodcast.core.database.PurePodcastDao) {
            val legacyCompressed = runCatching { dao.getAllDownloadedEpisodes() }
                .getOrDefault(emptyList())
                .filter { ep ->
                    ep.isTranscoded &&
                        ep.transcodeSavedBytes <= 0L &&
                        ep.localFilePath?.endsWith(".m4a", ignoreCase = true) == true
                }
            for (ep in legacyCompressed) {
                val path = ep.localFilePath ?: continue
                val file = File(path)
                val currentLen = if (file.exists() && file.length() > 0L) file.length() else ep.fileSizeBytes
                if (currentLen <= 0L) continue
                var originalSize = 0L
                if (ep.audioUrl.startsWith("http", ignoreCase = true)) {
                    runCatching {
                        val conn = com.lycoslane.purepodcast.core.network.NetworkSecurity.openValidatedConnection(
                            rawUrl = ep.audioUrl,
                            connectTimeoutMs = 4_000,
                            readTimeoutMs = 4_000,
                            headers = mapOf("Range" to "bytes=0-0")
                        )
                        try {
                            val contentRange = conn.getHeaderField("Content-Range").orEmpty()
                            val slashIdx = contentRange.lastIndexOf('/')
                            val rangeTotal = if (slashIdx >= 0) {
                                contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: 0L
                            } else {
                                0L
                            }
                            originalSize = if (rangeTotal > 0L) {
                                rangeTotal
                            } else {
                                conn.contentLengthLong.coerceAtLeast(0L)
                            }
                        } finally {
                            runCatching { conn.disconnect() }
                        }
                    }
                }
                val effectiveDurationMs = if (ep.durationMs > 0L) {
                    ep.durationMs
                } else {
                    AudioTranscoder.readDurationMs(file)
                }
                if (originalSize <= currentLen && effectiveDurationMs > 0L) {
                    // Fallback estimate based on standard 128 kbps MP3 source
                    val est128 = (effectiveDurationMs * 128_000L) / 8_000L
                    if (est128 > currentLen) {
                        originalSize = est128
                    }
                }
                if (originalSize > currentLen) {
                    dao.markEpisodeTranscoded(
                        episodeId = ep.id,
                        newPath = path,
                        newSizeBytes = currentLen,
                        savedBytes = originalSize - currentLen
                    )
                }
            }
        }

        suspend fun executeBatch(
            appContext: Context,
            singleEpisodeId: Long = -1L,
            manualTrigger: Boolean = false,
            shouldStop: () -> Boolean = { false }
        ): Result = withContext(Dispatchers.IO) {
            transcodeMutex.withLock {
                var completionMsg = _status.value.statusMessage
                try {
                    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    runCatching {
                        nm?.cancel(NOTIFICATION_ID)
                        nm?.cancel(NOTIFICATION_ID + 1)
                    }

                    val app = appContext.applicationContext as PurePodcastApplication
                    val settings = app.preferences.settings.value
                    val bitrateKbps = settings.transcodeBitrateKbps
                    if (bitrateKbps <= 0) {
                        if (manualTrigger) {
                            completionMsg = app.getString(R.string.snackbar_compression_select_bitrate)
                        }
                        return@withLock Result.success()
                    }

                    val dao = PurePodcastDatabase.getInstance(app).dao()
                    backfillLegacySavedBytes(dao)

                    val candidates = when {
                        singleEpisodeId > 0L -> listOfNotNull(dao.getEpisode(singleEpisodeId))
                        manualTrigger -> dao.getAllDownloadedEpisodes()
                        else -> dao.getUntranscodedDownloads()
                    }.filter {
                        it.downloadState == DownloadState.DOWNLOADED &&
                            it.localFilePath?.startsWith("content://") != true
                    }

                    if (candidates.isEmpty()) {
                        if (manualTrigger) {
                            val totalDownloaded = dao.getAllDownloadedEpisodes()
                                .count { it.localFilePath?.startsWith("content://") != true }
                            completionMsg = if (totalDownloaded == 0) {
                                app.getString(R.string.compression_no_downloads)
                            } else {
                                app.getString(R.string.snackbar_compression_nothing_to_do)
                            }
                        }
                        return@withLock Result.success()
                    }

                    // Filter candidates down to files that actually need compression at targetBitrateKbps
                    val targets = candidates.filter { ep ->
                        val path = ep.localFilePath ?: return@filter false
                        val file = File(path)
                        if (!file.exists() || file.length() <= 0L) return@filter false
                        if (AudioTranscoder.isAlreadyCompact(file, bitrateKbps)) {
                            if (!ep.isTranscoded) {
                                dao.markEpisodeTranscoded(ep.id, file.absolutePath, file.length(), ep.transcodeSavedBytes)
                            }
                            return@filter false
                        }
                        if (ep.isTranscoded) {
                            // If marked transcoded earlier, only re-attempt during manual trigger if file is still larger than target bitrate
                            if (!manualTrigger) return@filter false
                            if (ep.durationMs > 0L) {
                                val avgKbps = (file.length() * 8L) / ep.durationMs
                                if (avgKbps <= bitrateKbps + 8) return@filter false
                            } else {
                                return@filter false
                            }
                        }
                        true
                    }

                    if (targets.isEmpty()) {
                        if (manualTrigger) {
                            completionMsg = app.getString(R.string.snackbar_compression_nothing_to_do)
                        }
                        return@withLock Result.success()
                    }

                    var processed = 0
                    var compressedCount = 0
                    var skippedCount = 0
                    var savedBytes = 0L

                    for (ep in targets) {
                        if (shouldStop()) break
                        processed++
                        _status.value = TranscodeStatus(
                            isActive = true,
                            currentIndex = processed,
                            totalCount = targets.size,
                            episodeTitle = ep.title,
                            episodePercent = 0,
                            statusMessage = app.getString(R.string.snackbar_compression_queued_count, targets.size)
                        )

                        if (ep.id == app.playerController.state.value.currentEpisode?.id) {
                            skippedCount++
                            continue
                        }
                        val path = ep.localFilePath
                        if (path == null) {
                            skippedCount++
                            continue
                        }

                        val sourceFile = File(path)
                        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                            skippedCount++
                            continue
                        }

                        val parentDir = sourceFile.parentFile
                        if (parentDir == null) {
                            skippedCount++
                            continue
                        }
                        if (AudioTranscoder.isAlreadyCompact(sourceFile, bitrateKbps)) {
                            dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length(), ep.transcodeSavedBytes)
                            _status.value = _status.value.copy(episodePercent = 100)
                            continue
                        }
                        if (parentDir.usableSpace < sourceFile.length() + MIN_FREE_BYTES) {
                            skippedCount++
                            continue
                        }
                        val targetM4a = File(parentDir, "${ep.id}.m4a")
                        val tempFile = File(parentDir, "${ep.id}.m4a.tmp")
                        val originalSize = sourceFile.length()

                        val ok = AudioTranscoder.transcodeToAacM4a(
                            sourceFile = sourceFile,
                            tempOutputFile = tempFile,
                            targetBitrateKbps = bitrateKbps,
                            shouldCancel = shouldStop,
                            onProgress = { pct ->
                                _status.value = TranscodeStatus(
                                    isActive = true,
                                    currentIndex = processed,
                                    totalCount = targets.size,
                                    episodeTitle = ep.title,
                                    episodePercent = pct,
                                    statusMessage = ""
                                )
                            }
                        )

                        if (ok && tempFile.exists() && tempFile.length() > 0L) {
                            val fresh = dao.getEpisode(ep.id)
                            val playingNow = app.playerController.state.value.currentEpisode?.id == ep.id
                            if (playingNow || fresh == null ||
                                fresh.downloadState != DownloadState.DOWNLOADED ||
                                fresh.markedForDeletionAtMs > 0L ||
                                fresh.localFilePath != path
                            ) {
                                tempFile.delete()
                                skippedCount++
                                continue
                            }
                            if (tempFile.length() < originalSize) {
                                val newSize = tempFile.length()
                                if (targetM4a.exists() && targetM4a.absolutePath != sourceFile.absolutePath) {
                                    targetM4a.delete()
                                }
                                val moved = tempFile.renameTo(targetM4a) || run {
                                    tempFile.copyTo(targetM4a, overwrite = true)
                                    tempFile.delete()
                                    true
                                }
                                if (moved) {
                                    val savedForEp = (originalSize - newSize).coerceAtLeast(0L)
                                    val totalSavedForEp = ep.transcodeSavedBytes + savedForEp
                                    dao.markEpisodeTranscoded(ep.id, targetM4a.absolutePath, targetM4a.length(), totalSavedForEp)
                                    if (sourceFile.absolutePath != targetM4a.absolutePath) {
                                        sourceFile.delete()
                                    }
                                    compressedCount++
                                    savedBytes += savedForEp
                                } else {
                                    skippedCount++
                                }
                            } else {
                                // Transcoded file wasn't smaller (source already low-bitrate); keep original
                                tempFile.delete()
                                dao.markEpisodeTranscoded(ep.id, sourceFile.absolutePath, sourceFile.length(), ep.transcodeSavedBytes)
                            }
                        } else {
                            tempFile.delete()
                            skippedCount++
                        }
                    }

                    completionMsg = when {
                        compressedCount > 0 -> {
                            val mbStr = String.format(Locale.getDefault(), "%.1f", savedBytes / (1024.0 * 1024.0))
                            app.getString(R.string.compression_done_saved, compressedCount, mbStr)
                        }
                        skippedCount > 0 -> {
                            app.getString(R.string.compression_failed_summary, skippedCount)
                        }
                        else -> {
                            app.getString(R.string.snackbar_compression_nothing_to_do)
                        }
                    }

                    Result.success()
                } finally {
                    _status.value = TranscodeStatus(
                        isActive = false,
                        statusMessage = completionMsg
                    )
                }
            }
        }

        fun cancelForEpisode(context: Context, episodeId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("transcode_ep_$episodeId")
        }

        fun enqueueSingle(context: Context, episodeId: Long, chargingOnly: Boolean) {
            val app = context.applicationContext as? PurePodcastApplication
            if (app != null && (!chargingOnly || isDeviceCharging(app))) {
                app.appScope.launch {
                    executeBatch(appContext = app, singleEpisodeId = episodeId, manualTrigger = false)
                }
                return
            }
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
            val app = context.applicationContext as? PurePodcastApplication
            if (app != null && (!chargingOnly || isDeviceCharging(app))) {
                app.appScope.launch {
                    executeBatch(appContext = app, manualTrigger = false)
                }
                return
            }
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

        /**
         * Immediately runs compression for all downloaded episodes in the foreground app scope,
         * ignoring background charging-only constraints since the user explicitly clicked "Compress all now".
         */
        fun runAllNowImmediate(context: Context, onFinished: (String) -> Unit = {}) {
            val app = context.applicationContext as PurePodcastApplication
            runCatching {
                WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_BATCH_WORK)
            }
            app.appScope.launch {
                executeBatch(appContext = app, manualTrigger = true)
                val msg = _status.value.statusMessage
                if (msg.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        onFinished(msg)
                    }
                }
            }
        }
    }
}
