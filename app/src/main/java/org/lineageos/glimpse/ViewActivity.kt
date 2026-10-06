/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.datasources.MediaError
import org.lineageos.glimpse.ext.buildEditIntent
import org.lineageos.glimpse.ext.buildShareIntent
import org.lineageos.glimpse.ext.buildUseAsIntent
import org.lineageos.glimpse.ext.createDeleteRequest
import org.lineageos.glimpse.ext.createFavoriteRequest
import org.lineageos.glimpse.ext.createTrashRequest
import org.lineageos.glimpse.ext.fade
import org.lineageos.glimpse.ext.setBarsVisibility
import org.lineageos.glimpse.home.GalleryFolderActions
import org.lineageos.glimpse.home.GalleryStore
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.AlbumType
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.MotionPhoto
import org.lineageos.glimpse.models.RequestStatus
import org.lineageos.glimpse.ui.dialogs.MediaInfoBottomSheetDialog
import org.lineageos.glimpse.ui.recyclerview.MediaViewerAdapter
import org.lineageos.glimpse.utils.MediaDialogsUtils
import org.lineageos.glimpse.utils.PermissionsChecker
import org.lineageos.glimpse.utils.PermissionsUtils
import org.lineageos.glimpse.viewmodels.IntentsViewModel
import org.lineageos.glimpse.viewmodels.IntentsViewModel.ParsedIntent
import org.lineageos.glimpse.viewmodels.LocalPlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * An activity used to view one or mode medias.
 */
class ViewActivity : AppCompatActivity(R.layout.activity_view) {
    // View models
    private val viewModel by viewModels<LocalPlayerViewModel>()
    private val intentsViewModel by viewModels<IntentsViewModel>()

    // Views
    private val adjustButton by lazy { findViewById<MaterialButton>(R.id.adjustButton) }
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val backButton by lazy { findViewById<MaterialButton>(R.id.backButton) }
    private val bottomSheetLinearLayout by lazy { findViewById<LinearLayout>(R.id.bottomSheetLinearLayout) }
    private val deleteButton by lazy { findViewById<MaterialButton>(R.id.deleteButton) }
    private val favoriteButton by lazy { findViewById<MaterialButton>(R.id.favoriteButton) }
    private val mediaDateTextView by lazy { findViewById<TextView>(R.id.mediaDateTextView) }
    private val mediaSourceTextView by lazy { findViewById<TextView>(R.id.mediaSourceTextView) }
    private val moreButton by lazy { findViewById<MaterialButton>(R.id.moreButton) }
    private val motionPhotoToggleButton by lazy { findViewById<MaterialButton>(R.id.motionPhotoToggleButton) }
    private val rotateButton by lazy { findViewById<MaterialButton>(R.id.rotateButton) }
    private val shareButton by lazy { findViewById<MaterialButton>(R.id.shareButton) }
    private val viewerRoot by lazy { findViewById<View>(R.id.viewerRoot) }
    private val viewPager by lazy { findViewById<ViewPager2>(R.id.viewPager) }

    // System services
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    private var lastVideoUriPlayed: Uri? = null
    private var moreActionsPopup: PopupMenu? = null
    private var preparingFolderTransfer = false

    // Adapter
    private val mediaViewerAdapter by lazy {
        MediaViewerAdapter(
            localPlayerViewModel = viewModel,
            onNavigate = { forward ->
                viewPager.adapter?.let { adapter ->
                    val currentPosition = viewPager.currentItem

                    val newPosition = if (forward) {
                        currentPosition + 1
                    } else {
                        currentPosition - 1
                    }

                    if (newPosition in 0 until adapter.itemCount) {
                        viewPager.setCurrentItem(newPosition, true)
                    }
                }
            },
        )
    }

    private var lastProcessedMedia: Media? = null

