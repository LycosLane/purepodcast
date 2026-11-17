package com.lycoslane.purepodcast.core.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettings(
    val refreshIntervalHours: Int = 6, // 0 = manual only
    val wifiOnlyDownloads: Boolean = true,
    val autoDownloadNewByDefault: Boolean = false
)

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("purepodcast_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        refreshIntervalHours = prefs.getInt(KEY_REFRESH_INTERVAL_HOURS, 6),
        wifiOnlyDownloads = prefs.getBoolean(KEY_WIFI_ONLY, true),
        autoDownloadNewByDefault = prefs.getBoolean(KEY_AUTO_DOWNLOAD_DEFAULT, false)
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

    companion object {
        private const val KEY_REFRESH_INTERVAL_HOURS = "refresh_interval_hours"
        private const val KEY_WIFI_ONLY = "wifi_only_downloads"
        private const val KEY_AUTO_DOWNLOAD_DEFAULT = "auto_download_new_default"
    }
}
