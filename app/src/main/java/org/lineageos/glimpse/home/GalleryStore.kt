/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import java.time.ZoneId
import java.util.Date
import java.util.UUID

data class GalleryPhoto(
    val media: Media, val taken: Long, val duration: Long, val path: String,
    val volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
) {
    val key get() = media.uri.toString()
    val folderId get() = GalleryFolder(volume, path).id
    val day get() = Date(taken).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    val isVideo get() = media.mediaType == MediaType.VIDEO
    val isCamera get() = path.startsWith("DCIM/Camera/", true)
    val isCapture get() = listOf("screenshot", "screenrecord", "screen record", "截屏", "录屏")
        .any { path.contains(it, true) || media.displayName.orEmpty().contains(it, true) }
}

/** Collections only hold references. Removing a collection never deletes the original files. */
data class GalleryCollection(
    val id: String, val name: String, val kind: String, val members: Set<String>,
)

data class GalleryLibrary(
    val photos: List<GalleryPhoto> = emptyList(),
    val collections: List<GalleryCollection> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val folders: List<GalleryFolder> = emptyList(),
) {
    fun collection(id: String) = collections.firstOrNull { it.id == id }
    fun folder(id: String) = folders.firstOrNull { it.id == id }
}

data class GalleryQuery(
    val category: String = "all",
    val text: String = "",
    val mediaType: String = "all",
    val oldestFirst: Boolean = false,
) {
    fun encode() = JSONObject().put("category", category).put("text", text)
        .put("type", mediaType).put("oldest", oldestFirst).toString()

    fun select(library: GalleryLibrary): List<GalleryPhoto> {
        val members = library.collection(category.removePrefix("collection:"))?.members.orEmpty()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = library.photos.filter { photo ->
            val media = photo.media
            (media.isTrashed == (category == "trash")) && when (category) {
                "camera" -> photo.isCamera
                "captures" -> photo.isCapture
                "videos" -> photo.isVideo
                "favorites" -> media.isFavorite
                else -> when {
                    category.startsWith("folder:") -> photo.folderId == category.removePrefix("folder:")
                    category.startsWith("bucket:") -> media.albumUri.lastPathSegment ==
                        category.removePrefix("bucket:")
                    category.startsWith("collection:") -> photo.key in members
                    else -> true
                }
            } && when (mediaType) {
                "images" -> !photo.isVideo
                "videos" -> photo.isVideo
                else -> true
            } && (words.isEmpty() || run {
                val labels = library.collections.filter { photo.key in it.members }
                    .joinToString(" ") { it.name }
                val date = photo.day
                val searchable = "${media.displayName} ${media.albumName} $labels $date " +
                    "${date.monthValue}月${date.dayOfMonth}日 ${photo.path}"
                words.all { searchable.contains(it, ignoreCase = true) }
            })
        }.sortedWith(compareByDescending<GalleryPhoto> { it.taken }.thenByDescending { it.key })
        return if (oldestFirst) result.reversed() else result
    }

    companion object {
        fun decode(value: String?): GalleryQuery = runCatching {
            val json = JSONObject(value ?: "{}")
            GalleryQuery(json.optString("category", "all"), json.optString("text"),
                json.optString("type", "all"), json.optBoolean("oldest"))
        }.getOrDefault(GalleryQuery())
    }
}