    // Contracts
    private val deleteUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showDeleteForeverResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
            )
        }

    private val trashUriContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showMoveToTrashResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
                lastProcessedMedia?.let { trashedMedia ->
                    { trashMedia(trashedMedia, false) }
                },
            )

            lastProcessedMedia = null
        }

    private val restoreUriFromTrashContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            val succeeded = it.resultCode != RESULT_CANCELED

            MediaDialogsUtils.showRestoreFromTrashResultSnackbar(
                this,
                bottomSheetLinearLayout,
                succeeded, 1,
                bottomSheetLinearLayout,
                lastProcessedMedia?.let { trashedMedia ->
                    { trashMedia(trashedMedia, true) }
                },
            )
        }

    private val favoriteContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Do nothing
        }

    private val onPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)

            this@ViewActivity.viewModel.setMediaPosition(position)
        }
    }

    private val mediaInfoBottomSheetDialogCallbacks = MediaInfoBottomSheetDialog.Callbacks(this)

    // Intents
    private val intentListener = Consumer<Intent> { intentsViewModel.onIntent(it) }

    // Permissions
    private val permissionsChecker = PermissionsChecker(this, PermissionsUtils.mainPermissions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // We only want to show this activity on top of the keyguard if we're being launched with
        // the ACTION_REVIEW_SECURE intent and the system is currently locked.
        if (keyguardManager.isKeyguardLocked && intent.action == MediaStore.ACTION_REVIEW_SECURE) {
            setShowWhenLocked(true)
        }

        val actionPadding = resources.getDimensionPixelSize(R.dimen.viewer_action_padding)
        ViewCompat.setOnApplyWindowInsetsListener(viewerRoot) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            appBarLayout.updatePadding(
                left = insets.left, top = insets.top, right = insets.right,
            )
            bottomSheetLinearLayout.updatePadding(
                left = actionPadding + insets.left,
                right = actionPadding + insets.right,
                bottom = actionPadding + insets.bottom,
            )
            viewPager.updatePadding(left = insets.left, right = insets.right)
            viewerRoot.post(::updateSheetsHeight)

            windowInsets
        }

        // Attach the adapter to the view pager
        viewPager.adapter = mediaViewerAdapter

        backButton.setOnClickListener {
            finish()
        }

        moreButton.setOnClickListener { showMoreActions() }

        rotateButton.setOnClickListener {
            requestedOrientation = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            }
        }

        appBarLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSheetsHeight()
        }
        bottomSheetLinearLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSheetsHeight()
        }

        favoriteButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    favoriteContract.launch(
                        contentResolver.createFavoriteRequest(
                            !it.isFavorite, it.uri
                        )
                    )
                }
            }
        }

        shareButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    startActivity(
                        Intent.createChooser(
                            buildShareIntent(it),
                            null
                        )
                    )
                }
            }
        }

        adjustButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    startActivity(
                        Intent.createChooser(
                            buildEditIntent(it),
                            null
                        )
                    )
                }
            }
        }

        deleteButton.setOnClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    trashMedia(it)
                }
            }
        }

        deleteButton.setOnLongClickListener {
            viewModel.displayedMedia.value?.let {
                dismissKeyguardAndRun {
                    MediaDialogsUtils.openDeleteForeverDialog(this, it.uri) { uris ->
                        deleteUriContract.launch(contentResolver.createDeleteRequest(*uris))
                    }
                }
                return@setOnLongClickListener true
            }

            false
        }

        motionPhotoToggleButton.setOnClickListener {
            viewModel.toggleMotionPhotoEnabled()
        }

        viewPager.offscreenPageLimit = 2
        viewPager.registerOnPageChangeCallback(onPageChangeCallback)

        intentListener.accept(intent)
        addOnNewIntentListener(intentListener)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        viewModel.play()
    }

    override fun onPause() {
        saveCurrentVideoPosition()

        viewModel.pause()

        super.onPause()
    }

    override fun onDestroy() {
        saveCurrentVideoPosition()
        moreActionsPopup?.dismiss()

        removeOnNewIntentListener(intentListener)

        viewPager.unregisterOnPageChangeCallback(onPageChangeCallback)

        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        updateSheetsHeight()
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                intentsViewModel.parsedIntent.collectLatest { parsedIntent ->
                    parsedIntent?.handle {
                        when (it) {
                            is ParsedIntent.ViewIntent,
                            is ParsedIntent.ReviewIntent,
                            is ParsedIntent.SecureReviewIntent -> {
                                viewModel.setParsedIntent(it)
                            }

                            else -> run {
                                Toast.makeText(
                                    this@ViewActivity,
                                    R.string.intent_action_not_supported,
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }

                    }
                }
            }

            launch {
                viewModel.mediasWithInitialPosition.collect {
                    when (it) {
                        is RequestStatus.Loading -> {
                            // Do nothing
                        }

                        is RequestStatus.Success -> {
                            val (medias, initialPosition) = it.data

                            mediaViewerAdapter.submitList(medias)

                            initialPosition?.let { position ->
                                viewPager.setCurrentItem(position, false)
                                onPageChangeCallback.onPageSelected(position)

                                viewModel.setMediaPosition(position)
                            }

                            if (medias.isEmpty()) {
                                // Get out of here
                                finish()
                            }
                        }

                        is RequestStatus.Error -> {
                            Log.e(LOG_TAG, "Failed to load medias, error: ${it.error}")

                            mediaViewerAdapter.submitList(listOf())

                            if (it.error == MediaError.NOT_FOUND) {
                                // Get out of here
                                finish()
                            }
                        }
                    }
                }
            }

            launch {
                viewModel.isPlaying.collectLatest { isPlaying ->
                    viewPager.keepScreenOn = isPlaying
                }
            }

            launch {
                viewModel.fullscreenMode.collectLatest { fullscreenMode ->
                    appBarLayout.fade(!fullscreenMode)
                    bottomSheetLinearLayout.fade(!fullscreenMode)

                    window.setBarsVisibility(systemBars = !fullscreenMode)

                    ViewCompat.requestApplyInsets(viewerRoot)
                    viewerRoot.post(::updateSheetsHeight)
                }
            }

            launch {
                viewModel.displayedMedia.collectLatest { displayedMedia ->
                    // Update date and time text
                    displayedMedia?.also {
                        val captureDate = captureDate(it)
                        mediaDateTextView.text = dateFormatter.format(captureDate)
                        val time = timeFormatter.format(captureDate)
                        mediaSourceTextView.text = it.albumName?.takeIf(String::isNotBlank)?.let { source ->
                            getString(R.string.viewer_time_source, time, source)
                        } ?: time
                    } ?: run {
                        mediaDateTextView.text = ""
                        mediaSourceTextView.text = ""
                    }

                    // Update favorite button
                    val isFavorite = displayedMedia?.isFavorite ?: false
                    favoriteButton.isSelected = isFavorite
                    favoriteButton.setText(
                        when (isFavorite) {
                            true -> R.string.viewer_favorited
                            false -> R.string.viewer_favorite
                        }
                    )
                    favoriteButton.contentDescription = getString(
                        when (isFavorite) {
                            true -> R.string.file_action_remove_from_favorites
                            false -> R.string.file_action_add_to_favorites
                        }
                    )
                    moreButton.isEnabled = displayedMedia != null

                    // Update delete button
                    val isTrashed = displayedMedia?.isTrashed ?: false
                    deleteButton.text = when (isTrashed) {
                        true -> getString(R.string.viewer_restore)
                        false -> getString(R.string.viewer_delete)
                    }
                    deleteButton.contentDescription = getString(
                        when (isTrashed) {
                            true -> R.string.file_action_restore_from_trash
                            false -> R.string.file_action_move_to_trash
                        }
                    )
                    deleteButton.setCompoundDrawablesWithIntrinsicBounds(
                        0,
                        when (isTrashed) {
                            true -> R.drawable.ic_restore_from_trash
                            false -> R.drawable.ic_delete
                        },
                        0,
                        0
                    )

                    // Reset motion photo toggle button
                    viewModel.toggleMotionPhotoEnabled(false)
                }
            }

            launch {
                viewModel.displayedMediaToMotionPhoto.collectLatest { (displayedMedia, motionPhoto) ->
                    // Update ExoPlayer
                    displayedMedia?.let {
                        updateExoPlayer(it, motionPhoto)
                    }

                    val isPlayingMotionPhoto = motionPhoto != null
                    motionPhotoToggleButton.isSelected = isPlayingMotionPhoto
                    motionPhotoToggleButton.setText(
                        when (isPlayingMotionPhoto) {
                            true -> R.string.motion_photo_show_photo
                            false -> R.string.motion_photo_show_video
                        }
                    )

                    // Trigger a sheets height update
                    updateSheetsHeight()
                }
            }

            launch {
                viewModel.motionPhoto.collectLatest { motionPhoto ->
                    motionPhotoToggleButton.isVisible = motionPhoto != null
                }
            }

            launch {
                // Keep the WhileSubscribed secure state active even when the menu is closed.
                viewModel.secure.collectLatest { secure ->
                    moreActionsPopup?.menu?.findItem(R.id.useAs)?.isVisible =
                        !secure && !keyguardManager.isKeyguardLocked
                }
            }

            launch {
                viewModel.readOnly.collectLatest { readOnly ->
                    // Update favorite button
                    favoriteButton.isVisible = !readOnly

                    // Update adjust button
                    adjustButton.isVisible = !readOnly

                    // Update delete button
                    deleteButton.isVisible = !readOnly
                }
            }
        }
    }

    /**
     * Update exoPlayer's status.
     * @param media The currently displayed [Media]
     */
    private fun updateExoPlayer(media: Media, motionPhoto: MotionPhoto?) {
        if (media.mediaType == MediaType.VIDEO) {
            if (media.uri != lastVideoUriPlayed) {
                saveCurrentVideoPosition()
                lastVideoUriPlayed = media.uri
                viewModel.setCurrentVideoUri(media.uri)
            }
        } else {
            saveCurrentVideoPosition()
            motionPhoto?.also(viewModel::playMotionPhoto) ?: viewModel.stop()

            // Make sure we will forcefully reload and restart the video
            lastVideoUriPlayed = null
        }
    }

    private fun saveCurrentVideoPosition() {
        viewModel.saveCurrentVideoPosition(lastVideoUriPlayed)
    }

    private fun trashMedia(media: Media, trash: Boolean = !media.isTrashed) {
        if (trash) {
            lastProcessedMedia = media
        }

        val contract = when (trash) {
            true -> trashUriContract
            false -> restoreUriFromTrashContract
        }

        contract.launch(
            contentResolver.createTrashRequest(
                trash, media.uri
            )
        )
    }

    private fun updateSheetsHeight() {
        viewModel.setSheetsHeight(
            appBarLayout.height,
            bottomSheetLinearLayout.height,
        )
    }

    private fun showMoreActions() {
        val media = viewModel.displayedMedia.value ?: return
        PopupMenu(this, moreButton).apply {
            moreActionsPopup = this
            menuInflater.inflate(R.menu.activity_view_toolbar, menu)
            menu.findItem(R.id.useAs).isVisible =
                !viewModel.secure.value && !keyguardManager.isKeyguardLocked
            val canTransfer = !viewModel.readOnly.value && !media.isTrashed
            menu.findItem(R.id.moveToFolder).isVisible = canTransfer
            menu.findItem(R.id.copyToFolder).isVisible = canTransfer
            setOnDismissListener { moreActionsPopup = null }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.info -> {
                        MediaInfoBottomSheetDialog(
                            this@ViewActivity,
                            media,
                            mediaInfoBottomSheetDialogCallbacks,
                            viewModel.secure.value || keyguardManager.isKeyguardLocked,
                        ).show()
                        true
                    }
                    R.id.useAs -> {
                        if (!viewModel.secure.value && !keyguardManager.isKeyguardLocked) {
                            startActivity(Intent.createChooser(buildUseAsIntent(media), null))
                        }
                        true
                    }
                    R.id.moveToFolder, R.id.copyToFolder -> {
                        showFolderDestination(media, item.itemId == R.id.copyToFolder)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showFolderDestination(media: Media, copy: Boolean) {
        if (viewModel.readOnly.value || media.isTrashed) return
        dismissKeyguardAndRun {
            if (preparingFolderTransfer || viewModel.readOnly.value || keyguardManager.isKeyguardLocked) {
                return@dismissKeyguardAndRun
            }
            preparingFolderTransfer = true
            lifecycleScope.launch {
                try {
                    val photo = withContext(Dispatchers.IO) {
                        val photos = GalleryStore(applicationContext).readPhotos()
                        photos.firstOrNull { it.key == media.uri.toString() } ?: run {
                            // Older Review intents use the merged external volume. Only MediaStore
                            // URIs may fall back to their ID; arbitrary provider IDs are unrelated.
                            val mergedId = media.uri.takeIf {
                                it.scheme == "content" && it.authority == "media" &&
                                    it.pathSegments.firstOrNull() == MediaStore.VOLUME_EXTERNAL
                            }?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
                            photos.firstOrNull {
                                mergedId != null && it.media.mediaType == media.mediaType &&
                                    ContentUris.parseId(it.media.uri) == mergedId
                            }
                        }
                    }
                    if (photo == null) {
                        Toast.makeText(this@ViewActivity, R.string.folder_action_missing, Toast.LENGTH_LONG).show()
                    } else if (!keyguardManager.isKeyguardLocked) {
                        GalleryFolderActions.showDestination(this@ViewActivity, listOf(photo), copy)
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: SecurityException) {
                    Toast.makeText(this@ViewActivity, R.string.folder_action_access, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this@ViewActivity, R.string.folder_action_failed, Toast.LENGTH_LONG).show()
                } finally {
                    preparingFolderTransfer = false
                }
            }
        }
    }

    private suspend fun captureDate(media: Media): Date = withContext(Dispatchers.IO) {
        // Keep the viewer date consistent with the timeline after a copy or rename changes mtime.
        val taken = runCatching {
            contentResolver.query(media.uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
        Date(taken ?: media.dateModified.time.takeIf { it > 0 } ?: media.dateAdded.time)
    }

    private fun dismissKeyguardAndRun(runnable: () -> Unit) {
        if (!keyguardManager.isKeyguardLocked) {
            runnable()
            return
        }

        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    runnable()
                }
            }
        )
    }

    companion object {
        private val LOG_TAG = ViewActivity::class.simpleName!!

        private val dateFormatter = SimpleDateFormat.getDateInstance(SimpleDateFormat.LONG)
        private val timeFormatter = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT)

        val EXTRA_ALBUM_TYPE = "${ViewActivity::class.qualifiedName}.album_type"
        val EXTRA_ALBUM_URI = "${ViewActivity::class.qualifiedName}.album_uri"
        val EXTRA_MEDIA_TYPE = "${ViewActivity::class.qualifiedName}.media_type"
        val EXTRA_MIME_TYPE = "${ViewActivity::class.qualifiedName}.mime_type"
        val EXTRA_HOME_QUERY = "${ViewActivity::class.qualifiedName}.home_query"

        /**
         * Create a [Bundle] to use as the extras for this activity.
         * @param albumType The [AlbumType] to display, null to use [albumUri]
         * @param albumUri The [Album] to display's bucket ID, if null, reels will be shown
         * @param fileType The [MediaType] to filter for
         * @param mimeType The MIME type to filter for
         */
        fun createBundle(
            albumType: AlbumType? = null,
            albumUri: Uri? = null,
            fileType: MediaType? = null,
            mimeType: String? = null,
        ) = bundleOf(
            EXTRA_ALBUM_TYPE to albumType,
            EXTRA_ALBUM_URI to albumUri,
            EXTRA_MEDIA_TYPE to fileType,
            EXTRA_MIME_TYPE to mimeType,
        )
    }
}
