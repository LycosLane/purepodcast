package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.htmlToPlainText

private enum class EpisodeFilter {
    ALL,
    UNPLAYED,
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
    canDeleteLocal: Boolean = true,
    onTextScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleSort: (Boolean) -> Unit,
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
    var selectedEpisodeForPreviewId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEpisodeForMenuId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEpisodeForNotesId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var showCoverLightbox by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showDescriptionReader by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var expandDescription by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showEpisodeSearch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var episodeSearchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var showUnsubscribeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var deleteActiveEpisodeId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var deletePermanentlyEpisodeId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var activeFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(EpisodeFilter.ALL) }

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_back))
                    }
                },
                actions = {
                    if (podcast != null) {
                        IconButton(onClick = {
                            showEpisodeSearch = !showEpisodeSearch
                            if (!showEpisodeSearch) episodeSearchQuery = ""
                        }) {
                            Icon(Icons.Default.Search, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.episode_search_hint))
                        }
                        IconButton(onClick = { onToggleSort(!podcast.sortAscending) }) {
                            Icon(Icons.Default.SwapVert, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_toggle_sort))
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_refresh_feed))
                        }
                        if (!podcast.isLocal) {
                            if (podcast.isSubscribed) {
                                IconButton(onClick = { showUnsubscribeDialog = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_remove_podcast))
                                }
                            } else {
                                TextButton(onClick = onSubscribe) {
                                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_subscribe))
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
                androidx.compose.material3.CircularProgressIndicator()
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
        val filteredEpisodes = remember(episodes, activeFilter, episodeSearchQuery) {
            val base = when (activeFilter) {
                EpisodeFilter.ALL -> episodes.filter { !it.isHidden }
                EpisodeFilter.UNPLAYED -> episodes.filter { !it.isHidden && !it.isPlayed }
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
                    placeholder = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.episode_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (episodeSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { episodeSearchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_close))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            var lastHiddenEpisodeId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
            var sawHiddenRemoved by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            androidx.compose.runtime.LaunchedEffect(filteredEpisodes, lastHiddenEpisodeId) {
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
                            contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.cd_podcast_cover),
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
                                text = if (podcast.sortAscending) {
                                    androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.sort_asc)
                                } else {
                                    androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.sort_desc)
                                },
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
                                if (expandDescription) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.show_less)
                                else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.show_more)
                            )
                        }
                    }

                    if (podcast.autoDownloadFromPubDateMs > 0L) {
                        AssistChip(
                            onClick = onDisableAutoDownload,
                            label = {
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.lycoslane.purepodcast.R.string.auto_download_active,
                                        formatPubDate(podcast.autoDownloadFromPubDateMs)
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
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
                            label = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_all)) }
                        )
                        FilterChip(
                            selected = activeFilter == EpisodeFilter.UNPLAYED,
                            onClick = { activeFilter = EpisodeFilter.UNPLAYED },
                            label = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_unplayed)) }
                        )
                        if (hiddenCount > 0 || activeFilter == EpisodeFilter.HIDDEN) {
                            FilterChip(
                                selected = activeFilter == EpisodeFilter.HIDDEN,
                                onClick = { activeFilter = EpisodeFilter.HIDDEN },
                                label = {
                                    Text("${androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_hidden)} ($hiddenCount)")
                                }
                            )
                        }
                        if (trashedCount > 0 || activeFilter == EpisodeFilter.TRASH) {
                            FilterChip(
                                selected = activeFilter == EpisodeFilter.TRASH,
                                onClick = { activeFilter = EpisodeFilter.TRASH },
                                label = {
                                    Text("${androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_trash)} ($trashedCount)")
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
                            androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.trash_retention_immediate)
                        } else {
                            androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.trash_hint_hours, trashRetentionHours)
                        }
                        Text(
                            text = trashHintText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showEmptyTrashDialog = true }) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_empty_trash),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (filteredEpisodes.isEmpty() && activeFilter != EpisodeFilter.ALL) {
                item(key = "filter_empty") {
                    val emptyMsg = when (activeFilter) {
                        EpisodeFilter.UNPLAYED -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_empty_unplayed)
                        EpisodeFilter.HIDDEN -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_empty_hidden)
                        EpisodeFilter.TRASH -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.filter_empty_trash)
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
                    showDownloadAction = !(podcast?.isLocal ?: false),
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

    // Long-press action sheet (all actions at thumb reach, no nested submenus)
    selectedEpisodeForMenuId?.let { menuId ->
        val ep = episodes.find { it.id == menuId }
        if (ep != null) {
        ModalBottomSheet(onDismissRequest = { selectedEpisodeForMenuId = null }) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_play)) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                    modifier = Modifier.clickable(onClick = {
                        onPlayEpisode(ep)
                        selectedEpisodeForMenuId = null
                    })
                )
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_show_notes)) },
                    leadingContent = { Icon(Icons.Default.Description, null) },
                    modifier = Modifier.clickable(onClick = {
                        selectedEpisodeForNotesId = ep.id
                        selectedEpisodeForMenuId = null
                    })
                )
                ListItem(
                    headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_add_queue)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    modifier = Modifier.clickable(onClick = {
                        onAddToQueue(ep)
                        selectedEpisodeForMenuId = null
                    })
                )
                if (ep.markedForDeletionAtMs > 0L && ep.downloadState == DownloadState.DOWNLOADED) {
                    ListItem(
                        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_restore_trash)) },
                        leadingContent = { Icon(Icons.Default.RestoreFromTrash, null) },
                        modifier = Modifier.clickable(onClick = {
                            onRestoreFromTrash(ep)
                            selectedEpisodeForMenuId = null
                        })
                    )
                    ListItem(
                        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_delete_now)) },
                        leadingContent = { Icon(Icons.Default.DeleteForever, null) },
                        modifier = Modifier.clickable(onClick = {
                            deletePermanentlyEpisodeId = ep.id
                            selectedEpisodeForMenuId = null
                        })
                    )
                } else if (podcast?.isLocal != true ||
                    (ep.downloadState == DownloadState.DOWNLOADED && canDeleteLocal)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                if (ep.downloadState == DownloadState.DOWNLOADED) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_delete_download)
                                else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_download_offline)
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (ep.downloadState == DownloadState.DOWNLOADED) Icons.Default.Delete
                                else Icons.Default.Download,
                                null
                            )
                        },
                        modifier = Modifier.clickable(onClick = {
                            if (ep.downloadState == DownloadState.DOWNLOADED) requestDeleteDownload(ep)
                            else onDownloadEpisode(ep)
                            selectedEpisodeForMenuId = null
                        })
                    )
                }
                ListItem(
                    headlineContent = {
                        Text(
                            if (ep.isPlayed) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_mark_unplayed)
                            else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_mark_played)
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (ep.isPlayed) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                            null
                        )
                    },
                    modifier = Modifier.clickable(onClick = {
                        onTogglePlayed(ep)
                        selectedEpisodeForMenuId = null
                    })
                )
                ListItem(
                    headlineContent = {
                        Text(
                            if (ep.isHidden) androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_unhide)
                            else androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_hide)
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (ep.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            null
                        )
                    },
                    modifier = Modifier.clickable(onClick = {
                        onToggleHidden(ep)
                        selectedEpisodeForMenuId = null
                    })
                )
                if (podcast?.isLocal != true) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_auto_download_newer)) },
                        supportingContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_auto_download_newer_sub)) },
                        leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                        modifier = Modifier.clickable(onClick = {
                            onDownloadNewerFrom(ep)
                            selectedEpisodeForMenuId = null
                        })
                    )
                    ListItem(
                        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cleanup_older)) },
                        supportingContent = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cleanup_older_sub)) },
                        leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                        modifier = Modifier.clickable(onClick = {
                            onCleanupOlderThan(ep)
                            selectedEpisodeForMenuId = null
                        })
                    )
                }
            }
        }
        }
    }

    // Episode Preview Sheet (single tap)
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

    // Show Notes Sheet
    selectedEpisodeForNotesId?.let { notesId ->
        val ep = episodes.find { it.id == notesId }
        if (ep != null) {
        val notesText = remember(ep.descriptionHtml) { htmlToPlainText(ep.descriptionHtml) }
        val pubDateText = remember(ep.pubDateMs) { formatPubDate(ep.pubDateMs) }
        ModalBottomSheet(onDismissRequest = { selectedEpisodeForNotesId = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$pubDateText • ${formatDuration(ep.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PinchZoomText(
                    text = notesText.ifBlank {
                        androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.no_show_notes)
                    },
                    textScale = textScale,
                    onTextScaleChange = onTextScaleChange,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
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

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_empty_trash_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_empty_trash_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrashDialog = false
                    onEmptyTrash()
                }) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_empty_trash),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
    }

    val deletePermanentlyTarget = deletePermanentlyEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } }
    deletePermanentlyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletePermanentlyEpisodeId = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_delete_now)) },
            text = { Text(target.title) },
            confirmButton = {
                TextButton(onClick = {
                    deletePermanentlyEpisodeId = null
                    onDeletePermanently(target)
                }) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.swipe_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePermanentlyEpisodeId = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
    }

    if (showUnsubscribeDialog) {
        podcast?.let { pod ->
            AlertDialog(
                onDismissRequest = { showUnsubscribeDialog = false },
                title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_unsubscribe_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_unsubscribe_body, pod.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsubscribeDialog = false
                    onUnsubscribe()
                }) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_unsubscribe),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsubscribeDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
        }
    }

    val deleteActiveTarget = deleteActiveEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } }
    deleteActiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteActiveEpisodeId = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_delete_active_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_delete_active_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteActiveEpisodeId = null
                    onStopAndDeleteActive(target)
                }) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_stop_and_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteActiveEpisodeId = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeSwipeRow(
    episode: EpisodeEntity,
    isActive: Boolean,
    isTrashMode: Boolean,
    showDownloadAction: Boolean = true,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeRightQueue: () -> Unit,
    onSwipeLeftAction: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val latestRight by androidx.compose.runtime.rememberUpdatedState(onSwipeRightQueue)
    val latestLeft by androidx.compose.runtime.rememberUpdatedState(onSwipeLeftAction)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val thresholdFraction = 0.45f
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var dismissStateHolder: androidx.compose.material3.SwipeToDismissBoxState? = null

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                true
            } else {
                val offset = runCatching { dismissStateHolder?.requireOffset() }.getOrNull() ?: 0f
                val fraction = if (rowWidthPx > 0) {
                    kotlin.math.abs(offset) / rowWidthPx.toFloat()
                } else {
                    0f
                }
                fraction >= thresholdFraction
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * thresholdFraction }
    )
    dismissStateHolder = dismissState

    val swipeFraction by remember {
        androidx.compose.runtime.derivedStateOf {
            val offset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
            if (rowWidthPx > 0) {
                (kotlin.math.abs(offset) / rowWidthPx.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    val reachedThreshold = swipeFraction >= thresholdFraction

    androidx.compose.runtime.LaunchedEffect(reachedThreshold) {
        if (reachedThreshold) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
    }

    androidx.compose.runtime.LaunchedEffect(dismissState.currentValue) {
        val value = dismissState.currentValue
        if (value != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> latestRight()
                SwipeToDismissBoxValue.EndToStart -> latestLeft()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        }
    }

    val a11yQueueLabel = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_add_queue)
    val a11yLeftLabel = androidx.compose.ui.res.stringResource(
        when {
            isTrashMode -> com.lycoslane.purepodcast.R.string.swipe_delete
            episode.isHidden -> com.lycoslane.purepodcast.R.string.swipe_unhide
            else -> com.lycoslane.purepodcast.R.string.swipe_hide
        }
    )
    val a11yPlayedLabel = androidx.compose.ui.res.stringResource(
        if (episode.isPlayed) com.lycoslane.purepodcast.R.string.action_mark_unplayed
        else com.lycoslane.purepodcast.R.string.action_mark_played
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.onSizeChanged { rowWidthPx = it.width },
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val alignment = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val baseColor = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF2E7D32)
                SwipeToDismissBoxValue.EndToStart -> if (isTrashMode) MaterialTheme.colorScheme.error else Color(0xFFBF360C)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val bgColor = if (reachedThreshold || dir == SwipeToDismissBoxValue.Settled) {
                baseColor
            } else {
                baseColor.copy(alpha = 0.35f + 0.35f * (swipeFraction / thresholdFraction))
            }
            val icon = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.AutoMirrored.Filled.PlaylistAdd
                SwipeToDismissBoxValue.EndToStart -> when {
                    isTrashMode -> Icons.Default.DeleteForever
                    episode.isHidden -> Icons.Default.Visibility
                    else -> Icons.Default.VisibilityOff
                }
                else -> Icons.AutoMirrored.Filled.PlaylistAdd
            }
            val label = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.swipe_queue)
                SwipeToDismissBoxValue.EndToStart -> when {
                    isTrashMode -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.swipe_delete)
                    episode.isHidden -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.swipe_unhide)
                    else -> androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.swipe_hide)
                }
                else -> ""
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    Row(
                        modifier = Modifier.alpha(if (reachedThreshold) 1f else 0.7f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (dir == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(icon, contentDescription = null, tint = Color.White)
                            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Icon(icon, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(a11yQueueLabel) {
                            latestRight(); true
                        },
                        CustomAccessibilityAction(a11yLeftLabel) {
                            latestLeft(); true
                        },
                        CustomAccessibilityAction(a11yPlayedLabel) {
                            onDoubleClick(); true
                        }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (episode.isPlayed && !isActive) 0.6f else 1f)
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dateStr = remember(episode.pubDateMs) { formatPubDate(episode.pubDateMs) }
                    if (dateStr.isNotEmpty()) {
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.durationMs > 0L) {
                        Text(
                            text = formatDuration(episode.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.positionMs > 0L && !episode.isPlayed) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                com.lycoslane.purepodcast.R.string.at_position,
                                formatDuration(episode.positionMs)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (showDownloadAction) {
                Spacer(Modifier.width(8.dp))

                when (episode.downloadState) {
                    DownloadState.DOWNLOADING -> {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { episode.downloadProgress / 100f },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                    DownloadState.QUEUED -> {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    DownloadState.DOWNLOADED -> {
                        IconButton(onClick = onDownloadClick) {
                            if (episode.markedForDeletionAtMs > 0L) {
                                Icon(
                                    Icons.Default.RestoreFromTrash,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_restore_trash),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Icon(
                                    Icons.Default.DownloadDone,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.cd_downloaded),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    DownloadState.FAILED -> {
                        IconButton(onClick = onDownloadClick) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.cd_download_failed),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    else -> {
                        IconButton(onClick = onDownloadClick) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.cd_download)
                            )
                        }
                    }
                }
            }
        }
    }
}
