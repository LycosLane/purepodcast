package com.lycoslane.purepodcast.core.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettings(
    val refreshIntervalHours: Int = 6, // 0 = manual only
    val wifiOnlyDownloads: Boolean = true,
    val autoDownloadNewByDefault: Boolean = false,
    val downloadWhenQueued: Boolean = false,
    val addToQueueWhenDownloaded: Boolean = false,
    val autoRemovePlayedDays: Int = 1, // -1 = never, 0 = immediately, >0 = after X days
    val trashRetentionHours: Int = 24, // 0 = delete immediately, 24 = 24h, 48 = 48h
    val maxDownloadsPerPodcast: Int = 0, // 0 = unlimited, 1..30
    val smartRewindSeconds: Int = 10, // 0 = off
    val audioNormalizationEnabled: Boolean = false,
    val bluetoothNextPrevSeeks: Boolean = true, // true = -10s/+30s, false = prev/next in queue
    val textScale: Float = 1.0f, // 0.8f..1.8f global pinch-to-zoom text scale
    val appLanguage: String = "en", // "en" or "de"
    val transcodeBitrateKbps: Int = 0, // 0 = off, 96 = 96 kbps, 128 = 128 kbps
    val transcodeAutoEnabled: Boolean = false,
    val transcodeChargingOnly: Boolean = true,
    val localFolderUri: String = "",
    val showSubscribedPodcasts: Boolean = true,
    val showLocalPodcasts: Boolean = true
)

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        refreshIntervalHours = prefs.getInt(KEY_REFRESH_INTERVAL_HOURS, 6).coerceIn(0, 168),
        wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, true),
        autoDownloadNewByDefault = prefs.getBoolean(KEY_AUTO_DOWNLOAD_DEFAULT, false),
        downloadWhenQueued = prefs.getBoolean(KEY_DOWNLOAD_WHEN_QUEUED, false),
        addToQueueWhenDownloaded = prefs.getBoolean(KEY_QUEUE_WHEN_DOWNLOADED, false),
        autoRemovePlayedDays = prefs.getInt(KEY_AUTO_REMOVE_PLAYED_DAYS, 1).coerceIn(-1, 365),
        trashRetentionHours = prefs.getInt(KEY_TRASH_RETENTION_HOURS, 24).let {
            if (it in listOf(0, 24, 48)) it else 24
        },
        maxDownloadsPerPodcast = prefs.getInt(KEY_MAX_DOWNLOADS_PER_PODCAST, 0).coerceIn(0, 30),
        smartRewindSeconds = prefs.getInt(KEY_SMART_REWIND_SECONDS, 10).coerceIn(0, 120),
        audioNormalizationEnabled = prefs.getBoolean(KEY_AUDIO_NORMALIZATION, false),
        bluetoothNextPrevSeeks = prefs.getBoolean(KEY_BLUETOOTH_NEXT_PREV_SEEKS, true),
        textScale = prefs.getFloat(KEY_TEXT_SCALE, 1.0f).coerceIn(0.8f, 1.8f),
        appLanguage = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en",
        transcodeBitrateKbps = prefs.getInt(KEY_TRANSCODE_BITRATE_KBPS, 0).let {
            if (it in listOf(0, 96, 128)) it else 0
        },
        transcodeAutoEnabled = prefs.getBoolean(KEY_TRANSCODE_AUTO, false),
        transcodeChargingOnly = prefs.getBoolean(KEY_TRANSCODE_CHARGING_ONLY, true),
        localFolderUri = prefs.getString(KEY_LOCAL_FOLDER_URI, "") ?: "",
        showSubscribedPodcasts = prefs.getBoolean(KEY_SHOW_SUBSCRIBED, true),
        showLocalPodcasts = prefs.getBoolean(KEY_SHOW_LOCAL, true)
    )

    fun setRefreshIntervalHours(hours: Int) {
        val clamped = hours.coerceIn(0, 168)
        prefs.edit().putInt(KEY_REFRESH_INTERVAL_HOURS, clamped).apply()
        _settings.update { it.copy(refreshIntervalHours = clamped) }
    }

    fun setWifiOnlyDownloads(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
        _settings.update { it.copy(wifiOnlyDownloads = wifiOnly) }
    }

    fun setAutoDownloadNewByDefault(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_DEFAULT, enabled).apply()
        _settings.update { it.copy(autoDownloadNewByDefault = enabled) }
    }

    fun setDownloadWhenQueued(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_WHEN_QUEUED, enabled).apply()
        _settings.update { it.copy(downloadWhenQueued = enabled) }
    }

    fun setAddToQueueWhenDownloaded(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUEUE_WHEN_DOWNLOADED, enabled).apply()
        _settings.update { it.copy(addToQueueWhenDownloaded = enabled) }
    }

    fun setAutoRemovePlayedDays(days: Int) {
        val clamped = days.coerceIn(-1, 365)
        prefs.edit().putInt(KEY_AUTO_REMOVE_PLAYED_DAYS, clamped).apply()
        _settings.update { it.copy(autoRemovePlayedDays = clamped) }
    }

    fun setTrashRetentionHours(hours: Int) {
        val normalized = if (hours in listOf(0, 24, 48)) hours else 24
        prefs.edit().putInt(KEY_TRASH_RETENTION_HOURS, normalized).apply()
        _settings.update { it.copy(trashRetentionHours = normalized) }
    }

    fun setMaxDownloadsPerPodcast(limit: Int) {
        val clamped = limit.coerceIn(0, 30)
        prefs.edit().putInt(KEY_MAX_DOWNLOADS_PER_PODCAST, clamped).apply()
        _settings.update { it.copy(maxDownloadsPerPodcast = clamped) }
    }

    fun setSmartRewindSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 120)
        prefs.edit().putInt(KEY_SMART_REWIND_SECONDS, clamped).apply()
        _settings.update { it.copy(smartRewindSeconds = clamped) }
    }

    fun setAudioNormalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_NORMALIZATION, enabled).apply()
        _settings.update { it.copy(audioNormalizationEnabled = enabled) }
    }

    fun setBluetoothNextPrevSeeks(seeks: Boolean) {
        prefs.edit().putBoolean(KEY_BLUETOOTH_NEXT_PREV_SEEKS, seeks).apply()
        _settings.update { it.copy(bluetoothNextPrevSeeks = seeks) }
    }

    fun setTextScale(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 1.8f)
        prefs.edit().putFloat(KEY_TEXT_SCALE, clamped).apply()
        _settings.update { it.copy(textScale = clamped) }
    }

    fun setAppLanguage(languageTag: String) {
        val normalized = if (languageTag == "de") "de" else "en"
        prefs.edit().putString(KEY_APP_LANGUAGE, normalized).apply()
        _settings.update { it.copy(appLanguage = normalized) }
    }

    fun setTranscodeBitrateKbps(kbps: Int) {
        val normalized = if (kbps in listOf(0, 96, 128)) kbps else 0
        prefs.edit().putInt(KEY_TRANSCODE_BITRATE_KBPS, normalized).apply()
        _settings.update { it.copy(transcodeBitrateKbps = normalized) }
    }

    fun setTranscodeAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSCODE_AUTO, enabled).apply()
        _settings.update { it.copy(transcodeAutoEnabled = enabled) }
    }

    fun setTranscodeChargingOnly(chargingOnly: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSCODE_CHARGING_ONLY, chargingOnly).apply()
        _settings.update { it.copy(transcodeChargingOnly = chargingOnly) }
    }

    fun setLocalFolderUri(uri: String) {
        prefs.edit().putString(KEY_LOCAL_FOLDER_URI, uri).apply()
        _settings.update { it.copy(localFolderUri = uri) }
    }

    fun setShowSubscribedPodcasts(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SUBSCRIBED, show).apply()
        _settings.update { it.copy(showSubscribedPodcasts = show) }
    }

    fun setShowLocalPodcasts(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LOCAL, show).apply()
        _settings.update { it.copy(showLocalPodcasts = show) }
    }

    companion object {
        const val PREFS_NAME = "purepodcast_prefs"
        const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_REFRESH_INTERVAL_HOURS = "refresh_interval_hours"
        private const val KEY_WIFI_ONLY = "wifi_only_downloads"
        private const val KEY_AUTO_DOWNLOAD_DEFAULT = "auto_download_new_default"
        private const val KEY_DOWNLOAD_WHEN_QUEUED = "download_when_queued"
        private const val KEY_QUEUE_WHEN_DOWNLOADED = "queue_when_downloaded"
        private const val KEY_AUTO_REMOVE_PLAYED_DAYS = "auto_remove_played_days"
        private const val KEY_TRASH_RETENTION_HOURS = "trash_retention_hours"
        private const val KEY_MAX_DOWNLOADS_PER_PODCAST = "max_downloads_per_podcast"
        private const val KEY_SMART_REWIND_SECONDS = "smart_rewind_seconds"
        private const val KEY_AUDIO_NORMALIZATION = "audio_normalization_enabled"
        private const val KEY_BLUETOOTH_NEXT_PREV_SEEKS = "bluetooth_next_prev_seeks"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_TRANSCODE_BITRATE_KBPS = "transcode_bitrate_kbps"
        private const val KEY_TRANSCODE_AUTO = "transcode_auto_enabled"
        private const val KEY_TRANSCODE_CHARGING_ONLY = "transcode_charging_only"
        private const val KEY_LOCAL_FOLDER_URI = "local_folder_uri"
        private const val KEY_SHOW_SUBSCRIBED = "show_subscribed_podcasts"
        private const val KEY_SHOW_LOCAL = "show_local_podcasts"
    }
}
