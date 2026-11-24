package com.lycoslane.purepodcast.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.core.download.TranscodeStatus
import com.lycoslane.purepodcast.core.local.LocalPodcastScanner
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import com.lycoslane.purepodcast.core.preferences.AppSettings
import com.lycoslane.purepodcast.domain.repository.CleanupPreview
import com.lycoslane.purepodcast.ui.common.MonoModeSelector
import com.lycoslane.purepodcast.ui.common.PureSegmentedControl
import com.lycoslane.purepodcast.ui.common.formatFileSize
import com.lycoslane.purepodcast.ui.player.SpeedControlPanel
import com.lycoslane.purepodcast.ui.theme.PureAccent
import com.lycoslane.purepodcast.ui.theme.isDarkThemeEffective
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
    onAddAllDownloadedToQueue: () -> Unit,
    onChangeAutoRemovePlayedDays: (Int) -> Unit,
    onChangeTrashRetentionHours: (Int) -> Unit,
    onChangeMaxDownloadsPerPodcast: (Int) -> Unit,
    onFlushPendingDownloadSettings: () -> Unit,
    onPreviewCleanupImpact: ((CleanupPreview) -> Unit) -> Unit,
    onConfirmCleanupNow: () -> Unit,
    onToggleNotifyNewEpisodes: (Boolean) -> Unit,
    onChangeSmartRewind: (Int) -> Unit,
    onChangeDefaultSpeed: (Float) -> Unit,
    onChangeDefaultSilenceMode: (Boolean, Int) -> Unit,
    onToggleAudioNormalization: (Boolean) -> Unit,
    onChangeMonoMode: (Int) -> Unit,
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
    var cleanupSettingsChanged by rememberSaveable { mutableStateOf(false) }
    var pendingCleanupPreview by remember { mutableStateOf<CleanupPreview?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleNotifyNewEpisodes(true)
        }
    }

    val handleExit: () -> Unit = {
        onFlushPendingDownloadSettings()
        if (cleanupSettingsChanged) {
            onPreviewCleanupImpact { preview ->
                if (preview.episodeCount > 0) {
                    pendingCleanupPreview = preview
                } else {
                    onBack()
                }
            }
        } else {
            onBack()
        }
    }

    BackHandler(enabled = pendingCleanupPreview == null && !showLocalGuideDialog && !showCompressedDownloadsDialog) {
        handleExit()
    }

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
                    IconButton(onClick = handleExit) {
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
                SpeedControlPanel(
                    episodeId = 0L,
                    currentSpeed = settings.defaultPlaybackSpeed,
                    onSetSpeed = onChangeDefaultSpeed
                )
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.player_silence),
                    checked = settings.defaultSkipSilence,
                    onCheckedChange = { enabled ->
                        onChangeDefaultSilenceMode(enabled, settings.defaultSilenceIntensity)
                    }
                )
                if (settings.defaultSkipSilence) {
                    Spacer(Modifier.height(8.dp))
                    PureSegmentedControl(
                        options = listOf(
                            0 to stringResource(R.string.silence_soft),
                            1 to stringResource(R.string.silence_medium),
                            2 to stringResource(R.string.silence_strong)
                        ),
                        selected = settings.defaultSilenceIntensity.coerceIn(0, 2),
                        onSelect = { onChangeDefaultSilenceMode(true, it) }
                    )
                }
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_audio_normalization),
                    checked = settings.audioNormalizationEnabled,
                    onCheckedChange = onToggleAudioNormalization
                )
                SectionDivider()
                val isGlobalMonoOn = settings.monoMode != AudioMonoMode.OFF
                SettingSwitchRow(
                    title = stringResource(R.string.settings_mono_audio),
                    checked = isGlobalMonoOn,
                    onCheckedChange = { enabled ->
                        onChangeMonoMode(if (enabled) AudioMonoMode.MIX else AudioMonoMode.OFF)
                    }
                )
                if (isGlobalMonoOn) {
                    Spacer(Modifier.height(8.dp))
                    MonoModeSelector(
                        selectedMode = settings.monoMode,
                        onSelectMode = onChangeMonoMode
                    )
                }
                SectionDivider()
                val isSmartRewindOn = settings.smartRewindSeconds > 0
                SettingSwitchRow(
                    title = stringResource(R.string.settings_smart_rewind),
                    checked = isSmartRewindOn,
                    onCheckedChange = { enabled ->
                        onChangeSmartRewind(if (enabled) 10 else 0)
                    }
                )
                if (isSmartRewindOn) {
                    Spacer(Modifier.height(8.dp))
                    PureSegmentedControl(
                        options = listOf(
                            5 to stringResource(R.string.seconds_short, 5),
                            10 to stringResource(R.string.seconds_short, 10),
                            15 to stringResource(R.string.seconds_short, 15),
                            30 to stringResource(R.string.seconds_short, 30)
                        ),
                        selected = settings.smartRewindSeconds,
                        onSelect = onChangeSmartRewind
                    )
                }
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_queue_when_downloaded),
                    checked = settings.addToQueueWhenDownloaded,
                    onCheckedChange = onToggleAddToQueueWhenDownloaded
                )
                if (settings.addToQueueWhenDownloaded) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onAddAllDownloadedToQueue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_add_all_downloaded_to_queue))
                    }
                }
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
                        cleanupSettingsChanged = true
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
                            cleanupSettingsChanged = true
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
                        cleanupSettingsChanged = true
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
                        onSelect = { days ->
                            cleanupSettingsChanged = true
                            onChangeAutoRemovePlayedDays(days)
                        }
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
                    onSelect = { hours ->
                        cleanupSettingsChanged = true
                        onChangeTrashRetentionHours(hours)
                    }
                )
            }

            // --- 4. Audio Compression (Low / Medium / High + Info) ---
            CompressionSettingsSection(
                allEpisodes = allEpisodes,
                settings = settings,
                transcodeStatus = transcodeStatus,
                onChangeTranscodeBitrate = onChangeTranscodeBitrate,
                onToggleTranscodeAuto = onToggleTranscodeAuto,
                onToggleTranscodeChargingOnly = onToggleTranscodeChargingOnly,
                onTranscodeAllNow = onTranscodeAllNow,
                onShowCompressedDownloads = { showCompressedDownloadsDialog = true }
            )

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
                SectionDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notify_new_episodes),
                    checked = settings.notifyNewEpisodes,
                    onCheckedChange = { enabled ->
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onToggleNotifyNewEpisodes(enabled)
                        }
                    }
                )
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
        LocalFolderGuideDialog(onDismiss = { showLocalGuideDialog = false })
    }

    if (showCompressedDownloadsDialog) {
        CompressedDownloadsDialog(
            allEpisodes = allEpisodes,
            onDismiss = { showCompressedDownloadsDialog = false }
        )
    }

    pendingCleanupPreview?.let { preview ->
        val bodyText = if (preview.bytesToFree > 0L) {
            stringResource(
                R.string.dialog_cleanup_confirm_body_size,
                preview.episodeCount,
                formatFileSize(preview.bytesToFree)
            )
        } else {
            stringResource(R.string.dialog_cleanup_confirm_body, preview.episodeCount)
        }
        AlertDialog(
            onDismissRequest = {
                pendingCleanupPreview = null
                onBack()
            },
            title = { Text(stringResource(R.string.dialog_cleanup_confirm_title)) },
            text = { Text(bodyText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCleanupPreview = null
                        onConfirmCleanupNow()
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.action_cleanup_now))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCleanupPreview = null
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.action_cleanup_later))
                }
            }
        )
    }
}
