package com.lycoslane.purepodcast.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.lycoslane.purepodcast.core.audio.SilenceOverride
import com.lycoslane.purepodcast.core.audio.SpeedOverride
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.download.TranscodeStatus
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.core.network.NetworkUtils
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.formatFileSize
import com.lycoslane.purepodcast.ui.detail.PodcastDetailScreen
import com.lycoslane.purepodcast.ui.home.HomeScreen
import com.lycoslane.purepodcast.ui.player.FullPlayerSheet
import com.lycoslane.purepodcast.ui.player.MiniPlayer
import com.lycoslane.purepodcast.ui.queue.QueueScreen
import com.lycoslane.purepodcast.ui.search.SearchScreen
import com.lycoslane.purepodcast.ui.settings.SettingsScreen
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
    val allEpisodes by mainViewModel.allEpisodes.collectAsStateWithLifecycle()
    val queue by mainViewModel.queue.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val isRefreshing by mainViewModel.isRefreshing.collectAsStateWithLifecycle()
    val backupStatus by mainViewModel.backupStatus.collectAsStateWithLifecycle()
    val transcodeStatus by mainViewModel.transcodeStatus.collectAsStateWithLifecycle()

    val activeEpisodeId by remember(app.playerController) {
        app.playerController.state
            .map { it.currentEpisode?.id }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var queueScrollTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var focusSearchTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val queuedEpisodeIds = remember(queue) { queue.map { it.episode.id }.toSet() }
    var pendingCellularEpisode by remember { mutableStateOf<EpisodeEntity?>(null) }
    var pendingCellularSizeBytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingCellularEpisode?.id) {
        val ep = pendingCellularEpisode
        if (ep != null) {
            if (ep.fileSizeBytes > 0L) {
                pendingCellularSizeBytes = ep.fileSizeBytes
            } else {
                pendingCellularSizeBytes = null
                val len = NetworkUtils.fetchContentLengthBytes(ep.audioUrl)
                if (pendingCellularEpisode?.id == ep.id) {
                    pendingCellularSizeBytes = len
                }
            }
        } else {
            pendingCellularSizeBytes = null
        }
    }

    fun requestDownloadWithCellularCheck(ep: EpisodeEntity, onProceedDirect: () -> Unit) {
        if (settings.wifiOnlyDownloads && !NetworkUtils.isOnUnmeteredWifi(context)) {
            pendingCellularEpisode = ep
        } else {
            onProceedDirect()
        }
    }

    LaunchedEffect(activeEpisodeId) {
        if (activeEpisodeId == null) {
            showFullPlayer = false
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var detailOriginRoute by rememberSaveable { mutableStateOf("home") }
    val isDetailRoute = currentRoute?.startsWith("detail/") == true
    val isSettingsRoute = currentRoute == "settings"

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDetail(podcastId: Long, fromRoute: String) {
        detailOriginRoute = fromRoute
        navController.navigate("detail/$podcastId") { launchSingleTop = true }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!isSettingsRoute) {
                Column {
                    TranscodeProgressBanner(status = transcodeStatus)
                    MiniPlayerBar(
                        playerController = app.playerController,
                        onExpand = { showFullPlayer = true }
                    )
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == "home" || (isDetailRoute && detailOriginRoute == "home"),
                            onClick = {
                                if (isDetailRoute && detailOriginRoute == "home") {
                                    navController.popBackStack("home", inclusive = false)
                                } else {
                                    navigateTopLevel("home")
                                }
                            },
                            icon = { Icon(Icons.Default.Podcasts, null) },
                            label = { Text(stringResource(R.string.nav_podcasts)) }
                        )
                        NavigationBarItem(
                            selected = currentRoute == "queue" || (isDetailRoute && detailOriginRoute == "queue"),
                            onClick = {
                                if (currentRoute == "queue") {
                                    queueScrollTrigger++
                                } else if (isDetailRoute && detailOriginRoute == "queue") {
                                    navController.popBackStack("queue", inclusive = false)
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
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                                }
                            },
                            label = { Text(stringResource(R.string.nav_queue)) }
                        )
                        NavigationBarItem(
                            selected = currentRoute == "search" || (isDetailRoute && detailOriginRoute == "search"),
                            onClick = {
                                if (currentRoute == "search") {
                                    focusSearchTrigger++
                                } else if (isDetailRoute && detailOriginRoute == "search") {
                                    navController.popBackStack("search", inclusive = false)
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    podcasts = podcasts,
                    allEpisodes = allEpisodes,
                    queuedEpisodeIds = queuedEpisodeIds,
                    activeEpisodeId = activeEpisodeId,
                    isRefreshing = isRefreshing,
                    settings = settings,
                    onRefreshAll = mainViewModel::refreshAll,
                    onPodcastClick = { podcastId -> openDetail(podcastId, "home") },
                    onAddClick = { navigateTopLevel("search") },
                    onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                    onToggleFavorite = mainViewModel::toggleFavorite,
                    onPlayEpisode = { ep, pod -> app.playerController.playEpisode(ep, pod) },
                    onPauseMainPlayer = { app.playerController.pauseIfPlaying() },
                    onAddToQueueFront = { ep ->
                        mainViewModel.addToQueueFront(ep.id) {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_added_to_queue_front))
                            }
                        }
                    },
                    onAddToQueueEnd = { ep ->
                        mainViewModel.addToQueueEnd(ep.id) {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_added_to_queue))
                            }
                        }
                    },
                    onDownloadEpisode = { ep ->
                        requestDownloadWithCellularCheck(ep) { mainViewModel.downloadEpisode(ep.id) }
                    },
                    onDeleteDownload = { ep -> mainViewModel.deleteDownload(ep.id) },
                    onRestoreFromTrash = { ep -> mainViewModel.restoreFromTrash(ep.id) },
                    onTogglePlayed = mainViewModel::togglePlayed,
                    onToggleHidden = { ep ->
                        val nextHidden = !ep.isHidden
                        mainViewModel.setHidden(ep.id, nextHidden) {
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
                    onTextScaleChange = mainViewModel::setTextScale,
                    onToggleShowSubscribed = mainViewModel::setShowSubscribedPodcasts,
                    onToggleShowLocal = mainViewModel::setShowLocalPodcasts
                )
            }

            composable("settings") {
                SettingsScreen(
                    allEpisodes = allEpisodes,
                    settings = settings,
                    backupStatus = backupStatus,
                    transcodeStatus = transcodeStatus,
                    onBack = { navController.popBackStack() },
                    onChangeThemeMode = mainViewModel::setThemeMode,
                    onChangeLightAccent = mainViewModel::setLightAccent,
                    onChangeDarkAccent = mainViewModel::setDarkAccent,
                    onChangeRefreshIntervalMinutes = mainViewModel::setRefreshIntervalMinutes,
                    onToggleWifiOnly = mainViewModel::setWifiOnly,
                    onToggleAutoDownloadDefault = mainViewModel::setAutoDownloadDefault,
                    onToggleDownloadWhenQueued = mainViewModel::setDownloadWhenQueued,
                    onToggleAddToQueueWhenDownloaded = mainViewModel::setAddToQueueWhenDownloaded,
                    onAddAllDownloadedToQueue = {
                        mainViewModel.addAllDownloadedToQueue { count ->
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val msg = if (count > 0) {
                                    context.getString(R.string.snackbar_added_all_downloaded_to_queue, count)
                                } else {
                                    context.getString(R.string.snackbar_no_downloaded_to_add)
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    onChangeAutoRemovePlayedDays = mainViewModel::setAutoRemovePlayedDays,
                    onChangeTrashRetentionHours = mainViewModel::setTrashRetentionHours,
                    onChangeMaxDownloadsPerPodcast = mainViewModel::setMaxDownloadsPerPodcast,
                    onFlushPendingDownloadSettings = mainViewModel::flushPendingDownloadSettings,
                    onPreviewCleanupImpact = mainViewModel::previewCleanupImpact,
                    onConfirmCleanupNow = { mainViewModel.runCleanupNow(bypassTrash = true) },
                    onToggleNotifyNewEpisodes = mainViewModel::setNotifyNewEpisodes,
                    onChangeSmartRewind = mainViewModel::setSmartRewind,
                    onChangeDefaultSpeed = mainViewModel::setDefaultPlaybackSpeed,
                    onChangeDefaultSilenceMode = mainViewModel::setDefaultSilenceMode,
                    onToggleAudioNormalization = mainViewModel::setAudioNormalization,
                    onChangeMonoMode = mainViewModel::setMonoMode,
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
                    globalSettings = settings,
                    canDeleteLocal = canDeleteLocal,
                    onTextScaleChange = mainViewModel::setTextScale,
                    onBack = { navController.popBackStack() },
                    onRefresh = detailViewModel::refresh,
                    onToggleSort = detailViewModel::toggleSort,
                    onSetSpeedOverride = detailViewModel::setSpeedOverride,
                    onSetSilenceModeOverride = detailViewModel::setSilenceModeOverride,
                    onSetMonoModeOverride = detailViewModel::setMonoModeOverride,
                    onSetNormalizationOverride = detailViewModel::setAudioNormalizationOverride,
                    onSetSmartRewindOverride = detailViewModel::setSmartRewindOverride,
                    onApplyToAllPodcasts = {
                        detailViewModel.applyAudioSettingsToAll {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(context.getString(R.string.applied_to_all_podcasts))
                            }
                        }
                    },
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
                    onDownloadEpisode = { ep ->
                        requestDownloadWithCellularCheck(ep) { detailViewModel.downloadEpisode(ep.id) }
                    },
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
                    onToggleFavorite = mainViewModel::toggleFavorite,
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
                val playerState by app.playerController.state.collectAsStateWithLifecycle()
                val activeEpisode = remember(playerState.currentEpisode, playerState.positionMs, playerState.durationMs) {
                    playerState.currentEpisode?.let { current ->
                        current.copy(
                            positionMs = if (playerState.positionMs > 0L) playerState.positionMs else current.positionMs,
                            durationMs = if (playerState.durationMs > 0L) playerState.durationMs else current.durationMs
                        )
                    }
                }

                QueueScreen(
                    queue = queue,
                    currentEpisode = activeEpisode,
                    isPlaying = playerState.isPlaying,
                    scrollToggleTrigger = queueScrollTrigger,
                    onScrollToggleConsumed = { queueScrollTrigger = 0 },
                    onOpenNowPlaying = { showFullPlayer = true },
                    onOpenPodcast = { podcastId -> openDetail(podcastId, "queue") },
                    onPlay = { ep, pod -> app.playerController.playEpisode(ep, pod) },
                    onDownload = { ep ->
                        requestDownloadWithCellularCheck(ep) { mainViewModel.downloadEpisode(ep.id) }
                    },
                    onReorder = mainViewModel::reorderQueue,
                    onMoveNext = mainViewModel::moveEpisodeNextInQueue,
                    onRemove = mainViewModel::removeFromQueue,
                    onRemoveMultiple = mainViewModel::removeMultipleFromQueue,
                    onDeleteDownloadsKeepInQueue = mainViewModel::deleteDownloadsKeepInQueue,
                    onDeleteDownloadsAndRemove = mainViewModel::deleteDownloadsAndRemoveFromQueue,
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
                    onFocusSearchConsumed = { focusSearchTrigger = 0 },
                    onSearch = searchViewModel::search,
                    onLoadFeedPreview = searchViewModel::loadFeedPreview,
                    onSubscribeFeedUrl = { url ->
                        searchViewModel.subscribeFeedUrl(url) { podcastId ->
                            openDetail(podcastId, "search")
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
                            openDetail(podcastId, "search")
                        }
                    },
                    onImportOpmlUri = searchViewModel::importOpmlUri
                )
            }
        }
    }

    pendingCellularEpisode?.let { ep ->
        val sizeText = when {
            pendingCellularSizeBytes == null -> stringResource(R.string.dialog_cellular_size_loading)
            pendingCellularSizeBytes!! > 0L -> stringResource(
                R.string.dialog_cellular_body_size,
                formatFileSize(pendingCellularSizeBytes!!)
            )
            else -> stringResource(R.string.dialog_cellular_body_unknown)
        }
        AlertDialog(
            onDismissRequest = { pendingCellularEpisode = null },
            title = { Text(stringResource(R.string.dialog_cellular_title)) },
            text = { Text(sizeText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCellularEpisode = null
                        mainViewModel.downloadEpisode(ep.id, allowCellularOnce = true)
                    }
                ) {
                    Text(stringResource(R.string.action_download_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCellularEpisode = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showFullPlayer) {
        FullPlayerOverlay(
            playerController = app.playerController,
            queue = queue,
            globalSettings = settings,
            textScale = settings.textScale,
            onTextScaleChange = mainViewModel::setTextScale,
            onApplyToAllPodcasts = { pod ->
                mainViewModel.applyPodcastAudioSettingsToAll(pod) {
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(context.getString(R.string.applied_to_all_podcasts))
                    }
                }
            },
            onDismiss = { showFullPlayer = false }
        )
    }
}

@Composable
private fun TranscodeProgressBanner(status: TranscodeStatus) {
    if (!status.isActive) return
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.notif_transcode_progress, status.currentIndex, status.totalCount))
                        if (status.episodeTitle.isNotBlank()) {
                            append(" • ")
                            append(status.episodeTitle)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(status.overallProgress * 100f).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { status.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
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
    globalSettings: AppSettings,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onApplyToAllPodcasts: (com.lycoslane.purepodcast.core.database.PodcastEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val playerState by playerController.state.collectAsStateWithLifecycle()
    val ep = playerState.currentEpisode ?: return
    val pod = playerState.currentPodcast ?: return
    val canSkipQueue = queue.size > 1 || (queue.size == 1 && queue[0].episode.id != ep.id)
    FullPlayerSheet(
        state = playerState,
        globalSettings = globalSettings,
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
        onResetSpeedToGlobal = { playerController.setPodcastSpeedOverride(pod.id, SpeedOverride.INHERIT) },
        onSetSilenceMode = { enabled, intensity -> playerController.setSilenceMode(enabled, intensity) },
        onResetSilenceToGlobal = { playerController.setPodcastSilenceOverride(pod.id, SilenceOverride.INHERIT) },
        onSetSleepTimer = { playerController.setSleepTimer(it) },
        onSetEpisodeMonoOverride = { playerController.setEpisodeMonoOverride(it) },
        onSetPodcastSpeedOverride = { ov -> playerController.setPodcastSpeedOverride(pod.id, ov) },
        onSetPodcastSilenceOverride = { ov -> playerController.setPodcastSilenceOverride(pod.id, ov) },
        onSetPodcastMonoOverride = { mode -> playerController.setPodcastMonoOverride(pod.id, mode) },
        onSetPodcastNormalizationOverride = { ov -> playerController.setPodcastNormalizationOverride(pod.id, ov) },
        onSetPodcastSmartRewindOverride = { ov -> playerController.setPodcastSmartRewindOverride(pod.id, ov) },
        onApplyToAllPodcasts = { onApplyToAllPodcasts(pod) },
        onTogglePodcastSort = { asc -> playerController.setPodcastSortAscending(pod.id, asc) },
        onDisablePodcastAutoDownload = { playerController.disablePodcastAutoDownload(pod.id) }
    )
}
