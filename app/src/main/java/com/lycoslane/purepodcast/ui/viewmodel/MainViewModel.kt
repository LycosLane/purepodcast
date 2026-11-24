package com.lycoslane.purepodcast.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.database.PodcastWithStats
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.core.preferences.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: PurePodcastApplication
        get() = getApplication()

    private fun localizedString(@androidx.annotation.StringRes resId: Int, vararg args: Any): String =
        LocaleHelper.wrapContext(app).getString(resId, *args)

    val podcasts: StateFlow<List<PodcastWithStats>> = app.repository.observePodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val queue: StateFlow<List<EpisodeWithPodcast>> = app.repository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // Debounced: playback position writes invalidate the episodes table every few seconds.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val allEpisodes: StateFlow<List<EpisodeWithPodcast>> = app.repository.observeAllEpisodes()
        .debounce(400L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val settings: StateFlow<AppSettings> = app.preferences.settings

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    val transcodeStatus: StateFlow<com.lycoslane.purepodcast.core.download.TranscodeStatus> =
        com.lycoslane.purepodcast.core.download.TranscodeWorker.status

    private var wifiOnlyDelayJob: kotlinx.coroutines.Job? = null
    private var queueDownloadDelayJob: kotlinx.coroutines.Job? = null

    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                app.repository.refreshAllPodcasts()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setRefreshIntervalMinutes(minutes: Int) {
        app.preferences.setRefreshIntervalMinutes(minutes)
        FeedRefreshWorker.schedulePeriodic(app, app.preferences.settings.value.refreshIntervalMinutes, replaceExisting = true)
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        app.preferences.setWifiOnlyDownloads(wifiOnly)
        wifiOnlyDelayJob?.cancel()
        if (!wifiOnly) {
            wifiOnlyDelayJob = viewModelScope.launch {
                kotlinx.coroutines.delay(8_000L)
                if (!app.preferences.settings.value.wifiOnlyDownloads) {
                    app.repository.enqueueWaitingCellularDownloads()
                }
            }
        }
    }

    fun setAutoDownloadDefault(enabled: Boolean) = app.preferences.setAutoDownloadNewByDefault(enabled)

    fun setDownloadWhenQueued(enabled: Boolean) {
        app.preferences.setDownloadWhenQueued(enabled)
        queueDownloadDelayJob?.cancel()
        if (enabled) {
            queueDownloadDelayJob = viewModelScope.launch {
                kotlinx.coroutines.delay(8_000L)
                if (app.preferences.settings.value.downloadWhenQueued) {
                    app.repository.enqueueMissingQueueDownloads()
                }
            }
        }
    }

    fun flushPendingDownloadSettings() {
        val runWifi = wifiOnlyDelayJob?.isActive == true
        wifiOnlyDelayJob?.cancel()
        wifiOnlyDelayJob = null
        val runQueue = queueDownloadDelayJob?.isActive == true
        queueDownloadDelayJob?.cancel()
        queueDownloadDelayJob = null
        if (runWifi && !app.preferences.settings.value.wifiOnlyDownloads) {
            viewModelScope.launch { app.repository.enqueueWaitingCellularDownloads() }
        }
        if (runQueue && app.preferences.settings.value.downloadWhenQueued) {
            viewModelScope.launch { app.repository.enqueueMissingQueueDownloads() }
        }
    }

    fun setAddToQueueWhenDownloaded(enabled: Boolean) = app.preferences.setAddToQueueWhenDownloaded(enabled)

    fun setNotifyNewEpisodes(enabled: Boolean) = app.preferences.setNotifyNewEpisodes(enabled)

    fun addAllDownloadedToQueue(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = app.repository.addAllDownloadedToQueue()
            onDone(count)
        }
    }

    fun setAutoRemovePlayedDays(days: Int) {
        app.preferences.setAutoRemovePlayedDays(days)
    }

    fun setTrashRetentionHours(hours: Int) {
        app.preferences.setTrashRetentionHours(hours)
    }

    fun setMaxDownloadsPerPodcast(limit: Int) {
        app.preferences.setMaxDownloadsPerPodcast(limit)
    }

    fun previewCleanupImpact(onResult: (com.lycoslane.purepodcast.domain.repository.CleanupPreview) -> Unit) {
        viewModelScope.launch {
            val preview = app.repository.previewCleanupImpact(app.playerController.state.value.currentEpisode?.id)
            onResult(preview)
        }
    }

    fun runCleanupNow(bypassTrash: Boolean = true) {
        viewModelScope.launch {
            app.repository.cleanupPlayedDownloads(
                activeEpisodeId = app.playerController.state.value.currentEpisode?.id,
                bypassTrash = bypassTrash
            )
        }
    }

    fun setSmartRewind(secs: Int) {
        app.preferences.setSmartRewindSeconds(secs)
        app.playerController.refreshEffectiveAudioSettings()
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        app.preferences.setDefaultPlaybackSpeed(speed)
        app.playerController.refreshEffectiveAudioSettings()
    }

    fun setDefaultSilenceMode(enabled: Boolean, intensity: Int) {
        app.preferences.setDefaultSilenceMode(enabled, intensity)
        app.playerController.refreshEffectiveAudioSettings()
    }

    fun setAudioNormalization(enabled: Boolean) {
        app.preferences.setAudioNormalizationEnabled(enabled)
        app.playerController.refreshEffectiveAudioSettings()
    }

    fun setMonoMode(mode: Int) {
        app.preferences.setMonoMode(mode)
        app.playerController.refreshEffectiveAudioSettings()
    }

    fun applyPodcastAudioSettingsToAll(
        podcast: com.lycoslane.purepodcast.core.database.PodcastEntity,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            app.repository.applyAudioSettingsToAllPodcasts(podcast)
            app.playerController.refreshAfterApplyAll()
            onDone()
        }
    }

    fun setBluetoothNextPrevSeeks(seeks: Boolean) = app.preferences.setBluetoothNextPrevSeeks(seeks)
    fun setTextScale(scale: Float) = app.preferences.setTextScale(scale)

    fun setTranscodeBitrateKbps(kbps: Int) {
        app.preferences.setTranscodeBitrateKbps(kbps)
        val s = app.preferences.settings.value
        if (kbps > 0 && s.transcodeAutoEnabled) {
            com.lycoslane.purepodcast.core.download.TranscodeWorker.enqueueAllNow(app, s.transcodeChargingOnly)
        }
    }

    fun setTranscodeAutoEnabled(enabled: Boolean) {
        app.preferences.setTranscodeAutoEnabled(enabled)
        val s = app.preferences.settings.value
        if (enabled && s.transcodeBitrateKbps > 0) {
            com.lycoslane.purepodcast.core.download.TranscodeWorker.enqueueAllNow(app, s.transcodeChargingOnly)
        }
    }

    fun setTranscodeChargingOnly(chargingOnly: Boolean) {
        app.preferences.setTranscodeChargingOnly(chargingOnly)
        val s = app.preferences.settings.value
        if (s.transcodeAutoEnabled && s.transcodeBitrateKbps > 0) {
            com.lycoslane.purepodcast.core.download.TranscodeWorker.enqueueAllNow(app, chargingOnly)
        }
    }

    fun transcodeAllNow(onStatus: (String) -> Unit) {
        com.lycoslane.purepodcast.core.download.TranscodeWorker.runAllNowImmediate(
            context = app,
            onFinished = onStatus
        )
    }

    fun setLocalFolderUri(uri: String) {
        val old = app.preferences.settings.value.localFolderUri
        if (old != uri && app.playerController.state.value.currentPodcast?.isLocal == true) {
            app.playerController.stopAndClear()
        }
        if (old.isNotBlank() && old != uri) {
            val oldUri = android.net.Uri.parse(old)
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    oldUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    oldUri,
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        app.preferences.setLocalFolderUri(uri)
        viewModelScope.launch {
            runCatching { app.repository.syncLocalFolder() }
        }
    }

    fun setShowSubscribedPodcasts(show: Boolean) = app.preferences.setShowSubscribedPodcasts(show)
    fun setShowLocalPodcasts(show: Boolean) = app.preferences.setShowLocalPodcasts(show)
    fun setThemeMode(mode: String) = app.preferences.setThemeMode(mode)
    fun setLightAccent(accent: String) = app.preferences.setLightAccent(accent)
    fun setDarkAccent(accent: String) = app.preferences.setDarkAccent(accent)

    fun unhideEpisode(episodeId: Long) {
        viewModelScope.launch {
            app.repository.toggleHidden(episodeId, false, app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun toggleFavorite(ep: com.lycoslane.purepodcast.core.database.EpisodeEntity) {
        viewModelScope.launch { app.repository.setFavorite(ep.id, !ep.isFavorite) }
    }

    fun downloadEpisode(episodeId: Long, allowCellularOnce: Boolean = false) {
        viewModelScope.launch { app.repository.enqueueDownload(episodeId, allowCellularOnce) }
    }

    fun deleteDownload(episodeId: Long) {
        viewModelScope.launch {
            app.repository.deleteDownload(episodeId, app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun stopAndDeleteActive(episodeId: Long) {
        viewModelScope.launch {
            app.playerController.stopAndClear()
            app.repository.deleteDownload(episodeId, null)
        }
    }

    fun restoreFromTrash(episodeId: Long) {
        viewModelScope.launch { app.repository.restoreFromTrash(episodeId) }
    }

    fun togglePlayed(ep: com.lycoslane.purepodcast.core.database.EpisodeEntity) {
        viewModelScope.launch {
            app.repository.togglePlayed(ep.id, !ep.isPlayed, app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun setHidden(episodeId: Long, hidden: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            app.repository.toggleHidden(episodeId, hidden, app.playerController.state.value.currentEpisode?.id)
            onDone()
        }
    }

    fun addToQueueEnd(episodeId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            app.repository.addToQueue(episodeId)
            onDone()
        }
    }

    fun addToQueueFront(episodeId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            app.repository.addToQueueFront(episodeId)
            onDone()
        }
    }

    fun removeFromQueue(episodeId: Long) {
        viewModelScope.launch { app.repository.removeFromQueue(episodeId) }
    }

    fun reorderQueue(episodeIds: List<Long>) {
        viewModelScope.launch { app.repository.reorderQueue(episodeIds) }
    }

    fun moveEpisodeNextInQueue(episodeId: Long, currentEpisodeId: Long?) {
        viewModelScope.launch { app.repository.moveEpisodeNextInQueue(episodeId, currentEpisodeId) }
    }

    fun removeMultipleFromQueue(episodeIds: List<Long>) {
        viewModelScope.launch { app.repository.removeMultipleFromQueue(episodeIds) }
    }

    fun deleteDownloadsKeepInQueue(episodeIds: List<Long>) {
        viewModelScope.launch {
            app.repository.deleteDownloadsKeepInQueue(
                episodeIds = episodeIds,
                activeEpisodeId = app.playerController.state.value.currentEpisode?.id
            )
        }
    }

    fun deleteDownloadsAndRemoveFromQueue(episodeIds: List<Long>) {
        viewModelScope.launch {
            app.repository.deleteDownloadsAndRemoveFromQueue(
                episodeIds = episodeIds,
                activeEpisodeId = app.playerController.state.value.currentEpisode?.id
            )
        }
    }

    fun clearQueue() {
        viewModelScope.launch { app.repository.clearQueue() }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = app.repository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output")
                }
            }.onSuccess {
                _backupStatus.value = localizedString(R.string.backup_exported)
            }.onFailure { err ->
                _backupStatus.value = localizedString(
                    R.string.backup_failed,
                    err.localizedMessage ?: localizedString(R.string.error_generic_network)
                )
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_BACKUP_BYTES) {
                                error("Backup too large")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }?.takeIf { it.isNotBlank() }
                    ?: error(localizedString(R.string.backup_empty_file))
                app.repository.importBackupJson(text)
            }.onSuccess { count ->
                app.playerController.refreshAfterApplyAll()
                _backupStatus.value = localizedString(R.string.backup_imported, count)
            }.onFailure { err ->
                _backupStatus.value = localizedString(
                    R.string.backup_failed,
                    err.localizedMessage ?: localizedString(R.string.error_generic_network)
                )
            }
        }
    }
}
