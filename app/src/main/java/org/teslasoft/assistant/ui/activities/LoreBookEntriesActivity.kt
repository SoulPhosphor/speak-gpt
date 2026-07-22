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
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginBottom
import androidx.core.view.marginRight
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.dto.LoreBookEntry
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.LoreBookItemAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditLoreBookDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.EditLoreBookEntryDialogFragment

/**
 * Lists the memories inside a single lorebook. The lorebook is passed in via the
 * "lorebookId" / "lorebookName" intent extras.
 */
class LoreBookEntriesActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var btnDebug: ImageButton? = null
    private var btnEditBook: ImageButton? = null
    private var activityTitle: TextView? = null
    private var bookDescription: TextView? = null
    private var listView: ListView? = null
    private var actionBar: ConstraintLayout? = null

    private var list: ArrayList<LoreBookEntry> = arrayListOf()
    private var adapter: LoreBookItemAdapter? = null

    private var store: LoreBookStore? = null

    private var lorebookId: String = ""
    private var lorebookName: String = ""

    private fun openEditDialog(position: Int) {
        val entry = if (position == -1) LoreBookEntry(lorebookId = lorebookId) else list[position]
        val dialog = EditLoreBookEntryDialogFragment.newInstance(entry, position)
        dialog.setListener(editDialogListener)
        dialog.setCancelable(false)
        dialog.show(supportFragmentManager, "EditLoreBookEntryDialogFragment")
    }

    private var onSelectListener: LoreBookItemAdapter.OnSelectListener = object : LoreBookItemAdapter.OnSelectListener {
        override fun onClick(position: Int) {
            openEditDialog(position)
        }
    }

    private var editDialogListener: EditLoreBookEntryDialogFragment.StateChangesListener = object : EditLoreBookEntryDialogFragment.StateChangesListener {
        override fun onAdd(entry: LoreBookEntry) {
            store!!.saveEntry(entry)
            reloadList()
        }

        override fun onEdit(entry: LoreBookEntry, position: Int) {
            store!!.saveEntry(entry)
            reloadList()
        }

        override fun onDelete(position: Int, id: String) {
            if (id.isNotEmpty()) store!!.deleteEntry(id)
            reloadList()
        }

        override fun onError(message: String, position: Int) {
            Toast.makeText(this@LoreBookEntriesActivity, message, Toast.LENGTH_SHORT).show()
            openEditDialog(position)
        }
    }

    private fun openBookEditDialog() {
        val book = store?.getBook(lorebookId) ?: return
        // Position 0 (not -1) so the dialog behaves as "edit existing": Save
        // routes to onEdit and the Delete button is offered.
        val dialog = EditLoreBookDialogFragment.newInstance(book, 0)
        dialog.setListener(bookEditListener)
        dialog.setCancelable(false)
        dialog.show(supportFragmentManager, "EditLoreBookDialogFragment")
    }

    private var bookEditListener: EditLoreBookDialogFragment.StateChangesListener = object : EditLoreBookDialogFragment.StateChangesListener {
        override fun onEdit(book: LoreBook, position: Int) {
            store!!.saveBook(book)
            refreshBookHeader()
        }

        override fun onDelete(position: Int, id: String) {
            if (id.isNotEmpty()) {
                store!!.deleteBook(id)
                // No persona may keep referencing a book that no longer exists.
                PersonaPreferences.getPersonaPreferences(this@LoreBookEntriesActivity).removeLoreBookFromAllPersonas(id)
            }
            finish()
        }

        override fun onError(message: String, position: Int) {
            Toast.makeText(this@LoreBookEntriesActivity, message, Toast.LENGTH_SHORT).show()
            openBookEditDialog()
        }
    }

    /** Title + tag/description line reflect the stored book, so edits made in
     *  the dialog (or elsewhere) show up as soon as we render. */
    private fun refreshBookHeader() {
        val book = store?.getBook(lorebookId)
        if (book != null && book.name.isNotEmpty()) lorebookName = book.name
        if (lorebookName.isNotEmpty()) activityTitle?.text = lorebookName

        val summary = listOf(book?.tag.orEmpty(), book?.description.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (summary.isBlank()) {
            bookDescription?.visibility = View.GONE
        } else {
            bookDescription?.visibility = View.VISIBLE
            bookDescription?.text = summary
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        setContentView(R.layout.activity_lorebook_entries)

        lorebookId = intent.getStringExtra("lorebookId") ?: ""
        lorebookName = intent.getStringExtra("lorebookName") ?: ""

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        btnDebug = findViewById(R.id.btn_debug)
        btnEditBook = findViewById(R.id.btn_edit_book)
        activityTitle = findViewById(R.id.activity_title)
        bookDescription = findViewById(R.id.book_description)
        listView = findViewById(R.id.list_view)
        actionBar = findViewById(R.id.action_bar)

        if (lorebookName.isNotEmpty()) activityTitle?.text = lorebookName

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
            btnEditBook?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
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
            btnEditBook?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }

        listView?.divider = null

        // Build Phase 3 degraded gate: a lorebook store with confirmed damage
        // refuses to open; this screen cannot function without it, so show
        // the blocking notice (Repair routes to the recovery flow) instead of
        // crashing on the refusal.
        if (org.teslasoft.assistant.preferences.backup.DatabaseHealthState.isDegraded(
                this, org.teslasoft.assistant.preferences.backup.BackupType.LOREBOOK)) {
            org.teslasoft.assistant.ui.DatabaseRecoveryFlows.showBlockedScreenDialog(
                this, org.teslasoft.assistant.preferences.backup.BackupType.LOREBOOK)
            return
        }

        store = LoreBookStore.getInstance(this)
        initialize()
    }

    private fun reloadList() {
        list.clear()
        list.addAll(store!!.getEntries(lorebookId))

        runOnUiThread {
            adapter = LoreBookItemAdapter(list, this)
            adapter!!.setOnSelectListener(onSelectListener)
            listView!!.adapter = adapter
            adapter!!.notifyDataSetChanged()
        }
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
        refreshBookHeader()
        reloadList()

        btnBack!!.setOnClickListener {
            finish()
        }

        btnDebug!!.setOnClickListener {
            startActivity(Intent(this, LoreBookDebugActivity::class.java))
        }

        btnEditBook!!.setOnClickListener {
            openBookEditDialog()
        }

        btnAdd!!.setOnClickListener {
            openEditDialog(-1)
        }
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
