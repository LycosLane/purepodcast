package com.lycoslane.purepodcast.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.detail.EpisodePreviewSheet

private enum class HomeEpisodeFilter {
    ALL,
    RECENT_14D,
    IN_PROGRESS,
    DOWNLOADED,
    FAVORITES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    podcasts: List<PodcastWithStats>,
    allEpisodes: List<EpisodeWithPodcast> = emptyList(),
    queuedEpisodeIds: Set<Long> = emptySet(),
    activeEpisodeId: Long? = null,
    isRefreshing: Boolean,
    settings: AppSettings,
    onRefreshAll: () -> Unit,
    onPodcastClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onOpenSettings: () -> Unit,
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
    onToggleShowSubscribed: (Boolean) -> Unit,
    onToggleShowLocal: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showEpisodesView by rememberSaveable { mutableStateOf(false) }
    var activeEpisodeFilter by rememberSaveable { mutableStateOf(HomeEpisodeFilter.ALL) }
    var previewEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }

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
                    IconButton(onClick = onOpenSettings) {
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
                                        isInQueue = item.episode.id in queuedEpisodeIds,
                                        onClick = { previewEpisodeId = item.episode.id },
                                        onToggleFavorite = { onToggleFavorite(item.episode) },
                                        onAddToQueue = { onAddToQueueEnd(item.episode) },
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
private fun PodcastThumbnailCard(
    item: PodcastWithStats,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun HomeEpisodeRow(
    item: EpisodeWithPodcast,
    isActive: Boolean,
    isInQueue: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToQueue: () -> Unit,
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
                DownloadState.DOWNLOADED -> {
                    if (isInQueue) {
                        Spacer(Modifier.size(48.dp))
                    } else {
                        IconButton(onClick = onAddToQueue) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.action_add_queue),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (ep.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.action_favorite),
                    tint = if (ep.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
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
