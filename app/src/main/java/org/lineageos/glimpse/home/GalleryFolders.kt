/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.SQLException
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A physical directory. Its identity includes the volume and complete relative path. */
data class GalleryFolder(
    val volume: String,
    val path: String,
    val cover: GalleryPhoto? = null,
    val count: Int = 0,
    val totalCount: Int = count,
    val activeCount: Int = count,
) {
    val id get() = "${Uri.encode(volume)}:${Uri.encode(path)}"
    val name get() = path.trimEnd('/').substringAfterLast('/')
    val canModify get() = GalleryFolders.safePath(path) && path.trimEnd('/').contains('/')
    val canReceive get() = GalleryFolders.safePath(path)
}

enum class GalleryFolderError {
    INVALID_NAME, EXISTS, NOT_FOUND, NEEDS_ACCESS, CONTAINS_OTHER_FILES, IO,
}

data class GalleryFolderResult(
    val succeeded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val error: GalleryFolderError? = null,
    val folder: GalleryFolder? = null,
    val failedKeys: Set<String> = emptySet(),
)

/**
 * Folder mutations use the system gallery's FUSE checks and MediaStore transactions. No recursive
 * File deletion is used: only indexed images/videos are deleted, followed by empty directories.
 * All callers, including separate GalleryStore instances, share the mutation lock.
 */
