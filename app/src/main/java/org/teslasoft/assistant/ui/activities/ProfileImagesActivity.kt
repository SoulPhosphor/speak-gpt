/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageUsage
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.profileimages.GalleryTile
import org.teslasoft.assistant.ui.adapters.profileimages.ProfileImageGalleryAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.ProfileImageDetailBottomSheetDialogFragment
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Profile Images gallery (profile-images-plan.md, PROFILE IMAGES
 * GALLERY through UPLOAD FLOW). Two launch modes read from [EXTRA_MODE]:
 * [MODE_ASSIGNMENT] (a normal tap returns the bare hash via
 * [EXTRA_RESULT_HASH] and RESULT_OK; no selection/deletion controls exist)
 * and [MODE_MANAGEMENT] (a normal tap opens Image Detail/View Usage; Select
 * enters a deletion Selection Mode). The gallery does not know or edit the
 * destination profile - callers assign the returned hash themselves.
 *
 * Reconciliation and stale framing-session cleanup run once, here, only
 * when the gallery opens (never at app startup) - see [loadGallery].
 */
class ProfileImagesActivity : FragmentActivity(), ProfileImageDetailBottomSheetDialogFragment.Listener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ASSIGNMENT = "assignment"
        const val MODE_MANAGEMENT = "management"
        const val EXTRA_RESULT_HASH = "result_hash"

        private const val MIN_TILE_WIDTH_DP = 110
        private const val HORIZONTAL_ALLOWANCE_DP = 16
    }

    private enum class Filter { ALL_IMAGES, IN_USE, UNUSED }

    private var mode: String = MODE_MANAGEMENT
    private var store: ProfileImageStore? = null

    private var allTiles: List<GalleryTile> = emptyList()
    private var currentFilter: Filter = Filter.ALL_IMAGES
    private var selectionMode = false
    private val selectedHashes = LinkedHashSet<String>()

    private lateinit var adapter: ProfileImageGalleryAdapter

    private var btnBack: ImageButton? = null
    private var btnCancelSelection: ImageButton? = null
    private var activityTitle: TextView? = null
    private var textSelectionCount: TextView? = null
    private var btnSelect: TextView? = null
    private var selectAllContainer: FrameLayout? = null
    private var textSelectAllShown: TextView? = null
    private var btnSelectAllOverflow: ImageButton? = null
    private var chipGroup: ChipGroup? = null
    private var chipAllImages: Chip? = null
    private var chipInUse: Chip? = null
    private var chipUnused: Chip? = null
    private var recycler: RecyclerView? = null
    private var emptyState: LinearLayout? = null
    private var textEmptyTitle: TextView? = null
    private var textEmptyBody: TextView? = null
    private var textInlineStatus: TextView? = null
    private var bottomBar: LinearLayout? = null
    private var btnUploadNew: MaterialButton? = null
    private var btnDeleteSelected: MaterialButton? = null

    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) launchFraming(uri)
        }

    private val framingLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val tempPath = result.data?.getStringExtra(ProfileImageFramingActivity.EXTRA_RESULT_TEMP_PATH)
                if (tempPath != null) handleFramingResult(tempPath)
            }
            // Cancellation at the picker or Framing screen (UPLOAD FLOW):
            // leaves the existing assignment unchanged, creates no record.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_profile_images)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MANAGEMENT
        store = ProfileImageStore.getInstance(this)

        bindViews()
        setupRecycler()
        setupFilterChips()
        setupTopBarActions()
        setupBottomBarActions()
        applyModeVisibility()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadGallery()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val statusTop = window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top
            findViewById<View>(R.id.action_bar)?.setPadding(0, statusTop, 0, 0)

            val navBottom = window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val sidePx = dpToPx(16)
            val vPx = dpToPx(12)
            bottomBar?.setPadding(sidePx, vPx, sidePx, vPx + navBottom)
        } catch (_: Exception) { /* unused */ }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /* ------------------------------ setup ------------------------------ */

    private fun bindViews() {
        btnBack = findViewById(R.id.btn_back)
        btnCancelSelection = findViewById(R.id.btn_cancel_selection)
        activityTitle = findViewById(R.id.activity_title)
        textSelectionCount = findViewById(R.id.text_selection_count)
        btnSelect = findViewById(R.id.btn_select)
        selectAllContainer = findViewById(R.id.select_all_container)
        textSelectAllShown = findViewById(R.id.text_select_all_shown)
        btnSelectAllOverflow = findViewById(R.id.btn_select_all_overflow)
        chipGroup = findViewById(R.id.filter_group)
        chipAllImages = findViewById(R.id.chip_all_images)
        chipInUse = findViewById(R.id.chip_in_use)
        chipUnused = findViewById(R.id.chip_unused)
        recycler = findViewById(R.id.recycler_gallery)
        emptyState = findViewById(R.id.empty_state)
        textEmptyTitle = findViewById(R.id.text_empty_title)
        textEmptyBody = findViewById(R.id.text_empty_body)
        textInlineStatus = findViewById(R.id.text_inline_status)
        bottomBar = findViewById(R.id.bottom_bar)
        btnUploadNew = findViewById(R.id.btn_upload_new)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
    }

    private fun setupRecycler() {
        recycler?.layoutManager = GridLayoutManager(this, computeSpanCount())
        adapter = ProfileImageGalleryAdapter(this) { millis -> formatDate(millis) }
        adapter.listener = ProfileImageGalleryAdapter.Listener { tile -> onTileClicked(tile) }
        recycler?.adapter = adapter
    }

    /** Dynamic span count (PROFILE IMAGES GALLERY: "Phone portrait should
     *  normally produce three columns"), based on a minimum useful
     *  thumbnail width rather than a hardcoded column count. */
    private fun computeSpanCount(): Int {
        val usableWidthDp = resources.configuration.screenWidthDp - HORIZONTAL_ALLOWANCE_DP
        return (usableWidthDp / MIN_TILE_WIDTH_DP).coerceAtLeast(2)
    }

    private fun setupFilterChips() {
        chipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val newFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_in_use -> Filter.IN_USE
                R.id.chip_unused -> Filter.UNUSED
                else -> Filter.ALL_IMAGES
            }
            if (newFilter != currentFilter) {
                currentFilter = newFilter
                applyFilter()
            }
        }
    }

    private fun setupTopBarActions() {
        btnBack?.setOnClickListener { finish() }
        btnCancelSelection?.setOnClickListener { exitSelectionMode() }
        btnSelect?.setOnClickListener { enterSelectionMode() }
        textSelectAllShown?.setOnClickListener { selectAllShown() }
        btnSelectAllOverflow?.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, getString(R.string.profile_image_select_all_shown))
            menu.setOnMenuItemClickListener { selectAllShown(); true }
            menu.show()
        }
    }

    private fun setupBottomBarActions() {
        btnUploadNew?.setOnClickListener { openDocumentLauncher.launch(arrayOf("image/*")) }
        btnDeleteSelected?.setOnClickListener { confirmDeleteSelected() }
    }

    /* ------------------------------ modes ------------------------------ */

    private fun applyModeVisibility() {
        val assignment = mode == MODE_ASSIGNMENT
        if (selectionMode) {
            btnBack?.visibility = View.GONE
            btnCancelSelection?.visibility = View.VISIBLE
            activityTitle?.visibility = View.GONE
            textSelectionCount?.visibility = View.VISIBLE
            btnSelect?.visibility = View.GONE
            selectAllContainer?.visibility = View.VISIBLE
            btnUploadNew?.visibility = View.GONE
            btnDeleteSelected?.visibility = View.VISIBLE
            checkSelectAllTextFit()
        } else {
            btnBack?.visibility = View.VISIBLE
            btnCancelSelection?.visibility = View.GONE
            activityTitle?.visibility = View.VISIBLE
            textSelectionCount?.visibility = View.GONE
            btnSelect?.visibility = if (assignment) View.GONE else View.VISIBLE
            selectAllContainer?.visibility = View.GONE
            btnUploadNew?.visibility = View.VISIBLE
            btnDeleteSelected?.visibility = View.GONE
        }
        chipAllImages?.isEnabled = !selectionMode
        chipInUse?.isEnabled = !selectionMode
        chipUnused?.isEnabled = !selectionMode
        adapter.selectionMode = selectionMode
        updateSelectAvailability()
    }

    /** EXACT GALLERY CONTROL ORDER: never show Select All Shown truncated -
     *  fall back to the overflow icon (same action) when the text would
     *  clip at larger font sizes or narrower widths. */
    private fun checkSelectAllTextFit() {
        val text = textSelectAllShown ?: return
        text.visibility = View.VISIBLE
        btnSelectAllOverflow?.visibility = View.GONE
        text.post {
            val layout = text.layout
            val truncated = layout != null && layout.lineCount > 0 && layout.getEllipsisCount(0) > 0
            if (truncated) {
                text.visibility = View.GONE
                btnSelectAllOverflow?.visibility = View.VISIBLE
            }
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedHashes.clear()
        adapter.updateSelection(emptySet())
        applyModeVisibility()
        updateSelectionCount()
        btnDeleteSelected?.isEnabled = false
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedHashes.clear()
        adapter.updateSelection(emptySet())
        applyModeVisibility()
    }

    /* ------------------------------ filtering ------------------------------ */

    private fun filteredTiles(): List<GalleryTile> = when (currentFilter) {
        Filter.ALL_IMAGES -> allTiles
        Filter.IN_USE -> allTiles.filter { it.isUsed }
        Filter.UNUSED -> allTiles.filter { !it.isUsed }
    }

    private fun applyFilter() {
        val list = filteredTiles()
        adapter.submit(list, selectedHashes.toSet())
        renderEmptyState(list)
        updateSelectAvailability()
    }

    private fun renderEmptyState(list: List<GalleryTile>) {
        val empty = list.isEmpty()
        emptyState?.visibility = if (empty) View.VISIBLE else View.GONE
        recycler?.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) return

        val titleRes: Int
        val bodyRes: Int
        when (currentFilter) {
            Filter.ALL_IMAGES -> {
                titleRes = R.string.profile_image_empty_all_title
                bodyRes = R.string.profile_image_empty_all_body
            }
            Filter.IN_USE -> {
                titleRes = R.string.profile_image_empty_in_use_title
                bodyRes = R.string.profile_image_empty_in_use_body
            }
            Filter.UNUSED -> {
                titleRes = R.string.profile_image_empty_unused_title
                bodyRes = R.string.profile_image_empty_unused_body
            }
        }
        textEmptyTitle?.setText(titleRes)
        textEmptyBody?.setText(bodyRes)
        emptyState?.announceForAccessibility(getString(titleRes) + ". " + getString(bodyRes))
    }

    /** Select Availability (EXACT GALLERY CONTROL ORDER): only when the
     *  current filtered results contain at least one deletion-eligible
     *  unused image - so an empty Selection Mode can never be entered. */
    private fun updateSelectAvailability() {
        if (selectionMode || mode == MODE_ASSIGNMENT) return
        val hasEligible = filteredTiles().any { !it.isUsed }
        btnSelect?.visibility = if (hasEligible) View.VISIBLE else View.GONE
    }

    /* ------------------------------ selection ------------------------------ */

    private fun onTileClicked(tile: GalleryTile) {
        if (selectionMode) {
            toggleSelection(tile.hash)
            return
        }
        if (mode == MODE_ASSIGNMENT) {
            returnAssignmentResult(tile.hash)
        } else {
            openDetail(tile)
        }
    }

    private fun toggleSelection(hash: String) {
        if (!selectedHashes.remove(hash)) selectedHashes.add(hash)
        adapter.updateSelection(selectedHashes.toSet())
        updateSelectionCount()
        btnDeleteSelected?.isEnabled = selectedHashes.isNotEmpty()
    }

    private fun updateSelectionCount() {
        val count = selectedHashes.size
        val text = getString(R.string.profile_image_selection_count, count)
        textSelectionCount?.text = text
        textSelectionCount?.announceForAccessibility(text)
    }

    private fun selectAllShown() {
        val eligible = filteredTiles().filter { !it.isUsed }.map { it.hash }
        selectedHashes.clear()
        selectedHashes.addAll(eligible)
        adapter.updateSelection(selectedHashes.toSet())
        updateSelectionCount()
        btnDeleteSelected?.isEnabled = selectedHashes.isNotEmpty()
    }

    private fun returnAssignmentResult(hash: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_HASH, hash))
        finish()
    }

    /* ------------------------------ data ------------------------------ */

    /** Reconciliation and stale framing-session cleanup run only here, when
     *  the gallery opens (RECONCILIATION, TEMPORARY FILE CLEANUP). A
     *  catalog read failure is treated as an empty gallery rather than a
     *  crash - the empty state still renders and the failure is recorded
     *  to the Error Log for diagnosis. */
    private fun loadGallery() {
        ioScope.launch {
            val tiles = try {
                val s = store
                if (s == null) {
                    emptyList()
                } else {
                    s.cleanupStaleFramingSessions()
                    s.reconcile()
                    val usage = ProfileImageUsage.computeAll(this@ProfileImagesActivity)
                    s.listNewestFirst().map { record ->
                        val file = s.imageFile(record.hash)
                        GalleryTile(
                            hash = record.hash,
                            file = file,
                            createdAt = record.createdAt,
                            isUsed = usage.containsKey(record.hash),
                            corrupted = file != null && !isDecodableImage(file)
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.logAsync(
                    this@ProfileImagesActivity, "crash", "ProfileImagesActivity", "error",
                    "Profile Images catalog failed to load: ${e.javaClass.simpleName}"
                )
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                allTiles = tiles
                applyFilter()
            }
        }
    }

    /** Corrupted (owner-approved word, July 19 2026): the file exists but its
     *  content will not decode as an image - a distinct state from Missing
     *  (the file itself is gone). Bounds-only decode, matching the same
     *  cheap technique ProfileImageStore.reconcile() already uses to
     *  validate files - no full bitmap is allocated just to check this. */
    private fun isDecodableImage(file: File): Boolean = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (_: Exception) {
        false
    }

    private fun formatDate(millis: Long): String = try {
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    } catch (_: Exception) {
        ""
    }

    /* ------------------------------ detail sheet ------------------------------ */

    private fun openDetail(tile: GalleryTile) {
        ioScope.launch {
            // Display-time usage (USAGE RESOLUTION: "advisory").
            val refs = ProfileImageUsage.computeAll(this@ProfileImagesActivity)[tile.hash].orEmpty()
            val used = refs.isNotEmpty()
            val identityLines = ArrayList<String>()
            for (ref in refs) {
                identityLines.add(
                    when (ref.kind) {
                        ProfileImageUsage.Kind.DEFAULT_USER_IMAGE -> getString(R.string.profile_image_usage_default_user_image)
                        ProfileImageUsage.Kind.COMPANION -> getString(R.string.profile_image_usage_companion, ref.name)
                        ProfileImageUsage.Kind.MY_PERSONA -> getString(R.string.profile_image_usage_my_persona, ref.name)
                        ProfileImageUsage.Kind.ROLEPLAY_CHARACTER -> getString(R.string.profile_image_usage_roleplay_character, ref.name)
                    }
                )
            }
            val usageTotalLine = if (used) {
                resources.getQuantityString(R.plurals.profile_image_usage_total, refs.size, refs.size)
            } else null
            val dateAddedLine = getString(R.string.profile_image_date_added, formatDate(tile.createdAt))

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                ProfileImageDetailBottomSheetDialogFragment.newInstance(
                    tile.hash, tile.file?.absolutePath, tile.corrupted, dateAddedLine, used, usageTotalLine, identityLines
                ).show(supportFragmentManager, "profile_image_detail")
            }
        }
    }

    override fun onProfileImageDeletePermanentlyRequested(hash: String) {
        confirmDeleteSingle(hash)
    }

    /* ------------------------------ deletion ------------------------------ */

    private fun confirmDeleteSingle(hash: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.profile_image_delete_confirm_title_one)
            .setMessage(R.string.profile_image_delete_confirm_body_one)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.profile_image_delete_permanently) { _, _ -> performDelete(setOf(hash)) }
            .show()
    }

    private fun confirmDeleteSelected() {
        val hashes = selectedHashes.toSet()
        if (hashes.isEmpty()) return
        val count = hashes.size

        if (count == 1) {
            confirmDeleteSingle(hashes.first())
            return
        }

        val eligibleShown = filteredTiles().filter { !it.isUsed }.map { it.hash }.toSet()
        if (hashes == eligibleShown) {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(getString(R.string.profile_image_delete_confirm_title_all_shown, count))
                .setMessage(R.string.profile_image_delete_confirm_body_all_shown)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(getString(R.string.profile_image_delete_confirm_button_all_shown, count)) { _, _ -> performDelete(hashes) }
                .show()
        } else {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(getString(R.string.profile_image_delete_confirm_title_many, count))
                .setMessage(R.string.profile_image_delete_confirm_body_many)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(getString(R.string.profile_image_delete_confirm_button_many, count)) { _, _ -> performDelete(hashes) }
                .show()
        }
    }

    /** Unused Image Deletion Order (PERMANENT DELETION): deletion-time
     *  usage is authoritative - anything that became used since the
     *  gallery loaded is skipped, file deleted before the catalog record
     *  for each survivor, and the user is told when something was skipped. */
    private fun performDelete(hashes: Set<String>) {
        ioScope.launch {
            val s = store
            val skippedCount: Int
            if (s == null) {
                skippedCount = 0
            } else {
                val currentUsage = ProfileImageUsage.computeAll(this@ProfileImagesActivity)
                val stillUnused = hashes.filter { !currentUsage.containsKey(it) }
                skippedCount = hashes.size - stillUnused.size
                for (hash in stillUnused) s.delete(hash)
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                selectionMode = false
                selectedHashes.clear()
                applyModeVisibility()
                if (skippedCount > 0) {
                    showInlineStatus(getString(R.string.profile_image_some_not_deleted))
                } else {
                    hideInlineStatus()
                }
                loadGallery()
            }
        }
    }

    private fun showInlineStatus(message: String) {
        textInlineStatus?.text = message
        textInlineStatus?.visibility = View.VISIBLE
        textInlineStatus?.announceForAccessibility(message)
    }

    private fun hideInlineStatus() {
        textInlineStatus?.visibility = View.GONE
    }

    /* ------------------------------ upload ------------------------------ */

    private fun launchFraming(sourceUri: Uri) {
        val intent = Intent(this, ProfileImageFramingActivity::class.java)
            .putExtra(ProfileImageFramingActivity.EXTRA_SOURCE_URI, sourceUri.toString())
        framingLauncher.launch(intent)
    }

    /** UPLOAD FLOW: Framing already wrote exactly one 512x512 JPEG q92 into
     *  its session directory: this permanently saves it (dedup by content
     *  hash), then either returns the hash (assignment) or refreshes the
     *  grid (management). ERROR PRESENTATION: an upload/save failure is
     *  never silent. */
    private fun handleFramingResult(tempPath: String) {
        ioScope.launch {
            val tempFile = File(tempPath)
            val bitmap = try {
                if (tempFile.exists()) BitmapFactory.decodeFile(tempFile.absolutePath) else null
            } catch (_: Exception) {
                null
            }

            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    showSaveErrorDialog(R.string.profile_image_save_error_generic_body)
                }
                return@launch
            }

            val hash = store?.save(bitmap)
            // Normal success deletes the session directory (TEMPORARY FILE CLEANUP).
            tempFile.parentFile?.deleteRecursively()

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                when {
                    hash == null -> showSaveErrorDialog(R.string.profile_image_save_error_no_space_body)
                    mode == MODE_ASSIGNMENT -> returnAssignmentResult(hash)
                    else -> loadGallery()
                }
            }
        }
    }

    private fun showSaveErrorDialog(bodyRes: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_single_action, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.profile_image_save_error_title)
            .setMessage(bodyRes)
            .setView(view)
            .create()
        view.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(android.R.string.ok)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.show()
    }
}
