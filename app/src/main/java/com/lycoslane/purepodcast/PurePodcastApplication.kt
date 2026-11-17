package com.lycoslane.purepodcast

import android.app.Application
import com.lycoslane.purepodcast.core.audio.PlayerController
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import com.lycoslane.purepodcast.domain.repository.PodcastRepository

class PurePodcastApplication : Application() {

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
    }

    override fun onTerminate() {
        playerController.release()
        super.onTerminate()
    }
}
