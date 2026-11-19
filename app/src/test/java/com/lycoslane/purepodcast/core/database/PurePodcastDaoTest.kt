package com.lycoslane.purepodcast.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.AudioOverride
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

    @Test
    fun transcodeMetadataPersistsAndResetsOnRedownload() = runTest {
        val podcastId = dao.insertPodcast(
            PodcastEntity(feedUrl = "https://example.com/feed2.xml", title = "Show 2")
        )
        val epId = dao.insertEpisodes(
            listOf(
                EpisodeEntity(
                    podcastId = podcastId,
                    guid = "g-transcode",
                    title = "Ep Transcode",
                    audioUrl = "https://example.com/ep.mp3",
                    pubDateMs = 1_000L,
                    fileSizeBytes = 50_000_000L,
                    downloadState = DownloadState.DOWNLOADED,
                    localFilePath = "/tmp/ep.mp3"
                )
            )
        ).first()

        dao.markEpisodeTranscoded(
            episodeId = epId,
            newPath = "/tmp/ep.m4a",
            newSizeBytes = 25_000_000L,
            savedBytes = 25_000_000L
        )
        val afterTranscode = dao.getEpisode(epId)!!
        assertTrue(afterTranscode.isTranscoded)
        assertEquals("/tmp/ep.m4a", afterTranscode.localFilePath)
        assertEquals(25_000_000L, afterTranscode.fileSizeBytes)
        assertEquals(25_000_000L, afterTranscode.transcodeSavedBytes)

        // Resetting or re-downloading clears isTranscoded and transcodeSavedBytes
        dao.updateDownloadState(epId, DownloadState.NONE, 0, null)
        val afterReset = dao.getEpisode(epId)!!
        assertEquals(false, afterReset.isTranscoded)
        assertEquals(0L, afterReset.transcodeSavedBytes)
    }

    @Test
    fun monoAndNormalizationOverridesPersistAndIncludeEpisodesInProgress() = runTest {
        val podId = dao.insertPodcast(
            PodcastEntity(feedUrl = "https://example.com/mono.xml", title = "Mono Show")
        )
        val epId = dao.insertEpisodes(
            listOf(
                EpisodeEntity(
                    podcastId = podId,
                    guid = "mono-ep-1",
                    title = "Unplayed But Fixed",
                    audioUrl = "https://example.com/m1.mp3"
                )
            )
        ).first()

        dao.setPodcastMonoModeOverride(podId, AudioMonoMode.LEFT)
        dao.setPodcastAudioNormalizationOverride(podId, AudioOverride.ON)
        dao.setEpisodeMonoModeOverride(epId, AudioMonoMode.RIGHT)

        val pod = dao.getPodcast(podId)!!
        assertEquals(AudioMonoMode.LEFT, pod.monoModeOverride)
        assertEquals(AudioOverride.ON, pod.audioNormalizationOverride)
        assertEquals(AudioMonoMode.RIGHT, dao.getEpisode(epId)!!.monoModeOverride)
        assertEquals(listOf(epId), dao.getEpisodesWithProgress(podId).map { it.id })
    }

    @Test
    fun migratesV10DatabaseToV11PreservingExistingRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_10_11_test.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `podcasts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `feedUrl` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `artworkUrl` TEXT NOT NULL,
                        `autoDownloadFromPubDateMs` INTEGER NOT NULL,
                        `sortAscending` INTEGER NOT NULL,
                        `playbackSpeed` REAL NOT NULL,
                        `skipSilence` INTEGER NOT NULL,
                        `silenceIntensity` INTEGER NOT NULL,
                        `lastRefreshedMs` INTEGER NOT NULL,
                        `isLocal` INTEGER NOT NULL,
                        `isSubscribed` INTEGER NOT NULL,
                        `etag` TEXT NOT NULL,
                        `lastModified` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feedUrl` ON `podcasts` (`feedUrl`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `episodes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `podcastId` INTEGER NOT NULL,
                        `guid` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `descriptionHtml` TEXT NOT NULL,
                        `audioUrl` TEXT NOT NULL,
                        `artworkUrl` TEXT NOT NULL,
                        `pubDateMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `isPlayed` INTEGER NOT NULL,
                        `playedAtMs` INTEGER NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `markedForDeletionAtMs` INTEGER NOT NULL,
                        `localFilePath` TEXT,
                        `downloadState` TEXT NOT NULL,
                        `downloadProgress` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `skipAutoDownload` INTEGER NOT NULL,
                        `chaptersJson` TEXT NOT NULL,
                        `isTranscoded` INTEGER NOT NULL,
                        `transcodeSavedBytes` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId` ON `episodes` (`podcastId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_episodes_podcastId_guid` ON `episodes` (`podcastId`, `guid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_pubDateMs` ON `episodes` (`podcastId`, `pubDateMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_isPlayed` ON `episodes` (`podcastId`, `isPlayed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_downloadState` ON `episodes` (`podcastId`, `downloadState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isPlayed_downloadState` ON `episodes` (`isPlayed`, `downloadState`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_isHidden_pubDateMs` ON `episodes` (`isHidden`, `pubDateMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_podcastId_isHidden` ON `episodes` (`podcastId`, `isHidden`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_markedForDeletionAtMs` ON `episodes` (`markedForDeletionAtMs`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queue_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `episodeId` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_items_episodeId` ON `queue_items` (`episodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_items_sortOrder` ON `queue_items` (`sortOrder`)")

                db.execSQL(
                    """
                    INSERT INTO `podcasts` (`id`, `feedUrl`, `title`, `author`, `description`, `artworkUrl`,
                        `autoDownloadFromPubDateMs`, `sortAscending`, `playbackSpeed`, `skipSilence`,
                        `silenceIntensity`, `lastRefreshedMs`, `isLocal`, `isSubscribed`, `etag`, `lastModified`)
                    VALUES (1, 'https://example.com/v10.xml', 'Legacy Show', '', '', '', 0, 0, 1.25, 1, 2, 0, 0, 1, '', '')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `episodes` (`id`, `podcastId`, `guid`, `title`, `descriptionHtml`, `audioUrl`,
                        `artworkUrl`, `pubDateMs`, `durationMs`, `positionMs`, `isPlayed`, `playedAtMs`, `isHidden`,
                        `markedForDeletionAtMs`, `localFilePath`, `downloadState`, `downloadProgress`,
                        `fileSizeBytes`, `skipAutoDownload`, `chaptersJson`, `isTranscoded`,
                        `transcodeSavedBytes`, `isFavorite`)
                    VALUES (10, 1, 'legacy-ep', 'Legacy Ep', '', 'https://example.com/ep.mp3',
                        '', 1000, 60000, 15000, 0, 0, 0, 0, NULL, 'NONE', 0, 0, 0, '', 0, 0, 1)
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(context, PurePodcastDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(PurePodcastDatabase.MIGRATION_10_11)
            .build()
        try {
            val migratedDao = migratedDb.dao()
            val pod = migratedDao.getPodcast(1L)!!
            assertEquals("Legacy Show", pod.title)
            assertEquals(1.25f, pod.playbackSpeed, 0.001f)
            assertEquals(AudioMonoMode.INHERIT, pod.monoModeOverride)
            assertEquals(AudioOverride.INHERIT, pod.audioNormalizationOverride)

            val ep = migratedDao.getEpisode(10L)!!
            assertEquals("Legacy Ep", ep.title)
            assertTrue(ep.isFavorite)
            assertEquals(AudioMonoMode.INHERIT, ep.monoModeOverride)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
