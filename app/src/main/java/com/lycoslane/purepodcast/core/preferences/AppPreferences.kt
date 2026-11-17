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
    val maxDownloadsPerPodcast: Int = 0, // 0 = unlimited, 1..30
    val smartRewindSeconds: Int = 10, // 0 = off
    val audioNormalizationEnabled: Boolean = false,
    val bluetoothNextPrevSeeks: Boolean = true, // true = -10s/+30s, false = prev/next in queue
    val appLanguage: String = "en" // "en" or "de"
)

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        refreshIntervalHours = prefs.getInt(KEY_REFRESH_INTERVAL_HOURS, 6),
        wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, true),
        autoDownloadNewByDefault = prefs.getBoolean(KEY_AUTO_DOWNLOAD_DEFAULT, false),
        downloadWhenQueued = prefs.getBoolean(KEY_DOWNLOAD_WHEN_QUEUED, false),
        addToQueueWhenDownloaded = prefs.getBoolean(KEY_QUEUE_WHEN_DOWNLOADED, false),
        autoRemovePlayedDays = prefs.getInt(KEY_AUTO_REMOVE_PLAYED_DAYS, 1),
        maxDownloadsPerPodcast = prefs.getInt(KEY_MAX_DOWNLOADS_PER_PODCAST, 0).coerceIn(0, 30),
        smartRewindSeconds = prefs.getInt(KEY_SMART_REWIND_SECONDS, 10),
        audioNormalizationEnabled = prefs.getBoolean(KEY_AUDIO_NORMALIZATION, false),
        bluetoothNextPrevSeeks = prefs.getBoolean(KEY_BLUETOOTH_NEXT_PREV_SEEKS, true),
        appLanguage = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en"
    )

    fun setRefreshIntervalHours(hours: Int) {
        prefs.edit().putInt(KEY_REFRESH_INTERVAL_HOURS, hours).apply()
        _settings.update { it.copy(refreshIntervalHours = hours) }
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
        prefs.edit().putInt(KEY_AUTO_REMOVE_PLAYED_DAYS, days).apply()
        _settings.update { it.copy(autoRemovePlayedDays = days) }
    }

    fun setMaxDownloadsPerPodcast(limit: Int) {
        val clamped = limit.coerceIn(0, 30)
        prefs.edit().putInt(KEY_MAX_DOWNLOADS_PER_PODCAST, clamped).apply()
        _settings.update { it.copy(maxDownloadsPerPodcast = clamped) }
    }

    fun setSmartRewindSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SMART_REWIND_SECONDS, seconds).apply()
        _settings.update { it.copy(smartRewindSeconds = seconds) }
    }

    fun setAudioNormalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_NORMALIZATION, enabled).apply()
        _settings.update { it.copy(audioNormalizationEnabled = enabled) }
    }

    fun setBluetoothNextPrevSeeks(seeks: Boolean) {
        prefs.edit().putBoolean(KEY_BLUETOOTH_NEXT_PREV_SEEKS, seeks).apply()
        _settings.update { it.copy(bluetoothNextPrevSeeks = seeks) }
    }

    fun setAppLanguage(languageTag: String) {
        val normalized = if (languageTag == "de") "de" else "en"
        prefs.edit().putString(KEY_APP_LANGUAGE, normalized).apply()
        _settings.update { it.copy(appLanguage = normalized) }
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
        private const val KEY_MAX_DOWNLOADS_PER_PODCAST = "max_downloads_per_podcast"
        private const val KEY_SMART_REWIND_SECONDS = "smart_rewind_seconds"
        private const val KEY_AUDIO_NORMALIZATION = "audio_normalization_enabled"
        private const val KEY_BLUETOOTH_NEXT_PREV_SEEKS = "bluetooth_next_prev_seeks"
    }
}
