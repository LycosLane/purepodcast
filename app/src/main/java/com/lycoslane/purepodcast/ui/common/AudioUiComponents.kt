package com.lycoslane.purepodcast.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.AudioMonoMode

@Composable
fun monoModeLabel(mode: Int): String = when (AudioMonoMode.normalizeConcrete(mode)) {
    AudioMonoMode.MIX -> stringResource(R.string.mono_mode_mix)
    AudioMonoMode.AUTO -> stringResource(R.string.mono_mode_smart)
    AudioMonoMode.LEFT -> stringResource(R.string.mono_mode_left_short)
    AudioMonoMode.RIGHT -> stringResource(R.string.mono_mode_right_short)
    else -> stringResource(R.string.mono_mode_off)
}

@Composable
fun MonoModeSelector(
    selectedMode: Int,
    onSelectMode: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.settings_mono_mode),
    includeOffOption: Boolean = false
) {
    var showInfo by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

        if (showInfo) {
            Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        val options = buildList {
            if (includeOffOption) {
                add(AudioMonoMode.OFF to stringResource(R.string.mono_mode_off))
            }
            add(AudioMonoMode.MIX to stringResource(R.string.mono_mode_mix))
            add(AudioMonoMode.AUTO to stringResource(R.string.mono_mode_smart))
            add(AudioMonoMode.LEFT to stringResource(R.string.mono_mode_left))
            add(AudioMonoMode.RIGHT to stringResource(R.string.mono_mode_right))
        }
        PureSegmentedControl(
            options = options,
            selected = selectedMode,
            onSelect = onSelectMode
        )
    }
}
