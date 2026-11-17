package com.lycoslane.purepodcast.core.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lycoslane.purepodcast.MainActivity
import com.lycoslane.purepodcast.PurePodcastApplication
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var positionSaveJob: Job? = null
    private var lastPausedElapsedMs: Long = 0L

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
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        val smartRewindPlayer = object : ForwardingPlayer(player) {
            override fun play() {
                if (!player.playWhenReady && player.playbackState != STATE_ENDED) {
                    val pausedDuration = if (lastPausedElapsedMs > 0L) {
                        SystemClock.elapsedRealtime() - lastPausedElapsedMs
                    } else {
                        0L
                    }
                    val rewindSecs = (applicationContext as PurePodcastApplication)
                        .preferences.settings.value.smartRewindSeconds
                    if (rewindSecs > 0 && pausedDuration >= 30_000L) {
                        val target = (player.currentPosition - rewindSecs * 1000L).coerceAtLeast(0L)
                        player.seekTo(target)
                    }
                }
                super.play()
            }

            override fun pause() {
                if (player.playbackState != STATE_ENDED && player.playbackState != STATE_IDLE) {
                    lastPausedElapsedMs = SystemClock.elapsedRealtime()
                }
                super.pause()
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPositionSaver()
                } else {
                    if (player.playbackState != Player.STATE_ENDED &&
                        player.playbackState != Player.STATE_IDLE &&
                        !player.playWhenReady
                    ) {
                        lastPausedElapsedMs = SystemClock.elapsedRealtime()
                    }
                    saveCurrentPosition()
                    positionSaveJob?.cancel()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    positionSaveJob?.cancel()
                    lastPausedElapsedMs = 0L
                    val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                    serviceScope.launch(Dispatchers.IO) {
                        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
                        val now = System.currentTimeMillis()
                        dao.setPlayed(episodeId, true, now)
                        dao.updatePosition(episodeId, 0L)
                        dao.removeFromQueue(episodeId)
                        val autoRemoveDays = (applicationContext as PurePodcastApplication)
                            .preferences.settings.value.autoRemovePlayedDays
                        if (autoRemoveDays == 0) {
                            val ep = dao.getEpisode(episodeId)
                            ep?.localFilePath?.let { path -> safeDeleteEpisodeFile(path) }
                            dao.updateDownloadState(episodeId, DownloadState.NONE, 0, null)
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

        mediaSession = MediaSession.Builder(this, smartRewindPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val isTrusted = controller.packageName == packageName ||
                        session.isMediaNotificationController(controller)

                    return if (isTrusted) {
                        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .add(SessionCommand(CMD_SET_SKIP_SILENCE, Bundle.EMPTY))
                            .build()
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    } else {
                        val safePlayerCommands = Player.Commands.Builder()
                            .addAll(
                                Player.COMMAND_PLAY_PAUSE,
                                Player.COMMAND_SEEK_BACK,
                                Player.COMMAND_SEEK_FORWARD,
                                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                                Player.COMMAND_GET_METADATA,
                                Player.COMMAND_GET_TIMELINE
                            )
                            .build()
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(SessionCommands.EMPTY)
                            .setAvailablePlayerCommands(safePlayerCommands)
                            .build()
                    }
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (controller.packageName == packageName && customCommand.customAction == CMD_SET_SKIP_SILENCE) {
                        player.skipSilenceEnabled = args.getBoolean(ARG_SKIP_SILENCE, false)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    private fun safeDeleteEpisodeFile(path: String) {
        runCatching {
            val episodesRoot = File(applicationContext.filesDir, "episodes").canonicalFile
            val target = File(path).canonicalFile
            if (target.path.startsWith(episodesRoot.path + File.separator)) {
                target.delete()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null ||
            !currentPlayer.playWhenReady ||
            currentPlayer.mediaItemCount == 0 ||
            currentPlayer.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

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
        if (player.playbackState == Player.STATE_ENDED) return
        val episodeId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        serviceScope.launch(Dispatchers.IO) {
            PurePodcastDatabase.getInstance(applicationContext).dao().updatePosition(episodeId, pos)
        }
    }

    override fun onDestroy() {
        positionSaveJob?.cancel()
        val episodeId = if (player.playbackState != Player.STATE_ENDED) {
            player.currentMediaItem?.mediaId?.toLongOrNull()
        } else {
            null
        }
        val pos = player.currentPosition.coerceAtLeast(0L)

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }

        if (episodeId != null) {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1_500L) {
                    PurePodcastDatabase.getInstance(applicationContext).dao()
                        .updatePosition(episodeId, pos)
                }
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CMD_SET_SKIP_SILENCE = "com.lycoslane.purepodcast.SET_SKIP_SILENCE"
        const val ARG_SKIP_SILENCE = "skip_silence"
    }
}
