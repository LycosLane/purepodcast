package com.lycoslane.purepodcast

import android.app.Application
import com.lycoslane.purepodcast.core.audio.PlayerController
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.domain.repository.PodcastRepository

class PurePodcastApplication : Application() {

    lateinit var repository: PodcastRepository
        private set

    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PodcastRepository(this)
        playerController = PlayerController(this).apply { connect() }
        FeedRefreshWorker.schedulePeriodic(this)
    }

    override fun onTerminate() {
        playerController.release()
        super.onTerminate()
    }
}
