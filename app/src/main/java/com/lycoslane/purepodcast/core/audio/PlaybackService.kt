package com.lycoslane.purepodcast.core.audio

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
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
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var positionSaveJob: Job? = null
    private var lastPausedElapsedMs: Long = 0L

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    @OptIn(UnstableApi::class)
    private lateinit var silenceProcessors: List<SilenceSkippingAudioProcessor>

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        silenceProcessors = listOf(
            // 0: Soft
            SilenceSkippingAudioProcessor(1_600_000L, 0.35f, 1_600_000L, 10, 1024),
            // 1: Medium
            SilenceSkippingAudioProcessor(850_000L, 0.20f, 850_000L, 5, 1024),
            // 2: Strong
            SilenceSkippingAudioProcessor(400_000L, 0.10f, 400_000L, 2, 1280)
        )

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(silenceProcessors.toTypedArray())
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        val smartRewindPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .addAll(
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

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

            override fun seekToNext() {
                handleBluetoothSkip(forward = true)
            }

            override fun seekToNextMediaItem() {
                handleBluetoothSkip(forward = true)
            }

            override fun seekToPrevious() {
                handleBluetoothSkip(forward = false)
            }

            override fun seekToPreviousMediaItem() {
                handleBluetoothSkip(forward = false)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                currentAudioSessionId = audioSessionId
                val enabled = (applicationContext as PurePodcastApplication)
                    .preferences.settings.value.audioNormalizationEnabled
                applyAudioNormalization(enabled)
            }

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
                            dao.markForDeletion(episodeId, now)
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
                            .add(SessionCommand(CMD_SET_AUDIO_NORMALIZATION, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SKIP_QUEUE_NEXT, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SKIP_QUEUE_PREV, Bundle.EMPTY))
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
                                Player.COMMAND_SEEK_TO_NEXT,
                                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                                Player.COMMAND_SEEK_TO_PREVIOUS,
                                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
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
                    if (controller.packageName == packageName) {
                        when (customCommand.customAction) {
                            CMD_SET_SKIP_SILENCE -> {
                                val enabled = args.getBoolean(ARG_SKIP_SILENCE, false)
                                val intensity = args.getInt(ARG_SILENCE_INTENSITY, 1).coerceIn(0, 2)
                                updateSilenceProcessors(enabled, intensity)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SET_AUDIO_NORMALIZATION -> {
                                val enabled = args.getBoolean(ARG_AUDIO_NORMALIZATION, false)
                                applyAudioNormalization(enabled)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SKIP_QUEUE_NEXT -> {
                                skipQueueEpisode(forward = true)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SKIP_QUEUE_PREV -> {
                                skipQueueEpisode(forward = false)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                        }
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun updateSilenceProcessors(enabled: Boolean, intensity: Int) {
        silenceProcessors.forEachIndexed { index, processor ->
            processor.setEnabled(enabled && index == intensity)
        }
    }

    private fun applyAudioNormalization(enabled: Boolean) {
        runCatching {
            if (!enabled || currentAudioSessionId == C.AUDIO_SESSION_ID_UNSET || currentAudioSessionId == 0) {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
                dynamicsProcessing = null
                return
            }
            if (dynamicsProcessing == null) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    false, 0,
                    true, 1,
                    false, 0,
                    true
                ).apply {
                    val mbc = DynamicsProcessing.Mbc(true, true, 1).apply {
                        setBand(
                            0,
                            DynamicsProcessing.MbcBand(
                                true,
                                20000f,
                                5f,
                                120f,
                                3.0f,
                                -20f,
                                15f,
                                -45f,
                                2.0f,
                                0f,
                                3.5f
                            )
                        )
                    }
                    setMbcAllChannelsTo(mbc)
                    val limiter = DynamicsProcessing.Limiter(
                        true,
                        true,
                        0,
                        1f,
                        60f,
                        4.0f,
                        -1.5f,
                        0f
                    )
                    setLimiterAllChannelsTo(limiter)
                }.build()
                dynamicsProcessing = DynamicsProcessing(0, currentAudioSessionId, config)
            }
            dynamicsProcessing?.enabled = true
        }
    }

    private fun handleBluetoothSkip(forward: Boolean) {
        val seekMode = (applicationContext as PurePodcastApplication)
            .preferences.settings.value.bluetoothNextPrevSeeks
        if (seekMode) {
            if (forward) player.seekForward() else player.seekBack()
        } else {
            skipQueueEpisode(forward)
        }
    }

    private fun skipQueueEpisode(forward: Boolean) {
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
        serviceScope.launch {
            val queue = withContext(Dispatchers.IO) {
                PurePodcastDatabase.getInstance(applicationContext).dao().getQueueEpisodesOnce()
            }
            if (queue.isEmpty()) return@launch
            val currentIdx = queue.indexOfFirst { it.episode.id == currentId }
            val nextIdx = if (currentIdx < 0) {
                if (forward) 0 else queue.lastIndex
            } else if (forward) {
                (currentIdx + 1) % queue.size
            } else {
                (currentIdx - 1 + queue.size) % queue.size
            }
            val target = queue[nextIdx]
            val ep = target.episode
            val pod = target.podcast

            val localUri = withContext(Dispatchers.IO) {
                ep.localFilePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) Uri.fromFile(f) else null
                }
            }
            val mediaUri = localUri ?: run {
                if (!NetworkSecurity.isValidHttpUrl(ep.audioUrl)) return@launch
                Uri.parse(ep.audioUrl)
            }
            val artworkUri = ep.artworkUrl.ifEmpty { pod.artworkUrl }
                .takeIf { NetworkSecurity.isValidHttpUrl(it) }
                ?.let { Uri.parse(it) }

            val metadata = MediaMetadata.Builder()
                .setTitle(ep.title)
                .setArtist(pod.title)
                .setArtworkUri(artworkUri)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(ep.id.toString())
                .setUri(mediaUri)
                .setMediaMetadata(metadata)
                .build()

            val speed = pod.playbackSpeed.coerceIn(0.75f, 3.0f)
            val rewindMs = (applicationContext as PurePodcastApplication)
                .preferences.settings.value.smartRewindSeconds * 1000L
            val startPos = if (ep.positionMs > 0L) (ep.positionMs - rewindMs).coerceAtLeast(0L) else 0L

            player.setMediaItem(mediaItem, startPos)
            player.playbackParameters = PlaybackParameters(speed)
            updateSilenceProcessors(pod.skipSilence, pod.silenceIntensity.coerceIn(0, 2))
            player.prepare()
            player.play()
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

        runCatching {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
            dynamicsProcessing = null
        }

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
        const val ARG_SILENCE_INTENSITY = "silence_intensity"
        const val CMD_SET_AUDIO_NORMALIZATION = "com.lycoslane.purepodcast.SET_AUDIO_NORMALIZATION"
        const val ARG_AUDIO_NORMALIZATION = "audio_normalization"
        const val CMD_SKIP_QUEUE_NEXT = "com.lycoslane.purepodcast.SKIP_QUEUE_NEXT"
        const val CMD_SKIP_QUEUE_PREV = "com.lycoslane.purepodcast.SKIP_QUEUE_PREV"
    }
}
