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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.util.summarizer.SummarizerController
import com.google.android.material.elevation.SurfaceColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.spans.CodeBlockSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeHistoryPresentation
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.PersistentIncludeContext
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.reasoning.ReasoningIndicator
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.ui.activities.ImageBrowserActivity
import org.teslasoft.assistant.ui.chat.ChatMarkdownRenderer
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames
import org.teslasoft.assistant.ui.chat.ChatNameStyle
import org.teslasoft.assistant.ui.fragments.dialogs.EditMessageDialogFragment
import org.teslasoft.assistant.ui.util.IncludesPopupController
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
        private const val MENU_MESSAGE_EDIT = 104
        private const val MENU_MESSAGE_SHARE = 105

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
        const val KEY_COMPANION_NAME = ChatSpeakerNames.COMPANION_NAME_KEY

        // Durable per-message attribution for the compact metadata line and the
        // Message Details popup (chat-redesign-plan.md §4, §5). All stored as
        // strings so they round-trip through the chat's generic Gson HashMap the
        // same way every other persisted key does. Each is absent on messages
        // written before this feature and on messages where the value was never
        // reported; the display side omits whatever is absent rather than
        // inventing a value.
        //   KEY_MESSAGE_MODEL  — the model that produced this assistant reply,
        //                        frozen when the reply began so a later model
        //                        switch never relabels past turns.
        //   KEY_MESSAGE_TOKENS — provider-reported total tokens for that turn.
        //   KEY_MESSAGE_TIME   — epoch-millis timestamp of when the message was
        //                        created (both roles).
        const val KEY_MESSAGE_MODEL = "responseModel"
        const val KEY_MESSAGE_PROVIDER = "responseProvider"
        const val KEY_MESSAGE_TOKENS = "responseTokens"
        const val KEY_MESSAGE_TIME = "messageTime"

        // Durable accounting for completed API requests. responseTokens above
        // remains a legacy/display total-token field; it must never be read as
        // completion/output tokens. The JSON records preserve prompt,
        // completion and total independently, plus model/provider attribution,
        // source and the price/cost snapshot from completion time.
        const val KEY_TOKEN_USAGE_RECORDS =
            org.teslasoft.assistant.usage.TokenUsageAccounting.KEY_USAGE_RECORDS

        // Provider-supplied reasoning for this assistant reply (chat-redesign-
        // plan.md §7). Stored as strings like the attribution keys above so they
        // round-trip through the chat's generic Gson map and travel in a
        // regenerated turn's version snapshot. All absent unless the provider
        // actually returned reasoning for this reply.
        //   KEY_MESSAGE_REASONING        — the normalized reasoning text shown
        //                                  in the collapsed Thinking disclosure.
        //   KEY_MESSAGE_REASONING_SUMMARY — "true" when the provider supplied a
        //                                  summary rather than raw reasoning, so
        //                                  it is never presented as raw thought.
        //   KEY_MESSAGE_REASONING_TOKENS — provider-reported reasoning-token
        //                                  count, kept separate from answer
        //                                  tokens (§7.8).
        const val KEY_MESSAGE_REASONING = "reasoningText"
        const val KEY_MESSAGE_REASONING_FORMAT = "reasoningTextFormat"
        const val KEY_MESSAGE_REASONING_SUMMARY = "reasoningIsSummary"
        const val KEY_MESSAGE_REASONING_TOKENS = "reasoningTokens"

        // The per-message reasoning indicator state (owner design, Aug 2026),
        // stored as a stable ReasoningIndicator token ("low", "automatic",
        // "fixed", …). Frozen when the reply begins from the same capability and
        // effort the request used, so the action-bar glyph is what this turn was
        // actually generated with and never shifts on a later model switch.
        // Absent when the model is not known to reason (no glyph shown).
        const val KEY_MESSAGE_REASONING_LEVEL = "reasoningLevel"

        // Regenerated-response history for one assistant turn (owner spec, Aug
        // 16 2026). Each turn that has been regenerated keeps every version:
        //   KEY_VARIANTS          — JSON array of version snapshots, each a
        //                           {message, model, tokens, time, state, ...}
        //                           map, stored as strings so it round-trips
        //                           through the chat's generic Gson history map.
        //   KEY_CANONICAL_VARIANT — index of the version the conversation
        //                           actually continues from; the message's
        //                           top-level fields always mirror it, so
        //                           context, saving, and copy work on it
        //                           unchanged.
        //   KEY_DISPLAY_VARIANT   — index currently shown by the pager; pure
        //                           display state, defaults to the canonical
        //                           one. Browsing changes only this.
        // Absent entirely on a turn that was never regenerated (one version).
        const val KEY_VARIANTS = "variants"
        const val KEY_CANONICAL_VARIANT = "canonicalVariant"
        const val KEY_DISPLAY_VARIANT = "displayVariant"

        // The per-version fields copied between a message's top-level keys and a
        // stored variant snapshot. The visible reply text plus its own
        // provider metadata and completion state, so browsing to a version
        // restores exactly what that regeneration produced.
        private val VARIANT_FIELDS = listOf(
            "message",
            KEY_MESSAGE_MODEL,
            KEY_MESSAGE_PROVIDER,
            KEY_MESSAGE_TOKENS,
            KEY_TOKEN_USAGE_RECORDS,
            KEY_MESSAGE_TIME,
            KEY_MESSAGE_REASONING,
            KEY_MESSAGE_REASONING_FORMAT,
            KEY_MESSAGE_REASONING_SUMMARY,
            KEY_MESSAGE_REASONING_TOKENS,
            KEY_MESSAGE_REASONING_LEVEL,
            MessageCompletionState.KEY_STATE,
            MessageCompletionState.KEY_STATE_DETAIL,
            MessageCompletionState.KEY_ERROR_TEXT
        )

        /** Parse the stored version list, or an empty list when a turn has none. */
        fun parseVariants(json: String?): MutableList<HashMap<String, String>> {
            if (json.isNullOrBlank()) return mutableListOf()
            return try {
                val type = TypeToken.getParameterized(
                    ArrayList::class.java, HashMap::class.java
                ).type
                Gson().fromJson<ArrayList<HashMap<String, String>>>(json, type)
                    ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
        }

        fun variantsToJson(variants: List<Map<String, String>>): String =
            Gson().toJson(variants)

        /** Copy a message's current visible reply + metadata into a version
         *  snapshot. Absent fields are simply left out of the snapshot. */
        fun snapshotVariant(message: Map<String, Any>): HashMap<String, String> {
            val snapshot = HashMap<String, String>()
            for (field in VARIANT_FIELDS) {
                message[field]?.toString()?.let { snapshot[field] = it }
            }
            if (!snapshot.containsKey("message")) snapshot["message"] = ""
            return snapshot
        }

        /** Write a version snapshot back onto a message's top-level fields,
         *  removing any versioned field the snapshot does not carry so a
         *  version with (say) no token count never inherits another's. */
        fun applyVariant(message: HashMap<String, Any>, variant: Map<String, String>) {
            for (field in VARIANT_FIELDS) {
                val value = variant[field]
                if (value != null) message[field] = value else message.remove(field)
            }
        }

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

    /**
     * Which assistant replies currently have their Thinking disclosure expanded,
     * keyed by a content-stable key rather than position for the same recycling
     * reason as [expandedIncludeRows]. Default collapsed (§7.1): a key is absent
     * until the user taps to expand, and this set starts empty each time the
     * chat is opened, so reopening always shows Thinking collapsed.
     */
    private val expandedReasoning: MutableSet<String> = mutableSetOf()

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
        // circle). Photos and the error badge fill the slot and drop it; glyph
        // paths restore it so recycling cannot keep a nulled background.
        private val iconInitialBackground = icon.background
        private val iconInitialPaddingLeft = icon.paddingLeft
        private val iconInitialPaddingTop = icon.paddingTop
        private val iconInitialPaddingRight = icon.paddingRight
        private val iconInitialPaddingBottom = icon.paddingBottom
        private val iconInitialScaleType = icon.scaleType
        private val message: TextView = itemView.findViewById(R.id.message)
        private val username: TextView = itemView.findViewById(R.id.username)
        private val bubbleBg: ConstraintLayout = itemView.findViewById(R.id.bubble_bg)
        private val imageFrame: View = itemView.findViewById(R.id.image_frame)
        private val generatedImage: ImageView = itemView.findViewById(R.id.generated_image)
        private val generatedImageLoading: View = itemView.findViewById(R.id.generated_image_loading)
        private val generatedImageError: TextView = itemView.findViewById(R.id.generated_image_error)
        private val btnImagePrompt: MaterialButton = itemView.findViewById(R.id.btn_image_prompt)
        private val btnImageDownload: ImageButton = itemView.findViewById(R.id.btn_image_download)
        // Generated-image Share/Copy live on the image action row (owner ask,
        // Aug 14 2026); they delegate to the action-bar buttons so behavior is
        // identical. Present on both message layouts.
        private val btnImageShare: ImageButton = itemView.findViewById(R.id.btn_image_share)
        private val btnImageCopy: ImageButton = itemView.findViewById(R.id.btn_image_copy)
        // The row container holding the bubble; generated images now keep the
        // same speaker-side geometry as ordinary assistant responses.
        private val messageRow: LinearLayout? = itemView.findViewById(R.id.linearLayout3)
        // Legacy in-bubble name slot. Generated images now use the same row-level
        // speaker name as ordinary responses, so this stays hidden.
        private val bubbleName: TextView? = itemView.findViewById(R.id.bubble_name)
        private var boundGeneratedImagePath: String? = null
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btn_copy)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val btnMore: ImageButton? = itemView.findViewById(R.id.btn_more)
        private val btnRetry: ImageButton = itemView.findViewById(R.id.btn_retry)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        private val btnSpeak: ImageButton = itemView.findViewById(R.id.btn_speak)
        // Far-left Message Action on both layouts; opens the anchored Message
        // Details popup (chat-redesign-plan.md §5). Always present.
        private val btnDetails: ImageButton = itemView.findViewById(R.id.btn_details)
        // User-only derived persistent-Includes action. It is absent from the
        // assistant layout and is reset on every bind to survive recycling.
        private val btnPersistentIncludes: ImageButton? =
            itemView.findViewById(R.id.btn_persistent_includes)
        // Compact model/token line under the identity. Present only on the
        // assistant layout (model/tokens are AI-side), so nullable.
        private val messageMeta: TextView? = itemView.findViewById(R.id.message_meta)
        // Provider-supplied reasoning disclosure (§7.1). Present only on the
        // assistant layout, so nullable.
        private val reasoningContainer: LinearLayout? = itemView.findViewById(R.id.reasoning_container)
        private val reasoningHeader: LinearLayout? = itemView.findViewById(R.id.reasoning_header)
        private val reasoningLabel: TextView? = itemView.findViewById(R.id.reasoning_label)
        private val reasoningText: TextView? = itemView.findViewById(R.id.reasoning_text)
        private val reasoningChevron: ImageView? = itemView.findViewById(R.id.reasoning_chevron)
        // Per-message reasoning indicator glyph in the action bar, right of the
        // info button (owner design, Aug 2026). Assistant layout only, so
        // nullable; informational, never clickable.
        private val reasoningIndicator: ImageView? = itemView.findViewById(R.id.reasoning_indicator)
        // Regenerated-response version pager, far right of the assistant action
        // bar. Present only on the assistant layout, so nullable.
        private val versionNav: View? = itemView.findViewById(R.id.version_nav)
        private val btnVersionPrev: ImageButton? = itemView.findViewById(R.id.btn_version_prev)
        private val versionCount: TextView? = itemView.findViewById(R.id.version_count)
        private val btnVersionNext: ImageButton? = itemView.findViewById(R.id.btn_version_next)
        // To the right of the pager: check_circle when the shown version is the
        // canonical one (a no-op placeholder), or resume when a non-canonical
        // version is shown, tapping which makes it the canonical response.
        private val btnVersionPromote: ImageButton? = itemView.findViewById(R.id.btn_version_promote)
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
        // Present only on the user layout (attachments are user-side only), so
        // nullable like includeSummary. Lets the tray swap sides of Message
        // Actions depending on whether the message also carries text.
        private val messageActionsRow: View? = itemView.findViewById(R.id.message_actions_row)

        @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
        open fun bind(chatMessage: HashMap<String, Any>, position: Int) {

            // The version the pager is currently showing. For a turn with one
            // version (or none regenerated) this is just chatMessage itself; when
            // the user has paged to an older version it is that version's content
            // and metadata laid over the message, without disturbing the stored
            // canonical version used for context and saving.
            val display = displayVariantMap(chatMessage)

            val isGeneratedImage = display["message"].toString().startsWith("~file:")
            btnEdit.visibility = if (isGeneratedImage) View.GONE else if (chatMessage["isBot"] == true) View.GONE else View.VISIBLE
            btnMore?.visibility = if (chatMessage["isBot"] == true) View.VISIBLE else View.GONE
            if (isGeneratedImage) btnShare.isEnabled = false

            val hasAttachments = updateIncludeSummary(chatMessage, position)
            updatePersistentIncludeAction(chatMessage, position)
            // Attachment-only: no text, so nothing sits between identity and
            // the tray, and the tray moves ahead of Message Actions instead of
            // trailing them (chat-redesign-plan.md §6.1).
            val attachmentOnly = hasAttachments && chatMessage["message"].toString().isBlank()
            positionAttachmentTray(attachmentOnly)
            updatePresentation(chatMessage)
            updateRetryButton(chatMessage, position)
            updateShareButton(chatMessage)
            updateSpeakButton(display, position)
            updateStatusMarker(display)
            updateMessageMeta(display, isGeneratedImage)
            updateReasoning(display, position)
            updateReasoningIndicator(display, isGeneratedImage)
            updateVersionNav(chatMessage, position)

            btnDetails.setOnClickListener { anchor ->
                if (!bulkActionMode) showMessageDetailsPopup(anchor, display)
            }

            btnMore?.setOnClickListener { anchor ->
                if (!bulkActionMode && chatMessage["isBot"] == true) {
                    showMessageActionsPopup(anchor, chatMessage, position, display)
                }
            }

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
                val messageText = display["message"].toString()
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
            // Copy defaults to visible on the action bar; the image branch hides
            // it there because a generated image shows Copy on its own row.
            btnCopy.visibility = View.VISIBLE
            // The image-row Share/Copy forward to the action-bar buttons so the
            // share and clipboard behavior is defined in exactly one place.
            btnImageShare.setOnClickListener { btnShare.callOnClick() }
            btnImageCopy.setOnClickListener { btnCopy.callOnClick() }

            if (isGeneratedImage) {
                if (chatMessage["isBot"] == true) {
                    message.visibility = View.GONE
                }
                // Share and Copy move onto the image's own action row, so hide
                // the action-bar copies. Share visibility for a bot message was
                // already set VISIBLE by updateShareButton above.
                btnShare.visibility = View.GONE
                btnCopy.visibility = View.GONE
                processGeneratedImageFile(display)
            } else {
                boundGeneratedImagePath = null
                Glide.with(context).clear(generatedImage)
                (debugContext as FragmentActivity).runOnUiThread {
                    applyMarkdown(display)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    debugContext.runOnUiThread {
                        applyMarkdown(display)
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
                btnImageShare.visibility = View.GONE
                btnImageCopy.visibility = View.GONE

                btnShare.setOnClickListener {
                    sharePlainText(context, display["message"].toString())
                }
                btnShare.isEnabled = true

                // An attachment-only message has no text to show; leaving the
                // (empty) text view VISIBLE would reserve a blank line above
                // the tray (chat-redesign-plan.md §6.1).
                message.visibility = if (attachmentOnly) View.GONE else View.VISIBLE
            }
        }

        /**
         * Swaps which side of Message Actions the attachment tray sits on.
         *
         * With text: text -> Message Actions -> attachment tray (default XML
         * order). Attachment-only: identity -> attachment tray -> Message
         * Actions (chat-redesign-plan.md §6.1). No-op on the assistant
         * layout, which has neither view.
         */
        private fun positionAttachmentTray(attachmentOnly: Boolean) {
            val tray = includeSummary ?: return
            val actionsRow = messageActionsRow ?: return

            val trayParams = tray.layoutParams as ConstraintLayout.LayoutParams
            val actionsParams = actionsRow.layoutParams as ConstraintLayout.LayoutParams

            if (attachmentOnly) {
                trayParams.topToBottom = R.id.include_bookmarks
                actionsParams.topToBottom = R.id.include_summary
            } else {
                trayParams.topToBottom = R.id.message_actions_row
                actionsParams.topToBottom = R.id.image_frame
            }

            tray.layoutParams = trayParams
            actionsRow.layoutParams = actionsParams
        }

        /**
         * Shows the inherited-context paperclip only on a later user row.
         * The action holds Include ids, never copied Include records; opening
         * the popup resolves those ids against the current original-message
         * records in [dataArray].
         */
        private fun updatePersistentIncludeAction(
            chatMessage: HashMap<String, Any>,
            position: Int
        ) {
            val action = btnPersistentIncludes ?: return
            action.visibility = View.GONE
            action.setOnClickListener(null)

            if (chatMessage["isBot"] == true) return

            val inheritedIds = PersistentIncludeContext
                .earlierForUserMessage(dataArray, position)
                .map { it.id }
            if (inheritedIds.isEmpty()) return

            action.visibility = View.VISIBLE
            action.contentDescription = context.getString(R.string.message_includes_action)
            action.setOnClickListener { anchor ->
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val currentIds = PersistentIncludeContext
                    .earlierForUserMessage(dataArray, currentPosition)
                    .map { it.id }
                IncludesPopupController.show(
                    anchor = anchor,
                    includeIds = currentIds,
                    resolveCurrent = ::canonicalIncludesForIds,
                    callbacks = object : IncludesPopupController.Callbacks {
                        override fun onIncludeEdit(includeId: String) {
                            listener?.onIncludeEdit(includeId)
                        }

                        override fun onIncludeRemove(includeId: String) {
                            listener?.onIncludeRemove(includeId)
                        }

                        override fun onIncludeCondense(includeId: String) {
                            listener?.onIncludeCondense(includeId)
                        }
                    }
                )
            }
        }

        /** Reads current Include state from the message that canonically owns
         * each id. This is intentionally a read-only resolver for the popup. */
        private fun canonicalIncludesForIds(ids: Set<String>): List<ChatInclude> {
            if (ids.isEmpty()) return emptyList()
            val result = ArrayList<ChatInclude>()
            val seen = HashSet<String>()
            for (message in dataArray) {
                if (message["isBot"] == true) continue
                for (include in ChatInclude.listFromJson(
                    message[ChatActivity.INCLUDES_KEY]?.toString()
                )) {
                    if (include.id in ids && seen.add(include.id)) result.add(include)
                }
            }
            return result
        }

        /**
         * The compact metadata line beneath the identity (chat-redesign-plan.md
         * §4.3). Present only on the assistant layout. Shows the producing model
         * when Model Names is on, the provider token total when Token Usage is
         * on, joined by a centered dot when both are present and both enabled
         * and the two fit on one line. When a long model name would otherwise
         * push the token count past the row's edge, the token count drops to
         * its own line directly beneath the model name instead (owner spec,
         * Aug 23 2026), still starting at the same edge as the model name.
         * Anything not enabled or not stored on this turn is simply omitted; the
         * whole line is GONE when nothing remains. Never invents a value.
         */
        private fun updateMessageMeta(chatMessage: HashMap<String, Any>, isGeneratedImage: Boolean) {
            val meta = messageMeta ?: return

            // Generated images are produced by the image service, not a chat
            // model, and carry no chat token usage; their bubble owns its own
            // presentation, so the compact line never appears there.
            if (chatMessage["isBot"] != true || isGeneratedImage) {
                meta.visibility = View.GONE
                meta.text = ""
                return
            }

            val modelPart = if (preferences.getShowModelNames()) {
                chatMessage[KEY_MESSAGE_MODEL]?.toString()?.takeIf { it.isNotBlank() }
            } else null
            val tokenPart = if (preferences.getShowTokenUsage()) tokenCountLabel(chatMessage) else null

            val text = when {
                modelPart != null && tokenPart != null -> {
                    val combined = "$modelPart  ·  $tokenPart"
                    val nameBesidePortrait = preferences.getShowChatProfileImages() &&
                        preferences.getShowChatNames()
                    val available = availableMetaWidthPx(nameBesidePortrait)
                    if (available > 0 && meta.paint.measureText(combined) <= available) {
                        combined
                    } else {
                        "$modelPart\n$tokenPart"
                    }
                }
                modelPart != null -> modelPart
                tokenPart != null -> tokenPart
                else -> null
            }

            if (text == null) {
                meta.visibility = View.GONE
                meta.text = ""
            } else {
                meta.text = text
                meta.visibility = View.VISIBLE
            }
        }

        /** The provider token total for this turn, worded per the approved
         *  format (e.g. "1,234 tokens"), or null when this turn stored no
         *  usable count so the caller omits it. */
        private fun tokenCountLabel(chatMessage: HashMap<String, Any>): String? {
            val raw = chatMessage[KEY_MESSAGE_TOKENS]?.toString()?.takeIf { it.isNotBlank() }
                ?: return null
            val count = raw.toLongOrNull() ?: return null
            val grouped = NumberFormat.getIntegerInstance(Locale.getDefault()).format(count)
            return context.getString(R.string.chat_token_count, grouped)
        }

        /** The provider-reported reasoning-token count for this turn, kept
         *  separate from the answer-token total (§7.8), or null when this reply
         *  stored no reasoning-token count. */
        private fun reasoningTokenCountLabel(chatMessage: HashMap<String, Any>): String? {
            val raw = chatMessage[KEY_MESSAGE_REASONING_TOKENS]?.toString()?.takeIf { it.isNotBlank() }
                ?: return null
            val count = raw.toLongOrNull() ?: return null
            val grouped = NumberFormat.getIntegerInstance(Locale.getDefault()).format(count)
            return context.getString(R.string.chat_reasoning_token_count, grouped)
        }

        /**
         * The anchored Message Details popup (chat-redesign-plan.md §5). Always
         * reachable regardless of the compact-display toggles. Shows only the
         * fields stored for this message — date & time, and, for an assistant
         * reply, the producing model and provider token total. Fields never
         * recorded (older messages, or a turn the provider gave no usage for)
         * are omitted; a message with nothing stored shows the empty line. Text
         * is selectable. Outside tap or Back dismisses.
         */
        private fun showMessageDetailsPopup(anchor: View, chatMessage: HashMap<String, Any>) {
            val content = LayoutInflater.from(context)
                .inflate(R.layout.view_details_popup, null)

            val isBot = chatMessage["isBot"] == true
            var anyShown = false

            val dateTime = chatMessage[KEY_MESSAGE_TIME]?.toString()?.toLongOrNull()?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            }
            anyShown = bindDetailValue(content, R.id.details_value_datetime, dateTime) || anyShown

            val model = if (isBot) {
                chatMessage[KEY_MESSAGE_MODEL]?.toString()?.takeIf { it.isNotBlank() }
            } else null
            anyShown = bindDetailValue(content, R.id.details_value_model, model) || anyShown

            val tokens = if (isBot) tokenCountLabel(chatMessage) else null
            anyShown = bindDetailValue(content, R.id.details_value_tokens, tokens) || anyShown

            val reasoningTokens = if (isBot) reasoningTokenCountLabel(chatMessage) else null
            anyShown = bindDetailValue(content, R.id.details_value_reasoning_tokens, reasoningTokens) || anyShown

            content.findViewById<TextView>(R.id.details_empty).visibility =
                if (anyShown) View.GONE else View.VISIBLE

            val popup = PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.isOutsideTouchable = true
            // A non-null background is what makes outside-tap dismissal work.
            popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popup.elevation = anchor.resources.displayMetrics.density * 8f

            // Anchor above the action so the popup rises from the action area and
            // may overlap the chat content above it, per the plan.
            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val yOffset = -(anchor.height + content.measuredHeight)
            popup.showAsDropDown(anchor, 0, yOffset, Gravity.START)
        }

        /** Fills one detail value and shows it, or hides it when the value is
         *  absent. Returns whether the value is shown. */
        private fun bindDetailValue(content: View, valueId: Int, value: String?): Boolean {
            val view = content.findViewById<TextView>(valueId)
            return if (value.isNullOrBlank()) {
                view.visibility = View.GONE
                false
            } else {
                view.text = value
                view.visibility = View.VISIBLE
                true
            }
        }

        /**
         * The version the pager is currently showing, laid over the message.
         * Returns the message untouched for a turn with fewer than two versions
         * or when the displayed version is the canonical one, so the common path
         * allocates nothing. Otherwise a shallow copy carries the chosen
         * version's reply and metadata while the original keeps the canonical
         * fields the conversation and saving use.
         */
        private fun displayVariantMap(chatMessage: HashMap<String, Any>): HashMap<String, Any> {
            val variants = parseVariants(chatMessage[KEY_VARIANTS]?.toString())
            if (variants.size < 2) return chatMessage
            val canonical = canonicalIndexOf(chatMessage, variants.size)
            val displayIndex = displayIndexOf(chatMessage, variants.size, canonical)
            if (displayIndex == canonical) return chatMessage
            val copy = HashMap<String, Any>(chatMessage)
            applyVariant(copy, variants[displayIndex])
            return copy
        }

        private fun canonicalIndexOf(chatMessage: Map<String, Any>, count: Int): Int =
            (chatMessage[KEY_CANONICAL_VARIANT]?.toString()?.toIntOrNull() ?: (count - 1))
                .coerceIn(0, count - 1)

        private fun displayIndexOf(chatMessage: Map<String, Any>, count: Int, canonical: Int): Int =
            (chatMessage[KEY_DISPLAY_VARIANT]?.toString()?.toIntOrNull() ?: canonical)
                .coerceIn(0, count - 1)

        /**
         * The regenerated-response pager at the far right of the assistant
         * action bar (owner spec, Aug 16 2026). Hidden unless the turn has two
         * or more stored versions. Two or three versions show only the
         * previous/next chevrons with the count; four or more also make the
         * count tappable, opening the bare number picker. The first version
         * hides the left chevron and the last hides the right, since there is
         * nowhere to page. Paging is display-only — it never truncates history
         * or changes the canonical version.
         */
        private fun updateVersionNav(chatMessage: HashMap<String, Any>, position: Int) {
            val nav = versionNav ?: return
            val prev = btnVersionPrev ?: return
            val next = btnVersionNext ?: return
            val count = versionCount ?: return

            val variants = parseVariants(chatMessage[KEY_VARIANTS]?.toString())
            if (chatMessage["isBot"] != true || variants.size < 2) {
                nav.visibility = View.GONE
                return
            }

            val total = variants.size
            val canonical = canonicalIndexOf(chatMessage, total)
            val current = displayIndexOf(chatMessage, total, canonical)

            nav.visibility = View.VISIBLE
            count.text = context.getString(R.string.version_nav_count, current + 1, total)

            prev.visibility = if (current > 0) View.VISIBLE else View.INVISIBLE
            next.visibility = if (current < total - 1) View.VISIBLE else View.INVISIBLE
            prev.setOnClickListener {
                if (!bulkActionMode && current > 0) showVersion(chatMessage, position, current - 1)
            }
            next.setOnClickListener {
                if (!bulkActionMode && current < total - 1) showVersion(chatMessage, position, current + 1)
            }

            if (total >= 4) {
                count.isClickable = true
                count.setOnClickListener {
                    if (!bulkActionMode) showVersionPicker(count, chatMessage, position, total, current)
                }
            } else {
                count.isClickable = false
                count.setOnClickListener(null)
            }

            // The promote control: check_circle (placeholder, no-op) when the
            // shown version is already canonical; resume when it is not, tapping
            // which makes the shown version the canonical response.
            btnVersionPromote?.let { promote ->
                if (current == canonical) {
                    promote.setImageResource(R.drawable.ic_check_circle)
                    promote.contentDescription = context.getString(R.string.version_current_desc)
                    promote.setOnClickListener(null)
                    promote.isClickable = false
                } else {
                    promote.setImageResource(R.drawable.ic_resume)
                    promote.contentDescription = context.getString(R.string.version_make_current_desc)
                    promote.isClickable = true
                    promote.setOnClickListener {
                        val pos = bindingAdapterPosition
                        if (!bulkActionMode) {
                            keepingActionBarInPlace {
                                listener?.onMakeVersionCurrent(
                                    if (pos != RecyclerView.NO_POSITION) pos else position
                                )
                            }
                        }
                    }
                }
            }
        }

        /** Page the turn to another stored version. Display-only: it records the
         *  new pager position and rebinds; it never changes the canonical
         *  version or later history. */
        private fun showVersion(chatMessage: HashMap<String, Any>, position: Int, index: Int) {
            keepingActionBarInPlace {
                chatMessage[KEY_DISPLAY_VARIANT] = index.toString()
                notifyItemChanged(position)
                listener?.onResponseVersionChanged()
            }
        }

        /**
         * Runs [action] while keeping the version-pager action bar (@id/version_nav)
         * at the screen position it was at before [action] runs, regardless of how
         * much the message content above it grows or shrinks (owner ask, Aug 16
         * 2026). Without this, paging to a longer or shorter version shifts the
         * whole row and can push the action bar off the bottom of the screen.
         *
         * Reads the row's position again on the next predraw pass — after the
         * rebound item has been measured and laid out but before it is drawn —
         * and scrolls the RecyclerView by the difference so the row lands back
         * where it was. RecyclerView.scrollBy clamps to the available content, so
         * near the top of the list this is a best-effort correction rather than a
         * guarantee.
         */
        private fun keepingActionBarInPlace(action: () -> Unit) {
            val nav = versionNav
            val recyclerView = itemView.parent as? RecyclerView
            if (nav == null || recyclerView == null) {
                action()
                return
            }

            val before = IntArray(2)
            nav.getLocationOnScreen(before)
            val beforeY = before[1]

            action()

            recyclerView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    recyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                    val after = IntArray(2)
                    nav.getLocationOnScreen(after)
                    val delta = after[1] - beforeY
                    if (delta != 0) recyclerView.scrollBy(0, delta)
                    return true
                }
            })
        }

        /**
         * The bare version picker shown for four-plus versions: a borderless
         * list of the OTHER version numbers (the current one stays in the count
         * itself, like the app's dropdowns keep the selection in the anchor).
         * No box and no chevron. It drops down from the count, flipping upward
         * only when it would be clipped by the docked message-input bar at the
         * bottom of the screen.
         */
        private fun showVersionPicker(
            anchor: View,
            chatMessage: HashMap<String, Any>,
            position: Int,
            total: Int,
            current: Int
        ) {
            val density = anchor.resources.displayMetrics.density
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_dialog_rounded)
                val pad = (density * 6f).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val popup = PopupWindow(
                list,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popup.elevation = density * 8f

            val foreground = versionCount?.currentTextColor ?: resolveThemeColor(R.attr.appTextColor)
            val rowBg = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, rowBg, true
            )
            for (i in 0 until total) {
                if (i == current) continue
                val row = TextView(context).apply {
                    text = (i + 1).toString()
                    setTextColor(foreground)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minWidth = (density * 44f).toInt()
                    val vpad = (density * 8f).toInt()
                    val hpad = (density * 12f).toInt()
                    setPadding(hpad, vpad, hpad, vpad)
                    if (rowBg.resourceId != 0) setBackgroundResource(rowBg.resourceId)
                    setOnClickListener {
                        popup.dismiss()
                        showVersion(chatMessage, position, i)
                    }
                }
                list.addView(row)
            }

            list.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            // Measure available space against the top of the docked message-input
            // bar, not the raw screen height, so the popup flips up before it
            // would cover the typing area rather than merely before the screen
            // edge (owner ask, Aug 16 2026).
            val inputBarTop = context.findViewById<View?>(R.id.keyboard_input)?.let {
                val inputLocation = IntArray(2)
                it.getLocationOnScreen(inputLocation)
                inputLocation[1]
            } ?: anchor.resources.displayMetrics.heightPixels
            val spaceBelow = inputBarTop - (location[1] + anchor.height)
            if (list.measuredHeight <= spaceBelow) {
                popup.showAsDropDown(anchor, 0, 0, Gravity.START)
            } else {
                popup.showAsDropDown(
                    anchor, 0, -(anchor.height + list.measuredHeight), Gravity.START
                )
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
         * Renders sent documents in the tray positioned by [positionAttachmentTray].
         * One to three rows remain visible; only four or more collapse behind
         * the count.
         *
         * Every branch sets visibility explicitly: these rows are recycled, so
         * an early return would let one message's open accordion reappear on
         * an unrelated message further down the conversation.
         *
         * @return true when the tray is showing at least one full attachment
         * record (used by [bind] to decide whether the message is attachment-only).
         */
        /**
         * The collapsed Thinking disclosure for an assistant reply that carries
         * provider-supplied reasoning (chat-redesign-plan.md §7.1). Reasoning is
         * its own row between the identity/metadata line and the answer; tapping
         * only expands/collapses the already-received text and never regenerates
         * or alters the answer. Absent reasoning collapses the row to nothing.
         * Chat Settings → Show Thinking (owner spec, Aug 23 2026) gates only
         * this display: turning it off hides the row on every reply, current
         * and past alike, but never touches the stored reasoning text — the
         * app still requests and stores it exactly as before, and turning the
         * setting back on shows it again. Every branch sets visibility
         * explicitly because rows are recycled.
         */
        private fun updateReasoning(chatMessage: HashMap<String, Any>, position: Int) {
            val container = reasoningContainer ?: return
            val text = chatMessage[KEY_MESSAGE_REASONING]?.toString()?.takeIf { it.isNotBlank() }
            if (chatMessage["isBot"] != true || text == null || !preferences.getShowThinking()) {
                container.visibility = View.GONE
                reasoningText?.text = ""
                reasoningHeader?.setOnClickListener(null)
                return
            }

            container.visibility = View.VISIBLE
            // Content-stable key (like the include tray) so the open/closed state
            // stays with THIS reply's reasoning across recycling, not a position.
            val key = "R" + (chatMessage[KEY_MESSAGE_TIME]?.toString() ?: "") + "" + text.hashCode()
            val expanded = expandedReasoning.contains(key)

            reasoningText?.text = text
            reasoningText?.visibility = if (expanded) View.VISIBLE else View.GONE
            // Chevron: up (rotated 180 from the down-pointing asset) when
            // collapsed, down (unrotated) when expanded.
            reasoningChevron?.rotation = if (expanded) 0f else 180f

            // One blank line above Thinking when the model/token line above it
            // is showing anything (model name, token count, or both), so
            // Thinking never sits flush against it. No gap when that line is
            // empty — Thinking then starts immediately, same as before (owner
            // spec, Aug 23 2026). Stays flush with the bubble's own left edge
            // either way; it never follows the model/token line's portrait
            // indent.
            val metaVisible = messageMeta?.visibility == View.VISIBLE
            val containerParams = container.layoutParams as? ViewGroup.MarginLayoutParams
            if (containerParams != null) {
                val desiredTopMargin = if (metaVisible) (messageMeta?.lineHeight ?: 0) else 0
                if (containerParams.topMargin != desiredTopMargin) {
                    containerParams.topMargin = desiredTopMargin
                    container.layoutParams = containerParams
                }
            }

            reasoningHeader?.setOnClickListener {
                if (!expandedReasoning.add(key)) expandedReasoning.remove(key)
                notifyItemChanged(position)
            }
        }

        /**
         * The per-message reasoning indicator glyph (owner design, Aug 2026):
         * a single Material Wi-Fi-strength icon right of the info button showing
         * the effort this reply was generated with. Shown only for an assistant
         * reply whose persisted state names a known reasoning indicator, and
         * never on a generated-image message. Absent state hides the view, so a
         * non-reasoning reply (and every recycled user row) shows nothing here.
         */
        private fun updateReasoningIndicator(chatMessage: HashMap<String, Any>, isGeneratedImage: Boolean) {
            val view = reasoningIndicator ?: return
            // The glyph records what a reply was generated WITH, so it appears
            // only once the reply actually produced output (answer text or
            // reasoning). A request that dies before generating anything leaves
            // no glyph, even though its placeholder was stamped at stream start.
            // A stopped/partial reply that did produce text still shows it.
            val generated = chatMessage["message"]?.toString()?.isNotBlank() == true ||
                chatMessage[KEY_MESSAGE_REASONING]?.toString()?.isNotBlank() == true
            // Chat Settings → Thinking Indicator hides the glyph everywhere when
            // off; the stored per-message level is untouched, so turning it back
            // on restores every glyph exactly.
            val indicator = if (isGeneratedImage || chatMessage["isBot"] != true ||
                !generated || !preferences.getShowThinkingIndicator()) {
                null
            } else {
                ReasoningIndicator.fromToken(chatMessage[KEY_MESSAGE_REASONING_LEVEL]?.toString())
            }
            if (indicator == null) {
                view.visibility = View.GONE
                view.setImageDrawable(null)
                view.contentDescription = null
                return
            }
            val icon: Int
            val desc: Int
            when (indicator) {
                ReasoningIndicator.OFF -> { icon = R.drawable.ic_signal_wifi_off; desc = R.string.reasoning_indicator_off_desc }
                ReasoningIndicator.MINIMAL -> { icon = R.drawable.ic_signal_wifi_0_bar; desc = R.string.reasoning_indicator_minimal_desc }
                ReasoningIndicator.LOW -> { icon = R.drawable.ic_network_wifi_1_bar; desc = R.string.reasoning_indicator_low_desc }
                ReasoningIndicator.MEDIUM -> { icon = R.drawable.ic_network_wifi_2_bar; desc = R.string.reasoning_indicator_medium_desc }
                ReasoningIndicator.HIGH -> { icon = R.drawable.ic_network_wifi_3_bar; desc = R.string.reasoning_indicator_high_desc }
                ReasoningIndicator.XHIGH -> { icon = R.drawable.ic_signal_wifi_4_bar; desc = R.string.reasoning_indicator_xhigh_desc }
                // Max shares the full-strength glyph with Extra High for now; the
                // Wi-Fi metaphor has no state above 4 bars, and the two never
                // appear together in one model's ladder. A distinct Max icon is
                // an open owner decision. Accessibility text still says "Max".
                ReasoningIndicator.MAX -> { icon = R.drawable.ic_signal_wifi_4_bar; desc = R.string.reasoning_indicator_max_desc }
                ReasoningIndicator.AUTOMATIC -> { icon = R.drawable.ic_network_check; desc = R.string.reasoning_indicator_automatic_desc }
                ReasoningIndicator.FIXED -> { icon = R.drawable.ic_network_wifi_2_locked; desc = R.string.reasoning_indicator_fixed_desc }
            }
            view.setImageResource(icon)
            view.contentDescription = context.getString(desc)
            view.visibility = View.VISIBLE
        }

        private fun updateIncludeSummary(chatMessage: HashMap<String, Any>, position: Int): Boolean {
            val summary = includeSummary ?: return false
            val includes = ChatInclude.listFromJson(
                chatMessage[ChatActivity.INCLUDES_KEY]?.toString()
            )

            if (chatMessage["isBot"] == true || includes.isEmpty()) {
                summary.visibility = View.GONE
                condensedBookmark?.visibility = View.GONE
                artifactBookmark?.visibility = View.GONE
                includeSummaryList?.removeAllViews()
                return false
            }

            val groups = IncludeHistoryPresentation.group(includes)
            updateIncludeBookmarks(groups)
            val fullIncludes = groups.fullRecords
            if (fullIncludes.isEmpty()) {
                summary.visibility = View.GONE
                includeSummaryList?.removeAllViews()
                return false
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

            return true
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
            // Regenerate is offered on every assistant text reply now, not only
            // the last one, so earlier turns can be regenerated too (owner spec,
            // Aug 16 2026). Generated-image replies keep their original
            // last-only affordance — they are produced by the image flow, not by
            // text regeneration, and their presentation is out of scope.
            val isBot = chatMessage["isBot"] == true
            val isImage = chatMessage["message"].toString().startsWith("~file:")
            val isLast = position == dataArray.size - 1
            if (isBot && (!isImage || isLast)) {
                btnRetry.visibility = View.VISIBLE
                btnRetry.setOnClickListener {
                    if (!bulkActionMode) {
                        val pos = bindingAdapterPosition
                        listener?.onRegenerate(if (pos != RecyclerView.NO_POSITION) pos else position)
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
            val isImage = chatMessage["message"].toString().startsWith("~file:")
            val showPortrait = preferences.getShowChatProfileImages()
            val showName = preferences.getShowChatNames()
            val showBubble = if (isBot) {
                preferences.getShowAiBubble()
            } else {
                preferences.getShowUserBubble()
            }

            ui.setBackgroundColor(0x00000000)

            val nameText = if (isBot) {
                resolveAssistantLabel(chatMessage)
            } else {
                ChatSpeakerNames.userName(context, chatMessage)
            }
            val nameStyle = if (isBot) {
                companionNameStyle ?: ChatNameStyle.ai(preferences)
            } else {
                ChatNameStyle.user(preferences)
            }

            // Generated images use the same row-level speaker label as ordinary
            // replies. The image changes the bubble content, not identity placement.
            username.text = nameText
            ChatNameStyle.apply(username, context, nameStyle)
            username.visibility = if (showName) View.VISIBLE else View.GONE
            bubbleName?.visibility = View.GONE

            icon.visibility = if (showPortrait) View.VISIBLE else View.GONE
            if (showPortrait) {
                if (isBot) displayAvatar() else displayUserAvatar()
            }

            updateIdentityGeometry(isBot, showPortrait, showName, showBubble, isImage)
            updateBubbleDecoration(isBot, showBubble)
        }

        private fun updateBubbleDecoration(isBot: Boolean, showBubble: Boolean) {
            if (!showBubble) {
                bubbleBg.background = null
                val foreground = resolveThemeColor(R.attr.appTextColor)
                message.setTextColor(foreground)
                username.setTextColor(foreground)
                bubbleName?.setTextColor(foreground)
                applyMetaForeground(foreground)
                tintActionIcons(foreground)
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
            val foreground = if (amoled) {
                ResourcesCompat.getColor(context.resources, R.color.white, null)
            } else {
                resolveThemeColor(
                    if (isBot) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorSurface
                    }
                )
            }
            message.setTextColor(foreground)
            username.setTextColor(foreground)
            bubbleName?.setTextColor(foreground)
            applyMetaForeground(foreground)
            tintActionIcons(foreground)
        }

        /** The compact metadata line follows the bubble's text color so it
         *  contrasts on any theme, held at reduced opacity so it reads as
         *  subordinate to the reply without a hardcoded muted color. */
        private fun applyMetaForeground(foreground: Int) {
            messageMeta?.setTextColor(foreground)
            messageMeta?.alpha = 0.7f
        }

        /**
         * The message-action glyphs are authored tinted colorPrimary — the same
         * color as the USER bubble — so on a user message they were invisible
         * against their own bubble. Recolor them to the bubble's foreground so
         * they contrast on both the AI and user bubbles. btnSpeak is left to
         * updateSpeakButton, which owns its speaking-state color.
         */
        private fun tintActionIcons(foreground: Int) {
            btnDetails.setColorFilter(foreground)
            btnPersistentIncludes?.setColorFilter(foreground)
            btnShare.setColorFilter(foreground)
            btnRetry.setColorFilter(foreground)
            btnCopy.setColorFilter(foreground)
            btnEdit.setColorFilter(foreground)
            btnMore?.setColorFilter(foreground)
            // The version pager sits on the same bar and follows the same
            // bubble foreground so its glyphs and count read on any theme.
            btnVersionPrev?.setColorFilter(foreground)
            btnVersionNext?.setColorFilter(foreground)
            versionCount?.setTextColor(foreground)
            btnVersionPromote?.setColorFilter(foreground)
            // The reasoning indicator rides the same bar and follows the same
            // bubble foreground so its glyph reads on any theme.
            reasoningIndicator?.setColorFilter(foreground)
            // The Thinking chevron was only ever tinted by its authored XML
            // ?attr/colorPrimary, the same bug class described above — against
            // some bubble/theme combinations that left it the same color as
            // its background, effectively invisible. It now follows the same
            // live bubble foreground as every other glyph here, and the
            // Thinking label is kept the same color as its chevron (owner
            // spec, Aug 23 2026) so both move together across themes.
            reasoningChevron?.setColorFilter(foreground)
            reasoningLabel?.setTextColor(foreground)
        }

        /**
         * Places the portrait, speaker name, and bubble.
         *
         * Generated images use the same horizontal assistant-message geometry
         * as ordinary replies. Their bubble fills that normal available width so
         * the image can use the space, while the image itself remains centered.
         * With a portrait the image bubble still drops below the portrait's full
         * reach, preserving the clean no-overlap behavior.
         *
         * For text, the bubble hugs the speaker side. With a portrait the name
         * sits beside it; with just a painted bubble the row name aligns with the
         * message content (not the bubble's border). The no-bubble presentation
         * keeps its original placement untouched.
         */
        private fun updateIdentityGeometry(
            isBot: Boolean,
            showPortrait: Boolean,
            showName: Boolean,
            showBubble: Boolean,
            isImage: Boolean
        ) {
            val contentPadding = dimensionPixelSize(R.dimen.chat_message_content_padding)

            val bubbleParams = bubbleBg.layoutParams as ViewGroup.MarginLayoutParams
            bubbleParams.width = if (isImage) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            bubbleParams.topMargin = when {
                isImage && showPortrait -> dimensionPixelSize(R.dimen.chat_portrait_size) +
                    dimensionPixelSize(R.dimen.chat_portrait_top_offset)
                showPortrait -> dimensionPixelSize(R.dimen.chat_portrait_vertical_offset)
                showName && showBubble -> 0
                // No-bubble name keeps its original centered-on-the-line placement.
                showName -> username.lineHeight / 2
                else -> 0
            }
            bubbleBg.layoutParams = bubbleParams

            // The bubble remains on the same speaker side as a normal response.
            // Only the generated image inside it is centered.
            messageRow?.gravity = if (isBot) Gravity.START else Gravity.END

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
            } else if (showBubble) {
                // Align the name with the bubble's content (one content-padding
                // in from the bubble edge and down from its top), so it reads as
                // a label above the message instead of sitting on the border.
                val edge = dimensionPixelSize(R.dimen.chat_message_speaker_inset) + contentPadding
                nameParams.topMargin = contentPadding
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

            // The compact metadata line lives inside the bubble and normally
            // starts at the bubble's own content edge, which already lines up
            // with the name when there is no portrait (both are inset by the
            // same speaker margin). With a portrait, the name is pushed right
            // to clear it while the bubble is not, so the metadata line needs
            // its own extra start margin to keep lining up under the name
            // (owner spec, Aug 23 2026). Only applies when the name is
            // actually shown beside the portrait; otherwise the metadata line
            // keeps its default bubble-edge position.
            messageMeta?.let { meta ->
                val metaParams = meta.layoutParams as ConstraintLayout.LayoutParams
                metaParams.marginStart = if (showPortrait && showName) metaPortraitExtraStartPx() else 0
                meta.layoutParams = metaParams
            }

            // When there is no portrait, reserve the same name-to-body gap as a
            // normal response before the image begins. With a portrait the whole
            // image bubble is already pushed below the portrait and adjacent name.
            val imageParams = imageFrame.layoutParams as ViewGroup.MarginLayoutParams
            val imageTopMargin = if (isImage && showName && !showPortrait && showBubble) {
                username.lineHeight + dimensionPixelSize(R.dimen.chat_name_body_gap)
            } else {
                0
            }
            if (imageParams.topMargin != imageTopMargin) {
                imageParams.topMargin = imageTopMargin
                imageFrame.layoutParams = imageParams
            }

            // The (GONE) message anchors the image frame; clear any stale
            // text-clearance margin left from a recycled text row.
            if (isImage) {
                val msgParams = message.layoutParams as ViewGroup.MarginLayoutParams
                if (msgParams.topMargin != 0) {
                    msgParams.topMargin = 0
                    message.layoutParams = msgParams
                }
            }
        }

        private fun showMessageActionsPopup(
            anchor: View,
            chatMessage: HashMap<String, Any>,
            position: Int,
            display: HashMap<String, Any>
        ) {
            val popup = PopupMenu(context, anchor, Gravity.END)
            val isGeneratedImage = display["message"].toString().startsWith("~file:")
            if (!isGeneratedImage) {
                popup.menu.add(0, MENU_MESSAGE_EDIT, 0, R.string.btn_msg_edit)
            }
            popup.menu.add(0, MENU_MESSAGE_SHARE, 1, R.string.message_share_action)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_MESSAGE_EDIT -> {
                        openEditDialog(chatMessage, position)
                        true
                    }
                    MENU_MESSAGE_SHARE -> {
                        btnShare.callOnClick()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun updateShareButton(chatMessage: HashMap<String, Any>) {
            // Share is exposed from the AI message overflow menu. Keep the
            // existing hidden callback view so text/image sharing behavior
            // remains exactly the same without leaving an icon in the bar.
            btnShare.visibility = View.GONE
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

        /** How far past the bubble's own content edge the metadata line must
         *  shift to line up with the name when the name sits beside a
         *  portrait, in pixels. Never negative. */
        private fun metaPortraitExtraStartPx(): Int {
            val nameStart = dimensionPixelSize(R.dimen.chat_name_portrait_edge_inset)
            val bubbleContentStart = dimensionPixelSize(R.dimen.chat_message_speaker_inset) +
                dimensionPixelSize(R.dimen.chat_message_content_padding)
            return (nameStart - bubbleContentStart).coerceAtLeast(0)
        }

        /** The pixel width available to the metadata line before it would run
         *  past the row's right edge, used to decide whether the model name
         *  and token count still fit on one line. */
        private fun availableMetaWidthPx(nameBesidePortrait: Boolean): Int {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val insets = dimensionPixelSize(R.dimen.chat_message_speaker_inset) +
                dimensionPixelSize(R.dimen.chat_message_ai_right_inset) +
                dimensionPixelSize(R.dimen.chat_message_content_padding) * 2
            val portraitExtra = if (nameBesidePortrait) metaPortraitExtraStartPx() else 0
            return screenWidth - insets - portraitExtra
        }

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
            return ChatSpeakerNames.companionName(context, chatMessage, companionLabel)
        }

        /** Assistant-side precedence (profile-images-plan.md): Companion
         *  picture (shaped) -> existing per-chat avatar via the legacy
         *  resolver -> built-in glyph. When a Companion picture is present it
         *  is bound through [ProfileImageBinder] (which fully resets the view,
         *  so a recycled row can't keep another chat's picture or tint) and
         *  fills the slot without the glyph's tonal backing. Legacy photos use
         *  the same full slot; built-in glyphs restore the original XML
         *  padding and background after row recycling. */
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
                useFullPortraitSlot()
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
            useFullPortraitSlot()
            icon.background = null
            icon.imageTintList = null
            icon.clearColorFilter()
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.setImageResource(R.drawable.ic_avatar_error)
            icon.contentDescription = context.getString(R.string.chat_avatar_error_desc)
        }

        private fun displayLegacyOrBuiltinAvatar() {
            if (preferences.getAvatarType() == "builtin") {
                restorePortraitGlyphSlot()
                icon.background = iconInitialBackground
                icon.setImageResource(StaticAvatarParser.parse(preferences.getAvatarId()))
                DrawableCompat.setTint(icon.getDrawable()!!, ContextCompat.getColor(context, R.color.accent_900))
            } else {
                val legacyAvatarFile = LegacyAvatarResolver.resolve(context.getExternalFilesDir("images"), preferences.getAvatarId())

                if (legacyAvatarFile != null) {
                    useFullPortraitSlot()
                    icon.background = null
                    icon.imageTintList = null
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
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
                useFullPortraitSlot()
                ProfileImageBinder.bind(context, icon, file, userImageShape) {
                    // Only reachable if the file vanished between resolve and load.
                    restorePortraitGlyphSlot()
                    icon.background = iconInitialBackground
                    icon.imageTintList = null
                    icon.setImageResource(R.drawable.ic_user)
                }
            } else {
                restorePortraitGlyphSlot()
                icon.background = iconInitialBackground
                icon.imageTintList = null
                icon.setImageResource(R.drawable.ic_user)
            }
        }

        /** Photos consume the complete tuned 96dp slot. The XML's 6dp padding
         *  belongs only to fallback glyphs; applying it to photos shrinks them
         *  to 84dp and visually cancels most of the tuned -8dp edge offset. */
        private fun useFullPortraitSlot() {
            icon.setPadding(0, 0, 0, 0)
        }

        /** Restore the XML presentation before displaying a glyph in a row
         *  recycled from a full-bleed photo or error avatar. */
        private fun restorePortraitGlyphSlot() {
            icon.setPadding(
                iconInitialPaddingLeft,
                iconInitialPaddingTop,
                iconInitialPaddingRight,
                iconInitialPaddingBottom
            )
            icon.scaleType = iconInitialScaleType
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
                val markwon = ChatMarkdownRenderer.builder(context, message.textSize)
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

                val pre = ChatMarkdownRenderer.prepare(src)
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
            btnImageShare.visibility = View.GONE
            btnImageCopy.visibility = View.GONE
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
            val requestOptions = RequestOptions().transform(FitCenter(), RoundedCorners(convertDpToPixel(context).toInt()))
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
                        btnImageShare.visibility = View.VISIBLE
                        btnImageCopy.visibility = View.VISIBLE
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
            btnImageShare.visibility = View.GONE
            btnImageCopy.visibility = View.GONE
        }

        /**
         * The generated-image prompt box. The Image Prompt stays read-only;
         * the Summarized Image Text is the short reminder the model receives
         * each turn and becomes editable only through Edit. Saving an edit
         * persists it on the message's record and is what the model receives
         * from then on (owner request, Aug 16 2026).
         */
        private fun showGeneratedImagePrompt(chatMessage: HashMap<String, Any>) {
            val metadata = GeneratedImageMetadata
                .fromJson(chatMessage[GeneratedImageMetadata.KEY]?.toString())
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_generated_image_prompt, null, false)

            val promptText = view.findViewById<TextView>(R.id.prompt_dialog_text)
            val summaryTitle = view.findViewById<TextView>(R.id.summary_dialog_title)
            val summaryText = view.findViewById<TextView>(R.id.summary_dialog_text)
            val summaryField = view.findViewById<TextInputEditText>(R.id.summary_dialog_field)
            val btnEdit = view.findViewById<MaterialButton>(R.id.btn_prompt_summary_edit)
            val btnSave = view.findViewById<MaterialButton>(R.id.btn_prompt_summary_save)
            val btnCancel = view.findViewById<MaterialButton>(R.id.btn_prompt_summary_cancel)

            promptText.text = metadata?.prompt?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.image_gen_prompt_unavailable)

            val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
                .setView(view)
                .create()
            view.findViewById<ImageButton>(R.id.btn_prompt_close)
                .setOnClickListener { dialog.dismiss() }

            // A legacy image (no stored record) has nothing to summarize or
            // persist to: show only the read-only prompt.
            if (metadata == null) {
                summaryTitle.visibility = View.GONE
                summaryText.visibility = View.GONE
                summaryField.visibility = View.GONE
                btnEdit.visibility = View.GONE
                dialog.show()
                return
            }

            val effective = metadata.effectiveSummary()
            val summaryConfigured = SummarizerController.isConfigured(context)

            fun renderViewMode() {
                summaryText.text = when {
                    effective != null -> effective
                    summaryConfigured -> context.getString(R.string.image_gen_summary_summarizing)
                    else -> context.getString(R.string.image_gen_summary_no_summarizer)
                }
                summaryText.visibility = View.VISIBLE
                summaryField.visibility = View.GONE
                btnEdit.visibility = View.VISIBLE
                btnSave.visibility = View.GONE
                btnCancel.visibility = View.GONE
            }

            btnEdit.setOnClickListener {
                // Only the summarizer's version is editable; the field starts
                // from any existing summary, or empty so the user writes one.
                summaryField.setText(effective ?: "")
                summaryText.visibility = View.GONE
                summaryField.visibility = View.VISIBLE
                summaryField.requestFocus()
                btnEdit.visibility = View.GONE
                btnSave.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
            }

            btnCancel.setOnClickListener { renderViewMode() }

            btnSave.setOnClickListener {
                val edited = summaryField.text?.toString()?.trim().orEmpty().ifBlank { null }
                listener?.onImageSummaryEdited(bindingAdapterPosition, edited)
                summaryText.text = edited
                    ?: metadata.imageSummary?.takeIf { it.isNotBlank() }
                    ?: if (summaryConfigured) {
                        context.getString(R.string.image_gen_summary_summarizing)
                    } else {
                        context.getString(R.string.image_gen_summary_no_summarizer)
                    }
                summaryText.visibility = View.VISIBLE
                summaryField.visibility = View.GONE
                btnEdit.visibility = View.VISIBLE
                btnSave.visibility = View.GONE
                btnCancel.visibility = View.GONE
            }

            renderViewMode()
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
        // Editing only changes this message's stored text (owner spec, Aug 16
        // 2026). It never truncates later messages — that is what Regenerate
        // and Make Current do. Attachments on the message are kept.
        editMessage(position, prompt)
        // If this turn carries regenerated versions, keep the canonical
        // version's stored text in step with the edit so paging back to it
        // shows the edited text, not the pre-edit one.
        val message = dataArray[position]
        val variants = parseVariants(message[KEY_VARIANTS]?.toString())
        if (variants.size >= 2) {
            val canonical = (message[KEY_CANONICAL_VARIANT]?.toString()?.toIntOrNull()
                ?: (variants.size - 1)).coerceIn(0, variants.size - 1)
            variants[canonical]["message"] = prompt
            message[KEY_VARIANTS] = variantsToJson(variants)
        }
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

        /** Regenerate the assistant turn at [position]. On the latest turn this
         *  just adds a version; on an earlier turn the host confirms the branch
         *  and truncates the messages after it first (owner spec, Aug 16 2026). */
        fun onRegenerate(position: Int)

        /** Make the response version currently displayed on the turn at
         *  [position] the canonical one. On the latest turn this switches the
         *  version silently; on an earlier turn the host confirms ("Make Current
         *  Response?") and truncates everything after that turn. */
        fun onMakeVersionCurrent(position: Int)

        fun onMessageEdited()
        fun onMessageDeleted()
        fun onIncludeEdit(includeId: String)
        fun onIncludeRemove(includeId: String)
        fun onIncludeCondense(includeId: String)
        fun onBulkSelectionChanged(position: Int, selected: Boolean)
        fun onChangeBulkActionMode(mode: Boolean)
        fun onSpeakClick(message: String, position: Int)

        /** The user paged to a different stored response version. Display-only:
         *  the host just persists the new pager position; it never truncates
         *  history or changes which version the conversation continues from. */
        fun onResponseVersionChanged()

        /** The inline image-confirmation card's Create (true) or Cancel
         *  (false) tap (image-generation-rebuild-plan.md §5). */
        fun onImageConfirmationDecision(approved: Boolean)

        /** The Creating Image row's Cancel tap (plan §5 progress
         *  experience). */
        fun onImageProgressCancel()

        /** The downward-arrow action below a generated image. The host owns
         *  the document launcher so Android can return the selected save URI. */
        fun onGeneratedImageSaveClick(dataUrl: String, mimeType: String)

        /** The user saved an edit to a generated image's summary in the prompt
         *  box. [editedSummary] is null when the field was cleared. The host
         *  stores it on the message's record and sends it to the model instead
         *  of the full prompt from then on. */
        fun onImageSummaryEdited(position: Int, editedSummary: String?)
    }
}
