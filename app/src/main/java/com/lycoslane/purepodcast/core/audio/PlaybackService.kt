package com.lycoslane.purepodcast.core.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lycoslane.purepodcast.MainActivity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var positionSaveJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPositionSaver()
                } else {
                    saveCurrentPosition()
                    positionSaveJob?.cancel()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                    serviceScope.launch(Dispatchers.IO) {
                        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
                        val now = System.currentTimeMillis()
                        dao.setPlayed(episodeId, true, now)
                        dao.updatePosition(episodeId, 0L)
                        dao.removeFromQueue(episodeId)
                        val autoRemoveDays = com.lycoslane.purepodcast.core.preferences.AppPreferences(applicationContext)
                            .settings.value.autoRemovePlayedDays
                        if (autoRemoveDays == 0) {
                            val ep = dao.getEpisode(episodeId)
                            ep?.localFilePath?.let { path -> java.io.File(path).delete() }
                            dao.updateDownloadState(
                                episodeId,
                                com.lycoslane.purepodcast.core.database.DownloadState.NONE,
                                0,
                                null
                            )
                        }
                    }
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(androidx.media3.session.SessionCommand(CMD_SET_SKIP_SILENCE, android.os.Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if (customCommand.customAction == CMD_SET_SKIP_SILENCE) {
                        player.skipSilenceEnabled = args.getBoolean(ARG_SKIP_SILENCE, false)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                        )
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    companion object {
        const val CMD_SET_SKIP_SILENCE = "com.lycoslane.purepodcast.SET_SKIP_SILENCE"
        const val ARG_SKIP_SILENCE = "skip_silence"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun startPositionSaver() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                saveCurrentPosition()
            }
        }
    }

    private fun saveCurrentPosition() {
        val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        serviceScope.launch(Dispatchers.IO) {
            PurePodcastDatabase.getInstance(applicationContext).dao().updatePosition(episodeId, pos)
        }
    }

    override fun onDestroy() {
        saveCurrentPosition()
        positionSaveJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
