package com.lycoslane.purepodcast.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.download.TranscodeStatus
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import java.util.Locale

@Composable
fun SettingsSection(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (trailing != null) trailing()
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                content = content
            )
        }
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
internal fun CompressionSettingsSection(
    allEpisodes: List<EpisodeWithPodcast>,
    settings: AppSettings,
    transcodeStatus: TranscodeStatus,
    onChangeTranscodeBitrate: (Int) -> Unit,
    onToggleTranscodeAuto: (Boolean) -> Unit,
    onToggleTranscodeChargingOnly: (Boolean) -> Unit,
    onTranscodeAllNow: () -> Unit,
    onShowCompressedDownloads: () -> Unit
) {
    var showCompressionInfo by rememberSaveable { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(R.string.settings_section_compression),
        trailing = {
            IconButton(
                onClick = { showCompressionInfo = !showCompressionInfo },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.cd_compression_info),
                    tint = if (showCompressionInfo) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    ) {
        if (showCompressionInfo) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.settings_compression_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        PureSegmentedControl(
            options = listOf(
                0 to stringResource(R.string.settings_compression_off),
                64 to stringResource(R.string.settings_compression_low),
                128 to stringResource(R.string.settings_compression_medium),
                192 to stringResource(R.string.settings_compression_high)
            ),
            selected = settings.transcodeBitrateKbps,
            onSelect = onChangeTranscodeBitrate
        )

        if (settings.transcodeBitrateKbps > 0) {
            SectionDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_compression_auto),
                checked = settings.transcodeAutoEnabled,
                onCheckedChange = onToggleTranscodeAuto
            )
            SectionDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_compression_charging_only),
                checked = settings.transcodeChargingOnly,
                onCheckedChange = onToggleTranscodeChargingOnly
            )

            val downloadedNonLocal = remember(allEpisodes) {
                allEpisodes.filter { item ->
                    !item.podcast.isLocal &&
                        item.episode.downloadState == DownloadState.DOWNLOADED &&
                        item.episode.markedForDeletionAtMs == 0L &&
                        item.episode.localFilePath?.startsWith("content://") != true
                }
            }
            val compressedEpisodes = remember(downloadedNonLocal) {
                downloadedNonLocal.filter {
                    it.episode.isTranscoded &&
                        it.episode.localFilePath?.endsWith(".m4a", ignoreCase = true) == true
                }
            }
            val compressedCount = compressedEpisodes.size
            val pendingCount = (downloadedNonLocal.size - compressedCount).coerceAtLeast(0)
            val totalSavedBytes = remember(compressedEpisodes) {
                compressedEpisodes.sumOf { it.episode.transcodeSavedBytes.coerceAtLeast(0L) }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    downloadedNonLocal.isEmpty() -> stringResource(R.string.compression_no_downloads)
                    totalSavedBytes > 0L -> stringResource(
                        R.string.compression_status_summary_with_saved,
                        compressedCount,
                        pendingCount,
                        formatSizeMb(totalSavedBytes)
                    )
                    else -> stringResource(
                        R.string.compression_status_summary,
                        compressedCount,
                        pendingCount
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            if (transcodeStatus.isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append(
                                stringResource(
                                    R.string.notif_transcode_progress,
                                    transcodeStatus.currentIndex,
                                    transcodeStatus.totalCount
                                )
                            )
                            if (transcodeStatus.episodeTitle.isNotBlank()) {
                                append(" • ")
                                append(transcodeStatus.episodeTitle)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(transcodeStatus.overallProgress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { transcodeStatus.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onTranscodeAllNow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_compress_all_now))
                }
                if (transcodeStatus.statusMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = transcodeStatus.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (compressedCount > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onShowCompressedDownloads,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_show_compressed_downloads, compressedCount))
                }
            }
        }
    }
}

@Composable
internal fun LocalFolderGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_guide_title)) },
        text = { Text(stringResource(R.string.local_guide_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
internal fun formatSyncMinutesLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.interval_off)
    minutes < 60 -> stringResource(R.string.sync_interval_minutes_short, minutes)
    else -> stringResource(R.string.sync_interval_hours_short, minutes / 60)
}

internal fun formatSizeMb(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (mb >= 100.0) {
        String.format(Locale.getDefault(), "%.0f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}
