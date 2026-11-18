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

/** Records whether a URL was permanently moved (all redirect hops were 301/308). */
class RedirectTracker {
    @Volatile var permanentUrl: String? = null
}

object NetworkSecurity {

    private const val USER_AGENT = "PurePodcast/1.0 (Android)"
    private const val MAX_REDIRECTS = 5

    fun isValidHttpUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (uri.userInfo != null) return false // no credentials-in-URL / host confusion tricks
        val host = uri.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty()) return false
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare == "localhost" || bare.endsWith(".localhost") || bare.endsWith(".local") ||
            bare.endsWith(".internal")
        ) return false
        // Literal IPs (v4 or v6) are checked directly; names are checked on connect via DNS.
        val isLiteral = bare.contains(':') || bare.all { it.isDigit() || it == '.' }
        if (isLiteral) {
            val addr = runCatching { InetAddress.getByName(bare) }.getOrNull() ?: return false
            if (!isPublicAddress(addr)) return false
        }
        return true
    }

    /** True only for globally routable addresses (no loopback, private, CGNAT, link-local, ULA, multicast). */
    fun isPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return false
        val b = addr.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return false // CGNAT
            if (b0 == 0) return false
            if (b0 >= 240) return false // reserved + broadcast
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false // IETF + TEST-NET-1
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false // benchmarking
            if (b0 == 198 && b1 == 51 && b2 == 100) return false // TEST-NET-2
            if (b0 == 203 && b1 == 0 && b2 == 113) return false // TEST-NET-3
        } else if (b.size == 16) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return false // fc00::/7 unique local
            if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return false // fec0::/10 deprecated site-local
            if (b0 == 0x20 && b1 == 0x01 && b[2].toInt() == 0x0D && (b[3].toInt() and 0xFF) == 0xB8) return false // documentation
            // NAT64 64:ff9b::/96 embeds an IPv4 address: validate that one.
            val nat64 = b0 == 0x00 && b1 == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                (4..11).all { b[it].toInt() == 0 }
            if (nat64) {
                return runCatching {
                    isPublicAddress(InetAddress.getByAddress(byteArrayOf(b[12], b[13], b[14], b[15])))
                }.getOrDefault(false)
            }
        }
        return true
    }

    private fun requirePublicHost(rawUrl: String) {
        val host = runCatching { URI(rawUrl).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")
            ?: throw IOException("Invalid URL host")
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: throw IOException("Host could not be resolved")
        if (addrs.isEmpty() || addrs.any { !isPublicAddress(it) }) {
            throw IOException("Blocked non-public address")
        }
    }

    fun openValidatedConnection(
        rawUrl: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        headers: Map<String, String> = emptyMap(),
        redirectTracker: RedirectTracker? = null
    ): HttpURLConnection {
        var currentUrl = rawUrl.trim()
        var allPermanent = true
        var hops = 0
        for (hop in 0..MAX_REDIRECTS) {
            if (!isValidHttpUrl(currentUrl)) {
                throw IOException("Invalid or disallowed URL")
            }
            requirePublicHost(currentUrl)
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
                if (code != 301 && code != 308) allPermanent = false
                hops++
                currentUrl = resolved
                continue
            }
            if (allPermanent && hops > 0) redirectTracker?.permanentUrl = currentUrl
            return conn
        }
        throw IOException("Too many redirects")
    }

    /**
     * Follows redirects through [openValidatedConnection] (each hop validated) and returns the final
     * URL, so a media player can then fetch it without being redirected to an internal address.
     */
    fun resolveFinalUrl(rawUrl: String): String {
        val conn = openValidatedConnection(
            rawUrl = rawUrl,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
            headers = mapOf("Range" to "bytes=0-0")
        )
        return try {
            conn.url?.toString()?.takeIf { isValidHttpUrl(it) } ?: rawUrl
        } finally {
            conn.disconnect()
        }
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
