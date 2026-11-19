package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.AudioOverride
import com.lycoslane.purepodcast.core.audio.SilenceOverride
import com.lycoslane.purepodcast.core.audio.SmartRewindOverride
import com.lycoslane.purepodcast.core.audio.SpeedOverride
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.MonoModeSelector
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.common.formatPlaybackSpeed
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.monoModeLabel
import com.lycoslane.purepodcast.ui.player.SpeedControlPanel
import com.lycoslane.purepodcast.ui.settings.SectionDivider
import com.lycoslane.purepodcast.ui.settings.SettingsSection
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSettingsSheet(
    podcast: PodcastEntity,
    globalSettings: AppSettings,
    onDismiss: () -> Unit,
    onSetSpeedOverride: (Float) -> Unit,
    onSetSilenceModeOverride: (Int) -> Unit,
    onSetMonoModeOverride: (Int) -> Unit,
    onSetNormalizationOverride: (Int) -> Unit,
    onSetSmartRewindOverride: (Int) -> Unit,
    onApplyToAllPodcasts: () -> Unit,
    onToggleSort: (Boolean) -> Unit,
    onDisableAutoDownload: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.podcast_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = podcast.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SettingsSection(title = stringResource(R.string.podcast_settings_section_audio)) {
                // 1. Playback Speed (Global | Custom)
                val isCustomSpeed = !SpeedOverride.isInherit(podcast.speedOverride)
                val effectiveSpeed = SpeedOverride.resolve(globalSettings.defaultPlaybackSpeed, podcast.speedOverride)
                OverrideHeader(
                    title = stringResource(R.string.player_speed_title),
                    globalLabel = if (!isCustomSpeed) formatPlaybackSpeed(globalSettings.defaultPlaybackSpeed) else null
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        false to stringResource(R.string.override_global),
                        true to stringResource(R.string.override_custom)
                    ),
                    selected = isCustomSpeed,
                    onSelect = { custom ->
                        if (custom) {
                            if (!isCustomSpeed) onSetSpeedOverride(globalSettings.defaultPlaybackSpeed)
                        } else {
                            onSetSpeedOverride(SpeedOverride.INHERIT)
                        }
                    }
                )
                if (isCustomSpeed) {
                    Spacer(Modifier.height(10.dp))
                    SpeedControlPanel(
                        episodeId = podcast.id,
                        currentSpeed = effectiveSpeed,
                        onSetSpeed = { onSetSpeedOverride(it) }
                    )
                }

                SectionDivider()

                // 2. Trim Silence (Global | Off | On -> Soft/Medium/Strong)
                val normSilenceOv = SilenceOverride.normalize(podcast.silenceModeOverride)
                val silenceTriState = when (normSilenceOv) {
                    SilenceOverride.INHERIT -> -1
                    SilenceOverride.OFF -> 0
                    else -> 1
                }
                val globalSilenceText = if (!globalSettings.defaultSkipSilence) {
                    stringResource(R.string.override_off)
                } else {
                    silenceIntensityLabel(globalSettings.defaultSilenceIntensity)
                }
                OverrideHeader(
                    title = stringResource(R.string.player_silence),
                    globalLabel = if (silenceTriState == -1) globalSilenceText else null
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        -1 to stringResource(R.string.override_global),
                        0 to stringResource(R.string.override_off),
                        1 to stringResource(R.string.override_on)
                    ),
                    selected = silenceTriState,
                    onSelect = { choice ->
                        when (choice) {
                            -1 -> onSetSilenceModeOverride(SilenceOverride.INHERIT)
                            0 -> onSetSilenceModeOverride(SilenceOverride.OFF)
                            1 -> {
                                val next = if (normSilenceOv in SilenceOverride.SOFT..SilenceOverride.STRONG) {
                                    normSilenceOv
                                } else {
                                    SilenceOverride.fromEnabledAndIntensity(true, globalSettings.defaultSilenceIntensity)
                                }
                                onSetSilenceModeOverride(next)
                            }
                        }
                    }
                )
                if (silenceTriState == 1) {
                    Spacer(Modifier.height(10.dp))
                    PureSegmentedControl(
                        options = listOf(
                            SilenceOverride.SOFT to stringResource(R.string.silence_soft),
                            SilenceOverride.MEDIUM to stringResource(R.string.silence_medium),
                            SilenceOverride.STRONG to stringResource(R.string.silence_strong)
                        ),
                        selected = normSilenceOv.coerceIn(SilenceOverride.SOFT, SilenceOverride.STRONG),
                        onSelect = onSetSilenceModeOverride
                    )
                }

                SectionDivider()

                // 3. Volume Normalization (Global | Off | On)
                val normNormOv = AudioOverride.normalize(podcast.audioNormalizationOverride)
                OverrideHeader(
                    title = stringResource(R.string.settings_audio_normalization),
                    globalLabel = if (normNormOv == AudioOverride.INHERIT) {
                        stringResource(if (globalSettings.audioNormalizationEnabled) R.string.override_on else R.string.override_off)
                    } else null
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        AudioOverride.INHERIT to stringResource(R.string.override_global),
                        AudioOverride.OFF to stringResource(R.string.override_off),
                        AudioOverride.ON to stringResource(R.string.override_on)
                    ),
                    selected = normNormOv,
                    onSelect = onSetNormalizationOverride
                )

                SectionDivider()

                // 4. Mono Audio (Global | Off | On -> L+R/Smart/L/R)
                val monoTriState = when (AudioMonoMode.normalizeOverride(podcast.monoModeOverride)) {
                    AudioMonoMode.INHERIT -> -1
                    AudioMonoMode.OFF -> 0
                    else -> 1
                }
                OverrideHeader(
                    title = stringResource(R.string.settings_mono_audio),
                    globalLabel = if (monoTriState == -1) monoModeLabel(globalSettings.monoMode) else null
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        -1 to stringResource(R.string.override_global),
                        0 to stringResource(R.string.override_off),
                        1 to stringResource(R.string.override_on)
                    ),
                    selected = monoTriState,
                    onSelect = { choice ->
                        when (choice) {
                            -1 -> onSetMonoModeOverride(AudioMonoMode.INHERIT)
                            0 -> onSetMonoModeOverride(AudioMonoMode.OFF)
                            1 -> {
                                val nextMode = if (podcast.monoModeOverride in AudioMonoMode.MIX..AudioMonoMode.RIGHT) {
                                    podcast.monoModeOverride
                                } else if (globalSettings.monoMode != AudioMonoMode.OFF) {
                                    AudioMonoMode.normalizeConcrete(globalSettings.monoMode)
                                } else {
                                    AudioMonoMode.MIX
                                }
                                onSetMonoModeOverride(nextMode)
                            }
                        }
                    }
                )
                if (monoTriState == 1) {
                    Spacer(Modifier.height(10.dp))
                    MonoModeSelector(
                        selectedMode = podcast.monoModeOverride,
                        onSelectMode = onSetMonoModeOverride
                    )
                }

                SectionDivider()

                // 5. Rewind after pause (Global | Off | On -> 5s/10s/15s/30s)
                val normRewindOv = SmartRewindOverride.normalize(podcast.smartRewindOverride)
                val rewindTriState = when {
                    normRewindOv == SmartRewindOverride.INHERIT -> -1
                    normRewindOv == SmartRewindOverride.OFF -> 0
                    else -> 1
                }
                val globalRewindText = if (globalSettings.smartRewindSeconds <= 0) {
                    stringResource(R.string.override_off)
                } else {
                    stringResource(R.string.seconds_short, globalSettings.smartRewindSeconds)
                }
                OverrideHeader(
                    title = stringResource(R.string.settings_smart_rewind),
                    globalLabel = if (rewindTriState == -1) globalRewindText else null
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        -1 to stringResource(R.string.override_global),
                        0 to stringResource(R.string.override_off),
                        1 to stringResource(R.string.override_on)
                    ),
                    selected = rewindTriState,
                    onSelect = { choice ->
                        when (choice) {
                            -1 -> onSetSmartRewindOverride(SmartRewindOverride.INHERIT)
                            0 -> onSetSmartRewindOverride(SmartRewindOverride.OFF)
                            1 -> {
                                val nextSecs = if (normRewindOv > 0) normRewindOv
                                else globalSettings.smartRewindSeconds.takeIf { it > 0 } ?: 10
                                onSetSmartRewindOverride(nextSecs)
                            }
                        }
                    }
                )
                if (rewindTriState == 1) {
                    Spacer(Modifier.height(10.dp))
                    PureSegmentedControl(
                        options = listOf(
                            5 to stringResource(R.string.seconds_short, 5),
                            10 to stringResource(R.string.seconds_short, 10),
                            15 to stringResource(R.string.seconds_short, 15),
                            30 to stringResource(R.string.seconds_short, 30)
                        ),
                        selected = normRewindOv,
                        onSelect = onSetSmartRewindOverride
                    )
                }

                // Contextual "Apply to all podcasts" banner across all 5 audio settings
                val (effSilence, effIntensity) = SilenceOverride.resolve(
                    globalEnabled = globalSettings.defaultSkipSilence,
                    globalIntensity = globalSettings.defaultSilenceIntensity,
                    podcastOverride = podcast.silenceModeOverride
                )
                val effNorm = AudioOverride.resolve(globalSettings.audioNormalizationEnabled, podcast.audioNormalizationOverride)
                val effMono = AudioMonoMode.resolveEffective(globalSettings.monoMode, podcast.monoModeOverride, AudioMonoMode.INHERIT)
                val effRewind = SmartRewindOverride.resolve(globalSettings.smartRewindSeconds, podcast.smartRewindOverride)

                val speedDiffers = abs(effectiveSpeed - globalSettings.defaultPlaybackSpeed) >= 0.01f
                val silenceDiffers = effSilence != globalSettings.defaultSkipSilence ||
                    (effSilence && effIntensity != globalSettings.defaultSilenceIntensity.coerceIn(0, 2))
                val normDiffers = effNorm != globalSettings.audioNormalizationEnabled
                val monoDiffers = effMono != AudioMonoMode.normalizeConcrete(globalSettings.monoMode)
                val rewindDiffers = effRewind != globalSettings.smartRewindSeconds

                val diffLabels = buildList {
                    if (speedDiffers) add(formatPlaybackSpeed(effectiveSpeed))
                    if (silenceDiffers) add(if (!effSilence) stringResource(R.string.override_off) else silenceIntensityLabel(effIntensity))
                    if (normDiffers) add(stringResource(R.string.settings_audio_normalization))
                    if (monoDiffers) add(monoModeLabel(effMono))
                    if (rewindDiffers) {
                        add(
                            if (effRewind <= 0) stringResource(R.string.override_off)
                            else stringResource(R.string.seconds_short, effRewind)
                        )
                    }
                }

                if (diffLabels.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.differs_from_global_summary, diffLabels.joinToString(", ")),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Button(
                                onClick = onApplyToAllPodcasts,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.apply_to_all_podcasts))
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.podcast_settings_section_episodes)) {
                Text(
                    text = stringResource(R.string.podcast_settings_sort_order),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        false to stringResource(R.string.podcast_settings_sort_newest),
                        true to stringResource(R.string.podcast_settings_sort_oldest)
                    ),
                    selected = podcast.sortAscending,
                    onSelect = onToggleSort
                )

                if (podcast.autoDownloadFromPubDateMs > 0L) {
                    SectionDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.podcast_settings_auto_download_since,
                                formatPubDate(podcast.autoDownloadFromPubDateMs)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDisableAutoDownload) {
                            Text(stringResource(R.string.podcast_settings_auto_download_disable))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverrideHeader(
    title: String,
    globalLabel: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        if (globalLabel != null) {
            Text(
                text = stringResource(R.string.override_global_state, globalLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun silenceIntensityLabel(intensity: Int): String = stringResource(
    when (intensity.coerceIn(0, 2)) {
        0 -> R.string.silence_soft
        1 -> R.string.silence_medium
        else -> R.string.silence_strong
    }
)
