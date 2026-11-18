package com.lycoslane.purepodcast.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.network.ParsedEpisode
import com.lycoslane.purepodcast.core.network.ParsedPodcastFeed
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.core.network.RssFeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val app: PurePodcastApplication
        get() = getApplication()

    val query: StateFlow<String> = savedStateHandle.getStateFlow("search_query", "")

    private val _results = MutableStateFlow<List<PodcastSearchResult>>(emptyList())
    val results: StateFlow<List<PodcastSearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var searchJob: Job? = null
    private var lastSearchedQuery: String? = null

    fun onQueryChange(newQuery: String) {
        savedStateHandle["search_query"] = newQuery
        if (newQuery.trim().length < 2) {
            searchJob?.cancel()
            lastSearchedQuery = null
            _isSearching.value = false
            _results.value = emptyList()
        }
    }

    fun search(q: String) {
        val trimmed = q.trim()
        if (trimmed == lastSearchedQuery && _results.value.isNotEmpty()) return
        searchJob?.cancel()
        lastSearchedQuery = trimmed
        searchJob = viewModelScope.launch {
            val currentJob = coroutineContext[kotlinx.coroutines.Job]
            _isSearching.value = true
            _statusMessage.value = null
            try {
                _results.value = runCatching {
                    app.repository.searchPodcasts(trimmed).distinctBy { it.feedUrl }
                }.getOrElse { err ->
                    lastSearchedQuery = null
                    _statusMessage.value = app.getString(
                        R.string.status_search_failed,
                        err.localizedMessage ?: app.getString(R.string.error_generic_network)
                    )
                    emptyList()
                }
            } finally {
                if (searchJob === currentJob) {
                    _isSearching.value = false
                }
            }
        }
    }

    suspend fun loadFeedPreview(url: String): ParsedPodcastFeed? =
        runCatching { RssFeedParser.fetchAndParse(url) }.getOrNull()

    fun subscribeFeedUrl(url: String, onSubscribed: (Long) -> Unit) {
        viewModelScope.launch {
            _statusMessage.value = app.getString(R.string.status_subscribing)
            runCatching { app.repository.subscribeByFeedUrl(url) }
                .onSuccess { podcastId ->
                    _statusMessage.value = app.getString(R.string.status_subscribed)
                    onSubscribed(podcastId)
                }
                .onFailure { err ->
                    _statusMessage.value = app.getString(
                        R.string.status_search_failed,
                        err.localizedMessage ?: app.getString(R.string.error_generic_network)
                    )
                }
        }
    }

    fun unsubscribeById(podcastId: Long, title: String) {
        viewModelScope.launch {
            app.repository.unsubscribePodcast(podcastId)
            _statusMessage.value = app.getString(R.string.status_unsubscribed, title)
        }
    }

    fun addPreviewEpisodeToQueue(
        feed: ParsedPodcastFeed,
        episode: ParsedEpisode,
        toFront: Boolean,
        onDone: (podcastId: Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                app.repository.ensurePodcastFromPreviewAndAct(
                    feed = feed,
                    episode = episode,
                    addToQueueFront = toFront,
                    download = false
                )
            }.onSuccess { (podcastId, _) ->
                _statusMessage.value = app.getString(
                    if (toFront) R.string.snackbar_added_to_queue_front else R.string.snackbar_added_to_queue
                )
                onDone(podcastId)
            }.onFailure { err ->
                _statusMessage.value = app.getString(
                    R.string.status_search_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
                )
            }
        }
    }

    fun downloadPreviewEpisode(
        feed: ParsedPodcastFeed,
        episode: ParsedEpisode,
        onDone: (podcastId: Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                app.repository.ensurePodcastFromPreviewAndAct(
                    feed = feed,
                    episode = episode,
                    addToQueueFront = null,
                    download = true
                )
            }.onSuccess { (podcastId, _) ->
                _statusMessage.value = app.getString(R.string.snackbar_download_started)
                onDone(podcastId)
            }.onFailure { err ->
                _statusMessage.value = app.getString(
                    R.string.status_search_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
                )
            }
        }
    }

    fun openPreviewPodcast(
        feed: ParsedPodcastFeed,
        onOpenPodcast: (Long) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                app.repository.ensurePodcastFromPreviewAndAct(
                    feed = feed,
                    episode = null,
                    addToQueueFront = null,
                    download = false
                )
            }.onSuccess { (podcastId, _) ->
                onOpenPodcast(podcastId)
            }.onFailure { err ->
                _statusMessage.value = app.getString(
                    R.string.status_search_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
                )
            }
        }
    }

    fun importOpmlUri(uri: Uri) {
        viewModelScope.launch {
            _statusMessage.value = app.getString(R.string.status_opml_importing)
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        app.repository.importOpml(stream)
                    } ?: 0
                }
            }.onSuccess { count ->
                _statusMessage.value = app.getString(R.string.status_opml_imported, count)
            }.onFailure { err ->
                _statusMessage.value = app.getString(
                    R.string.status_opml_failed,
                    err.localizedMessage ?: app.getString(R.string.error_generic_network)
                )
            }
        }
    }
}
