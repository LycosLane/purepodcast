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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.network.ParsedPodcastFeed
import com.lycoslane.purepodcast.core.network.PodcastSearchResult
import com.lycoslane.purepodcast.ui.common.CoverLightboxDialog
import com.lycoslane.purepodcast.ui.common.PinchZoomText
import com.lycoslane.purepodcast.ui.common.PodcastImage
import com.lycoslane.purepodcast.ui.common.TextReaderDialog
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
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    focusSearchTrigger: Int = 0,
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
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
    }
    if (textFieldValue.text != query) {
        textFieldValue = textFieldValue.copy(text = query, selection = TextRange(query.length))
    }

    LaunchedEffect(focusSearchTrigger) {
        if (focusSearchTrigger > 0) {
            showSearchControls = true
            textFieldValue = textFieldValue.copy(text = query, selection = TextRange(query.length))
            delay(50L)
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

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
                        value = textFieldValue,
                        onValueChange = { newVal ->
                            textFieldValue = newVal
                            onQueryChange(newVal.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    textFieldValue = TextFieldValue("")
                                    onQueryChange("")
                                }) {
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
            var lightboxArtwork by remember { mutableStateOf<Pair<String, String>?>(null) }
            var readerData by remember { mutableStateOf<Triple<String, String, String>?>(null) }

            Dialog(
                onDismissRequest = { previewIndex = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { previewIndex = null }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_cancel)
                                )
                            }
                        }

                        HorizontalDivider()

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
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
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                PodcastImage(
                                    url = item.artworkUrl,
                                    contentDescription = stringResource(R.string.cd_podcast_cover),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(196.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { lightboxArtwork = item.artworkUrl to item.title }
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.author.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = item.author,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (item.primaryGenre.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = item.primaryGenre,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(14.dp))

                                if (isLoadingPreview && feed == null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    }
                                } else if (previewFailed && feed == null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_preview_failed),
                                            style = MaterialTheme.typography.bodyMedium,
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
                                                text = stringResource(R.string.hint_tap_to_read),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            PinchZoomText(
                                                text = desc,
                                                textScale = textScale,
                                                onTextScaleChange = onTextScaleChange,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                onClick = {
                                                    readerData = Triple(item.title, item.author, desc)
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        val latest = feed?.episodes?.take(5).orEmpty()
                                        if (latest.isNotEmpty()) {
                                            Spacer(Modifier.height(14.dp))
                                            HorizontalDivider()
                                            Spacer(Modifier.height(10.dp))
                                            latest.forEach { ep ->
                                                Text(
                                                    text = "• ${ep.title}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val currentItem = results.getOrNull(pagerState.currentPage)
                        if (currentItem != null) {
                            HorizontalDivider()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 14.dp)
                            ) {
                                val subscribedId = subscribedFeeds[currentItem.feedUrl]
                                if (subscribedId != null) {
                                    OutlinedButton(
                                        onClick = { pendingUnsubscribe = subscribedId to currentItem.title },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.action_subscribed_tap))
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            previewIndex = null
                                            onSubscribeFeedUrl(currentItem.feedUrl)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
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

            lightboxArtwork?.let { (url, title) ->
                CoverLightboxDialog(
                    imageUrl = url,
                    title = title,
                    onDismiss = { lightboxArtwork = null }
                )
            }

            readerData?.let { (title, author, text) ->
                TextReaderDialog(
                    title = title,
                    subtitle = author.ifBlank { null },
                    text = text,
                    textScale = textScale,
                    onTextScaleChange = onTextScaleChange,
                    onDismiss = { readerData = null }
                )
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
