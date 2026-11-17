package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.htmlToPlainText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    podcast: PodcastEntity?,
    episodes: List<EpisodeEntity>,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSort: (Boolean) -> Unit,
    onDisableAutoDownload: () -> Unit,
    onUnsubscribe: () -> Unit,
    onPlayEpisode: (EpisodeEntity) -> Unit,
    onDownloadEpisode: (EpisodeEntity) -> Unit,
    onDeleteDownload: (EpisodeEntity) -> Unit,
    onAddToQueue: (EpisodeEntity) -> Unit,
    onTogglePlayed: (EpisodeEntity) -> Unit,
    onDownloadNewerFrom: (EpisodeEntity) -> Unit,
    onCleanupOlderThan: (EpisodeEntity) -> Unit
) {
    var selectedEpisodeForMenu by remember { mutableStateOf<EpisodeEntity?>(null) }
    var selectedEpisodeForNotes by remember { mutableStateOf<EpisodeEntity?>(null) }
    var expandDescription by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = podcast?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_back))
                    }
                },
                actions = {
                    if (podcast != null) {
                        IconButton(onClick = { onToggleSort(!podcast.sortAscending) }) {
                            Icon(Icons.Default.SwapVert, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_toggle_sort))
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_refresh_feed))
                        }
                        IconButton(onClick = onUnsubscribe) {
                            Icon(Icons.Default.Delete, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_remove_podcast))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (podcast == null) return@Scaffold

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = podcast.artworkUrl,
                            contentDescription = podcast.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = podcast.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (podcast.author.isNotBlank()) {
                                Text(
                                    text = podcast.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (podcast.sortAscending) {
                                    androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.sort_asc)
                                } else {
                                    androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.sort_desc)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (podcast.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = htmlToPlainText(podcast.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expandDescription) 30 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = { expandDescription = !expandDescription },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (expandDescription) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.show_less)
                                else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.show_more)
                            )
                        }
                    }

                    if (podcast.autoDownloadFromPubDateMs > 0L) {
                        AssistChip(
                            onClick = onDisableAutoDownload,
                            label = {
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.lycoslane.purepodcast.R.string.auto_download_active,
                                        formatPubDate(podcast.autoDownloadFromPubDateMs)
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
                HorizontalDivider()
            }

            items(episodes, key = { it.id }) { episode ->
                EpisodeSwipeRow(
                    episode = episode,
                    onClick = { onPlayEpisode(episode) },
                    onLongClick = { selectedEpisodeForMenu = episode },
                    onSwipeRightQueue = { onAddToQueue(episode) },
                    onSwipeLeftCleanup = {
                        if (episode.downloadState == DownloadState.DOWNLOADED) {
                            onDeleteDownload(episode)
                        } else {
                            onTogglePlayed(episode)
                        }
                    },
                    onDownloadClick = {
                        if (episode.downloadState == DownloadState.DOWNLOADED) {
                            onDeleteDownload(episode)
                        } else if (episode.downloadState == DownloadState.NONE || episode.downloadState == DownloadState.FAILED) {
                            onDownloadEpisode(episode)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.alpha(0.4f))
            }
        }
        }
    }

    // Long-press action sheet (all actions at thumb reach, no nested submenus)
    selectedEpisodeForMenu?.let { ep ->
        ModalBottomSheet(onDismissRequest = { selectedEpisodeForMenu = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_play)) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                    modifier = Modifier.clickable(onClick = {
                        onPlayEpisode(ep)
                        selectedEpisodeForMenu = null
                    })
                )
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_show_notes)) },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    modifier = Modifier.clickable(onClick = {
                        selectedEpisodeForNotes = ep
                        selectedEpisodeForMenu = null
                    })
                )
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_add_queue)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    modifier = Modifier.clickable(onClick = {
                        onAddToQueue(ep)
                        selectedEpisodeForMenu = null
                    })
                )
                ListItem(
                    headlineContent = {
                        Text(
                            if (ep.downloadState == DownloadState.DOWNLOADED) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_delete_download)
                            else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_download_offline)
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (ep.downloadState == DownloadState.DOWNLOADED) Icons.Default.Delete
                            else Icons.Default.Download,
                            null
                        )
                    },
                    modifier = Modifier.clickable(onClick = {
                        if (ep.downloadState == DownloadState.DOWNLOADED) onDeleteDownload(ep)
                        else onDownloadEpisode(ep)
                        selectedEpisodeForMenu = null
                    })
                )
                ListItem(
                    headlineContent = {
                        Text(
                            if (ep.isPlayed) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_mark_unplayed)
                            else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_mark_played)
                        )
                    },
                    leadingContent = { Icon(Icons.Default.CheckCircle, null) },
                    modifier = Modifier.clickable(onClick = {
                        onTogglePlayed(ep)
                        selectedEpisodeForMenu = null
                    })
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_auto_download_newer)) },
                    supportingContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_auto_download_newer_sub)) },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                    modifier = Modifier.clickable(onClick = {
                        onDownloadNewerFrom(ep)
                        selectedEpisodeForMenu = null
                    })
                )
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cleanup_older)) },
                    supportingContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cleanup_older_sub)) },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    modifier = Modifier.clickable(onClick = {
                        onCleanupOlderThan(ep)
                        selectedEpisodeForMenu = null
                    })
                )
            }
        }
    }

    // Show Notes Sheet
    selectedEpisodeForNotes?.let { ep ->
        ModalBottomSheet(onDismissRequest = { selectedEpisodeForNotes = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatPubDate(ep.pubDateMs)} • ${formatDuration(ep.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = htmlToPlainText(ep.descriptionHtml).ifBlank {
                        androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.no_show_notes)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeSwipeRow(
    episode: EpisodeEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeRightQueue: () -> Unit,
    onSwipeLeftCleanup: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeRightQueue()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeLeftCleanup()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val icon = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.AutoMirrored.Filled.PlaylistAdd
                else -> Icons.Default.Delete
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (episode.isPlayed) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dateStr = formatPubDate(episode.pubDateMs)
                    if (dateStr.isNotEmpty()) {
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.durationMs > 0L) {
                        Text(
                            text = formatDuration(episode.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.positionMs > 0L && !episode.isPlayed) {
                        Text(
                            text = "• bei ${formatDuration(episode.positionMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            when (episode.downloadState) {
                DownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        progress = { episode.downloadProgress / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                }
                DownloadState.QUEUED -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
                DownloadState.DOWNLOADED -> {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = "Heruntergeladen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Herunterladen"
                        )
                    }
                }
            }
        }
    }
}
