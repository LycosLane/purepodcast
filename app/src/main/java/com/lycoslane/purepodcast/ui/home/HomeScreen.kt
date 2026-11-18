package com.lycoslane.purepodcast.ui.home

import androidx.compose.material.icons.filled.ErrorOutline
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PodcastWithStats
import com.lycoslane.purepodcast.core.download.TranscodeStatus
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.detail.EpisodePreviewSheet
import kotlin.math.roundToInt

private enum class HomeEpisodeFilter {
    ALL,
    RECENT_14D,
    IN_PROGRESS,
    DOWNLOADED,
    FAVORITES
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    podcasts: List<PodcastWithStats>,
    allEpisodes: List<EpisodeWithPodcast> = emptyList(),
    activeEpisodeId: Long? = null,
    isRefreshing: Boolean,
    settings: AppSettings,
    backupStatus: String?,
    transcodeStatus: TranscodeStatus = TranscodeStatus(),
    onRefreshAll: () -> Unit,
    onPodcastClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onToggleFavorite: (EpisodeEntity) -> Unit = {},
    onPlayEpisode: (EpisodeEntity, PodcastEntity) -> Unit = { _, _ -> },
    onPauseMainPlayer: () -> Unit = {},
    onAddToQueueFront: (EpisodeEntity) -> Unit = {},
    onAddToQueueEnd: (EpisodeEntity) -> Unit = {},
    onDownloadEpisode: (EpisodeEntity) -> Unit = {},
    onDeleteDownload: (EpisodeEntity) -> Unit = {},
    onRestoreFromTrash: (EpisodeEntity) -> Unit = {},
    onTogglePlayed: (EpisodeEntity) -> Unit = {},
    onToggleHidden: (EpisodeEntity) -> Unit = {},
    onTextScaleChange: (Float) -> Unit = {},
    onChangeRefreshInterval: (Int) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleAutoDownloadDefault: (Boolean) -> Unit,
    onToggleDownloadWhenQueued: (Boolean) -> Unit,
    onToggleAddToQueueWhenDownloaded: (Boolean) -> Unit,
    onChangeAutoRemovePlayedDays: (Int) -> Unit,
    onChangeTrashRetentionHours: (Int) -> Unit,
    onChangeMaxDownloadsPerPodcast: (Int) -> Unit,
    onChangeSmartRewind: (Int) -> Unit,
    onToggleAudioNormalization: (Boolean) -> Unit,
    onChangeBluetoothNextPrevSeeks: (Boolean) -> Unit,
    onChangeAppLanguage: (String) -> Unit,
    onChangeTranscodeBitrate: (Int) -> Unit,
    onToggleTranscodeAuto: (Boolean) -> Unit,
    onToggleTranscodeChargingOnly: (Boolean) -> Unit,
    onTranscodeAllNow: () -> Unit,
    onSelectLocalFolderUri: (String) -> Unit,
    onToggleShowSubscribed: (Boolean) -> Unit,
    onToggleShowLocal: (Boolean) -> Unit,
    onExportBackupUri: (Uri) -> Unit,
    onImportBackupUri: (Uri) -> Unit
) {
    val context = LocalContext.current
    var showSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showLocalGuideDialog by rememberSaveable { mutableStateOf(false) }
    var showCompressedDownloadsDialog by rememberSaveable { mutableStateOf(false) }
    var showEpisodesView by rememberSaveable { mutableStateOf(false) }
    var activeEpisodeFilter by rememberSaveable { mutableStateOf(HomeEpisodeFilter.ALL) }
    var previewEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportBackupUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportBackupUri(uri)
    }

    val localFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val taken = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, rwFlags)
                true
            }.getOrElse {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                }.getOrDefault(false)
            }
            if (taken) {
                onSelectLocalFolderUri(uri.toString())
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (podcasts.isNotEmpty()) {
                        IconButton(onClick = { showEpisodesView = !showEpisodesView }) {
                            Icon(
                                imageVector = if (showEpisodesView) Icons.Default.GridView else Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = stringResource(R.string.action_toggle_episodes_view)
                            )
                        }
                    }
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefreshAll) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_all))
                        }
                    }
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add_podcast))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        val hasLocal = remember(podcasts, settings.localFolderUri) {
            podcasts.any { it.podcast.isLocal } || settings.localFolderUri.isNotBlank()
        }
        val visiblePodcasts = remember(podcasts, settings.showSubscribedPodcasts, settings.showLocalPodcasts, hasLocal) {
            if (!hasLocal || (settings.showSubscribedPodcasts == settings.showLocalPodcasts)) {
                podcasts
            } else if (settings.showSubscribedPodcasts) {
                podcasts.filter { !it.podcast.isLocal }
            } else {
                podcasts.filter { it.podcast.isLocal }
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (podcasts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.empty_podcasts_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_podcasts_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onAddClick) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.action_add_podcast))
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (hasLocal) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = settings.showSubscribedPodcasts,
                                onClick = {
                                    val next = !settings.showSubscribedPodcasts
                                    onToggleShowSubscribed(next)
                                    if (!next && !settings.showLocalPodcasts) {
                                        onToggleShowLocal(true)
                                    }
                                },
                                label = { Text(stringResource(R.string.filter_source_subscribed)) }
                            )
                            FilterChip(
                                selected = settings.showLocalPodcasts,
                                onClick = {
                                    val next = !settings.showLocalPodcasts
                                    onToggleShowLocal(next)
                                    if (!next && !settings.showSubscribedPodcasts) {
                                        onToggleShowSubscribed(true)
                                    }
                                },
                                label = { Text(stringResource(R.string.filter_source_local)) }
                            )
                        }
                    }
                    if (showEpisodesView) {
                        val visiblePodcastIds = remember(visiblePodcasts) {
                            visiblePodcasts.map { it.podcast.id }.toSet()
                        }
                        val inProgressCount = remember(allEpisodes, visiblePodcastIds) {
                            allEpisodes.count { item ->
                                item.podcast.id in visiblePodcastIds &&
                                    item.episode.positionMs > 0L &&
                                    !item.episode.isPlayed
                            }
                        }
                        val filteredEpisodes = remember(allEpisodes, visiblePodcastIds, activeEpisodeFilter) {
                            val cutoff14d = System.currentTimeMillis() - 14L * 86_400_000L
                            allEpisodes.filter { item ->
                                if (item.podcast.id !in visiblePodcastIds) return@filter false
                                val ep = item.episode
                                when (activeEpisodeFilter) {
                                    HomeEpisodeFilter.ALL -> true
                                    HomeEpisodeFilter.RECENT_14D -> ep.pubDateMs >= cutoff14d
                                    HomeEpisodeFilter.IN_PROGRESS -> ep.positionMs > 0L && !ep.isPlayed
                                    HomeEpisodeFilter.DOWNLOADED -> ep.downloadState == DownloadState.DOWNLOADED && ep.markedForDeletionAtMs == 0L
                                    HomeEpisodeFilter.FAVORITES -> ep.isFavorite
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = activeEpisodeFilter == HomeEpisodeFilter.ALL,
                                onClick = { activeEpisodeFilter = HomeEpisodeFilter.ALL },
                                label = { Text(stringResource(R.string.filter_episodes_all)) }
                            )
                            FilterChip(
                                selected = activeEpisodeFilter == HomeEpisodeFilter.RECENT_14D,
                                onClick = { activeEpisodeFilter = HomeEpisodeFilter.RECENT_14D },
                                label = { Text(stringResource(R.string.filter_episodes_recent_14d)) }
                            )
                            FilterChip(
                                selected = activeEpisodeFilter == HomeEpisodeFilter.IN_PROGRESS,
                                onClick = { activeEpisodeFilter = HomeEpisodeFilter.IN_PROGRESS },
                                label = {
                                    val baseLabel = stringResource(R.string.filter_episodes_in_progress)
                                    Text(if (inProgressCount > 0) "$baseLabel ($inProgressCount)" else baseLabel)
                                }
                            )
                            FilterChip(
                                selected = activeEpisodeFilter == HomeEpisodeFilter.DOWNLOADED,
                                onClick = { activeEpisodeFilter = HomeEpisodeFilter.DOWNLOADED },
                                label = { Text(stringResource(R.string.filter_episodes_downloaded)) }
                            )
                            FilterChip(
                                selected = activeEpisodeFilter == HomeEpisodeFilter.FAVORITES,
                                onClick = { activeEpisodeFilter = HomeEpisodeFilter.FAVORITES },
                                label = { Text(stringResource(R.string.filter_episodes_favorites)) }
                            )
                        }

                        if (filteredEpisodes.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.episodes_empty_filter),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                items(filteredEpisodes, key = { it.episode.id }) { item ->
                                    HomeEpisodeRow(
                                        item = item,
                                        isActive = item.episode.id == activeEpisodeId,
                                        onClick = { previewEpisodeId = item.episode.id },
                                        onToggleFavorite = { onToggleFavorite(item.episode) },
                                        onDownload = { onDownloadEpisode(item.episode) }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(visiblePodcasts, key = { it.podcast.id }) { item ->
                                PodcastThumbnailCard(
                                    item = item,
                                    onClick = { onPodcastClick(item.podcast.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                // --- Section 1: Downloads ---
                Text(
                    text = stringResource(R.string.settings_section_downloads),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    subtitle = stringResource(R.string.settings_wifi_only_sub),
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = onToggleWifiOnly
                )
                Spacer(Modifier.height(10.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_download_default),
                    subtitle = stringResource(R.string.settings_auto_download_default_sub),
                    checked = settings.autoDownloadNewByDefault,
                    onCheckedChange = onToggleAutoDownloadDefault
                )
                Spacer(Modifier.height(10.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_download_when_queued),
                    subtitle = stringResource(R.string.settings_download_when_queued_sub),
                    checked = settings.downloadWhenQueued,
                    onCheckedChange = onToggleDownloadWhenQueued
                )
                Spacer(Modifier.height(12.dp))
                val autoRemoveEnabled = settings.autoRemovePlayedDays >= 0
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_remove_played),
                    subtitle = stringResource(R.string.settings_auto_remove_played_sub),
                    checked = autoRemoveEnabled,
                    onCheckedChange = { enabled ->
                        onChangeAutoRemovePlayedDays(if (enabled) 1 else -1)
                    }
                )
                if (autoRemoveEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_auto_remove_delay),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0, 1, 3, 7).forEach { days ->
                            val label = if (days == 0) {
                                stringResource(R.string.auto_remove_immediately)
                            } else {
                                stringResource(R.string.auto_remove_days, days)
                            }
                            FilterChip(
                                selected = settings.autoRemovePlayedDays == days,
                                onClick = { onChangeAutoRemovePlayedDays(days) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_trash_retention),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_trash_retention_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 24, 48).forEach { hours ->
                        val label = when (hours) {
                            0 -> stringResource(R.string.trash_retention_immediate)
                            24 -> stringResource(R.string.trash_retention_24h)
                            else -> stringResource(R.string.trash_retention_48h)
                        }
                        FilterChip(
                            selected = settings.trashRetentionHours == hours,
                            onClick = { onChangeTrashRetentionHours(hours) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                val isUnlimitedDownloads = settings.maxDownloadsPerPodcast == 0
                SettingSwitchRow(
                    title = stringResource(R.string.settings_max_downloads_unlimited_toggle),
                    subtitle = stringResource(R.string.settings_max_downloads_sub),
                    checked = isUnlimitedDownloads,
                    onCheckedChange = { unlimited ->
                        onChangeMaxDownloadsPerPodcast(if (unlimited) 0 else 5)
                    }
                )
                if (!isUnlimitedDownloads) {
                    var sliderLimit by remember(settings.maxDownloadsPerPodcast) {
                        mutableFloatStateOf(settings.maxDownloadsPerPodcast.coerceIn(1, 30).toFloat())
                    }
                    val displayLimit = sliderLimit.roundToInt().coerceIn(1, 30)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_max_downloads),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.max_downloads_count, displayLimit),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = sliderLimit,
                        onValueChange = { sliderLimit = it },
                        onValueChangeFinished = {
                            onChangeMaxDownloadsPerPodcast(sliderLimit.roundToInt().coerceIn(1, 30))
                        },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section 2: Playback & Queue ---
                Text(
                    text = stringResource(R.string.settings_section_playback),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.settings_queue_when_downloaded),
                    subtitle = stringResource(R.string.settings_queue_when_downloaded_sub),
                    checked = settings.addToQueueWhenDownloaded,
                    onCheckedChange = onToggleAddToQueueWhenDownloaded
                )
                Spacer(Modifier.height(12.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_audio_normalization),
                    subtitle = stringResource(R.string.settings_audio_normalization_sub),
                    checked = settings.audioNormalizationEnabled,
                    onCheckedChange = onToggleAudioNormalization
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_smart_rewind),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_smart_rewind_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 5, 10, 15, 30).forEach { secs ->
                        val label = if (secs == 0) {
                            stringResource(R.string.interval_off)
                        } else {
                            stringResource(R.string.seconds_short, secs)
                        }
                        FilterChip(
                            selected = settings.smartRewindSeconds == secs,
                            onClick = { onChangeSmartRewind(secs) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_bluetooth_skip),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = settings.bluetoothNextPrevSeeks,
                        onClick = { onChangeBluetoothNextPrevSeeks(true) },
                        label = { Text(stringResource(R.string.settings_bluetooth_skip_seek)) }
                    )
                    FilterChip(
                        selected = !settings.bluetoothNextPrevSeeks,
                        onClick = { onChangeBluetoothNextPrevSeeks(false) },
                        label = { Text(stringResource(R.string.settings_bluetooth_skip_episode)) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section 3: Updates & Backup ---
                Text(
                    text = stringResource(R.string.settings_section_updates),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_refresh_interval),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 1, 3, 6, 12, 24).forEach { hours ->
                        val label = if (hours == 0) {
                            stringResource(R.string.interval_off)
                        } else {
                            stringResource(R.string.interval_hours, hours)
                        }
                        FilterChip(
                            selected = settings.refreshIntervalHours == hours,
                            onClick = { onChangeRefreshInterval(hours) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section: Audio Compression (AAC/M4A) ---
                Text(
                    text = stringResource(R.string.settings_section_compression),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_compression_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        0 to stringResource(R.string.settings_compression_off),
                        96 to "96 kbps",
                        128 to "128 kbps"
                    ).forEach { (kbps, label) ->
                        FilterChip(
                            selected = settings.transcodeBitrateKbps == kbps,
                            onClick = { onChangeTranscodeBitrate(kbps) },
                            label = { Text(label) }
                        )
                    }
                }
                if (settings.transcodeBitrateKbps > 0) {
                    Spacer(Modifier.height(6.dp))
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_compression_auto),
                        subtitle = stringResource(R.string.settings_compression_auto_sub),
                        checked = settings.transcodeAutoEnabled,
                        onCheckedChange = onToggleTranscodeAuto
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_compression_charging_only),
                        subtitle = stringResource(R.string.settings_compression_charging_only_sub),
                        checked = settings.transcodeChargingOnly,
                        onCheckedChange = onToggleTranscodeChargingOnly
                    )
                    val downloadedNonLocal = remember(allEpisodes) {
                        allEpisodes.filter { item ->
                            !item.podcast.isLocal &&
                                item.episode.downloadState == DownloadState.DOWNLOADED &&
                                item.episode.markedForDeletionAtMs == 0L &&
                                item.episode.localFilePath?.startsWith("content://") != true
                        }
                    }
                    val compressedEpisodes = remember(downloadedNonLocal) {
                        downloadedNonLocal.filter { it.episode.isTranscoded }
                    }
                    val compressedCount = compressedEpisodes.size
                    val pendingCount = (downloadedNonLocal.size - compressedCount).coerceAtLeast(0)
                    val totalSavedBytes = remember(compressedEpisodes) {
                        compressedEpisodes.sumOf { it.episode.transcodeSavedBytes.coerceAtLeast(0L) }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            downloadedNonLocal.isEmpty() -> stringResource(R.string.compression_no_downloads)
                            totalSavedBytes > 0L -> stringResource(
                                R.string.compression_status_summary_with_saved,
                                compressedCount,
                                pendingCount,
                                formatSizeMb(totalSavedBytes)
                            )
                            else -> stringResource(R.string.compression_status_summary, compressedCount, pendingCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (transcodeStatus.isActive) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        append(
                                            stringResource(
                                                R.string.notif_transcode_progress,
                                                transcodeStatus.currentIndex,
                                                transcodeStatus.totalCount
                                            )
                                        )
                                        if (transcodeStatus.episodeTitle.isNotBlank()) {
                                            append(" • ")
                                            append(transcodeStatus.episodeTitle)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${(transcodeStatus.overallProgress * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { transcodeStatus.overallProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onTranscodeAllNow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_compress_all_now))
                        }
                        if (transcodeStatus.statusMessage.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = transcodeStatus.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (compressedCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { showCompressedDownloadsDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_show_compressed_downloads, compressedCount))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section: Local Podcast Folder ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_section_local_folder),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { showLocalGuideDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_local_folder_guide))
                    }
                }
                val hasLocalFolder = settings.localFolderUri.isNotBlank()
                val localStatusText = if (hasLocalFolder) {
                    val canWrite = LocalPodcastScanner.hasWritePermission(context, settings.localFolderUri)
                    if (canWrite) stringResource(R.string.settings_local_folder_rw)
                    else stringResource(R.string.settings_local_folder_ro)
                } else {
                    stringResource(R.string.settings_local_folder_none)
                }
                Text(
                    text = localStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { localFolderLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_choose_folder))
                    }
                    if (hasLocalFolder) {
                        OutlinedButton(
                            onClick = { onSelectLocalFolderUri("") }
                        ) {
                            Text(stringResource(R.string.action_disconnect_folder))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.appLanguage == "en",
                        onClick = { onChangeAppLanguage("en") },
                        label = { Text(stringResource(R.string.settings_language_en)) }
                    )
                    FilterChip(
                        selected = settings.appLanguage == "de",
                        onClick = { onChangeAppLanguage("de") },
                        label = { Text(stringResource(R.string.settings_language_de)) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_backup_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_backup_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("PurePodcast_backup.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export_backup))
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_backup))
                    }
                }

                if (!backupStatus.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = backupStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    if (showLocalGuideDialog) {
        AlertDialog(
            onDismissRequest = { showLocalGuideDialog = false },
            title = { Text(stringResource(R.string.local_guide_title)) },
            text = { Text(stringResource(R.string.local_guide_body)) },
            confirmButton = {
                TextButton(onClick = { showLocalGuideDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showCompressedDownloadsDialog) {
        val compressedList = remember(allEpisodes) {
            allEpisodes.filter { item ->
                !item.podcast.isLocal &&
                    item.episode.downloadState == DownloadState.DOWNLOADED &&
                    item.episode.markedForDeletionAtMs == 0L &&
                    item.episode.isTranscoded &&
                    item.episode.localFilePath?.startsWith("content://") != true
            }.sortedByDescending { it.episode.pubDateMs }
        }
        val totalCurrentBytes = remember(compressedList) {
            compressedList.sumOf { item ->
                item.episode.fileSizeBytes.takeIf { it > 0L }
                    ?: runCatching { java.io.File(item.episode.localFilePath.orEmpty()).length() }.getOrDefault(0L)
            }
        }
        val totalSavedBytes = remember(compressedList) {
            compressedList.sumOf { it.episode.transcodeSavedBytes.coerceAtLeast(0L) }
        }

        AlertDialog(
            onDismissRequest = { showCompressedDownloadsDialog = false },
            title = { Text(stringResource(R.string.dialog_compressed_downloads_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.compressed_downloads_total_summary,
                            compressedList.size,
                            formatSizeMb(totalCurrentBytes),
                            formatSizeMb(totalSavedBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(compressedList, key = { it.episode.id }) { item ->
                            val ep = item.episode
                            val pod = item.podcast
                            val currentBytes = ep.fileSizeBytes.takeIf { it > 0L }
                                ?: runCatching { java.io.File(ep.localFilePath.orEmpty()).length() }.getOrDefault(0L)
                            val savedBytes = ep.transcodeSavedBytes.coerceAtLeast(0L)
                            val origBytes = currentBytes + savedBytes
                            val savedPct = if (origBytes > 0L && savedBytes > 0L) {
                                ((savedBytes * 100L) / origBytes).toInt().coerceIn(1, 99)
                            } else {
                                0
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PodcastImage(
                                    url = ep.artworkUrl.ifEmpty { pod.artworkUrl },
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ep.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pod.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = if (savedBytes > 0L) {
                                            stringResource(
                                                R.string.compressed_episode_saved_detail,
                                                formatSizeMb(origBytes),
                                                formatSizeMb(currentBytes),
                                                formatSizeMb(savedBytes),
                                                savedPct
                                            )
                                        } else {
                                            stringResource(
                                                R.string.compressed_episode_size_only,
                                                formatSizeMb(currentBytes)
                                            )
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.alpha(0.35f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCompressedDownloadsDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    val previewItem = remember(previewEpisodeId, allEpisodes) {
        previewEpisodeId?.let { id -> allEpisodes.firstOrNull { it.episode.id == id } }
    }
    if (previewItem != null) {
        val canDelete = remember(previewItem.podcast.isLocal, settings.localFolderUri) {
            !previewItem.podcast.isLocal || LocalPodcastScanner.hasWritePermission(context, settings.localFolderUri)
        }
        EpisodePreviewSheet(
            episode = previewItem.episode,
            podcast = previewItem.podcast,
            textScale = settings.textScale,
            canDeleteDownload = canDelete,
            onTextScaleChange = onTextScaleChange,
            onPauseMainPlayer = onPauseMainPlayer,
            onPlayInMainPlayer = {
                onPlayEpisode(previewItem.episode, previewItem.podcast)
                previewEpisodeId = null
            },
            onAddToQueueFront = {
                onAddToQueueFront(previewItem.episode)
                previewEpisodeId = null
            },
            onAddToQueueEnd = {
                onAddToQueueEnd(previewItem.episode)
                previewEpisodeId = null
            },
            onDownloadClick = {
                val ep = previewItem.episode
                when {
                    ep.markedForDeletionAtMs > 0L -> onRestoreFromTrash(ep)
                    ep.downloadState == DownloadState.DOWNLOADED -> onDeleteDownload(ep)
                    else -> onDownloadEpisode(ep)
                }
            },
            onToggleHidden = {
                onToggleHidden(previewItem.episode)
                previewEpisodeId = null
            },
            onTogglePlayed = { onTogglePlayed(previewItem.episode) },
            onToggleFavorite = { onToggleFavorite(previewItem.episode) },
            onDismiss = { previewEpisodeId = null }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
private fun PodcastThumbnailCard(
    item: PodcastWithStats,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PodcastImage(
                url = item.podcast.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.unplayedCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(item.unplayedCount.toString())
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.podcast.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeEpisodeRow(
    item: EpisodeWithPodcast,
    isActive: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit
) {
    val ep = item.episode
    val pod = item.podcast
    val artworkUrl = ep.artworkUrl.ifEmpty { pod.artworkUrl }
    val pubDateText = remember(ep.pubDateMs) { formatPubDate(ep.pubDateMs) }
    val durationText = remember(ep.durationMs) { formatDuration(ep.durationMs) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PodcastImage(
                url = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .alpha(if (ep.isPlayed) 0.55f else 1f)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOfNotNull(
                    pod.title.takeIf { it.isNotBlank() },
                    pubDateText.takeIf { it.isNotBlank() },
                    durationText.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ep.positionMs > 0L && !ep.isPlayed && ep.durationMs > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.episode_remaining,
                            formatDuration((ep.durationMs - ep.positionMs).coerceAtLeast(0L))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (ep.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.action_favorite),
                    tint = if (ep.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (ep.downloadState) {
                DownloadState.DOWNLOADING -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { ep.downloadProgress / 100f },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                DownloadState.QUEUED -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                DownloadState.FAILED -> {
                    if (!pod.isLocal) {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = stringResource(R.string.cd_download_failed),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                DownloadState.DOWNLOADED -> {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = stringResource(R.string.cd_downloaded),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                else -> {
                    if (!pod.isLocal) {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.cd_download),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (ep.positionMs > 0L && !ep.isPlayed && ep.durationMs > 0L) {
            val progressFraction = (ep.positionMs.toFloat() / ep.durationMs.toFloat()).coerceIn(0.02f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

private fun formatSizeMb(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (mb >= 100.0) {
        String.format(java.util.Locale.getDefault(), "%.0f MB", mb)
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    }
}
