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

    fun setRefreshInterval(hours: Int) {
        app.preferences.setRefreshIntervalHours(hours)
        FeedRefreshWorker.schedulePeriodic(app, hours, replaceExisting = true)
    }

    fun setWifiOnly(wifiOnly: Boolean) = app.preferences.setWifiOnlyDownloads(wifiOnly)
    fun setAutoDownloadDefault(enabled: Boolean) = app.preferences.setAutoDownloadNewByDefault(enabled)
    fun setDownloadWhenQueued(enabled: Boolean) = app.preferences.setDownloadWhenQueued(enabled)
    fun setAddToQueueWhenDownloaded(enabled: Boolean) = app.preferences.setAddToQueueWhenDownloaded(enabled)

    fun setAutoRemovePlayedDays(days: Int) {
        app.preferences.setAutoRemovePlayedDays(days)
        viewModelScope.launch {
            app.repository.cleanupPlayedDownloads(app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun setTrashRetentionHours(hours: Int) {
        app.preferences.setTrashRetentionHours(hours)
        viewModelScope.launch {
            app.repository.cleanupPlayedDownloads(app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun setMaxDownloadsPerPodcast(limit: Int) {
        app.preferences.setMaxDownloadsPerPodcast(limit)
        viewModelScope.launch {
            app.repository.cleanupPlayedDownloads(app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun setSmartRewind(secs: Int) = app.preferences.setSmartRewindSeconds(secs)

    fun setAudioNormalization(enabled: Boolean) {
        app.preferences.setAudioNormalizationEnabled(enabled)
        app.playerController.setAudioNormalization(enabled)
    }

    fun setBluetoothNextPrevSeeks(seeks: Boolean) = app.preferences.setBluetoothNextPrevSeeks(seeks)
    fun setTextScale(scale: Float) = app.preferences.setTextScale(scale)

    fun setTranscodeBitrateKbps(kbps: Int) = app.preferences.setTranscodeBitrateKbps(kbps)
    fun setTranscodeAutoEnabled(enabled: Boolean) = app.preferences.setTranscodeAutoEnabled(enabled)
    fun setTranscodeChargingOnly(chargingOnly: Boolean) = app.preferences.setTranscodeChargingOnly(chargingOnly)

    fun transcodeAllNow(onStatus: (String) -> Unit) {
        val settings = app.preferences.settings.value
        if (settings.transcodeBitrateKbps <= 0) {
            onStatus(app.getString(com.lycoslane.purepodcast.R.string.snackbar_compression_select_bitrate))
            return
        }
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                com.lycoslane.purepodcast.core.database.PurePodcastDatabase.getInstance(app)
                    .dao()
                    .getUntranscodedDownloads()
                    .filter { it.localFilePath?.startsWith("content://") != true }
            }
            if (pending.isEmpty()) {
                onStatus(app.getString(com.lycoslane.purepodcast.R.string.snackbar_compression_nothing_to_do))
            } else {
                com.lycoslane.purepodcast.core.download.TranscodeWorker.enqueueAllNow(
                    context = app,
                    chargingOnly = settings.transcodeChargingOnly
                )
                onStatus(app.getString(com.lycoslane.purepodcast.R.string.snackbar_compression_queued_count, pending.size))
            }
        }
    }

    fun setLocalFolderUri(uri: String) {
        val old = app.preferences.settings.value.localFolderUri
        if (old.isNotBlank() && old != uri) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
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

    fun unhideEpisode(episodeId: Long) {
        viewModelScope.launch {
            app.repository.toggleHidden(episodeId, false, app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun toggleFavorite(ep: com.lycoslane.purepodcast.core.database.EpisodeEntity) {
        viewModelScope.launch { app.repository.setFavorite(ep.id, !ep.isFavorite) }
    }

    fun downloadEpisode(episodeId: Long) {
        viewModelScope.launch { app.repository.enqueueDownload(episodeId) }
    }

    fun deleteDownload(episodeId: Long) {
        viewModelScope.launch {
            app.repository.deleteDownload(episodeId, app.playerController.state.value.currentEpisode?.id)
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
                _backupStatus.value = app.getString(R.string.backup_exported)
            }.onFailure { err ->
                _backupStatus.value = app.getString(
                    R.string.backup_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
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
                    ?: error(app.getString(R.string.backup_empty_file))
                app.repository.importBackupJson(text)
            }.onSuccess { count ->
                _backupStatus.value = app.getString(R.string.backup_imported, count)
            }.onFailure { err ->
                _backupStatus.value = app.getString(
                    R.string.backup_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
                )
            }
        }
    }
}
