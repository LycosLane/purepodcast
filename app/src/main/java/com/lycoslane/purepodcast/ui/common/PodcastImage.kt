package com.lycoslane.purepodcast.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.lycoslane.purepodcast.core.network.BoundedInputStream
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private object NativeImageCache {
    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    private const val MAX_DISK_CACHE_BYTES = 64L * 1024 * 1024
    private const val FAILED_TTL_MS = 5 * 60_000L

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val failedUrls = ConcurrentHashMap<String, Long>()

    private val memoryCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    fun getCached(url: String): ImageBitmap? =
        if (url.isBlank()) null else memoryCache.get(url)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        val isLocal = url.startsWith("content://") || url.startsWith("/") || url.startsWith("file://")
        if (!isLocal && !NetworkSecurity.isValidHttpUrl(url)) return null
        memoryCache.get(url)?.let { return it }

        val failedAt = failedUrls[url]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_TTL_MS) {
            return null
        }

        val deferred = inFlight.computeIfAbsent(url) {
            cacheScope.async {
                try {
                    loadInternal(context.applicationContext, url)
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        return deferred.await()
    }

    private fun loadInternal(appContext: Context, url: String): ImageBitmap? {
        memoryCache.get(url)?.let { return it }

        if (url.startsWith("content://")) {
            val bitmap = runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            return if (bitmap != null) {
                failedUrls.remove(url)
                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(url, imageBitmap)
                imageBitmap
            } else {
                failedUrls[url] = System.currentTimeMillis()
                null
            }
        }

        if (url.startsWith("/") || url.startsWith("file://")) {
            val filePath = if (url.startsWith("file://")) android.net.Uri.parse(url).path ?: "" else url
            val localFile = File(filePath)
            val bitmap = if (localFile.exists()) decodeSampledFile(localFile) else null
            return if (bitmap != null) {
                failedUrls.remove(url)
                val imageBitmap = bitmap.asImageBitmap()
                memoryCache.put(url, imageBitmap)
                imageBitmap
            } else {
                failedUrls[url] = System.currentTimeMillis()
                null
            }
        }

        val cacheDir = File(appContext.cacheDir, "artwork").apply { mkdirs() }
        val file = File(cacheDir, sha256(url))

        val bitmap = if (file.exists() && file.length() > 0L) {
            decodeSampledFile(file) ?: run {
                file.delete()
                downloadAndCache(url, cacheDir, file)
            }
        } else {
            downloadAndCache(url, cacheDir, file)
        }

        return if (bitmap != null) {
            failedUrls.remove(url)
            val imageBitmap = bitmap.asImageBitmap()
            memoryCache.put(url, imageBitmap)
            imageBitmap
        } else {
            failedUrls[url] = System.currentTimeMillis()
            null
        }
    }

    private fun downloadAndCache(url: String, cacheDir: File, target: File): Bitmap? {
        var conn: HttpURLConnection? = null
        val rawTemp = File(cacheDir, "${target.name}.${System.nanoTime()}.raw.tmp")
        val compressedTemp = File(cacheDir, "${target.name}.${System.nanoTime()}.jpg.tmp")
        return try {
            conn = NetworkSecurity.openValidatedConnection(
                rawUrl = url,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 10_000
            )
            if (conn.responseCode !in 200..299) return null

            BoundedInputStream(conn.inputStream, MAX_IMAGE_BYTES).use { input ->
                rawTemp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val decoded = decodeSampledFile(rawTemp) ?: return null
            runCatching {
                compressedTemp.outputStream().use { out ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                if (compressedTemp.exists() && compressedTemp.length() > 0L) {
                    compressedTemp.renameTo(target)
                    trimDiskCacheIfNeeded(cacheDir)
                }
            }
            decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            rawTemp.delete()
            compressedTemp.delete()
            conn?.disconnect()
        }
    }

    private fun decodeSampledFile(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, 512, 512)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun trimDiskCacheIfNeeded(cacheDir: File) {
        val files = cacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_CACHE_BYTES) return
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (total <= MAX_DISK_CACHE_BYTES * 3 / 4) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun PodcastImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    var imageBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        imageBitmap = NativeImageCache.load(context, url)
    }

    val bmp = imageBitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Podcasts,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
