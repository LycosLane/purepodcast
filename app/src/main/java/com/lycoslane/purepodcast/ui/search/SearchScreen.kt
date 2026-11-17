package com.lycoslane.purepodcast.ui.search

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.network.ParsedPodcastFeed
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.htmlToPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<PodcastSearchResult>,
    subscribedFeeds: Map<String, Long>,
    isSearching: Boolean,
    statusMessage: String?,
    onSearch: (String) -> Unit,
    onLoadFeedPreview: suspend (String) -> ParsedPodcastFeed?,
    onSubscribeFeedUrl: (String) -> Unit,
    onUnsubscribeById: (Long, String) -> Unit,
    onImportOpmlUri: (Uri) -> Unit
) {
    var showRssDialog by rememberSaveable { mutableStateOf(false) }
    var rssInput by rememberSaveable { mutableStateOf("") }
    var pendingUnsubscribe by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var previewIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val previewCache = remember { mutableStateMapOf<String, ParsedPodcastFeed>() }

    val listState = rememberLazyListState()
    var showSearchControls by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        var prevIndex = 0
        var prevOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, offset) ->
                if (idx == 0 && offset == 0) {
                    showSearchControls = true
                } else if (idx > prevIndex || (idx == prevIndex && offset > prevOffset + 24)) {
                    showSearchControls = false
                } else if (idx < prevIndex || (idx == prevIndex && offset < prevOffset - 24)) {
                    showSearchControls = true
                }
                prevIndex = idx
                prevOffset = offset
            }
    }

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
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.search_title), fontWeight = FontWeight.SemiBold) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(
                visible = showSearchControls,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_cancel))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val trimmed = query.trim()
                            if (trimmed.isNotEmpty()) onSearch(trimmed)
                        })
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
                            Text(
                                text = stringResource(R.string.action_rss_url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { opmlPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_opml_import),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (!isSearching && results.isEmpty() && query.trim().length >= 2) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(results, key = { index, item -> "${item.feedUrl}#$index" }) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable { previewIndex = index }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastImage(
                            url = item.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
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
                                    contentDescription = stringResource(R.string.action_subscribed_tap),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = { onSubscribeFeedUrl(item.feedUrl) }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_subscribe))
                            }
                        }
                    }
                }
            }
        }
    }

    previewIndex?.let { startIdx ->
        if (results.isNotEmpty()) {
            val safeStart = startIdx.coerceIn(0, results.lastIndex)
            val pagerState = rememberPagerState(initialPage = safeStart) { results.size }
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { previewIndex = null },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.search_preview_swipe_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / ${results.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { previewIndex = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        val item = results.getOrNull(page) ?: return@HorizontalPager
                        val feed = previewCache[item.feedUrl]
                        var isLoadingPreview by remember(item.feedUrl) { mutableStateOf(feed == null) }
                        var previewFailed by remember(item.feedUrl) { mutableStateOf(false) }
                        var retryToken by remember(item.feedUrl) { mutableIntStateOf(0) }

                        LaunchedEffect(item.feedUrl, retryToken) {
                            if (previewCache[item.feedUrl] == null) {
                                isLoadingPreview = true
                                previewFailed = false
                                val parsed = onLoadFeedPreview(item.feedUrl)
                                if (parsed != null) {
                                    if (previewCache.size >= 20) {
                                        previewCache.keys.firstOrNull()?.let { previewCache.remove(it) }
                                    }
                                    previewCache[item.feedUrl] = parsed
                                } else {
                                    previewFailed = true
                                }
                                isLoadingPreview = false
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PodcastImage(
                                url = item.artworkUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(168.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.author.isNotBlank()) {
                                Text(
                                    text = item.author,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.primaryGenre.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = item.primaryGenre,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (isLoadingPreview && feed == null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .align(Alignment.Center)
                                    )
                                } else if (previewFailed && feed == null) {
                                    Column(
                                        modifier = Modifier.align(Alignment.Center),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_preview_failed),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        TextButton(onClick = { retryToken++ }) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                    }
                                } else {
                                    val rawDesc = feed?.description ?: ""
                                    val desc by produceState(initialValue = "", key1 = rawDesc) {
                                        value = if (rawDesc.isBlank()) "" else {
                                            withContext(Dispatchers.Default) { htmlToPlainText(rawDesc) }
                                        }
                                    }
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        if (desc.isNotBlank()) {
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        val latest = feed?.episodes?.take(3).orEmpty()
                                        if (latest.isNotEmpty()) {
                                            Spacer(Modifier.height(10.dp))
                                            HorizontalDivider()
                                            Spacer(Modifier.height(8.dp))
                                            latest.forEach { ep ->
                                                Text(
                                                    text = "• ${ep.title}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            val subscribedId = subscribedFeeds[item.feedUrl]
                            if (subscribedId != null) {
                                OutlinedButton(
                                    onClick = { pendingUnsubscribe = subscribedId to item.title },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.action_subscribed_tap))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        previewIndex = null
                                        onSubscribeFeedUrl(item.feedUrl)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.action_subscribe))
                                }
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
            title = { Text(stringResource(R.string.dialog_unsubscribe_title)) },
            text = { Text(stringResource(R.string.dialog_unsubscribe_body, title)) },
            confirmButton = {
                Button(onClick = {
                    onUnsubscribeById(podcastId, title)
                    pendingUnsubscribe = null
                }) {
                    Text(stringResource(R.string.action_unsubscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showRssDialog) {
        AlertDialog(
            onDismissRequest = { showRssDialog = false },
            title = { Text(stringResource(R.string.dialog_rss_title)) },
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
                    Text(stringResource(R.string.action_subscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRssDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
