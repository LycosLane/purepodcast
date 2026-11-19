package com.lycoslane.purepodcast.core.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppPreferencesTest {

    @Test
    fun clampsAndNormalizesSettingsValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)

        prefs.setRefreshIntervalMinutes(9999)
        assertEquals(1440, prefs.settings.value.refreshIntervalMinutes)
        prefs.setRefreshIntervalMinutes(5)
        assertEquals(15, prefs.settings.value.refreshIntervalMinutes)
        prefs.setRefreshIntervalMinutes(30)
        assertEquals(30, prefs.settings.value.refreshIntervalMinutes)
        prefs.setRefreshIntervalMinutes(-5)
        assertEquals(0, prefs.settings.value.refreshIntervalMinutes)

        prefs.setTrashRetentionHours(12)
        assertEquals(24, prefs.settings.value.trashRetentionHours)
        prefs.setTrashRetentionHours(48)
        assertEquals(48, prefs.settings.value.trashRetentionHours)

        prefs.setMaxDownloadsPerPodcast(99)
        assertEquals(30, prefs.settings.value.maxDownloadsPerPodcast)

        prefs.setSmartRewindSeconds(500)
        assertEquals(120, prefs.settings.value.smartRewindSeconds)

        prefs.setTextScale(5.0f)
        assertEquals(1.8f, prefs.settings.value.textScale, 0.001f)
        prefs.setTextScale(0.1f)
        assertEquals(0.8f, prefs.settings.value.textScale, 0.001f)

        prefs.setAppLanguage("fr")
        assertEquals("en", prefs.settings.value.appLanguage)
        prefs.setAppLanguage("de")
        assertEquals("de", prefs.settings.value.appLanguage)

        prefs.setTranscodeBitrateKbps(64)
        assertEquals(64, prefs.settings.value.transcodeBitrateKbps)
        prefs.setTranscodeBitrateKbps(96)
        assertEquals(64, prefs.settings.value.transcodeBitrateKbps)
        prefs.setTranscodeBitrateKbps(128)
        assertEquals(128, prefs.settings.value.transcodeBitrateKbps)
        prefs.setTranscodeBitrateKbps(192)
        assertEquals(192, prefs.settings.value.transcodeBitrateKbps)
        prefs.setTranscodeBitrateKbps(100)
        assertEquals(0, prefs.settings.value.transcodeBitrateKbps)

        prefs.setThemeMode("invalid")
        assertEquals(AppPreferences.THEME_SYSTEM, prefs.settings.value.themeMode)
        prefs.setThemeMode(AppPreferences.THEME_DARK)
        assertEquals(AppPreferences.THEME_DARK, prefs.settings.value.themeMode)

        prefs.setLightAccent("unknown")
        assertEquals(AppPreferences.DEFAULT_ACCENT, prefs.settings.value.lightAccent)
        prefs.setLightAccent("indigo")
        assertEquals("indigo", prefs.settings.value.lightAccent)

        prefs.setDarkAccent("unknown")
        assertEquals(AppPreferences.DEFAULT_ACCENT, prefs.settings.value.darkAccent)
        prefs.setDarkAccent("amber")
        assertEquals("amber", prefs.settings.value.darkAccent)

        prefs.setMonoMode(99)
        assertEquals(AudioMonoMode.OFF, prefs.settings.value.monoMode)
        prefs.setMonoMode(AudioMonoMode.AUTO)
        assertEquals(AudioMonoMode.AUTO, prefs.settings.value.monoMode)
        prefs.setMonoMode(AudioMonoMode.LEFT)
        assertEquals(AudioMonoMode.LEFT, prefs.settings.value.monoMode)

        prefs.setDefaultPlaybackSpeed(0.2f)
        assertEquals(0.50f, prefs.settings.value.defaultPlaybackSpeed, 0.001f)
        prefs.setDefaultPlaybackSpeed(5.0f)
        assertEquals(3.00f, prefs.settings.value.defaultPlaybackSpeed, 0.001f)
        prefs.setDefaultPlaybackSpeed(1.35f)
        assertEquals(1.35f, prefs.settings.value.defaultPlaybackSpeed, 0.001f)

        prefs.setDefaultSilenceMode(true, 9)
        assertEquals(true, prefs.settings.value.defaultSkipSilence)
        assertEquals(2, prefs.settings.value.defaultSilenceIntensity)
        prefs.setDefaultSilenceMode(false, 0)
        assertEquals(false, prefs.settings.value.defaultSkipSilence)
        assertEquals(0, prefs.settings.value.defaultSilenceIntensity)
    }

    @Test
    fun migratesLegacyRefreshHoursWhenMinutesKeyAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        raw.edit().clear().putInt("refresh_interval_hours", 12).commit()

        val migrated = AppPreferences(context)
        assertEquals(720, migrated.settings.value.refreshIntervalMinutes)
    }
}
