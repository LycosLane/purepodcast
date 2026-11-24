package com.lycoslane.purepodcast.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

object NetworkUtils {

    fun isOnUnmeteredWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun fetchContentLengthBytes(url: String): Long = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = NetworkSecurity.openValidatedConnection(
                rawUrl = url,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
                method = "HEAD"
            )
            conn.contentLengthLong.takeIf { it > 0L } ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }
}
