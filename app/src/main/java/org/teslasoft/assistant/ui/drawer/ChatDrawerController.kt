package org.teslasoft.assistant.ui.drawer

import android.content.Intent
import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.ui.activities.FoldersActivity
import org.teslasoft.assistant.ui.activities.ImageGalleryActivity
import org.teslasoft.assistant.ui.activities.SearchActivity
import org.teslasoft.assistant.ui.activities.SettingsActivity
import java.lang.ref.WeakReference

class ChatDrawerController private constructor(
    private val activity: FragmentActivity,
    private val drawer: DrawerLayout,
    private val panel: View,
    private val currentChatId: () -> String
) {
    private val repository = ChatNavigationRepository.get(activity)
    private val list: RecyclerView = panel.findViewById(R.id.drawer_hierarchy)
    private val pinnedFolders: LockedFoldersLayout = panel.findViewById(R.id.drawer_folders_pinned)
    private val pinnedHeading: android.widget.FrameLayout = panel.findViewById(R.id.drawer_folders_heading)
    private val folderContents: RecyclerView = panel.findViewById(R.id.drawer_folder_contents)
    private val displayPreferences = org.teslasoft.assistant.preferences.Preferences.getPreferences(activity, "")
    private var pinnedHolder: DrawerHierarchyAdapter.Holder? = null
    private var pinnedRow: DrawerRow.FoldersHeader? = null
    private var foldersHeaderIndex = -1
    private var lockFolders = false
    private var separateFolders = false
    private val updates = DrawerUpdateDispatcher()
    // A chat is already being opened from this drawer; further taps wait for it.
    private var opening = false
    private val rowActions = DrawerRowActions(activity) { refresh() }
    private val rowCallbacks = object : DrawerHierarchyAdapter.Callbacks {
        override fun onClick(row: DrawerRow) = handleClick(row)
        override fun onLongClick(anchor: View, row: DrawerRow) = rowActions.onLongClick(anchor, row)
    }
    private val adapter = DrawerHierarchyAdapter(activity, currentChatId, rowCallbacks, updates::dispatch)
    private val folderAdapter by lazy {
        DrawerHierarchyAdapter(activity, currentChatId, rowCallbacks, updates::dispatch)
    }
    // Stored row details were filled in or changed in the background. A
    // closed drawer rereads the list when it opens.
    private val indexListener: () -> Unit = { if (isOpen()) refresh() }
    private val foldersLauncher = activity.activityResultRegistry.register(
        "chat-drawer-folders", ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val chatId = result.data?.getStringExtra(FoldersActivity.EXTRA_CHAT_ID)
            val name = result.data?.getStringExtra(FoldersActivity.EXTRA_CHAT_NAME)
            if (chatId != null && name != null) {
                if (chatId == currentChatId()) close()
                else openConversationFromDrawer(ChatActivity.rootIntent(activity, chatId, name))
            }
        }
    }

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Back to this screen without the chat having opened.
            override fun onResume(owner: LifecycleOwner) {
                opening = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                foldersLauncher.unregister()
                ChatListIndex.removeListener(indexListener)
            }
        })
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        folderContents.layoutManager = LinearLayoutManager(activity)
        pinnedFolders.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                list.post {
                    if (lockFolders) adapter.setFolderPlaceholderHeight(pinnedFolders.height)
                    updatePinnedFolders()
                }
            }
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePinnedFolders() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updatePinnedFolders()
        })
        panel.findViewById<ImageButton>(R.id.drawer_close).setOnClickListener { close() }
        panel.findViewById<View>(R.id.drawer_search).setOnClickListener {
            activity.startActivity(Intent(activity, SearchActivity::class.java))
        }
        panel.findViewById<View>(R.id.drawer_new_chat).setOnClickListener { openNewChat() }
        panel.findViewById<View>(R.id.drawer_new_folder).setOnClickListener { rowActions.showAddFolder() }
        panel.findViewById<TextView>(R.id.drawer_settings).setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java).setAction(Intent.ACTION_VIEW))
        }
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                updates.setMoving(newState != DrawerLayout.STATE_IDLE)
            }

            override fun onDrawerOpened(drawerView: View) {
                // Allow swipe-to-close; the closed callback still disables edge-swipe opening.
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
            }
            override fun onDrawerClosed(drawerView: View) {
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
            }
        })
        ChatListIndex.addListener(indexListener)
        ChatListIndex.prepareAllInBackground(activity)
        refresh()
    }

    fun open() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.openDrawer(GravityCompat.START, true)
        refresh()
    }

    fun close() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.closeDrawer(GravityCompat.START, true)
    }

    fun isOpen(): Boolean = drawer.isDrawerOpen(GravityCompat.START)

    /**
     * Starts with the drawer open exactly as the previous chat screen left it, list and
     * scroll position included, then pulls it back so this chat is revealed underneath.
     * The previous screen stays behind until this drawer is on screen, so nothing else
     * shows in between. [onCovered] runs once the previous screen has been closed.
     */
    fun revealChat(firstRow: Int, firstRowOffset: Int, onCovered: () -> Unit) {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
        drawer.openDrawer(GravityCompat.START, false)
        val pullBack = {
            if (firstRow != RecyclerView.NO_POSITION) {
                (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(firstRow, firstRowOffset)
            }
            // Wait until the open drawer has been drawn once, then pull it back.
            drawer.doOnPreDraw {
                drawer.post {
                    drawer.addDrawerListener(RetireOnMove(onCovered))
                    close()
                }
            }
        }
        val shown = lastShown
        if (shown != null) showProjection(shown) { pullBack() } else refresh { pullBack() }
    }

    /** Closes the replaced chat screen the moment this drawer starts to move. */
    private inner class RetireOnMove(private val onCovered: () -> Unit) : DrawerLayout.SimpleDrawerListener() {
        private var done = false

        override fun onDrawerStateChanged(newState: Int) = finish()
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) = finish()
        override fun onDrawerClosed(drawerView: View) = finish()

        private fun finish() {
            if (done) return
            done = true
            drawer.post { drawer.removeDrawerListener(this) }
            retireReplaced(activity)
            onCovered()
        }
    }

    fun refresh(onShown: () -> Unit = {}) {
        // Chat-list display options may have changed in Settings.
        lockFolders = displayPreferences.getLockFolderLocationInChatList()
        separateFolders = displayPreferences.getOpenFoldersInSeparateArea()
        updates.dispatch {
            adapter.refreshDisplayIfChanged()
            if (lockFolders) folderAdapter.refreshDisplayIfChanged()
        }
        activity.lifecycleScope.launch {
            val projection = withContext(Dispatchers.IO) {
                when (val result = readyNavigation(activity, repository)) {
                    is ChatNavigationResult.Success -> {
                        adapter.prepareCompanionImages(result.value.allChats)
                        if (lockFolders) folderAdapter.prepareCompanionImages(result.value.allChats)
                        val foldersExpanded = repository.areFoldersExpanded()
                        val expanded = result.value.folders.mapNotNullTo(LinkedHashSet()) {
                            it.folder.id.takeIf(repository::isFolderExpanded)
                        }
                        DrawerHierarchyProjection.build(result.value, foldersExpanded, expanded, separateFolders)
                    }
                    is ChatNavigationResult.Failure -> null
                }
            }
            if (projection == null) {
                Toast.makeText(activity, R.string.label_sorry_action_failed, Toast.LENGTH_LONG).show()
                onShown()
            } else updates.dispatch {
                showProjection(projection, onShown)
            }
        }
    }

    private fun showProjection(projection: List<DrawerRow>, onShown: () -> Unit) {
        lastShown = projection
        val mainRows = if (lockFolders) {
            val layers = DrawerHierarchyProjection.lockedLayers(projection)
            val holder = pinnedHolder ?: adapter.onCreateViewHolder(pinnedHeading, 0).also {
                pinnedHeading.addView(it.itemView)
                pinnedHolder = it
            }
            if (pinnedRow != layers.header) {
                holder.bind(layers.header)
                pinnedRow = layers.header
            }
            pinnedFolders.visibility = View.VISIBLE
            adapter.setFolderPlaceholderHeight(pinnedFolders.height)
            if (folderContents.adapter == null) folderContents.adapter = folderAdapter
            folderAdapter.submitList(layers.folderRows)
            layers.mainRows
        } else {
            pinnedFolders.visibility = View.GONE
            adapter.setFolderPlaceholderHeight(null)
            (folderContents.adapter as? DrawerHierarchyAdapter)?.submitList(emptyList())
            projection
        }
        adapter.submitList(mainRows) {
            foldersHeaderIndex = mainRows.indexOfFirst { it is DrawerRow.FoldersHeader }
            list.post { updatePinnedFolders() }
            onShown()
        }
    }

    /** Follow the container's reserved space until it reaches the very top.
     * Its height is capped independently, and its heading never scrolls away. */
    private fun updatePinnedFolders() {
        val index = foldersHeaderIndex
        val manager = list.layoutManager as? LinearLayoutManager
        if (!lockFolders || index < 0 || manager == null) {
            pinnedFolders.visibility = View.GONE
            return
        }
        val first = manager.findFirstVisibleItemPosition()
        val top = if (first > index) 0 else manager.findViewByPosition(index)?.top ?: return
        pinnedFolders.translationY = top.coerceAtLeast(0).toFloat()
        pinnedFolders.visibility = View.VISIBLE
    }

    private fun handleClick(row: DrawerRow) {
        when (row) {
            DrawerRow.Gallery -> activity.startActivity(Intent(activity, ImageGalleryActivity::class.java))
            is DrawerRow.FoldersHeader -> {
                if (separateFolders) foldersLauncher.launch(
                    Intent(activity, FoldersActivity::class.java)
                        .putExtra(FoldersActivity.EXTRA_CHAT_ID, currentChatId())
                ) else {
                    repository.setFoldersExpanded(!row.expanded)
                    refresh()
                }
            }
            is DrawerRow.Folder -> { repository.setFolderExpanded(row.value.id, !row.expanded); refresh() }
            is DrawerRow.Chat -> {
                if (row.value.id == currentChatId()) close()
                else openConversationFromDrawer(
                    ChatActivity.rootIntent(activity, row.value.id, row.value.name)
                        .putExtra(ChatActivity.EXTRA_START_DATE_SAVED, row.value.startedAt != null)
                )
            }
            is DrawerRow.Section -> Unit
        }
    }

    private fun openNewChat() {
        activity.lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                NewConversationCoordinator(activity).createDefaultPendingConversation()
            }
            openConversationFromDrawer(
                ChatActivity.rootIntent(activity, pending.id, pending.name, pendingConversation = true)
            )
        }
    }

    /**
     * Both new and saved conversations are revealed by the drawer pulling back. The new
     * chat screen opens on top of this one instead of replacing the app's screens, so this
     * screen and its open drawer stay visible until the new one has drawn its own drawer.
     */
    private fun openConversationFromDrawer(intent: Intent) {
        if (opening) return
        opening = true
        val manager = list.layoutManager as? LinearLayoutManager
        val firstRow = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val firstRowOffset = (manager?.findViewByPosition(firstRow)?.top ?: 0) - list.paddingTop
        replaced = WeakReference(activity)
        intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_REVEAL_FROM_DRAWER, true)
                .putExtra(EXTRA_DRAWER_FIRST_ROW, firstRow)
                .putExtra(EXTRA_DRAWER_FIRST_ROW_OFFSET, firstRowOffset)
        )
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_REVEAL_FROM_DRAWER = "revealFromDrawer"
        const val EXTRA_DRAWER_FIRST_ROW = "drawerFirstRow"
        const val EXTRA_DRAWER_FIRST_ROW_OFFSET = "drawerFirstRowOffset"

        // The drawer list last shown, so the next chat screen's drawer opens already
        // filled in instead of reading the whole chat list again first. Main thread only.
        private var lastShown: List<DrawerRow>? = null

        // The chat screen a drawer tap is replacing. Main thread only.
        private var replaced: WeakReference<Activity>? = null

        /** Closes the chat screen that [current] was opened to replace, if it is still open. */
        fun retireReplaced(current: Activity) {
            val previous = replaced?.get()
            replaced = null
            if (previous == null || previous === current || previous.isFinishing) return
            previous.finish()
            @Suppress("DEPRECATION")
            previous.overridePendingTransition(0, 0)
        }

        /** Worker thread only. The chat list, with every chat's details
         *  prepared before it is shown; never a row without them. */
        fun readyNavigation(context: android.content.Context, repository: ChatNavigationRepository) =
            repository.snapshot().let { result ->
                val chats = (result as? ChatNavigationResult.Success)?.value?.allChats
                    ?.filter { it.listIndex == null }?.map { it.id }.orEmpty()
                if (chats.isNotEmpty() && ChatListIndex.ensureReady(context, chats)) repository.snapshot()
                else result
            }

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
