package com.lycoslane.purepodcast.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeWithPodcast
import com.lycoslane.purepodcast.ui.common.PodcastImage
import java.io.File

@Composable
internal fun CompressedDownloadsDialog(
    allEpisodes: List<EpisodeWithPodcast>,
    onDismiss: () -> Unit
) {
    val compressedList = remember(allEpisodes) {
        allEpisodes.filter { item ->
            !item.podcast.isLocal &&
                item.episode.downloadState == DownloadState.DOWNLOADED &&
                item.episode.markedForDeletionAtMs == 0L &&
                item.episode.isTranscoded &&
                item.episode.localFilePath?.endsWith(".m4a", ignoreCase = true) == true
        }.sortedByDescending { it.episode.pubDateMs }
    }
    val totalCurrentBytes = remember(compressedList) {
        compressedList.sumOf { item ->
            item.episode.fileSizeBytes.takeIf { it > 0L }
                ?: runCatching { File(item.episode.localFilePath.orEmpty()).length() }.getOrDefault(0L)
        }
    }
    val totalSavedBytes = remember(compressedList) {
        compressedList.sumOf { it.episode.transcodeSavedBytes.coerceAtLeast(0L) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_compressed_downloads_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.compressed_downloads_total_summary,
                        compressedList.size,
                        formatSizeMb(totalCurrentBytes),
                        formatSizeMb(totalSavedBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(compressedList, key = { it.episode.id }) { item ->
                        val ep = item.episode
                        val pod = item.podcast
                        val currentBytes = ep.fileSizeBytes.takeIf { it > 0L }
                            ?: runCatching { File(ep.localFilePath.orEmpty()).length() }.getOrDefault(0L)
                        val savedBytes = ep.transcodeSavedBytes.coerceAtLeast(0L)
                        val origBytes = currentBytes + savedBytes
                        val savedPct = if (origBytes > 0L && savedBytes > 0L) {
                            ((savedBytes * 100L) / origBytes).toInt().coerceIn(1, 99)
                        } else {
                            0
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastImage(
                                url = ep.artworkUrl.ifEmpty { pod.artworkUrl },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ep.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pod.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (savedBytes > 0L) {
                                        stringResource(
                                            R.string.compressed_episode_saved_detail,
                                            formatSizeMb(origBytes),
                                            formatSizeMb(currentBytes),
                                            formatSizeMb(savedBytes),
                                            savedPct
                                        )
                                    } else {
                                        stringResource(
                                            R.string.compressed_episode_size_only,
                                            formatSizeMb(currentBytes)
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.alpha(0.35f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}
