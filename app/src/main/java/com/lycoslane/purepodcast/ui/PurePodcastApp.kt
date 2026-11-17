package com.lycoslane.purepodcast.ui

import android.app.Activity
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.PlayerController
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.ui.detail.PodcastDetailScreen
import com.lycoslane.purepodcast.ui.home.HomeScreen
import com.lycoslane.purepodcast.ui.player.FullPlayerSheet
import com.lycoslane.purepodcast.ui.player.MiniPlayer
import com.lycoslane.purepodcast.ui.queue.QueueScreen
import com.lycoslane.purepodcast.ui.search.SearchScreen
import com.lycoslane.purepodcast.ui.viewmodel.MainViewModel
import com.lycoslane.purepodcast.ui.viewmodel.PodcastDetailViewModel
import com.lycoslane.purepodcast.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun PurePodcastApp(app: PurePodcastApplication) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val mainViewModel: MainViewModel = viewModel()

    LaunchedEffect(Unit) {
        app.playerController.connect()
    }

    val podcasts by mainViewModel.podcasts.collectAsStateWithLifecycle()
    val queue by mainViewModel.queue.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val isRefreshing by mainViewModel.isRefreshing.collectAsStateWithLifecycle()
    val backupStatus by mainViewModel.backupStatus.collectAsStateWithLifecycle()

    val activeEpisodeId by remember(app.playerController) {
        app.playerController.state
            .map { it.currentEpisode?.id }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var queueScrollTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var focusSearchTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(activeEpisodeId) {
        if (activeEpisodeId == null) {
            showFullPlayer = false
        }
    }

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
                MiniPlayerBar(
                    playerController = app.playerController,
                    onExpand = { showFullPlayer = true }
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "home" || currentRoute?.startsWith("detail/") == true,
                        onClick = {
                            if (currentRoute?.startsWith("detail/") == true) {
                                navController.popBackStack("home", inclusive = false)
                            } else {
                                navigateTopLevel("home")
                            }
                        },
                        icon = { Icon(Icons.Default.Podcasts, null) },
                        label = { Text(stringResource(R.string.nav_podcasts)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "queue",
                        onClick = {
                            if (currentRoute == "queue") {
                                queueScrollTrigger++
                            } else {
                                navigateTopLevel("queue")
                            }
                        },
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
                        onClick = {
                            if (currentRoute == "search") {
                                focusSearchTrigger++
                            } else {
                                navigateTopLevel("search")
                            }
                        },
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
                    onRefreshAll = mainViewModel::refreshAll,
                    onPodcastClick = { podcastId ->
                        navController.navigate("detail/$podcastId")
                    },
                    onAddClick = { navigateTopLevel("search") },
                    onChangeRefreshInterval = mainViewModel::setRefreshInterval,
                    onToggleWifiOnly = mainViewModel::setWifiOnly,
                    onToggleAutoDownloadDefault = mainViewModel::setAutoDownloadDefault,
                    onToggleDownloadWhenQueued = mainViewModel::setDownloadWhenQueued,
                    onToggleAddToQueueWhenDownloaded = mainViewModel::setAddToQueueWhenDownloaded,
                    onChangeAutoRemovePlayedDays = mainViewModel::setAutoRemovePlayedDays,
                    onChangeTrashRetentionHours = mainViewModel::setTrashRetentionHours,
                    onChangeMaxDownloadsPerPodcast = mainViewModel::setMaxDownloadsPerPodcast,
                    onChangeSmartRewind = mainViewModel::setSmartRewind,
                    onToggleAudioNormalization = mainViewModel::setAudioNormalization,
                    onChangeBluetoothNextPrevSeeks = mainViewModel::setBluetoothNextPrevSeeks,
                    onChangeAppLanguage = { lang ->
                        if (settings.appLanguage != lang) {
                            app.preferences.setAppLanguage(lang)
                            (context as? Activity)?.recreate()
                        }
                    },
                    onChangeTranscodeBitrate = mainViewModel::setTranscodeBitrateKbps,
                    onToggleTranscodeAuto = mainViewModel::setTranscodeAutoEnabled,
                    onToggleTranscodeChargingOnly = mainViewModel::setTranscodeChargingOnly,
                    onTranscodeAllNow = {
                        mainViewModel.transcodeAllNow { msg ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    onSelectLocalFolderUri = mainViewModel::setLocalFolderUri,
                    onToggleShowSubscribed = mainViewModel::setShowSubscribedPodcasts,
                    onToggleShowLocal = mainViewModel::setShowLocalPodcasts,
                    onExportBackupUri = mainViewModel::exportBackup,
                    onImportBackupUri = mainViewModel::importBackup
                )
            }

            composable(
                route = "detail/{podcastId}",
                arguments = listOf(navArgument("podcastId") { type = NavType.LongType })
            ) {
                val detailViewModel: PodcastDetailViewModel = viewModel()
                val podcast by detailViewModel.podcast.collectAsStateWithLifecycle()
                val episodes by detailViewModel.episodes.collectAsStateWithLifecycle()
                val isRefreshingDetail by detailViewModel.isRefreshing.collectAsStateWithLifecycle()
                val canDeleteLocal = remember(settings.localFolderUri) {
                    LocalPodcastScanner.hasWritePermission(context, settings.localFolderUri)
                }

                PodcastDetailScreen(
                    podcast = podcast,
                    episodes = episodes,
                    activeEpisodeId = activeEpisodeId,
                    isRefreshing = isRefreshingDetail,
                    textScale = settings.textScale,
                    trashRetentionHours = settings.trashRetentionHours,
                    canDeleteLocal = canDeleteLocal,
                    onTextScaleChange = mainViewModel::setTextScale,
                    onBack = { navController.popBackStack() },
                    onRefresh = detailViewModel::refresh,
                    onToggleSort = detailViewModel::toggleSort,
                    onDisableAutoDownload = detailViewModel::disableAutoDownload,
                    onSubscribe = detailViewModel::subscribe,
                    onUnsubscribe = {
                        detailViewModel.unsubscribe {
                            navController.popBackStack()
                        }
                    },
                    onPlayEpisode = { ep ->
                        podcast?.let { pod -> app.playerController.playEpisode(ep, pod) }
                    },
                    onPauseMainPlayer = { app.playerController.pauseIfPlaying() },
                    onDownloadEpisode = { ep -> detailViewModel.downloadEpisode(ep.id) },
                    onDeleteDownload = { ep -> detailViewModel.deleteDownload(ep.id) },
                    onStopAndDeleteActive = { ep -> detailViewModel.stopAndDeleteActive(ep.id) },
                    onRestoreFromTrash = { ep -> detailViewModel.restoreFromTrash(ep.id) },
                    onDeletePermanently = { ep -> detailViewModel.deletePermanently(ep.id) },
                    onEmptyTrash = detailViewModel::emptyTrash,
                    onAddToQueue = { ep ->
                        detailViewModel.addToQueueEnd(ep.id) {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_added_to_queue))
                            }
                        }
                    },
                    onAddToQueueFront = { ep ->
                        detailViewModel.addToQueueFront(ep.id) {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_added_to_queue_front))
                            }
                        }
                    },
                    onTogglePlayed = { ep -> detailViewModel.togglePlayed(ep) },
                    onToggleHidden = { ep ->
                        val nextHidden = !ep.isHidden
                        detailViewModel.setHidden(ep.id, nextHidden) {
                            if (nextHidden) {
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.snackbar_episode_hidden),
                                        actionLabel = context.getString(R.string.action_undo),
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        mainViewModel.unhideEpisode(ep.id)
                                    }
                                }
                            }
                        }
                    },
                    onDownloadNewerFrom = { ep -> detailViewModel.downloadNewerFrom(ep.pubDateMs) },
                    onCleanupOlderThan = { ep -> detailViewModel.cleanupOlderThan(ep.pubDateMs) }
                )
            }

            composable("queue") {
                val activePair by remember(app.playerController) {
                    app.playerController.state
                        .map { it.currentEpisode to it.currentPodcast }
                        .distinctUntilChanged()
                }.collectAsStateWithLifecycle(initialValue = null to null)

                QueueScreen(
                    queue = queue,
                    currentEpisode = activePair.first,
                    currentPodcast = activePair.second,
                    scrollToggleTrigger = queueScrollTrigger,
                    onOpenNowPlaying = { showFullPlayer = true },
                    onOpenPodcast = { podcastId -> navController.navigate("detail/$podcastId") },
                    onPlay = { ep, pod -> app.playerController.playEpisode(ep, pod) },
                    onRemove = mainViewModel::removeFromQueue,
                    onClear = mainViewModel::clearQueue
                )
            }

            composable("search") {
                val searchViewModel: SearchViewModel = viewModel()
                val searchQuery by searchViewModel.query.collectAsStateWithLifecycle()
                val searchResults by searchViewModel.results.collectAsStateWithLifecycle()
                val isSearching by searchViewModel.isSearching.collectAsStateWithLifecycle()
                val searchStatus by searchViewModel.statusMessage.collectAsStateWithLifecycle()
                val subscribedFeeds = remember(podcasts) {
                    podcasts.filter { it.podcast.isSubscribed }.associate { it.podcast.feedUrl to it.podcast.id }
                }

                SearchScreen(
                    query = searchQuery,
                    onQueryChange = searchViewModel::onQueryChange,
                    results = searchResults,
                    subscribedFeeds = subscribedFeeds,
                    isSearching = isSearching,
                    statusMessage = searchStatus,
                    textScale = settings.textScale,
                    onTextScaleChange = mainViewModel::setTextScale,
                    focusSearchTrigger = focusSearchTrigger,
                    onSearch = searchViewModel::search,
                    onLoadFeedPreview = searchViewModel::loadFeedPreview,
                    onSubscribeFeedUrl = { url ->
                        searchViewModel.subscribeFeedUrl(url) { podcastId ->
                            navController.navigate("detail/$podcastId")
                        }
                    },
                    onUnsubscribeById = searchViewModel::unsubscribeById,
                    onAddPreviewEpisodeToQueue = { feed, ep, toFront ->
                        searchViewModel.addPreviewEpisodeToQueue(feed, ep, toFront)
                    },
                    onDownloadPreviewEpisode = { feed, ep ->
                        searchViewModel.downloadPreviewEpisode(feed, ep)
                    },
                    onOpenPreviewPodcast = { feed ->
                        searchViewModel.openPreviewPodcast(feed) { podcastId ->
                            navController.navigate("detail/$podcastId")
                        }
                    },
                    onImportOpmlUri = searchViewModel::importOpmlUri
                )
            }
        }
    }

    if (showFullPlayer) {
        FullPlayerOverlay(
            playerController = app.playerController,
            queue = queue,
            textScale = settings.textScale,
            onTextScaleChange = mainViewModel::setTextScale,
            onDismiss = { showFullPlayer = false }
        )
    }
}

