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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        playerController = PlayerController(this, preferences).apply { connect() }
        FeedRefreshWorker.schedulePeriodic(this, preferences.settings.value.refreshIntervalHours)
        appScope.launch {
            runCatching { repository.cleanupPlayedDownloads() }
        }
    }

    override fun onTerminate() {
        playerController.release()
        super.onTerminate()
    }
}
