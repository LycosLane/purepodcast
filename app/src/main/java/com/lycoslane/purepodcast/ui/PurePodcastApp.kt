package com.lycoslane.purepodcast.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.ui.detail.PodcastDetailScreen
import com.lycoslane.purepodcast.ui.home.HomeScreen
import com.lycoslane.purepodcast.ui.player.FullPlayerSheet
import com.lycoslane.purepodcast.ui.player.MiniPlayer
import com.lycoslane.purepodcast.ui.queue.QueueScreen
import com.lycoslane.purepodcast.ui.search.SearchScreen
import kotlinx.coroutines.launch

@Composable
fun PurePodcastApp(app: PurePodcastApplication) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val podcasts by app.repository.observePodcasts().collectAsStateWithLifecycle(initialValue = emptyList())
    val queue by app.repository.observeQueue().collectAsStateWithLifecycle(initialValue = emptyList())
    val playerState by app.playerController.state.collectAsStateWithLifecycle()
    val settings by app.preferences.settings.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var isRefreshingDetail by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<PodcastSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(
                    state = playerState,
                    onTogglePlayPause = { app.playerController.togglePlayPause() },
                    onForward30 = { app.playerController.seekBy(30_000L) },
                    onExpand = { showFullPlayer = true }
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "home",
                        onClick = {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                        },
                        icon = { Icon(Icons.Default.Podcasts, null) },
                        label = { Text("Podcasts") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "queue",
                        onClick = {
                            navController.navigate("queue") {
                                popUpTo("home")
                            }
                        },
                        icon = { Icon(Icons.Default.QueueMusic, null) },
                        label = { Text("Warteschlange") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "search",
                        onClick = {
                            navController.navigate("search") {
                                popUpTo("home")
                            }
                        },
                        icon = { Icon(Icons.Default.Explore, null) },
                        label = { Text("Finden") }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    podcasts = podcasts,
                    isRefreshing = isRefreshing,
                    settings = settings,
                    onRefreshAll = {
                        scope.launch {
                            isRefreshing = true
                            app.repository.refreshAllPodcasts()
                            isRefreshing = false
                        }
                    },
                    onPodcastClick = { podcastId ->
                        navController.navigate("detail/$podcastId")
                    },
                    onAddClick = {
                        navController.navigate("search") {
                            popUpTo("home")
                        }
                    },
                    onChangeRefreshInterval = { hours ->
                        app.preferences.setRefreshIntervalHours(hours)
                        com.lycoslane.purepodcast.core.download.FeedRefreshWorker
                            .schedulePeriodic(context, hours)
                    },
                    onToggleWifiOnly = { wifiOnly ->
                        app.preferences.setWifiOnlyDownloads(wifiOnly)
                    },
                    onToggleAutoDownloadDefault = { enabled ->
                        app.preferences.setAutoDownloadNewByDefault(enabled)
                    }
                )
            }

            composable(
                route = "detail/{podcastId}",
                arguments = listOf(navArgument("podcastId") { type = NavType.LongType })
            ) { entry ->
                val podcastId = entry.arguments?.getLong("podcastId") ?: return@composable
                val podcast by app.repository.observePodcast(podcastId)
                    .collectAsStateWithLifecycle(initialValue = null)
                val episodes by app.repository.observeEpisodes(
                    podcastId = podcastId,
                    ascending = podcast?.sortAscending ?: false
                ).collectAsStateWithLifecycle(initialValue = emptyList())

                PodcastDetailScreen(
                    podcast = podcast,
                    episodes = episodes,
                    isRefreshing = isRefreshingDetail,
                    onBack = { navController.popBackStack() },
                    onRefresh = {
                        scope.launch {
                            isRefreshingDetail = true
                            runCatching { app.repository.refreshPodcast(podcastId) }
                            isRefreshingDetail = false
                        }
                    },
                    onToggleSort = { asc -> scope.launch { app.repository.toggleSortAscending(podcastId, asc) } },
                    onDisableAutoDownload = { scope.launch { app.repository.disableAutoDownload(podcastId) } },
                    onUnsubscribe = {
                        scope.launch {
                            app.repository.unsubscribePodcast(podcastId)
                            navController.popBackStack()
                        }
                    },
                    onPlayEpisode = { ep ->
                        podcast?.let { pod -> app.playerController.playEpisode(ep, pod) }
                    },
                    onDownloadEpisode = { ep -> scope.launch { app.repository.enqueueDownload(ep.id) } },
                    onDeleteDownload = { ep -> scope.launch { app.repository.deleteDownload(ep.id) } },
                    onAddToQueue = { ep -> scope.launch { app.repository.addToQueue(ep.id) } },
                    onTogglePlayed = { ep -> scope.launch { app.repository.togglePlayed(ep.id, !ep.isPlayed) } },
                    onDownloadNewerFrom = { ep ->
                        scope.launch { app.repository.downloadNewerFromEpisode(podcastId, ep.pubDateMs) }
                    },
                    onCleanupOlderThan = { ep ->
                        scope.launch { app.repository.cleanupOlderThan(podcastId, ep.pubDateMs) }
                    }
                )
            }

            composable("queue") {
                QueueScreen(
                    queue = queue,
                    onPlay = { ep, pod -> app.playerController.playEpisode(ep, pod) },
                    onRemove = { epId -> scope.launch { app.repository.removeFromQueue(epId) } },
                    onClear = { scope.launch { app.repository.clearQueue() } }
                )
            }

            composable("search") {
                val subscribedFeeds = remember(podcasts) {
                    podcasts.associate { it.podcast.feedUrl to it.podcast.id }
                }
                SearchScreen(
                    results = searchResults,
                    subscribedFeeds = subscribedFeeds,
                    isSearching = isSearching,
                    statusMessage = searchStatus,
                    onSearch = { q ->
                        scope.launch {
                            isSearching = true
                            searchStatus = null
                            searchResults = runCatching { app.repository.searchPodcasts(q) }
                                .getOrElse {
                                    searchStatus = "Suche fehlgeschlagen: ${it.localizedMessage}"
                                    emptyList()
                                }
                            isSearching = false
                        }
                    },
                    onSubscribeFeedUrl = { url ->
                        scope.launch {
                            searchStatus = "Abonniere Feed…"
                            runCatching { app.repository.subscribeByFeedUrl(url) }
                                .onSuccess { podcastId ->
                                    searchStatus = "Podcast abonniert!"
                                    navController.navigate("detail/$podcastId")
                                }
                                .onFailure {
                                    searchStatus = "Fehler: ${it.localizedMessage}"
                                }
                        }
                    },
                    onUnsubscribeById = { podcastId, title ->
                        scope.launch {
                            app.repository.unsubscribePodcast(podcastId)
                            searchStatus = "„$title“ deabonniert."
                        }
                    },
                    onImportOpmlUri = { uri: Uri ->
                        scope.launch {
                            searchStatus = "Importiere OPML…"
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    app.repository.importOpml(stream)
                                } ?: 0
                            }.onSuccess { count ->
                                searchStatus = "$count Podcasts aus OPML importiert!"
                            }.onFailure {
                                searchStatus = "OPML-Import fehlgeschlagen: ${it.localizedMessage}"
                            }
                        }
                    }
                )
            }
        }
    }

    if (showFullPlayer) {
        FullPlayerSheet(
            state = playerState,
            onDismiss = { showFullPlayer = false },
            onTogglePlayPause = { app.playerController.togglePlayPause() },
            onSeekTo = { app.playerController.seekTo(it) },
            onSeekBy = { app.playerController.seekBy(it) },
            onSetSpeed = { app.playerController.setPlaybackSpeed(it) },
            onToggleSkipSilence = { app.playerController.toggleSkipSilence() },
            onSetSleepTimer = { app.playerController.setSleepTimer(it) }
        )
    }
}
