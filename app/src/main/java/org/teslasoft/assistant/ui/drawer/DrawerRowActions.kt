package org.teslasoft.assistant.ui.drawer

import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.ui.util.ChatDeletionRequestCoordinator
import org.teslasoft.assistant.ui.util.CompactActionPopup

/** Identical folder/chat management actions in both folder destinations. */
internal class DrawerRowActions(
    private val activity: FragmentActivity,
    private val refresh: () -> Unit
) {
    private val repository = ChatNavigationRepository.get(activity)
    fun onLongClick(anchor: View, row: DrawerRow) {
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
                            ChatDeletionRequestCoordinator.requestFolder(activity, row.value.id, refresh); true
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
                            ChatDeletionRequestCoordinator.requestChats(activity, setOf(row.value.id), onCommitted = refresh); true
                        }
                        else -> false
                    }
                }.show()
            else -> Unit
        }
    }

    fun showAddFolder() = NameEntryDialog.show(
        activity, R.string.folder_add_title, save = repository::createFolder, onSaved = refresh
    )

    private fun showRenameFolder(row: DrawerRow.Folder) = NameEntryDialog.show(
        activity, R.string.folder_rename_title, row.value.name,
        save = { repository.renameFolder(row.value.id, it) }, onSaved = refresh
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

    companion object {
        private const val MENU_ADD_FOLDER = 1
        private const val MENU_FOLDER_PIN = 2
        private const val MENU_FOLDER_RENAME = 3
        private const val MENU_FOLDER_DELETE = 4
        private const val MENU_CHAT_PIN = 5
        private const val MENU_CHAT_MOVE = 6
        private const val MENU_CHAT_DELETE = 7
    }
}
