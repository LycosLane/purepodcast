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
    val sleepTimerRemainingMs: Long = 0L,
    val sleepTimerPresetMinutes: Int = 0
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
                _state.update { it.copy(isPlaying = false) }
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

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerController.controller === controller) {
                this@PlayerController.controller = null
                controllerFuture = null
                progressJob?.cancel()
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun connect() {
        val current = controller
        if (current != null) {
            if (current.isConnected) return
            controller = null
        }
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull()
            if (mc == null) {
                controllerFuture = null
                return@addListener
            }
            controller = mc
            controllerFuture = null
            mc.addListener(playerListener)
            syncTiming()
            val currentItemId = mc.currentMediaItem?.mediaId?.toLongOrNull()
            if (currentItemId != null) {
                syncFromEpisodeId(currentItemId)
            } else {
                restoreLastEpisodeIfIdle()
            }
            if (mc.isPlaying) {
                startProgressTicker()
            }
            pendingPlay?.let { (ep, pod) ->
                pendingPlay = null
                playEpisode(ep, pod)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun restoreLastEpisodeIfIdle() {
        scope.launch(Dispatchers.IO) {
            if (_state.value.currentEpisode != null) return@launch
            val lastId = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
                .getLong("last_episode_id", -1L)
            val candidate = (if (lastId > 0L) dao.getEpisodeWithPodcast(lastId) else null)
                ?.takeIf { !it.episode.isPlayed }
                ?: dao.getQueueEpisodesOnce().firstOrNull { it.episode.positionMs > 0L && !it.episode.isPlayed }
                ?: dao.getMostRecentInProgressEpisode()
                ?: return@launch

            _state.update { st ->
                if (st.currentEpisode != null) {
                    st
                } else {
                    st.copy(
                        currentEpisode = candidate.episode,
                        currentPodcast = candidate.podcast,
                        positionMs = candidate.episode.positionMs.coerceAtLeast(0L),
                        durationMs = candidate.episode.durationMs.coerceAtLeast(0L),
                        playbackSpeed = candidate.podcast.playbackSpeed.coerceIn(0.50f, 3.0f),
                        skipSilence = candidate.podcast.skipSilence,
                        silenceIntensity = candidate.podcast.silenceIntensity.coerceIn(0, 2),
                        isPlaying = false
                    )
                }
            }
            val marker = candidate.episode.chaptersJson
            if (marker.startsWith("url:")) {
                val json = com.lycoslane.purepodcast.core.network.ChaptersParser
                    .fetchPodcasting2ChaptersJson(marker.removePrefix("url:"))
                dao.updateChaptersJson(candidate.episode.id, json)
                _state.update { st ->
                    val cur = st.currentEpisode
                    if (cur != null && cur.id == candidate.episode.id) {
                        st.copy(currentEpisode = cur.copy(chaptersJson = json))
                    } else {
                        st
                    }
                }
            }
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var hasExplicitIdleSeek: Boolean = false

    private fun syncFromEpisodeId(episodeId: Long) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.IO) {
            val withPodcast = dao.getEpisodeWithPodcast(episodeId) ?: return@launch
            val liveDuration = withContext(Dispatchers.Main) {
                controller?.duration?.takeIf { it > 0L }
            }
            val livePos = withContext(Dispatchers.Main) {
                controller?.currentPosition?.takeIf { it > 0L }
            }
            _state.update {
                it.copy(
                    currentEpisode = withPodcast.episode,
                    currentPodcast = withPodcast.podcast,
                    positionMs = livePos ?: withPodcast.episode.positionMs,
                    durationMs = liveDuration ?: withPodcast.episode.durationMs,
                    playbackSpeed = withPodcast.podcast.playbackSpeed.coerceIn(0.50f, 3.0f),
                    skipSilence = withPodcast.podcast.skipSilence,
                    silenceIntensity = withPodcast.podcast.silenceIntensity.coerceIn(0, 2)
                )
            }
            // Lazily resolve an external chapters file (stored as "url:<link>" by the feed import).
            val marker = withPodcast.episode.chaptersJson
            if (marker.startsWith("url:")) {
                val json = com.lycoslane.purepodcast.core.network.ChaptersParser
                    .fetchPodcasting2ChaptersJson(marker.removePrefix("url:"))
                dao.updateChaptersJson(episodeId, json)
                _state.update { st ->
                    val cur = st.currentEpisode
                    if (cur != null && cur.id == episodeId) st.copy(currentEpisode = cur.copy(chaptersJson = json)) else st
                }
            }
        }
    }

    fun playEpisode(episode: EpisodeEntity, podcast: PodcastEntity) {
        context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_episode_id", episode.id)
            .apply()
        val mc = controller?.takeIf { it.isConnected }
        if (mc == null) {
            controller = null
            pendingPlay = episode to podcast
            connect()
            return
        }

        // If the user already has this exact episode loaded in the MediaController and paused,
        // simply resume playback rather than re-preparing and re-applying smart rewind.
        if (mc.currentMediaItem?.mediaId == episode.id.toString() && mc.playbackState != Player.STATE_ENDED) {
            if (!mc.isPlaying) {
                mc.play()
            }
            return
        }

        val useExplicitIdlePos = hasExplicitIdleSeek && _state.value.currentEpisode?.id == episode.id
        val explicitPosMs = _state.value.positionMs
        hasExplicitIdleSeek = false

        playJob?.cancel()
        playJob = scope.launch {
            val freshEpisode = withContext(Dispatchers.IO) {
                dao.getEpisode(episode.id) ?: episode
            }
            val freshPodcast = withContext(Dispatchers.IO) {
                dao.getPodcast(podcast.id) ?: podcast
            }
            val localUri = withContext(Dispatchers.IO) {
                freshEpisode.localFilePath?.let { path ->
                    if (path.startsWith("content://")) {
                        Uri.parse(path)
                    } else {
                        val f = File(path)
                        if (f.exists()) Uri.fromFile(f) else null
                    }
                }
            }
            val mediaUri = localUri ?: run {
                if (!NetworkSecurity.isValidHttpUrl(freshEpisode.audioUrl)) return@launch
                Uri.parse(freshEpisode.audioUrl)
            }

            val rawArtwork = freshEpisode.artworkUrl.ifEmpty { freshPodcast.artworkUrl }
            val artworkUri = when {
                rawArtwork.isBlank() -> null
                rawArtwork.startsWith("content://") || rawArtwork.startsWith("file://") -> Uri.parse(rawArtwork)
                rawArtwork.startsWith("/") -> Uri.fromFile(File(rawArtwork))
                NetworkSecurity.isValidHttpUrl(rawArtwork) -> Uri.parse(rawArtwork)
                else -> null
            }

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

            val speed = freshPodcast.playbackSpeed.coerceIn(0.50f, 3.0f)
            val intensity = freshPodcast.silenceIntensity.coerceIn(0, 2)
            val rewindMs = preferences.settings.value.smartRewindSeconds * 1000L
            val startPos = when {
                useExplicitIdlePos -> explicitPosMs.coerceAtLeast(0L)
                freshEpisode.positionMs > 0L -> (freshEpisode.positionMs - rewindMs).coerceAtLeast(0L)
                else -> 0L
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
                    positionMs = startPos
                )
            }
        }
    }

    fun togglePlayPause() {
        val mc = controller
        if (mc == null || !mc.isConnected || mc.currentMediaItem == null) {
            val ep = _state.value.currentEpisode
            val pod = _state.value.currentPodcast
            if (ep != null && pod != null) {
                playEpisode(ep, pod)
            }
            return
        }
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
        val dur = _state.value.durationMs
        val clamped = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        val mc = controller
        if (mc != null && mc.currentMediaItem != null) {
            mc.seekTo(clamped)
        } else {
            hasExplicitIdleSeek = true
        }
        _state.update {
            it.copy(
                positionMs = clamped,
                currentEpisode = it.currentEpisode?.copy(positionMs = clamped)
            )
        }
        val epId = _state.value.currentEpisode?.id ?: return
        scope.launch(Dispatchers.IO) { dao.updatePosition(epId, clamped) }
    }

    fun seekBy(deltaMs: Long) {
        val mc = controller
        val basePos = if (mc != null && mc.currentMediaItem != null) {
            mc.currentPosition
        } else {
            _state.value.positionMs
        }
        seekTo(basePos + deltaMs)
    }

    fun skipToNextInQueue() {
        val mc = controller ?: return
        val args = Bundle().apply {
            _state.value.currentEpisode?.id?.let { putLong(PlaybackService.ARG_CURRENT_EPISODE_ID, it) }
        }
        mc.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP_QUEUE_NEXT, Bundle.EMPTY), args)
    }

    fun skipToPreviousInQueue() {
        val mc = controller ?: return
        val args = Bundle().apply {
            _state.value.currentEpisode?.id?.let { putLong(PlaybackService.ARG_CURRENT_EPISODE_ID, it) }
        }
        mc.sendCustomCommand(SessionCommand(PlaybackService.CMD_SKIP_QUEUE_PREV, Bundle.EMPTY), args)
    }

    fun stopAndClear() {
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        hasExplicitIdleSeek = false
        context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
            .edit()
            .remove("last_episode_id")
            .apply()
        controller?.let { mc ->
            mc.stop()
            mc.clearMediaItems()
        }
        _state.value = PlayerUiState()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.50f, 3.0f)
        controller?.playbackParameters = PlaybackParameters(clamped)
        _state.update { it.copy(playbackSpeed = clamped) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) { dao.setPlaybackSpeed(podcastId, clamped) }
    }

    fun setSilenceMode(enabled: Boolean, intensity: Int) {
        val clamped = intensity.coerceIn(0, 2)
        controller?.let { sendSkipSilenceCommand(it, enabled, clamped) }
        _state.update { it.copy(skipSilence = enabled, silenceIntensity = clamped) }
        val podcastId = _state.value.currentPodcast?.id ?: return
        scope.launch(Dispatchers.IO) {
            dao.setSkipSilence(podcastId, enabled)
            dao.setSilenceIntensity(podcastId, clamped)
        }
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
            _state.update { it.copy(sleepTimerRemainingMs = 0L, sleepTimerPresetMinutes = 0) }
            return
        }
        val endElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = minutes * 60_000L, sleepTimerPresetMinutes = minutes) }
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _state.update {
                    it.copy(
                        sleepTimerRemainingMs = remaining,
                        sleepTimerPresetMinutes = if (remaining == 0L) 0 else minutes
                    )
                }
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
