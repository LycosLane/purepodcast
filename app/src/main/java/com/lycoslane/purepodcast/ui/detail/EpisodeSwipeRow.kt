package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeSwipeRow(
    episode: EpisodeEntity,
    isActive: Boolean,
    isTrashMode: Boolean,
    showDownloadAction: Boolean = true,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeRightQueue: () -> Unit,
    onSwipeLeftAction: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val latestRight by rememberUpdatedState(onSwipeRightQueue)
    val latestLeft by rememberUpdatedState(onSwipeLeftAction)
    val haptic = LocalHapticFeedback.current
    val thresholdFraction = 0.45f
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var dismissStateHolder: SwipeToDismissBoxState? = null

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                true
            } else {
                val offset = runCatching { dismissStateHolder?.requireOffset() }.getOrNull() ?: 0f
                val fraction = if (rowWidthPx > 0) {
                    abs(offset) / rowWidthPx.toFloat()
                } else {
                    0f
                }
                fraction >= thresholdFraction
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * thresholdFraction }
    )
    dismissStateHolder = dismissState

    val swipeFraction by remember {
        derivedStateOf {
            val offset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
            if (rowWidthPx > 0) {
                (abs(offset) / rowWidthPx.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val reachedThreshold = swipeFraction >= thresholdFraction

    LaunchedEffect(reachedThreshold) {
        if (reachedThreshold) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(dismissState.currentValue) {
        val value = dismissState.currentValue
        if (value != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> latestRight()
                SwipeToDismissBoxValue.EndToStart -> latestLeft()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
    }

    val a11yQueueLabel = stringResource(R.string.action_add_queue)
    val a11yLeftLabel = stringResource(
        when {
            isTrashMode -> R.string.swipe_delete
            episode.isHidden -> R.string.swipe_unhide
            else -> R.string.swipe_hide
        }
    )
    val a11yPlayedLabel = stringResource(
        if (episode.isPlayed) R.string.action_mark_unplayed
        else R.string.action_mark_played
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.onSizeChanged { rowWidthPx = it.width },
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val alignment = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val baseColor = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF2E7D32)
                SwipeToDismissBoxValue.EndToStart -> if (isTrashMode) MaterialTheme.colorScheme.error else Color(0xFFBF360C)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val bgColor = if (reachedThreshold || dir == SwipeToDismissBoxValue.Settled) {
                baseColor
            } else {
                baseColor.copy(alpha = 0.35f + 0.35f * (swipeFraction / thresholdFraction))
            }
            val icon = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.AutoMirrored.Filled.PlaylistAdd
                SwipeToDismissBoxValue.EndToStart -> when {
                    isTrashMode -> Icons.Default.DeleteForever
                    episode.isHidden -> Icons.Default.Visibility
                    else -> Icons.Default.VisibilityOff
                }
                else -> Icons.AutoMirrored.Filled.PlaylistAdd
            }
            val label = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> stringResource(R.string.swipe_queue)
                SwipeToDismissBoxValue.EndToStart -> when {
                    isTrashMode -> stringResource(R.string.swipe_delete)
                    episode.isHidden -> stringResource(R.string.swipe_unhide)
                    else -> stringResource(R.string.swipe_hide)
                }
                else -> ""
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Row(
                        modifier = Modifier.alpha(if (reachedThreshold) 1f else 0.7f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (dir == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(icon, contentDescription = null, tint = Color.White)
                            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Icon(icon, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(a11yQueueLabel) {
                            latestRight(); true
                        },
                        CustomAccessibilityAction(a11yLeftLabel) {
                            latestLeft(); true
                        },
                        CustomAccessibilityAction(a11yPlayedLabel) {
                            onDoubleClick(); true
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (episode.isPlayed && !isActive) 0.6f else 1f)
                ) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val dateStr = remember(episode.pubDateMs) { formatPubDate(episode.pubDateMs) }
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
                            val remainingMs = (episode.durationMs - episode.positionMs).coerceAtLeast(0L)
                            val progressText = if (episode.durationMs > 0L && remainingMs > 0L) {
                                stringResource(R.string.episode_remaining, formatDuration(remainingMs))
                            } else {
                                stringResource(R.string.at_position, formatDuration(episode.positionMs))
                            }
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (showDownloadAction) {
                    Spacer(Modifier.width(8.dp))

                    when (episode.downloadState) {
                        DownloadState.DOWNLOADING -> {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { episode.downloadProgress / 100f },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                        }
                        DownloadState.QUEUED -> {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        DownloadState.DOWNLOADED -> {
                            IconButton(onClick = onDownloadClick) {
                                if (episode.markedForDeletionAtMs > 0L) {
                                    Icon(
                                        Icons.Default.RestoreFromTrash,
                                        contentDescription = stringResource(R.string.action_restore_trash),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.DownloadDone,
                                        contentDescription = stringResource(R.string.cd_downloaded),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        DownloadState.FAILED -> {
                            IconButton(onClick = onDownloadClick) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = stringResource(R.string.cd_download_failed),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        else -> {
                            IconButton(onClick = onDownloadClick) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.cd_download)
                                )
                            }
                        }
                    }
                }
            }
            if (episode.positionMs > 0L && !episode.isPlayed && episode.durationMs > 0L) {
                val progressFraction = (episode.positionMs.toFloat() / episode.durationMs.toFloat()).coerceIn(0.02f, 1f)
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
    }
}
