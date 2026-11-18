package com.lycoslane.purepodcast.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PurePodcastDaoTest {

    private lateinit var db: PurePodcastDatabase
    private lateinit var dao: PurePodcastDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PurePodcastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun queueProtectsEpisodesFromTrashAndAutoTrim() = runTest {
        val podcastId = dao.insertPodcast(
            PodcastEntity(feedUrl = "https://example.com/feed.xml", title = "Show")
        )
        val ids = dao.insertEpisodes(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "g1",
                    title = "Ep 1",
                    audioUrl = "https://example.com/1.mp3",
                    pubDateMs = 1_000L,
                    downloadState = DownloadState.DOWNLOADED,
                    localFilePath = "/tmp/1.mp3"
                ),
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "g2",
                    title = "Ep 2",
                    audioUrl = "https://example.com/2.mp3",
                    pubDateMs = 2_000L,
                    downloadState = DownloadState.DOWNLOADED,
                    localFilePath = "/tmp/2.mp3"
                )
            )
        )
        val ep1 = ids[0]
        val ep2 = ids[1]

        // Marking ep1 for deletion sets skipAutoDownload = true and makes it eligible for trash sweep
        dao.markForDeletion(ep1, 500L)
        assertTrue(dao.getEpisode(ep1)!!.skipAutoDownload)
        assertEquals(listOf(ep1), dao.getTrashedDownloadsOlderThan(1_000L).map { it.id })

        // Enqueuing ep1 clears its trash timestamp and protects it from trash sweep & max-download trim
        dao.enqueueToQueue(ep1)
        assertEquals(0L, dao.getEpisode(ep1)!!.markedForDeletionAtMs)
        assertTrue(dao.getTrashedDownloadsOlderThan(1_000L).isEmpty())
        assertEquals(listOf(ep2), dao.getActiveDownloadsForPodcastNotInQueue(podcastId).map { it.id })

        // Enqueuing ep2 to front puts ep2 before ep1
        dao.enqueueToQueueFront(ep2)
        assertEquals(listOf(ep2, ep1), dao.getQueueEpisodesOnce().map { it.episode.id })
    }
}
