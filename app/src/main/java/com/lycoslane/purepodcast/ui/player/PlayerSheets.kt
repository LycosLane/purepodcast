package com.lycoslane.purepodcast.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.PlayerUiState
import com.lycoslane.purepodcast.core.network.ChaptersParser
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import kotlinx.coroutines.launch
import java.util.Locale

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
                    contentDescription = null,
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
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play
                        )
                    )
                }
                IconButton(onClick = onForward30) {
                    Icon(
                        Icons.Default.Forward30,
                        contentDescription = stringResource(R.string.cd_forward_30)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FullPlayerSheet(
    state: PlayerUiState,
    hasQueue: Boolean,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleSkipSilence: () -> Unit,
    onSetSilenceIntensity: (Int) -> Unit,
    onSetSleepTimer: (Int) -> Unit
) {
    val ep = state.currentEpisode ?: return
    val pod = state.currentPodcast ?: return

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showNotes by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showSpeedRow by remember { mutableStateOf(false) }
    var showSleepRow by remember { mutableStateOf(false) }
    var showSilenceIntensityRow by remember { mutableStateOf(false) }
    var showCoverLightbox by remember { mutableStateOf(false) }
    var showReaderDialog by remember { mutableStateOf(false) }
    var scrubPos by remember(ep.id) { mutableStateOf<Float?>(null) }

    fun expandSheet() {
        scope.launch { runCatching { sheetState.expand() } }
    }

    androidx.compose.runtime.LaunchedEffect(showNotes, showChapters, textScale) {
        if (showNotes || showChapters) {
            runCatching { sheetState.expand() }
        }
    }

    val artworkUrl = ep.artworkUrl.ifEmpty { pod.artworkUrl }
    val plainNotes = remember(ep.descriptionHtml) {
        htmlToPlainText(ep.descriptionHtml)
    }
    val chapters = remember(ep.id, ep.chaptersJson) {
        ChaptersParser.decodeChaptersJson(ep.chaptersJson)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PodcastImage(
                url = artworkUrl,
                contentDescription = stringResource(R.string.cd_podcast_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showCoverLightbox = true }
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = ep.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pod.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))

            val maxDur = state.durationMs.coerceAtLeast(1L).toFloat()
            val displayPosMs = scrubPos?.toLong()
                ?: state.positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
            val posFormatted = formatDuration(displayPosMs)
            val durFormatted = formatDuration(state.durationMs)

            Slider(
                value = displayPosMs.toFloat(),
                onValueChange = { scrubPos = it },
                onValueChangeFinished = {
                    scrubPos?.let { target ->
                        onSeekTo(target.toLong())
                        scrubPos = null
                    }
                },
                valueRange = 0f..maxDur,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = "$posFormatted / $durFormatted" }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(posFormatted, style = MaterialTheme.typography.labelSmall)
                Text(durFormatted, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevEpisode,
                    enabled = hasQueue,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.cd_prev_episode),
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { onSeekBy(-10_000L) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.cd_rewind_10),
                        modifier = Modifier.size(32.dp)
                    )
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play
                        ),
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = { onSeekBy(30_000L) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Forward30,
                        contentDescription = stringResource(R.string.cd_forward_30),
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(
                    onClick = onNextEpisode,
                    enabled = hasQueue,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.cd_next_episode),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = showSpeedRow,
                    onClick = {
                        showSpeedRow = !showSpeedRow
                        showSleepRow = false
                        showSilenceIntensityRow = false
                        if (showSpeedRow) expandSheet()
                    },
                    label = { Text(String.format(Locale.getDefault(), "%.1fx", state.playbackSpeed)) },
                    leadingIcon = { Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp)) }
                )

                val silenceSelected = state.skipSilence
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (silenceSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = if (silenceSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = onToggleSkipSilence,
                                onLongClick = {
                                    showSilenceIntensityRow = !showSilenceIntensityRow
                                    showSpeedRow = false
                                    showSleepRow = false
                                    if (showSilenceIntensityRow) expandSheet()
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (silenceSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = stringResource(R.string.player_silence),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (silenceSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                FilterChip(
                    selected = state.sleepTimerRemainingMs > 0L || showSleepRow,
                    onClick = {
                        showSleepRow = !showSleepRow
                        showSpeedRow = false
                        showSilenceIntensityRow = false
                        if (showSleepRow) expandSheet()
                    },
                    label = {
                        Text(
                            if (state.sleepTimerRemainingMs > 0L) formatDuration(state.sleepTimerRemainingMs)
                            else stringResource(R.string.player_sleep)
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Bedtime, null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = showNotes,
                    onClick = {
                        showNotes = !showNotes
                        if (showNotes) {
                            showChapters = false
                            expandSheet()
                        } else if (!showChapters) {
                            scope.launch { runCatching { sheetState.partialExpand() } }
                        }
                    },
                    label = { Text(stringResource(R.string.player_notes)) },
                    leadingIcon = { Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp)) }
                )
                if (chapters.isNotEmpty()) {
                    FilterChip(
                        selected = showChapters,
                        onClick = {
                            showChapters = !showChapters
                            if (showChapters) {
                                showNotes = false
                                expandSheet()
                            } else if (!showNotes) {
                                scope.launch { runCatching { sheetState.partialExpand() } }
                            }
                        },
                        label = { Text(stringResource(R.string.player_chapters_count, chapters.size)) },
                        leadingIcon = { Icon(Icons.Default.FormatListBulleted, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            if (showSilenceIntensityRow) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.silence_intensity_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val levels = listOf(
                        0 to stringResource(R.string.silence_soft),
                        1 to stringResource(R.string.silence_medium),
                        2 to stringResource(R.string.silence_strong)
                    )
                    levels.forEach { (level, label) ->
                        FilterChip(
                            selected = state.silenceIntensity == level,
                            onClick = {
                                onSetSilenceIntensity(level)
                                if (!state.skipSilence) onToggleSkipSilence()
                                showSilenceIntensityRow = false
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (showSpeedRow) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f).forEach { speed ->
                        FilterChip(
                            selected = state.playbackSpeed == speed,
                            onClick = { onSetSpeed(speed) },
                            label = { Text(String.format(Locale.getDefault(), "%.1fx", speed)) }
                        )
                    }
                }
            }

            if (showSleepRow) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val offLabel = stringResource(R.string.sleep_off)
                    listOf(0, 15, 30, 45, 60).forEach { mins ->
                        val label = if (mins == 0) offLabel else stringResource(R.string.sleep_minutes_short, mins)
                        FilterChip(
                            selected = mins == 0 && state.sleepTimerRemainingMs <= 0L,
                            onClick = { onSetSleepTimer(mins); showSleepRow = false },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (showChapters && chapters.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                val currentChapterIdx = chapters.indexOfLast { it.startMs <= displayPosMs }
                chapters.forEachIndexed { idx, ch ->
                    val isCurrent = idx == currentChapterIdx
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onSeekTo(ch.startMs) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(ch.startMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            text = ch.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (showNotes) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                if (plainNotes.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.hint_tap_to_read),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    PinchZoomText(
                        text = plainNotes,
                        textScale = textScale,
                        onTextScaleChange = onTextScaleChange,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { showReaderDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.no_show_notes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showCoverLightbox) {
        CoverLightboxDialog(
            imageUrl = artworkUrl,
            title = ep.title,
            onDismiss = { showCoverLightbox = false }
        )
    }

    if (showReaderDialog && plainNotes.isNotBlank()) {
        TextReaderDialog(
            title = ep.title,
            subtitle = pod.title,
            text = plainNotes,
            textScale = textScale,
            onTextScaleChange = onTextScaleChange,
            onDismiss = { showReaderDialog = false }
        )
    }
}
