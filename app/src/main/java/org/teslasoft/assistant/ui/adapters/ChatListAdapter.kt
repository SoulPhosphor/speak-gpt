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

package org.teslasoft.assistant.ui.adapters

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.util.ProfileImageResolver
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.util.ChatIdentity
import org.teslasoft.assistant.util.LegacyAvatarResolver
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.StaticAvatarParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import androidx.core.graphics.createBitmap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChatListAdapter(
    private val dataArray: ArrayList<HashMap<String, String>>,
    private val selectorProjection: ArrayList<HashMap<String, String>>,
    private val mContext: Fragment,
    private var showMemoryStatus: Boolean
) : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_MEMORY_MARKER = "memory_marker"
        private const val PAYLOAD_CHAT_PREVIEW = "chat_preview"
        private const val EMPTY_CHAT_PREVIEW = "No messages yet."
    }

    private var preferences: Preferences? = null
    private var bulkActionMode = false
    private val disposed = AtomicBoolean(false)
    private val animatedChatIds = HashSet<String>()
    private val previewRequests = HashSet<String>()
    private val boundMemoryStates = HashMap<String, String?>()
    private val boundMemoryExclusions = HashMap<String, Boolean>()
    private val previewLoader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chat-preview-loader")
    }
    private val memoryStatusLoader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chat-memory-status-loader")
    }
    private val memoryStatusGeneration = AtomicInteger(0)
    private var memoryStatusFuture: Future<*>? = null

    // Memory-system review markers (pending / partially archived / archived /
    // excluded) per chat id. Loaded off the main thread only while the display
    // setting is enabled — the store is SQLCipher-backed and must never be
    // opened during a bind. Null until loaded: rows retain an invisible line.
    private var reviewStates: HashMap<String, String>? = null

    init {
        if (showMemoryStatus) loadMemoryStatuses()
    }

    private fun loadMemoryStatuses() {
        if (!showMemoryStatus || disposed.get()) return
        val generation = memoryStatusGeneration.incrementAndGet()
        memoryStatusFuture = memoryStatusLoader.submit {
            try {
                val appContext = mContext.context?.applicationContext ?: return@submit
                if (!MemoryStore.isProvisioned(appContext)) return@submit
                val states = MemoryStore.getInstance(appContext).chatReviewStates()
                mContext.activity?.runOnUiThread {
                    if (disposed.get() || !showMemoryStatus ||
                        generation != memoryStatusGeneration.get()
                    ) return@runOnUiThread
                    val changedRows = ArrayList<Int>()
                    dataArray.forEachIndexed { position, chat ->
                        val chatId = ChatIdentity.effectiveId(chat)
                        if (!boundMemoryStates.containsKey(chatId)) return@forEachIndexed

                        val previousState = boundMemoryStates[chatId]
                        val currentState = if (boundMemoryExclusions[chatId] == true) {
                            "excluded"
                        } else {
                            states[chatId]
                        }
                        if (previousState != currentState) {
                            boundMemoryStates[chatId] = currentState
                            changedRows.add(position)
                        }
                    }
                    reviewStates = states
                    changedRows.forEach { position ->
                        notifyItemChanged(position, PAYLOAD_MEMORY_MARKER)
                    }
                }
            } catch (_: Exception) { /* markers are decoration; never break the list */ }
        }
    }

    fun setShowMemoryStatus(show: Boolean) {
        if (showMemoryStatus == show || disposed.get()) return

        showMemoryStatus = show
        memoryStatusGeneration.incrementAndGet()
        memoryStatusFuture?.cancel(true)
        memoryStatusFuture = null
        reviewStates = null
        boundMemoryStates.clear()
        boundMemoryExclusions.clear()

        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MEMORY_MARKER)
        }
        if (show) loadMemoryStatuses()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = if (Preferences.getPreferences(mContext.requireActivity(), "").getHideModelNames()) {
            LayoutInflater.from(parent.context).inflate(R.layout.view_chat_name_min, parent, false)
        } else {
            LayoutInflater.from(parent.context).inflate(R.layout.view_chat_name, parent, false)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chatId = ChatIdentity.effectiveId(dataArray[position])
        holder.bind(
            dataArray[position],
            selectorProjection[position],
            position,
            animatedChatIds.add(chatId)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.any {
                it != PAYLOAD_MEMORY_MARKER && it != PAYLOAD_CHAT_PREVIEW
            }
        ) {
            onBindViewHolder(holder, position)
            return
        }

        val chat = dataArray[position]
        if (payloads.contains(PAYLOAD_MEMORY_MARKER)) holder.bindMemoryMarker(chat)
        if (payloads.contains(PAYLOAD_CHAT_PREVIEW)) holder.bindPreview(chat)
    }

    override fun getItemCount(): Int {
        return dataArray.size
    }

    fun deleteItemAtPosition(position: Int) {
        dataArray.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, itemCount)
    }
    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.clearAnimation()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        dispose()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            memoryStatusGeneration.incrementAndGet()
            memoryStatusFuture?.cancel(true)
            memoryStatusLoader.shutdownNow()
            previewLoader.shutdownNow()
        }
    }

    private fun requestPreview(chat: HashMap<String, String>) {
        if (disposed.get()) return
        val chatId = ChatIdentity.effectiveId(chat)
        synchronized(previewRequests) {
            if (!previewRequests.add(chatId)) return
        }

        val appContext = mContext.context?.applicationContext ?: return
        previewLoader.execute {
            try {
                val result = ChatPreferences.getChatPreferences()
                    .getChatByIdResult(appContext, chatId)
                val preview = if (result.messages.isNotEmpty()) {
                    result.messages[0]["message"].toString()
                } else {
                    EMPTY_CHAT_PREVIEW
                }

                mContext.activity?.runOnUiThread {
                    if (disposed.get()) return@runOnUiThread
                    val position = dataArray.indexOfFirst {
                        ChatIdentity.effectiveId(it) == chatId
                    }
                    if (position < 0) return@runOnUiThread

                    val chat = dataArray[position]
                    if (chat["first_message"] != preview) {
                        chat["first_message"] = preview
                        notifyItemChanged(position, PAYLOAD_CHAT_PREVIEW)
                    }
                }
            } catch (_: Exception) {
                // A preview is optional decoration. Storage-health handling is
                // owned by ChatPreferences; a failed preview must not break the list.
            }
        }
    }

    fun setBulkActionMode(bulkActionMode: Boolean) {
        this.bulkActionMode = bulkActionMode
    }

    private fun checkSelectionIsEmpty(): Boolean {
        var isEmpty = true

        for (projection in selectorProjection) {
            if (projection["selected"] == "true") {
                isEmpty = false
                break
            }
        }

        return isEmpty
    }

    fun unselectAll() {
        for (projection in selectorProjection) {
            projection["selected"] = "false"
        }

        setBulkActionMode(false)
        listener?.onChangeBulkActionMode(bulkActionMode)
    }

    fun selectAll() {
        for (projection in selectorProjection) {
            projection["selected"] = "true"
        }

        setBulkActionMode(true)
        listener?.onChangeBulkActionMode(bulkActionMode)
    }

    fun edit(position: Int, name: String) {
        listener?.onRename(position, name, ChatIdentity.effectiveId(dataArray[position]))
    }

    private var listener: OnInteractionListener? = null

    fun setOnInteractionListener(listener: OnInteractionListener) {
        this.listener = listener
    }

    interface OnInteractionListener {
        fun onRename(position: Int, name: String, id: String)
        fun onBulkSelectionChanged(position: Int, selected: Boolean)
        fun onChangeBulkActionMode(mode: Boolean)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.name)
        private val icon: ImageView = itemView.findViewById(R.id.chat_icon)
        // The icon's original scale type (glyph rendering). A Companion photo
        // uses centerCrop; the no-photo path restores this so a row recycled
        // from a photo row renders its glyph correctly.
        private val iconInitialScaleType = icon.scaleType
        private val textFirstMessage: TextView = itemView.findViewById(R.id.chat_first_message)
        private val modelName: TextView = itemView.findViewById(R.id.model_name)
        private val root: MaterialCardView = itemView.findViewById(R.id.root)
        private val pinMarker: ImageView = itemView.findViewById(R.id.pin_marker)
        private val textModel: TextView = itemView.findViewById(R.id.textModel)
        private val memoryMarker: TextView = itemView.findViewById(R.id.memory_marker)

        // The chat's Companion picture (profile-images-plan.md, CHAT AND
        // CHAT-LIST DISPLAY): when its Companion has an assigned, available
        // picture it takes precedence over the per-chat avatar and glyph.
        // Resolved once in bind() (a cheap prefs read, no memory DB) and
        // re-applied as a final override after the card/glyph styling runs
        // (including on a bulk-select re-render), so a recycled row never
        // keeps a stale photo or shows the glyph in its place.
        private var companionImageFile: File? = null
        private var companionImageShape: String = "flower"

        @SuppressLint("SetTextI18n")
        fun bind(
            chatMessage: HashMap<String, String>,
            projection: HashMap<String, String>,
            position: Int,
            animateEntrance: Boolean
        ) {
            preferences = Preferences.getPreferences(mContext.requireActivity(), "")

            if (preferences?.getAvatarTypeByChatId(ChatIdentity.effectiveId(chatMessage), mContext.requireActivity()) == "builtin") {
                icon.setImageResource(StaticAvatarParser.parse(preferences?.getAvatarIdByChatId(ChatIdentity.effectiveId(chatMessage), mContext.requireActivity())!!))
            } else {
                val legacyAvatarId = preferences?.getAvatarIdByChatId(ChatIdentity.effectiveId(chatMessage), mContext.requireActivity())
                val legacyAvatarFile = if (legacyAvatarId != null) LegacyAvatarResolver.resolve(mContext.requireActivity().getExternalFilesDir("images"), legacyAvatarId) else null

                if (legacyAvatarFile != null) {
                    readAndDisplay(Uri.fromFile(legacyAvatarFile), icon)
                }
            }

            if (chatMessage["pinned"] == "true") {
                pinMarker.visibility = View.VISIBLE
            } else {
                pinMarker.visibility = View.GONE
            }

            name.text = if (chatMessage["name"].toString().trim().contains("_autoname_")) mContext.getString(R.string.label_untitled_chat) else chatMessage["name"].toString()

            bindPreview(chatMessage)
            if (!chatMessage.containsKey("first_message")) {
                requestPreview(chatMessage)
            }

            val chatHash = ChatIdentity.effectiveId(chatMessage)
            val chatPreferences = Preferences.getPreferences(mContext.requireActivity(), chatHash)
            val model: String = chatPreferences.getModel()

            // Resolve this chat's assistant picture (prefs + a file-exists check,
            // no memory DB) so the override below shows it ahead of the per-chat
            // avatar/glyph. AI-side cascade (owner ruling, July 21 2026): the
            // Companion's own picture, else the Default AI Avatar - so a chat
            // whose Companion has no picture (or no Companion) still shows the AI
            // default, and a picture edited elsewhere re-resolves on next bind.
            val personaId = chatPreferences.getPersonaId()
            companionImageShape = GlobalPreferences.getPreferences(mContext.requireActivity()).getProfileImageShape()
            val companionRef = if (personaId.isNotEmpty()) {
                PersonaPreferences.getPersonaPreferences(mContext.requireActivity()).getPersona(personaId).avatarRef
            } else ""
            companionImageFile = ProfileImageResolver.resolveAiImageFile(mContext.requireActivity(), companionRef)

            bindMemoryMarker(chatMessage, chatPreferences)

            icon.contentDescription = name.text

            modelName.text = model
            initModelName(model)

            if (bulkActionMode && (projection["selected"] ?: "false") == "true") {
                updateCard(root, icon, pinMarker, R.color.accent_300, R.color.accent_900, chatMessage, true)
            } else {
                reloadCards(chatMessage)
            }

            root.setOnClickListener {
                if (bulkActionMode) {
                    switchBulkActionState(projection, chatMessage, position)
                } else {
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        mContext.requireActivity() as Activity,
                        Pair.create(root, ViewCompat.getTransitionName(root))
                    )

                    val i = Intent(
                        mContext.requireActivity(),
                        ChatActivity::class.java
                    ).setAction(Intent.ACTION_VIEW)

                    i.putExtra("name", chatMessage["name"].toString())
                    i.putExtra("chatId", ChatIdentity.effectiveId(chatMessage))

                    mContext.requireActivity().startActivity(i, options.toBundle())
                }
            }

            root.setOnLongClickListener {
                switchBulkActionState(projection, chatMessage, position)

                return@setOnLongClickListener true
            }

            itemView.clearAnimation()
            if (animateEntrance) {
                val animation: Animation = AnimationUtils.loadAnimation(mContext.context, R.anim.fade_in)
                animation.duration = 300
                animation.startOffset = 50
                itemView.startAnimation(animation)
            } else {
                itemView.alpha = 1f
            }
        }

        fun bindPreview(chatMessage: HashMap<String, String>) {
            textFirstMessage.text = chatMessage["first_message"] ?: EMPTY_CHAT_PREVIEW
        }

        fun bindMemoryMarker(chatMessage: HashMap<String, String>) {
            val chatHash = ChatIdentity.effectiveId(chatMessage)
            if (!showMemoryStatus) {
                boundMemoryExclusions.remove(chatHash)
                boundMemoryStates.remove(chatHash)
                memoryMarker.visibility = View.GONE
                return
            }
            val chatPreferences = Preferences.getPreferences(mContext.requireActivity(), chatHash)
            bindMemoryMarker(chatMessage, chatPreferences)
        }

        private fun bindMemoryMarker(
            chatMessage: HashMap<String, String>,
            chatPreferences: Preferences
        ) {
            val chatHash = ChatIdentity.effectiveId(chatMessage)
            if (!showMemoryStatus) {
                boundMemoryExclusions.remove(chatHash)
                boundMemoryStates.remove(chatHash)
                memoryMarker.visibility = View.GONE
                return
            }

            val excluded = chatPreferences.isChatExcludedFromMemory()
            val memoryState = if (excluded) "excluded" else reviewStates?.get(chatHash)
            boundMemoryExclusions[chatHash] = excluded
            boundMemoryStates[chatHash] = memoryState
            if (memoryState == null) {
                memoryMarker.visibility = View.INVISIBLE
            } else {
                memoryMarker.visibility = View.VISIBLE
                memoryMarker.text = mContext.getString(
                    when (memoryState) {
                        "pending" -> R.string.memory_marker_pending
                        "partial" -> R.string.memory_marker_partial
                        "processed" -> R.string.memory_marker_processed
                        else -> R.string.memory_marker_excluded
                    }
                )
            }
        }

        private fun switchBulkActionState(projection: HashMap<String, String>, chatMessage: HashMap<String, String>, position: Int) {
            if ((projection["selected"] ?: "false") == "true") {
                projection["selected"] = "false"
                reloadCards(chatMessage)
                if (checkSelectionIsEmpty()) setBulkActionMode(false)
            } else {
                setBulkActionMode(true)
                projection["selected"] = "true"
                updateCard(root, icon, pinMarker, R.color.accent_300, R.color.accent_900, chatMessage, true)
            }

            listener?.onBulkSelectionChanged(position, (projection["selected"] ?: "false") == "true")
            listener?.onChangeBulkActionMode(bulkActionMode)
        }

        @SuppressLint("SetTextI18n")
        private fun initModelName(model: String) {
            textModel.text = "CUSTOM"
            textModel.text = if (model.lowercase().contains("deepseek")) "DEEPSEEK" else textModel.text
            textModel.text = if (model.lowercase().contains("o3")) "O3" else textModel.text
            textModel.text = if (model.lowercase().contains("o1")) "O1" else textModel.text
            textModel.text = if (model.lowercase().contains("gemini")) "GEMINI" else textModel.text
            textModel.text = if (model.lowercase().contains("gemma")) "GEMMA" else textModel.text
            textModel.text = if (model.lowercase().contains("mistral") || model.lowercase().contains("mixtral")) "MISTRAL" else textModel.text
            textModel.text = if (model.lowercase().contains("perplexity")) "PERPLEXITY" else textModel.text
            textModel.text = if (model.lowercase().contains("claude")) "CLAUDE" else textModel.text
            textModel.text = if (model.lowercase().contains("llama")) "META" else textModel.text
            textModel.text = if (model.lowercase().contains("gpt-4") && model.lowercase().contains("turbo")) "GPT 4 Turbo" else textModel.text
            textModel.text = if (model.lowercase().contains("gpt-4") && !model.lowercase().contains("turbo")) "GPT 4" else textModel.text
            textModel.text = if (model.lowercase().contains("gpt-3.5") && model.lowercase().contains("turbo")) "GPT 3.5 Turbo" else textModel.text
            textModel.text = if (model.lowercase().contains("gpt-4o")) "GPT 4o" else textModel.text
        }

        private fun reloadCards(chatMessage: HashMap<String, String>) {
            when (textModel.text) {
                "O1", "O3" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_epic, R.color.gpt_icon_epic, chatMessage, false)
                }

                "GPT 4", "GEMINI" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_red, R.color.gpt_icon_red, chatMessage, false)
                }

                "GPT 3.5 Turbo", "GEMMA" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_yellow, R.color.gpt_icon_yellow, chatMessage, false)
                }

                "PERPLEXITY" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_purple, R.color.gpt_icon_purple, chatMessage, false)
                }

                "GPT 4 Turbo", "CLAUDE" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_green, R.color.gpt_icon_green, chatMessage, false)
                }

                "MISTRAL", "META" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_orange, R.color.gpt_icon_orange, chatMessage, false)
                }

                "CUSTOM" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_blue, R.color.gpt_icon_blue, chatMessage, false)
                }

                "GPT 4o", "DEEPSEEK" -> {
                    updateCard(root, icon, pinMarker, R.color.tint_cyan, R.color.gpt_icon_cyan, chatMessage, false)
                }

                else -> {
                    icon.setImageResource(R.drawable.chatgpt_icon)
                    DrawableCompat.setTint(icon.getDrawable(), ContextCompat.getColor(mContext.requireActivity(), R.color.accent_900))
                    // This branch does not route through updateCard(), so apply
                    // the Companion override here too.
                    applyCompanionOverride()
                }
            }
        }

        /** Companion picture wins over the per-chat avatar/glyph (CHAT AND
         *  CHAT-LIST DISPLAY). Run after the card/glyph styling so it cleanly
         *  resets and replaces the icon (through the shared binder, which
         *  clears any prior request, tint and background) whenever the chat's
         *  Companion has an available picture. No-op otherwise, leaving the
         *  existing avatar/glyph in place. */
        private fun applyCompanionOverride() {
            val file = companionImageFile
            if (file != null) {
                ProfileImageBinder.bind(mContext.requireActivity(), icon, file, companionImageShape) { }
            } else {
                // No photo: leave the glyph/shape/tint updateCard set, only
                // undo a centerCrop left by a recycled photo row.
                icon.scaleType = iconInitialScaleType
            }
        }

        private fun updateCard(selector: MaterialCardView, icon: ImageView, pin: ImageView, tintColor: Int, iconColor: Int, chatMessage: HashMap<String, String>, isSelected: Boolean) {
            if (isSelected) {
                selector.backgroundTintList = ColorStateList.valueOf(mContext.requireActivity().getColor(R.color.accent_300) + 0x99000000.toInt()) // Kotlin programming language bug (actually integer can handle values from -0x80000000 to 0x7FFFFFFF)
            } else if (Preferences.getPreferences(mContext.requireActivity(), "").getMonochromeBackgroundForChatList()) {
                selector.backgroundTintList = ColorStateList.valueOf(mContext.requireActivity().getColor(R.color.accent_100) + 0x66000000)
            } else {
                selector.backgroundTintList = ColorStateList.valueOf(mContext.requireActivity().getColor(tintColor) - 0x11000000)
            }

            icon.background = getAccentDrawable(
                ContextCompat.getDrawable(mContext.requireActivity(), getResourceFromModelName())!!, mContext.requireActivity().getColor(tintColor))

            DrawableCompat.setTint(pin.getDrawable(), ContextCompat.getColor(mContext.requireActivity(), iconColor))

            if (preferences?.getAvatarTypeByChatId(ChatIdentity.effectiveId(chatMessage), mContext.requireActivity()) == "builtin") {
                DrawableCompat.setTint(icon.getDrawable(), ContextCompat.getColor(mContext.requireActivity(), iconColor))
            }

            applyCompanionOverride()
        }

        private fun getResourceFromModelName() : Int {
            return when (textModel.text) {
                "O1", "O3" -> {
                    R.drawable.mtrl_shape_clover
                }

                "GPT 4", "GEMINI" -> {
                    R.drawable.mtrl_shape_scallop
                }

                "GPT 3.5 Turbo", "GEMMA" -> {
                    R.drawable.mtrl_shape_hive
                }

                "PERPLEXITY" -> {
                    R.drawable.mtrl_shape_diamond
                }

                "GPT 4 Turbo", "CLAUDE" -> {
                    R.drawable.mtrl_shape_pill
                }

                "MISTRAL", "META" -> {
                    R.drawable.mtrl_shape_diamond
                }

                "CUSTOM" -> {
                    R.drawable.mtrl_shape_scallop
                }

                "GPT 4o", "DEEPSEEK" -> {
                    R.drawable.mtrl_shape_pill
                }

                else -> {
                    R.drawable.mtrl_shape_clover
                }
            }
        }

        private fun getAccentDrawable(drawable: Drawable, color: Int) : Drawable {
            DrawableCompat.setTint(DrawableCompat.wrap(drawable), color)
            return drawable
        }

        private fun readAndDisplay(uri: Uri, icon: ImageView?) {
            val bitmap = readFile(uri)

            if (bitmap != null) {
                icon?.setImageBitmap(roundCorners(bitmap))
            }
        }

        private fun readFile(uri: Uri) : Bitmap? {
            return mContext.requireActivity().contentResolver?.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { _ ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        }

        private fun roundCorners(bitmap: Bitmap): Bitmap {
            // Create a bitmap with the same size as the original.
            val output = createBitmap(bitmap.width, bitmap.height)

            // Prepare a canvas with the new bitmap.
            val canvas = Canvas(output)

            // The paint used to draw the original bitmap onto the new one.
            val paint = Paint().apply {
                isAntiAlias = true
                color = -0xbdbdbe
            }

            // The rectangle bounds for the original bitmap.
            val rect = Rect(0, 0, bitmap.width, bitmap.height)
            val rectF = RectF(rect)

            // Draw rounded rectangle as background.
            canvas.drawRoundRect(rectF, 80.0f, 80.0f, paint)

            // Change the paint mode to draw the original bitmap on top.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

            // Draw the original bitmap.
            canvas.drawBitmap(bitmap, rect, rect, paint)

            return output
        }
    }
}
