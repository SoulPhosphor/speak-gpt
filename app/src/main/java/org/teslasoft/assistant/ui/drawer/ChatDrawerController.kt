package org.teslasoft.assistant.ui.drawer

import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.ui.activities.ImageGalleryActivity
import org.teslasoft.assistant.ui.activities.SearchActivity
import org.teslasoft.assistant.ui.activities.SettingsActivity
import org.teslasoft.assistant.ui.util.ChatDeletionRequestCoordinator
import org.teslasoft.assistant.ui.util.CompactActionPopup

class ChatDrawerController private constructor(
    private val activity: FragmentActivity,
    private val drawer: DrawerLayout,
    private val panel: View,
    private val currentChatId: () -> String
) {
    private val repository = ChatNavigationRepository.get(activity)
    private val list: RecyclerView = panel.findViewById(R.id.drawer_hierarchy)
    private val adapter = DrawerHierarchyAdapter(activity, currentChatId, object : DrawerHierarchyAdapter.Callbacks {
        override fun onClick(row: DrawerRow) = handleClick(row)
        override fun onLongClick(anchor: View, row: DrawerRow) = handleLongClick(anchor, row)
    })

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        panel.findViewById<ImageButton>(R.id.drawer_close).setOnClickListener { close() }
        panel.findViewById<View>(R.id.drawer_search).setOnClickListener {
            activity.startActivity(Intent(activity, SearchActivity::class.java))
        }
        panel.findViewById<View>(R.id.drawer_new_chat).setOnClickListener { openNewChat() }
        panel.findViewById<View>(R.id.drawer_new_folder).setOnClickListener { showAddFolder() }
        panel.findViewById<TextView>(R.id.drawer_settings).setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java).setAction(Intent.ACTION_VIEW))
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN, GravityCompat.START)
            }
            override fun onDrawerClosed(drawerView: View) {
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
            }
        })
        refresh()
    }

    fun open() {
        refresh()
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.openDrawer(GravityCompat.START, true)
    }

    fun close() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.closeDrawer(GravityCompat.START, true)
    }

    fun isOpen(): Boolean = drawer.isDrawerOpen(GravityCompat.START)

    fun refresh() {
        activity.lifecycleScope.launch {
            val projection = withContext(Dispatchers.IO) {
                when (val result = repository.snapshot()) {
                    is ChatNavigationResult.Success -> {
                        val foldersExpanded = repository.areFoldersExpanded()
                        val expanded = result.value.folders.mapNotNullTo(LinkedHashSet()) {
                            it.folder.id.takeIf(repository::isFolderExpanded)
                        }
                        DrawerHierarchyProjection.build(result.value, foldersExpanded, expanded)
                    }
                    is ChatNavigationResult.Failure -> null
                }
            }
            if (projection == null) {
                Toast.makeText(activity, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
            } else adapter.submitList(projection)
        }
    }

    private fun handleClick(row: DrawerRow) {
        when (row) {
            DrawerRow.Gallery -> activity.startActivity(Intent(activity, ImageGalleryActivity::class.java))
            is DrawerRow.FoldersHeader -> { repository.setFoldersExpanded(!row.expanded); refresh() }
            is DrawerRow.Folder -> { repository.setFolderExpanded(row.value.id, !row.expanded); refresh() }
            is DrawerRow.Chat -> activity.startActivity(
                ChatActivity.rootIntent(activity, row.value.id, row.value.name)
            )
            is DrawerRow.Section -> Unit
        }
    }

    private fun handleLongClick(anchor: View, row: DrawerRow) {
        when (row) {
            is DrawerRow.FoldersHeader -> CompactActionPopup(activity, anchor)
                .add(MENU_ADD_FOLDER, 0, activity.getString(R.string.folder_add))
                .onAction { if (it == MENU_ADD_FOLDER) { showAddFolder(); true } else false }.show()
            is DrawerRow.Folder -> CompactActionPopup(activity, anchor)
                .add(MENU_FOLDER_PIN, 0, activity.getString(if (row.value.pinned) R.string.folder_unpin else R.string.folder_pin))
                .add(MENU_FOLDER_RENAME, 1, activity.getString(R.string.folder_rename))
                .add(MENU_FOLDER_DELETE, 2, activity.getString(R.string.folder_delete))
                .onAction {
                    when (it) {
                        MENU_FOLDER_PIN -> {
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                repository.setFolderPinned(row.value.id, !row.value.pinned)
                                withContext(Dispatchers.Main) { refresh() }
                            }; true
                        }
                        MENU_FOLDER_RENAME -> { showRenameFolder(row); true }
                        MENU_FOLDER_DELETE -> {
                            ChatDeletionRequestCoordinator.requestFolder(activity, row.value.id, ::refresh); true
                        }
                        else -> false
                    }
                }.show()
            is DrawerRow.Chat -> CompactActionPopup(activity, anchor)
                .add(MENU_CHAT_PIN, 0, activity.getString(if (row.value.pinned) R.string.chat_menu_unpin else R.string.chat_menu_pin))
                .add(MENU_CHAT_MOVE, 1, activity.getString(R.string.chat_move_to_folder))
                .add(MENU_CHAT_DELETE, 2, activity.getString(R.string.btn_delete))
                .onAction {
                    when (it) {
                        MENU_CHAT_PIN -> {
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                repository.setChatPinned(row.value.id, !row.value.pinned)
                                withContext(Dispatchers.Main) { refresh() }
                            }; true
                        }
                        MENU_CHAT_MOVE -> { showMoveChooser(row); true }
                        MENU_CHAT_DELETE -> {
                            ChatDeletionRequestCoordinator.requestChats(activity, setOf(row.value.id), onCommitted = ::refresh); true
                        }
                        else -> false
                    }
                }.show()
            else -> Unit
        }
    }

    private fun showAddFolder() = NameEntryDialog.show(
        activity, R.string.folder_add_title, save = repository::createFolder, onSaved = ::refresh
    )

    private fun showRenameFolder(row: DrawerRow.Folder) = NameEntryDialog.show(
        activity, R.string.folder_rename_title, row.value.name,
        save = { repository.renameFolder(row.value.id, it) }, onSaved = ::refresh
    )

    private fun showMoveChooser(row: DrawerRow.Chat) {
        activity.lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
            if (snapshot !is ChatNavigationResult.Success) return@launch
            val folders = snapshot.value.folders.map { it.folder }
            val labels = listOf(activity.getString(R.string.folder_none)) + folders.map { it.name }
            val selected = folders.indexOfFirst { it.id == row.value.folderId } + 1
            MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.chat_move_to_folder)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        repository.moveChat(row.value.id, folders.getOrNull(which - 1)?.id)
                        withContext(Dispatchers.Main) { dialog.dismiss(); refresh() }
                    }
                }.setNegativeButton(R.string.btn_cancel, null).show()
        }
    }

    private fun openNewChat() {
        activity.lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                NewConversationCoordinator(activity).createDefaultPendingConversation()
            }
            activity.startActivity(
                ChatActivity.rootIntent(activity, pending.id, pending.name, pendingConversation = true)
            )
        }
    }

    companion object {
        private const val MENU_ADD_FOLDER = 1
        private const val MENU_FOLDER_PIN = 2
        private const val MENU_FOLDER_RENAME = 3
        private const val MENU_FOLDER_DELETE = 4
        private const val MENU_CHAT_PIN = 5
        private const val MENU_CHAT_MOVE = 6
        private const val MENU_CHAT_DELETE = 7

        fun install(activity: FragmentActivity, chatRoot: View, currentChatId: () -> String): ChatDrawerController {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            (chatRoot.parent as? ViewGroup)?.removeView(chatRoot)
            val drawer = FullWidthDrawerLayout(activity).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
            }
            drawer.addView(chatRoot, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val panel = LayoutInflater.from(activity).inflate(R.layout.activity_chat_drawer, drawer, false)
            drawer.addView(panel, DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START
            ))
            content.addView(drawer)
            return ChatDrawerController(activity, drawer, panel, currentChatId)
        }
    }
}
