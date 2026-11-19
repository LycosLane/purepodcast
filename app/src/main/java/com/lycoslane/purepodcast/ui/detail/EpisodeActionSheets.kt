package com.lycoslane.purepodcast.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.formatDuration
import com.lycoslane.purepodcast.ui.common.formatPubDate
import com.lycoslane.purepodcast.ui.common.htmlToPlainText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeOptionsSheet(
    episode: EpisodeEntity,
    podcast: PodcastEntity?,
    canDeleteLocal: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShowNotes: () -> Unit,
    onAddToQueue: () -> Unit,
    onRestoreFromTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleHidden: () -> Unit,
    onDownloadNewerFrom: () -> Unit,
    onCleanupOlderThan: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringResource(R.string.action_play)) },
                leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                modifier = Modifier.clickable(onClick = {
                    onPlay()
                    onDismiss()
                })
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_show_notes)) },
                leadingContent = { Icon(Icons.Default.Description, null) },
                modifier = Modifier.clickable(onClick = {
                    onShowNotes()
                    onDismiss()
                })
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_add_queue)) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                modifier = Modifier.clickable(onClick = {
                    onAddToQueue()
                    onDismiss()
                })
            )
            if (episode.markedForDeletionAtMs > 0L && episode.downloadState == DownloadState.DOWNLOADED) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_restore_trash)) },
                    leadingContent = { Icon(Icons.Default.RestoreFromTrash, null) },
                    modifier = Modifier.clickable(onClick = {
                        onRestoreFromTrash()
                        onDismiss()
                    })
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_delete_now)) },
                    leadingContent = { Icon(Icons.Default.DeleteForever, null) },
                    modifier = Modifier.clickable(onClick = {
                        onDeletePermanently()
                        onDismiss()
                    })
                )
            } else if (podcast?.isLocal != true ||
                (episode.downloadState == DownloadState.DOWNLOADED && canDeleteLocal)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            if (episode.downloadState == DownloadState.DOWNLOADED) {
                                stringResource(R.string.action_delete_download)
                            } else {
                                stringResource(R.string.action_download_offline)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (episode.downloadState == DownloadState.DOWNLOADED) Icons.Default.Delete
                            else Icons.Default.Download,
                            null
                        )
                    },
                    modifier = Modifier.clickable(onClick = {
                        if (episode.downloadState == DownloadState.DOWNLOADED) onDeleteDownload()
                        else onDownload()
                        onDismiss()
                    })
                )
            }
            ListItem(
                headlineContent = {
                    Text(
                        if (episode.isPlayed) stringResource(R.string.action_mark_unplayed)
                        else stringResource(R.string.action_mark_played)
                    )
                },
                leadingContent = {
                    Icon(
                        if (episode.isPlayed) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                        null
                    )
                },
                modifier = Modifier.clickable(onClick = {
                    onTogglePlayed()
                    onDismiss()
                })
            )
            ListItem(
                headlineContent = {
                    Text(
                        if (episode.isHidden) stringResource(R.string.action_unhide)
                        else stringResource(R.string.action_hide)
                    )
                },
                leadingContent = {
                    Icon(
                        if (episode.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        null
                    )
                },
                modifier = Modifier.clickable(onClick = {
                    onToggleHidden()
                    onDismiss()
                })
            )
            if (podcast?.isLocal != true) {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_auto_download_newer)) },
                    supportingContent = { Text(stringResource(R.string.action_auto_download_newer_sub)) },
                    leadingContent = { Icon(Icons.Default.AutoAwesome, null) },
                    modifier = Modifier.clickable(onClick = {
                        onDownloadNewerFrom()
                        onDismiss()
                    })
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_cleanup_older)) },
                    supportingContent = { Text(stringResource(R.string.action_cleanup_older_sub)) },
                    leadingContent = { Icon(Icons.Default.CleaningServices, null) },
                    modifier = Modifier.clickable(onClick = {
                        onCleanupOlderThan()
                        onDismiss()
                    })
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeNotesSheet(
    episode: EpisodeEntity,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val notesText = remember(episode.descriptionHtml) { htmlToPlainText(episode.descriptionHtml) }
    val pubDateText = remember(episode.pubDateMs) { formatPubDate(episode.pubDateMs) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$pubDateText • ${formatDuration(episode.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            PinchZoomText(
                text = notesText.ifBlank { stringResource(R.string.no_show_notes) },
                textScale = textScale,
                onTextScaleChange = onTextScaleChange,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun PodcastConfirmDialogs(
    showEmptyTrashDialog: Boolean,
    onDismissEmptyTrash: () -> Unit,
    onConfirmEmptyTrash: () -> Unit,
    deletePermanentlyTarget: EpisodeEntity?,
    onDismissDeletePermanently: () -> Unit,
    onConfirmDeletePermanently: (EpisodeEntity) -> Unit,
    showUnsubscribeDialog: Boolean,
    podcast: PodcastEntity?,
    onDismissUnsubscribe: () -> Unit,
    onConfirmUnsubscribe: () -> Unit,
    deleteActiveTarget: EpisodeEntity?,
    onDismissDeleteActive: () -> Unit,
    onConfirmDeleteActive: (EpisodeEntity) -> Unit
) {
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = onDismissEmptyTrash,
            title = { Text(stringResource(R.string.dialog_empty_trash_title)) },
            text = { Text(stringResource(R.string.dialog_empty_trash_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmEmptyTrash) {
                    Text(
                        text = stringResource(R.string.action_empty_trash),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissEmptyTrash) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    deletePermanentlyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onDismissDeletePermanently,
            title = { Text(stringResource(R.string.action_delete_now)) },
            text = { Text(target.title) },
            confirmButton = {
                TextButton(onClick = { onConfirmDeletePermanently(target) }) {
                    Text(
                        text = stringResource(R.string.swipe_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeletePermanently) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showUnsubscribeDialog) {
        podcast?.let { pod ->
            AlertDialog(
                onDismissRequest = onDismissUnsubscribe,
                title = { Text(stringResource(R.string.dialog_unsubscribe_title)) },
                text = { Text(stringResource(R.string.dialog_unsubscribe_body, pod.title)) },
                confirmButton = {
                    TextButton(onClick = onConfirmUnsubscribe) {
                        Text(
                            text = stringResource(R.string.action_unsubscribe),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissUnsubscribe) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    deleteActiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = onDismissDeleteActive,
            title = { Text(stringResource(R.string.dialog_delete_active_title)) },
            text = { Text(stringResource(R.string.dialog_delete_active_body)) },
            confirmButton = {
                TextButton(onClick = { onConfirmDeleteActive(target) }) {
                    Text(
                        text = stringResource(R.string.action_stop_and_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteActive) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
