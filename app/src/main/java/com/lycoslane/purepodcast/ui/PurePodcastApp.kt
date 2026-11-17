package com.lycoslane.purepodcast.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.ui.detail.PodcastDetailScreen
import com.lycoslane.purepodcast.ui.home.HomeScreen
import com.lycoslane.purepodcast.ui.player.FullPlayerSheet
import com.lycoslane.purepodcast.ui.player.MiniPlayer
import com.lycoslane.purepodcast.ui.queue.QueueScreen
import com.lycoslane.purepodcast.ui.search.SearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

@Composable
fun PurePodcastApp(app: PurePodcastApplication) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        app.playerController.connect()
    }

    val podcasts by app.repository.observePodcasts().collectAsStateWithLifecycle(initialValue = emptyList())
    val queue by app.repository.observeQueue().collectAsStateWithLifecycle(initialValue = emptyList())
    val playerState by app.playerController.state.collectAsStateWithLifecycle()
    val settings by app.preferences.settings.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var isRefreshingDetail by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<PodcastSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        onClick = { navigateTopLevel("home") },
                        icon = { Icon(Icons.Default.Podcasts, null) },
                        label = { Text(stringResource(R.string.nav_podcasts)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "queue",
                        onClick = { navigateTopLevel("queue") },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (queue.isNotEmpty()) {
                                        Badge { Text(queue.size.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.QueueMusic, null)
                            }
                        },
                        label = { Text(stringResource(R.string.nav_queue)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "search",
                        onClick = { navigateTopLevel("search") },
                        icon = { Icon(Icons.Default.Explore, null) },
                        label = { Text(stringResource(R.string.nav_discover)) }
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
                    backupStatus = backupStatus,
                    onRefreshAll = {
                        scope.launch {
                            isRefreshing = true
                            try {
                                app.repository.refreshAllPodcasts()
                            } finally {
                                isRefreshing = false
                            }
                        }
                    },
                    onPodcastClick = { podcastId ->
                        navController.navigate("detail/$podcastId")
                    },
                    onAddClick = { navigateTopLevel("search") },
                    onChangeRefreshInterval = { hours ->
                        app.preferences.setRefreshIntervalHours(hours)
                        FeedRefreshWorker.schedulePeriodic(context, hours, replaceExisting = true)
                    },
                    onToggleWifiOnly = { wifiOnly ->
                        app.preferences.setWifiOnlyDownloads(wifiOnly)
                    },
                    onToggleAutoDownloadDefault = { enabled ->
                        app.preferences.setAutoDownloadNewByDefault(enabled)
                    },
                    onToggleDownloadWhenQueued = { enabled ->
                        app.preferences.setDownloadWhenQueued(enabled)
                    },
                    onToggleAddToQueueWhenDownloaded = { enabled ->
                        app.preferences.setAddToQueueWhenDownloaded(enabled)
                    },
                    onChangeAutoRemovePlayedDays = { days ->
                        app.preferences.setAutoRemovePlayedDays(days)
                        scope.launch {
                            app.repository.cleanupPlayedDownloads(playerState.currentEpisode?.id)
                        }
                    },
                    onChangeSmartRewind = { secs ->
                        app.preferences.setSmartRewindSeconds(secs)
                    },
                    onChangeAppLanguage = { lang ->
                        if (settings.appLanguage != lang) {
                            app.preferences.setAppLanguage(lang)
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    onExportBackupUri = { uri ->
                        scope.launch {
                            runCatching {
                                val json = app.repository.exportBackupJson()
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        out.write(json.toByteArray(Charsets.UTF_8))
                                    } ?: error("Cannot open output")
                                }
                            }.onSuccess {
                                backupStatus = context.getString(R.string.backup_exported)
                            }.onFailure { err ->
                                backupStatus = context.getString(R.string.backup_failed, err.javaClass.simpleName)
                            }
                        }
                    },
                    onImportBackupUri = { uri ->
                        scope.launch {
                            runCatching {
                                val text = withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
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
                                    ?: error(context.getString(R.string.backup_empty_file))
                                app.repository.importBackupJson(text)
                            }.onSuccess { count ->
                                backupStatus = context.getString(R.string.backup_imported, count)
                            }.onFailure { err ->
                                backupStatus = context.getString(R.string.backup_failed, err.javaClass.simpleName)
                            }
                        }
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
                            try {
                                runCatching { app.repository.refreshPodcast(podcastId) }
                            } finally {
                                isRefreshingDetail = false
                            }
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
                    onDeleteDownload = { ep ->
                        scope.launch {
                            app.repository.deleteDownload(ep.id, playerState.currentEpisode?.id)
                        }
                    },
                    onAddToQueue = { ep ->
                        scope.launch {
                            app.repository.addToQueue(ep.id)
                            snackbarHostState.showSnackbar(context.getString(R.string.snackbar_added_to_queue))
                        }
                    },
                    onTogglePlayed = { ep ->
                        scope.launch {
                            app.repository.togglePlayed(ep.id, !ep.isPlayed, playerState.currentEpisode?.id)
                        }
                    },
                    onDownloadNewerFrom = { ep ->
                        scope.launch { app.repository.downloadNewerFromEpisode(podcastId, ep.pubDateMs) }
                    },
                    onCleanupOlderThan = { ep ->
                        scope.launch {
                            app.repository.cleanupOlderThan(podcastId, ep.pubDateMs, playerState.currentEpisode?.id)
                        }
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
                            try {
                                searchResults = runCatching { app.repository.searchPodcasts(q) }
                                    .getOrElse { err ->
                                        searchStatus = context.getString(R.string.status_search_failed, err.javaClass.simpleName)
                                        emptyList()
                                    }
                            } finally {
                                isSearching = false
                            }
                        }
                    },
                    onSubscribeFeedUrl = { url ->
                        scope.launch {
                            searchStatus = context.getString(R.string.status_subscribing)
                            runCatching { app.repository.subscribeByFeedUrl(url) }
                                .onSuccess { podcastId ->
                                    searchStatus = context.getString(R.string.status_subscribed)
                                    navController.navigate("detail/$podcastId")
                                }
                                .onFailure { err ->
                                    searchStatus = context.getString(R.string.status_search_failed, err.javaClass.simpleName)
                                }
                        }
                    },
                    onUnsubscribeById = { podcastId, title ->
                        scope.launch {
                            app.repository.unsubscribePodcast(podcastId)
                            searchStatus = context.getString(R.string.status_unsubscribed, title)
                        }
                    },
                    onImportOpmlUri = { uri: Uri ->
                        scope.launch {
                            searchStatus = context.getString(R.string.status_opml_importing)
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        app.repository.importOpml(stream)
                                    } ?: 0
                                }
                            }.onSuccess { count ->
                                searchStatus = context.getString(R.string.status_opml_imported, count)
                            }.onFailure { err ->
                                searchStatus = context.getString(R.string.status_opml_failed, err.javaClass.simpleName)
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
