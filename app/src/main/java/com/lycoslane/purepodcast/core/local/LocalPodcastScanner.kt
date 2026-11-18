package com.lycoslane.purepodcast.core.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.lycoslane.purepodcast.core.database.DownloadState
import com.lycoslane.purepodcast.core.database.EpisodeEntity
import com.lycoslane.purepodcast.core.database.PodcastEntity
import com.lycoslane.purepodcast.core.database.PurePodcastDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object LocalPodcastScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
    private val COVER_FILENAMES = setOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "artwork.jpg", "artwork.jpeg", "artwork.png"
    )

    private data class DocEntry(
        val docId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val uri: Uri
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun hasWritePermission(context: Context, treeUriString: String): Boolean {
        if (treeUriString.isBlank()) return false
        val target = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == target && it.isWritePermission
        }
    }

    fun deleteLocalDocument(context: Context, docUriString: String): Boolean {
        if (!docUriString.startsWith("content://")) return false
        return runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(docUriString))
        }.getOrDefault(false)
    }

    suspend fun syncLocalFolder(
        context: Context,
        dao: PurePodcastDao,
        treeUriString: String
    ): Int = withContext(Dispatchers.IO) {
        if (treeUriString.isBlank()) {
            // Remove previously scanned local podcasts if folder was disconnected
            dao.getLocalPodcasts().forEach { dao.deletePodcast(it.id) }
            return@withContext 0
        }

        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return@withContext 0
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext 0

        val rootChildren = queryChildren(context, treeUri, rootDocId)
        val folders = rootChildren.filter { it.isDirectory }
        val rootAudioFiles = rootChildren.filter { !it.isDirectory && isAudioFile(it) }

        val discoveredFeedUrls = mutableSetOf<String>()
        var syncedCount = 0

        // 1. Each subfolder becomes a local Podcast
        for (folder in folders) {
            val children = queryChildren(context, treeUri, folder.docId)
            val audioFiles = children.filter { !it.isDirectory && isAudioFile(it) }
            if (audioFiles.isEmpty()) continue

            val feedUrl = "local://${folder.docId}"
            discoveredFeedUrls += feedUrl
            val coverUri = findCoverUri(context, folder.docId, children, audioFiles)
            upsertLocalPodcast(
                context = context,
                dao = dao,
                feedUrl = feedUrl,
                title = folder.name,
                artworkUrl = coverUri,
                audioFiles = audioFiles
            )
            syncedCount++
        }

        // 2. Any audio files directly in the root folder become one local podcast
        if (rootAudioFiles.isNotEmpty()) {
            val rootFeedUrl = "local://$rootDocId"
            discoveredFeedUrls += rootFeedUrl
            val rootTitle = queryDocumentName(context, treeUri, rootDocId)
                ?.takeIf { it.isNotBlank() }
                ?: "Local Podcasts"
            val coverUri = findCoverUri(context, rootDocId, rootChildren, rootAudioFiles)
            upsertLocalPodcast(
                context = context,
                dao = dao,
                feedUrl = rootFeedUrl,
                title = rootTitle,
                artworkUrl = coverUri,
                audioFiles = rootAudioFiles
            )
            syncedCount++
        }

        // Remove stale local podcasts no longer present in the folder
        dao.getLocalPodcasts()
            .filter { it.feedUrl !in discoveredFeedUrls }
            .forEach { dao.deletePodcast(it.id) }

        syncedCount
    }

    private suspend fun upsertLocalPodcast(
        context: Context,
        dao: PurePodcastDao,
        feedUrl: String,
        title: String,
        artworkUrl: String,
        audioFiles: List<DocEntry>
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getPodcastByFeedUrl(feedUrl)
        val podcastId = if (existing == null) {
            dao.insertPodcast(
                PodcastEntity(
                    feedUrl = feedUrl,
                    title = title,
                    author = "Local Folder",
                    description = "",
                    artworkUrl = artworkUrl,
                    lastRefreshedMs = now,
                    isLocal = true
                )
            )
        } else {
            dao.updatePodcastMetadata(
                podcastId = existing.id,
                title = title,
                author = existing.author.ifBlank { "Local Folder" },
                description = existing.description,
                artworkUrl = artworkUrl.ifBlank { existing.artworkUrl },
                lastRefreshedMs = now
            )
            existing.id
        }

        if (podcastId <= 0L) return

        val toInsert = mutableListOf<EpisodeEntity>()
        for (doc in audioFiles) {
            val guid = doc.docId
            val existingEp = dao.getEpisodeByGuid(podcastId, guid)
            if (existingEp != null) {
                val docUriStr = doc.uri.toString()
                if (existingEp.localFilePath != docUriStr || existingEp.downloadState != DownloadState.DOWNLOADED) {
                    dao.updateDownloadState(
                        episodeId = existingEp.id,
                        state = DownloadState.DOWNLOADED,
                        progress = 100,
                        localPath = docUriStr
                    )
                }
                continue
            }

            val meta = extractAudioMetadata(context, doc.uri, doc.name)
            toInsert += EpisodeEntity(
                podcastId = podcastId,
                guid = guid,
                title = meta.title,
                descriptionHtml = "",
                audioUrl = doc.uri.toString(),
                artworkUrl = artworkUrl,
                pubDateMs = doc.lastModifiedMs.takeIf { it > 0L } ?: now,
                durationMs = meta.durationMs,
                localFilePath = doc.uri.toString(),
                downloadState = DownloadState.DOWNLOADED,
                downloadProgress = 100,
                fileSizeBytes = doc.sizeBytes,
                isTranscoded = true
            )
        }

        if (toInsert.isNotEmpty()) {
            dao.insertEpisodes(toInsert)
        }
    }

    private fun findCoverUri(
        context: Context,
        folderDocId: String,
        allChildren: List<DocEntry>,
        audioFiles: List<DocEntry>
    ): String {
        val coverDoc = allChildren.firstOrNull { !it.isDirectory && it.name.lowercase() in COVER_FILENAMES }
        if (coverDoc != null) return coverDoc.uri.toString()

        // Fallback: extract embedded artwork from the first audio file and cache locally
        val firstAudio = audioFiles.firstOrNull() ?: return ""
        val cacheDir = File(context.filesDir, "local_covers").apply { mkdirs() }
        val hash = sha256(folderDocId)
        val cachedFile = File(cacheDir, "$hash.jpg")
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            return cachedFile.absolutePath
        }

        val embeddedBytes = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, firstAudio.uri)
                mmr.embeddedPicture
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()

        if (embeddedBytes != null && embeddedBytes.isNotEmpty() && embeddedBytes.size <= 10 * 1024 * 1024) {
            runCatching { cachedFile.writeBytes(embeddedBytes) }
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                return cachedFile.absolutePath
            }
        }
        return ""
    }

    private data class AudioMeta(val title: String, val durationMs: Long)

    private fun extractAudioMetadata(context: Context, uri: Uri, fallbackName: String): AudioMeta {
        val cleanFallback = fallbackName.substringBeforeLast('.').ifBlank { fallbackName }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cleanFallback
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            AudioMeta(title = title, durationMs = durationMs)
        } catch (_: Throwable) {
            AudioMeta(title = cleanFallback, durationMs = 0L)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun isAudioFile(doc: DocEntry): Boolean {
        if (doc.mimeType.startsWith("audio/")) return true
        val ext = doc.name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun queryDocumentName(context: Context, treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryChildren(context: Context, treeUri: Uri, parentDocId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<DocEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) ?: continue else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: docId else docId
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result += DocEntry(docId, name, mime, size, mod, docUri)
                }
            }
        }
        return result
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
