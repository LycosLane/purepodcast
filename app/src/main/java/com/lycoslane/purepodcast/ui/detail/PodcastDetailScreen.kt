package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.AudioOverride
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import kotlin.math.abs

private enum class EpisodeFilter {
    ALL,
    UNPLAYED,
    IN_PROGRESS,
    HIDDEN,
    TRASH
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PodcastDetailScreen(
    podcast: PodcastEntity?,
    episodes: List<EpisodeEntity>,
    activeEpisodeId: Long?,
    isRefreshing: Boolean,
    textScale: Float,
    trashRetentionHours: Int,
    globalMonoMode: Int,
    globalAudioNormalization: Boolean,
    canDeleteLocal: Boolean = true,
    onTextScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSort: (Boolean) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetSilenceMode: (Boolean, Int) -> Unit,
    onSetMonoModeOverride: (Int) -> Unit,
    onSetNormalizationOverride: (Int) -> Unit,
    onDisableAutoDownload: () -> Unit,
    onSubscribe: () -> Unit = {},
    onUnsubscribe: () -> Unit,
    onPlayEpisode: (EpisodeEntity) -> Unit,
    onPauseMainPlayer: () -> Unit,
    onDownloadEpisode: (EpisodeEntity) -> Unit,
    onDeleteDownload: (EpisodeEntity) -> Unit,
    onStopAndDeleteActive: (EpisodeEntity) -> Unit,
    onRestoreFromTrash: (EpisodeEntity) -> Unit,
    onDeletePermanently: (EpisodeEntity) -> Unit,
    onEmptyTrash: () -> Unit,
    onAddToQueue: (EpisodeEntity) -> Unit,
    onAddToQueueFront: (EpisodeEntity) -> Unit,
    onTogglePlayed: (EpisodeEntity) -> Unit,
    onToggleHidden: (EpisodeEntity) -> Unit,
    onToggleFavorite: (EpisodeEntity) -> Unit = {},
    onDownloadNewerFrom: (EpisodeEntity) -> Unit,
    onCleanupOlderThan: (EpisodeEntity) -> Unit
) {
    var selectedEpisodeForPreviewId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEpisodeForMenuId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEpisodeForNotesId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPodcastSettings by rememberSaveable { mutableStateOf(false) }
    var showCoverLightbox by rememberSaveable { mutableStateOf(false) }
    var showDescriptionReader by rememberSaveable { mutableStateOf(false) }
    var expandDescription by rememberSaveable { mutableStateOf(false) }
    var showEpisodeSearch by rememberSaveable { mutableStateOf(false) }
    var episodeSearchQuery by rememberSaveable { mutableStateOf("") }
    var showUnsubscribeDialog by rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashDialog by rememberSaveable { mutableStateOf(false) }
    var deleteActiveEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletePermanentlyEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var activeFilter by rememberSaveable { mutableStateOf(EpisodeFilter.ALL) }

    fun requestDeleteDownload(ep: EpisodeEntity) {
        if (ep.id == activeEpisodeId) {
            deleteActiveEpisodeId = ep.id
        } else {
            onDeleteDownload(ep)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = podcast?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (podcast != null) {
                        IconButton(onClick = {
                            showEpisodeSearch = !showEpisodeSearch
                            if (!showEpisodeSearch) episodeSearchQuery = ""
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.episode_search_hint)
                            )
                        }
                        IconButton(onClick = { onToggleSort(!podcast.sortAscending) }) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = stringResource(R.string.action_toggle_sort)
                            )
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_refresh_feed)
                            )
                        }
                        if (!podcast.isLocal) {
                            if (podcast.isSubscribed) {
                                IconButton(onClick = { showUnsubscribeDialog = true }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.action_remove_podcast)
                                    )
                                }
                            } else {
                                TextButton(onClick = onSubscribe) {
                                    Text(stringResource(R.string.action_subscribe))
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (podcast == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val plainDescription = remember(podcast.description) {
            htmlToPlainText(podcast.description)
        }
        val trashedCount = remember(episodes) {
            episodes.count { it.markedForDeletionAtMs > 0L && it.downloadState == DownloadState.DOWNLOADED }
        }
        val hiddenCount = remember(episodes) {
            episodes.count { it.isHidden }
        }
        val inProgressCount = remember(episodes) {
            episodes.count { !it.isHidden && !it.isPlayed && it.positionMs > 0L }
        }
        val filteredEpisodes = remember(episodes, activeFilter, episodeSearchQuery) {
            val base = when (activeFilter) {
                EpisodeFilter.ALL -> episodes.filter { !it.isHidden }
                EpisodeFilter.UNPLAYED -> episodes.filter { !it.isHidden && !it.isPlayed }
                EpisodeFilter.IN_PROGRESS -> episodes.filter { !it.isHidden && !it.isPlayed && it.positionMs > 0L }
                EpisodeFilter.HIDDEN -> episodes.filter { it.isHidden }
                EpisodeFilter.TRASH -> episodes.filter {
                    it.markedForDeletionAtMs > 0L && it.downloadState == DownloadState.DOWNLOADED
                }
            }
            val q = episodeSearchQuery.trim()
            if (q.isEmpty()) base else {
                base.filter { ep ->
                    ep.title.contains(q, ignoreCase = true) ||
                        ep.descriptionHtml.contains(q, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (showEpisodeSearch) {
                OutlinedTextField(
                    value = episodeSearchQuery,
                    onValueChange = { episodeSearchQuery = it },
                    placeholder = { Text(stringResource(R.string.episode_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (episodeSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { episodeSearchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_close))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            val listState = rememberLazyListState()
            var lastHiddenEpisodeId by rememberSaveable { mutableStateOf<Long?>(null) }
            var sawHiddenRemoved by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(filteredEpisodes, lastHiddenEpisodeId) {
                val targetId = lastHiddenEpisodeId ?: return@LaunchedEffect
                val idx = filteredEpisodes.indexOfFirst { it.id == targetId }
                if (idx < 0) {
                    sawHiddenRemoved = true
                } else if (sawHiddenRemoved) {
                    lastHiddenEpisodeId = null
                    sawHiddenRemoved = false
                    val listIdx = (idx + 1).coerceAtLeast(0)
                    if (listIdx <= listState.firstVisibleItemIndex) {
                        listState.animateScrollToItem(listIdx)
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item(key = "header") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PodcastImage(
                                    url = podcast.artworkUrl,
                                    contentDescription = stringResource(R.string.cd_podcast_cover),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showCoverLightbox = true }
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = podcast.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (podcast.author.isNotBlank()) {
                                        Text(
                                            text = podcast.author,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(
                                            if (podcast.sortAscending) R.string.sort_asc else R.string.sort_desc
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (plainDescription.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = plainDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expandDescription) 30 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { showDescriptionReader = true }
                                )
                                TextButton(
                                    onClick = { expandDescription = !expandDescription },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        stringResource(
                                            if (expandDescription) R.string.show_less else R.string.show_more
                                        )
                                    )
                                }
                            }

                            if (podcast.autoDownloadFromPubDateMs > 0L) {
                                AssistChip(
                                    onClick = onDisableAutoDownload,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.auto_download_active,
                                                formatPubDate(podcast.autoDownloadFromPubDateMs)
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            val hasCustomAudio = abs(podcast.playbackSpeed - 1.0f) >= 0.01f ||
                                podcast.skipSilence ||
                                podcast.monoModeOverride != AudioMonoMode.INHERIT ||
                                podcast.audioNormalizationOverride != AudioOverride.INHERIT
                            OutlinedButton(
                                onClick = { showPodcastSettings = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = if (hasCustomAudio) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.podcast_settings_title),
                                    color = if (hasCustomAudio) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = activeFilter == EpisodeFilter.ALL,
                                    onClick = { activeFilter = EpisodeFilter.ALL },
                                    label = { Text(stringResource(R.string.filter_all)) }
                                )
                                FilterChip(
                                    selected = activeFilter == EpisodeFilter.UNPLAYED,
                                    onClick = { activeFilter = EpisodeFilter.UNPLAYED },
                                    label = { Text(stringResource(R.string.filter_unplayed)) }
                                )
                                if (inProgressCount > 0 || activeFilter == EpisodeFilter.IN_PROGRESS) {
                                    FilterChip(
                                        selected = activeFilter == EpisodeFilter.IN_PROGRESS,
                                        onClick = { activeFilter = EpisodeFilter.IN_PROGRESS },
                                        label = {
                                            Text("${stringResource(R.string.filter_episodes_in_progress)} ($inProgressCount)")
                                        }
                                    )
                                }
                                if (hiddenCount > 0 || activeFilter == EpisodeFilter.HIDDEN) {
                                    FilterChip(
                                        selected = activeFilter == EpisodeFilter.HIDDEN,
                                        onClick = { activeFilter = EpisodeFilter.HIDDEN },
                                        label = {
                                            Text("${stringResource(R.string.filter_hidden)} ($hiddenCount)")
                                        }
                                    )
                                }
                                if (trashedCount > 0 || activeFilter == EpisodeFilter.TRASH) {
                                    FilterChip(
                                        selected = activeFilter == EpisodeFilter.TRASH,
                                        onClick = { activeFilter = EpisodeFilter.TRASH },
                                        label = {
                                            Text("${stringResource(R.string.filter_trash)} ($trashedCount)")
                                        }
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    if (activeFilter == EpisodeFilter.TRASH && filteredEpisodes.isNotEmpty()) {
                        item(key = "trash_hint") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val trashHintText = if (trashRetentionHours <= 0) {
                                    stringResource(R.string.trash_retention_immediate)
                                } else {
                                    stringResource(R.string.trash_hint_hours, trashRetentionHours)
                                }
                                Text(
                                    text = trashHintText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { showEmptyTrashDialog = true }) {
                                    Text(
                                        text = stringResource(R.string.action_empty_trash),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    if (filteredEpisodes.isEmpty() && activeFilter != EpisodeFilter.ALL) {
                        item(key = "filter_empty") {
                            val emptyMsg = when (activeFilter) {
                                EpisodeFilter.UNPLAYED -> stringResource(R.string.filter_empty_unplayed)
                                EpisodeFilter.IN_PROGRESS -> stringResource(R.string.episodes_empty_filter)
                                EpisodeFilter.HIDDEN -> stringResource(R.string.filter_empty_hidden)
                                EpisodeFilter.TRASH -> stringResource(R.string.filter_empty_trash)
                                EpisodeFilter.ALL -> ""
                            }
                            Text(
                                text = emptyMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                            )
                        }
                    }

                    val isTrashFilter = activeFilter == EpisodeFilter.TRASH
                    items(filteredEpisodes, key = { it.id }) { episode ->
                        EpisodeSwipeRow(
                            episode = episode,
                            isActive = episode.id == activeEpisodeId,
                            isTrashMode = isTrashFilter,
                            showDownloadAction = !podcast.isLocal,
                            onClick = { selectedEpisodeForPreviewId = episode.id },
                            onDoubleClick = { onTogglePlayed(episode) },
                            onLongClick = { selectedEpisodeForMenuId = episode.id },
                            onSwipeRightQueue = { onAddToQueue(episode) },
                            onSwipeLeftAction = {
                                if (isTrashFilter) {
                                    deletePermanentlyEpisodeId = episode.id
                                } else {
                                    if (!episode.isHidden) {
                                        lastHiddenEpisodeId = episode.id
                                        sawHiddenRemoved = false
                                    }
                                    onToggleHidden(episode)
                                }
                            },
                            onDownloadClick = {
                                if (episode.markedForDeletionAtMs > 0L) {
                                    onRestoreFromTrash(episode)
                                } else if (episode.downloadState == DownloadState.DOWNLOADED) {
                                    requestDeleteDownload(episode)
                                } else if (episode.downloadState == DownloadState.NONE || episode.downloadState == DownloadState.FAILED) {
                                    onDownloadEpisode(episode)
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.alpha(0.4f))
                    }
                }
            }
        }
    }

    if (showPodcastSettings) {
        podcast?.let { pod ->
            PodcastSettingsSheet(
                podcast = pod,
                globalMonoMode = globalMonoMode,
                globalAudioNormalization = globalAudioNormalization,
                onDismiss = { showPodcastSettings = false },
                onSetSpeed = onSetPlaybackSpeed,
                onSetSilenceMode = onSetSilenceMode,
                onSetMonoModeOverride = onSetMonoModeOverride,
                onSetNormalizationOverride = onSetNormalizationOverride,
                onToggleSort = onToggleSort,
                onDisableAutoDownload = onDisableAutoDownload
            )
        }
    }

    selectedEpisodeForMenuId?.let { menuId ->
        val ep = episodes.find { it.id == menuId }
        if (ep != null) {
            EpisodeOptionsSheet(
                episode = ep,
                podcast = podcast,
                canDeleteLocal = canDeleteLocal,
                onDismiss = { selectedEpisodeForMenuId = null },
                onPlay = { onPlayEpisode(ep) },
                onShowNotes = { selectedEpisodeForNotesId = ep.id },
                onAddToQueue = { onAddToQueue(ep) },
                onRestoreFromTrash = { onRestoreFromTrash(ep) },
                onDeletePermanently = { deletePermanentlyEpisodeId = ep.id },
                onDownload = { onDownloadEpisode(ep) },
                onDeleteDownload = { requestDeleteDownload(ep) },
                onTogglePlayed = { onTogglePlayed(ep) },
                onToggleHidden = { onToggleHidden(ep) },
                onDownloadNewerFrom = { onDownloadNewerFrom(ep) },
                onCleanupOlderThan = { onCleanupOlderThan(ep) }
            )
        }
    }

    selectedEpisodeForPreviewId?.let { previewId ->
        val previewEp = episodes.find { it.id == previewId }
        val pod = podcast
        if (previewEp != null && pod != null) {
            EpisodePreviewSheet(
                episode = previewEp,
                podcast = pod,
                textScale = textScale,
                canDeleteDownload = !pod.isLocal || canDeleteLocal,
                onTextScaleChange = onTextScaleChange,
                onPauseMainPlayer = onPauseMainPlayer,
                onPlayInMainPlayer = { onPlayEpisode(previewEp) },
                onAddToQueueFront = { onAddToQueueFront(previewEp) },
                onAddToQueueEnd = { onAddToQueue(previewEp) },
                onDownloadClick = {
                    if (previewEp.markedForDeletionAtMs > 0L) {
                        onRestoreFromTrash(previewEp)
                    } else if (previewEp.downloadState == DownloadState.DOWNLOADED) {
                        requestDeleteDownload(previewEp)
                    } else if (previewEp.downloadState == DownloadState.NONE || previewEp.downloadState == DownloadState.FAILED) {
                        onDownloadEpisode(previewEp)
                    }
                },
                onToggleHidden = { onToggleHidden(previewEp) },
                onTogglePlayed = { onTogglePlayed(previewEp) },
                onToggleFavorite = { onToggleFavorite(previewEp) },
                onDismiss = { selectedEpisodeForPreviewId = null }
            )
        }
    }

    selectedEpisodeForNotesId?.let { notesId ->
        val ep = episodes.find { it.id == notesId }
        if (ep != null) {
            EpisodeNotesSheet(
                episode = ep,
                textScale = textScale,
                onTextScaleChange = onTextScaleChange,
                onDismiss = { selectedEpisodeForNotesId = null }
            )
        }
    }

    if (showCoverLightbox) {
        podcast?.let { pod ->
            CoverLightboxDialog(
                imageUrl = pod.artworkUrl,
                title = pod.title,
                onDismiss = { showCoverLightbox = false }
            )
        }
    }

    if (showDescriptionReader) {
        podcast?.let { pod ->
            val readerText = remember(pod.description) { htmlToPlainText(pod.description) }
            if (readerText.isNotBlank()) {
                TextReaderDialog(
                    title = pod.title,
                    subtitle = pod.author.takeIf { it.isNotBlank() },
                    text = readerText,
                    textScale = textScale,
                    onTextScaleChange = onTextScaleChange,
                    onDismiss = { showDescriptionReader = false }
                )
            }
        }
    }

    PodcastConfirmDialogs(
        showEmptyTrashDialog = showEmptyTrashDialog,
        onDismissEmptyTrash = { showEmptyTrashDialog = false },
        onConfirmEmptyTrash = {
            showEmptyTrashDialog = false
            onEmptyTrash()
        },
        deletePermanentlyTarget = deletePermanentlyEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } },
        onDismissDeletePermanently = { deletePermanentlyEpisodeId = null },
        onConfirmDeletePermanently = { target ->
            deletePermanentlyEpisodeId = null
            onDeletePermanently(target)
        },
        showUnsubscribeDialog = showUnsubscribeDialog,
        podcast = podcast,
        onDismissUnsubscribe = { showUnsubscribeDialog = false },
        onConfirmUnsubscribe = {
            showUnsubscribeDialog = false
            onUnsubscribe()
        },
        deleteActiveTarget = deleteActiveEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } },
        onDismissDeleteActive = { deleteActiveEpisodeId = null },
        onConfirmDeleteActive = { target ->
            deleteActiveEpisodeId = null
            onStopAndDeleteActive(target)
        }
    )
}
