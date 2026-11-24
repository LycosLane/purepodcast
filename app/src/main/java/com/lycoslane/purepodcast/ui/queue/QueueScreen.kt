package com.lycoslane.purepodcast.ui.queue

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    queue: List<EpisodeWithPodcast>,
    currentEpisode: EpisodeEntity?,
    isPlaying: Boolean = false,
    scrollToggleTrigger: Int = 0,
    onScrollToggleConsumed: () -> Unit = {},
    onOpenNowPlaying: () -> Unit,
    onOpenPodcast: (Long) -> Unit = {},
    onPlay: (EpisodeEntity, PodcastEntity) -> Unit,
    onDownload: (EpisodeEntity) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {},
    onMoveNext: (Long, Long?) -> Unit = { _, _ -> },
    onRemove: (Long) -> Unit,
    onRemoveMultiple: (List<Long>) -> Unit = {},
    onDeleteDownloadsKeepInQueue: (List<Long>) -> Unit = {},
    onDeleteDownloadsAndRemove: (List<Long>) -> Unit = {},
    onClear: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var menuEpisodeId by remember { mutableStateOf<Long?>(null) }

    val displayQueue = remember { mutableStateListOf<EpisodeWithPodcast>() }
    var isDragging by remember { mutableStateOf(false) }
    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(queue) {
        if (!isDragging) {
            displayQueue.clear()
            displayQueue.addAll(queue)
        }
        val validIds = queue.map { it.episode.id }.toSet()
        selectedIds.removeAll { it !in validIds }
        if (queue.isEmpty()) {
            isEditMode = false
        }
    }

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
                title = {
                    Text(
                        text = if (isEditMode) {
                            stringResource(R.string.queue_selected_count, selectedIds.size)
                        } else {
                            stringResource(R.string.queue_title)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        if (isEditMode) {
                            IconButton(
                                onClick = {
                                    isEditMode = false
                                    selectedIds.clear()
                                }
                            ) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_done))
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    isEditMode = true
                                    selectedIds.clear()
                                }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.queue_edit))
                            }
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.queue_clear))
                            }
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
                        Icons.AutoMirrored.Filled.QueueMusic,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isEditMode) {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val allSelected = selectedIds.size == displayQueue.size && displayQueue.isNotEmpty()
                        val hasSelection = selectedIds.isNotEmpty()
                        val selectedNonLocalDownloaded = displayQueue.any { item ->
                            item.episode.id in selectedIds &&
                                !item.podcast.isLocal &&
                                item.episode.downloadState == DownloadState.DOWNLOADED
                        }
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = allSelected,
                                onClick = {
                                    if (allSelected) {
                                        selectedIds.clear()
                                    } else {
                                        selectedIds.clear()
                                        selectedIds.addAll(displayQueue.map { it.episode.id })
                                    }
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            if (allSelected) R.string.queue_deselect_all else R.string.queue_select_all
                                        )
                                    )
                                }
                            )
                            TextButton(
                                enabled = hasSelection,
                                onClick = {
                                    onRemoveMultiple(selectedIds.toList())
                                    selectedIds.clear()
                                }
                            ) {
                                Text(stringResource(R.string.action_remove))
                            }
                            TextButton(
                                enabled = selectedNonLocalDownloaded,
                                onClick = {
                                    onDeleteDownloadsKeepInQueue(selectedIds.toList())
                                    selectedIds.clear()
                                }
                            ) {
                                Text(stringResource(R.string.queue_delete_files_keep))
                            }
                            TextButton(
                                enabled = selectedNonLocalDownloaded,
                                onClick = {
                                    onDeleteDownloadsAndRemove(selectedIds.toList())
                                    selectedIds.clear()
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.queue_delete_and_remove),
                                    color = if (selectedNonLocalDownloaded) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(displayQueue, key = { _, item -> item.episode.id }) { _, item ->
                        val isCurrentInPlayer = item.episode.id == currentEpisode?.id
                        val isHighlighted = item.episode.id == highlightedEpisodeId
                        val isDragged = item.episode.id == draggedItemId
                        val isSelected = item.episode.id in selectedIds
                        val ep = item.episode
                        val posMs = if (isCurrentInPlayer && currentEpisode.positionMs > 0L) {
                            currentEpisode.positionMs
                        } else {
                            ep.positionMs
                        }
                        val durMs = if (isCurrentInPlayer && currentEpisode.durationMs > 0L) {
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
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragged) dragDeltaY else 0f
                                    if (isDragged) {
                                        shadowElevation = 10f
                                        scaleX = 1.01f
                                        scaleY = 1.01f
                                    }
                                }
                                .then(
                                    when {
                                        isDragged -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        isHighlighted -> Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                                        else -> Modifier
                                    }
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (isEditMode) {
                                            if (isSelected) selectedIds.remove(ep.id) else selectedIds.add(ep.id)
                                        } else if (isCurrentInPlayer && isPlaying) {
                                            onOpenNowPlaying()
                                        } else {
                                            onPlay(item.episode, item.podcast)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isEditMode && !isDragging) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuEpisodeId = ep.id
                                        }
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isEditMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!isSelected) selectedIds.add(ep.id)
                                            } else {
                                                selectedIds.remove(ep.id)
                                            }
                                        }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 40.dp, height = 48.dp)
                                            .pointerInput(ep.id) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        isDragging = true
                                                        draggedItemId = ep.id
                                                        dragDeltaY = 0f
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragEnd = {
                                                        val reorderedIds = displayQueue.map { it.episode.id }
                                                        draggedItemId = null
                                                        dragDeltaY = 0f
                                                        isDragging = false
                                                        onReorder(reorderedIds)
                                                    },
                                                    onDragCancel = {
                                                        draggedItemId = null
                                                        dragDeltaY = 0f
                                                        isDragging = false
                                                        displayQueue.clear()
                                                        displayQueue.addAll(queue)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragDeltaY += dragAmount.y
                                                        val currentIdx = displayQueue.indexOfFirst { it.episode.id == ep.id }
                                                        if (currentIdx < 0) return@detectDragGestures
                                                        val visible = listState.layoutInfo.visibleItemsInfo
                                                        val currentInfo = visible.firstOrNull { it.key == ep.id }
                                                            ?: return@detectDragGestures
                                                        val draggedCenter = currentInfo.offset + dragDeltaY + currentInfo.size / 2f

                                                        if (dragDeltaY > 0f && currentIdx < displayQueue.lastIndex) {
                                                            val nextKey = displayQueue[currentIdx + 1].episode.id
                                                            val nextInfo = visible.firstOrNull { it.key == nextKey }
                                                            if (nextInfo != null) {
                                                                val nextCenter = nextInfo.offset + nextInfo.size / 2f
                                                                if (draggedCenter > nextCenter) {
                                                                    val moved = displayQueue.removeAt(currentIdx)
                                                                    displayQueue.add(currentIdx + 1, moved)
                                                                    dragDeltaY -= (nextInfo.offset - currentInfo.offset)
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            }
                                                        } else if (dragDeltaY < 0f && currentIdx > 0) {
                                                            val prevKey = displayQueue[currentIdx - 1].episode.id
                                                            val prevInfo = visible.firstOrNull { it.key == prevKey }
                                                            if (prevInfo != null) {
                                                                val prevCenter = prevInfo.offset + prevInfo.size / 2f
                                                                if (draggedCenter < prevCenter) {
                                                                    val moved = displayQueue.removeAt(currentIdx)
                                                                    displayQueue.add(currentIdx - 1, moved)
                                                                    dragDeltaY += (currentInfo.offset - prevInfo.offset)
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragIndicator,
                                            contentDescription = stringResource(R.string.cd_reorder_queue),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                PodcastImage(
                                    url = item.episode.artworkUrl.ifEmpty { item.podcast.artworkUrl },
                                    contentDescription = item.podcast.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            enabled = !isEditMode,
                                            onClickLabel = stringResource(R.string.action_open_podcast)
                                        ) {
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
                                            if (item.episode.markedForDeletionAtMs > 0L) {
                                                Box(
                                                    modifier = Modifier.size(48.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.RestoreFromTrash,
                                                        contentDescription = stringResource(R.string.action_restore_trash),
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                        DownloadState.FAILED -> {
                                            IconButton(onClick = { onDownload(item.episode) }) {
                                                Icon(
                                                    Icons.Default.ErrorOutline,
                                                    contentDescription = stringResource(R.string.cd_download_failed),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        DownloadState.NONE -> {
                                            IconButton(onClick = { onDownload(item.episode) }) {
                                                Icon(
                                                    Icons.Default.Download,
                                                    contentDescription = stringResource(R.string.cd_download),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
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
    }

    val menuItem = remember(menuEpisodeId, displayQueue) {
        menuEpisodeId?.let { id -> displayQueue.firstOrNull { it.episode.id == id } }
    }
    if (menuItem != null) {
        val ep = menuItem.episode
        val pod = menuItem.podcast
        val isActive = ep.id == currentEpisode?.id
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { menuEpisodeId = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = ep.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pod.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (ep.id != highlightedEpisodeId && displayQueue.size > 1) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.action_play_next)) },
                        leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        modifier = Modifier.clickable {
                            menuEpisodeId = null
                            onMoveNext(ep.id, highlightedEpisodeId)
                        }
                    )
                }

                if (!pod.isLocal && (ep.downloadState == DownloadState.NONE || ep.downloadState == DownloadState.FAILED)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.action_download)) },
                        leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                        modifier = Modifier.clickable {
                            menuEpisodeId = null
                            onDownload(ep)
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_remove_queue)) },
                    leadingContent = { Icon(Icons.Default.Close, contentDescription = null) },
                    modifier = Modifier.clickable {
                        menuEpisodeId = null
                        onRemove(ep.id)
                    }
                )

                if (!pod.isLocal && ep.downloadState == DownloadState.DOWNLOADED && !isActive) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.queue_delete_files_keep)) },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        modifier = Modifier.clickable {
                            menuEpisodeId = null
                            onDeleteDownloadsKeepInQueue(listOf(ep.id))
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.queue_delete_and_remove),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable {
                            menuEpisodeId = null
                            onDeleteDownloadsAndRemove(listOf(ep.id))
                        }
                    )
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
