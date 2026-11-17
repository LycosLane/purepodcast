package com.lycoslane.purepodcast.core.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayerUiState(
    val currentEpisode: EpisodeEntity? = null,
    val currentPodcast: PodcastEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val skipSilence: Boolean = false,
    val silenceIntensity: Int = 1,
    val sleepTimerRemainingMs: Long = 0L
)

class PlayerController(
    private val context: Context,
    private val preferences: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao = PurePodcastDatabase.getInstance(context).dao()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var pendingPlay: Pair<EpisodeEntity, PodcastEntity>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressTicker()
            } else {
                progressJob?.cancel()
                syncTiming()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                progressJob?.cancel()
                sleepTimerJob?.cancel()
                _state.value = PlayerUiState()
                return
            }
            syncTiming()
            if (playbackState == Player.STATE_IDLE) {
                progressJob?.cancel()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val epId = mediaItem?.mediaId?.toLongOrNull() ?: return
            syncFromEpisodeId(epId)
        }
    }

    fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull()
            if (mc == null) {
                controllerFuture = null
                return@addListener
            }
            controller = mc
            mc.addListener(playerListener)
            syncTiming()
            mc.currentMediaItem?.mediaId?.toLongOrNull()?.let { syncFromEpisodeId(it) }
            if (mc.isPlaying) {
                startProgressTicker()
            }
            pendingPlay?.let { (ep, pod) ->
                pendingPlay = null
                playEpisode(ep, pod)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun syncFromEpisodeId(episodeId: Long) {
        scope.launch(Dispatchers.IO) {
            val withPodcast = dao.getEpisodeWithPodcast(episodeId) ?: return@launch
            _state.update {
                it.copy(
                    currentEpisode = withPodcast.episode,
                    currentPodcast = withPodcast.podcast,
                    durationMs = withPodcast.episode.durationMs,
                    playbackSpeed = withPodcast.podcast.playbackSpeed.coerceIn(0.75f, 3.0f),
                    skipSilence = withPodcast.podcast.skipSilence,
                    silenceIntensity = withPodcast.podcast.silenceIntensity.coerceIn(0, 2)
                )
            }
        }
    }

    fun playEpisode(episode: EpisodeEntity, podcast: PodcastEntity) {
        val mc = controller
        if (mc == null) {
            pendingPlay = episode to podcast
            connect()
            return
        }

        scope.launch {
            val freshEpisode = withContext(Dispatchers.IO) {
                dao.getEpisode(episode.id) ?: episode
            }
            val freshPodcast = withContext(Dispatchers.IO) {
                dao.getPodcast(podcast.id) ?: podcast
            }
            val localUri = withContext(Dispatchers.IO) {
                freshEpisode.localFilePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) Uri.fromFile(f) else null
                }
            }
            val mediaUri = localUri ?: run {
                if (!NetworkSecurity.isValidHttpUrl(freshEpisode.audioUrl)) return@launch
                Uri.parse(freshEpisode.audioUrl)
            }

            val artworkUri = freshEpisode.artworkUrl.ifEmpty { freshPodcast.artworkUrl }
                .takeIf { NetworkSecurity.isValidHttpUrl(it) }
                ?.let { Uri.parse(it) }

            val metadata = MediaMetadata.Builder()
                .setTitle(freshEpisode.title)
                .setArtist(freshPodcast.title)
                .setArtworkUri(artworkUri)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(freshEpisode.id.toString())
                .setUri(mediaUri)
                .setMediaMetadata(metadata)
                .build()

            val speed = freshPodcast.playbackSpeed.coerceIn(0.75f, 3.0f)
            val intensity = freshPodcast.silenceIntensity.coerceIn(0, 2)
            val rewindMs = preferences.settings.value.smartRewindSeconds * 1000L
            val startPos = if (freshEpisode.positionMs > 0L) {
                (freshEpisode.positionMs - rewindMs).coerceAtLeast(0L)
            } else {
                0L
            }

            mc.setMediaItem(mediaItem, startPos)
            mc.playbackParameters = PlaybackParameters(speed)
            sendSkipSilenceCommand(mc, freshPodcast.skipSilence, intensity)
            mc.prepare()
            mc.play()

            _state.update {
                it.copy(
                    currentEpisode = freshEpisode,
                    currentPodcast = freshPodcast,
                    playbackSpeed = speed,
                    skipSilence = freshPodcast.skipSilence,
                    silenceIntensity = intensity,
                    durationMs = freshEpisode.durationMs,
                    positionMs = startPos,
                    isPlaying = true
                )
            }
        }
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.playWhenReady && mc.playbackState != Player.STATE_ENDED) {
            mc.pause()
        } else {
            mc.play()
        }
    }

    fun pauseIfPlaying() {
        val mc = controller ?: return
        if (mc.playWhenReady && mc.playbackState != Player.STATE_ENDED) {
            mc.pause()
        }
    }

    fun seekTo(positionMs: Long) {
        val mc = controller ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        mc.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) {
        val mc = controller ?: return
        val target = (mc.currentPosition + deltaMs).coerceAtLeast(0L)
        mc.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun skipToNextInQueue() {
        val mc = controller ?: return
        mc.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP_QUEUE_NEXT, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun skipToPreviousInQueue() {
        val mc = controller ?: return
        mc.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP_QUEUE_PREV, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun stopAndClear() {
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        controller?.let { mc ->
            mc.stop()
            mc.clearMediaItems()
        }
        _state.value = PlayerUiState()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.75f, 3.0f)
        controller?.playbackParameters = PlaybackParameters(clamped)
        _state.update { it.copy(playbackSpeed = clamped) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) { dao.setPlaybackSpeed(podcastId, clamped) }
    }

    fun toggleSkipSilence() {
        val next = !_state.value.skipSilence
        val intensity = _state.value.silenceIntensity
        controller?.let { sendSkipSilenceCommand(it, next, intensity) }
        _state.update { it.copy(skipSilence = next) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) { dao.setSkipSilence(podcastId, next) }
    }

    fun setSilenceIntensity(intensity: Int) {
        val clamped = intensity.coerceIn(0, 2)
        val enabled = _state.value.skipSilence
        controller?.let { sendSkipSilenceCommand(it, enabled, clamped) }
        _state.update { it.copy(silenceIntensity = clamped) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) { dao.setSilenceIntensity(podcastId, clamped) }
    }

    fun setAudioNormalization(enabled: Boolean) {
        val mc = controller ?: return
        val cmd = SessionCommand(PlaybackService.CMD_SET_AUDIO_NORMALIZATION, Bundle.EMPTY)
        val args = Bundle().apply { putBoolean(PlaybackService.ARG_AUDIO_NORMALIZATION, enabled) }
        mc.sendCustomCommand(cmd, args)
    }

    private fun sendSkipSilenceCommand(mc: MediaController, enabled: Boolean, intensity: Int) {
        val cmd = SessionCommand(PlaybackService.CMD_SET_SKIP_SILENCE, Bundle.EMPTY)
        val args = Bundle().apply {
            putBoolean(PlaybackService.ARG_SKIP_SILENCE, enabled)
            putInt(PlaybackService.ARG_SILENCE_INTENSITY, intensity.coerceIn(0, 2))
        }
        mc.sendCustomCommand(cmd, args)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = 0L) }
            return
        }
        val endElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = minutes * 60_000L) }
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                if (remaining == 0L) break
                delay(1_000L)
            }
            if (isActive) {
                controller?.pause()
            }
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                syncTiming()
                delay(500L)
            }
        }
    }

    private fun syncTiming() {
        val mc = controller ?: return
        val dur = mc.duration.takeIf { it > 0 } ?: _state.value.durationMs
        val pos = mc.currentPosition.coerceAtLeast(0L)
        _state.update {
            it.copy(
                positionMs = pos,
                durationMs = dur,
                isPlaying = mc.isPlaying
            )
        }
    }

    fun release() {
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        scope.cancel()
    }
}
