package com.lycoslane.purepodcast.ui.search

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lycoslane.purepodcast.core.network.PodcastSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    results: List<PodcastSearchResult>,
    subscribedFeeds: Map<String, Long>,
    isSearching: Boolean,
    statusMessage: String?,
    onSearch: (String) -> Unit,
    onSubscribeFeedUrl: (String) -> Unit,
    onUnsubscribeById: (Long, String) -> Unit,
    onImportOpmlUri: (Uri) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showRssDialog by remember { mutableStateOf(false) }
    var rssInput by remember { mutableStateOf("") }
    var pendingUnsubscribe by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            delay(350L)
            onSearch(trimmed)
        }
    }

    val opmlPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportOpmlUri(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.search_title), fontWeight = FontWeight.SemiBold) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (query.isNotBlank()) {
                        IconButton(onClick = { onSearch(query) }) {
                            Icon(Icons.Default.Search, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_search))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) })
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showRssDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_rss_url))
                }
                OutlinedButton(
                    onClick = { opmlPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_opml_import))
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.feedUrl }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = item.artworkUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val subscribedId = subscribedFeeds[item.feedUrl]
                        if (subscribedId != null) {
                            IconButton(onClick = { pendingUnsubscribe = subscribedId to item.title }) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_subscribed_tap),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = { onSubscribeFeedUrl(item.feedUrl) }) {
                                Icon(Icons.Default.Add, contentDescription = androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_subscribe))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUnsubscribe?.let { (podcastId, title) ->
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_unsubscribe_title)) },
            text = {
                Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_unsubscribe_body, title))
            },
            confirmButton = {
                Button(onClick = {
                    onUnsubscribeById(podcastId, title)
                    pendingUnsubscribe = null
                }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_unsubscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
    }

    if (showRssDialog) {
        AlertDialog(
            onDismissRequest = { showRssDialog = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.dialog_rss_title)) },
            text = {
                OutlinedTextField(
                    value = rssInput,
                    onValueChange = { rssInput = it },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (rssInput.isNotBlank()) {
                        onSubscribeFeedUrl(rssInput.trim())
                        rssInput = ""
                        showRssDialog = false
                    }
                }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_subscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRssDialog = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.lycoslane.purepodcast.R.string.action_cancel))
                }
            }
        )
    }
}
