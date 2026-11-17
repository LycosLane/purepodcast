package com.lycoslane.purepodcast.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.core.audio.PlayerUiState
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.htmlToPlainText

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onForward30: () -> Unit,
    onExpand: () -> Unit
) {
    val ep = state.currentEpisode ?: return
    val pod = state.currentPodcast ?: return

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
    ) {
        Column {
            val progress = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PodcastImage(
                    url = ep.artworkUrl.ifEmpty { pod.artworkUrl },
                    contentDescription = ep.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ep.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
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
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onForward30) {
                    Icon(Icons.Default.Forward30, contentDescription = "+30s")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleSkipSilence: () -> Unit,
    onSetSleepTimer: (Int) -> Unit
) {
    val ep = state.currentEpisode ?: return
    val pod = state.currentPodcast ?: return

    var showNotes by remember { mutableStateOf(false) }
    var showSpeedRow by remember { mutableStateOf(false) }
    var showSleepRow by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PodcastImage(
                url = ep.artworkUrl.ifEmpty { pod.artworkUrl },
                contentDescription = ep.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(210.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = ep.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pod.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            val maxDur = state.durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = state.positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L)).toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..maxDur,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSeekBy(-10_000L) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Replay10, contentDescription = "-10s", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = { onSeekBy(30_000L) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Forward30, contentDescription = "+30s", modifier = Modifier.size(32.dp))
                }
            }

            Spacer(Modifier.height(18.dp))

            // Quick feature toggles (Speed, Silence-Skip, Sleep Timer, Show Notes)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = showSpeedRow,
                    onClick = { showSpeedRow = !showSpeedRow; showSleepRow = false },
                    label = { Text("${state.playbackSpeed}x") },
                    leadingIcon = { Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = state.skipSilence,
                    onClick = onToggleSkipSilence,
                    label = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.player_silence)) },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = state.sleepTimerRemainingMs > 0L || showSleepRow,
                    onClick = { showSleepRow = !showSleepRow; showSpeedRow = false },
                    label = {
                        Text(
                            if (state.sleepTimerRemainingMs > 0L) formatDuration(state.sleepTimerRemainingMs)
                            else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.player_sleep)
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Bedtime, null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = showNotes,
                    onClick = { showNotes = !showNotes },
                    label = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.player_notes)) },
                    leadingIcon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp)) }
                )
            }

            if (showSpeedRow) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f).forEach { speed ->
                        FilterChip(
                            selected = state.playbackSpeed == speed,
                            onClick = { onSetSpeed(speed) },
                            label = { Text("${speed}x") }
                        )
                    }
                }
            }

            if (showSleepRow) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val offLabel = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.sleep_off)
                    listOf(0 to offLabel, 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                        FilterChip(
                            selected = false,
                            onClick = { onSetSleepTimer(mins); showSleepRow = false },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (showNotes) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = htmlToPlainText(ep.descriptionHtml).ifBlank {
                        androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.no_show_notes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
