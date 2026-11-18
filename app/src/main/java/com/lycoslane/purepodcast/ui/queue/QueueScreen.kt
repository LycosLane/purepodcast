package com.lycoslane.purepodcast.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queue: List<EpisodeWithPodcast>,
    currentEpisode: EpisodeEntity?,
    currentPodcast: PodcastEntity?,
    isPlaying: Boolean = false,
    scrollToggleTrigger: Int = 0,
    onScrollToggleConsumed: () -> Unit = {},
    onOpenNowPlaying: () -> Unit,
    onOpenPodcast: (Long) -> Unit = {},
    onPlay: (EpisodeEntity, PodcastEntity) -> Unit,
    onDownload: (Long) -> Unit = {},
    onRemove: (Long) -> Unit,
    onClear: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val highlightedEpisodeId = remember(queue, currentEpisode?.id) {
        val currentId = currentEpisode?.id
        if (currentId != null && queue.any { it.episode.id == currentId }) {
            currentId
        } else {
            queue.firstOrNull { it.episode.positionMs > 0L && !it.episode.isPlayed }?.episode?.id
        }
    }

    LaunchedEffect(scrollToggleTrigger) {
        if (scrollToggleTrigger > 0) {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (isAtTop && listState.canScrollForward) {
                    listState.animateScrollToItem(totalItems - 1)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
            onScrollToggleConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.queue_clear))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.queue_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.queue_empty_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(queue, key = { it.episode.id }) { item ->
                    val isCurrentInPlayer = item.episode.id == currentEpisode?.id
                    val isHighlighted = item.episode.id == highlightedEpisodeId
                    val ep = item.episode
                    val posMs = if (isCurrentInPlayer && currentEpisode != null && currentEpisode.positionMs > 0L) {
                        currentEpisode.positionMs
                    } else {
                        ep.positionMs
                    }
                    val durMs = if (isCurrentInPlayer && currentEpisode != null && currentEpisode.durationMs > 0L) {
                        currentEpisode.durationMs
                    } else {
                        ep.durationMs
                    }
                    val progressFraction = when {
                        ep.isPlayed -> 1f
                        posMs > 0L && durMs > 0L -> (posMs.toFloat() / durMs.toFloat()).coerceIn(0.02f, 1f)
                        else -> 0f
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isHighlighted) {
                                    Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                if (isCurrentInPlayer && isPlaying) {
                                    onOpenNowPlaying()
                                } else {
                                    onPlay(item.episode, item.podcast)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastImage(
                                url = item.episode.artworkUrl.ifEmpty { item.podcast.artworkUrl },
                                contentDescription = item.podcast.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClickLabel = stringResource(R.string.action_open_podcast)) {
                                        onOpenPodcast(item.podcast.id)
                                    }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                if (isHighlighted) {
                                    val nowPlayingActive = isCurrentInPlayer && isPlaying
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (nowPlayingActive) Icons.Default.GraphicEq else Icons.Default.PlayCircleOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(
                                                if (nowPlayingActive) R.string.queue_now_playing else R.string.queue_last_played
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Text(
                                    text = item.episode.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sub = buildString {
                                    append(item.podcast.title)
                                    if (durMs > 0L) {
                                        append(" • ")
                                        if (posMs > 0L && !ep.isPlayed) {
                                            append(
                                                stringResource(
                                                    R.string.episode_remaining,
                                                    formatDuration((durMs - posMs).coerceAtLeast(0L))
                                                )
                                            )
                                        } else {
                                            append(formatDuration(durMs))
                                        }
                                    }
                                }
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHighlighted) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!item.podcast.isLocal) {
                                when (item.episode.downloadState) {
                                    DownloadState.DOWNLOADING -> {
                                        Box(
                                            modifier = Modifier.size(48.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { item.episode.downloadProgress / 100f },
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
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                    DownloadState.DOWNLOADED -> {
                                        Box(
                                            modifier = Modifier.size(48.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (item.episode.markedForDeletionAtMs > 0L) {
                                                Icon(
                                                    Icons.Default.RestoreFromTrash,
                                                    contentDescription = stringResource(R.string.action_restore_trash),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.DownloadDone,
                                                    contentDescription = stringResource(R.string.cd_downloaded),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                    DownloadState.FAILED -> {
                                        IconButton(onClick = { onDownload(item.episode.id) }) {
                                            Icon(
                                                Icons.Default.ErrorOutline,
                                                contentDescription = stringResource(R.string.cd_download_failed),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    DownloadState.NONE -> {
                                        IconButton(onClick = { onDownload(item.episode.id) }) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = stringResource(R.string.cd_download),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = { onRemove(item.episode.id) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove))
                            }
                        }
                        if (progressFraction > 0f) {
                            LinearProgressIndicator(
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
                    HorizontalDivider(modifier = Modifier.alpha(0.35f))
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.dialog_clear_queue_title)) },
            text = { Text(stringResource(R.string.dialog_clear_queue_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    }
                ) {
                    Text(stringResource(R.string.queue_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
