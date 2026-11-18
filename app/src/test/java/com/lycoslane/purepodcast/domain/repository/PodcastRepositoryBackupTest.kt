package com.lycoslane.purepodcast.domain.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [34])
class PodcastRepositoryBackupTest {

    @Test
    fun exportAndImportBackupJsonRoundTripsSettingsAndProgress() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        prefs.setSmartRewindSeconds(25)
        prefs.setAppLanguage("de")

        val dao = PurePodcastDatabase.getInstance(context).dao()
        val podId = dao.insertPodcast(
            PodcastEntity(
                feedUrl = "https://nonexistent.invalid/feed.xml",
                title = "Backup Show",
                playbackSpeed = 1.5f,
                skipSilence = true,
                silenceIntensity = 2,
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
                    playedAtMs = 999_000L
                )
            )
        )
        dao.enqueueToQueue(epIds[0])

        val repo = PodcastRepository(context, prefs)
        val exported = repo.exportBackupJson()
        val json = JSONObject(exported)
        assertEquals(1, json.getInt("version"))
        assertEquals(25, json.getJSONObject("settings").getInt("smartRewindSeconds"))

        // Reset state, then import backup offline
        prefs.setSmartRewindSeconds(0)
        prefs.setAppLanguage("en")
        dao.clearQueue()
        dao.updatePosition(epIds[0], 0L)
        dao.setPlayed(epIds[0], false, 0L)
        dao.setFavorite(epIds[0], false)

        val restoredCount = repo.importBackupJson(exported)
        assertEquals(1, restoredCount)
        assertEquals(25, prefs.settings.value.smartRewindSeconds)
        assertEquals("de", prefs.settings.value.appLanguage)

        val restoredEp = dao.getEpisode(epIds[0])!!
        assertEquals(42_000L, restoredEp.positionMs)
        assertTrue(restoredEp.isPlayed)
        assertTrue(restoredEp.isFavorite)
        assertEquals(listOf(epIds[0]), dao.getQueueEpisodesOnce().map { it.episode.id })
    }
}
