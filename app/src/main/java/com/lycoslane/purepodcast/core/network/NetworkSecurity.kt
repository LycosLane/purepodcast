package com.lycoslane.purepodcast.core.network

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

object NetworkSecurity {

    private const val USER_AGENT = "PurePodcast/0.1 (Android)"
    private const val MAX_REDIRECTS = 5

    fun isValidHttpUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") {
            return false
        }
        if (host.startsWith("169.254.") || host.startsWith("10.") || host.startsWith("192.168.")) {
            return false
        }
        // Reject raw IPv4 loopback/link-local/private if literal
        val ipCheck = runCatching {
            if (host.all { it.isDigit() || it == '.' }) {
                val addr = InetAddress.getByName(host)
                addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress
            } else {
                false
            }
        }.getOrDefault(false)
        return !ipCheck
    }

    fun openValidatedConnection(
        rawUrl: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        headers: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        var currentUrl = rawUrl.trim()
        for (hop in 0..MAX_REDIRECTS) {
            if (!isValidHttpUrl(currentUrl)) {
                throw IOException("Invalid or disallowed URL")
            }
            val conn = (URL(currentUrl).openConnection() as? HttpURLConnection)
                ?: throw IOException("Unsupported protocol")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect without Location header")
                }
                val resolved = runCatching { URI(currentUrl).resolve(location).toString() }
                    .getOrElse { throw IOException("Malformed redirect URL") }
                // Reject HTTPS -> HTTP downgrade
                if (currentUrl.startsWith("https://", ignoreCase = true) &&
                    resolved.startsWith("http://", ignoreCase = true)
                ) {
                    throw IOException("Blocked insecure HTTPS-to-HTTP redirect")
                }
                currentUrl = resolved
                continue
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    fun newSafeXmlPullParser(): XmlPullParser {
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
            runCatching { setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
        }
    }
}

class BoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long
) : FilterInputStream(delegate) {
    private var bytesRead: Long = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            bytesRead++
            if (bytesRead > maxBytes) throw IOException("Stream exceeds maximum allowed size")
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            bytesRead += count
            if (bytesRead > maxBytes) throw IOException("Stream exceeds maximum allowed size")
        }
        return count
    }
}
