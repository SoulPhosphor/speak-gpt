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

package org.teslasoft.assistant.ui.adapters.chat

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.LineHeightSpan
import android.text.style.TtsSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeHistoryPresentation
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.ui.activities.ImageBrowserActivity
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.fragments.dialogs.EditMessageDialogFragment
import org.teslasoft.assistant.util.LegacyAvatarResolver
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.StaticAvatarParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import java.util.Locale
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.content.edit
import org.teslasoft.assistant.util.ShareUtil.Companion.shareBase64Image
import org.teslasoft.assistant.util.ShareUtil.Companion.sharePlainText

class ChatAdapter(private val dataArray: ArrayList<HashMap<String, Any>>, private val selectorProjection: ArrayList<HashMap<String, Any>>, private val context: FragmentActivity, private val preferences: Preferences, private var chatId: String) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), EditMessageDialogFragment.StateChangesListener {

    private val generatedImageDataUrls = HashMap<String, String>()
    private var listener: OnUpdateListener? = null
    private var bulkActionMode = false

    // Assistant-side picture, already cascaded by ChatActivity off the main
    // thread (the active Companion's own picture, else the Default AI Avatar).
    // Row binding never touches storage. Null only when neither exists, in
    // which case the row falls through to the built-in glyph.
    // [companionImageShape] is the current Default Shape to render it with.
    private var companionImageFile: File? = null
    private var companionImageShape: String = "flower"
    private var companionNameStyle: ChatNameStyle.Resolved? = null

    // The chat's current companion name, used only as the display fallback for
    // assistant messages that carry no stamped [KEY_COMPANION_NAME] of their own.
    private var companionLabel: String? = null

    // User-side picture (owner ruling, July 21 2026), already cascaded by
    // ChatActivity: the active Roleplay Character's picture, else the active My
    // Persona's, else the Default Personal Avatar. Null only when none of those
    // is set, in which case the user bubble shows the generic person icon.
    private var userImageFile: File? = null
    private var userImageShape: String = "flower"

    /** Supplies the already-resolved assistant presentation in one update.
     *  Storage and identity resolution stay in ChatActivity; rows only render. */
    fun setCompanionPresentation(
        file: File?,
        shape: String,
        label: String?,
        nameStyle: ChatNameStyle.Resolved
    ) {
        companionImageFile = file
        companionImageShape = shape
        companionLabel = label
        companionNameStyle = nameStyle
        notifyDataSetChanged()
    }

    /** Called by ChatActivity with the already-resolved user-side picture (or
     *  null) plus the current Default Shape. Rebinds visible rows so the user
     *  bubble's avatar reflects the active identity / Personal Default. */
    fun setUserAvatar(file: File?, shape: String) {
        userImageFile = file
        userImageShape = shape
        notifyDataSetChanged()
    }

    // Adapter position of the message currently being read aloud via its
    // speak button, or -1. Set the moment the press is registered (before the
    // audio is even prepared) and cleared by the host when playback finishes,
    // so the button visibly acknowledges the tap during the multi-second gap
    // before any sound comes out.
    private var speakingPosition = -1

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_BOT = 1
        private const val TYPE_IMAGE_CONFIRMATION = 2
        private const val MENU_INCLUDE_REMOVE = 101
        private const val MENU_INCLUDE_CONDENSE = 102
        private const val MENU_INCLUDE_EDIT = 103

        // Transient inline image-confirmation card row
        // (image-generation-rebuild-plan.md §5). These rows live only in
        // the on-screen list: ChatActivity filters them out of persistence,
        // and the model projection skips them because their message text is
        // blank.
        const val KEY_IMAGE_CONFIRMATION = "imageConfirmationCard"
        const val KEY_IMAGE_CONFIRMATION_PROMPT = "imageConfirmationPrompt"
        const val KEY_IMAGE_CONFIRMATION_COMPANION = "imageConfirmationCompanion"

        // The companion name shown on an assistant message's label. Stamped
        // onto the message when the reply is created (ChatActivity.putMessage)
        // so it is LOCKED to the companion that was active for that reply and
        // a later companion switch never rewrites past labels. Absent on
        // messages written before this feature; those fall back to the chat's
        // current companion name at display time.
        const val KEY_COMPANION_NAME = "companionName"

