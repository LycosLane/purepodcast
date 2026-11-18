package com.lycoslane.purepodcast.core.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import kotlin.math.min

/**
 * Media3 [DataSource] for HTTP/HTTPS that routes every open (and redirect hop) through
 * [NetworkSecurity.openValidatedConnection] so ExoPlayer cannot be redirected to private IPs
 * or downgraded from HTTPS to HTTP.
 */
@UnstableApi
class ValidatedHttpDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var currentUri: Uri? = null
    private var opened = false
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val rawUrl = dataSpec.uri.toString()
        val headers = HashMap<String, String>(dataSpec.httpRequestHeaders)
        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                (dataSpec.position + dataSpec.length - 1L).toString()
            } else {
                ""
            }
            headers["Range"] = "bytes=${dataSpec.position}-$end"
        }

        transferInitializing(dataSpec)
        val conn = NetworkSecurity.openValidatedConnection(
            rawUrl = rawUrl,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 30_000,
            headers = headers
        )
        connection = conn
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }

            currentUri = conn.url?.toString()?.let { Uri.parse(it) } ?: dataSpec.uri
            val stream = conn.inputStream
            if (dataSpec.position > 0L && code == HttpURLConnection.HTTP_OK) {
                var toSkip = dataSpec.position
                val skipBuf = ByteArray(8192)
                while (toSkip > 0L) {
                    val read = stream.read(skipBuf, 0, min(toSkip, skipBuf.size.toLong()).toInt())
                    if (read == -1) break
                    toSkip -= read
                }
                if (toSkip > 0L) {
                    runCatching { stream.close() }
                    throw IOException("Unexpected EOF while skipping to position ${dataSpec.position}")
                }
            }
            inputStream = stream
            val contentLen = conn.contentLengthLong
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                contentLen >= 0L && !(dataSpec.position > 0L && code == HttpURLConnection.HTTP_OK) -> contentLen
                contentLen >= 0L && dataSpec.position > 0L && code == HttpURLConnection.HTTP_OK ->
                    (contentLen - dataSpec.position).coerceAtLeast(0L)
                else -> C.LENGTH_UNSET.toLong()
            }
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Throwable) {
            runCatching { inputStream?.close() }
            inputStream = null
            closeConnectionQuietly()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesRemaining).toInt()
        }
        val read = stream.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            closeConnectionQuietly()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { connection?.disconnect() }
        connection = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = ValidatedHttpDataSource()
    }
}

@UnstableApi
object ValidatedMediaDataSource {
    fun factory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, ValidatedHttpDataSource.Factory())
}

/**
 * [BitmapLoader] for MediaSession artwork that validates remote URLs through [NetworkSecurity]
 * and bounds decoded bitmap size to 512x512.
 */
@UnstableApi
class ValidatedBitmapLoader(private val context: Context) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight, 512, 512)
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    ?: throw IOException("Could not decode bitmap")
                future.set(bmp)
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val scheme = uri.scheme?.lowercase()
                val bytes = if (scheme == "http" || scheme == "https") {
                    val conn = NetworkSecurity.openValidatedConnection(
                        rawUrl = uri.toString(),
                        connectTimeoutMs = 10_000,
                        readTimeoutMs = 15_000
                    )
                    try {
                        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                        BoundedInputStream(conn.inputStream, 10L * 1024 * 1024).use { it.readBytes() }
                    } finally {
                        conn.disconnect()
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BoundedInputStream(it, 10L * 1024 * 1024).readBytes()
                    } ?: throw IOException("Cannot open $uri")
                }
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calcSample(opts.outWidth, opts.outHeight, 512, 512)
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: throw IOException("Could not decode bitmap")
                future.set(bmp)
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    private fun calcSample(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        if (h > reqH || w > reqW) {
            val hh = h / 2
            val hw = w / 2
            while ((hh / s) >= reqH && (hw / s) >= reqW) s *= 2
        }
        return s
    }
}
