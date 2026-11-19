package com.lycoslane.purepodcast.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
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
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPlaybackSpeed
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class PlayerControlPanel {
    NONE,
    SPEED,
    SILENCE,
    SLEEP
}

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
            .clickable(
                onClickLabel = stringResource(R.string.cd_expand_player),
                onClick = onExpand
            )
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onSetSilenceMode: (Boolean, Int) -> Unit,
    onSetSleepTimer: (Int) -> Unit
) {
    val ep = state.currentEpisode ?: return
    val pod = state.currentPodcast ?: return

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var activePanel by rememberSaveable { mutableStateOf(PlayerControlPanel.NONE) }
    var showCoverLightbox by rememberSaveable { mutableStateOf(false) }
    var showReaderDialog by rememberSaveable { mutableStateOf(false) }
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

            val speedLabel = formatPlaybackSpeed(state.playbackSpeed)
            val silenceStateText = stringResource(if (state.skipSilence) R.string.state_on else R.string.state_off)
            val silenceLongClickLabel = stringResource(R.string.player_silence)
            val sleepLabel = if (state.sleepTimerRemainingMs > 0L) {
                formatDuration(state.sleepTimerRemainingMs)
            } else {
                stringResource(R.string.player_sleep)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerActionTile(
                    icon = Icons.Default.Speed,
                    label = speedLabel,
                    active = activePanel == PlayerControlPanel.SPEED || abs(state.playbackSpeed - 1.0f) >= 0.01f,
                    onClick = {
                        activePanel = if (activePanel == PlayerControlPanel.SPEED) {
                            PlayerControlPanel.NONE
                        } else {
                            expandSheet()
                            PlayerControlPanel.SPEED
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                PlayerActionTile(
                    icon = Icons.Default.GraphicEq,
                    label = stringResource(R.string.player_silence_short),
                    active = state.skipSilence || activePanel == PlayerControlPanel.SILENCE,
                    stateText = silenceStateText,
                    onLongClickLabel = silenceLongClickLabel,
                    onClick = {
                        activePanel = if (activePanel == PlayerControlPanel.SILENCE) {
                            PlayerControlPanel.NONE
                        } else {
                            expandSheet()
                            PlayerControlPanel.SILENCE
                        }
                    },
                    onLongClick = {
                        onSetSilenceMode(!state.skipSilence, state.silenceIntensity)
                    },
                    modifier = Modifier.weight(1f)
                )

                PlayerActionTile(
                    icon = Icons.Default.Bedtime,
                    label = sleepLabel,
                    active = state.sleepTimerRemainingMs > 0L || activePanel == PlayerControlPanel.SLEEP,
                    onClick = {
                        activePanel = if (activePanel == PlayerControlPanel.SLEEP) {
                            PlayerControlPanel.NONE
                        } else {
                            expandSheet()
                            PlayerControlPanel.SLEEP
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                PlayerActionTile(
                    icon = Icons.Default.Description,
                    label = stringResource(R.string.player_notes_short),
                    active = showNotes,
                    onClick = {
                        showNotes = !showNotes
                        if (showNotes) {
                            showChapters = false
                            activePanel = PlayerControlPanel.NONE
                            expandSheet()
                        } else if (!showChapters) {
                            scope.launch { runCatching { sheetState.partialExpand() } }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                if (chapters.isNotEmpty()) {
                    PlayerActionTile(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        label = stringResource(R.string.player_chapters_short),
                        active = showChapters,
                        onClick = {
                            showChapters = !showChapters
                            if (showChapters) {
                                showNotes = false
                                activePanel = PlayerControlPanel.NONE
                                expandSheet()
                            } else if (!showNotes) {
                                scope.launch { runCatching { sheetState.partialExpand() } }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (activePanel != PlayerControlPanel.NONE) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        when (activePanel) {
                            PlayerControlPanel.SPEED -> SpeedControlPanel(
                                episodeId = ep.id,
                                currentSpeed = state.playbackSpeed,
                                onSetSpeed = onSetSpeed
                            )
                            PlayerControlPanel.SILENCE -> SilenceControlPanel(
                                skipSilence = state.skipSilence,
                                silenceIntensity = state.silenceIntensity,
                                onSetSilenceMode = onSetSilenceMode
                            )
                            PlayerControlPanel.SLEEP -> SleepControlPanel(
                                remainingMs = state.sleepTimerRemainingMs,
                                presetMinutes = state.sleepTimerPresetMinutes,
                                onSetSleepTimer = onSetSleepTimer
                            )
                            PlayerControlPanel.NONE -> Unit
                        }
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
                            .heightIn(min = 48.dp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerActionTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    stateText: String? = null
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = border,
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Button
                if (stateText != null) stateDescription = stateText
            }
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SpeedControlPanel(
    episodeId: Long,
    currentSpeed: Float,
    onSetSpeed: (Float) -> Unit
) {
    var sliderSpeed by remember(episodeId, currentSpeed) {
        mutableFloatStateOf(currentSpeed.coerceIn(0.50f, 3.00f))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.player_speed_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    val next = (((sliderSpeed - 0.02f) * 100f).roundToInt().coerceIn(50, 300)) / 100f
                    sliderSpeed = next
                    onSetSpeed(next)
                }
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.cd_speed_decrease)
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        sliderSpeed = 1.0f
                        onSetSpeed(1.0f)
                    }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatPlaybackSpeed(sliderSpeed),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            IconButton(
                onClick = {
                    val next = (((sliderSpeed + 0.02f) * 100f).roundToInt().coerceIn(50, 300)) / 100f
                    sliderSpeed = next
                    onSetSpeed(next)
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_speed_increase)
                )
            }
        }
    }

    Slider(
        value = sliderSpeed,
        onValueChange = { raw ->
            sliderSpeed = (raw * 100f).roundToInt().coerceIn(50, 300) / 100f
        },
        onValueChangeFinished = { onSetSpeed(sliderSpeed) },
        valueRange = 0.50f..3.00f,
        steps = 0,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f).forEach { preset ->
            val selected = abs(sliderSpeed - preset) < 0.01f
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        sliderSpeed = preset
                        onSetSpeed(preset)
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatPlaybackSpeed(preset),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SilenceControlPanel(
    skipSilence: Boolean,
    silenceIntensity: Int,
    onSetSilenceMode: (Boolean, Int) -> Unit
) {
    val selectedOption = if (skipSilence) silenceIntensity.coerceIn(0, 2) else -1
    val statusText = when (selectedOption) {
        0 -> stringResource(R.string.silence_soft)
        1 -> stringResource(R.string.silence_medium)
        2 -> stringResource(R.string.silence_strong)
        else -> stringResource(R.string.sleep_off)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.player_silence),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Spacer(Modifier.height(10.dp))
    PureSegmentedControl(
        options = listOf(
            -1 to stringResource(R.string.sleep_off),
            0 to stringResource(R.string.silence_soft),
            1 to stringResource(R.string.silence_medium),
            2 to stringResource(R.string.silence_strong)
        ),
        selected = selectedOption,
        onSelect = { level ->
            if (level < 0) {
                onSetSilenceMode(false, silenceIntensity)
            } else {
                onSetSilenceMode(true, level)
            }
        }
    )
}

@Composable
private fun SleepControlPanel(
    remainingMs: Long,
    presetMinutes: Int,
    onSetSleepTimer: (Int) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sleepStep by remember(presetMinutes, remainingMs) {
        val currentMins = if (remainingMs <= 0L) 0 else presetMinutes.coerceIn(0, 120)
        mutableIntStateOf(((currentMins + 2) / 5).coerceIn(0, 24))
    }

    val selectedMinutes = sleepStep * 5
    val formattedSelected = formatSleepMinutesLabel(selectedMinutes)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.player_sleep_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (!isDragging && remainingMs > 0L) {
                    formatDuration(remainingMs)
                } else {
                    formattedSelected
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (remainingMs > 0L || selectedMinutes > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (remainingMs > 0L) {
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        isDragging = false
                        sleepStep = 0
                        onSetSleepTimer(0)
                    }
                ) {
                    Text(stringResource(R.string.sleep_off))
                }
            }
        }
    }

    Slider(
        value = sleepStep.toFloat(),
        onValueChange = { raw ->
            isDragging = true
            sleepStep = raw.roundToInt().coerceIn(0, 24)
        },
        onValueChangeFinished = {
            isDragging = false
            onSetSleepTimer(sleepStep * 5)
        },
        valueRange = 0f..24f,
        steps = 23,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val presets = listOf(
            0 to stringResource(R.string.sleep_off),
            15 to stringResource(R.string.sleep_minutes_compact, 15),
            30 to stringResource(R.string.sleep_minutes_compact, 30),
            60 to stringResource(R.string.sleep_minutes_compact, 60),
            120 to stringResource(R.string.sleep_hours_exact, 2)
        )
        presets.forEach { (mins, label) ->
            val selected = if (remainingMs <= 0L && !isDragging) {
                mins == 0
            } else {
                selectedMinutes == mins
            }
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        isDragging = false
                        sleepStep = mins / 5
                        onSetSleepTimer(mins)
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun formatSleepMinutesLabel(minutes: Int): String {
    if (minutes <= 0) return stringResource(R.string.sleep_off)
    val hours = minutes / 60
    val remMins = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.sleep_minutes_short, remMins)
        remMins == 0 -> stringResource(R.string.sleep_hours_exact, hours)
        else -> stringResource(R.string.sleep_hours_minutes, hours, remMins)
    }
}
