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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lycoslane.purepodcast.core.network.ValidatedBitmapLoader
import com.lycoslane.purepodcast.core.network.ValidatedMediaDataSource
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var positionSaveJob: Job? = null
    private var skipQueueJob: Job? = null
    private var lastPausedElapsedMs: Long = 0L

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var attachedAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var currentEffectiveNormalization: Boolean = false

    @Volatile
    private var currentEffectiveSmartRewindSeconds: Int = 10

    private var bitmapLoader: ValidatedBitmapLoader? = null

    @OptIn(UnstableApi::class)
    private lateinit var monoProcessor: MonoMixAudioProcessor

    @OptIn(UnstableApi::class)
    private lateinit var silenceProcessor: DelegatingSilenceAudioProcessor

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        monoProcessor = MonoMixAudioProcessor()
        silenceProcessor = DelegatingSilenceAudioProcessor()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(monoProcessor, silenceProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ValidatedMediaDataSource.factory(this)))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        val app = applicationContext as PurePodcastApplication
        currentEffectiveSmartRewindSeconds = app.preferences.settings.value.smartRewindSeconds
        serviceScope.launch {
            app.preferences.settings
                .map { s ->
                    listOf(
                        s.defaultPlaybackSpeed,
                        s.defaultSkipSilence,
                        s.defaultSilenceIntensity,
                        s.audioNormalizationEnabled,
                        s.monoMode,
                        s.smartRewindSeconds
                    )
                }
                .distinctUntilChanged()
                .collect {
                    resolveAndApplyCurrentAudioSettings()
                }
        }

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

            private fun isLocalItem(mediaItem: MediaItem?): Boolean {
                val scheme = mediaItem?.localConfiguration?.uri?.scheme
                return scheme == "file" || scheme == "content"
            }

            override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
                lastPausedElapsedMs = 0L
                player.setWakeMode(if (isLocalItem(mediaItem)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
                super.setMediaItem(mediaItem, startPositionMs)
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
                lastPausedElapsedMs = 0L
                val first = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                player.setWakeMode(if (isLocalItem(first)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
                super.setMediaItems(mediaItems, startIndex, startPositionMs)
            }

            override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
                lastPausedElapsedMs = 0L
                player.setWakeMode(if (isLocalItem(mediaItems.firstOrNull())) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
                super.setMediaItems(mediaItems, resetPosition)
            }

            override fun stop() {
                lastPausedElapsedMs = 0L
                super.stop()
            }

            override fun play() {
                if (!player.playWhenReady && player.playbackState != STATE_ENDED && lastPausedElapsedMs > 0L) {
                    val pausedDuration = SystemClock.elapsedRealtime() - lastPausedElapsedMs
                    lastPausedElapsedMs = 0L
                    val rewindSecs = currentEffectiveSmartRewindSeconds
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
                applyAudioNormalization(currentEffectiveNormalization)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    lastPausedElapsedMs = 0L
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
                    lastEpisodePrefs().edit().remove(KEY_LAST_EPISODE_ID).apply()
                    serviceScope.launch(Dispatchers.IO) {
                        val dao = PurePodcastDatabase.getInstance(applicationContext).dao()
                        val now = System.currentTimeMillis()
                        dao.setPlayed(episodeId, true, now)
                        dao.updatePosition(episodeId, 0L)
                        dao.removeFromQueue(episodeId)
                        val autoRemoveDays = app.preferences.settings.value.autoRemovePlayedDays
                        if (autoRemoveDays == 0) {
                            val ep = dao.getEpisode(episodeId)
                            if (ep != null && ep.downloadState == DownloadState.DOWNLOADED && ep.localFilePath != null) {
                                dao.markForDeletion(episodeId, now)
                            }
                        }
                        // Auto-advance: the finished episode is no longer in the queue,
                        // so "forward" picks the head of the remaining queue.
                        if (dao.getQueueEpisodesOnce().isNotEmpty()) {
                            withContext(Dispatchers.Main) { skipQueueEpisode(forward = true) }
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                lastEpisodePrefs().edit().putLong(KEY_LAST_EPISODE_ID, id).apply()
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val loader = ValidatedBitmapLoader(applicationContext)
        bitmapLoader = loader

        mediaSession = MediaSession.Builder(this, smartRewindPlayer)
            .setBitmapLoader(loader)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val isTrusted = controller.packageName == packageName ||
                        session.isMediaNotificationController(controller) ||
                        session.isAutoCompanionController(controller) ||
                        controller.packageName in TRUSTED_MEDIA_CONTROLLERS

                    return if (isTrusted) {
                        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .add(SessionCommand(CMD_SET_SKIP_SILENCE, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_MONO_MODE, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_AUDIO_NORMALIZATION, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_SMART_REWIND, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SKIP_QUEUE_NEXT, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SKIP_QUEUE_PREV, Bundle.EMPTY))
                            .build()
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(sessionCommands)
                            .build()
                    } else {
                        MediaSession.ConnectionResult.reject()
                    }
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = com.google.common.util.concurrent.SettableFuture
                        .create<MediaSession.MediaItemsWithStartPosition>()
                    serviceScope.launch {
                        try {
                            val id = lastEpisodePrefs().getLong(KEY_LAST_EPISODE_ID, -1L)
                            val withPodcast = if (id < 0) null else withContext(Dispatchers.IO) {
                                PurePodcastDatabase.getInstance(applicationContext).dao().getEpisodeWithPodcast(id)
                            }
                            if (withPodcast == null) {
                                future.setException(UnsupportedOperationException("Nothing to resume"))
                                return@launch
                            }
                            val item = buildMediaItem(withPodcast.episode, withPodcast.podcast)
                            if (item == null) {
                                future.setException(UnsupportedOperationException("Nothing to resume"))
                            } else {
                                val globalSettings = app.preferences.settings.value
                                val effectiveSpeed = SpeedOverride.resolve(
                                    globalSpeed = globalSettings.defaultPlaybackSpeed,
                                    podcastOverride = withPodcast.podcast.speedOverride
                                )
                                val (effectiveSkipSilence, effectiveIntensity) = SilenceOverride.resolve(
                                    globalEnabled = globalSettings.defaultSkipSilence,
                                    globalIntensity = globalSettings.defaultSilenceIntensity,
                                    podcastOverride = withPodcast.podcast.silenceModeOverride
                                )
                                val effectiveMono = AudioMonoMode.resolveEffective(
                                    globalMonoMode = globalSettings.monoMode,
                                    podcastMonoOverride = withPodcast.podcast.monoModeOverride,
                                    episodeMonoOverride = withPodcast.episode.monoModeOverride
                                )
                                val effectiveNorm = AudioOverride.resolve(
                                    globalEnabled = globalSettings.audioNormalizationEnabled,
                                    podcastOverride = withPodcast.podcast.audioNormalizationOverride
                                )
                                val effectiveSmartRewind = SmartRewindOverride.resolve(
                                    globalSeconds = globalSettings.smartRewindSeconds,
                                    podcastOverride = withPodcast.podcast.smartRewindOverride
                                )
                                currentEffectiveSmartRewindSeconds = effectiveSmartRewind
                                withContext(Dispatchers.Main) {
                                    player.playbackParameters = PlaybackParameters(effectiveSpeed)
                                    updateSilenceProcessors(effectiveSkipSilence, effectiveIntensity)
                                    monoProcessor.setMode(effectiveMono)
                                    currentEffectiveNormalization = effectiveNorm
                                    applyAudioNormalization(effectiveNorm)
                                    val isLocal = item.localConfiguration?.uri?.scheme in setOf("file", "content")
                                    player.setWakeMode(if (isLocal) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
                                }
                                future.set(
                                    MediaSession.MediaItemsWithStartPosition(
                                        listOf(item), 0, withPodcast.episode.positionMs.coerceAtLeast(0L)
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            future.setException(e)
                        }
                    }
                    return future
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
                            CMD_SET_MONO_MODE -> {
                                val mode = AudioMonoMode.normalizeConcrete(args.getInt(ARG_MONO_MODE, AudioMonoMode.OFF))
                                monoProcessor.setMode(mode)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SET_AUDIO_NORMALIZATION -> {
                                val enabled = args.getBoolean(ARG_AUDIO_NORMALIZATION, false)
                                currentEffectiveNormalization = enabled
                                applyAudioNormalization(enabled)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SET_SMART_REWIND -> {
                                currentEffectiveSmartRewindSeconds =
                                    args.getInt(ARG_SMART_REWIND_SECONDS, 10).coerceIn(0, 120)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SKIP_QUEUE_NEXT -> {
                                val fallbackId = args.getLong(ARG_CURRENT_EPISODE_ID, -1L).takeIf { it > 0L }
                                skipQueueEpisode(forward = true, fallbackEpisodeId = fallbackId)
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                            }
                            CMD_SKIP_QUEUE_PREV -> {
                                val fallbackId = args.getLong(ARG_CURRENT_EPISODE_ID, -1L).takeIf { it > 0L }
                                skipQueueEpisode(forward = false, fallbackEpisodeId = fallbackId)
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
        silenceProcessor.setMode(enabled, intensity)
    }

    private fun resolveAndApplyCurrentAudioSettings() {
        val app = applicationContext as PurePodcastApplication
        val global = app.preferences.settings.value
        val epId = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (epId == null) {
            player.playbackParameters = PlaybackParameters(global.defaultPlaybackSpeed.coerceIn(0.50f, 3.00f))
            updateSilenceProcessors(global.defaultSkipSilence, global.defaultSilenceIntensity)
            monoProcessor.setMode(global.monoMode)
            currentEffectiveNormalization = global.audioNormalizationEnabled
            currentEffectiveSmartRewindSeconds = global.smartRewindSeconds
            applyAudioNormalization(currentEffectiveNormalization)
            return
        }
        serviceScope.launch {
            val withPodcast = withContext(Dispatchers.IO) {
                PurePodcastDatabase.getInstance(applicationContext).dao().getEpisodeWithPodcast(epId)
            }
            if (player.currentMediaItem?.mediaId?.toLongOrNull() != epId) return@launch
            val currentGlobal = app.preferences.settings.value
            val effectiveSpeed = if (withPodcast != null) {
                SpeedOverride.resolve(
                    globalSpeed = currentGlobal.defaultPlaybackSpeed,
                    podcastOverride = withPodcast.podcast.speedOverride
                )
            } else {
                currentGlobal.defaultPlaybackSpeed.coerceIn(0.50f, 3.00f)
            }
            val (effectiveSilence, effectiveIntensity) = if (withPodcast != null) {
                SilenceOverride.resolve(
                    globalEnabled = currentGlobal.defaultSkipSilence,
                    globalIntensity = currentGlobal.defaultSilenceIntensity,
                    podcastOverride = withPodcast.podcast.silenceModeOverride
                )
            } else {
                currentGlobal.defaultSkipSilence to currentGlobal.defaultSilenceIntensity.coerceIn(0, 2)
            }
            val effectiveMono = if (withPodcast != null) {
                AudioMonoMode.resolveEffective(
                    globalMonoMode = currentGlobal.monoMode,
                    podcastMonoOverride = withPodcast.podcast.monoModeOverride,
                    episodeMonoOverride = withPodcast.episode.monoModeOverride
                )
            } else {
                AudioMonoMode.normalizeConcrete(currentGlobal.monoMode)
            }
            val effectiveNorm = if (withPodcast != null) {
                AudioOverride.resolve(
                    globalEnabled = currentGlobal.audioNormalizationEnabled,
                    podcastOverride = withPodcast.podcast.audioNormalizationOverride
                )
            } else {
                currentGlobal.audioNormalizationEnabled
            }
            val effectiveRewind = if (withPodcast != null) {
                SmartRewindOverride.resolve(
                    globalSeconds = currentGlobal.smartRewindSeconds,
                    podcastOverride = withPodcast.podcast.smartRewindOverride
                )
            } else {
                currentGlobal.smartRewindSeconds
            }
            player.playbackParameters = PlaybackParameters(effectiveSpeed)
            updateSilenceProcessors(effectiveSilence, effectiveIntensity)
            monoProcessor.setMode(effectiveMono)
            currentEffectiveNormalization = effectiveNorm
            currentEffectiveSmartRewindSeconds = effectiveRewind
            applyAudioNormalization(effectiveNorm)
        }
    }

    private fun releaseDynamicsProcessing() {
        val dp = dynamicsProcessing ?: return
        try {
            runCatching { dp.enabled = false }
        } finally {
            runCatching { dp.release() }
            dynamicsProcessing = null
            attachedAudioSessionId = C.AUDIO_SESSION_ID_UNSET
        }
    }

    /**
     * Configures a single-band multiband compressor + brickwall limiter via Android's [DynamicsProcessing]
     * to lift quiet speech (below -20 dBFS, 3.5 dB makeup gain) while preventing clipping above -1.5 dBFS.
     */
    private fun applyAudioNormalization(enabled: Boolean) {
        runCatching {
            if (!enabled || currentAudioSessionId == C.AUDIO_SESSION_ID_UNSET || currentAudioSessionId == 0) {
                releaseDynamicsProcessing()
                return
            }
            if (dynamicsProcessing != null && attachedAudioSessionId != currentAudioSessionId) {
                releaseDynamicsProcessing()
            }
            if (dynamicsProcessing == null) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
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
                attachedAudioSessionId = currentAudioSessionId
            }
            dynamicsProcessing?.enabled = true
        }.onFailure {
            releaseDynamicsProcessing()
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

    /** Builds the MediaItem for an episode (local file preferred, validated remote URL otherwise). */
    private suspend fun buildMediaItem(
        ep: com.lycoslane.purepodcast.core.database.EpisodeEntity,
        pod: com.lycoslane.purepodcast.core.database.PodcastEntity
    ): MediaItem? {
        val localUri = withContext(Dispatchers.IO) {
            ep.localFilePath?.let { path ->
                if (path.startsWith("content://")) {
                    Uri.parse(path)
                } else {
                    val f = File(path)
                    if (f.exists()) Uri.fromFile(f) else null
                }
            }
        }
        val mediaUri = localUri ?: run {
            if (!NetworkSecurity.isValidHttpUrl(ep.audioUrl)) return null
            Uri.parse(ep.audioUrl)
        }
        val rawArtwork = ep.artworkUrl.ifEmpty { pod.artworkUrl }
        val artworkUri = when {
            rawArtwork.isBlank() -> null
            rawArtwork.startsWith("content://") || rawArtwork.startsWith("file://") -> Uri.parse(rawArtwork)
            rawArtwork.startsWith("/") -> Uri.fromFile(File(rawArtwork))
            NetworkSecurity.isValidHttpUrl(rawArtwork) -> Uri.parse(rawArtwork)
            else -> null
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(ep.title)
            .setArtist(pod.title)
            .setArtworkUri(artworkUri)
            .build()
        return MediaItem.Builder()
            .setMediaId(ep.id.toString())
            .setUri(mediaUri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun skipQueueEpisode(forward: Boolean, fallbackEpisodeId: Long? = null) {
        saveCurrentPosition()
        val currentId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: fallbackEpisodeId
        skipQueueJob?.cancel()
        skipQueueJob = serviceScope.launch {
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
            val mediaItem = buildMediaItem(ep, pod) ?: return@launch
            val mediaUri = mediaItem.localConfiguration?.uri ?: return@launch

            val globalSettings = (applicationContext as PurePodcastApplication).preferences.settings.value
            val speed = SpeedOverride.resolve(
                globalSpeed = globalSettings.defaultPlaybackSpeed,
                podcastOverride = pod.speedOverride
            )
            val (effectiveSkipSilence, effectiveIntensity) = SilenceOverride.resolve(
                globalEnabled = globalSettings.defaultSkipSilence,
                globalIntensity = globalSettings.defaultSilenceIntensity,
                podcastOverride = pod.silenceModeOverride
            )
            val effectiveRewind = SmartRewindOverride.resolve(
                globalSeconds = globalSettings.smartRewindSeconds,
                podcastOverride = pod.smartRewindOverride
            )
            currentEffectiveSmartRewindSeconds = effectiveRewind
            val rewindMs = effectiveRewind * 1000L
            val startPos = if (ep.positionMs > 0L) (ep.positionMs - rewindMs).coerceAtLeast(0L) else 0L
            val effectiveMono = AudioMonoMode.resolveEffective(
                globalMonoMode = globalSettings.monoMode,
                podcastMonoOverride = pod.monoModeOverride,
                episodeMonoOverride = ep.monoModeOverride
            )
            val effectiveNorm = AudioOverride.resolve(
                globalEnabled = globalSettings.audioNormalizationEnabled,
                podcastOverride = pod.audioNormalizationOverride
            )

            lastPausedElapsedMs = 0L
            player.setWakeMode(if (mediaUri.scheme == "file" || mediaUri.scheme == "content") C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)
            player.setMediaItem(mediaItem, startPos)
            player.playbackParameters = PlaybackParameters(speed)
            updateSilenceProcessors(effectiveSkipSilence, effectiveIntensity)
            monoProcessor.setMode(effectiveMono)
            currentEffectiveNormalization = effectiveNorm
            applyAudioNormalization(effectiveNorm)
            player.prepare()
            player.play()
        }
    }

    private fun lastEpisodePrefs() =
        getSharedPreferences("playback_state", MODE_PRIVATE)

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
        skipQueueJob?.cancel()
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

        bitmapLoader?.close()
        bitmapLoader = null
        releaseDynamicsProcessing()

        if (episodeId != null) {
            // Bounded synchronous write so the position survives immediate process death.
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                        PurePodcastDatabase.getInstance(applicationContext).dao()
                            .updatePosition(episodeId, pos)
                    }
                }
            }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val TRUSTED_MEDIA_CONTROLLERS = setOf(
            "com.android.systemui",
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.google.android.projection.gearhead",
            "com.google.android.wearable.app"
        )
        private const val KEY_LAST_EPISODE_ID = "last_episode_id"
        const val CMD_SET_SKIP_SILENCE = "com.lycoslane.purepodcast.SET_SKIP_SILENCE"
        const val ARG_SKIP_SILENCE = "skip_silence"
        const val ARG_SILENCE_INTENSITY = "silence_intensity"
        const val CMD_SET_MONO_MODE = "com.lycoslane.purepodcast.SET_MONO_MODE"
        const val ARG_MONO_MODE = "mono_mode"
        const val CMD_SET_AUDIO_NORMALIZATION = "com.lycoslane.purepodcast.SET_AUDIO_NORMALIZATION"
        const val ARG_AUDIO_NORMALIZATION = "audio_normalization"
        const val CMD_SET_SMART_REWIND = "com.lycoslane.purepodcast.SET_SMART_REWIND"
        const val ARG_SMART_REWIND_SECONDS = "smart_rewind_seconds"
        const val CMD_SKIP_QUEUE_NEXT = "com.lycoslane.purepodcast.SKIP_QUEUE_NEXT"
        const val CMD_SKIP_QUEUE_PREV = "com.lycoslane.purepodcast.SKIP_QUEUE_PREV"
        const val ARG_CURRENT_EPISODE_ID = "current_episode_id"
    }
}
