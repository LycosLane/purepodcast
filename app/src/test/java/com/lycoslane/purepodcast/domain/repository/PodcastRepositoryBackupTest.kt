package com.lycoslane.purepodcast.domain.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.AudioOverride
import com.lycoslane.purepodcast.core.audio.SilenceOverride
import com.lycoslane.purepodcast.core.audio.SmartRewindOverride
import com.lycoslane.purepodcast.core.audio.SpeedOverride
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PodcastRepositoryBackupTest {

    @Test
    fun exportAndImportBackupJsonRoundTripsSettingsAndProgress() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val prefs = AppPreferences(context)
        prefs.setSmartRewindSeconds(25)
        prefs.setAppLanguage("de")
        prefs.setRefreshIntervalMinutes(30)
        prefs.setTranscodeBitrateKbps(192)
        prefs.setThemeMode(AppPreferences.THEME_DARK)
        prefs.setLightAccent("indigo")
        prefs.setDarkAccent("amber")
        prefs.setMonoMode(AudioMonoMode.AUTO)
        prefs.setDefaultPlaybackSpeed(1.25f)
        prefs.setDefaultSilenceMode(true, 1)
        prefs.setNotifyNewEpisodes(true)

        val dao = PurePodcastDatabase.getInstance(context).dao()
        val podId = dao.insertPodcast(
            PodcastEntity(
                feedUrl = "https://nonexistent.invalid/feed.xml",
                title = "Backup Show",
                playbackSpeed = 1.5f,
                speedOverride = 1.5f,
                skipSilence = true,
                silenceIntensity = 2,
                silenceModeOverride = SilenceOverride.STRONG,
                monoModeOverride = AudioMonoMode.LEFT,
                audioNormalizationOverride = AudioOverride.ON,
                smartRewindOverride = 15,
                isSubscribed = true
            )
        )
        val epIds = dao.insertEpisodes(
            listOf(
                EpisodeEntity(
                    podcastId = podId,
                    guid = "ep-guid-1",
                    title = "Ep 1",
                    audioUrl = "https://nonexistent.invalid/1.mp3",
                    positionMs = 42_000L,
                    isPlayed = true,
                    isFavorite = true,
                    playedAtMs = 999_000L,
                    monoModeOverride = AudioMonoMode.RIGHT
                )
            )
        )
        dao.enqueueToQueue(epIds[0])

        val repo = PodcastRepository(context, prefs)
        val exported = repo.exportBackupJson()
        val json = JSONObject(exported)
        assertEquals(1, json.getInt("version"))
        assertEquals(25, json.getJSONObject("settings").getInt("smartRewindSeconds"))
        assertEquals(AudioMonoMode.AUTO, json.getJSONObject("settings").getInt("monoMode"))
        assertEquals(1.25, json.getJSONObject("settings").getDouble("defaultPlaybackSpeed"), 0.001)
        assertTrue(json.getJSONObject("settings").getBoolean("defaultSkipSilence"))

        // Reset state, then import backup offline
        prefs.setSmartRewindSeconds(0)
        prefs.setAppLanguage("en")
        prefs.setRefreshIntervalMinutes(360)
        prefs.setTranscodeBitrateKbps(0)
        prefs.setThemeMode(AppPreferences.THEME_SYSTEM)
        prefs.setLightAccent(AppPreferences.DEFAULT_ACCENT)
        prefs.setDarkAccent(AppPreferences.DEFAULT_ACCENT)
        prefs.setMonoMode(AudioMonoMode.OFF)
        prefs.setDefaultPlaybackSpeed(1.0f)
        prefs.setDefaultSilenceMode(false, 1)
        prefs.setNotifyNewEpisodes(false)
        dao.clearQueue()
        dao.setPodcastSpeedOverride(podId, SpeedOverride.INHERIT, 1.0f)
        dao.setPodcastSilenceModeOverride(podId, SilenceOverride.INHERIT, false, 1)
        dao.setPodcastSmartRewindOverride(podId, SmartRewindOverride.INHERIT)
        dao.setPodcastMonoModeOverride(podId, AudioMonoMode.INHERIT)
        dao.setPodcastAudioNormalizationOverride(podId, AudioOverride.INHERIT)
        dao.setEpisodeMonoModeOverride(epIds[0], AudioMonoMode.INHERIT)
        dao.updatePosition(epIds[0], 0L)
        dao.setPlayed(epIds[0], false, 0L)
        dao.setFavorite(epIds[0], false)

        val restoredCount = repo.importBackupJson(exported)
        assertEquals(1, restoredCount)
        assertEquals(25, prefs.settings.value.smartRewindSeconds)
        assertEquals("de", prefs.settings.value.appLanguage)
        assertEquals(30, prefs.settings.value.refreshIntervalMinutes)
        assertEquals(192, prefs.settings.value.transcodeBitrateKbps)
        assertEquals(AppPreferences.THEME_DARK, prefs.settings.value.themeMode)
        assertEquals("indigo", prefs.settings.value.lightAccent)
        assertEquals("amber", prefs.settings.value.darkAccent)
        assertEquals(AudioMonoMode.AUTO, prefs.settings.value.monoMode)
        assertEquals(1.25f, prefs.settings.value.defaultPlaybackSpeed, 0.001f)
        assertTrue(prefs.settings.value.defaultSkipSilence)
        assertEquals(1, prefs.settings.value.defaultSilenceIntensity)
        assertTrue(prefs.settings.value.notifyNewEpisodes)

        val restoredPod = dao.getPodcast(podId)!!
        assertEquals(1.5f, restoredPod.speedOverride, 0.001f)
        assertEquals(SilenceOverride.STRONG, restoredPod.silenceModeOverride)
        assertEquals(15, restoredPod.smartRewindOverride)
        assertEquals(AudioMonoMode.LEFT, restoredPod.monoModeOverride)
        assertEquals(AudioOverride.ON, restoredPod.audioNormalizationOverride)

        val restoredEp = dao.getEpisode(epIds[0])!!
        assertEquals(42_000L, restoredEp.positionMs)
        assertTrue(restoredEp.isPlayed)
        assertTrue(restoredEp.isFavorite)
        assertEquals(AudioMonoMode.RIGHT, restoredEp.monoModeOverride)
        assertEquals(listOf(epIds[0]), dao.getQueueEpisodesOnce().map { it.episode.id })

        // Verify legacy backup JSON without new v1.2.0 override fields imports and promotes overrides cleanly
        dao.setPodcastSpeedOverride(podId, SpeedOverride.INHERIT, 1.0f)
        dao.setPodcastSilenceModeOverride(podId, SilenceOverride.INHERIT, false, 1)
        dao.setPodcastSmartRewindOverride(podId, SmartRewindOverride.INHERIT)
        val legacyJson = JSONObject(exported)
        legacyJson.getJSONObject("settings").apply {
            remove("monoMode")
            remove("defaultPlaybackSpeed")
            remove("defaultSkipSilence")
            remove("defaultSilenceIntensity")
        }
        val legacyPod = legacyJson.getJSONArray("podcasts").getJSONObject(0)
        legacyPod.remove("speedOverride")
        legacyPod.remove("silenceModeOverride")
        legacyPod.remove("smartRewindOverride")
        legacyPod.remove("monoModeOverride")
        legacyPod.remove("audioNormalizationOverride")
        legacyPod.getJSONArray("episodes").getJSONObject(0).remove("monoModeOverride")
        assertEquals(1, repo.importBackupJson(legacyJson.toString()))
        val promotedPod = dao.getPodcast(podId)!!
        assertEquals(1.5f, promotedPod.speedOverride, 0.001f)
        assertEquals(SilenceOverride.STRONG, promotedPod.silenceModeOverride)
        assertEquals(SmartRewindOverride.INHERIT, promotedPod.smartRewindOverride)

        verifyApplyAudioSettingsToAllPodcastsPromotesGlobalDefaultsAndResetsOverrides(repo, dao, prefs)
        verifyQueueActionsAndCleanupPreview(repo, dao, prefs)
    }

    private suspend fun verifyQueueActionsAndCleanupPreview(
        repo: PodcastRepository,
        dao: com.lycoslane.purepodcast.core.database.PurePodcastDao,
        prefs: AppPreferences
    ) {
        val podId = dao.insertPodcast(
            PodcastEntity(feedUrl = "https://nonexistent.invalid/queue-preview.xml", title = "Queue Preview Show")
        )
        try {
            dao.clearQueue()
            val epIds = dao.insertEpisodes(
                listOf(
                    EpisodeEntity(
                        podcastId = podId,
                        guid = "qp-1",
                        title = "Played DL",
                        audioUrl = "https://nonexistent.invalid/1.mp3",
                        pubDateMs = 3_000L,
                        isPlayed = true,
                        playedAtMs = 1_000L,
                        downloadState = com.lycoslane.purepodcast.core.database.DownloadState.DOWNLOADED,
                        localFilePath = "/tmp/nonexistent-1.mp3",
                        fileSizeBytes = 5_000_000L
                    ),
                    EpisodeEntity(
                        podcastId = podId,
                        guid = "qp-2",
                        title = "Unplayed DL",
                        audioUrl = "https://nonexistent.invalid/2.mp3",
                        pubDateMs = 2_000L,
                        downloadState = com.lycoslane.purepodcast.core.database.DownloadState.DOWNLOADED,
                        localFilePath = "/tmp/nonexistent-2.mp3",
                        fileSizeBytes = 3_000_000L
                    ),
                    EpisodeEntity(
                        podcastId = podId,
                        guid = "qp-3",
                        title = "Unplayed DL 2",
                        audioUrl = "https://nonexistent.invalid/3.mp3",
                        pubDateMs = 1_000L,
                        downloadState = com.lycoslane.purepodcast.core.database.DownloadState.DOWNLOADED,
                        localFilePath = "/tmp/nonexistent-3.mp3",
                        fileSizeBytes = 2_000_000L
                    )
                )
            )

            // Preview cleanup for autoRemovePlayedDays = 0 -> catches only qp-1 (5 MB)
            prefs.setAutoRemovePlayedDays(0)
            prefs.setMaxDownloadsPerPodcast(0)
            val preview = repo.previewCleanupImpact(activeEpisodeId = null)
            assertEquals(1, preview.episodeCount)
            assertEquals(5_000_000L, preview.bytesToFree)

            // Add all downloaded episodes to queue -> 3 added
            val added = repo.addAllDownloadedToQueue()
            assertEquals(3, added)
            assertEquals(listOf(epIds[0], epIds[1], epIds[2]), dao.getQueueEpisodeIds())

            // Move epIds[2] right after epIds[0]
            repo.moveEpisodeNextInQueue(epIds[2], epIds[0])
            assertEquals(listOf(epIds[0], epIds[2], epIds[1]), dao.getQueueEpisodeIds())

            // Deleting download while keeping item in queue clears DOWNLOADED state without removing queue entry
            repo.deleteDownloadsKeepInQueue(listOf(epIds[2]))
            assertEquals(listOf(epIds[0], epIds[2], epIds[1]), dao.getQueueEpisodeIds())
            assertEquals(com.lycoslane.purepodcast.core.database.DownloadState.NONE, dao.getEpisode(epIds[2])!!.downloadState)
        } finally {
            dao.clearQueue()
            dao.deletePodcast(podId)
        }
    }

    // Runs in the same Robolectric test because PurePodcastDatabase caches a companion singleton across tests.
    private suspend fun verifyApplyAudioSettingsToAllPodcastsPromotesGlobalDefaultsAndResetsOverrides(
        repo: PodcastRepository,
        dao: com.lycoslane.purepodcast.core.database.PurePodcastDao,
        prefs: AppPreferences
    ) {
        prefs.setDefaultPlaybackSpeed(1.0f)
        prefs.setDefaultSilenceMode(false, 1)
        prefs.setNotifyNewEpisodes(false)
        prefs.setMonoMode(AudioMonoMode.OFF)
        prefs.setAudioNormalizationEnabled(false)
        prefs.setSmartRewindSeconds(10)

        val sourceId = dao.insertPodcast(
            PodcastEntity(
                feedUrl = "https://nonexistent.invalid/source.xml",
                title = "Source Show",
                speedOverride = 1.4f,
                silenceModeOverride = SilenceOverride.MEDIUM,
                monoModeOverride = AudioMonoMode.LEFT,
                audioNormalizationOverride = AudioOverride.ON,
                smartRewindOverride = 20
            )
        )
        val otherId = dao.insertPodcast(
            PodcastEntity(
                feedUrl = "https://nonexistent.invalid/other.xml",
                title = "Other Show",
                speedOverride = 0.8f,
                silenceModeOverride = SilenceOverride.OFF,
                monoModeOverride = AudioMonoMode.RIGHT,
                audioNormalizationOverride = AudioOverride.OFF,
                smartRewindOverride = 5
            )
        )

        try {
            repo.applyAudioSettingsToAllPodcasts(dao.getPodcast(sourceId)!!)

            val global = prefs.settings.value
            assertEquals(1.4f, global.defaultPlaybackSpeed, 0.001f)
            assertTrue(global.defaultSkipSilence)
            assertEquals(1, global.defaultSilenceIntensity)
            assertEquals(AudioMonoMode.LEFT, global.monoMode)
            assertTrue(global.audioNormalizationEnabled)
            assertEquals(20, global.smartRewindSeconds)

            listOf(dao.getPodcast(sourceId)!!, dao.getPodcast(otherId)!!).forEach { pod ->
                assertTrue(SpeedOverride.isInherit(pod.speedOverride))
                assertEquals(SilenceOverride.INHERIT, pod.silenceModeOverride)
                assertEquals(AudioMonoMode.INHERIT, pod.monoModeOverride)
                assertEquals(AudioOverride.INHERIT, pod.audioNormalizationOverride)
                assertEquals(SmartRewindOverride.INHERIT, pod.smartRewindOverride)
            }
        } finally {
            dao.deletePodcast(sourceId)
            dao.deletePodcast(otherId)
        }
    }
}
