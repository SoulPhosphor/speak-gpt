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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.marginBottom
import androidx.core.view.marginRight
import androidx.fragment.app.FragmentActivity
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.LoreBookAdapter
import org.teslasoft.assistant.ui.fragments.dialogs.EditLoreBookDialogFragment

/**
 * Lists all lorebooks. Tapping a lorebook opens its memories; the cog (or a long
 * press) edits the lorebook itself. Reached from the Lorebook tile in Characters.
 */
class LoreBooksListActivity : FragmentActivity() {

    private var btnAdd: ExtendedFloatingActionButton? = null
    private var btnBack: ImageButton? = null
    private var btnDebug: ImageButton? = null
    private var activityTitle: TextView? = null
    private var listView: ListView? = null
    private var actionBar: ConstraintLayout? = null

    private var list: ArrayList<LoreBook> = arrayListOf()
    private var counts: HashMap<String, Int> = hashMapOf()
    private var adapter: LoreBookAdapter? = null

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
            store!!.saveBook(book)
            reloadList()
        }

        override fun onEdit(book: LoreBook, position: Int) {
            store!!.saveBook(book)
            reloadList()
        }

        override fun onDelete(position: Int, id: String) {
            if (id.isNotEmpty()) store!!.deleteBook(id)
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

        setContentView(R.layout.activity_lorebooks_list)

        btnAdd = findViewById(R.id.btn_add)
        btnBack = findViewById(R.id.btn_back)
        btnDebug = findViewById(R.id.btn_debug)
        activityTitle = findViewById(R.id.activity_title)
        listView = findViewById(R.id.list_view)
        actionBar = findViewById(R.id.action_bar)

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

    private fun reloadList() {
        list.clear()
        counts.clear()
        val books = store!!.getAllBooks()
        for (book in books) {
            counts[book.id] = store!!.getEntryCount(book.id)
        }
        list.addAll(books)

        runOnUiThread {
            adapter = LoreBookAdapter(list, counts, this)
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
        reloadList()

        btnBack!!.setOnClickListener {
            finish()
        }

        btnDebug!!.setOnClickListener {
            startActivity(Intent(this, LoreBookDebugActivity::class.java))
        }

        btnAdd!!.setOnClickListener {
            openEditDialog(-1)
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
