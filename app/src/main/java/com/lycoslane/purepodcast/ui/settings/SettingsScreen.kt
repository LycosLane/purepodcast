package com.lycoslane.purepodcast.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.download.TranscodeStatus
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.theme.PureAccent
import com.lycoslane.purepodcast.ui.theme.isDarkThemeEffective
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    allEpisodes: List<EpisodeWithPodcast>,
    settings: AppSettings,
    backupStatus: String?,
    transcodeStatus: TranscodeStatus,
    onBack: () -> Unit,
    onChangeThemeMode: (String) -> Unit,
    onChangeLightAccent: (String) -> Unit,
    onChangeDarkAccent: (String) -> Unit,
    onChangeRefreshIntervalMinutes: (Int) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleAutoDownloadDefault: (Boolean) -> Unit,
    onToggleDownloadWhenQueued: (Boolean) -> Unit,
    onToggleAddToQueueWhenDownloaded: (Boolean) -> Unit,
    onChangeAutoRemovePlayedDays: (Int) -> Unit,
    onChangeTrashRetentionHours: (Int) -> Unit,
    onChangeMaxDownloadsPerPodcast: (Int) -> Unit,
    onChangeSmartRewind: (Int) -> Unit,
    onToggleAudioNormalization: (Boolean) -> Unit,
    onChangeBluetoothNextPrevSeeks: (Boolean) -> Unit,
    onChangeAppLanguage: (String) -> Unit,
    onChangeTranscodeBitrate: (Int) -> Unit,
    onToggleTranscodeAuto: (Boolean) -> Unit,
    onToggleTranscodeChargingOnly: (Boolean) -> Unit,
    onTranscodeAllNow: () -> Unit,
    onSelectLocalFolderUri: (String) -> Unit,
    onExportBackupUri: (Uri) -> Unit,
    onImportBackupUri: (Uri) -> Unit
) {
    val context = LocalContext.current
    var showLocalGuideDialog by rememberSaveable { mutableStateOf(false) }
    var showCompressedDownloadsDialog by rememberSaveable { mutableStateOf(false) }
    var showCompressionInfo by rememberSaveable { mutableStateOf(false) }

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

    val localFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val taken = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, rwFlags)
                true
            }.getOrElse {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                }.getOrDefault(false)
            }
            if (taken) {
                onSelectLocalFolderUri(uri.toString())
            }
        }
    }

    val effectiveDark = isDarkThemeEffective(settings.themeMode)
    val activeAccentId = if (effectiveDark) settings.darkAccent else settings.lightAccent
    val activeAccent = PureAccent.fromId(activeAccentId)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // --- 1. Appearance (Light / Dark + 6 Curated Accents per Mode) ---
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                PureSegmentedControl(
                    options = listOf(
                        AppPreferences.THEME_SYSTEM to stringResource(R.string.settings_theme_system),
                        AppPreferences.THEME_LIGHT to stringResource(R.string.settings_theme_light),
                        AppPreferences.THEME_DARK to stringResource(R.string.settings_theme_dark)
                    ),
                    selected = settings.themeMode,
                    onSelect = onChangeThemeMode
                )

                SectionDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_accent_color),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(activeAccent.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PureAccent.entries.forEach { accent ->
                        val swatchColor = if (effectiveDark) accent.darkPrimary else accent.lightPrimary
                        val isSelected = accent.id == activeAccent.id
                        val accentLabel = stringResource(accent.labelRes)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .semantics {
                                    role = Role.RadioButton
                                    selected = isSelected
                                    contentDescription = accentLabel
                                }
                                .clickable {
                                    if (effectiveDark) {
                                        onChangeDarkAccent(accent.id)
                                    } else {
                                        onChangeLightAccent(accent.id)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    val checkTint = if (swatchColor.luminance() > 0.45f) {
                                        Color(0xFF101416)
                                    } else {
                                        Color.White
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = checkTint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- 2. Playback & Queue ---
            SettingsSection(title = stringResource(R.string.settings_section_playback)) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_queue_when_downloaded),
                    checked = settings.addToQueueWhenDownloaded,
                    onCheckedChange = onToggleAddToQueueWhenDownloaded
                )
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_audio_normalization),
                    checked = settings.audioNormalizationEnabled,
                    onCheckedChange = onToggleAudioNormalization
                )
                SectionDivider()
                Text(
                    text = stringResource(R.string.settings_smart_rewind),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        0 to stringResource(R.string.interval_off),
                        5 to stringResource(R.string.seconds_short, 5),
                        10 to stringResource(R.string.seconds_short, 10),
                        15 to stringResource(R.string.seconds_short, 15),
                        30 to stringResource(R.string.seconds_short, 30)
                    ),
                    selected = settings.smartRewindSeconds,
                    onSelect = onChangeSmartRewind
                )
                SectionDivider()
                Text(
                    text = stringResource(R.string.settings_bluetooth_skip),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        true to stringResource(R.string.settings_bluetooth_skip_seek_short),
                        false to stringResource(R.string.settings_bluetooth_skip_episode_short)
                    ),
                    selected = settings.bluetoothNextPrevSeeks,
                    onSelect = onChangeBluetoothNextPrevSeeks,
                    maxLines = 2
                )
            }

            // --- 3. Downloads & Storage ---
            SettingsSection(title = stringResource(R.string.settings_section_downloads)) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = onToggleWifiOnly
                )
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_download_default),
                    checked = settings.autoDownloadNewByDefault,
                    onCheckedChange = onToggleAutoDownloadDefault
                )
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_download_when_queued),
                    checked = settings.downloadWhenQueued,
                    onCheckedChange = onToggleDownloadWhenQueued
                )
                SectionDivider()
                val isLimitedDownloads = settings.maxDownloadsPerPodcast > 0
                SettingSwitchRow(
                    title = stringResource(R.string.settings_max_downloads_unlimited_toggle),
                    checked = isLimitedDownloads,
                    onCheckedChange = { limited ->
                        onChangeMaxDownloadsPerPodcast(if (limited) 5 else 0)
                    }
                )
                if (isLimitedDownloads) {
                    var sliderLimit by remember(settings.maxDownloadsPerPodcast) {
                        mutableFloatStateOf(settings.maxDownloadsPerPodcast.coerceIn(1, 30).toFloat())
                    }
                    val displayLimit = sliderLimit.roundToInt().coerceIn(1, 30)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_max_downloads),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.max_downloads_count, displayLimit),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = sliderLimit,
                        onValueChange = { sliderLimit = it },
                        onValueChangeFinished = {
                            onChangeMaxDownloadsPerPodcast(sliderLimit.roundToInt().coerceIn(1, 30))
                        },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SectionDivider()
                val autoRemoveEnabled = settings.autoRemovePlayedDays >= 0
                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_remove_played),
                    checked = autoRemoveEnabled,
                    onCheckedChange = { enabled ->
                        onChangeAutoRemovePlayedDays(if (enabled) 1 else -1)
                    }
                )
                if (autoRemoveEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_auto_remove_delay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PureSegmentedControl(
                        options = listOf(
                            0 to stringResource(R.string.auto_remove_immediately),
                            1 to stringResource(R.string.auto_remove_days, 1),
                            3 to stringResource(R.string.auto_remove_days, 3),
                            7 to stringResource(R.string.auto_remove_days, 7)
                        ),
                        selected = settings.autoRemovePlayedDays,
                        onSelect = onChangeAutoRemovePlayedDays
                    )
                }
                SectionDivider()
                Text(
                    text = stringResource(R.string.settings_trash_retention),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                PureSegmentedControl(
                    options = listOf(
                        0 to stringResource(R.string.trash_retention_immediate),
                        24 to stringResource(R.string.trash_retention_24h),
                        48 to stringResource(R.string.trash_retention_48h)
                    ),
                    selected = settings.trashRetentionHours,
                    onSelect = onChangeTrashRetentionHours
                )
            }

            // --- 4. Audio Compression (Low / Medium / High + Info) ---
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
                            onClick = { showCompressedDownloadsDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_show_compressed_downloads, compressedCount))
                        }
                    }
                }
            }

            // --- 5. Background Sync ---
            SettingsSection(title = stringResource(R.string.settings_section_sync)) {
                val steps = AppPreferences.REFRESH_INTERVAL_STEPS_MINUTES
                val initialIndex = remember(settings.refreshIntervalMinutes) {
                    steps.indices.minByOrNull { abs(steps[it] - settings.refreshIntervalMinutes) } ?: 5
                }
                var sliderIndex by remember(initialIndex) { mutableIntStateOf(initialIndex) }
                val activeMinutes = steps[sliderIndex.coerceIn(0, steps.lastIndex)]

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_refresh_interval),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatSyncMinutesLabel(activeMinutes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = sliderIndex.toFloat(),
                    onValueChange = { raw ->
                        sliderIndex = raw.roundToInt().coerceIn(0, steps.lastIndex)
                    },
                    onValueChangeFinished = {
                        onChangeRefreshIntervalMinutes(steps[sliderIndex.coerceIn(0, steps.lastIndex)])
                    },
                    valueRange = 0f..steps.lastIndex.toFloat(),
                    steps = (steps.size - 2).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.interval_off),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.sync_interval_hours_short, 24),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- 6. Local Podcast Folder ---
            SettingsSection(
                title = stringResource(R.string.settings_section_local_folder),
                trailing = {
                    IconButton(
                        onClick = { showLocalGuideDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.action_local_folder_guide),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            ) {
                val hasLocalFolder = settings.localFolderUri.isNotBlank()
                val localStatusText = if (hasLocalFolder) {
                    val canWrite = LocalPodcastScanner.hasWritePermission(context, settings.localFolderUri)
                    if (canWrite) stringResource(R.string.settings_local_folder_rw)
                    else stringResource(R.string.settings_local_folder_ro)
                } else {
                    stringResource(R.string.settings_local_folder_none)
                }
                Text(
                    text = localStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { localFolderLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_choose_folder),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (hasLocalFolder) {
                        OutlinedButton(onClick = { onSelectLocalFolderUri("") }) {
                            Text(stringResource(R.string.action_disconnect_folder))
                        }
                    }
                }
            }

            // --- 7. Language & Backup ---
            SettingsSection(title = stringResource(R.string.settings_section_language_backup)) {
                PureSegmentedControl(
                    options = listOf(
                        "en" to stringResource(R.string.settings_language_en),
                        "de" to stringResource(R.string.settings_language_de)
                    ),
                    selected = settings.appLanguage,
                    onSelect = onChangeAppLanguage
                )
                SectionDivider()
                Text(
                    text = stringResource(R.string.settings_backup_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
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
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_export_backup),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_import_backup),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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

            // --- 8. Version Footer ---
            val (versionName, versionCode) = remember(context) {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    val name = info.versionName ?: "1.0"
                    val code = PackageInfoCompat.getLongVersionCode(info).toInt()
                    name to code
                }.getOrDefault("1.0" to 1)
            }
            Text(
                text = stringResource(R.string.settings_version_format, versionName, versionCode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp)
            )
        }
    }

    if (showLocalGuideDialog) {
        AlertDialog(
            onDismissRequest = { showLocalGuideDialog = false },
            title = { Text(stringResource(R.string.local_guide_title)) },
            text = { Text(stringResource(R.string.local_guide_body)) },
            confirmButton = {
                TextButton(onClick = { showLocalGuideDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showCompressedDownloadsDialog) {
        CompressedDownloadsDialog(
            allEpisodes = allEpisodes,
            onDismiss = { showCompressedDownloadsDialog = false }
        )
    }
}

@Composable
private fun SettingsSection(
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
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
private fun SettingSwitchRow(
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
private fun CompressedDownloadsDialog(
    allEpisodes: List<EpisodeWithPodcast>,
    onDismiss: () -> Unit
) {
    val compressedList = remember(allEpisodes) {
        allEpisodes.filter { item ->
            !item.podcast.isLocal &&
                item.episode.downloadState == DownloadState.DOWNLOADED &&
                item.episode.markedForDeletionAtMs == 0L &&
                item.episode.isTranscoded &&
                item.episode.localFilePath?.endsWith(".m4a", ignoreCase = true) == true
        }.sortedByDescending { it.episode.pubDateMs }
    }
    val totalCurrentBytes = remember(compressedList) {
        compressedList.sumOf { item ->
            item.episode.fileSizeBytes.takeIf { it > 0L }
                ?: runCatching { File(item.episode.localFilePath.orEmpty()).length() }.getOrDefault(0L)
        }
    }
    val totalSavedBytes = remember(compressedList) {
        compressedList.sumOf { it.episode.transcodeSavedBytes.coerceAtLeast(0L) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_compressed_downloads_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.compressed_downloads_total_summary,
                        compressedList.size,
                        formatSizeMb(totalCurrentBytes),
                        formatSizeMb(totalSavedBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(compressedList, key = { it.episode.id }) { item ->
                        val ep = item.episode
                        val pod = item.podcast
                        val currentBytes = ep.fileSizeBytes.takeIf { it > 0L }
                            ?: runCatching { File(ep.localFilePath.orEmpty()).length() }.getOrDefault(0L)
                        val savedBytes = ep.transcodeSavedBytes.coerceAtLeast(0L)
                        val origBytes = currentBytes + savedBytes
                        val savedPct = if (origBytes > 0L && savedBytes > 0L) {
                            ((savedBytes * 100L) / origBytes).toInt().coerceIn(1, 99)
                        } else {
                            0
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastImage(
                                url = ep.artworkUrl.ifEmpty { pod.artworkUrl },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ep.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pod.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (savedBytes > 0L) {
                                        stringResource(
                                            R.string.compressed_episode_saved_detail,
                                            formatSizeMb(origBytes),
                                            formatSizeMb(currentBytes),
                                            formatSizeMb(savedBytes),
                                            savedPct
                                        )
                                    } else {
                                        stringResource(
                                            R.string.compressed_episode_size_only,
                                            formatSizeMb(currentBytes)
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.alpha(0.35f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun formatSyncMinutesLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.interval_off)
    minutes < 60 -> stringResource(R.string.sync_interval_minutes_short, minutes)
    else -> stringResource(R.string.sync_interval_hours_short, minutes / 60)
}

private fun formatSizeMb(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (mb >= 100.0) {
        String.format(Locale.getDefault(), "%.0f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}