class GalleryFolders(
    private val context: Context,
    private val preferences: SharedPreferences,
) {
    private val resolver = context.contentResolver

    /** Called on the IO dispatcher by GalleryStore. Existing empty public media directories remain. */
    fun list(photos: List<GalleryPhoto>): List<GalleryFolder> {
        val groups = photos.filterNot { it.media.isTrashed }.groupBy { it.folderId }
        val totals = mutableMapOf<String, Int>()
        val activeTotals = mutableMapOf<String, Int>()
        val descendantCovers = mutableMapOf<String, GalleryPhoto>()
        photos.forEach { photo ->
            if (!validRelativePath(photo.path)) return@forEach
            var path = photo.path.trimEnd('/')
            while (path.isNotEmpty()) {
                val id = GalleryFolder(photo.volume, "$path/").id
                totals[id] = totals.getOrDefault(id, 0) + 1
                if (!photo.media.isTrashed) {
                    activeTotals[id] = activeTotals.getOrDefault(id, 0) + 1
                    val previous = descendantCovers[id]
                    if (previous == null || photo.taken > previous.taken) descendantCovers[id] = photo
                }
                path = path.substringBeforeLast('/', "")
            }
        }
        val result = linkedMapOf<String, GalleryFolder>()
        fun add(volume: String, path: String) {
            if (!validRelativePath(path)) return
            val folder = GalleryFolder(volume, path)
            if (folder.id in result) return
            val items = groups[folder.id].orEmpty()
            result[folder.id] = folder.copy(count = items.size, totalCount = totals[folder.id] ?: 0,
                activeCount = activeTotals[folder.id] ?: 0,
                cover = items.maxByOrNull { it.taken } ?: descendantCovers[folder.id])
        }
        photos.filterNot { it.media.isTrashed }.forEach { photo ->
            if (validRelativePath(photo.path)) {
                add(photo.volume, photo.path)
                var parent = photo.path.trimEnd('/').substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    add(photo.volume, "$parent/")
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
        roots().forEach { (volume, root) ->
            val queue = ArrayDeque<File>()
            PUBLIC_DIRECTORIES.forEach { name ->
                File(root, name).takeIf { it.isDirectory }?.let(queue::add)
            }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < MAX_ENTRIES) {
                val directory = queue.removeFirst()
                val path = directory.relativeTo(root).invariantSeparatorsPath + "/"
                if (!safePath(path) || !contained(root, directory)) continue
                add(volume, path)
                directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }
                    ?.forEach(queue::add)
            }
        }
        knownFolders().forEach { folder ->
            runCatching { directory(folder) }.getOrNull()?.takeIf { it.isDirectory }
                ?.let { add(folder.volume, folder.path) }
        }
        return result.values.sortedWith(compareBy<GalleryFolder> { it.name.lowercase() }
            .thenBy { it.volume }.thenBy { it.path })
    }

    fun refresh() {
        preferences.edit().putLong(CHANGED, System.nanoTime()).apply()
    }

    suspend fun create(
        name: String,
        parent: GalleryFolder? = null,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    ): GalleryFolderResult = mutate {
        if (!validName(name)) return@mutate failure(GalleryFolderError.INVALID_NAME)
        if (parent != null && !parent.canReceive) return@mutate failure(GalleryFolderError.NEEDS_ACCESS)
        val folder = GalleryFolder(parent?.volume ?: volume,
            (parent?.path ?: "${Environment.DIRECTORY_PICTURES}/") + name + "/")
        val target = directory(folder)
        if (target.exists()) return@mutate failure(GalleryFolderError.EXISTS)
        val parentFile = target.parentFile ?: return@mutate failure(GalleryFolderError.INVALID_NAME)
        if (parent != null && !parentFile.isDirectory) return@mutate failure(GalleryFolderError.NOT_FOUND)
        if (!parentFile.isDirectory && !parentFile.mkdirs()) return@mutate failure(GalleryFolderError.IO)
        if (!target.mkdir()) return@mutate failure(
            if (target.exists()) GalleryFolderError.EXISTS else GalleryFolderError.NEEDS_ACCESS)
        remember(knownFolders() + folder)
        GalleryFolderResult(succeeded = 1, folder = folder)
    }

    suspend fun rename(folder: GalleryFolder, name: String): GalleryFolderResult = mutate {
        if (!validName(name)) return@mutate failure(GalleryFolderError.INVALID_NAME)
        if (!folder.canModify) return@mutate failure(GalleryFolderError.NEEDS_ACCESS)
        val source = directory(folder)
        if (!source.isDirectory) return@mutate failure(GalleryFolderError.NOT_FOUND)
        if (folder.name == name) return@mutate GalleryFolderResult(skipped = 1, folder = folder)
        val renamed = folder.copy(path = folder.path.trimEnd('/').substringBeforeLast('/') + "/$name/")
        val target = directory(renamed)
        if (target.exists()) return@mutate failure(GalleryFolderError.EXISTS)
        val snapshot = mediaTree(folder, source)
        if (snapshot.hasOtherFiles) return@mutate failure(GalleryFolderError.CONTAINS_OTHER_FILES)
        // MediaProvider validates the complete tree again, including files hidden from our FUSE
        // listing, before atomically renaming the directory and updating every media row.
        if (!source.renameTo(target)) return@mutate failure(GalleryFolderError.NEEDS_ACCESS)
        remember(knownFolders().map {
            if (it.volume == folder.volume && it.path.startsWith(folder.path))
                it.copy(path = renamed.path + it.path.removePrefix(folder.path)) else it
        } + renamed)
        scan(listOf(target.absolutePath))
        GalleryFolderResult(succeeded = 1, folder = renamed.copy(count = folder.count,
            totalCount = folder.totalCount, cover = folder.cover))
    }

    /** The UI must confirm permanent deletion before calling this method. */
    suspend fun delete(folder: GalleryFolder): GalleryFolderResult = mutate {
        if (!folder.canModify) return@mutate failure(GalleryFolderError.NEEDS_ACCESS)
        val source = directory(folder)
        if (!source.isDirectory) return@mutate failure(GalleryFolderError.NOT_FOUND)
        val tree = mediaTree(folder, source)
        if (tree.hasOtherFiles) return@mutate failure(GalleryFolderError.CONTAINS_OTHER_FILES)
        var succeeded = 0
        var failed = 0
        tree.media.forEach { item ->
            coroutineContext.ensureActive()
            try {
                // Require the row to still describe exactly the media file that was checked.
                val live = readItem(item.uri)
                if (live == null || live.file != item.file || !live.path.startsWith(folder.path) ||
                    !contained(source, live.file)) {
                    failed++
                } else if (resolver.delete(item.uri, Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }) == 1) {
                    succeeded++
                } else failed++
            } catch (_: SecurityException) { failed++ }
            catch (_: IOException) { failed++ }
            catch (_: IllegalArgumentException) { failed++ }
            catch (_: SQLException) { failed++ }
        }
        // File.delete() only removes empty directories. Files created concurrently, unindexed
        // files and non-media content are kept; never call deleteRecursively or delete file paths.
        tree.directories.sortedByDescending { it.absolutePath.length }.forEach {
            if (it.isDirectory && contained(source, it)) it.delete()
        }
        val removed = !source.exists()
        if (removed) {
            remember(knownFolders().filterNot {
                it.volume == folder.volume && it.path.startsWith(folder.path)
            })
            // FUSE rmdir leaves directory rows behind; reconcile only this removed subtree.
            scan(listOf(source.absolutePath))
        } else refresh()
        GalleryFolderResult(succeeded = if (tree.media.isEmpty() && removed) 1 else succeeded,
            failed = failed + if (removed) 0 else 1,
            error = if (removed && failed == 0) null else GalleryFolderError.CONTAINS_OTHER_FILES)
    }

    suspend fun transfer(
        photos: List<GalleryPhoto>,
        target: GalleryFolder,
        copy: Boolean,
    ): GalleryFolderResult = mutate {
        val requestedKeys = photos.map { it.key }.toSet()
        if (!target.canReceive) return@mutate failure(
            GalleryFolderError.NEEDS_ACCESS, photos.size, requestedKeys)
        val destination = try {
            directory(target)
        } catch (_: SecurityException) {
            return@mutate failure(GalleryFolderError.NEEDS_ACCESS, photos.size, requestedKeys)
        } catch (_: IOException) {
            return@mutate failure(GalleryFolderError.NOT_FOUND, photos.size, requestedKeys)
        } catch (_: IllegalArgumentException) {
            return@mutate failure(GalleryFolderError.INVALID_NAME, photos.size, requestedKeys)
        }
        if (!destination.isDirectory) return@mutate failure(
            GalleryFolderError.NOT_FOUND, photos.size, requestedKeys)
        var succeeded = 0
        var failed = 0
        var skipped = 0
        var error: GalleryFolderError? = null
        val failedKeys = mutableSetOf<String>()
        photos.distinctBy { it.key }.forEach { photo ->
            coroutineContext.ensureActive()
            try {
                val source = readItem(photo.media.uri) ?: throw IOException("Media was removed")
                if (source.trashed || source.pending) throw IOException("Media is unavailable")
                if (!copy && source.volume == target.volume && source.path == target.path) {
                    skipped++
                } else if (!copy && source.volume == target.volume) {
                    // Provider builds a unique filename if another item has the same name.
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, target.path)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    }
                    if (resolver.update(source.uri, values, null, null) != 1)
                        throw IOException("Media move failed")
                    succeeded++
                } else {
                    val newUri = copyItem(source, target)
                    if (!copy) {
                        try {
                            coroutineContext.ensureActive()
                            val live = readItem(source.uri)
                            if (live == null || live.file != source.file ||
                                resolver.delete(source.uri, null, null) != 1) {
                                throw IOException("Original could not be removed")
                            }
                        } catch (failure: Exception) {
                            // A provider can fail after deleting the original but before replying.
                            // Only remove the completed copy when the original is still present.
                            val originalPresent = runCatching {
                                readItem(source.uri)?.let {
                                    it.file == source.file && it.file.isFile
                                } == true
                            }.getOrDefault(false)
                            if (originalPresent) runCatching { resolver.delete(newUri, null, null) }
                            throw failure
                        }
                        // The media move is now complete. A preferences failure must never roll
                        // back its only surviving file or count this successful media as failed.
                        try {
                            remapCollections(source.uri.toString(), newUri.toString())
                        } catch (_: Exception) {
                            if (error == null) error = GalleryFolderError.IO
                        }
                    }
                    succeeded++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                failed++
                failedKeys.add(photo.key)
                error = GalleryFolderError.NEEDS_ACCESS
            } catch (_: IOException) {
                failed++
                failedKeys.add(photo.key)
                if (error == null) error = GalleryFolderError.IO
            } catch (_: IllegalArgumentException) {
                failed++
                failedKeys.add(photo.key)
                if (error == null) error = GalleryFolderError.IO
            } catch (_: IllegalStateException) {
                failed++
                failedKeys.add(photo.key)
                if (error == null) error = GalleryFolderError.IO
            } catch (_: SQLException) {
                failed++
                failedKeys.add(photo.key)
                if (error == null) error = GalleryFolderError.IO
            }
        }
        refresh()
        GalleryFolderResult(succeeded, failed, skipped, error, target, failedKeys)
    }

    private suspend fun copyItem(source: Item, target: GalleryFolder): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, source.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, source.taken)
        }
        val table = if (source.video) MediaStore.Video.Media.getContentUri(target.volume)
            else MediaStore.Images.Media.getContentUri(target.volume)
        val uri = resolver.insert(table, values) ?: throw IOException("Could not create media")
        try {
            var bytes = 0L
            resolver.openInputStream(MediaStore.setRequireOriginal(source.uri))?.use { input ->
                resolver.openOutputStream(uri, "w")?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytes += count
                    }
                    output.flush()
                } ?: throw IOException("Could not open destination")
            } ?: throw IOException("Could not open source")
            if (bytes != source.size) throw IOException("Source changed while copying")
            val live = readItem(source.uri)
            if (live == null || live.size != source.size || live.modified != source.modified ||
                live.file != source.file) throw IOException("Source changed while copying")
            coroutineContext.ensureActive()
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.IS_FAVORITE, source.favorite)
            }
            if (resolver.update(uri, published, null, null) != 1)
                throw IOException("Could not publish media")
            return uri
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    private data class Item(
        val uri: Uri, val volume: String, val path: String, val name: String, val file: File,
        val video: Boolean, val mime: String, val size: Long, val modified: Long,
        val taken: Long, val favorite: Boolean, val trashed: Boolean, val pending: Boolean,
    )

    private fun readItem(uri: Uri): Item? {
        // Refuse content from exported or arbitrary providers, even if a caller constructs Media.
        if (uri.authority != MediaStore.AUTHORITY || uri.scheme != "content") return null
        val segments = uri.pathSegments
        val typed = segments.size == 4 && segments[2] == "media" &&
            segments[1] in listOf("images", "video")
        val files = segments.size == 3 && segments[1] == "file"
        if (!typed && !files) return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it > 0 } ?: return null
        val row = ContentUris.withAppendedId(MediaStore.Files.getContentUri(segments[0]), id)
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        // Images/Video tables deliberately reject the Files-only media_type projection. Read the
        // row through Files, then return a typed URI after independently verifying its media type.
        resolver.query(row, COLUMNS, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val item = item(cursor) ?: return null
            if (typed && (segments[1] == "video") != item.video) return null
            return item
        }
        return null
    }

    private fun item(
        cursor: android.database.Cursor,
        volumes: Map<String, File> = roots(),
    ): Item? {
        val type = cursor.getInt(1)
        if (type != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE &&
            type != MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) return null
        val volume = cursor.getString(2) ?: return null
        val path = cursor.getString(3) ?: return null
        if (!validRelativePath(path)) return null
        val file = File(cursor.getString(5) ?: return null)
        val root = volumes[volume] ?: return null
        if (!contained(root, file) || File(root, path).absolutePath != file.parent) return null
        val video = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mediaUri = ContentUris.withAppendedId(if (video)
            MediaStore.Video.Media.getContentUri(volume) else
            MediaStore.Images.Media.getContentUri(volume), cursor.getLong(0))
        return Item(mediaUri, volume, path, cursor.getString(4) ?: file.name, file,
            video, cursor.getString(6) ?: if (video) "video/mp4" else "image/jpeg",
            cursor.getLong(7), cursor.getLong(8), cursor.getLong(9), cursor.getInt(10) != 0,
            cursor.getInt(11) != 0, cursor.getInt(12) != 0)
    }

    private data class Tree(val media: List<Item>, val directories: List<File>, val hasOtherFiles: Boolean)

    private fun mediaTree(folder: GalleryFolder, directory: File): Tree {
        val media = mutableListOf<Item>()
        val volumes = roots()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "media_type IN (1, 3)")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(MediaStore.Files.getContentUri(folder.volume), COLUMNS, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(3).orEmpty()
                if (path.startsWith(folder.path)) item(cursor, volumes = volumes)?.takeIf {
                    contained(directory, it.file)
                }?.let(media::add)
            }
        } ?: throw IOException("Media provider unavailable")
        val indexed = media.filterNot { it.pending }.map { it.file.absolutePath }.toSet()
        val directories = mutableListOf<File>()
        val queue = ArrayDeque<File>().apply { add(directory) }
        var other = false
        var visited = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (++visited > MAX_ENTRIES || !contained(directory, current)) {
                other = true
                break
            }
            directories.add(current)
            val contents = current.listFiles()
            if (contents == null) { other = true; continue }
            contents.forEach {
                if (!contained(directory, it)) other = true
                else if (it.isDirectory && !it.name.startsWith('.')) queue.add(it)
                else if (!it.isFile || it.absolutePath !in indexed) other = true
            }
        }
        return Tree(media.filterNot { it.pending }, directories, other)
    }

    private fun directory(folder: GalleryFolder): File {
        require(safePath(folder.path)) { "Invalid folder path" }
        val root = roots()[folder.volume] ?: throw IOException("Storage unavailable")
        val result = File(root, folder.path)
        require(contained(root, result)) { "Path leaves storage volume" }
        return result
    }

    private fun roots(): Map<String, File> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
        return manager.storageVolumes.mapNotNull { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED &&
                volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val name = volume.mediaStoreVolumeName ?: return@mapNotNull null
            val directory = volume.directory ?: return@mapNotNull null
            name to directory
        }.toMap()
    }

    private fun contained(root: File, file: File): Boolean = runCatching {
        val base = root.canonicalFile
        val canonical = file.canonicalFile
        canonical.absolutePath == file.absolutePath &&
            (canonical == base || canonical.path.startsWith(base.path + File.separator))
    }.getOrDefault(false)

    private fun knownFolders(): List<GalleryFolder> = buildList {
        val entries = runCatching { JSONArray(preferences.getString(KNOWN, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONArray(i) ?: continue
            val folder = GalleryFolder(entry.optString(0), entry.optString(1))
            if (folder.canReceive) add(folder)
        }
    }

    private fun remember(folders: List<GalleryFolder>) {
        val entries = JSONArray()
        folders.distinctBy { it.id }.forEach { entries.put(JSONArray(listOf(it.volume, it.path))) }
        preferences.edit().putString(KNOWN, entries.toString()).putLong(CHANGED, System.nanoTime()).apply()
    }

    private fun remapCollections(oldUri: String, newUri: String) = synchronized(GalleryStore.COLLECTION_LOCK) {
        val collections = runCatching { JSONArray(preferences.getString("collections", "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until collections.length()) {
            val members = collections.optJSONObject(i)?.optJSONArray("members") ?: continue
            for (j in 0 until members.length()) if (members.optString(j) == oldUri) members.put(j, newUri)
        }
        preferences.edit().putString("collections", collections.toString()).apply()
    }

    private fun scan(paths: List<String>) {
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> refresh() }
    }

    private suspend fun mutate(action: suspend () -> GalleryFolderResult): GalleryFolderResult =
        withContext(Dispatchers.IO) {
            mutationLock.withLock {
                try { action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) { failure(GalleryFolderError.NEEDS_ACCESS) }
                catch (_: IOException) { failure(GalleryFolderError.IO) }
                catch (_: IllegalArgumentException) { failure(GalleryFolderError.INVALID_NAME) }
                catch (_: IllegalStateException) { failure(GalleryFolderError.IO) }
                catch (_: SQLException) { failure(GalleryFolderError.IO) }
            }
        }

    private fun failure(error: GalleryFolderError, count: Int = 1, keys: Set<String> = emptySet()) =
        GalleryFolderResult(failed = count, error = error, failedKeys = keys)

    companion object {
        const val CHANGED = "folders_changed"
        private const val KNOWN = "physical_folders"
        private const val MAX_ENTRIES = 50_000
        private val mutationLock = Mutex()
        private val PUBLIC_DIRECTORIES = listOf(Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)
        private val COLUMNS = arrayOf("_id", "media_type", "volume_name", "relative_path",
            "_display_name", "_data", "mime_type", "_size", "date_modified", "datetaken",
            "is_favorite", "is_trashed", "is_pending")

        internal fun validName(name: String): Boolean = name.isNotBlank() &&
            name == name.trim() && name != "." && name != ".." && !name.startsWith('.') &&
            name.toByteArray(Charsets.UTF_8).size <= 255 &&
            name.none { it.code < 32 || it in "/\\:*?\"<>|" }

        private fun validRelativePath(path: String): Boolean = path.endsWith('/') &&
            !path.startsWith('/') && path.trimEnd('/').split('/').all(::validName)

        internal fun safePath(path: String): Boolean = validRelativePath(path) &&
            !path.substringBefore('/').equals("Android", ignoreCase = true)
    }
}
