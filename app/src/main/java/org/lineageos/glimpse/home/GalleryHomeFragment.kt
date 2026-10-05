/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.SettingsActivity
import org.lineageos.glimpse.ViewActivity
import org.lineageos.glimpse.ext.buildShareIntent
import org.lineageos.glimpse.ext.createFavoriteRequest
import org.lineageos.glimpse.ext.createTrashRequest
import org.lineageos.glimpse.fragments.AlbumFragment
import org.lineageos.glimpse.models.AlbumType
import org.lineageos.glimpse.utils.PermissionsChecker
import org.lineageos.glimpse.utils.PermissionsUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.google.android.material.R as MaterialR

/** Photo timeline and editable collections share a recycled, edge-to-edge media surface. */
open class GalleryHomeFragment : Fragment() {
    private val model by viewModels<GalleryHomeViewModel>()
    private val permissions = PermissionsChecker(this, PermissionsUtils.mainPermissions)
    private lateinit var root: FrameLayout
    private lateinit var list: RecyclerView
    private lateinit var toolbar: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var layout: GridLayoutManager
    private lateinit var back: OnBackPressedCallback
    private var adapter: GalleryHomeAdapter? = null
    private var library = GalleryLibrary(loading = true)
    private var visiblePhotos = emptyList<GalleryPhoto>()
    private var renderJob: Job? = null
    private var searchEdit: EditText? = null
    private var toolbarMode = ""
    private var scrollToStart = false
    private var topInset = 0
    private var bottomInset = 0
    private var columns = 4
    private val operation = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) clearSelection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val c = requireContext()
        columns = model.store.preferences.getInt("columns", 4).takeIf { it in listOf(3, 4, 6) } ?: 4
        root = FrameLayout(c).apply { setBackgroundColor(c.tone(MaterialR.attr.colorSurface)) }
        list = RecyclerView(c).apply {
            id = R.id.galleryHomeRecycler
            clipToPadding = false
            itemAnimator = null
            isVerticalScrollBarEnabled = true
        }
        layout = GridLayoutManager(c, 12)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = when (adapter?.currentList?.getOrNull(position)?.kind) {
                GalleryRow.PHOTO -> 12 / effectiveColumns()
                GalleryRow.COMMON, GalleryRow.PERSON -> if (resources.configuration.screenWidthDp >= 600) 3 else 6
                GalleryRow.ALBUM -> if (resources.configuration.screenWidthDp >= 600) 2 else 4
                else -> 12
            }
        }
        list.layoutManager = layout
        adapter = GalleryHomeAdapter(Glide.with(this), ::onClick, ::onHold)
        list.adapter = adapter
        root.addView(list, FrameLayout.LayoutParams(-1, -1))
        toolbar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(12), 0, c.dp(12), 0)
            setBackgroundColor(c.tone(MaterialR.attr.colorSurface))
        }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, c.dp(56), Gravity.TOP))
        bottom = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        root.addView(bottom, FrameLayout.LayoutParams(-2, c.dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            root.setPadding(bars.left, 0, bars.right, 0)
            toolbar.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = topInset }
            bottom.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bottomInset + requireContext().dp(16) }
            updateListInsets()
            insets
        }
        back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    model.selecting -> {
                        val target = model.collectionTarget
                        clearSelection()
                        if (target != null) { model.page = 1; open("collection:$target") }
                    }
                    model.searching -> {
                        hideKeyboard()
                        model.searching = false
                        model.currentQuery = model.currentQuery.copy(text = "")
                    }
                    model.currentQuery.category != "all" -> open("all")
                    model.page != 0 -> switchTab(0)
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissions.withPermissionsGranted {
                    combine(model.content, model.tab, model.search) { content, _, _ -> content }
                        .collect { (data, photos) ->
                            library = data
                            visiblePhotos = photos
                            val present = data.photos.mapTo(HashSet()) { it.key }
                            if (!data.loading) model.selected = model.selected.intersect(present)
                            render()
                        }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layout.spanSizeLookup.invalidateSpanIndexCache()
        adapter?.notifyDataSetChanged()
        render()
    }

    private fun effectiveColumns() = if (resources.configuration.screenWidthDp >= 600 &&
        !model.store.preferences.contains("columns")) 6 else columns
    private fun isGrid() = model.page == 0 || model.searching || model.currentQuery.category !in
        listOf("all", "albums", "people", "common")

    private fun updateListInsets() {
        val side = if (isGrid()) 0 else requireContext().dp(15)
        list.setPadding(side, topInset, side, bottomInset + requireContext().dp(98))
    }

    private fun render() {
        if (view == null) return
        val query = model.currentQuery
        back.isEnabled = model.selecting || model.searching || query.category != "all" || model.page != 0
        updateListInsets()
        renderToolbar()
        renderBottom()
        val snapshot = library
        val photos = visiblePhotos
        val selected = model.selected
        val selecting = model.selecting
        val grid = isGrid()
        val searching = model.searching
        val resources = resources
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.Default) {
                buildRows(snapshot, photos, query, selected, selecting, grid, searching, resources)
            }
            adapter?.submitList(rows) {
                if (scrollToStart) { list.scrollToPosition(0); scrollToStart = false }
            }
        }
    }

    private fun buildRows(data: GalleryLibrary, photos: List<GalleryPhoto>, query: GalleryQuery,
        selected: Set<String>, selecting: Boolean, grid: Boolean, searching: Boolean,
        resources: Resources): List<GalleryRow> = buildList {
        // Use the captured resources, not Fragment.requireContext(), on the worker. The
        // fragment can detach while a large timeline is being grouped during recreation.
        fun getString(id: Int, vararg args: Any) = resources.getString(id, *args)
        add(GalleryRow(if (grid) "photoSpace" else "space", GalleryRow.SPACE))
        if (grid) {
            add(GalleryRow("title", GalleryRow.TITLE, if (searching) getString(R.string.gallery_search)
                else categoryName(query.category, data, resources)))
            if (data.loading || data.error) {
                add(GalleryRow("state", GalleryRow.EMPTY, getString(if (data.error) R.string.gallery_error
                    else R.string.gallery_loading), if (data.error) getString(R.string.gallery_error_tip) else ""))
            } else if (photos.isEmpty() || (searching && query.text.isBlank())) {
                val collection = query.category.startsWith("collection:")
                add(GalleryRow("empty", GalleryRow.EMPTY, getString(when {
                    searching && query.text.isBlank() -> R.string.gallery_search_empty
                    searching -> R.string.gallery_no_results
                    collection -> R.string.gallery_add_photos
                    else -> R.string.gallery_empty
                }), getString(when {
                    searching && query.text.isBlank() -> R.string.gallery_search_tip
                    searching -> R.string.gallery_no_results_tip
                    collection -> R.string.gallery_add_hint
                    else -> R.string.gallery_empty_tip
                }), action = if (collection) "add:${query.category.removePrefix("collection:")}" else ""))
            } else {
                var previous: LocalDate? = null
                val today = LocalDate.now()
                val locale = resources.configuration.locales[0]
                val formatter = DateTimeFormatter.ofPattern(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
                val yearFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                photos.forEach { photo ->
                    val day = photo.day
                    if (day != previous) {
                        val date = when (day) {
                            today -> getString(R.string.gallery_today)
                            today.minusDays(1) -> getString(R.string.gallery_yesterday)
                            else -> day.format(if (day.year == today.year) formatter else yearFormatter)
                        }
                        add(GalleryRow("day:$day", GalleryRow.SECTION, date))
                        previous = day
                    }
                    val seconds = photo.duration / 1000L
                    val duration = if (photo.isVideo) "▷ " + if (seconds >= 3600)
                        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                        else "%d:%02d".format(seconds / 60, seconds % 60) else ""
                    add(GalleryRow(photo.key, GalleryRow.PHOTO, subtitle = duration,
                        cover = photo, selected = photo.key in selected, selecting = selecting))
                }
                add(GalleryRow("count", GalleryRow.FOOTER, getString(R.string.gallery_count, photos.size)))
            }
        } else {
            val active = data.photos.filterNot { it.media.isTrashed }.sortedByDescending { it.taken }
            fun card(id: String, name: String, items: List<GalleryPhoto>, kind: Int, icon: Int = R.drawable.ic_albums) =
                GalleryRow(id, kind, name, items.size.toString(), items.firstOrNull(), id, icon)
            if (query.category in listOf("all", "common")) {
                add(GalleryRow("commonTitle", GalleryRow.SECTION, getString(R.string.gallery_common), action = "common"))
                add(card("all", getString(R.string.gallery_all), active, GalleryRow.COMMON, R.drawable.ic_image))
                add(card("camera", getString(R.string.gallery_camera), active.filter { it.isCamera }, GalleryRow.COMMON, R.drawable.ic_camera))
                add(card("captures", getString(R.string.gallery_captures), active.filter { it.isCapture }, GalleryRow.COMMON, R.drawable.ic_photo_size_select_actual))
                add(card("videos", getString(R.string.album_videos), active.filter { it.isVideo }, GalleryRow.COMMON, R.drawable.ic_video_camera_back))
                if (query.category == "common") {
                    add(card("favorites", getString(R.string.album_favorites), active.filter { it.media.isFavorite }, GalleryRow.COMMON, R.drawable.ic_star))
                    add(card("trash", getString(R.string.gallery_trash), data.photos.filter { it.media.isTrashed }, GalleryRow.COMMON, R.drawable.ic_delete))
                }
            }
            if (query.category in listOf("all", "albums")) {
                add(GalleryRow("albumsTitle", GalleryRow.SECTION, getString(R.string.albums_title), action = "albums"))
                val albums = buildList {
                    data.collections.filter { it.kind == "album" }.forEach { collection ->
                        add(card("collection:${collection.id}", collectionName(collection, resources),
                            active.filter { it.key in collection.members }, GalleryRow.ALBUM,
                            when (collection.id) { "cards", "documents" -> R.drawable.ic_contact_page
                                "ai" -> R.drawable.ic_star; else -> R.drawable.ic_albums }))
                    }
                    active.groupBy { it.media.albumUri }.values.forEach { bucket ->
                        add(card("bucket:${bucket.first().media.albumUri.lastPathSegment}",
                            bucket.first().media.albumName.orEmpty(), bucket, GalleryRow.ALBUM))
                    }
                }
                if (query.category == "all" && albums.size > 6) {
                    addAll(albums.take(5))
                    add(GalleryRow("other", GalleryRow.ALBUM, getString(R.string.gallery_other),
                        (albums.size - 5).toString(), albums.getOrNull(5)?.cover, "albums"))
                } else addAll(albums)
                if (query.category == "albums") add(GalleryRow("newAlbum", GalleryRow.EMPTY,
                    getString(R.string.gallery_new_album), action = "newAlbum"))
            }
            if (query.category in listOf("all", "people")) {
                add(GalleryRow("peopleTitle", GalleryRow.SECTION, getString(R.string.gallery_people), action = "people"))
                val people = data.collections.filter { it.kind == "person" }
                people.forEach { person ->
                    add(card("collection:${person.id}", person.name, active.filter { it.key in person.members },
                        GalleryRow.PERSON, R.drawable.ic_person))
                }
                add(GalleryRow("newPerson", GalleryRow.EMPTY, getString(R.string.gallery_new_person),
                    if (people.isEmpty()) getString(R.string.gallery_people_hint) else "", action = "newPerson"))
            }
            if (query.category == "all") {
                add(GalleryRow("moreTitle", GalleryRow.SECTION, getString(R.string.gallery_more)))
                add(card("favorites", getString(R.string.album_favorites), active.filter { it.media.isFavorite }, GalleryRow.COMMON, R.drawable.ic_star))
                add(card("trash", getString(R.string.gallery_trash), data.photos.filter { it.media.isTrashed }, GalleryRow.COMMON, R.drawable.ic_delete))
            }
        }
    }

    private fun collectionName(collection: GalleryCollection, resources: Resources = this.resources): String = collection.name.ifEmpty {
        resources.getString(when (collection.id) { "cards" -> R.string.gallery_cards
            "documents" -> R.string.gallery_documents; "ai" -> R.string.gallery_ai
            else -> R.string.albums_title })
    }

    private fun categoryName(category: String, data: GalleryLibrary = library,
        resources: Resources = this.resources): String = when (category) {
        "all" -> resources.getString(R.string.gallery_photos)
        "camera" -> resources.getString(R.string.gallery_camera)
        "captures" -> resources.getString(R.string.gallery_captures)
        "videos" -> resources.getString(R.string.album_videos)
        "favorites" -> resources.getString(R.string.album_favorites)
        "common" -> resources.getString(R.string.gallery_common)
        "albums" -> resources.getString(R.string.albums_title)
        "people" -> resources.getString(R.string.gallery_people)
        else -> if (category.startsWith("collection:")) data.collection(category.removePrefix("collection:"))
            ?.let { collectionName(it, resources) }.orEmpty() else data.photos.firstOrNull {
                it.media.albumUri.lastPathSegment == category.removePrefix("bucket:")
            }?.media?.albumName.orEmpty()
    }

    private fun iconButton(icon: Int, description: Int, action: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon)
        contentDescription = getString(description)
        tooltipText = contentDescription
        imageTintList = ColorStateList.valueOf(context.tone(MaterialR.attr.colorOnSurface))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        clickableSurface(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
        setOnClickListener { action() }
    }

    private fun renderToolbar() {
        val query = model.currentQuery
        val mode = if (model.selecting) "select:${model.selected.size}" else if (model.searching) "search"
            else "${query.category}:${model.page}"
        if (toolbarMode == mode) return
        toolbarMode = mode
        toolbar.removeAllViews()
        searchEdit = null
        val c = requireContext()
        if (model.searching && !model.selecting) {
            toolbar.addView(iconButton(R.drawable.ic_back, android.R.string.cancel) { back.handleOnBackPressed() })
            val edit = EditText(c).apply {
                id = R.id.gallerySearchInput
                hint = getString(R.string.gallery_search_hint)
                textSize = 16f
                setSingleLine(true)
                setText(query.text)
                background = null
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                doAfterTextChanged { model.currentQuery = model.currentQuery.copy(text = it.toString()) }
                setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
            }
            searchEdit = edit
            toolbar.addView(edit, LinearLayout.LayoutParams(0, -1, 1f))
            toolbar.addView(iconButton(R.drawable.ic_close, R.string.gallery_clear_search) { edit.setText("") })
        } else {
            val nested = query.category != "all" || model.selecting
            if (nested) toolbar.addView(iconButton(if (model.selecting) R.drawable.ic_close else R.drawable.ic_back,
                android.R.string.cancel) { back.handleOnBackPressed() })
            val title = c.label(if (model.selecting) getString(R.string.gallery_selected, model.selected.size)
                else if (nested && !isGrid()) categoryName(query.category) else "", 16f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            toolbar.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
            if (model.selecting) {
                toolbar.addView(iconButton(R.drawable.ic_check, R.string.gallery_select_all) {
                    if (visiblePhotos.size > 500) toast(R.string.gallery_selection_limit)
                    model.selected = visiblePhotos.take(500).mapTo(linkedSetOf()) { it.key }
                    render()
                })
            } else if (isGrid()) {
                toolbar.addView(iconButton(R.drawable.ic_gallery_filter, R.string.gallery_filter, ::showFilter))
            }
            toolbar.addView(iconButton(R.drawable.ic_gallery_more, R.string.gallery_more) {
                showMenu(toolbar.getChildAt(toolbar.childCount - 1))
            })
        }
    }

    private fun glass(view: View) {
        val c = view.context
        view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            ColorUtils.setAlphaComponent(c.tone(MaterialR.attr.colorSurfaceContainerHigh), 245),
            ColorUtils.setAlphaComponent(c.tone(MaterialR.attr.colorSurfaceContainer), 235))).apply {
            cornerRadius = c.dp(32).toFloat()
            setStroke(c.dp(1), ColorUtils.setAlphaComponent(c.tone(MaterialR.attr.colorOnSurface), 24))
        }
        view.elevation = c.dp(3).toFloat()
    }

    private fun renderBottom() {
        bottom.removeAllViews()
        val c = requireContext()
        val group = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; glass(this) }
        fun button(label: Int, icon: Int, selected: Boolean = false, action: () -> Unit): View {
            val item = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(c.dp(6), c.dp(5), c.dp(6), c.dp(4))
                clickableSurface(if (selected) ColorUtils.setAlphaComponent(c.tone(MaterialR.attr.colorSecondaryContainer), 160)
                    else Color.TRANSPARENT, 28)
                contentDescription = getString(label)
                isSelected = selected
                setOnClickListener { action() }
            }
            val image = ImageView(c).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(c.tone(MaterialR.attr.colorOnSurface))
            }
            item.addView(image, LinearLayout.LayoutParams(c.dp(23), c.dp(23)))
            item.addView(c.label(getString(label), 12f).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(3), 0, 0) })
            return item
        }
        if (model.selecting) {
            val target = model.collectionTarget
            val actions: List<Pair<Pair<Int, Int>, () -> Unit>> = if (target != null) listOf(
                (R.string.gallery_done to R.drawable.ic_done) to { completeAdd(target) }
            ) else listOf(
                (R.string.gallery_share to R.drawable.ic_share) to { share() },
                (R.string.gallery_add_to to R.drawable.ic_gallery_add) to { chooseCollection() },
                (R.string.gallery_favorite to R.drawable.ic_star_border) to { favorite() },
                (R.string.gallery_delete to R.drawable.ic_delete) to { trash() },
            )
            actions.forEach { (info, action) ->
                group.addView(button(info.first, info.second, action = action).apply {
                    isEnabled = model.selected.isNotEmpty()
                    alpha = if (isEnabled) 1f else .38f
                }, LinearLayout.LayoutParams(c.dp(76), c.dp(56)))
            }
            bottom.addView(group)
        } else {
            group.addView(button(R.string.gallery_photos, R.drawable.ic_image, model.page == 0 && !model.searching) {
                switchTab(0)
            }, LinearLayout.LayoutParams(c.dp(86), c.dp(56)))
            group.addView(button(R.string.gallery_collections, R.drawable.ic_albums, model.page == 1 && !model.searching) {
                switchTab(1)
            }, LinearLayout.LayoutParams(c.dp(86), c.dp(56)))
            bottom.addView(group)
            val search = iconButton(R.drawable.ic_gallery_search, R.string.gallery_search) {
                model.searching = true
                model.currentQuery = GalleryQuery()
                scrollToStart = true
                render()
                searchEdit?.apply {
                    requestFocus()
                    post { c.getSystemService<InputMethodManager>()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }
            }
            glass(search)
            bottom.addView(search, LinearLayout.LayoutParams(c.dp(56), c.dp(56)).apply { marginStart = c.dp(12) })
        }
    }

    private fun switchTab(page: Int) {
        hideKeyboard()
        model.searching = false
        model.currentQuery = GalleryQuery()
        model.page = page
        scrollToStart = true
        render()
    }

    private fun open(category: String) {
        if (category == "trash") {
            findNavController().navigate(R.id.action_mainFragment_to_fragment_album,
                AlbumFragment.createBundle(albumType = AlbumType.TRASH))
            return
        }
        model.currentQuery = GalleryQuery(category)
        model.searching = false
        scrollToStart = true
        render()
    }

    private fun onClick(row: GalleryRow) {
        if (row.kind == GalleryRow.PHOTO) {
            val photo = row.cover ?: return
            if (model.selecting) toggle(photo.key) else {
                hideKeyboard()
                startActivity(Intent(requireContext(), ViewActivity::class.java).apply {
                    action = MediaStore.ACTION_REVIEW
                    setDataAndType(photo.media.uri, photo.media.mimeType)
                    putExtras(ViewActivity.createBundle(albumType = AlbumType.REELS))
                    putExtra(ViewActivity.EXTRA_HOME_QUERY, model.currentQuery.encode())
                })
            }
        } else when {
            row.action == "all" -> switchTab(0)
            row.action == "newAlbum" -> createCollection(false)
            row.action == "newPerson" -> createCollection(true)
            row.action.startsWith("add:") -> beginAdd(row.action.removePrefix("add:"))
            row.action.isNotEmpty() -> open(row.action)
        }
    }

    private fun onHold(row: GalleryRow) {
        if (row.kind == GalleryRow.PHOTO) {
            model.selecting = true
            row.cover?.let { toggle(it.key) }
        } else if (row.action.startsWith("collection:")) {
            collectionMenu(row.action.removePrefix("collection:"))
        }
    }

    private fun toggle(key: String) {
        if (key !in model.selected && model.selected.size >= 500) {
            toast(R.string.gallery_selection_limit); return
        }
        model.selected = if (key in model.selected) model.selected - key else model.selected + key
        render()
    }

    private fun clearSelection() {
        model.selected = emptySet()
        model.selecting = false
        model.collectionTarget = null
        render()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (model.selecting && model.currentQuery.category.startsWith("collection:")) {
            actions += R.string.gallery_remove_from_album to {
                model.store.updateMembers(model.currentQuery.category.removePrefix("collection:"), model.selected, false)
                clearSelection(); toast(R.string.gallery_removed)
            }
        } else if (model.selecting) {
            actions += R.string.gallery_select_all to {
                if (visiblePhotos.size > 500) toast(R.string.gallery_selection_limit)
                model.selected = visiblePhotos.take(500).mapTo(linkedSetOf()) { it.key }; render()
            }
        } else {
            if (isGrid()) actions += R.string.gallery_select to { model.selecting = true; render() }
            model.currentQuery.category.takeIf { it.startsWith("collection:") }?.removePrefix("collection:")?.let { id ->
                actions += R.string.gallery_add_photos to { beginAdd(id) }
                actions += R.string.gallery_rename to { rename(id) }
            }
            actions += R.string.gallery_new_album to { createCollection(false) }
            actions += R.string.gallery_new_person to { createCollection(true) }
            actions += R.string.album_favorites to { open("favorites") }
            actions += R.string.gallery_trash to { open("trash") }
            actions += R.string.title_activity_settings to { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        }
        actions.forEachIndexed { index, entry -> popup.menu.add(0, index, index, entry.first) }
        popup.setOnMenuItemClickListener { actions[it.itemId].second(); true }
        popup.show()
    }

    private fun showFilter() {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.gallery_filter)
            .setItems(arrayOf(getString(R.string.gallery_media_type), getString(R.string.gallery_order), getString(R.string.gallery_grid))) { _, which ->
                when (which) {
                    0 -> choices(R.string.gallery_media_type, listOf(R.string.gallery_all_types, R.string.gallery_images_only, R.string.gallery_videos_only),
                        listOf("all", "images", "videos").indexOf(model.currentQuery.mediaType)) {
                        model.currentQuery = model.currentQuery.copy(mediaType = listOf("all", "images", "videos")[it]); scrollToStart = true
                    }
                    1 -> choices(R.string.gallery_order, listOf(R.string.gallery_newest, R.string.gallery_oldest),
                        if (model.currentQuery.oldestFirst) 1 else 0) {
                        model.currentQuery = model.currentQuery.copy(oldestFirst = it == 1); scrollToStart = true
                    }
                    2 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.gallery_grid)
                        .setSingleChoiceItems(arrayOf(3, 4, 6).map { getString(R.string.gallery_grid_option, it) }.toTypedArray(),
                            listOf(3, 4, 6).indexOf(columns)) { dialog, index ->
                            columns = listOf(3, 4, 6)[index]
                            model.store.preferences.edit().putInt("columns", columns).apply()
                            layout.spanSizeLookup.invalidateSpanIndexCache()
                            list.requestLayout(); dialog.dismiss()
                        }.show()
                }
            }.show()
    }

    private fun choices(title: Int, labels: List<Int>, selected: Int, action: (Int) -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setSingleChoiceItems(labels.map { getString(it) }.toTypedArray(), selected) { dialog, index ->
                action(index); dialog.dismiss()
            }.show()
    }

    private fun nameDialog(title: Int, initial: String = "", action: (String) -> Unit) {
        val c = requireContext()
        val input = EditText(c).apply {
            hint = getString(R.string.gallery_name); setSingleLine(); setText(initial)
            filters = arrayOf(InputFilter.LengthFilter(80))
            setSelectAllOnFocus(true)
        }
        val wrapper = FrameLayout(c).apply {
            setPadding(c.dp(24), c.dp(8), c.dp(24), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(c).setTitle(title).setView(wrapper)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.gallery_done, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) input.error = getString(R.string.gallery_name_error)
                else { action(name); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun createCollection(person: Boolean) = nameDialog(if (person) R.string.gallery_new_person else R.string.gallery_new_album) {
        val collection = model.store.create(it, if (person) "person" else "album")
        if (model.selecting && model.selected.isNotEmpty()) {
            model.store.updateMembers(collection.id, model.selected, true)
            clearSelection(); toast(R.string.gallery_added)
        } else beginAdd(collection.id)
    }

    private fun rename(id: String) {
        val collection = library.collection(id) ?: return
        nameDialog(R.string.gallery_rename, collectionName(collection)) { model.store.rename(id, it) }
    }

    private fun collectionMenu(id: String) {
        val builtin = id in listOf("cards", "documents", "ai")
        val labels = if (builtin) listOf(R.string.gallery_add_photos, R.string.gallery_rename)
            else listOf(R.string.gallery_add_photos, R.string.gallery_rename, R.string.gallery_remove_album)
        MaterialAlertDialogBuilder(requireContext()).setItems(labels.map { getString(it) }.toTypedArray()) { _, index ->
            when (index) {
                0 -> beginAdd(id)
                1 -> rename(id)
                2 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.gallery_remove_album)
                    .setMessage(R.string.gallery_remove_album_message).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.gallery_remove_album) { _, _ -> model.store.remove(id) }.show()
            }
        }.show()
    }

    private fun beginAdd(id: String) {
        model.collectionTarget = id
        model.selected = emptySet()
        model.selecting = true
        model.page = 0
        open("all")
    }

    private fun completeAdd(id: String) {
        if (model.selected.isEmpty()) return
        model.store.updateMembers(id, model.selected, true)
        clearSelection()
        model.page = 1
        open("collection:$id")
        toast(R.string.gallery_added)
    }

    private fun chooseCollection() {
        if (model.selected.isEmpty()) return
        val collections = library.collections
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.gallery_add_to)
            .setItems((collections.map { collectionName(it) } + getString(R.string.gallery_new_album)).toTypedArray()) { _, index ->
                if (index == collections.size) createCollection(false) else {
                    model.store.updateMembers(collections[index].id, model.selected, true)
                    clearSelection(); toast(R.string.gallery_added)
                }
            }.show()
    }

    private fun selectedMedia() = library.photos.filter { it.key in model.selected }.map { it.media }
    private fun share() {
        val media = selectedMedia()
        if (media.isNotEmpty()) startActivity(Intent.createChooser(buildShareIntent(*media.toTypedArray()), null))
    }

    private fun favorite() {
        val media = selectedMedia()
        if (media.isNotEmpty()) runCatching {
            operation.launch(requireContext().contentResolver.createFavoriteRequest(true, *media.map { it.uri }.toTypedArray()))
        }.onFailure { toast(R.string.gallery_operation_failed) }
    }

    private fun trash() {
        val media = selectedMedia()
        if (media.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.gallery_delete)
            .setMessage(getString(R.string.gallery_trash_confirm, media.size))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.gallery_delete) { _, _ ->
                runCatching {
                    operation.launch(requireContext().contentResolver.createTrashRequest(true, *media.map { it.uri }.toTypedArray()))
                }.onFailure { toast(R.string.gallery_operation_failed) }
            }.show()
    }

    private fun hideKeyboard() {
        context?.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(view?.windowToken, 0)
        searchEdit?.clearFocus()
    }

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        renderJob?.cancel()
        list.adapter = null
        adapter = null
        searchEdit = null
        toolbarMode = ""
        super.onDestroyView()
    }
}