        // Transient inline Creating Image row (plan §5 progress
        // experience): the visible status and Cancel action for a running
        // generation. Same transience rules as the confirmation card —
        // filtered from persistence, blank message text keeps it out of
        // the model projection.
        private const val TYPE_IMAGE_PROGRESS = 3
        const val KEY_IMAGE_PROGRESS = "imageProgressCard"
    }

    fun setChatId(chatId: String) {
        this.chatId = chatId
    }

    /**
     * Which messages currently have their "Includes" record opened. Held on
     * the adapter rather than the row, because rows are recycled — keeping it
     * on the view would make an unrelated message inherit an open accordion
     * as soon as it scrolled into that recycled slot.
     */
    private val expandedIncludeRows: MutableSet<String> = mutableSetOf()

    override fun getItemViewType(position: Int): Int {
        if (dataArray[position][KEY_IMAGE_CONFIRMATION] == true) {
            return TYPE_IMAGE_CONFIRMATION
        }
        if (dataArray[position][KEY_IMAGE_PROGRESS] == true) {
            return TYPE_IMAGE_PROGRESS
        }
        return if (dataArray[position]["isBot"] == true) {
            TYPE_BOT
        } else {
            TYPE_USER
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ImageConfirmationViewHolder -> holder.bind(dataArray[position])
            is ImageProgressViewHolder -> holder.bind()
            is ViewHolder -> holder.bind(dataArray[position], position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_IMAGE_CONFIRMATION) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_image_confirmation_card, parent, false)
            return ImageConfirmationViewHolder(view)
        }
        if (viewType == TYPE_IMAGE_PROGRESS) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_image_progress_card, parent, false)
            return ImageProgressViewHolder(view)
        }
        val layoutId = when (viewType) {
            TYPE_BOT -> R.layout.view_assistant_bot_message
            TYPE_USER -> R.layout.view_assistant_user_message
            else -> error("Unsupported chat message view type: $viewType")
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view, context)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageProgressViewHolder) holder.stopAnimation()
        super.onViewRecycled(holder)
    }

    /** The §5 inline confirmation card: names the companion, keeps the
     *  prompt collapsed behind View Prompt so an intended surprise is not
     *  spoiled, and reports Create/Cancel back to the host. */
    inner class ImageConfirmationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.confirmation_title)
        private val prompt: TextView = itemView.findViewById(R.id.confirmation_prompt)
        private val btnCreate: MaterialButton = itemView.findViewById(R.id.btn_confirmation_create)
        private val btnCancel: MaterialButton = itemView.findViewById(R.id.btn_confirmation_cancel)
        private val btnViewPrompt: MaterialButton =
            itemView.findViewById(R.id.btn_confirmation_view_prompt)

        fun bind(chatMessage: HashMap<String, Any>) {
            val companion = chatMessage[KEY_IMAGE_CONFIRMATION_COMPANION]?.toString().orEmpty()
            title.text = context.getString(R.string.image_gen_card_title, companion)
            prompt.text = chatMessage[KEY_IMAGE_CONFIRMATION_PROMPT]?.toString().orEmpty()
            btnViewPrompt.setOnClickListener {
                prompt.visibility =
                    if (prompt.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            btnCreate.setOnClickListener { listener?.onImageConfirmationDecision(true) }
            btnCancel.setOnClickListener { listener?.onImageConfirmationDecision(false) }
        }
    }

    /** The §5 Creating Image row: the in-chat status for a running
     *  generation with its required visible Cancel action. */
    inner class ImageProgressViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.progress_title)
        private val btnCancel: MaterialButton = itemView.findViewById(R.id.btn_progress_cancel)
        private var dotsAnimator: ValueAnimator? = null

        fun bind() {
            stopAnimation()
            dotsAnimator = ValueAnimator.ofInt(0, 3).apply {
                duration = 1200L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    val dots = ".".repeat(animator.animatedValue as Int)
                    title.text = context.getString(R.string.image_gen_creating_title) + dots
                }
                start()
            }
            btnCancel.setOnClickListener { listener?.onImageProgressCancel() }
        }

        fun stopAnimation() {
            dotsAnimator?.cancel()
            dotsAnimator = null
        }
    }

    fun setOnUpdateListener(listener: OnUpdateListener) {
        this.listener = listener
    }

    fun setSpeakingPosition(position: Int) {
        if (position == speakingPosition) return
        val old = speakingPosition
        speakingPosition = position
        if (old != -1) notifyItemChanged(old)
        if (position != -1) notifyItemChanged(position)
    }

    fun clearSpeakingPosition() {
        setSpeakingPosition(-1)
    }

    /** The adapter position currently marked as being read aloud, or -1.
     *  Lets the host treat a second tap on the same speaker button as a stop
     *  instead of a restart. */
    fun getSpeakingPosition(): Int = speakingPosition

    private fun editMessage(position: Int, message: String) {
        dataArray[position]["message"] = message
        // Editing an incomplete assistant reply finalizes it: the user owns the
        // text now, so drop any incomplete-completion marker (and its error
        // fields) in the live list too. The persisted copy is cleared in
        // ChatPreferences.editMessage; this keeps the on-screen row in sync.
        if (dataArray[position]["isBot"] == true &&
            !MessageCompletionState.isComplete(dataArray[position][MessageCompletionState.KEY_STATE]?.toString())
        ) {
            dataArray[position][MessageCompletionState.KEY_STATE] = MessageCompletionState.DONE
            dataArray[position].remove(MessageCompletionState.KEY_STATE_DETAIL)
            dataArray[position].remove(MessageCompletionState.KEY_ERROR_TEXT)
        }
        listener?.onMessageEdited()
    }

    private fun deleteMessage(position: Int) {
        if (position < 0 || position >= dataArray.size) return
        dataArray.removeAt(position)
        notifyItemRemoved(position)
        if (position > 0) {
            notifyItemRangeChanged(position - 1, itemCount)
        } else {
            notifyItemRangeChanged(position, itemCount)
        }
    }

    override fun getItemCount(): Int {
        return dataArray.size
    }

    fun setBulkActionMode(bulkActionMode: Boolean) {
        this.bulkActionMode = bulkActionMode
    }

    private fun checkSelectionIsEmpty(): Boolean {
        var isEmpty = true

        for (projection in selectorProjection) {
            if (projection["selected"].toString() == "true") {
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

        bulkActionMode = false
        listener?.onChangeBulkActionMode(false)
    }

    fun selectAll() {
        for (projection in selectorProjection) {
            projection["selected"] = "true"
        }

        bulkActionMode = true
        listener?.onChangeBulkActionMode(true)
    }

    open inner class ViewHolder(itemView: View, private val debugContext: Context) : RecyclerView.ViewHolder(itemView) {
        private val ui: ConstraintLayout = itemView.findViewById(R.id.ui)
        private val icon: ImageView = itemView.findViewById(R.id.icon)
        // The icon's original XML backing (e.g. the assistant bubble's tonal
        // circle). A Companion photo fills the slot and drops it; every other
        // path restores it, so a recycled row can't keep a nulled background.
        private val iconInitialBackground = icon.background
        private val message: TextView = itemView.findViewById(R.id.message)
        private val username: TextView = itemView.findViewById(R.id.username)
        private val bubbleBg: ConstraintLayout = itemView.findViewById(R.id.bubble_bg)
        private val imageFrame: View = itemView.findViewById(R.id.image_frame)
        private val generatedImage: ImageView = itemView.findViewById(R.id.generated_image)
        private val generatedImageLoading: View = itemView.findViewById(R.id.generated_image_loading)
        private val generatedImageError: TextView = itemView.findViewById(R.id.generated_image_error)
        private val btnImagePrompt: MaterialButton = itemView.findViewById(R.id.btn_image_prompt)
        private val btnImageDownload: ImageButton = itemView.findViewById(R.id.btn_image_download)
        private var boundGeneratedImagePath: String? = null
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btn_copy)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val btnRetry: ImageButton = itemView.findViewById(R.id.btn_retry)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        private val btnSpeak: ImageButton = itemView.findViewById(R.id.btn_speak)
        // Present only on the assistant layout (the user row has no completion
        // marker); nullable so the shared binder remains safe on both sides.
        private val statusMarker: TextView? = itemView.findViewById(R.id.status_marker)
        // The "Includes" record of what this message carried. Absent from the
        // assistant bubble (attachments are user-side only), so nullable.
        private val includeSummary: LinearLayout? = itemView.findViewById(R.id.include_summary)
        private val includeSummaryHeader: LinearLayout? = itemView.findViewById(R.id.include_summary_header)
        private val includeSummaryLabel: TextView? = itemView.findViewById(R.id.include_summary_label)
        private val includeSummaryChevron: ImageView? = itemView.findViewById(R.id.include_summary_chevron)
        private val includeSummaryList: LinearLayout? = itemView.findViewById(R.id.include_summary_list)
        private val condensedBookmark: ImageView? = itemView.findViewById(R.id.condensed_bookmark)
        private val artifactBookmark: ImageView? = itemView.findViewById(R.id.artifact_bookmark)

        @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
        open fun bind(chatMessage: HashMap<String, Any>, position: Int) {

            val isGeneratedImage = chatMessage["message"].toString().startsWith("~file:")
            btnEdit.visibility = if (isGeneratedImage) View.GONE else View.VISIBLE
            if (isGeneratedImage) btnShare.isEnabled = false

            updateIncludeSummary(chatMessage, position)
            updatePresentation(chatMessage)
            updateRetryButton(chatMessage, position)
            updateShareButton(chatMessage)
            updateSpeakButton(chatMessage, position)
            updateStatusMarker(chatMessage)

            if (selectorProjection[position]["selected"].toString() == "true") {
                ui.setBackgroundColor(getSurface3Color(context))
            } else {
                updatePresentation(chatMessage)
            }

            ui.setOnLongClickListener {
                switchBulkActionState(position)
                return@setOnLongClickListener true
            }

            ui.setOnClickListener {
                if (bulkActionMode) {
                    switchBulkActionState(position)
                }
            }

            // Deliberately no long-click listener on the message text itself:
            // long-pressing the text starts native text selection (so parts of
            // a message can be highlighted and copied). Bulk message selection
            // is still reachable by long-pressing the row/bubble around the
            // text or the avatar.
            message.setOnClickListener {
                if (bulkActionMode) {
                    switchBulkActionState(position)
                }
            }

            btnEdit.setOnClickListener {
                if (!bulkActionMode) {
                    openEditDialog(chatMessage, position)
                }
            }

            btnCopy.setImageResource(R.drawable.ic_copy)
            btnCopy.setOnClickListener {
                val clipboard: ClipboardManager = context.getSystemService(FragmentActivity.CLIPBOARD_SERVICE) as ClipboardManager
                val messageText = chatMessage["message"].toString()
                // The failed/interrupted/stopped marker (and, when "Show chat
                // errors" is on, the coded error beneath it) lives in a separate
                // TextView from the reply body. Fold it into the copied text so
                // sharing a failure captures the exact wording shown on screen,
                // not just whatever partial reply text preceded it.
                val markerText = statusMarker
                    ?.takeIf { it.visibility == View.VISIBLE }
                    ?.text?.toString()?.takeIf { it.isNotBlank() }
                val fullText = when {
                    markerText == null -> messageText
                    messageText.isBlank() -> markerText
                    else -> "$messageText\n\n$markerText"
                }
                val clip = ClipData.newPlainText("response", fullText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.label_copy), Toast.LENGTH_SHORT).show()
            }

            if (isGeneratedImage) {
                if (chatMessage["isBot"] == true) {
                    message.visibility = View.GONE
                }
                processGeneratedImageFile(chatMessage)
            } else {
                boundGeneratedImagePath = null
                Glide.with(context).clear(generatedImage)
                (debugContext as FragmentActivity).runOnUiThread {
                    applyMarkdown(chatMessage)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    debugContext.runOnUiThread {
                        applyMarkdown(chatMessage)
                    }
                }, 100)

                // User-attached images belong to the Includes system, never
                // this provider-neutral generated-image content slot.
                imageFrame.visibility = View.GONE
                generatedImage.visibility = View.GONE
                generatedImageLoading.visibility = View.GONE
                generatedImageError.visibility = View.GONE
                btnImagePrompt.visibility = View.GONE
                btnImageDownload.visibility = View.GONE

                btnShare.setOnClickListener {
                    sharePlainText(context, chatMessage["message"].toString())
                }
                btnShare.isEnabled = true

                message.visibility = View.VISIBLE
            }
        }

        /**
         * Small persistent inline marker shown after an assistant reply did
         * not finish (interrupted / stopped / failed / an unrecognized
         * non-complete state). It stays hidden while the reply is still
         * streaming. The partial text stays visible above it. For a failed
         * reply, the coded error is shown next to the
         * marker ONLY when "Show chat errors" is on, and always separately from
         * the model's own words (it lives in a different field). No toast,
         * dialog, notification, or sound — just this line.
         */
        /**
         * Renders sent documents directly under the user's name. One to three
         * rows remain visible; only four or more collapse behind the count.
         *
         * Every branch sets visibility explicitly: these rows are recycled, so
         * an early return would let one message's open accordion reappear on
         * an unrelated message further down the conversation.
         */
        private fun updateIncludeSummary(chatMessage: HashMap<String, Any>, position: Int) {
            val summary = includeSummary ?: return
            val includes = ChatInclude.listFromJson(
                chatMessage[ChatActivity.INCLUDES_KEY]?.toString()
            )

            if (chatMessage["isBot"] == true || includes.isEmpty()) {
                summary.visibility = View.GONE
                condensedBookmark?.visibility = View.GONE
                artifactBookmark?.visibility = View.GONE
                includeSummaryList?.removeAllViews()
                return
            }

            val groups = IncludeHistoryPresentation.group(includes)
            updateIncludeBookmarks(groups)
            val fullIncludes = groups.fullRecords
            if (fullIncludes.isEmpty()) {
                summary.visibility = View.GONE
                includeSummaryList?.removeAllViews()
                return
            }

            summary.visibility = View.VISIBLE
            val collapsible = IncludeHistoryPresentation.shouldCollapse(fullIncludes.size)
            val composition = IncludeHistoryPresentation.compositionOf(fullIncludes)
            val summaryKey = fullIncludes.joinToString(separator = "\u001F") { it.id }
            val expanded = !collapsible || expandedIncludeRows.contains(summaryKey)

            includeSummaryHeader?.visibility = if (collapsible) View.VISIBLE else View.GONE
            includeSummaryList?.visibility = if (expanded) View.VISIBLE else View.GONE
            includeSummaryLabel?.text = if (collapsible) {
                context.getString(collapsedCountRes(composition), fullIncludes.size)
            } else {
                context.getString(R.string.include_label)
            }
            includeSummaryChevron?.rotation = if (expanded) 180f else 0f
            includeSummaryChevron?.contentDescription =
                context.getString(toggleDescRes(composition, expanded))

            if (expanded) {
                buildIncludeSummaryRows(fullIncludes)
            } else {
                includeSummaryList?.removeAllViews()
            }

            includeSummaryHeader?.setOnClickListener(
                if (collapsible) {
                    View.OnClickListener {
                        if (expandedIncludeRows.contains(summaryKey)) {
                            expandedIncludeRows.remove(summaryKey)
                        } else {
                            expandedIncludeRows.add(summaryKey)
                        }
                        notifyItemChanged(position)
                    }
                } else {
                    null
                }
            )
        }

        /**
         * Full documents keep their metadata row. Condensed documents use the
         * bookmark-with-plus; removed sent documents use the empty bookmark.
         * The plus bookmark offers Edit or Remove. The empty bookmark opens
         * its already-removed reminder for optional editing.
         */
        private fun updateIncludeBookmarks(groups: IncludeHistoryPresentation.Groups) {
            updateBookmark(
                marker = condensedBookmark,
                items = groups.condensedBookmarks,
                canRemove = true
            )
            updateBookmark(
                marker = artifactBookmark,
                items = groups.artifactBookmarks,
                canRemove = false
            )
        }

        private fun updateBookmark(
            marker: ImageView?,
            items: List<ChatInclude>,
            canRemove: Boolean
        ) {
            marker ?: return
            if (items.isEmpty()) {
                marker.visibility = View.GONE
                marker.setOnClickListener(null)
                return
            }

            marker.visibility = View.VISIBLE
            marker.setOnClickListener { anchor ->
                if (items.size == 1) {
                    if (canRemove) {
                        showCondensedBookmarkMenu(anchor, items.first())
                    } else {
                        listener?.onIncludeEdit(items.first().id)
                    }
                    return@setOnClickListener
                }

                val popup = PopupMenu(context, anchor)
                for ((index, include) in items.withIndex()) {
                    popup.menu.add(
                        0,
                        index,
                        index,
                        "${include.fileName} (${include.kind.key.uppercase(Locale.ROOT)})"
                    )
                }
                popup.setOnMenuItemClickListener { item ->
                    val include = items.getOrNull(item.itemId)
                        ?: return@setOnMenuItemClickListener false
                    if (canRemove) {
                        // Let the file picker close before opening its action
                        // menu on the same anchor.
                        anchor.post { showCondensedBookmarkMenu(anchor, include) }
                    } else {
                        listener?.onIncludeEdit(include.id)
                    }
                    true
                }
                popup.show()
            }
        }

        private fun showCondensedBookmarkMenu(anchor: View, include: ChatInclude) {
            val popup = PopupMenu(context, anchor)
            popup.menu.add(
                0,
                MENU_INCLUDE_EDIT,
                0,
                R.string.include_action_edit
            )
            popup.menu.add(
                0,
                MENU_INCLUDE_REMOVE,
                1,
                R.string.include_action_remove
            )
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_INCLUDE_EDIT -> {
                        listener?.onIncludeEdit(include.id)
                        true
                    }
                    MENU_INCLUDE_REMOVE -> {
                        listener?.onIncludeRemove(include.id)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun buildIncludeSummaryRows(includes: List<ChatInclude>) {
            val list = includeSummaryList ?: return
            list.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for (include in includes) {
                val row = inflater.inflate(R.layout.view_include_summary_item, list, false)
                row.findViewById<ImageView>(R.id.summary_item_icon)
                    ?.setImageResource(includeIcon(include.kind))
                row.findViewById<TextView>(R.id.summary_item_name)?.text = include.fileName
                row.findViewById<TextView>(R.id.summary_item_format)?.text =
                    include.kind.key.uppercase(Locale.ROOT)
                row.findViewById<TextView>(R.id.summary_item_weight)?.text = context.getString(
                    R.string.include_weight,
                    NumberFormat.getIntegerInstance().format(include.currentTokens())
                )
                row.findViewById<ImageButton>(R.id.summary_item_action)?.let { action ->
                    action.contentDescription =
                        context.getString(R.string.include_menu_desc, include.fileName)
                    action.setOnClickListener { showIncludeRowMenu(it, include) }
                }
                list.addView(row)
            }
        }

        private fun showIncludeRowMenu(anchor: View, include: ChatInclude) {
            val popup = PopupMenu(context, anchor)
            popup.menu.add(
                0,
                MENU_INCLUDE_REMOVE,
                0,
                R.string.include_action_remove
            )
            popup.menu.add(
                0,
                MENU_INCLUDE_CONDENSE,
                1,
                if (include.kind.isImage()) {
                    R.string.include_action_reduce
                } else {
                    R.string.include_action_condense
                }
            )
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_INCLUDE_REMOVE -> {
                        listener?.onIncludeRemove(include.id)
                        true
                    }
                    MENU_INCLUDE_CONDENSE -> {
                        listener?.onIncludeCondense(include.id)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun includeIcon(kind: IncludeKind): Int =
            if (kind.isImage()) R.drawable.ic_image else R.drawable.ic_file

        private fun collapsedCountRes(
            composition: IncludeHistoryPresentation.Composition
        ): Int = when (composition) {
            IncludeHistoryPresentation.Composition.DOCUMENTS -> R.string.include_collapsed_count
            IncludeHistoryPresentation.Composition.IMAGES -> R.string.include_collapsed_count_images
            IncludeHistoryPresentation.Composition.MIXED -> R.string.include_collapsed_count_files
        }

        private fun toggleDescRes(
            composition: IncludeHistoryPresentation.Composition,
            expanded: Boolean
        ): Int = when (composition) {
            IncludeHistoryPresentation.Composition.DOCUMENTS ->
                if (expanded) R.string.include_collapse_desc else R.string.include_expand_desc
            IncludeHistoryPresentation.Composition.IMAGES ->
                if (expanded) R.string.include_collapse_desc_images else R.string.include_expand_desc_images
            IncludeHistoryPresentation.Composition.MIXED ->
                if (expanded) R.string.include_collapse_desc_files else R.string.include_expand_desc_files
        }

        private fun updateStatusMarker(chatMessage: HashMap<String, Any>) {
            val marker = statusMarker ?: return
            val state = chatMessage[MessageCompletionState.KEY_STATE]?.toString()
            // A deliberate user Stop is not an error and shows no marker at all
            // (owner ruling, Aug 8 2026); a complete or still-streaming reply
            // never shows one either.
            if (chatMessage["isBot"] != true ||
                MessageCompletionState.isComplete(state) ||
                state == MessageCompletionState.STREAMING ||
                state == MessageCompletionState.STOPPED
            ) {
                marker.visibility = View.GONE
                return
            }
            // Each non-user termination names its own cause. START_FAILED and
            // UNKNOWN_END ride on FAILED, distinguished by the state detail; a
            // plain FAILED is a provider/network error; INTERRUPTED is an
            // app/lifecycle interruption.
            val detail = chatMessage[MessageCompletionState.KEY_STATE_DETAIL]?.toString()
            val label = when {
                state == MessageCompletionState.INTERRUPTED ->
                    context.getString(R.string.message_state_interrupted)
                state == MessageCompletionState.FAILED &&
                    detail == MessageCompletionState.DETAIL_START_FAILED ->
                    context.getString(R.string.message_state_start_failed)
                state == MessageCompletionState.FAILED &&
                    detail == MessageCompletionState.DETAIL_UNKNOWN_END ->
                    context.getString(R.string.message_state_unknown_end)
                state == MessageCompletionState.FAILED ->
                    context.getString(R.string.message_state_failed)
                else -> context.getString(R.string.message_state_incomplete)
            }
            val errorText = chatMessage[MessageCompletionState.KEY_ERROR_TEXT]?.toString().orEmpty()
            // The cause (a provider/network error, or the known internal reason
            // for an interruption) is shown below the label — failures are never
            // hidden (owner ruling, July 31 2026).
            marker.text = if (errorText.isNotBlank()) "$label\n$errorText" else label
            marker.visibility = View.VISIBLE
        }

        private fun updateRetryButton(chatMessage: HashMap<String, Any>, position: Int) {
            if (dataArray.isNotEmpty() && position == dataArray.size - 1 && chatMessage["isBot"] == true) {
                btnRetry.visibility = View.VISIBLE

                btnRetry.setOnClickListener {
                    if (!bulkActionMode) {
                        listener?.onRetryClick()
                    }
                }
            } else {
                btnRetry.visibility = View.GONE
            }
        }

        /**
         * Binds the one current message presentation. The four Appearance
         * controls alter decoration and identity placement without selecting a
         * different renderer or touching message behavior.
         */
        private fun updatePresentation(chatMessage: HashMap<String, Any>) {
            val isBot = chatMessage["isBot"] == true
            val showPortrait = preferences.getShowChatProfileImages()
            val showName = preferences.getShowChatNames()
            val showBubble = if (isBot) {
                preferences.getShowAiBubble()
            } else {
                preferences.getShowUserBubble()
            }

            ui.setBackgroundColor(0x00000000)

            username.text = if (isBot) {
                resolveAssistantLabel(chatMessage)
            } else {
                context.getString(R.string.chat_role_user)
            }
            ChatNameStyle.apply(
                username,
                context,
                if (isBot) {
                    companionNameStyle ?: ChatNameStyle.ai(preferences)
                } else {
                    ChatNameStyle.user(preferences)
                }
            )
            username.visibility = if (showName) View.VISIBLE else View.GONE

            icon.visibility = if (showPortrait) View.VISIBLE else View.GONE
            if (showPortrait) {
                if (isBot) displayAvatar() else displayUserAvatar()
            }

            updateIdentityGeometry(isBot, showPortrait, showName)
            updateBubbleDecoration(isBot, showBubble)
        }

        private fun updateBubbleDecoration(isBot: Boolean, showBubble: Boolean) {
            if (!showBubble) {
                bubbleBg.background = null
                message.setTextColor(resolveThemeColor(R.attr.appTextColor))
                return
            }

            val amoled = isDarkThemeEnabled() && preferences.getAmoledPitchBlack()
            val background = when {
                isBot && amoled -> R.drawable.bubble_out_dark
                isBot -> R.drawable.bubble_in
                amoled -> R.drawable.bubble_in_dark
                else -> R.drawable.bubble_out
            }
            bubbleBg.setBackgroundResource(background)
            message.setTextColor(
                if (amoled) {
                    ResourcesCompat.getColor(context.resources, R.color.white, null)
                } else {
                    resolveThemeColor(
                        if (isBot) {
                            com.google.android.material.R.attr.colorPrimary
                        } else {
                            com.google.android.material.R.attr.colorSurface
                        }
                    )
                }
            )
        }

        /**
         * Portrait-on placement uses the approved fixed offsets. Without a
         * portrait, the name's measured line height places its center on the
         * bubble's top edge, so every configured sp size remains centered.
         */
        private fun updateIdentityGeometry(
            isBot: Boolean,
            showPortrait: Boolean,
            showName: Boolean
        ) {
            val bubbleParams = bubbleBg.layoutParams as ViewGroup.MarginLayoutParams
            bubbleParams.topMargin = when {
                showPortrait -> dimensionPixelSize(R.dimen.chat_portrait_vertical_offset)
                showName -> username.lineHeight / 2
                else -> 0
            }
            bubbleBg.layoutParams = bubbleParams
            val contentPadding = dimensionPixelSize(R.dimen.chat_message_content_padding)
            bubbleBg.setPadding(
                contentPadding,
                contentPadding + if (showPortrait) {
                    dimensionPixelSize(R.dimen.chat_portrait_text_clearance)
                } else 0,
                contentPadding,
                contentPadding
            )
            message.setPadding(0, 0, 0, 0)

            val nameParams = username.layoutParams as ConstraintLayout.LayoutParams
            nameParams.startToStart = ConstraintLayout.LayoutParams.UNSET
            nameParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
            nameParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            nameParams.setMarginStart(0)
            nameParams.setMarginEnd(0)

            if (showPortrait) {
                val edge = dimensionPixelSize(R.dimen.chat_name_portrait_edge_inset)
                nameParams.topMargin = dimensionPixelSize(R.dimen.chat_name_portrait_top)
                if (isBot) {
                    nameParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    nameParams.setMarginStart(edge)
                } else {
                    nameParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    nameParams.setMarginEnd(edge)
                }
            } else {
                val edge = dimensionPixelSize(R.dimen.chat_message_speaker_inset) +
                    dimensionPixelSize(R.dimen.chat_name_bubble_edge_offset)
                nameParams.topMargin = 0
                if (isBot) {
                    nameParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    nameParams.setMarginStart(edge)
                } else {
                    nameParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    nameParams.setMarginEnd(edge)
                }
            }
            username.layoutParams = nameParams
        }

        private fun updateShareButton(chatMessage: HashMap<String, Any>) {
            if (chatMessage["isBot"] == true) {
                btnShare.visibility = View.VISIBLE
            } else {
                btnShare.visibility = View.GONE
            }
        }

        private fun updateSpeakButton(chatMessage: HashMap<String, Any>, position: Int) {
            // Re-read only makes sense for assistant text replies. Generated
            // images use `~file:` compatibility markers and are not speakable.
            val msg = chatMessage["message"].toString()
            val speakable = chatMessage["isBot"] == true &&
                    !msg.contains("~file:")
            if (speakable) {
                btnSpeak.visibility = View.VISIBLE
                if (position == speakingPosition) {
                    btnSpeak.setColorFilter(
                        ResourcesCompat.getColor(context.resources, R.color.mic_listening_green, context.theme)
                    )
                } else {
                    btnSpeak.clearColorFilter()
                }
                btnSpeak.setOnClickListener {
                    if (!bulkActionMode) {
                        val pos = bindingAdapterPosition
                        listener?.onSpeakClick(msg, if (pos != RecyclerView.NO_POSITION) pos else position)
                    }
                }
            } else {
                btnSpeak.visibility = View.GONE
            }
        }

        private fun dimensionPixelSize(resource: Int): Int =
            context.resources.getDimensionPixelSize(resource)

        private fun resolveThemeColor(attribute: Int): Int {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attribute, value, true)) {
                return ResourcesCompat.getColor(context.resources, R.color.text, context.theme)
            }
            return if (value.resourceId != 0) {
                ResourcesCompat.getColor(context.resources, value.resourceId, context.theme)
            } else {
                value.data
            }
        }

        /** The name shown on an assistant message: the companion name stamped
         *  on the message when it was created (locked, so a later companion
         *  switch never rewrites it), else the chat's current companion name
         *  for messages written before the stamp existed, else the generic
         *  "Assistant". Never the app name. */
        private fun resolveAssistantLabel(chatMessage: HashMap<String, Any>): String {
            val stamped = chatMessage[KEY_COMPANION_NAME]?.toString()
            if (!stamped.isNullOrBlank()) return stamped
            val live = companionLabel
            if (!live.isNullOrBlank()) return live
            return context.getString(R.string.chat_role_assistant)
        }

        /** Assistant-side precedence (profile-images-plan.md): Companion
         *  picture (shaped) -> existing per-chat avatar via the legacy
         *  resolver -> built-in glyph. When a Companion picture is present it
         *  is bound through [ProfileImageBinder] (which fully resets the view,
         *  so a recycled row can't keep another chat's picture or tint) and
         *  fills the slot without the glyph's tonal backing. Otherwise the
         *  existing legacy/built-in rendering runs unchanged, with the icon's
         *  original XML background explicitly restored so recycling from a
         *  photo row never leaves it blank. */
        private fun displayAvatar() {
            // While this chat has only failed replies (no completed reply yet),
            // the assistant avatar is the red error badge instead of the
            // Companion picture / glyph (owner ruling, July 31 2026). The first
            // completed reply clears it and the normal avatar returns.
            if (MessageCompletionState.chatShowsErrorAvatar(dataArray)) {
                displayErrorAvatar()
                return
            }
            val file = companionImageFile
            if (file != null && file.exists()) {
                ProfileImageBinder.bind(context, icon, file, companionImageShape) {
                    // Only reachable if the file vanished between resolve and load.
                    icon.background = iconInitialBackground
                    displayLegacyOrBuiltinAvatar()
                }
            } else {
                icon.background = iconInitialBackground
                icon.imageTintList = null
                displayLegacyOrBuiltinAvatar()
            }
        }

        /** Full-bleed red-disc-with-white-X badge shown when the chat has no
         *  successful reply yet. Self-contained, so the tonal backing and any
         *  tint are cleared and the view fully reset to avoid recycled-row
         *  bleed, matching the Companion-photo binder's discipline. */
        private fun displayErrorAvatar() {
            icon.background = null
            icon.imageTintList = null
            icon.clearColorFilter()
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.setImageResource(R.drawable.ic_avatar_error)
            icon.contentDescription = context.getString(R.string.chat_avatar_error_desc)
        }

        private fun displayLegacyOrBuiltinAvatar() {
            if (preferences.getAvatarType() == "builtin") {
                icon.setImageResource(StaticAvatarParser.parse(preferences.getAvatarId()))
                DrawableCompat.setTint(icon.getDrawable()!!, ContextCompat.getColor(context, R.color.accent_900))
            } else {
                val legacyAvatarFile = LegacyAvatarResolver.resolve(context.getExternalFilesDir("images"), preferences.getAvatarId())

                if (legacyAvatarFile != null) {
                    readAndDisplay(Uri.fromFile(legacyAvatarFile))
                }
            }
        }

        /** User-side avatar (owner ruling, July 21 2026): binds the picture
         *  ChatActivity already cascaded (active Roleplay Character -> active My
         *  Persona -> Default Personal Avatar), or the generic person icon when
         *  none is set. Fully resets the view each bind (background + tint) so a
         *  recycled user row can't keep a stale photo or accent tint. */
        private fun displayUserAvatar() {
            val file = userImageFile
            if (file != null && file.exists()) {
                ProfileImageBinder.bind(context, icon, file, userImageShape) {
                    // Only reachable if the file vanished between resolve and load.
                    icon.background = iconInitialBackground
                    icon.imageTintList = null
                    icon.setImageResource(R.drawable.ic_user)
                }
            } else {
                icon.background = iconInitialBackground
                icon.imageTintList = null
                icon.setImageResource(R.drawable.ic_user)
            }
        }

        private fun readAndDisplay(uri: Uri) {
            val bitmap = readFile(uri)

            if (bitmap != null) {
                icon.setImageBitmap(roundCorners(bitmap))
            }
        }

        private fun readFile(uri: Uri) : Bitmap? {
            return context.contentResolver?.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { _ ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        }

        private fun roundCorners(bitmap: Bitmap): Bitmap {
            val output = createBitmap(bitmap.width, bitmap.height)
            val canvas = Canvas(output)

            val paint = Paint().apply {
                isAntiAlias = true
                color = -0xbdbdbe
            }

            val rect = Rect(0, 0, bitmap.width, bitmap.height)
            val rectF = RectF(rect)

            canvas.drawRoundRect(rectF, 80f, 80f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, rect, rect, paint)

            return output
        }

        private fun switchBulkActionState(position: Int) {
            updatePresentation(dataArray[position])
            if (selectorProjection[position]["selected"].toString() == "true") {
                selectorProjection[position]["selected"] = "false"
                if (checkSelectionIsEmpty()) bulkActionMode = false
            } else {
                ui.setBackgroundColor(getSurface3Color(context))
                bulkActionMode = true
                selectorProjection[position]["selected"] = "true"
            }

            listener?.onBulkSelectionChanged(position, (selectorProjection[position]["selected"] ?: "false") == "true")
            listener?.onChangeBulkActionMode(bulkActionMode)
        }

        inner class BottomPaddingSpan(private val bottomPadding: Int) : LineHeightSpan {
            override fun chooseHeight(
                text: CharSequence?, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt?
            ) {
                fm?.let {
                    it.bottom += bottomPadding
                    it.descent += bottomPadding
                }
            }
        }

        @SuppressLint("SetTextI18n")
        private fun applyMarkdown(chatMessage: HashMap<String, Any>) {
            if (chatMessage["isBot"] == true) {
                val src = chatMessage["message"].toString()
                val markwon: Markwon = Markwon.builder(context)
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .usePlugin(TaskListPlugin.create(context))
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(MarkwonInlineParserPlugin.create())
                    .usePlugin(JLatexMathPlugin.create(message.textSize) { builder ->
                         builder.inlinesEnabled(true)
                    })
                    .usePlugin(object : AbstractMarkwonPlugin() {
                        override fun beforeSetText(
                            textView: TextView,
                            markdown: Spanned,
                        ) {
                            val spannableBuilder = SpannableStringBuilder(markdown)
                            val regex = Regex("\\|[^\\|]*\\|")
                            val matches = regex.findAll(spannableBuilder)

                            for (match in matches) {
                                val startIndex = match.range.first
                                val endIndex = match.range.last + 1
                                spannableBuilder.setSpan(
                                    BottomPaddingSpan(16),
                                    startIndex,
                                    endIndex,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                            textView.text = spannableBuilder
                        }
                    })
                    .build()

                val pre = parseLatex(trimLineByLine(src))
                markwon.setMarkdown(message, pre)
                addCodeBlockCopyControls(message)
            } else {
                message.text = chatMessage["message"].toString()
            }
            enableTextSelection()
        }

        /**
         * Adds a small right-aligned copy icon to the top of every fenced code
         * block, without changing the message renderer or splitting the reply
         * into separate views. Markwon has already drawn each block with a
         * [CodeBlockSpan] (the gray box); we reuse that span's range to insert a
         * one-line control at the block's start and reuse the SAME span
         * instance so the gray styling is untouched. The control is a single
         * placeholder character carrying three spans: an [ImageSpan] (the
         * app's existing content_copy glyph, R.drawable.ic_copy — the same
         * drawable and theme tint already used by the app's other copy/save/
         * delete icon buttons, so this reads as the same icon language, not a
         * one-off), a [ClickableSpan] limited to that one character so only the
         * icon — never the surrounding code — is tappable, and a [TtsSpan]
         * carrying the "Copy code block" label for screen readers. Copies only
         * that block; the code text stays fully selectable. Best-effort per
         * block: a failure on one never breaks the bind.
         */
        private fun addCodeBlockCopyControls(textView: TextView) {
            val rendered = textView.text as? Spanned ?: return
            if (rendered.getSpans(0, rendered.length, CodeBlockSpan::class.java).isEmpty()) return

            val icon = ContextCompat.getDrawable(textView.context, R.drawable.ic_copy) ?: return
            // Sized relative to the message text so it reads as an inline
            // glyph next to the code, not a full icon-button.
            val iconSizePx = (textView.textSize * 1.15f).toInt().coerceAtLeast(1)
            icon.setBounds(0, 0, iconSizePx, iconSizePx)

            val builder = SpannableStringBuilder(rendered)
            // Object Replacement Character: the single glyph the ImageSpan
            // draws over. Kept off-limits to text selection semantics by being
            // exactly one character, same as any other inline image span.
            val placeholder = "￼\n"
            // Highest-index block first so earlier blocks' offsets stay valid as
            // we insert.
            val blocks = builder.getSpans(0, builder.length, CodeBlockSpan::class.java)
                .sortedByDescending { builder.getSpanStart(it) }

            for (block in blocks) {
                try {
                    val start = builder.getSpanStart(block)
                    val end = builder.getSpanEnd(block)
                    if (start < 0 || end < 0 || start >= end) continue

                    val code = builder.subSequence(start, end).toString().trim('\n')
                    if (code.isEmpty()) continue

                    builder.insert(start, placeholder)

                    // Re-anchor the existing gray-box span so it also covers the
                    // control line — same instance, so styling is identical.
                    builder.removeSpan(block)
                    builder.setSpan(block, start, end + placeholder.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)

                    val controlEnd = start + 1 // the single placeholder character
                    try {
                        builder.setSpan(
                            AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                            start, start + placeholder.length, Spanned.SPAN_PARAGRAPH
                        )
                    } catch (_: Exception) { /* alignment is cosmetic; keep the control if it can't align */ }

                    builder.setSpan(
                        ImageSpan(icon, ImageSpan.ALIGN_BASELINE),
                        start, controlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    builder.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val clipboard = widget.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                            Toast.makeText(widget.context, R.string.label_code_copied, Toast.LENGTH_SHORT).show()
                        }

                        // No underline/tint override: the icon itself is the
                        // affordance, so ClickableSpan's default link styling
                        // (which would only apply to text) must not be drawn.
                        override fun updateDrawState(ds: TextPaint) { /* no-op: icon needs no link styling */ }
                    }, start, controlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                    builder.setSpan(
                        TtsSpan.TextBuilder(textView.context.getString(R.string.copy_code_block)).build(),
                        start, controlEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } catch (_: Exception) {
                    // A single malformed block must never crash the message bind.
                }
            }

            textView.text = builder
        }

        private fun enableTextSelection() {
            // Toggle off/on on every (re)bind: recycled views can carry stale
            // selection state into a new message, which crashes when the new
            // text is shorter than the old selection bounds.
            message.setTextIsSelectable(false)
            message.setTextIsSelectable(true)
            // setTextIsSelectable() replaces the movement method with one that
            // ignores ClickableSpans, which would leave markdown links dead.
            // LinkMovementMethod keeps links tappable while long-press
            // selection (handled by the TextView editor, not the movement
            // method) continues to work.
            message.movementMethod = LinkMovementMethod.getInstance()
        }

        private fun trimLineByLine(str: String) : String {
            val lines = str.split("\n")
            val sb = StringBuilder()
            for (line in lines) {
                sb.append(line.trim()).append("\n")
            }
            return sb.toString()
        }

        private fun parseLatex(markdown: String): String {
            val pattern = Regex("(`[^`]*`|```[\\s\\S]*?```)|\\\\\\[|\\\\\\]|\\\\\\(|\\\\\\)")
            // val pattern = Regex("(`[^`]*`|```[\\s\\S]*?```)|\\\\\\[|\\\\]|\\\\\\(|\\\\\\)")
            val sb = StringBuilder()
            var index = 0

            pattern.findAll(markdown).forEach { match ->
                if (match.groups[1] != null) { // Code block
                    sb.append(markdown.substring(index, match.range.first))
                    sb.append(match.value)
                    index = match.range.last + 1
                } else { // LaTeX \[, \], \(, or \) to be replaced
                    sb.append(markdown.substring(index, match.range.first))
                    when (match.value) {
                        """\[""" -> sb.append("""$$""").append("\n").append("""\[""")
                        """\]""" -> sb.append("""\]""").append("\n").append("""$$""")
                        """\(""" -> sb.append("""$$\(""")
                        """\)""" -> sb.append("""\)$$""")
                    }
                    index = match.range.last + 1
                }
            }
            sb.append(markdown.substring(index))

            val s = sb.toString()

            val openMatrixPattern = "\\begin{bmatrix}"
            val closeMatrixPattern = "\\end{bmatrix}"
            val openedMatricesCount = s.split(openMatrixPattern).size - 1
            val closedMatricesCount = s.split(closeMatrixPattern).size - 1

            val openMathPattern = "\\["
            val closeMathPattern = "\\]"
            val openedMathCount = s.split(openMathPattern).size - 1
            val closedMathCount = s.split(closeMathPattern).size - 1

            if (openedMatricesCount > closedMatricesCount) {
                sb.append("\\end{bmatrix}")
            }

            if (openedMathCount > closedMathCount) {
                sb.append("\n\\]")
            }

            return sb.toString()
        }

        /** Loads a provider-neutral generated-image bubble from its
         *  `~file:<hash>` compatibility slot
         *  in the shared images cache. User-attached images no longer flow
         *  through this path — they render as Includes summary rows under
         *  the user's own message. */
        private fun processGeneratedImageFile(chatMessage: HashMap<String, Any>) {
            val path = chatMessage["message"].toString().removePrefix("~file:")

            imageFrame.visibility = View.VISIBLE
            generatedImage.visibility = View.INVISIBLE
            generatedImageLoading.visibility = View.VISIBLE
            generatedImageError.visibility = View.GONE
            btnImagePrompt.visibility = View.VISIBLE
            btnImageDownload.visibility = View.GONE
            generatedImage.setOnClickListener(null)
            generatedImage.setOnLongClickListener(null)
            btnImagePrompt.setOnClickListener { showGeneratedImagePrompt(chatMessage) }

            try {
                // Rebuilt generated images store their REAL detected type
                // (image-generation-rebuild-plan.md §4.5), while legacy
                // markers are always .png files. Resolve the marker against
                // the supported types, falling back to the legacy name.
                val imagesDir = context.getExternalFilesDir("images")?.absolutePath
                val stored = org.teslasoft.assistant.imagegen.ImageFormat.entries
                    .map { format -> format to File("$imagesDir/$path.${format.fileExtension}") }
                    .firstOrNull { it.second.exists() }
                val mimeType = stored?.first?.mimeType ?: "image/png"
                val fullPath = stored?.second?.absolutePath ?: "$imagesDir/$path.png"
                boundGeneratedImagePath = fullPath

                val cached = generatedImageDataUrls[fullPath]
                if (cached == null) {
                    // A generated image may be tens of megabytes. Encode off
                    // the UI thread so the Loading Image state can actually
                    // animate instead of the row appearing frozen.
                    context.lifecycleScope.launch(Dispatchers.IO) {
                        val dataUrl = try {
                            val bytes = File(fullPath).readBytes()
                            "data:$mimeType;base64," +
                                Base64.getEncoder().encodeToString(bytes)
                        } catch (_: Exception) {
                            null
                        }
                        withContext(Dispatchers.Main) {
                            if (boundGeneratedImagePath != fullPath) return@withContext
                            if (dataUrl == null) {
                                showGeneratedImageLoadFailure()
                                return@withContext
                            }
                            generatedImageDataUrls[fullPath] = dataUrl
                            loadImage(dataUrl)
                            updateImageActions(dataUrl, mimeType)
                        }
                    }
                } else {
                    loadImage(cached)
                    updateImageActions(cached, mimeType)
                }
            } catch (_: Exception) {
                showGeneratedImageLoadFailure()
            }
        }

        private fun loadImage(url: String) {
            val requestOptions = RequestOptions().transform(CenterCrop(), RoundedCorners(convertDpToPixel(context).toInt()))
            Glide.with(context)
                .load(url.toUri())
                .apply(requestOptions)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (!isCurrentGeneratedImage(url)) return true
                        showGeneratedImageLoadFailure()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (!isCurrentGeneratedImage(url)) return true
                        generatedImageLoading.visibility = View.GONE
                        generatedImageError.visibility = View.GONE
                        generatedImage.visibility = View.VISIBLE
                        btnImageDownload.visibility = View.VISIBLE
                        return false
                    }
                })
                .into(generatedImage)

            btnShare.setOnClickListener {
                val imageType = url.substringAfter("data:image/").substringBefore(";")
                shareBase64Image(context, url, imageType)
            }
            btnShare.isEnabled = true
        }

        private fun isCurrentGeneratedImage(url: String): Boolean {
            val path = boundGeneratedImagePath ?: return false
            return generatedImageDataUrls[path] == url
        }

        private fun updateImageActions(url: String, mimeType: String) {
            btnImageDownload.setOnClickListener {
                if (!bulkActionMode) listener?.onGeneratedImageSaveClick(url, mimeType)
            }

            generatedImage.setOnClickListener {
                if (bulkActionMode) {
                    switchBulkActionState(bindingAdapterPosition)
                } else {
                    val sharedPreferences: SharedPreferences = context.getSharedPreferences("tmp", Context.MODE_PRIVATE)
                    sharedPreferences.edit {
                        putString("tmp", url)
                    }
                    val intent = Intent(context, ImageBrowserActivity::class.java).setAction(Intent.ACTION_VIEW)
                    intent.putExtra("tmp", "1")
                    context.startActivity(intent)
                }
            }

            generatedImage.setOnLongClickListener {
                switchBulkActionState(bindingAdapterPosition)
                return@setOnLongClickListener true
            }
        }

        private fun showGeneratedImageLoadFailure() {
            generatedImageLoading.visibility = View.GONE
            generatedImage.visibility = View.INVISIBLE
            generatedImageError.visibility = View.VISIBLE
            btnImageDownload.visibility = View.GONE
        }

        private fun showGeneratedImagePrompt(chatMessage: HashMap<String, Any>) {
            val prompt = GeneratedImageMetadata
                .fromJson(chatMessage[GeneratedImageMetadata.KEY]?.toString())
                ?.prompt
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.image_gen_prompt_unavailable)
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_generated_image_prompt, null, false)
            view.findViewById<TextView>(R.id.prompt_dialog_text).text = prompt
            val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
                .setView(view)
                .create()
            view.findViewById<ImageButton>(R.id.btn_prompt_close)
                .setOnClickListener { dialog.dismiss() }
            dialog.show()
        }

        private fun openEditDialog(chatMessage: HashMap<String, Any>, position: Int) {
            val dialog = EditMessageDialogFragment.newInstance(chatMessage["message"].toString(), position)
            dialog.setStateChangedListener(this@ChatAdapter)
            dialog.show(context.supportFragmentManager, "EditMessageDialogFragment")
        }

        fun resetView() {
            itemView.translationX = 0f
            itemView.alpha = 1f
        }
    }

    private fun convertDpToPixel(context: Context): Float {
        return 16f * context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
    }

    private fun getSurface3Color(context: Context): Int {
        return if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
            ResourcesCompat.getColor(context.resources, R.color.amoled_accent_100, null)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SurfaceColors.SURFACE_4.getColor(context)
            } else {
                context.getColor(R.color.accent_250)
            }
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (context.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    override fun onEdit(prompt: String, position: Int) {
        // Mirror onDelete's guard: a stale dialog position (the list changed
        // while the edit dialog was open) must not index past the array.
        if (position < 0 || position >= dataArray.size) return
        editMessage(position, prompt)
        notifyItemChanged(position)

        if (chatId !== "") {
            ChatPreferences.getChatPreferences().editMessage(context, chatId, position, prompt)
        }
    }

    override fun onDelete(position: Int) {
        if (position < 0 || position >= dataArray.size) return
        // §12 cleanup: note the generated-image file this message references
        // BEFORE removing it; once the deletion is persisted, the file goes
        // too unless another stored message still uses it.
        val deletedImageHash =
            org.teslasoft.assistant.imagegen.GeneratedImageMetadata
                .referencedFileHash(dataArray[position])
        deleteMessage(position)

        if (chatId !== "") {
            ChatPreferences.getChatPreferences().deleteMessage(context, chatId, position)
        }
        if (deletedImageHash != null) {
            org.teslasoft.assistant.imagegen.GeneratedImageFiles
                .deleteIfUnreferenced(context, listOf(deletedImageHash))
        }

        listener?.onMessageDeleted()
    }

    interface OnUpdateListener {
        fun onRetryClick()
        fun onMessageEdited()
        fun onMessageDeleted()
        fun onIncludeEdit(includeId: String)
        fun onIncludeRemove(includeId: String)
        fun onIncludeCondense(includeId: String)
        fun onBulkSelectionChanged(position: Int, selected: Boolean)
        fun onChangeBulkActionMode(mode: Boolean)
        fun onSpeakClick(message: String, position: Int)

        /** The inline image-confirmation card's Create (true) or Cancel
         *  (false) tap (image-generation-rebuild-plan.md §5). */
        fun onImageConfirmationDecision(approved: Boolean)

        /** The Creating Image row's Cancel tap (plan §5 progress
         *  experience). */
        fun onImageProgressCancel()

        /** The downward-arrow action below a generated image. The host owns
         *  the document launcher so Android can return the selected save URI. */
        fun onGeneratedImageSaveClick(dataUrl: String, mimeType: String)
    }
}
