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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.profileimages.GlobalDefaultImageSeeder
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
 * GALLERY through UPLOAD FLOW). It opens in one of two shapes, chosen by
 * whether an assignment target ([EXTRA_ASSIGN_TARGET]) is present:
 *
 *  - Management (no target): ordinary housekeeping - a normal tap opens
 *    Image Detail/View Usage, Select enters a deletion Selection Mode.
 *
 *  - Assignment (a target is set): the owner's tap-to-assign model
 *    (July 19 2026 - "tapping a picture assigns it; the screen stays open;
 *    to change it, tap a different picture"). A normal tap assigns that
 *    image to the target and the tile takes the Assigned badge (see
 *    [GalleryTile.isAssigned]); there is no detail sheet, no Select, and no
 *    delete here (that all lives in Management mode). The gallery is never
 *    auto-closed by a tap - the user leaves via back. For the two Default
 *    targets the tap writes the preference immediately; for a Companion the
 *    chosen hash is returned to the caller ([EXTRA_RESULT_ASSIGNED_HASH]),
 *    which persists it when the Companion itself is saved. Upload New is
 *    present in both shapes; in Assignment mode a freshly uploaded image is
 *    assigned automatically so the user is not made to find and tap it again.
 *
 * Reconciliation and stale framing-session cleanup run once, here, only
 * when the gallery opens (never at app startup) - see [loadGallery].
 */
class ProfileImagesActivity : FragmentActivity(), ProfileImageDetailBottomSheetDialogFragment.Listener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_MANAGEMENT = "management"
        /** Presence of this extra puts the gallery in Assignment mode.
         *  One of [TARGET_GLOBAL] / [TARGET_PERSONAL] / [TARGET_COMPANION]. */
        const val EXTRA_ASSIGN_TARGET = "assign_target"
        const val TARGET_GLOBAL = "global"
        const val TARGET_PERSONAL = "personal"
        const val TARGET_COMPANION = "companion"
        /** For [TARGET_COMPANION]: the hash the caller currently holds, so the
         *  matching tile opens already showing the Assigned badge. */
        const val EXTRA_ASSIGN_CURRENT_HASH = "assign_current_hash"
        /** Result extra carrying the tapped hash back to a Companion caller. */
        const val EXTRA_RESULT_ASSIGNED_HASH = "result_assigned_hash"

        private const val MIN_TILE_WIDTH_DP = 110
        private const val HORIZONTAL_ALLOWANCE_DP = 16
    }

    private enum class Filter { ALL_IMAGES, IN_USE, UNUSED }

    private var assignTarget: String? = null
    /** The pending pick for a Companion assignment (TARGET_COMPANION only):
     *  what the current tile Assigned badge tracks and what is returned on
     *  leave. Null in Management mode and for the Default targets (those read
     *  and write their preference directly). */
    private var pendingAssignedHash: String? = null
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
    private var textShowLabels: TextView? = null
    private var switchShowLabels: MaterialSwitch? = null
    private var filterDropdownLayout: TextInputLayout? = null
    private var filterDropdown: AutoCompleteTextView? = null
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

        assignTarget = intent.getStringExtra(EXTRA_ASSIGN_TARGET)
        if (assignTarget == TARGET_COMPANION) {
            pendingAssignedHash = intent.getStringExtra(EXTRA_ASSIGN_CURRENT_HASH)?.takeIf { it.isNotEmpty() }
        }
        store = ProfileImageStore.getInstance(this)

        bindViews()
        applyAssignTargetTitle()
        setupRecycler()
        setupFilterDropdown()
        setupShowLabelsToggle()
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
        textShowLabels = findViewById(R.id.text_show_labels)
        switchShowLabels = findViewById(R.id.switch_show_labels)
        filterDropdownLayout = findViewById(R.id.filter_dropdown_layout)
        filterDropdown = findViewById(R.id.filter_dropdown)
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

    /** Assignment mode is any launch that carries an [EXTRA_ASSIGN_TARGET]. */
    private fun isAssignmentMode(): Boolean = assignTarget != null

    /** Sets the gallery title to the Default Avatar / Default Personal
     *  Avatar row's own already-approved text when assigning one of those
     *  (owner ruling, July 19 2026); left as the plain gallery title (the
     *  layout default) for a Companion assignment and for Management mode -
     *  the user arrived from the Companion's own "Add Picture", so no new
     *  title wording is invented here. */
    private fun applyAssignTargetTitle() {
        val titleRes = when (assignTarget) {
            TARGET_GLOBAL -> R.string.row_global_default_title
            TARGET_PERSONAL -> R.string.row_personal_default_title
            else -> return
        }
        activityTitle?.setText(titleRes)
    }

    /** Filter dropdown (owner ruling, July 19 2026, replaced the old filter
     *  chips): All / Active / Inactive map onto the same internal [Filter]
     *  values the chips drove - only the presentation changed. */
    private fun setupFilterDropdown() {
        val labels = listOf(
            getString(R.string.filter_all),
            getString(R.string.filter_active),
            getString(R.string.filter_inactive)
        )
        filterDropdown?.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        filterDropdown?.setText(labels[0], false)
        filterDropdown?.setOnItemClickListener { _, _, position, _ ->
            val newFilter = when (position) {
                1 -> Filter.IN_USE
                2 -> Filter.UNUSED
                else -> Filter.ALL_IMAGES
            }
            if (newFilter != currentFilter) {
                currentFilter = newFilter
                applyFilter()
            }
        }
    }

    /** Show Labels (owner ruling, July 19 2026): plain text + switch, not a
     *  chip/tile - tapping the text also toggles it. Persisted so it holds
     *  across gallery visits. */
    private fun setupShowLabelsToggle() {
        val showLabels = GlobalPreferences.getPreferences(this).getProfileImageShowLabels()
        switchShowLabels?.isChecked = showLabels
        adapter.showLabels = showLabels

        val toggle = {
            val newValue = !(switchShowLabels?.isChecked ?: true)
            switchShowLabels?.isChecked = newValue
        }
        textShowLabels?.setOnClickListener { toggle() }
        switchShowLabels?.setOnCheckedChangeListener { _, checked ->
            GlobalPreferences.getPreferences(this).setProfileImageShowLabels(checked)
            adapter.showLabels = checked
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
            btnSelect?.visibility = View.VISIBLE
            selectAllContainer?.visibility = View.GONE
            btnUploadNew?.visibility = View.VISIBLE
            btnDeleteSelected?.visibility = View.GONE
        }
        // Locked (disabled, still visible) while Selection Mode is active so
        // filtering cannot silently discard the current selection - same
        // rule the old filter chips followed.
        filterDropdownLayout?.isEnabled = !selectionMode
        filterDropdown?.isEnabled = !selectionMode
        textShowLabels?.isEnabled = !selectionMode
        switchShowLabels?.isEnabled = !selectionMode
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
     *  unused image - so an empty Selection Mode can never be entered.
     *  Assignment mode has no selection/deletion at all, so Select never
     *  shows there. */
    private fun updateSelectAvailability() {
        if (selectionMode) return
        if (isAssignmentMode()) {
            btnSelect?.visibility = View.GONE
            return
        }
        val hasEligible = filteredTiles().any { !it.isUsed }
        btnSelect?.visibility = if (hasEligible) View.VISIBLE else View.GONE
    }

    /* ------------------------------ selection ------------------------------ */

    private fun onTileClicked(tile: GalleryTile) {
        if (selectionMode) {
            toggleSelection(tile.hash)
            return
        }
        if (isAssignmentMode()) {
            assignTile(tile)
            return
        }
        openDetail(tile)
    }

    /** Tap-to-assign (owner model, July 19 2026): assigns [tile] to the
     *  current target and keeps the gallery open, the Assigned badge moving
     *  to the tapped tile on reload. A Missing or Corrupted tile is not a
     *  real choice, so a tap on one is ignored rather than assigning a broken
     *  reference. */
    private fun assignTile(tile: GalleryTile) {
        if (tile.file == null || tile.corrupted) return
        commitAssignment(tile.hash)
        loadGallery()
    }

    /** Writes an assignment for whichever target this gallery serves: the two
     *  Defaults write their global preference immediately; a Companion pick is
     *  held and returned to the caller ([EXTRA_RESULT_ASSIGNED_HASH]) so the
     *  Companion editor can persist it on its own Save. */
    private fun commitAssignment(hash: String) {
        when (assignTarget) {
            TARGET_GLOBAL -> GlobalPreferences.getPreferences(this).setGlobalDefaultImageRef(hash)
            TARGET_PERSONAL -> GlobalPreferences.getPreferences(this).setDefaultUserImageRef(hash)
            TARGET_COMPANION -> {
                pendingAssignedHash = hash
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_ASSIGNED_HASH, hash))
            }
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

    /* ------------------------------ data ------------------------------ */

    /** The hash currently assigned to this gallery's target (drives the
     *  Assigned badge), or "" in Management mode / when none is set yet. */
    private fun assignedHash(): String {
        val preferences = GlobalPreferences.getPreferences(this)
        return when (assignTarget) {
            TARGET_GLOBAL -> preferences.getGlobalDefaultImageRef()
            TARGET_PERSONAL -> preferences.getDefaultUserImageRef()
            TARGET_COMPANION -> pendingAssignedHash.orEmpty()
            else -> ""
        }
    }

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
                    // Ensures the Global Default and every built-in preset
                    // exist as ordinary, pickable catalog entries whenever
                    // the gallery opens - not just from Default Images
                    // (owner ruling: "why take away options").
                    GlobalDefaultImageSeeder.ensureSeeded(this@ProfileImagesActivity)
                    val usage = ProfileImageUsage.computeAll(this@ProfileImagesActivity)
                    val assigned = assignedHash()
                    s.listNewestFirst().map { record ->
                        val file = s.imageFile(record.hash)
                        GalleryTile(
                            hash = record.hash,
                            file = file,
                            createdAt = record.createdAt,
                            isUsed = usage.containsKey(record.hash),
                            corrupted = file != null && !isDecodableImage(file),
                            isAssigned = assigned.isNotEmpty() && record.hash == assigned
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

    /** Owner ruling, July 19 2026: an in-use image may be deleted directly
     *  from the Image Detail sheet, with a stronger confirmation naming
     *  every identity that uses it. */
    override fun onProfileImageDeleteWhileInUseRequested(hash: String, identityLines: List<String>) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.profile_image_delete_in_use_confirm_title)
            .setMessage(getString(R.string.profile_image_delete_in_use_confirm_body, identityLines.joinToString("\n")))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.profile_image_delete_in_use_confirm_button) { _, _ ->
                performDelete(setOf(hash), forceEvenIfUsed = true)
            }
            .show()
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

    /** Unused Image Deletion Order (PERMANENT DELETION): deletion-time usage
     *  is authoritative for the ordinary (bulk/Select All Shown) path -
     *  anything that became used since the gallery loaded is skipped, file
     *  deleted before the catalog record for each survivor, and the user is
     *  told when something was skipped. [forceEvenIfUsed] bypasses that
     *  recheck: it is set ONLY by the single-image in-use confirmation
     *  (owner ruling, July 19 2026), where the user has already been shown
     *  exactly who uses the image and explicitly chosen to delete it anyway -
     *  the recheck exists to protect the bulk flow, which never shows
     *  per-image usage before deleting, not to block an informed choice here.
     *  The reference left behind on the identity (avatarRef/imageRef) is not
     *  cleared; it is architecturally the same as any other Missing record -
     *  display code already falls through gracefully. */
    private fun performDelete(hashes: Set<String>, forceEvenIfUsed: Boolean = false) {
        ioScope.launch {
            val s = store
            val skippedCount: Int
            if (s == null) {
                skippedCount = 0
            } else if (forceEvenIfUsed) {
                skippedCount = 0
                for (hash in hashes) s.delete(hash)
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
     *  hash) and refreshes the grid. ERROR PRESENTATION: an upload/save
     *  failure is never silent. */
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
                if (hash == null) {
                    showSaveErrorDialog(R.string.profile_image_save_error_no_space_body)
                } else {
                    // UPLOAD FLOW: in Assignment mode a freshly uploaded image
                    // is assigned right away, so the user is never made to hunt
                    // for and tap it again. In Management mode it simply joins
                    // the grid.
                    if (isAssignmentMode()) commitAssignment(hash)
                    loadGallery()
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
