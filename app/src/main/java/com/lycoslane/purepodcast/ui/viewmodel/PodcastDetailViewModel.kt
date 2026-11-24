package com.lycoslane.purepodcast.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val app: PurePodcastApplication
        get() = getApplication()

    private val podcastId: Long = savedStateHandle.get<Long>("podcastId") ?: -1L

    val podcast: StateFlow<PodcastEntity?> = app.repository.observePodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val episodes: StateFlow<List<EpisodeEntity>> = podcast
        .map { it?.sortAscending ?: false }
        .distinctUntilChanged()
        .flatMapLatest { asc -> app.repository.observeEpisodes(podcastId, asc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value || podcastId < 0L) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                runCatching { app.repository.refreshPodcast(podcastId) }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleSort(ascending: Boolean) {
        if (podcastId < 0L) return
        viewModelScope.launch { app.repository.toggleSortAscending(podcastId, ascending) }
    }

    fun setSpeedOverride(speedOverride: Float) {
        if (podcastId < 0L) return
        app.playerController.setPodcastSpeedOverride(podcastId, speedOverride)
    }

    fun setSilenceModeOverride(silenceModeOverride: Int) {
        if (podcastId < 0L) return
        app.playerController.setPodcastSilenceOverride(podcastId, silenceModeOverride)
    }

    fun setSmartRewindOverride(smartRewindOverride: Int) {
        if (podcastId < 0L) return
        app.playerController.setPodcastSmartRewindOverride(podcastId, smartRewindOverride)
    }

    fun setMonoModeOverride(mode: Int) {
        if (podcastId < 0L) return
        app.playerController.setPodcastMonoOverride(podcastId, mode)
    }

    fun setAudioNormalizationOverride(override: Int) {
        if (podcastId < 0L) return
        app.playerController.setPodcastNormalizationOverride(podcastId, override)
    }

    fun applyAudioSettingsToAll(onDone: () -> Unit = {}) {
        val currentPod = podcast.value ?: return
        viewModelScope.launch {
            app.repository.applyAudioSettingsToAllPodcasts(currentPod)
            app.playerController.refreshAfterApplyAll()
            onDone()
        }
    }

    fun disableAutoDownload() {
        if (podcastId < 0L) return
        viewModelScope.launch { app.repository.disableAutoDownload(podcastId) }
    }

    fun subscribe() {
        if (podcastId < 0L) return
        viewModelScope.launch { app.repository.subscribePodcast(podcastId) }
    }

    fun unsubscribe(onDone: () -> Unit) {
        if (podcastId < 0L) return
        viewModelScope.launch {
            if (app.playerController.state.value.currentPodcast?.id == podcastId) {
                app.playerController.stopAndClear()
            }
            app.repository.unsubscribePodcast(podcastId)
            onDone()
        }
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
            app.repository.deleteDownload(episodeId, activeEpisodeId = null, immediate = false)
        }
    }

    fun restoreFromTrash(episodeId: Long) {
        viewModelScope.launch { app.repository.restoreFromTrash(episodeId) }
    }

    fun deletePermanently(episodeId: Long) {
        viewModelScope.launch {
            if (episodeId == app.playerController.state.value.currentEpisode?.id) {
                app.playerController.stopAndClear()
            }
            app.repository.deleteDownload(episodeId, activeEpisodeId = null, immediate = true)
        }
    }

    fun emptyTrash() {
        if (podcastId < 0L) return
        viewModelScope.launch {
            app.repository.emptyTrash(podcastId, app.playerController.state.value.currentEpisode?.id)
        }
    }

    fun addToQueueEnd(episodeId: Long, onAdded: () -> Unit) {
        viewModelScope.launch {
            app.repository.addToQueue(episodeId)
            onAdded()
        }
    }

    fun addToQueueFront(episodeId: Long, onAdded: () -> Unit) {
        viewModelScope.launch {
            app.repository.addToQueueFront(episodeId)
            onAdded()
        }
    }

    fun togglePlayed(episode: EpisodeEntity) {
        viewModelScope.launch {
            app.repository.togglePlayed(
                episode.id,
                !episode.isPlayed,
                app.playerController.state.value.currentEpisode?.id
            )
        }
    }

    fun setHidden(episodeId: Long, hidden: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            app.repository.toggleHidden(
                episodeId,
                hidden,
                app.playerController.state.value.currentEpisode?.id
            )
            onDone()
        }
    }

    fun downloadNewerFrom(pubDateMs: Long) {
        if (podcastId < 0L) return
        viewModelScope.launch { app.repository.downloadNewerFromEpisode(podcastId, pubDateMs) }
    }

    fun cleanupOlderThan(pubDateMs: Long) {
        if (podcastId < 0L) return
        viewModelScope.launch {
            app.repository.cleanupOlderThan(
                podcastId,
                pubDateMs,
                app.playerController.state.value.currentEpisode?.id
            )
        }
    }
}
