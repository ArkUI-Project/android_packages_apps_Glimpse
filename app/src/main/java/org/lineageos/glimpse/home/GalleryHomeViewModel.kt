/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryHomeViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    val store = GalleryStore(application)
    val busy = MutableStateFlow(false)
    private val completions = Channel<Pair<GalleryFolderResult, String?>>(Channel.BUFFERED)
    val completed = completions.receiveAsFlow()
    val query = saved.getStateFlow("query", GalleryQuery().encode())
    val tab = saved.getStateFlow("tab", store.preferences.getInt("tab", 0))
    val search = saved.getStateFlow("search", false)
    val library = store.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
        GalleryLibrary(loading = true))
    val content = combine(library, query) { library, query ->
        library to GalleryQuery.decode(query).select(library)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
        GalleryLibrary(loading = true) to emptyList())
    var currentQuery: GalleryQuery
        get() = GalleryQuery.decode(query.value)
        set(value) { saved["query"] = value.encode() }
    var page: Int
        get() = tab.value
        set(value) {
            saved["tab"] = value
            store.preferences.edit().putInt("tab", value).apply()
        }
    var searching: Boolean
        get() = search.value
        set(value) { saved["search"] = value }
    var collectionTarget: String?
        get() = saved["collectionTarget"]
        set(value) { saved["collectionTarget"] = value }
    var folderTarget: String?
        get() = saved["folderTarget"]
        set(value) { saved["folderTarget"] = value }
    var folderCopy: Boolean
        get() = saved["folderCopy"] ?: false
        set(value) { saved["folderCopy"] = value }
    var selected: Set<String>
        get() = saved.get<ArrayList<String>>("selection")?.toSet().orEmpty()
        set(value) { saved["selection"] = ArrayList(value) }
    var selecting: Boolean
        get() = saved["selecting"] ?: false
        set(value) { saved["selecting"] = value }

    fun mutate(destination: String? = null, action: suspend () -> GalleryFolderResult) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val result = try { action() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GalleryFolderResult(failed = 1, error = GalleryFolderError.IO)
            } finally { busy.value = false }
            completions.send(result to (result.folder?.let { "folder:${it.id}" } ?: destination))
        }
    }
}
