package com.lycoslane.purepodcast.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.PlayerUiState
import com.lycoslane.purepodcast.core.network.ChaptersParser
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPlaybackSpeed
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import com.lycoslane.purepodcast.ui.common.monoModeLabel
import com.lycoslane.purepodcast.ui.detail.PodcastSettingsSheet
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    state: PlayerUiState,
    globalMonoMode: Int,
    globalAudioNormalization: Boolean,
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
    onSetSleepTimer: (Int) -> Unit,
    onSetEpisodeMonoOverride: (Int) -> Unit,
    onSetPodcastMonoOverride: (Int) -> Unit,
    onSetPodcastNormalizationOverride: (Int) -> Unit,
    onTogglePodcastSort: (Boolean) -> Unit,
    onDisablePodcastAutoDownload: () -> Unit
) {
    val ep = state.currentEpisode ?: return
    val pod = state.currentPodcast ?: return

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showPodcastSettings by rememberSaveable { mutableStateOf(false) }
    var activePanel by rememberSaveable { mutableStateOf(PlayerControlPanel.NONE) }
    var showCoverLightbox by rememberSaveable { mutableStateOf(false) }
    var showReaderDialog by rememberSaveable { mutableStateOf(false) }
    var scrubPos by remember(ep.id) { mutableStateOf<Float?>(null) }

    fun expandSheet() {
        scope.launch { runCatching { sheetState.expand() } }
    }

    LaunchedEffect(showNotes, showChapters, textScale) {
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
            val monoTileLabel = if (state.effectiveMonoMode == AudioMonoMode.OFF) {
                stringResource(R.string.player_mono_short)
            } else {
                monoModeLabel(state.effectiveMonoMode)
            }
            val sleepLabel = if (state.sleepTimerRemainingMs > 0L) {
                formatDuration(state.sleepTimerRemainingMs)
            } else {
                stringResource(R.string.player_sleep)
            }

            // Primary row: 4 uniform 58.dp tiles for live playback controls
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
                    icon = Icons.Default.Headphones,
                    label = monoTileLabel,
                    active = state.effectiveMonoMode != AudioMonoMode.OFF ||
                        state.episodeMonoOverride != AudioMonoMode.INHERIT ||
                        state.podcastMonoOverride != AudioMonoMode.INHERIT ||
                        activePanel == PlayerControlPanel.AUDIO_FIX,
                    onClick = {
                        activePanel = if (activePanel == PlayerControlPanel.AUDIO_FIX) {
                            PlayerControlPanel.NONE
                        } else {
                            expandSheet()
                            PlayerControlPanel.AUDIO_FIX
                        }
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
            }

            if (activePanel != PlayerControlPanel.NONE) {
                Spacer(Modifier.height(10.dp))
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
                            PlayerControlPanel.AUDIO_FIX -> AudioFixControlPanel(
                                episodeId = ep.id,
                                globalMonoMode = globalMonoMode,
                                podcastMonoOverride = state.podcastMonoOverride,
                                episodeMonoOverride = state.episodeMonoOverride,
                                onSetEpisodeMonoOverride = onSetEpisodeMonoOverride,
                                onSetPodcastMonoOverride = onSetPodcastMonoOverride
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

            Spacer(Modifier.height(10.dp))

            // Secondary row: Notes, optional Chapters, and direct access to Podcast Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerSecondaryTile(
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
                    PlayerSecondaryTile(
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

                PlayerSecondaryTile(
                    icon = Icons.Default.Tune,
                    label = stringResource(R.string.player_podcast_short),
                    active = showPodcastSettings,
                    onClick = { showPodcastSettings = true },
                    modifier = Modifier.weight(1f)
                )
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

    if (showPodcastSettings) {
        PodcastSettingsSheet(
            podcast = pod,
            globalMonoMode = globalMonoMode,
            globalAudioNormalization = globalAudioNormalization,
            onDismiss = { showPodcastSettings = false },
            onSetSpeed = onSetSpeed,
            onSetSilenceMode = onSetSilenceMode,
            onSetMonoModeOverride = onSetPodcastMonoOverride,
            onSetNormalizationOverride = onSetPodcastNormalizationOverride,
            onToggleSort = onTogglePodcastSort,
            onDisableAutoDownload = onDisablePodcastAutoDownload
        )
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
