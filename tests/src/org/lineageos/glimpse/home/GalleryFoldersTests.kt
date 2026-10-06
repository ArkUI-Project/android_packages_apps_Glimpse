/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real engine and MediaProvider under Glimpse's UID and existing gallery role. */
class GalleryFoldersTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private lateinit var prefix: String
    private lateinit var preferencesName: String
    private lateinit var folders: GalleryFolders
    private lateinit var root: GalleryFolder
    private val allFiles = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Before fun setUp() = runBlocking {
        prefix = "ArkUI-Gallery-QA-${UUID.randomUUID()}"
        preferencesName = "gallery_folders_test_$prefix"
        folders = GalleryFolders(context, context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
        root = success(folders.create(prefix))
        assertTrue(physical(root).isDirectory)
    }

    @After fun tearDown() {
        if (!::prefix.isInitialized) return
        // Only delete rows and directories whose names contain this test's random UUID.
        val cleanupArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                TEST_TOP_LEVELS.joinToString(" OR ") { "relative_path LIKE ?" })
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                TEST_TOP_LEVELS.map { "$it/$prefix%" }.toTypedArray())
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(allFiles, arrayOf("_id"), cleanupArgs, null)?.use { cursor ->
            val uris = buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(allFiles, cursor.getLong(0))) }
            uris.forEach { runCatching { resolver.delete(it, Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }) } }
        }
        TEST_TOP_LEVELS.forEach { name ->
            val topLevel = File(Environment.getExternalStorageDirectory(), name)
            topLevel.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach {
                // Only UUID fixture subdirectories are removed; standard top-level folders remain.
                shell("rm -rf -- ${quote(it.absolutePath)}")
            }
        }
        clearFixtureIndexes()
        context.deleteSharedPreferences(preferencesName)
    }

    private fun clearFixtureIndexes() {
        // The gallery role intentionally hides foreign documents and directory index rows. Remove
        // those fixture-only rows as shell after their physical UUID trees have been removed.
        val arguments = fixtureIndexArguments()
        shell("content delete $arguments")
        val remaining = queryFixtureIndexes()
        assertEquals("Fixture MediaStore indexes remain for $prefix: $remaining",
            "No result found.", remaining)
    }

    private fun fixtureIndexArguments(): String {
        require(prefix.matches(Regex("ArkUI-Gallery-QA-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        fun literal(value: String) = "'" + value.replace("'", "''") + "'"
        val selection = TEST_TOP_LEVELS.joinToString(" OR ") { name ->
            val absoluteStem = File(Environment.getExternalStorageDirectory(), "$name/$prefix").absolutePath
            "(relative_path LIKE ${literal("$name/$prefix%")} OR " +
                "(relative_path = ${literal("$name/")} AND _display_name LIKE ${literal("$prefix%")}) OR " +
                "_data LIKE ${literal("$absoluteStem%")})"
        }
        // content's binding parser uses ':' separators; escape the colon inside each query key.
        val extras = listOf(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.QUERY_ARG_MATCH_PENDING)
            .joinToString(" ") { key ->
                "--extra ${quote(key.replace(":", "\\:") + ":i:${MediaStore.MATCH_INCLUDE}")}"
            }
        return "--uri ${quote(allFiles.toString())} --where ${quote(selection)} $extras"
    }

    private fun queryFixtureIndexes() =
        shell("content query ${fixtureIndexArguments()} --projection _id:_data").trim()

    @Test fun existingSystemGalleryRoleProvidesMediaAccessWithoutAllFilesPermission() {
        assertEquals("org.lineageos.glimpse", context.packageName)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse("Gallery must not require broad all-files access", Environment.isExternalStorageManager())
    }

    @Test fun emptyFolderExistsOnDiskAndSurvivesEngineRecreation() {
        val restarted = GalleryFolders(context, context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
        assertTrue(restarted.list(emptyList()).any { it.id == root.id && it.count == 0 })
        assertTrue(physical(root).isDirectory)
    }

    @Test fun createsNestedFoldersWithoutPlaceholderMedia() = runBlocking {
        val child = success(folders.create("旅行照片", root))
        assertEquals("${root.path}旅行照片/", child.path)
        assertTrue(physical(child).isDirectory)
        assertTrue(rows(root).isEmpty())
    }

    @Test fun refusesInvalidNamesAndPathTraversal() = runBlocking {
        for (name in listOf("", " ", ".", "..", "../outside", "a/b", "a\\b", "a\nb")) {
            val result = folders.create(name, root)
            assertNotNull("Invalid name accepted: $name", result.error)
            assertEquals(0, result.succeeded)
        }
        assertTrue(physical(root).isDirectory)
        assertTrue(physical(root).listFiles().orEmpty().isEmpty())
    }

    @Test fun duplicateFolderNeverOverwritesExistingFiles() = runBlocking {
        val child = success(folders.create("Existing", root))
        val photo = image(child, "keep.png")
        val before = hash(photo.media.uri)
        val result = folders.create("Existing", root)
        assertNotNull(result.error)
        assertEquals(before, hash(photo.media.uri))
        assertEquals(1, rows(child).size)
    }

    @Test fun movesImageOnDiskPreservingUriBytesAndFavorite() = runBlocking {
        val destination = success(folders.create("Moved", root))
        val photo = image(root, "original.png")
        resolver.update(photo.media.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, 1)
        }, null, null)
        val before = hash(photo.media.uri)
        val result = folders.transfer(listOf(photo), destination, false)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(before, hash(photo.media.uri))
        assertEquals(destination.path, path(photo.media.uri))
        assertEquals(1, integer(photo.media.uri, MediaStore.MediaColumns.IS_FAVORITE))
        assertFalse(File(physical(root), "original.png").exists())
        assertTrue(File(physical(destination), "original.png").isFile)
    }

    @Test fun galleryRoleMovesImageOwnedByAnotherAppWithoutTakingAllFilesAccess() = runBlocking {
        val destination = success(folders.create("Foreign moved", root))
        val seed = image(root, "seed.png", Color.RED)
        val file = File(physical(root), "foreign.png")
        shell("cp ${quote(File(physical(root), "seed.png").absolutePath)} ${quote(file.absolutePath)}")
        val scanned = CountDownLatch(1)
        var scannedUri: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png")) { _, uri ->
            scannedUri = uri
            scanned.countDown()
        }
        assertTrue(scanned.await(15, TimeUnit.SECONDS))
        val uri = requireNotNull(scannedUri)
        assertNotEquals("Fixture must not be owned by Glimpse", context.packageName,
            string(uri, MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
        val photo = photo(root, uri, "foreign.png", "image/png", seed.media.sizeBytes)
        val before = hash(uri)
        val result = folders.transfer(listOf(photo), destination, false)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(before, hash(uri))
        assertEquals(destination.path, path(uri))
        assertFalse(Environment.isExternalStorageManager())
    }

    @Test fun copiesImageToNewUriWithoutChangingOriginal() = runBlocking {
        val destination = success(folders.create("Copies", root))
        val photo = image(root, "copy.png")
        val before = hash(photo.media.uri)
        val result = folders.transfer(listOf(photo), destination, true)
        assertEquals(1, result.succeeded)
        assertEquals(0, result.failed)
        val copied = rows(destination).single()
        assertNotEquals(photo.media.uri, copied)
        assertEquals(before, hash(copied))
        assertEquals(before, hash(photo.media.uri))
        assertEquals(root.path, path(photo.media.uri))
    }

    @Test fun partialCopyReportsOnlyFailedSource() = runBlocking {
        val destination = success(folders.create("Partial copy", root))
        val valid = image(root, "valid.png", Color.RED)
        val stale = image(root, "removed.png", Color.BLUE)
        val before = hash(valid.media.uri)
        assertEquals(1, resolver.delete(stale.media.uri, null, null))

        val result = folders.transfer(listOf(valid, stale), destination, true)
        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(setOf(stale.key), result.failedKeys)
        assertNotNull(result.error)
        assertEquals(before, hash(valid.media.uri))
        assertEquals(root.path, path(valid.media.uri))
        val copied = rows(destination).single()
        assertNotEquals(valid.media.uri, copied)
        assertEquals(before, hash(copied))
        assertFalse(exists(stale.media.uri))
    }

    @Test fun batchCopyRoutesImagesAndRealMp4WithoutChangingAnyBytes() = runBlocking {
        val destination = success(folders.create("Batch", root))
        val photos = listOf(image(root, "one.png"), image(root, "two.png", Color.RED),
            media(root, "clip.mp4", "video/mp4", Base64.decode(BLUE_VIDEO, Base64.DEFAULT)))
        val hashes = photos.map { hash(it.media.uri) }.sorted()
        val result = folders.transfer(photos, destination, true)
        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(hashes, rows(destination).map(::hash).sorted())
        assertEquals(hashes, photos.map { hash(it.media.uri) }.sorted())
    }

    @Test fun galleryRoleCopiesAndMovesImagesAndVideosAcrossMoviesAndDownload() = runBlocking {
        val parents = listOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)
            .map { GalleryFolder(MediaStore.VOLUME_EXTERNAL_PRIMARY, "$it/") }
        parents.forEach { parent ->
            if (!physical(parent).isDirectory) shell("mkdir -p -- ${quote(physical(parent).absolutePath)}")
            assertTrue(physical(parent).isDirectory)
        }
        val movies = success(folders.create(prefix, parents[0]))
        val downloads = success(folders.create(prefix, parents[1]))
        val originals = listOf(image(root, "public-image.png", Color.RED),
            media(root, "public-video.mp4", "video/mp4", Base64.decode(BLUE_VIDEO, Base64.DEFAULT)))
        val hashes = originals.map { hash(it.media.uri) }.sorted()
        val originalUris = originals.map { it.media.uri }.toSet()

        val movedToMovies = folders.transfer(originals, movies, false)
        assertEquals(2, movedToMovies.succeeded)
        assertEquals(0, movedToMovies.failed)
        assertEquals(originalUris, rows(movies).toSet())
        originals.forEach { assertEquals(movies.path, path(it.media.uri)) }
        assertEquals(hashes, originals.map { hash(it.media.uri) }.sorted())
        assertTrue(rows(root).isEmpty())

        val copiedToDownloads = folders.transfer(originals, downloads, true)
        assertEquals(2, copiedToDownloads.succeeded)
        assertEquals(0, copiedToDownloads.failed)
        val copies = rows(downloads)
        assertEquals(2, copies.size)
        assertTrue(copies.none { it in originalUris })
        assertEquals(hashes, copies.map(::hash).sorted())
        originals.forEach { assertEquals(movies.path, path(it.media.uri)) }

        // Moving originals beside their copies must preserve their URIs and choose fresh names.
        val movedToDownloads = folders.transfer(originals, downloads, false)
        assertEquals(2, movedToDownloads.succeeded)
        assertEquals(0, movedToDownloads.failed)
        originals.forEach { assertEquals(downloads.path, path(it.media.uri)) }
        assertEquals(hashes, originals.map { hash(it.media.uri) }.sorted())
        val allDownloads = rows(downloads)
        assertEquals(4, allDownloads.size)
        assertEquals((hashes + hashes).sorted(), allDownloads.map(::hash).sorted())
        assertEquals(4, allDownloads.map { string(it, MediaStore.MediaColumns.DISPLAY_NAME) }.distinct().size)
        allDownloads.forEach {
            assertTrue(File(physical(downloads), string(it, MediaStore.MediaColumns.DISPLAY_NAME)).isFile)
        }
        assertTrue(rows(movies).isEmpty())
        assertTrue(physical(movies).listFiles().orEmpty().isEmpty())
        assertFalse(Environment.isExternalStorageManager())
    }

    @Test fun duplicateFileNamesProduceDistinctCopiesWithoutOverwrite() = runBlocking {
        val destination = success(folders.create("Conflicts", root))
        val original = image(root, "same.png", Color.RED)
        val existing = image(destination, "same.png", Color.BLUE)
        val originalHash = hash(original.media.uri)
        val existingHash = hash(existing.media.uri)
        assertEquals(1, folders.transfer(listOf(original), destination, true).succeeded)
        assertEquals(existingHash, hash(existing.media.uri))
        assertEquals(listOf(originalHash, existingHash).sorted(), rows(destination).map(::hash).sorted())
        val names = rows(destination).map { string(it, MediaStore.MediaColumns.DISPLAY_NAME) }
        assertEquals(2, names.distinct().size)
    }

    @Test fun movingWithinSameFolderSkipsWithoutRenamingOrCopying() = runBlocking {
        val photo = image(root, "same-folder.png")
        val before = hash(photo.media.uri)
        val result = folders.transfer(listOf(photo), root, false)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(1, rows(root).size)
        assertEquals(before, hash(photo.media.uri))
        assertEquals("same-folder.png", string(photo.media.uri, MediaStore.MediaColumns.DISPLAY_NAME))
    }

    @Test fun renameUpdatesNestedMediaRowsAndActualDirectoryTree() = runBlocking {
        val child = success(folders.create("Nested", root))
        val first = image(root, "root.png")
        val second = image(child, "nested.png")
        val before = listOf(first, second).map { hash(it.media.uri) }
        val renamed = success(folders.rename(root, "$prefix-renamed"))
        assertFalse(physical(root).exists())
        assertTrue(physical(renamed).isDirectory)
        assertEquals(renamed.path, path(first.media.uri))
        assertEquals("${renamed.path}Nested/", path(second.media.uri))
        assertEquals(before, listOf(first, second).map { hash(it.media.uri) })
    }

    @Test fun renameCollisionLeavesBothFolderTreesIntact() = runBlocking {
        val first = success(folders.create("First", root))
        val second = success(folders.create("Second", root))
        val firstPhoto = image(first, "first.png")
        val secondPhoto = image(second, "second.png", Color.RED)
        val hashes = listOf(firstPhoto, secondPhoto).map { hash(it.media.uri) }
        val result = folders.rename(first, second.name)
        assertNotNull(result.error)
        assertTrue(physical(first).isDirectory)
        assertTrue(physical(second).isDirectory)
        assertEquals(hashes, listOf(firstPhoto, secondPhoto).map { hash(it.media.uri) })
    }

    @Test fun deleteEmptyFolderRemovesDirectoryAndRegisteredEntry() = runBlocking {
        val result = folders.delete(root)
        assertEquals(0, result.failed)
        assertFalse(physical(root).exists())
        assertFalse(folders.list(emptyList()).any { it.id == root.id })
    }

    @Test fun deleteMediaFolderRemovesNestedImagesAndVideosAndRealDirectories() = runBlocking {
        val child = success(folders.create("Child", root))
        val photos = listOf(image(root, "delete.png"), image(child, "nested.png"),
            media(child, "delete.mp4", "video/mp4", Base64.decode(BLUE_VIDEO, Base64.DEFAULT)))
        val result = folders.delete(root)
        assertEquals(0, result.failed)
        assertTrue("Directory was retained after successful delete", !physical(root).exists())
        photos.forEach { assertFalse(exists(it.media.uri)) }
    }

    @Test fun deleteFolderReconcilesRootAndChildDirectoryIndexes() = runBlocking {
        val child = success(folders.create("Indexed child", root))
        image(child, "indexed.png")
        val rootPath = physical(root).absolutePath
        val childPath = physical(child).absolutePath
        val scanned = CountDownLatch(2)
        MediaScannerConnection.scanFile(context, arrayOf(rootPath, childPath), null) { _, _ ->
            scanned.countDown()
        }
        assertTrue("Fixture directory scan timed out", scanned.await(15, TimeUnit.SECONDS))
        val before = queryFixtureIndexes()
        val indexedPaths = before.lines().map { it.substringAfter("_data=", "") }.toSet()
        assertTrue("Root directory index was not created: $before", rootPath in indexedPaths)
        assertTrue("Child directory index was not created: $before", childPath in indexedPaths)

        val result = folders.delete(root)
        assertEquals(0, result.failed)
        assertNull(result.error)
        assertFalse(physical(root).exists())
        assertFalse(physical(child).exists())
        val deadline = SystemClock.uptimeMillis() + 10_000
        var remaining = queryFixtureIndexes()
        while (remaining != "No result found." && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            remaining = queryFixtureIndexes()
        }
        assertEquals("Deleted root/child directory indexes were not reconciled: $remaining",
            "No result found.", remaining)
    }

    @Test fun neverRenamesOrDeletesTopLevelPictures() = runBlocking {
        val pictures = GalleryFolder(MediaStore.VOLUME_EXTERNAL_PRIMARY, "Pictures/")
        assertNotNull(folders.rename(pictures, "Changed").error)
        assertNotNull(folders.delete(pictures).error)
        assertTrue(physical(pictures).isDirectory)
        assertTrue(physical(root).isDirectory)
    }

    @Test fun mixedDirectoryRenameEitherRefusesOrAtomicallyPreservesForeignDocument() = runBlocking {
        val photo = image(root, "safe.png")
        val before = hash(photo.media.uri)
        foreignDocument(root)
        val result = folders.rename(root, "$prefix-mixed-renamed")
        val remaining = if (result.error != null) {
            assertTrue(physical(root).isDirectory)
            assertEquals(root.path, path(photo.media.uri))
            root
        } else {
            val renamed = success(result)
            assertEquals(1, result.succeeded)
            assertFalse(physical(root).exists())
            assertTrue(physical(renamed).isDirectory)
            assertEquals(renamed.path, path(photo.media.uri))
            renamed
        }
        assertEquals(before, hash(photo.media.uri))
        assertEquals(FOREIGN_TEXT, shell("cat ${quote(File(physical(remaining), "keep.txt").absolutePath)}"))
    }

    @Test fun mixedDirectoryDeletePreservesForeignDocumentAndReportsIncompleteDeletion() = runBlocking {
        val photo = image(root, "removable.png")
        foreignDocument(root)
        val result = folders.delete(root)
        assertNotNull("Directory with foreign document must not report complete deletion", result.error)
        assertTrue(physical(root).isDirectory)
        assertEquals(FOREIGN_TEXT, shell("cat ${quote(File(physical(root), "keep.txt").absolutePath)}"))
        // Either preflight refuses all changes or media deletion reports partial progress.
        if (!exists(photo.media.uri)) assertTrue(result.succeeded > 0)
    }

    @Test fun invalidTransferDestinationCannotEscapeSharedMediaRoot() = runBlocking {
        val photo = image(root, "safe.png")
        val before = hash(photo.media.uri)
        val result = folders.transfer(listOf(photo),
            GalleryFolder(MediaStore.VOLUME_EXTERNAL_PRIMARY, "../outside/"), false)
        assertNotNull(result.error)
        assertEquals(root.path, path(photo.media.uri))
        assertEquals(before, hash(photo.media.uri))
    }

    @Test fun listsRealFolderCountsAlongsideRegisteredEmptyFolders() = runBlocking {
        val populated = success(folders.create("Populated", root))
        val empty = success(folders.create("Empty", root))
        val photos = listOf(image(populated, "one.png"), image(populated, "two.png"))
        val listed = folders.list(photos)
        assertEquals(2, listed.single { it.id == populated.id }.count)
        assertEquals(0, listed.single { it.id == empty.id }.count)
        assertEquals(2, listed.single { it.id == root.id }.activeCount)
        assertEquals(2, listed.single { it.id == root.id }.totalCount)
        assertNotNull(listed.single { it.id == root.id }.cover)
        assertEquals(0, listed.single { it.id == empty.id }.activeCount)
        assertEquals(2, rows(populated).size)
    }

    @Test fun folderSummaryCountsTrashedDescendantsWithoutShowingThemAsCovers() = runBlocking {
        val child = success(folders.create("Child", root))
        val active = image(child, "active.png")
        val trashed = image(child, "trashed.png", Color.RED)
        assertEquals(1, resolver.update(trashed.media.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, 1)
        }, null, null))
        val isolatedName = "${preferencesName}_summary"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(if (name == "gallery_home") isolatedName else name, mode)
        }
        try {
            val library = withTimeout(10_000) { GalleryStore(isolated).observe().first() }
            assertFalse(library.error)
            val summary = requireNotNull(library.folder(root.id))
            assertEquals(1, summary.activeCount)
            assertEquals(2, summary.totalCount)
            assertEquals(active.key, summary.cover?.key)
            val childSummary = requireNotNull(library.folder(child.id))
            assertEquals(1, childSummary.count)
            assertEquals(1, childSummary.activeCount)
            assertEquals(2, childSummary.totalCount)
        } finally {
            context.deleteSharedPreferences(isolatedName)
        }
    }

    @Test fun oldMergedVolumeCollectionReferencesMigrateWithoutLosingMedia() = runBlocking {
        val photo = image(root, "legacy-reference.png")
        val isolatedName = "${preferencesName}_legacy"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(if (name == "gallery_home") isolatedName else name, mode)
        }
        try {
            val store = GalleryStore(isolated)
            val collection = store.create("QA legacy aliases", "album")
            val legacy = photo.key.replace("content://media/${photo.volume}/", "content://media/external/")
            store.updateMembers(collection.id, setOf(legacy), true)
            val library = withTimeout(10_000) { store.observe().first() }
            assertFalse(library.error)
            assertEquals(setOf(photo.key), library.collection(collection.id)?.members)
            assertEquals(listOf(photo.key), GalleryQuery(category = "collection:${collection.id}")
                .select(library).map { it.key })
            assertTrue(library.folders.any { it.id == root.id })
        } finally {
            context.deleteSharedPreferences(isolatedName)
        }
    }

    private fun success(result: GalleryFolderResult): GalleryFolder {
        assertNull("Folder operation failed: $result", result.error)
        assertEquals(0, result.failed)
        return requireNotNull(result.folder)
    }

    private fun physical(folder: GalleryFolder) = File(Environment.getExternalStorageDirectory(), folder.path)

    private fun image(folder: GalleryFolder, name: String, color: Int = Color.BLUE): GalleryPhoto {
        val bitmap = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }
        bitmap.recycle()
        return media(folder, name, "image/png", bytes)
    }

    private fun media(folder: GalleryFolder, name: String, mime: String, bytes: ByteArray): GalleryPhoto {
        val video = mime.startsWith("video/")
        val table = if (video) MediaStore.Video.Media.getContentUri(folder.volume)
            else MediaStore.Images.Media.getContentUri(folder.volume)
        val uri = requireNotNull(resolver.insert(table, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder.path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        assertEquals(1, resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null))
        return photo(folder, uri, name, mime, bytes.size.toLong())
    }

    private fun photo(folder: GalleryFolder, uri: Uri, name: String, mime: String, size: Long): GalleryPhoto {
        val video = mime.startsWith("video/")
        val now = Date()
        return GalleryPhoto(Media(uri, if (video) MediaType.VIDEO else MediaType.IMAGE,
            mime, allFiles.buildUpon().appendPath("albums").appendPath("test").build(),
            folder.name, name, false, false, now, now, 20, 16, 0, size),
            now.time, if (video) 1000 else 0, folder.path, folder.volume)
    }

    private fun rows(folder: GalleryFolder): List<Uri> = buildList {
        resolver.query(allFiles, arrayOf("_id", "media_type"),
            "relative_path = ? AND media_type IN (1,3) AND is_pending = 0", arrayOf(folder.path), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val table = if (cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    MediaStore.Video.Media.getContentUri(folder.volume)
                else MediaStore.Images.Media.getContentUri(folder.volume)
                add(ContentUris.withAppendedId(table, cursor.getLong(0)))
            }
        }
    }

    private fun path(uri: Uri) = string(uri, MediaStore.MediaColumns.RELATIVE_PATH)

    private fun string(uri: Uri, column: String) = resolver.query(uri, arrayOf(column), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    private fun integer(uri: Uri, column: String) = resolver.query(uri, arrayOf(column), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    private fun exists(uri: Uri) = resolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() }

    private fun hash(uri: Uri): String = requireNotNull(resolver.openInputStream(uri)).use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun foreignDocument(folder: GalleryFolder) {
        val file = File(physical(folder), "keep.txt")
        shell("printf '%s' ${quote(FOREIGN_TEXT)} > ${quote(file.absolutePath)}")
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain")) { _, _ -> scanned.countDown() }
        assertTrue("Foreign document scan timed out", scanned.await(15, TimeUnit.SECONDS))
        assertEquals(FOREIGN_TEXT, shell("cat ${quote(file.absolutePath)}"))
    }

    private fun shell(command: String): String {
        // UiAutomation uses Runtime.exec(String), so quoted arguments and redirection require an
        // actual shell. Send the script over stdin rather than relying on exec's word splitting.
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                val script = command + "\narkui_gallery_qa_status=\$?\n" +
                    "printf '\\n__ARKUI_GALLERY_QA_STATUS__=%s\\n' \"\$arkui_gallery_qa_status\"\n"
                input.write(script.toByteArray(Charsets.UTF_8))
            }
            val response = output.readBytes().toString(Charsets.UTF_8)
            val marker = "\n__ARKUI_GALLERY_QA_STATUS__="
            val separator = response.lastIndexOf(marker)
            assertTrue("Shell fixture did not report exit status: $command\n$response", separator >= 0)
            val status = response.substring(separator + marker.length).trim()
            assertEquals("Shell fixture failed: $command\n$response", "0", status)
            response.substring(0, separator)
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private val TEST_TOP_LEVELS = listOf(Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)
        private const val FOREIGN_TEXT = "ArkUI gallery QA foreign document: preserve these bytes."
        // An original, one-frame 32x32 blue H.264 MP4, generated solely for these byte-preservation tests.
        private const val BLUE_VIDEO = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMNbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAjh0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAACAAAAAgAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAGwbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABW21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAARtzdGJsAAAAt3N0c2QAAAAAAAAAAQAAAKdhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAACAAIABIAAAASAAAAAAAAAABFUxhdmM2MS4xOS4xMDAgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAALWF2Y0MBQsAK/+EAFmdCwAraJbARAAADAAEAAAMAAg8SJqABAARozg/IAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAE4AAABOAAAAAGHN0dHMAAAAAAAAAAQAAAAEAAEAAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAAnAAAAABAAAAFHN0Y28AAAAAAAAAAQAAAz0AAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYxLjcuMTAwAAAACGZyZWUAAAJ4bWRhdAAAAlMGBf//T9xF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgcjMxOTIgYzI0ZTA2YyAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjQgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0xIGRlYmxvY2s9MDowOjAgYW5hbHlzZT0wOjAgbWU9ZGlhIHN1Ym1lPTAgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MCBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTAgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9MCB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0wIHdlaWdodHA9MCBrZXlpbnQ9MjUwIGtleWludF9taW49MSBzY2VuZWN1dD0wIGludHJhX3JlZnJlc2g9MCByYz1jcmYgbWJ0cmVlPTAgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MACAAAAAFWWIhDoRigACMXHAAEPKOAAIBcnXXg=="
    }
}