@Composable
private fun MiniPlayerBar(
    playerController: PlayerController,
    onExpand: () -> Unit
) {
    val playerState by playerController.state.collectAsStateWithLifecycle()
    MiniPlayer(
        state = playerState,
        onTogglePlayPause = { playerController.togglePlayPause() },
        onForward30 = { playerController.seekBy(30_000L) },
        onExpand = onExpand
    )
}

@Composable
private fun FullPlayerOverlay(
    playerController: PlayerController,
    queue: List<EpisodeWithPodcast>,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val playerState by playerController.state.collectAsStateWithLifecycle()
    if (playerState.currentEpisode == null) return
    val currentId = playerState.currentEpisode?.id
    val canSkipQueue = queue.size > 1 || (queue.size == 1 && queue[0].episode.id != currentId)
    FullPlayerSheet(
        state = playerState,
        hasQueue = canSkipQueue,
        textScale = textScale,
        onTextScaleChange = onTextScaleChange,
        onDismiss = onDismiss,
        onTogglePlayPause = { playerController.togglePlayPause() },
        onSeekTo = { playerController.seekTo(it) },
        onSeekBy = { playerController.seekBy(it) },
        onPrevEpisode = { playerController.skipToPreviousInQueue() },
        onNextEpisode = { playerController.skipToNextInQueue() },
        onSetSpeed = { playerController.setPlaybackSpeed(it) },
        onToggleSkipSilence = { playerController.toggleSkipSilence() },
        onSetSilenceIntensity = { playerController.setSilenceIntensity(it) },
        onSetSleepTimer = { playerController.setSleepTimer(it) }
    )
}
