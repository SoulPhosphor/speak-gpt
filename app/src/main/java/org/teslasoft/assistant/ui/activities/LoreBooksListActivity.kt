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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginBottom
import androidx.core.view.marginRight
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.LoreBookAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditLoreBookDialogFragment

/**
 * Lists all lorebooks, searchable by keyword and filterable by type tag.
 * Tapping a lorebook opens its memories; the cog (or a long press) edits the
 * lorebook itself. Reached from the Lorebook tile in Characters.
 *
 * Pick mode (launched for result with "pickMode"): rows show checkboxes instead,
 * for linking books to a persona. Both the back arrow and the "Save and close"
 * button return the checked ids, so picks are never lost.
 */
class LoreBooksListActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PICK_MODE = "pickMode"
        const val EXTRA_SELECTED_IDS = "selectedLoreBookIds"
    }

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var btnDebug: ImageButton? = null
    private var activityTitle: TextView? = null
    private var listView: ListView? = null
    private var actionBar: ConstraintLayout? = null
    private var fieldSearch: TextInputEditText? = null
    private var btnFilterTag: MaterialButton? = null

    private var allBooks: ArrayList<LoreBook> = arrayListOf()
    private var list: ArrayList<LoreBook> = arrayListOf()
    private var counts: HashMap<String, Int> = hashMapOf()
    private var adapter: LoreBookAdapter? = null

    private var searchQuery: String = ""
    private var tagFilter: String = ""

    private var pickMode: Boolean = false
    private var selectedIds: HashSet<String> = hashSetOf()

    private var store: LoreBookStore? = null

    private fun openEntries(position: Int) {
        val book = list[position]
        val intent = Intent(this, LoreBookEntriesActivity::class.java)
        intent.putExtra("lorebookId", book.id)
        intent.putExtra("lorebookName", book.name)
        startActivity(intent)
    }

    private fun openEditDialog(position: Int) {
        val book = if (position == -1) LoreBook() else list[position]
        val dialog = EditLoreBookDialogFragment.newInstance(book, position)
        dialog.setListener(editDialogListener)
        dialog.setCancelable(false)
        dialog.show(supportFragmentManager, "EditLoreBookDialogFragment")
    }

    private var onSelectListener: LoreBookAdapter.OnSelectListener = object : LoreBookAdapter.OnSelectListener {
        override fun onClick(position: Int) {
            openEntries(position)
        }

        override fun onSettingsClick(position: Int) {
            openEditDialog(position)
        }
    }

    private var editDialogListener: EditLoreBookDialogFragment.StateChangesListener = object : EditLoreBookDialogFragment.StateChangesListener {
        override fun onAdd(book: LoreBook) {
            val saved = store!!.saveBook(book)
            // In pick mode a book created on the spot is what the user came to
            // link, so it starts checked.
            if (pickMode) selectedIds.add(saved.id)
            reloadList()
        }

        override fun onEdit(book: LoreBook, position: Int) {
            store!!.saveBook(book)
            reloadList()
        }

        override fun onDelete(position: Int, id: String) {
            if (id.isNotEmpty()) {
                store!!.deleteBook(id)
                // No persona may keep referencing a book that no longer exists.
                PersonaPreferences.getPersonaPreferences(this@LoreBooksListActivity).removeLoreBookFromAllPersonas(id)
                selectedIds.remove(id)
            }
            reloadList()
        }

        override fun onError(message: String, position: Int) {
            Toast.makeText(this@LoreBooksListActivity, message, Toast.LENGTH_SHORT).show()
            openEditDialog(position)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_lorebooks_list)

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        btnDebug = findViewById(R.id.btn_debug)
        activityTitle = findViewById(R.id.activity_title)
        listView = findViewById(R.id.list_view)
        actionBar = findViewById(R.id.action_bar)
        fieldSearch = findViewById(R.id.field_search)
        btnFilterTag = findViewById(R.id.btn_filter_tag)

        pickMode = intent.getBooleanExtra(EXTRA_PICK_MODE, false)
        if (pickMode) {
            selectedIds = HashSet(intent.getStringArrayListExtra(EXTRA_SELECTED_IDS) ?: arrayListOf())
            // The system back gesture also saves: leaving the picker by any
            // route returns the current selection.
            onBackPressedDispatcher.addCallback(this) { finishWithSelection() }
            activityTitle?.text = getString(R.string.title_add_lorebooks)
            btnAdd?.text = getString(R.string.btn_save_and_close)
            btnAdd?.setIconResource(R.drawable.ic_done)
            // The debug button doubles as "new book" in pick mode so books can
            // still be created mid-pick.
            btnDebug?.setImageResource(R.drawable.ic_add)
            btnDebug?.contentDescription = getString(R.string.btn_new_lorebook)
            btnDebug?.tooltipText = getString(R.string.btn_new_lorebook)
        }

        val preferences = Preferences.getPreferences(this, "")

        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences.getAmoledPitchBlack())

        if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)

            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }

            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnDebug?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            val colorDrawable = SurfaceColors.SURFACE_0.getColor(this).toDrawable()
            window.setBackgroundDrawable(colorDrawable)

            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }

            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
            btnDebug?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        listView?.divider = null

        store = LoreBookStore.getInstance(this)
        initialize()
    }

    private fun finishWithSelection() {
        val resultIntent = Intent()
        resultIntent.putStringArrayListExtra(EXTRA_SELECTED_IDS, ArrayList(selectedIds))
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun matchesFilters(book: LoreBook): Boolean {
        if (tagFilter.isNotEmpty() && !book.tag.equals(tagFilter, ignoreCase = true)) return false
        if (searchQuery.isBlank()) return true
        val q = searchQuery.trim().lowercase()
        return book.name.lowercase().contains(q) ||
                book.description.lowercase().contains(q) ||
                book.tag.lowercase().contains(q)
    }

    private fun reloadList() {
        allBooks = store!!.getAllBooks()
        counts.clear()
        for (book in allBooks) {
            counts[book.id] = store!!.getEntryCount(book.id)
        }
        applyFilters()
    }

    private fun applyFilters() {
        list = ArrayList(allBooks.filter { matchesFilters(it) })

        runOnUiThread {
            adapter = LoreBookAdapter(list, counts, this, pickMode, selectedIds)
            adapter!!.setOnSelectListener(onSelectListener)
            listView!!.adapter = adapter
            adapter!!.notifyDataSetChanged()
        }
    }

    private fun showTagFilterChooser() {
        val tags = store!!.getAllTags()
        val labels = arrayListOf(getString(R.string.lorebook_filter_all_types))
        labels.addAll(tags)
        val current = (tags.indexOfFirst { it.equals(tagFilter, ignoreCase = true) } + 1)

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.lorebook_filter_by_type)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                tagFilter = if (which == 0) "" else tags[which - 1]
                btnFilterTag?.text = if (tagFilter.isEmpty()) getString(R.string.lorebook_filter_all_types) else tagFilter
                applyFilters()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    private fun initialize() {
        reloadList()

        btnBack!!.setOnClickListener {
            if (pickMode) {
                // "Save and go back": leaving the picker never discards picks.
                finishWithSelection()
            } else {
                finish()
            }
        }

        btnDebug!!.setOnClickListener {
            if (pickMode) {
                openEditDialog(-1)
            } else {
                startActivity(Intent(this, LoreBookDebugActivity::class.java))
            }
        }

        btnAdd!!.setOnClickListener {
            if (pickMode) {
                finishWithSelection()
            } else {
                openEditDialog(-1)
            }
        }

        fieldSearch?.doAfterTextChanged { text ->
            searchQuery = text?.toString() ?: ""
            applyFilters()
        }

        btnFilterTag?.setOnClickListener {
            showTagFilterChooser()
        }
    }

    override fun onResume() {
        super.onResume()
        // Counts can change after editing memories in a book, so refresh on return.
        reloadList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val actionBar = findViewById<ConstraintLayout>(R.id.action_bar)
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )

            val list = findViewById<ListView>(R.id.list_view)
            list?.setPadding(
                0,
                pxToDp(8),
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )

            val extendedFab = findViewById<ExtendedFloatingActionButton>(R.id.btn_add)
            val params: ConstraintLayout.LayoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, extendedFab!!.marginRight, window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + extendedFab!!.marginBottom)
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            extendedFab.layoutParams = params
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }
}
