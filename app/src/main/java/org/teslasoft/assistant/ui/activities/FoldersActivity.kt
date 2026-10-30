package org.teslasoft.assistant.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.drawer.DrawerHierarchyAdapter
import org.teslasoft.assistant.ui.drawer.DrawerHierarchyProjection
import org.teslasoft.assistant.ui.drawer.DrawerRow
import org.teslasoft.assistant.ui.drawer.DrawerRowActions
import org.teslasoft.assistant.ui.util.ScreenChrome

/** The same folder hierarchy as the drawer, with the whole screen to scroll. */
class FoldersActivity : FragmentActivity() {
    private val repository by lazy { ChatNavigationRepository.get(this) }
    private lateinit var list: RecyclerView
    private lateinit var adapter: DrawerHierarchyAdapter
    private lateinit var rowActions: DrawerRowActions
    private var refreshJob: Job? = null
    private var restoredScroll: Parcelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_folders)
        val back = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), back)
        back.setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        list = findViewById(R.id.folder_hierarchy)
        rowActions = DrawerRowActions(this) { refresh() }
        adapter = DrawerHierarchyAdapter(this, { intent.getStringExtra(EXTRA_CHAT_ID).orEmpty() },
            object : DrawerHierarchyAdapter.Callbacks {
                override fun onClick(row: DrawerRow) {
                    when (row) {
                        is DrawerRow.Folder -> {
                            repository.setFolderExpanded(row.value.id, !row.expanded)
                            refresh(refreshDetails = false)
                        }
                        is DrawerRow.Chat -> {
                            setResult(Activity.RESULT_OK, Intent()
                                .putExtra(EXTRA_CHAT_ID, row.value.id)
                                .putExtra(EXTRA_CHAT_NAME, row.value.name))
                            finish()
                        }
                        else -> Unit
                    }
                }

                override fun onLongClick(anchor: View, row: DrawerRow) = rowActions.onLongClick(anchor, row)
            })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        @Suppress("DEPRECATION")
        restoredScroll = savedInstanceState?.getParcelable(STATE_SCROLL)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(refreshDetails: Boolean = true) {
        refreshJob?.cancel()
        adapter.refreshDisplayIfChanged(refreshDetails)
        refreshJob = lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                when (val result = repository.snapshot()) {
                    is ChatNavigationResult.Success -> {
                        val expanded = result.value.folders.mapNotNullTo(LinkedHashSet()) {
                            it.folder.id.takeIf(repository::isFolderExpanded)
                        }
                        DrawerHierarchyProjection.lockedLayers(
                            DrawerHierarchyProjection.build(result.value, true, expanded)
                        ).folderRows
                    }
                    is ChatNavigationResult.Failure -> null
                }
            }
            if (rows == null) {
                Toast.makeText(this@FoldersActivity, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
            } else adapter.submitList(rows) {
                restoredScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
                restoredScroll = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_SCROLL, list.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        list.adapter = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHAT_ID = "folders.chatId"
        const val EXTRA_CHAT_NAME = "folders.chatName"
        private const val STATE_SCROLL = "folders.scroll"
    }
}
