package com.lycoslane.purepodcast.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.PodcastWithStats
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.PodcastImage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    podcasts: List<PodcastWithStats>,
    isRefreshing: Boolean,
    settings: AppSettings,
    backupStatus: String?,
    onRefreshAll: () -> Unit,
    onPodcastClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onChangeRefreshInterval: (Int) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleAutoDownloadDefault: (Boolean) -> Unit,
    onToggleDownloadWhenQueued: (Boolean) -> Unit,
    onToggleAddToQueueWhenDownloaded: (Boolean) -> Unit,
    onChangeAutoRemovePlayedDays: (Int) -> Unit,
    onChangeMaxDownloadsPerPodcast: (Int) -> Unit,
    onChangeSmartRewind: (Int) -> Unit,
    onToggleAudioNormalization: (Boolean) -> Unit,
    onChangeBluetoothNextPrevSeeks: (Boolean) -> Unit,
    onChangeAppLanguage: (String) -> Unit,
    onExportBackupUri: (Uri) -> Unit,
    onImportBackupUri: (Uri) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onExportBackupUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportBackupUri(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefreshAll) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh_all))
                        }
                    }
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add_podcast))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (podcasts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.empty_podcasts_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_podcasts_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onAddClick) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.action_add_podcast))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(podcasts, key = { it.podcast.id }) { item ->
                        PodcastThumbnailCard(
                            item = item,
                            onClick = { onPodcastClick(item.podcast.id) }
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                // --- Section 1: Downloads ---
                Text(
                    text = stringResource(R.string.settings_section_downloads),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    subtitle = stringResource(R.string.settings_wifi_only_sub),
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = onToggleWifiOnly
                )
                Spacer(Modifier.height(10.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_download_default),
                    subtitle = stringResource(R.string.settings_auto_download_default_sub),
                    checked = settings.autoDownloadNewByDefault,
                    onCheckedChange = onToggleAutoDownloadDefault
                )
                Spacer(Modifier.height(10.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_download_when_queued),
                    subtitle = stringResource(R.string.settings_download_when_queued_sub),
                    checked = settings.downloadWhenQueued,
                    onCheckedChange = onToggleDownloadWhenQueued
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_auto_remove_played),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_auto_remove_played_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-1, 0, 1, 3, 7).forEach { days ->
                        val label = when (days) {
                            -1 -> stringResource(R.string.auto_remove_never)
                            0 -> stringResource(R.string.auto_remove_immediately)
                            else -> stringResource(R.string.auto_remove_days, days)
                        }
                        FilterChip(
                            selected = settings.autoRemovePlayedDays == days,
                            onClick = { onChangeAutoRemovePlayedDays(days) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                var sliderLimit by remember(settings.maxDownloadsPerPodcast) {
                    mutableFloatStateOf(settings.maxDownloadsPerPodcast.toFloat())
                }
                val displayLimit = sliderLimit.roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_max_downloads),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.settings_max_downloads_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (displayLimit == 0) {
                            stringResource(R.string.max_downloads_unlimited)
                        } else {
                            stringResource(R.string.max_downloads_count, displayLimit)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = sliderLimit,
                    onValueChange = { sliderLimit = it },
                    onValueChangeFinished = {
                        onChangeMaxDownloadsPerPodcast(sliderLimit.roundToInt())
                    },
                    valueRange = 0f..30f,
                    steps = 29,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section 2: Playback & Queue ---
                Text(
                    text = stringResource(R.string.settings_section_playback),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                SettingSwitchRow(
                    title = stringResource(R.string.settings_queue_when_downloaded),
                    subtitle = stringResource(R.string.settings_queue_when_downloaded_sub),
                    checked = settings.addToQueueWhenDownloaded,
                    onCheckedChange = onToggleAddToQueueWhenDownloaded
                )
                Spacer(Modifier.height(12.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_audio_normalization),
                    subtitle = stringResource(R.string.settings_audio_normalization_sub),
                    checked = settings.audioNormalizationEnabled,
                    onCheckedChange = onToggleAudioNormalization
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_smart_rewind),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_smart_rewind_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 5, 10, 15, 30).forEach { secs ->
                        val label = if (secs == 0) {
                            stringResource(R.string.interval_off)
                        } else {
                            stringResource(R.string.seconds_short, secs)
                        }
                        FilterChip(
                            selected = settings.smartRewindSeconds == secs,
                            onClick = { onChangeSmartRewind(secs) },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_bluetooth_skip),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = settings.bluetoothNextPrevSeeks,
                        onClick = { onChangeBluetoothNextPrevSeeks(true) },
                        label = { Text(stringResource(R.string.settings_bluetooth_skip_seek)) }
                    )
                    FilterChip(
                        selected = !settings.bluetoothNextPrevSeeks,
                        onClick = { onChangeBluetoothNextPrevSeeks(false) },
                        label = { Text(stringResource(R.string.settings_bluetooth_skip_episode)) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))

                // --- Section 3: Updates & Backup ---
                Text(
                    text = stringResource(R.string.settings_section_updates),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_refresh_interval),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0, 1, 3, 6, 12, 24).forEach { hours ->
                        val label = if (hours == 0) {
                            stringResource(R.string.interval_off)
                        } else {
                            stringResource(R.string.interval_hours, hours)
                        }
                        FilterChip(
                            selected = settings.refreshIntervalHours == hours,
                            onClick = { onChangeRefreshInterval(hours) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.appLanguage == "en",
                        onClick = { onChangeAppLanguage("en") },
                        label = { Text(stringResource(R.string.settings_language_en)) }
                    )
                    FilterChip(
                        selected = settings.appLanguage == "de",
                        onClick = { onChangeAppLanguage("de") },
                        label = { Text(stringResource(R.string.settings_language_de)) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_backup_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_backup_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("PurePodcast_backup.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_export_backup))
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import_backup))
                    }
                }

                if (!backupStatus.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = backupStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
private fun PodcastThumbnailCard(
    item: PodcastWithStats,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PodcastImage(
                url = item.podcast.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.unplayedCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(item.unplayedCount.toString())
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.podcast.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
