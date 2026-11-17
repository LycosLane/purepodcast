package com.lycoslane.purepodcast.core.audio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUiState(
    val currentEpisode: EpisodeEntity? = null,
    val currentPodcast: PodcastEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val skipSilence: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L
)

class PlayerController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao = PurePodcastDatabase.getInstance(context).dao()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = mc
            mc.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncTiming()
                }
            })
            startProgressTicker()
        }, ContextCompat.getMainExecutor(context))
    }

    fun playEpisode(episode: EpisodeEntity, podcast: PodcastEntity) {
        val mc = controller ?: return
        val localFile = episode.localFilePath?.let { File(it) }?.takeIf { it.exists() }
        val mediaUri = if (localFile != null) Uri.fromFile(localFile) else Uri.parse(episode.audioUrl)

        val artworkUri = (episode.artworkUrl.ifEmpty { podcast.artworkUrl }).takeIf { it.isNotEmpty() }
            ?.let { Uri.parse(it) }

        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(podcast.title)
            .setArtworkUri(artworkUri)
            .setExtras(Bundle().apply { putLong("podcastId", podcast.id) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(mediaUri)
            .setMediaMetadata(metadata)
            .build()

        mc.setMediaItem(mediaItem, episode.positionMs)
        mc.playbackParameters = PlaybackParameters(podcast.playbackSpeed)
        sendSkipSilenceCommand(mc, podcast.skipSilence)
        mc.prepare()
        mc.play()

        _state.update {
            it.copy(
                currentEpisode = episode,
                currentPodcast = podcast,
                isPlaying = true,
                positionMs = episode.positionMs,
                durationMs = episode.durationMs,
                playbackSpeed = podcast.playbackSpeed,
                skipSilence = podcast.skipSilence
            )
        }
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun seekBy(deltaMs: Long) {
        val mc = controller ?: return
        val target = (mc.currentPosition + deltaMs).coerceAtLeast(0L)
        mc.seekTo(target)
        _state.update { it.copy(positionMs = target) }
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
        controller?.let { sendSkipSilenceCommand(it, next) }
        _state.update { it.copy(skipSilence = next) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) { dao.setSkipSilence(podcastId, next) }
    }

    private fun sendSkipSilenceCommand(mc: MediaController, enabled: Boolean) {
        val cmd = androidx.media3.session.SessionCommand(
            PlaybackService.CMD_SET_SKIP_SILENCE,
            Bundle.EMPTY
        )
        val args = Bundle().apply { putBoolean(PlaybackService.ARG_SKIP_SILENCE, enabled) }
        mc.sendCustomCommand(cmd, args)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = 0L) }
            return
        }
        var remaining = minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = remaining) }
        sleepTimerJob = scope.launch {
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining = (remaining - 1_000L).coerceAtLeast(0L)
                _state.update { it.copy(sleepTimerRemainingMs = remaining) }
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
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }
}
