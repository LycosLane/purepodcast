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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.MonoModeSelector
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.monoModeLabel
import com.lycoslane.purepodcast.ui.player.SilenceControlPanel
import com.lycoslane.purepodcast.ui.player.SpeedControlPanel
import com.lycoslane.purepodcast.ui.settings.SectionDivider
import com.lycoslane.purepodcast.ui.settings.SettingsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSettingsSheet(
    podcast: PodcastEntity,
    globalMonoMode: Int,
    globalAudioNormalization: Boolean,
    onDismiss: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSilenceMode: (Boolean, Int) -> Unit,
    onSetMonoModeOverride: (Int) -> Unit,
    onSetNormalizationOverride: (Int) -> Unit,
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
                SpeedControlPanel(
                    episodeId = podcast.id,
                    currentSpeed = podcast.playbackSpeed,
                    onSetSpeed = onSetSpeed
                )

                SectionDivider()

                SilenceControlPanel(
                    skipSilence = podcast.skipSilence,
                    silenceIntensity = podcast.silenceIntensity,
                    onSetSilenceMode = onSetSilenceMode
                )

                SectionDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_audio_normalization),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (podcast.audioNormalizationOverride == AudioOverride.INHERIT) {
                        val globalState = stringResource(
                            if (globalAudioNormalization) R.string.override_on else R.string.override_off
                        )
                        Text(
                            text = stringResource(R.string.override_global_state, globalState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        AudioOverride.INHERIT to stringResource(R.string.override_global),
                        AudioOverride.OFF to stringResource(R.string.override_off),
                        AudioOverride.ON to stringResource(R.string.override_on)
                    ),
                    selected = AudioOverride.normalize(podcast.audioNormalizationOverride),
                    onSelect = onSetNormalizationOverride
                )

                SectionDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_mono_audio),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (podcast.monoModeOverride == AudioMonoMode.INHERIT) {
                        Text(
                            text = stringResource(R.string.override_global_state, monoModeLabel(globalMonoMode)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val monoTriState = when (AudioMonoMode.normalizeOverride(podcast.monoModeOverride)) {
                    AudioMonoMode.INHERIT -> -1
                    AudioMonoMode.OFF -> 0
                    else -> 1
                }
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
                                } else if (globalMonoMode != AudioMonoMode.OFF) {
                                    AudioMonoMode.normalizeConcrete(globalMonoMode)
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
