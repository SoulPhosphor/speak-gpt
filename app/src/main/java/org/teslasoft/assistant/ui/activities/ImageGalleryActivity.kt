/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageAssetResolver
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import org.teslasoft.assistant.preferences.generatedimages.ImageGalleryActionPolicy
import org.teslasoft.assistant.preferences.generatedimages.ImageGalleryPresentation
import org.teslasoft.assistant.preferences.generatedimages.ImageGalleryPresentationPreferences
import org.teslasoft.assistant.preferences.generatedimages.ImageGallerySelection
import org.teslasoft.assistant.preferences.generatedimages.ImageGallerySortOrder
import org.teslasoft.assistant.preferences.generatedimages.ImageGallerySorter
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.generatedimages.GeneratedImageGalleryAdapter
import org.teslasoft.assistant.ui.adapters.generatedimages.GeneratedImageGalleryRow
import org.teslasoft.assistant.ui.util.CompactActionPopup
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ImageGalleryActivity : FragmentActivity(), GeneratedImageGalleryAdapter.Listener {

    companion object {
        private const val STATE_LAYOUT = "gallery_layout_state"
        private const val MENU_GO_TO_CHAT = 1
        private const val MENU_ADD_TO_AVATAR = 2
        private const val MENU_LOCK = 3
        private const val MENU_DELETE = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val selection = ImageGallerySelection()
    private lateinit var presentationPreferences: ImageGalleryPresentationPreferences
    private lateinit var presentation: ImageGalleryPresentation
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: GeneratedImageGalleryAdapter
    private var rows: List<GeneratedImageGalleryRow> = emptyList()
    private var selectionMode = false
    private var pendingLayoutState: Parcelable? = null

    private lateinit var recycler: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnCancelSelection: ImageButton
    private lateinit var activityTitle: TextView
    private lateinit var selectedCount: TextView
    private lateinit var btnSelect: TextView
    private lateinit var controls: LinearLayout
    private lateinit var sortLayout: TextInputLayout
    private lateinit var sortDropdown: AutoCompleteTextView
    private lateinit var columnsLayout: TextInputLayout
    private lateinit var columnsDropdown: AutoCompleteTextView
    private lateinit var textShowLabels: TextView
    private lateinit var switchShowLabels: MaterialSwitch
    private lateinit var inlineStatus: TextView
    private lateinit var emptyState: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnDeleteSelected: MaterialButton

    private val framingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ProfileImageFramingActivity.EXTRA_RESULT_TEMP_PATH)?.let(::saveAvatarDerivative)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_image_gallery)
        bindViews()

        presentationPreferences = ImageGalleryPresentationPreferences.get(this)
        presentation = presentationPreferences.read()
        pendingLayoutState = if (Build.VERSION.SDK_INT >= 33) {
            savedInstanceState?.getParcelable(STATE_LAYOUT, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION") savedInstanceState?.getParcelable(STATE_LAYOUT)
        }

        layoutManager = GridLayoutManager(this, presentation.columns)
        recycler.layoutManager = layoutManager
        adapter = GeneratedImageGalleryAdapter(::formatDate, this)
        adapter.showLabels = presentation.showLabels
        recycler.adapter = adapter

        setupControls()
        setupActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode) exitSelectionMode() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        loadGallery()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadGallery()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            val insets = window.decorView.rootWindowInsets
            findViewById<View>(R.id.action_bar).setPadding(
                0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0
            )
            val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, navBottom + dp(12))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_LAYOUT, layoutManager.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        recycler = findViewById(R.id.recycler_gallery)
        btnBack = findViewById(R.id.btn_back)
        btnCancelSelection = findViewById(R.id.btn_cancel_selection)
        activityTitle = findViewById(R.id.activity_title)
        selectedCount = findViewById(R.id.text_selection_count)
        btnSelect = findViewById(R.id.btn_select)
        controls = findViewById(R.id.gallery_controls)
        sortLayout = findViewById(R.id.sort_dropdown_layout)
        sortDropdown = findViewById(R.id.sort_dropdown)
        columnsLayout = findViewById(R.id.columns_dropdown_layout)
        columnsDropdown = findViewById(R.id.columns_dropdown)
        textShowLabels = findViewById(R.id.text_show_labels)
        switchShowLabels = findViewById(R.id.switch_show_labels)
        inlineStatus = findViewById(R.id.text_inline_status)
        emptyState = findViewById(R.id.empty_state)
        bottomBar = findViewById(R.id.bottom_bar)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
    }

    private fun setupControls() {
        val sortLabels = listOf(getString(R.string.image_gallery_newest), getString(R.string.image_gallery_oldest))
        sortDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, sortLabels))
        sortDropdown.setText(sortLabels[if (presentation.sortOrder == ImageGallerySortOrder.NEWEST_TO_OLDEST) 0 else 1], false)
        sortDropdown.setOnItemClickListener { _, _, position, _ ->
            presentation = presentation.copy(sortOrder = if (position == 0) {
                ImageGallerySortOrder.NEWEST_TO_OLDEST
            } else ImageGallerySortOrder.OLDEST_TO_NEWEST)
            presentationPreferences.setSortOrder(presentation.sortOrder)
            displayRows(rows)
        }

        val columnLabels = listOf("2", "3", "4")
        columnsDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, columnLabels))
        columnsDropdown.setText(presentation.columns.toString(), false)
        columnsDropdown.setOnItemClickListener { _, _, position, _ ->
            val columns = position + 2
            presentation = presentation.copy(columns = columns)
            presentationPreferences.setColumns(columns)
            layoutManager.spanCount = columns
        }

        switchShowLabels.isChecked = presentation.showLabels
        val toggle = { switchShowLabels.isChecked = !switchShowLabels.isChecked }
        textShowLabels.setOnClickListener { toggle() }
        switchShowLabels.setOnCheckedChangeListener { _, checked ->
            presentation = presentation.copy(showLabels = checked)
            presentationPreferences.setShowLabels(checked)
            adapter.showLabels = checked
        }
    }

    private fun setupActions() {
        btnBack.setOnClickListener { finish() }
        btnCancelSelection.setOnClickListener { exitSelectionMode() }
        btnSelect.setOnClickListener { enterSelectionMode() }
        btnDeleteSelected.setOnClickListener {
            if (selection.ids().isNotEmpty()) confirmDelete(selection.ids(), true)
        }
    }

    private fun loadGallery() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val catalog = GeneratedImageCatalogStore.listActive(this@ImageGalleryActivity)
                if (catalog.state != GeneratedImageCatalogStorageState.AVAILABLE) return@withContext null
                val chats = when (val snapshot = ChatNavigationRepository.get(this@ImageGalleryActivity).snapshot()) {
                    is ChatNavigationResult.Success -> snapshot.value.allChats.associateBy { it.id }
                    is ChatNavigationResult.Failure -> emptyMap()
                }
                catalog.records.mapNotNull { record ->
                    when (val resolved = GeneratedImageAssetResolver.resolveCatalogImage(this@ImageGalleryActivity, record.imageId)) {
                        is GeneratedImageAssetResolver.Result.Available -> GeneratedImageGalleryRow(
                            record,
                            resolved.file,
                            record.originChatId != null && chats.containsKey(record.originChatId)
                        )
                        is GeneratedImageAssetResolver.Result.Missing -> {
                            GeneratedImageCatalogStore.tombstoneMissing(
                                this@ImageGalleryActivity, record.imageId, record.assetFileName
                            )
                            null
                        }
                        is GeneratedImageAssetResolver.Result.CatalogUnavailable -> null
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (result == null) {
                showStatus(getString(R.string.image_gallery_catalog_unavailable))
                return@launch
            }
            hideStatus()
            rows = result
            selection.retainEligible(rows.map { it.record })
            displayRows(rows)
        }
    }

    private fun displayRows(source: List<GeneratedImageGalleryRow>) {
        val byId = source.associateBy { it.record.imageId }
        val sorted = ImageGallerySorter.sort(source.map { it.record }, presentation.sortOrder)
            .mapNotNull { byId[it.imageId] }
        adapter.submit(sorted, selection.ids())
        emptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
        if (!selectionMode) btnSelect.visibility = if (sorted.any { !it.record.locked }) View.VISIBLE else View.GONE
        pendingLayoutState?.let {
            layoutManager.onRestoreInstanceState(it)
            pendingLayoutState = null
        }
        updateSelectionUi()
    }

    override fun onClick(row: GeneratedImageGalleryRow) {
        if (selectionMode) {
            if (selection.toggle(row.record)) {
                adapter.updateSelection(selection.ids())
                updateSelectionUi()
            }
        } else {
            preservePosition()
            startActivity(Intent(this, ImageBrowserActivity::class.java)
                .putExtra(ImageBrowserActivity.EXTRA_GENERATED_IMAGE_ID, row.record.imageId))
        }
    }

    override fun onLongClick(anchor: View, row: GeneratedImageGalleryRow): Boolean {
        val policy = ImageGalleryActionPolicy.forRecord(row.record, row.originChatExists)
        CompactActionPopup(this, anchor)
            .add(MENU_GO_TO_CHAT, 0, getString(R.string.image_gallery_go_to_chat), policy.canGoToChat)
            .add(MENU_ADD_TO_AVATAR, 1, getString(R.string.image_gallery_add_to_avatar))
            .add(MENU_LOCK, 2, getString(if (row.record.locked) R.string.image_gallery_unlock else R.string.image_gallery_lock))
            .apply {
                if (policy.canDelete) add(MENU_DELETE, 3, getString(R.string.image_gallery_delete))
            }
            .onAction { action ->
                when (action) {
                    MENU_GO_TO_CHAT -> openOriginChat(row)
                    MENU_ADD_TO_AVATAR -> openAvatarFraming(row)
                    MENU_LOCK -> setLocked(row.record.imageId, !row.record.locked)
                    MENU_DELETE -> confirmDelete(setOf(row.record.imageId), false)
                }
                true
            }.show()
        return true
    }

    private fun openOriginChat(row: GeneratedImageGalleryRow) {
        val chatId = row.record.originChatId ?: return
        scope.launch {
            val current = withContext(Dispatchers.IO) {
                when (val snapshot = ChatNavigationRepository.get(this@ImageGalleryActivity).snapshot()) {
                    is ChatNavigationResult.Success -> snapshot.value.allChats.firstOrNull { it.id == chatId }
                    is ChatNavigationResult.Failure -> null
                }
            } ?: return@launch
            startActivity(ChatActivity.rootIntent(this@ImageGalleryActivity, chatId, current.name)
                .putExtra("imageId", row.record.imageId)
                .putExtra("originMessageId", row.record.originMessageId))
        }
    }

    private fun openAvatarFraming(row: GeneratedImageGalleryRow) {
        preservePosition()
        framingLauncher.launch(Intent(this, ProfileImageFramingActivity::class.java)
            .putExtra(ProfileImageFramingActivity.EXTRA_GENERATED_IMAGE_ID, row.record.imageId))
    }

    private fun setLocked(imageId: String, locked: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                GeneratedImageCatalogStore.setLocked(this@ImageGalleryActivity, imageId, locked)
            }
            loadGallery()
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selection.clear()
        adapter.selectionMode = true
        applyModeVisibility()
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selection.clear()
        adapter.selectionMode = false
        adapter.updateSelection(emptySet())
        applyModeVisibility()
    }

    private fun applyModeVisibility() {
        btnBack.visibility = if (selectionMode) View.GONE else View.VISIBLE
        btnCancelSelection.visibility = if (selectionMode) View.VISIBLE else View.GONE
        activityTitle.visibility = if (selectionMode) View.GONE else View.VISIBLE
        selectedCount.visibility = if (selectionMode) View.VISIBLE else View.GONE
        btnSelect.visibility = if (selectionMode) View.GONE else if (rows.any { !it.record.locked }) View.VISIBLE else View.GONE
        bottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        controls.isEnabled = !selectionMode
        sortLayout.isEnabled = !selectionMode
        columnsLayout.isEnabled = !selectionMode
        textShowLabels.isEnabled = !selectionMode
        switchShowLabels.isEnabled = !selectionMode
    }

    private fun updateSelectionUi() {
        if (!selectionMode) return
        val count = selection.ids().size
        selectedCount.text = getString(R.string.image_gallery_selection_count, count)
        btnDeleteSelected.isEnabled = count > 0
    }

    private fun confirmDelete(ids: Set<String>, bulk: Boolean) {
        val actions = LayoutInflater.from(this).inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        okay.setText(R.string.image_gallery_okay)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(if (bulk) R.string.image_gallery_delete_selected_title else R.string.image_gallery_delete_one_title)
            .setView(actions)
            .create()
        cancel.setOnClickListener { dialog.dismiss() }
        okay.setOnClickListener { dialog.dismiss(); performDelete(ids) }
        dialog.show()
    }

    private fun performDelete(ids: Set<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                GeneratedImageCatalogStore.tombstoneUnlockedExplicit(this@ImageGalleryActivity, ids)
            }
            exitSelectionMode()
            loadGallery()
        }
    }

    private fun saveAvatarDerivative(tempPath: String) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val temp = File(tempPath)
                val bitmap = runCatching { BitmapFactory.decodeFile(temp.absolutePath) }.getOrNull()
                val result = bitmap?.let { ProfileImageStore.getInstance(this@ImageGalleryActivity).save(it) }
                temp.parentFile?.deleteRecursively()
                result != null
            }
            if (!saved) showStatus(getString(R.string.image_gallery_avatar_save_failed))
        }
    }

    private fun preservePosition() {
        pendingLayoutState = layoutManager.onSaveInstanceState()
    }

    private fun showStatus(message: String) {
        inlineStatus.text = message
        inlineStatus.visibility = View.VISIBLE
        inlineStatus.announceForAccessibility(message)
    }

    private fun hideStatus() { inlineStatus.visibility = View.GONE }

    private fun formatDate(millis: Long): String = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
