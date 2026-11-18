package com.lycoslane.purepodcast.core.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
        val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

        prefs.setRefreshIntervalHours(999)
        assertEquals(168, prefs.settings.value.refreshIntervalHours)
        prefs.setRefreshIntervalHours(-5)
        assertEquals(0, prefs.settings.value.refreshIntervalHours)

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
        assertEquals(0, prefs.settings.value.transcodeBitrateKbps)
        prefs.setTranscodeBitrateKbps(128)
        assertEquals(128, prefs.settings.value.transcodeBitrateKbps)
    }
}
