package com.lycoslane.purepodcast.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPlaybackSpeed
import com.lycoslane.purepodcast.ui.common.monoModeLabel
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PlayerControlPanel {
    NONE,
    SPEED,
    SILENCE,
    AUDIO_FIX,
    SLEEP
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerActionTile(
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
internal fun PlayerSecondaryTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = border,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SpeedControlPanel(
    episodeId: Long,
    currentSpeed: Float,
    onSetSpeed: (Float) -> Unit,
    onResetToGlobal: (() -> Unit)? = null
) {
    var sliderSpeed by remember(episodeId, currentSpeed) {
        mutableFloatStateOf(currentSpeed.coerceIn(0.50f, 3.00f))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_speed_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (onResetToGlobal != null) {
                TextButton(
                    onClick = onResetToGlobal,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.override_global),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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
fun SilenceControlPanel(
    skipSilence: Boolean,
    silenceIntensity: Int,
    onSetSilenceMode: (Boolean, Int) -> Unit,
    onResetToGlobal: (() -> Unit)? = null
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.player_silence),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (onResetToGlobal != null) {
                TextButton(
                    onClick = onResetToGlobal,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.override_global),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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
internal fun AudioFixControlPanel(
    episodeId: Long,
    globalMonoMode: Int,
    podcastMonoOverride: Int,
    episodeMonoOverride: Int,
    onSetEpisodeMonoOverride: (Int) -> Unit,
    onSetPodcastMonoOverride: (Int) -> Unit
) {
    var selectedScope by rememberSaveable(episodeId) {
        val defaultScope = if (
            episodeMonoOverride == AudioMonoMode.INHERIT &&
            podcastMonoOverride != AudioMonoMode.INHERIT
        ) 1 else 0
        mutableIntStateOf(defaultScope)
    }
    var showInfo by rememberSaveable { mutableStateOf(false) }

    val currentScopeOverride = if (selectedScope == 0) {
        AudioMonoMode.normalizeOverride(episodeMonoOverride)
    } else {
        AudioMonoMode.normalizeOverride(podcastMonoOverride)
    }
    val scopeTriState = when (currentScopeOverride) {
        AudioMonoMode.INHERIT -> -1
        AudioMonoMode.OFF -> 0
        else -> 1
    }

    val inheritsFromPodcast = selectedScope == 0 && podcastMonoOverride != AudioMonoMode.INHERIT
    val inheritOptionLabel = stringResource(
        if (inheritsFromPodcast) R.string.override_podcast else R.string.override_global
    )
    val inheritedMode = if (inheritsFromPodcast) {
        AudioMonoMode.normalizeConcrete(podcastMonoOverride)
    } else {
        AudioMonoMode.normalizeConcrete(globalMonoMode)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.player_audio_fix_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scopeTriState == -1) {
                val hintText = stringResource(
                    if (inheritsFromPodcast) R.string.override_podcast_state else R.string.override_global_state,
                    monoModeLabel(inheritedMode)
                )
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            IconButton(
                onClick = { showInfo = !showInfo },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.cd_mono_info),
                    tint = if (showInfo) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showInfo) {
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.mono_mode_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    PureSegmentedControl(
        options = listOf(
            0 to stringResource(R.string.player_scope_episode),
            1 to stringResource(R.string.player_scope_podcast)
        ),
        selected = selectedScope,
        onSelect = { selectedScope = it }
    )

    Spacer(Modifier.height(8.dp))
    PureSegmentedControl(
        options = listOf(
            -1 to inheritOptionLabel,
            0 to stringResource(R.string.override_off),
            1 to stringResource(R.string.override_on)
        ),
        selected = scopeTriState,
        onSelect = { choice ->
            val apply: (Int) -> Unit = if (selectedScope == 0) onSetEpisodeMonoOverride else onSetPodcastMonoOverride
            when (choice) {
                -1 -> apply(AudioMonoMode.INHERIT)
                0 -> apply(AudioMonoMode.OFF)
                1 -> {
                    val nextMode = if (currentScopeOverride in AudioMonoMode.MIX..AudioMonoMode.RIGHT) {
                        currentScopeOverride
                    } else if (inheritedMode != AudioMonoMode.OFF) {
                        inheritedMode
                    } else {
                        AudioMonoMode.MIX
                    }
                    apply(nextMode)
                }
            }
        }
    )

    if (scopeTriState == 1) {
        Spacer(Modifier.height(8.dp))
        PureSegmentedControl(
            options = listOf(
                AudioMonoMode.MIX to stringResource(R.string.mono_mode_mix),
                AudioMonoMode.AUTO to stringResource(R.string.mono_mode_smart),
                AudioMonoMode.LEFT to stringResource(R.string.mono_mode_left),
                AudioMonoMode.RIGHT to stringResource(R.string.mono_mode_right)
            ),
            selected = currentScopeOverride,
            onSelect = { chosen ->
                if (selectedScope == 0) onSetEpisodeMonoOverride(chosen)
                else onSetPodcastMonoOverride(chosen)
            }
        )
    }
}

@Composable
internal fun SleepControlPanel(
    remainingMs: Long,
    presetMinutes: Int,
    onSetSleepTimer: (Int) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sleepStep by remember(presetMinutes, remainingMs > 0L) {
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
