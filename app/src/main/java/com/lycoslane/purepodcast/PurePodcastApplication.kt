package com.lycoslane.purepodcast

import android.app.Application
import com.lycoslane.purepodcast.core.audio.PlayerController
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import com.lycoslane.purepodcast.domain.repository.PodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PurePodcastApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferences: AppPreferences
        private set

    lateinit var repository: PodcastRepository
        private set

    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        repository = PodcastRepository(this, preferences)
        playerController = PlayerController(this, preferences)
        FeedRefreshWorker.schedulePeriodic(
            context = this,
            intervalHours = preferences.settings.value.refreshIntervalHours,
            replaceExisting = false
        )
        appScope.launch {
            runCatching { repository.sweepOrphanedDownloads() }
            runCatching { repository.cleanupPlayedDownloads(playerController.state.value.currentEpisode?.id) }
            val s = preferences.settings.value
            if (s.transcodeAutoEnabled && s.transcodeBitrateKbps > 0) {
                runCatching {
                    com.lycoslane.purepodcast.core.download.TranscodeWorker.enqueueAllNow(
                        context = this@PurePodcastApplication,
                        chargingOnly = s.transcodeChargingOnly
                    )
                }
            }
        }
    }
}
