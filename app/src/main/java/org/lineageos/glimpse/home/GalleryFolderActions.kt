/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.home

import android.app.KeyguardManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R

/** Shared destination selection for the grid and the single-media viewer. */
object GalleryFolderActions {
    private val sessions = mutableMapOf<FragmentActivity, DestinationSession>()

    fun showDestination(
        activity: FragmentActivity,
        photos: List<GalleryPhoto>,
        copy: Boolean,
        onComplete: (GalleryFolderResult) -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed || sessions.containsKey(activity)) return
        if (isLocked(activity)) {
            Toast.makeText(activity, R.string.folder_action_unlock, Toast.LENGTH_SHORT).show()
            return
        }
        if (photos.isEmpty()) {
            Toast.makeText(activity, R.string.folder_action_no_media, Toast.LENGTH_SHORT).show()
            return
        }
        DestinationSession(activity, photos.distinctBy { it.key }, copy, onComplete).also {
            sessions[activity] = it
            it.start()
        }
    }

    private class DestinationSession(
        private val activity: FragmentActivity,
        private val photos: List<GalleryPhoto>,
        private val copy: Boolean,
        private val onComplete: (GalleryFolderResult) -> Unit,
    ) : DefaultLifecycleObserver {
        private val store = GalleryStore(activity.applicationContext)
        private var activeDialog: AlertDialog? = null
        private var completed = false
        private var working = false

        private val title get() = if (copy) R.string.folder_action_copy_to else R.string.folder_action_move_to
        private val action get() = if (copy) R.string.folder_action_copy else R.string.folder_action_move

        fun start() {
            activity.lifecycle.addObserver(this)
            showDialog(MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(R.string.folder_action_loading)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() })
            activity.lifecycleScope.launch {
                try {
                    val destinations = withContext(Dispatchers.IO) {
                        store.folders.list(store.readPhotos()).filter { it.canReceive }
                    }
                    if (!completed) showFolders(destinations)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: SecurityException) {
                    finish(GalleryFolderResult(failed = photos.size, error = GalleryFolderError.NEEDS_ACCESS))
                } catch (_: Exception) {
                    finish(GalleryFolderResult(failed = photos.size, error = GalleryFolderError.IO))
                }
            }
        }

        private fun showFolders(destinations: List<GalleryFolder>) {
            var selected: GalleryFolder? = null
            val builder = MaterialAlertDialogBuilder(activity).setTitle(title)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setNeutralButton(R.string.folder_action_new_folder, null)
            if (destinations.isEmpty()) {
                builder.setMessage(R.string.folder_action_no_folders)
            } else {
                builder.setSingleChoiceItems(destinations.map(::label).toTypedArray(), -1) { _, which ->
                    selected = destinations[which]
                    activeDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                }.setPositiveButton(action, null)
            }
            val dialog = showDialog(builder)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = false
                setOnClickListener { selected?.let(::transfer) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                showNewFolder(selected)
            }
        }

        private fun showNewFolder(parent: GalleryFolder?) {
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, 0, padding, 0)
            }
            content.addView(TextView(activity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                text = activity.getString(R.string.folder_action_create_location,
                    parent?.let(::label) ?: activity.getString(R.string.folder_action_default_location))
                setPadding(0, 0, 0, padding / 2)
            })
            val inputLayout = TextInputLayout(activity).apply {
                hint = activity.getString(R.string.folder_action_name)
            }
            val input = TextInputEditText(inputLayout.context).apply {
                isSingleLine = true
                setSelectAllOnFocus(true)
            }
            inputLayout.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            content.addView(inputLayout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val dialog = showDialog(MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.folder_action_new_folder)
                .setView(content)
                .setPositiveButton(R.string.folder_action_create_and_continue, null)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() })
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (working || completed || !requireUnlocked()) return@setOnClickListener
                working = true
                inputLayout.error = null
                input.isEnabled = false
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                activity.lifecycleScope.launch {
                    val result = runOperation {
                        store.folders.create(input.text?.toString().orEmpty().trim(), parent)
                    }
                    if (completed) return@launch
                    working = false
                    val destination = result.folder
                    if (result.error == null && destination != null) {
                        transfer(destination)
                    } else {
                        inputLayout.error = activity.getString(errorString(result.error))
                        input.isEnabled = true
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        private fun transfer(destination: GalleryFolder) {
            if (working || completed || !requireUnlocked()) return
            working = true
            showDialog(MaterialAlertDialogBuilder(activity).setTitle(title)
                .setMessage(if (copy) R.string.folder_action_copying else R.string.folder_action_moving)
                .setCancelable(false))
            activity.lifecycleScope.launch {
                finish(runOperation { store.folders.transfer(photos, destination, copy) })
            }
        }

        private fun requireUnlocked(): Boolean {
            if (!isLocked(activity)) return true
            finish()
            Toast.makeText(activity, R.string.folder_action_unlock, Toast.LENGTH_SHORT).show()
            return false
        }

        private suspend fun runOperation(operation: suspend () -> GalleryFolderResult): GalleryFolderResult =
            try {
                operation()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: SecurityException) {
                GalleryFolderResult(failed = photos.size, error = GalleryFolderError.NEEDS_ACCESS)
            } catch (_: Exception) {
                GalleryFolderResult(failed = photos.size, error = GalleryFolderError.IO)
            }

        private fun showDialog(builder: MaterialAlertDialogBuilder): AlertDialog {
            val previous = activeDialog
            activeDialog = null
            previous?.dismiss()
            return builder.create().also { dialog ->
                activeDialog = dialog
                dialog.setOnCancelListener {
                    if (activeDialog === dialog && !working) finish()
                }
                dialog.setOnDismissListener {
                    // Replacing a picker with the name/progress dialog is not a cancellation.
                    if (activeDialog === dialog && !working) finish()
                }
                dialog.show()
            }
        }

        private fun label(folder: GalleryFolder): String = activity.getString(
            R.string.folder_action_destination_label, folder.path,
            if (folder.volume == android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                activity.getString(R.string.folder_action_internal_storage) else folder.volume,
        )

        private fun finish(result: GalleryFolderResult? = null) {
            if (completed) return
            completed = true
            working = false
            val dialog = activeDialog
            activeDialog = null
            dialog?.dismiss()
            activity.lifecycle.removeObserver(this)
            if (sessions[activity] === this) sessions.remove(activity)
            if (result != null && !activity.isFinishing && !activity.isDestroyed) {
                val counts = activity.getString(
                    if (copy) R.string.folder_action_copy_result else R.string.folder_action_move_result,
                    result.succeeded, result.skipped, result.failed,
                )
                val message = result.error?.let {
                    activity.getString(R.string.folder_action_result_error, counts,
                        activity.getString(errorString(it)))
                } ?: counts
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                onComplete(result)
            }
        }

        override fun onDestroy(owner: LifecycleOwner) { finish() }
    }

    private fun isLocked(activity: FragmentActivity): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun errorString(error: GalleryFolderError?): Int = when (error) {
        GalleryFolderError.INVALID_NAME -> R.string.folder_action_invalid_name
        GalleryFolderError.EXISTS -> R.string.folder_action_exists
        GalleryFolderError.NOT_FOUND -> R.string.folder_action_missing
        GalleryFolderError.NEEDS_ACCESS -> R.string.folder_action_access
        GalleryFolderError.CONTAINS_OTHER_FILES -> R.string.folder_action_other_files
        else -> R.string.folder_action_failed
    }
}
