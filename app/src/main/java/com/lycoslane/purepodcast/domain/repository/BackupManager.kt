package com.lycoslane.purepodcast.domain.repository

import android.content.Context
import com.lycoslane.purepodcast.core.audio.AudioMonoMode
import com.lycoslane.purepodcast.core.audio.AudioOverride
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDao
import com.lycoslane.purepodcast.core.download.FeedRefreshWorker
import com.lycoslane.purepodcast.core.network.NetworkSecurity
import com.lycoslane.purepodcast.core.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class BackupManager(
    private val context: Context,
    private val dao: PurePodcastDao,
    private val preferences: AppPreferences,
    private val subscribeByFeedUrl: suspend (String) -> Long
) {

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val settings = preferences.settings.value
        val root = JSONObject().apply {
            put("version", 1)
            put("exportedAtMs", System.currentTimeMillis())
            put(
                "settings",
                JSONObject().apply {
                    put("refreshIntervalMinutes", settings.refreshIntervalMinutes)
                    put("refreshIntervalHours", settings.refreshIntervalMinutes / 60)
                    put("wifiOnlyDownloads", settings.wifiOnlyDownloads)
                    put("autoDownloadNewByDefault", settings.autoDownloadNewByDefault)
                    put("downloadWhenQueued", settings.downloadWhenQueued)
                    put("addToQueueWhenDownloaded", settings.addToQueueWhenDownloaded)
                    put("autoRemovePlayedDays", settings.autoRemovePlayedDays)
                    put("trashRetentionHours", settings.trashRetentionHours)
                    put("maxDownloadsPerPodcast", settings.maxDownloadsPerPodcast)
                    put("smartRewindSeconds", settings.smartRewindSeconds)
                    put("audioNormalizationEnabled", settings.audioNormalizationEnabled)
                    put("monoMode", settings.monoMode)
                    put("bluetoothNextPrevSeeks", settings.bluetoothNextPrevSeeks)
                    put("textScale", settings.textScale.toDouble())
                    put("appLanguage", settings.appLanguage)
                    put("transcodeBitrateKbps", settings.transcodeBitrateKbps)
                    put("transcodeAutoEnabled", settings.transcodeAutoEnabled)
                    put("transcodeChargingOnly", settings.transcodeChargingOnly)
                    put("themeMode", settings.themeMode)
                    put("lightAccent", settings.lightAccent)
                    put("darkAccent", settings.darkAccent)
                }
            )
            val podcastsArr = JSONArray()
            dao.getAllPodcasts().filter { !it.isLocal && it.isSubscribed }.forEach { pod ->
                val epArr = JSONArray()
                dao.getEpisodesWithProgress(pod.id).forEach { ep ->
                    epArr.put(
                        JSONObject().apply {
                            put("guid", ep.guid)
                            put("positionMs", ep.positionMs)
                            put("isPlayed", ep.isPlayed)
                            put("isHidden", ep.isHidden)
                            put("isFavorite", ep.isFavorite)
                            put("playedAtMs", ep.playedAtMs)
                            if (ep.monoModeOverride != AudioMonoMode.INHERIT) {
                                put("monoModeOverride", ep.monoModeOverride)
                            }
                        }
                    )
                }
                podcastsArr.put(
                    JSONObject().apply {
                        put("feedUrl", pod.feedUrl)
                        put("autoDownloadFromPubDateMs", pod.autoDownloadFromPubDateMs)
                        put("sortAscending", pod.sortAscending)
                        put("playbackSpeed", pod.playbackSpeed.toDouble())
                        put("skipSilence", pod.skipSilence)
                        put("silenceIntensity", pod.silenceIntensity)
                        put("monoModeOverride", pod.monoModeOverride)
                        put("audioNormalizationOverride", pod.audioNormalizationOverride)
                        put("episodes", epArr)
                    }
                )
            }
            put("podcasts", podcastsArr)
            val queueArr = JSONArray()
            dao.getQueueEpisodesOnce().forEach { item ->
                if (!item.podcast.isLocal) {
                    queueArr.put(
                        JSONObject().apply {
                            put("feedUrl", item.podcast.feedUrl)
                            put("guid", item.episode.guid)
                        }
                    )
                }
            }
            put("queue", queueArr)
        }
        root.toString(2)
    }

    suspend fun importBackupJson(jsonText: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonText)
        val version = root.optInt("version", 1)
        if (version < 1 || version > 1) error("Unsupported backup version")

        root.optJSONObject("settings")?.let { s ->
            importSettingsObject(s)
        }

        val podcastsArr = root.optJSONArray("podcasts") ?: return@withContext 0
        var restored = 0
        val feedUrlToPodcastId = mutableMapOf<String, Long>()
        val maxPodcasts = minOf(podcastsArr.length(), 500)
        for (i in 0 until maxPodcasts) {
            val pObj = podcastsArr.optJSONObject(i) ?: continue
            val feedUrl = pObj.optString("feedUrl", "").trim()
            if (!NetworkSecurity.isValidHttpUrl(feedUrl)) continue
            try {
                val podcastId = runCatching { subscribeByFeedUrl(feedUrl) }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    dao.getPodcastByFeedUrl(feedUrl)?.id ?: dao.insertPodcast(
                        PodcastEntity(
                            feedUrl = feedUrl,
                            title = pObj.optString("title", "").ifBlank { feedUrl },
                            isSubscribed = true
                        )
                    )
                }
                if (podcastId <= 0L) continue
                feedUrlToPodcastId[feedUrl] = podcastId
                val existing = dao.getPodcast(podcastId)
                if (existing != null) {
                    val rawSpeed = pObj.optDouble("playbackSpeed", existing.playbackSpeed.toDouble())
                    val safeSpeed = if (rawSpeed.isFinite()) rawSpeed.toFloat().coerceIn(0.50f, 3.0f) else 1.0f
                    dao.updatePodcast(
                        existing.copy(
                            autoDownloadFromPubDateMs = pObj.optLong("autoDownloadFromPubDateMs", existing.autoDownloadFromPubDateMs).coerceAtLeast(0L),
                            sortAscending = pObj.optBoolean("sortAscending", existing.sortAscending),
                            playbackSpeed = safeSpeed,
                            skipSilence = pObj.optBoolean("skipSilence", existing.skipSilence),
                            silenceIntensity = pObj.optInt("silenceIntensity", existing.silenceIntensity).coerceIn(0, 2),
                            monoModeOverride = if (pObj.has("monoModeOverride")) {
                                AudioMonoMode.normalizeOverride(pObj.optInt("monoModeOverride", existing.monoModeOverride))
                            } else {
                                existing.monoModeOverride
                            },
                            audioNormalizationOverride = if (pObj.has("audioNormalizationOverride")) {
                                AudioOverride.normalize(pObj.optInt("audioNormalizationOverride", existing.audioNormalizationOverride))
                            } else {
                                existing.audioNormalizationOverride
                            }
                        )
                    )
                }
                val epArr = pObj.optJSONArray("episodes")
                if (epArr != null) {
                    for (j in 0 until minOf(epArr.length(), 5000)) {
                        val eObj = epArr.optJSONObject(j) ?: continue
                        val guid = eObj.optString("guid", "").take(512)
                        if (guid.isEmpty()) continue
                        val ep = dao.getEpisodeByGuid(podcastId, guid) ?: continue
                        dao.updatePosition(ep.id, eObj.optLong("positionMs", ep.positionMs).coerceAtLeast(0L))
                        val restoredPlayed = eObj.optBoolean("isPlayed", ep.isPlayed)
                        val restoredPlayedAt = if (restoredPlayed) {
                            eObj.optLong("playedAtMs", 0L).takeIf { it > 0L }
                                ?: ep.playedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else 0L
                        dao.setPlayed(ep.id, restoredPlayed, restoredPlayedAt)
                        if (eObj.has("isFavorite")) {
                            dao.setFavorite(ep.id, eObj.optBoolean("isFavorite", false))
                        }
                        if (eObj.has("isHidden")) {
                            dao.setHidden(ep.id, eObj.optBoolean("isHidden", ep.isHidden))
                        }
                        if (eObj.has("monoModeOverride")) {
                            dao.setEpisodeMonoModeOverride(
                                ep.id,
                                AudioMonoMode.normalizeOverride(eObj.optInt("monoModeOverride", ep.monoModeOverride))
                            )
                        }
                    }
                }
                restored++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val queueArr = root.optJSONArray("queue")
        if (queueArr != null && dao.getQueueEpisodesOnce().isEmpty()) {
            for (k in 0 until minOf(queueArr.length(), 500)) {
                val qObj = queueArr.optJSONObject(k) ?: continue
                val feedUrl = qObj.optString("feedUrl", "").trim()
                val guid = qObj.optString("guid", "").take(512)
                if (feedUrl.isEmpty() || guid.isEmpty()) continue
                val podId = feedUrlToPodcastId[feedUrl] ?: dao.getPodcastByFeedUrl(feedUrl)?.id ?: continue
                val ep = dao.getEpisodeByGuid(podId, guid) ?: continue
                dao.enqueueToQueue(ep.id)
            }
        }
        restored
    }

    private fun importSettingsObject(s: JSONObject) {
        when {
            s.has("refreshIntervalMinutes") -> {
                val mins = s.optInt("refreshIntervalMinutes", 360)
                preferences.setRefreshIntervalMinutes(mins)
                FeedRefreshWorker.schedulePeriodic(
                    context,
                    preferences.settings.value.refreshIntervalMinutes,
                    replaceExisting = true
                )
            }
            s.has("refreshIntervalHours") -> {
                val hours = s.optInt("refreshIntervalHours", 6)
                val mins = if (hours <= 0) 0 else (hours * 60)
                preferences.setRefreshIntervalMinutes(mins)
                FeedRefreshWorker.schedulePeriodic(
                    context,
                    preferences.settings.value.refreshIntervalMinutes,
                    replaceExisting = true
                )
            }
        }
        if (s.has("wifiOnlyDownloads")) preferences.setWifiOnlyDownloads(s.optBoolean("wifiOnlyDownloads", true))
        if (s.has("autoDownloadNewByDefault")) preferences.setAutoDownloadNewByDefault(s.optBoolean("autoDownloadNewByDefault", false))
        if (s.has("downloadWhenQueued")) preferences.setDownloadWhenQueued(s.optBoolean("downloadWhenQueued", false))
        if (s.has("addToQueueWhenDownloaded")) preferences.setAddToQueueWhenDownloaded(s.optBoolean("addToQueueWhenDownloaded", false))
        if (s.has("autoRemovePlayedDays")) {
            val days = s.optInt("autoRemovePlayedDays", 1).let { if (it in listOf(-1, 0, 1, 3, 7)) it else 1 }
            preferences.setAutoRemovePlayedDays(days)
        }
        if (s.has("trashRetentionHours")) {
            val hours = s.optInt("trashRetentionHours", 24).let { if (it in listOf(0, 24, 48)) it else 24 }
            preferences.setTrashRetentionHours(hours)
        }
        if (s.has("maxDownloadsPerPodcast")) {
            preferences.setMaxDownloadsPerPodcast(s.optInt("maxDownloadsPerPodcast", 0).coerceIn(0, 30))
        }
        if (s.has("smartRewindSeconds")) {
            preferences.setSmartRewindSeconds(s.optInt("smartRewindSeconds", 10).coerceIn(0, 120))
        }
        if (s.has("audioNormalizationEnabled")) {
            preferences.setAudioNormalizationEnabled(s.optBoolean("audioNormalizationEnabled", false))
        }
        if (s.has("monoMode")) {
            preferences.setMonoMode(s.optInt("monoMode", AudioMonoMode.OFF))
        }
        if (s.has("bluetoothNextPrevSeeks")) {
            preferences.setBluetoothNextPrevSeeks(s.optBoolean("bluetoothNextPrevSeeks", true))
        }
        if (s.has("textScale")) {
            val scale = s.optDouble("textScale", 1.0)
            if (scale.isFinite()) preferences.setTextScale(scale.toFloat())
        }
        if (s.has("appLanguage")) {
            val lang = s.optString("appLanguage", "en")
            if (lang in listOf("en", "de")) preferences.setAppLanguage(lang)
        }
        if (s.has("transcodeBitrateKbps")) {
            preferences.setTranscodeBitrateKbps(s.optInt("transcodeBitrateKbps", 0))
        }
        if (s.has("transcodeAutoEnabled")) preferences.setTranscodeAutoEnabled(s.optBoolean("transcodeAutoEnabled", false))
        if (s.has("transcodeChargingOnly")) preferences.setTranscodeChargingOnly(s.optBoolean("transcodeChargingOnly", true))
        if (s.has("themeMode")) preferences.setThemeMode(s.optString("themeMode", AppPreferences.THEME_SYSTEM))
        if (s.has("lightAccent")) preferences.setLightAccent(s.optString("lightAccent", AppPreferences.DEFAULT_ACCENT))
        if (s.has("darkAccent")) preferences.setDarkAccent(s.optString("darkAccent", AppPreferences.DEFAULT_ACCENT))
    }
}
