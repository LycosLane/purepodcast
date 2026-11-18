package com.lycoslane.purepodcast.ui.detail

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lycoslane.purepodcast.core.network.ValidatedMediaDataSource
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EpisodePreviewSheet(
    episode: EpisodeEntity,
    podcast: PodcastEntity,
    textScale: Float,
    canDeleteDownload: Boolean = true,
    onTextScaleChange: (Float) -> Unit,
    onPauseMainPlayer: () -> Unit,
    onPlayInMainPlayer: () -> Unit,
    onAddToQueueFront: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    onDownloadClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCoverLightbox by remember { mutableStateOf(false) }
    var showReaderDialog by remember { mutableStateOf(false) }

    val artworkUrl = episode.artworkUrl.ifEmpty { podcast.artworkUrl }
    val pubDateText = remember(episode.pubDateMs) { formatPubDate(episode.pubDateMs) }
    val plainNotes = remember(episode.descriptionHtml) { htmlToPlainText(episode.descriptionHtml) }
    val chapters = remember(episode.id, episode.chaptersJson) {
        com.lycoslane.purepodcast.core.network.ChaptersParser.decodeChaptersJson(episode.chaptersJson)
    }

    val localUri = remember(episode.id, episode.localFilePath) {
        episode.localFilePath?.let { path ->
            if (path.startsWith("content://")) {
                Uri.parse(path)
            } else {
                val f = File(path)
                if (f.exists()) Uri.fromFile(f) else null
            }
        }
    }
    val hasValidMedia = localUri != null || NetworkSecurity.isValidHttpUrl(episode.audioUrl)

    var isPreviewPlaying by remember(episode.id) { mutableStateOf(false) }
    var isPreviewPrepared by remember(episode.id) { mutableStateOf(false) }
    var previewPosMs by remember(episode.id) { mutableLongStateOf(episode.positionMs.coerceAtLeast(0L)) }
    var previewDurMs by remember(episode.id) { mutableLongStateOf(episode.durationMs.coerceAtLeast(0L)) }
    var scrubPos by remember(episode.id) { mutableStateOf<Float?>(null) }

    val previewPlayer = remember(episode.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ValidatedMediaDataSource.factory(context)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
    }

    DisposableEffect(episode.id, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPreviewPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    isPreviewPlaying = false
                }
                val dur = previewPlayer.duration
                if (dur > 0L) previewDurMs = dur
            }

            override fun onPlayerError(error: PlaybackException) {
                isPreviewPlaying = false
                isPreviewPrepared = false
                previewPlayer.stop()
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && previewPlayer.isPlaying) {
                previewPlayer.pause()
            }
        }
        previewPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            previewPlayer.removeListener(listener)
            previewPlayer.release()
        }
    }

    LaunchedEffect(isPreviewPlaying) {
        while (isActive && isPreviewPlaying) {
            previewPosMs = previewPlayer.currentPosition.coerceAtLeast(0L)
            val dur = previewPlayer.duration
            if (dur > 0L) previewDurMs = dur
            delay(400L)
        }
    }

    fun dismissAnimated(after: () -> Unit = {}) {
        after()
        scope.launch {
            runCatching { sheetState.hide() }
        }.invokeOnCompletion {
            onDismiss()
        }
    }

    fun togglePreviewPlay() {
        if (!hasValidMedia) return
        if (previewPlayer.isPlaying) {
            previewPlayer.pause()
            return
        }
        val mediaUri = localUri ?: Uri.parse(episode.audioUrl)
        onPauseMainPlayer()
        if (!isPreviewPrepared) {
            previewPlayer.setMediaItem(MediaItem.fromUri(mediaUri), previewPosMs)
            previewPlayer.prepare()
            isPreviewPrepared = true
        } else if (previewPlayer.playbackState == Player.STATE_ENDED) {
            previewPosMs = 0L
            previewPlayer.seekTo(0L)
        }
        previewPlayer.play()
    }

    fun seekPreviewTo(posMs: Long) {
        val upper = if (previewDurMs > 0L) previewDurMs else Long.MAX_VALUE
        val clamped = posMs.coerceIn(0L, upper)
        previewPosMs = clamped
        if (isPreviewPrepared) {
            previewPlayer.seekTo(clamped)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PodcastImage(
                    url = artworkUrl,
                    contentDescription = stringResource(R.string.cd_podcast_cover),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showCoverLightbox = true }
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(
                        pubDateText.takeIf { it.isNotEmpty() },
                        episode.durationMs.takeIf { it > 0L }?.let { formatDuration(it) }
                    ).joinToString(" • ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (onToggleFavorite != null) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (episode.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.action_favorite),
                            tint = if (episode.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Inline Preview Player
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isPreviewPlaying) stringResource(R.string.preview_playing) else stringResource(R.string.preview_listen),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = { dismissAnimated { onPlayInMainPlayer() } },
                            enabled = hasValidMedia
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_play))
                        }
                    }

                    val canScrub = previewDurMs > 0L
                    val maxDur = previewDurMs.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = if (canScrub) (scrubPos ?: previewPosMs.toFloat()).coerceIn(0f, maxDur) else 0f,
                        onValueChange = { if (canScrub) scrubPos = it },
                        onValueChangeFinished = {
                            if (canScrub) {
                                scrubPos?.let { seekPreviewTo(it.toLong()) }
                            }
                            scrubPos = null
                        },
                        enabled = canScrub,
                        valueRange = 0f..maxDur,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(scrubPos?.toLong() ?: previewPosMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (canScrub) {
                            Text(
                                text = formatDuration(previewDurMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { seekPreviewTo(previewPosMs - 10_000L) },
                            enabled = hasValidMedia
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.cd_rewind_10))
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledIconButton(
                            onClick = { togglePreviewPlay() },
                            enabled = hasValidMedia,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (isPreviewPlaying) R.string.player_pause else R.string.player_play)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        IconButton(
                            onClick = { seekPreviewTo(previewPosMs + 30_000L) },
                            enabled = hasValidMedia && (previewDurMs == 0L || previewPosMs < previewDurMs)
                        ) {
                            Icon(Icons.Default.Forward30, contentDescription = stringResource(R.string.cd_forward_30))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Primary Queue Actions: Play Next vs Add to End
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { dismissAnimated { onAddToQueueFront() } },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.preview_queue_next), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { dismissAnimated { onAddToQueueEnd() } },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.preview_queue_end), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Secondary Actions: Download, Hide, Played
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!podcast.isLocal || canDeleteDownload) {
                    val isInProgress = episode.downloadState == DownloadState.DOWNLOADING ||
                        episode.downloadState == DownloadState.QUEUED
                    val dlLabel = when {
                        episode.markedForDeletionAtMs > 0L -> stringResource(R.string.action_restore_trash)
                        episode.downloadState == DownloadState.DOWNLOADED -> stringResource(R.string.action_delete_download)
                        episode.downloadState == DownloadState.DOWNLOADING -> "${episode.downloadProgress}%"
                        episode.downloadState == DownloadState.QUEUED -> "…"
                        else -> stringResource(R.string.cd_download)
                    }
                    val dlIcon = when {
                        episode.markedForDeletionAtMs > 0L -> Icons.Default.RestoreFromTrash
                        episode.downloadState == DownloadState.DOWNLOADED -> Icons.Default.Delete
                        else -> Icons.Default.Download
                    }
                    FilterChip(
                        selected = episode.downloadState == DownloadState.DOWNLOADED && episode.markedForDeletionAtMs == 0L,
                        enabled = !isInProgress,
                        onClick = onDownloadClick,
                        label = { Text(dlLabel) },
                        leadingIcon = {
                            if (isInProgress) {
                                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                Icon(dlIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }

                FilterChip(
                    selected = episode.isHidden,
                    onClick = { dismissAnimated { onToggleHidden() } },
                    label = {
                        Text(
                            if (episode.isHidden) stringResource(R.string.swipe_unhide)
                            else stringResource(R.string.swipe_hide)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (episode.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )

                FilterChip(
                    selected = episode.isPlayed,
                    onClick = onTogglePlayed,
                    label = {
                        Text(
                            if (episode.isPlayed) stringResource(R.string.action_mark_unplayed)
                            else stringResource(R.string.action_mark_played)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (episode.isPlayed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            if (chapters.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.player_chapters_count, chapters.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                chapters.forEach { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = hasValidMedia) {
                                seekPreviewTo(ch.startMs)
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(ch.startMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            text = ch.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            if (plainNotes.isNotBlank()) {
                Text(
                    text = stringResource(R.string.hint_tap_to_read),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                PinchZoomText(
                    text = plainNotes,
                    textScale = textScale,
                    onTextScaleChange = onTextScaleChange,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    onClick = { showReaderDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(R.string.no_show_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCoverLightbox) {
        CoverLightboxDialog(
            imageUrl = artworkUrl,
            title = episode.title,
            onDismiss = { showCoverLightbox = false }
        )
    }

    if (showReaderDialog && plainNotes.isNotBlank()) {
        TextReaderDialog(
            title = episode.title,
            subtitle = podcast.title,
            text = plainNotes,
            textScale = textScale,
            onTextScaleChange = onTextScaleChange,
            onDismiss = { showReaderDialog = false }
        )
    }
}
