package com.lycoslane.purepodcast.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress

class NetworkSecurityTest {

    @Test
    fun rejectsNonHttpAndLocalUrls() {
        assertFalse(NetworkSecurity.isValidHttpUrl(""))
        assertFalse(NetworkSecurity.isValidHttpUrl("file:///etc/passwd"))
        assertFalse(NetworkSecurity.isValidHttpUrl("ftp://example.com/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("http://localhost/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://127.0.0.1/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://10.0.0.1/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://192.168.1.1/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://[::1]/feed.xml"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://feed.internal/rss"))
        assertFalse(NetworkSecurity.isValidHttpUrl("https://user:pass@example.com/rss"))
    }

    @Test
    fun acceptsPublicHttpUrls() {
        assertTrue(NetworkSecurity.isValidHttpUrl("https://example.com/podcast.xml"))
        assertTrue(NetworkSecurity.isValidHttpUrl("http://feeds.example.org/rss?id=1"))
    }

    @Test
    fun blocksPrivateAndReservedIpAddresses() {
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("10.1.2.3")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("172.16.0.1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("192.168.0.1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("198.18.0.1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("::1")))
        assertFalse(NetworkSecurity.isPublicAddress(InetAddress.getByName("fc00::1")))
        assertTrue(NetworkSecurity.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(NetworkSecurity.isPublicAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun boundedInputStreamAllowsWithinLimitAndRejectsOverflow() {
        val ok = BoundedInputStream(ByteArrayInputStream(ByteArray(10)), 10L)
        assertEquals(10, ok.readBytes().size)

        val overflow = BoundedInputStream(ByteArrayInputStream(ByteArray(11)), 10L)
        var threw = false
        try {
            overflow.readBytes()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }
}
