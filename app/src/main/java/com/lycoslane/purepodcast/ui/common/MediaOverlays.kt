package com.lycoslane.purepodcast.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lycoslane.purepodcast.R
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CoverLightboxDialog(
    imageUrl: String,
    title: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .safeDrawingPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val coverSize = min(maxWidth - 48.dp, maxHeight - 140.dp).coerceAtLeast(120.dp)

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PodcastImage(
                    url = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(20.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        }
                )
                if (title.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PinchZoomText(
    text: String,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    style: TextStyle,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentScale by rememberUpdatedState(textScale)
    val currentOnScale by rememberUpdatedState(onTextScaleChange)
    val currentOnClick by rememberUpdatedState(onClick)

    var localScale by remember { mutableFloatStateOf(textScale) }
    LaunchedEffect(textScale) {
        localScale = textScale
    }

    val scaledStyle = if (style.fontSize.isSpecified) {
        style.copy(
            fontSize = style.fontSize * localScale,
            lineHeight = if (style.lineHeight.isSpecified) {
                style.lineHeight * localScale
            } else {
                style.fontSize * localScale * 1.4f
            }
        )
    } else {
        style
    }

    Text(
        text = text,
        style = scaledStyle,
        color = color,
        modifier = modifier
            .semantics {
                val click = currentOnClick
                if (click != null) {
                    role = Role.Button
                    onClick {
                        click()
                        true
                    }
                }
            }
            .pointerInput(Unit) {
            val touchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val downPos = down.position
                var maxPointers = 1
                var moved = false
                var didZoom = false
                var activeScale = currentScale
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedChanges = event.changes.filter { it.pressed }
                    if (pressedChanges.size > maxPointers) {
                        maxPointers = pressedChanges.size
                    }
                    if (pressedChanges.size == 1 && !moved) {
                        val delta = pressedChanges[0].position - downPos
                        if (delta.getDistance() > touchSlop) {
                            moved = true
                        }
                    }
                    if (pressedChanges.size >= 2) {
                        val zoom = event.calculateZoom()
                        if (abs(zoom - 1f) > 0.001f) {
                            activeScale = (activeScale * zoom).coerceIn(0.8f, 1.8f)
                            localScale = activeScale
                            didZoom = true
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (didZoom) {
                    currentOnScale(activeScale)
                } else if (maxPointers == 1 && !moved) {
                    currentOnClick?.invoke()
                }
            }
        }
    )
}

@Composable
fun TextReaderDialog(
    title: String,
    subtitle: String?,
    text: String,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    TextButton(onClick = { onTextScaleChange(1.0f) }) {
                        Text("${(textScale * 100f).roundToInt()}%")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    PinchZoomText(
                        text = text,
                        textScale = textScale,
                        onTextScaleChange = onTextScaleChange,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

private val androidx.compose.ui.unit.TextUnit.isSpecified: Boolean
    get() = this != androidx.compose.ui.unit.TextUnit.Unspecified