class GalleryStore(private val context: Context) {
    private val resolver = context.contentResolver
    val preferences: SharedPreferences = context.getSharedPreferences("gallery_home", Context.MODE_PRIVATE)
    val folders = GalleryFolders(context, preferences)
    private val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun observe() = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == COLLECTIONS || key == GalleryFolders.CHANGED) trySend(Unit)
        }
        resolver.registerContentObserver(files, true, observer)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            resolver.unregisterContentObserver(observer)
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate().debounce(120).mapLatest {
        try {
            val photos = readPhotos()
            migrateCollectionUris(photos)
            GalleryLibrary(photos, collections(), folders = folders.list(photos))
        } catch (_: SecurityException) {
            GalleryLibrary(error = true)
        } catch (_: IllegalArgumentException) {
            GalleryLibrary(error = true)
        } catch (_: SQLiteException) {
            GalleryLibrary(error = true)
        }
    }.flowOn(Dispatchers.IO)

    internal fun readPhotos(): List<GalleryPhoto> {
        val columns = arrayOf("_id", "media_type", "mime_type", "bucket_id", "bucket_display_name",
            "_display_name", "is_favorite", "is_trashed", "date_added", "date_modified",
            "width", "height", "orientation", "_size", "datetaken", "duration", "relative_path",
            "volume_name")
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "media_type IN (1, 3) AND is_pending = 0")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return buildList {
            resolver.query(files, columns, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val volume = cursor.getString(17) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val uri = ContentUris.withAppendedId(if (video)
                        MediaStore.Video.Media.getContentUri(volume) else
                        MediaStore.Images.Media.getContentUri(volume), cursor.getLong(0))
                    val added = cursor.getLong(8) * 1000L
                    val modified = cursor.getLong(9) * 1000L
                    add(GalleryPhoto(Media(uri, if (video) MediaType.VIDEO else MediaType.IMAGE,
                        cursor.getString(2) ?: if (video) "video/*" else "image/*",
                        MediaStore.Files.getContentUri(volume).buildUpon().appendPath("albums")
                            .appendPath(cursor.getString(3)).build(),
                        cursor.getString(4)?.takeIf { it.isNotBlank() }
                            ?: context.getString(android.R.string.untitled),
                        cursor.getString(5), cursor.getInt(6) != 0,
                        cursor.getInt(7) != 0, Date(added), Date(modified), cursor.getInt(10),
                        cursor.getInt(11), cursor.getInt(12), cursor.getLong(13)),
                        cursor.getLong(14).takeIf { it > 0 } ?: modified.takeIf { it > 0 } ?: added,
                        cursor.getLong(15), cursor.getString(16).orEmpty(), volume))
                }
            } ?: throw IllegalArgumentException("Media provider unavailable")
        }
    }

    fun collections(): List<GalleryCollection> = buildList {
        val array = runCatching { JSONArray(preferences.getString(COLLECTIONS, "[]")) }
            .getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val members = item.optJSONArray("members") ?: JSONArray()
            add(GalleryCollection(item.optString("id"), item.optString("name"),
                item.optString("kind", "album"), buildSet {
                    for (j in 0 until members.length()) add(members.getString(j))
                }))
        }
        for (id in listOf("cards", "documents", "ai")) {
            if (none { it.id == id }) add(GalleryCollection(id, "", "album", emptySet()))
        }
    }.sortedBy { when (it.id) { "cards" -> 0; "documents" -> 1; "ai" -> 2; else -> 3 } }

    private fun save(collections: List<GalleryCollection>) {
        val array = JSONArray()
        collections.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)
                .put("members", JSONArray(it.members.toList())))
        }
        preferences.edit().putString(COLLECTIONS, array.toString()).apply()
    }

    private fun migrateCollectionUris(photos: List<GalleryPhoto>) {
        // Older home versions saved merged-volume URIs. Retain these references when moving to
        // volume-specific URIs; MediaStore IDs are unique in the merged external database.
        val aliases = photos.associate { photo ->
            photo.key.replace("content://media/${photo.volume}/", "content://media/external/") to photo.key
        }
        synchronized(COLLECTION_LOCK) {
            val existing = collections()
            val migrated = existing.map { collection ->
                collection.copy(members = collection.members.map { aliases[it] ?: it }.toSet())
            }
            if (migrated != existing) save(migrated)
        }
    }

    fun create(name: String, kind: String): GalleryCollection = synchronized(COLLECTION_LOCK) {
        val collection = GalleryCollection(UUID.randomUUID().toString(), name, kind, emptySet())
        save(collections() + collection)
        collection
    }

    fun rename(id: String, name: String) = synchronized(COLLECTION_LOCK) {
        save(collections().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(id: String) = synchronized(COLLECTION_LOCK) {
        save(collections().filterNot { it.id == id })
    }

    fun updateMembers(id: String, uris: Set<String>, add: Boolean) = synchronized(COLLECTION_LOCK) {
        save(collections().map {
            if (it.id == id) it.copy(members = if (add) it.members + uris else it.members - uris) else it
        })
    }

    fun refresh() = folders.refresh()

    companion object {
        private const val COLLECTIONS = "collections"
        internal val COLLECTION_LOCK = Any()
    }
}
