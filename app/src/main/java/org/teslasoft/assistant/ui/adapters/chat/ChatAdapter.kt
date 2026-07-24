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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.LineHeightSpan
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.elevation.SurfaceColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.text.NumberFormat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.ui.activities.ChatActivity
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.activities.ImageBrowserActivity
import org.teslasoft.assistant.ui.fragments.dialogs.EditMessageDialogFragment
import org.teslasoft.assistant.util.LegacyAvatarResolver
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.StaticAvatarParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.Collections
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.content.edit
import org.teslasoft.assistant.ui.fragments.dialogs.ReportAIContentBottomSheet
import org.teslasoft.assistant.util.ShareUtil.Companion.shareBase64Image
import org.teslasoft.assistant.util.ShareUtil.Companion.sharePlainText

class ChatAdapter(private val dataArray: ArrayList<HashMap<String, Any>>, private val selectorProjection: ArrayList<HashMap<String, Any>>, private val context: FragmentActivity, private val preferences: Preferences, private val isAssistant: Boolean, private var chatId: String) : RecyclerView.Adapter<ChatAdapter.ViewHolder>(), EditMessageDialogFragment.StateChangesListener {

    private var dalleImageStringList = ArrayList<String>(Collections.nCopies(itemCount + 1, ""))
    private var imageStringList = ArrayList<String>(Collections.nCopies(itemCount + 1, ""))
    private var listener: OnUpdateListener? = null
    private var bulkActionMode = false

    // Assistant-side picture, already cascaded by ChatActivity off the main
    // thread (the active Companion's own picture, else the Default AI Avatar).
    // Row binding never touches storage. Null only when neither exists, in
    // which case the row falls through to the built-in glyph.
    // [companionImageShape] is the current Default Shape to render it with.
    private var companionImageFile: File? = null
    private var companionImageShape: String = "flower"

    // User-side picture (owner ruling, July 21 2026), already cascaded by
    // ChatActivity: the active Roleplay Character's picture, else the active My
    // Persona's, else the Default Personal Avatar. Null only when none of those
    // is set, in which case the user bubble shows the generic person icon.
    private var userImageFile: File? = null
    private var userImageShape: String = "flower"

    /** Called by ChatActivity with the already-resolved assistant picture (or
     *  null) plus the current Default Shape. Rebinds visible rows so the
     *  assistant avatar reflects the new state. */
    fun setCompanionAvatar(file: File?, shape: String) {
        companionImageFile = file
        companionImageShape = shape
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
        private const val TYPE_CLASSIC = 2
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
    private val expandedIncludeRows: MutableSet<Int> = mutableSetOf()

    override fun getItemViewType(position: Int): Int {
        return if (preferences.getLayout() == "bubbles" || isAssistant) {
            if (dataArray[position]["isBot"] == true) {
                TYPE_BOT
            } else {
                TYPE_USER
            }
        } else {
            TYPE_CLASSIC
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(dataArray[position], position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = when (viewType) {
            TYPE_BOT -> R.layout.view_assistant_bot_message
            TYPE_USER -> R.layout.view_assistant_user_message
            else -> R.layout.view_message
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view, context)
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
        // Open "Includes" records are tracked by position, and a deletion
        // shifts every position after it — so drop the tracking rather than
        // let an accordion reopen against the wrong message.
        expandedIncludeRows.clear()
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
        private val bubbleBg: ConstraintLayout? = itemView.findViewById(R.id.bubble_bg)
        private val dalleImage: ImageView = itemView.findViewById(R.id.dalle_image)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btn_copy)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btn_edit)
        private val btnRetry: ImageButton = itemView.findViewById(R.id.btn_retry)
        private val btnReport: ImageButton = itemView.findViewById(R.id.btn_report)
        private val btnShare: ImageButton = itemView.findViewById(R.id.btn_share)
        private val btnSpeak: ImageButton = itemView.findViewById(R.id.btn_speak)
        // Present only on the assistant/classic layouts (the user bubble has no
        // completion marker); nullable so the shared ViewHolder is safe on every
        // layout it inflates.
        private val statusMarker: TextView? = itemView.findViewById(R.id.status_marker)
        // The "Includes" record of what this message carried. Absent from the
        // assistant bubble (attachments are user-side only), so nullable.
        private val includeSummary: LinearLayout? = itemView.findViewById(R.id.include_summary)
        private val includeSummaryHeader: LinearLayout? = itemView.findViewById(R.id.include_summary_header)
        private val includeSummaryIcon: ImageView? = itemView.findViewById(R.id.include_summary_icon)
        private val includeSummaryList: LinearLayout? = itemView.findViewById(R.id.include_summary_list)

        @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
        open fun bind(chatMessage: HashMap<String, Any>, position: Int) {

            updateIncludeSummary(chatMessage, position)
            updateUI(chatMessage)
            updateRetryButton(chatMessage, position)
            updateReportButton(chatMessage)
            updateShareButton(chatMessage)
            updateSpeakButton(chatMessage, position)
            updateStatusMarker(chatMessage)

            if (selectorProjection[position]["selected"].toString() == "true") {
                ui.setBackgroundColor(getSurface3Color(context))
            } else {
                updateUI(chatMessage)
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

            btnReport.setOnClickListener {
                val reportBottomSheet = ReportAIContentBottomSheet.newInstance(chatMessage["message"].toString(), chatId, true)
                reportBottomSheet.show(context.supportFragmentManager, "ReportAIContentBottomSheet")
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
                val clip = ClipData.newPlainText("response", chatMessage["message"].toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.label_copy), Toast.LENGTH_SHORT).show()
            }

            if (chatMessage["message"].toString().contains("data:image")) {
                dalleImage.visibility = View.VISIBLE
                message.visibility = View.GONE
                btnCopy.visibility = View.GONE

                loadImage(chatMessage["message"].toString())
                updateImageClickListener(chatMessage["message"].toString())
            } else if (chatMessage["message"].toString().contains("~file:")) {
                if (chatMessage["isBot"] == true) {
                    message.visibility = View.GONE
                }
                processFile(chatMessage, position, "png", dalleImageStringList, true)
            } else {
                (debugContext as FragmentActivity).runOnUiThread {
                    applyMarkdown(chatMessage)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    debugContext.runOnUiThread {
                        applyMarkdown(chatMessage)
                    }
                }, 100)

                if (chatMessage["isBot"] == false && chatMessage["image"] !== null) {
                    dalleImage.visibility = View.VISIBLE

                    processFile(chatMessage, position, chatMessage["imageType"].toString(), imageStringList, false)
                } else {
                    dalleImage.visibility = View.GONE

                    btnShare.setOnClickListener {
                        sharePlainText(context, chatMessage["message"].toString())
                    }
                }

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
         * Renders this message's record of what it carried: a small
         * "Includes" box under the user's name that opens like an accordion.
         *
         * It is a record of the PAST — the weights shown are what each item
         * cost when this turn was sent, so the transcript does not silently
         * rewrite itself when the user later condenses or removes something.
         * The live strip above the message box is what shows the present.
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
                includeSummaryList?.removeAllViews()
                return
            }

            summary.visibility = View.VISIBLE

            // Once everything attached here has been reduced to a bookmark,
            // the box wears the artifact marker instead of a file glyph.
            val allArtifacts = includes.all { it.form == IncludeForm.ARTIFACT }
            includeSummaryIcon?.setImageResource(
                if (allArtifacts) R.drawable.ic_bookmark_added else R.drawable.ic_file
            )

            val expanded = expandedIncludeRows.contains(position)
            includeSummaryList?.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                buildIncludeSummaryRows(includes)
            } else {
                includeSummaryList?.removeAllViews()
            }

            includeSummaryHeader?.setOnClickListener {
                if (expandedIncludeRows.contains(position)) {
                    expandedIncludeRows.remove(position)
                } else {
                    expandedIncludeRows.add(position)
                }
                notifyItemChanged(position)
            }
        }

        private fun buildIncludeSummaryRows(includes: List<ChatInclude>) {
            val list = includeSummaryList ?: return
            list.removeAllViews()
            val inflater = LayoutInflater.from(context)
            for (include in includes) {
                val row = inflater.inflate(R.layout.view_include_summary_item, list, false)
                row.findViewById<ImageView>(R.id.summary_item_icon)
                    ?.setImageResource(includeIcon(include.kind))
                // For an item that has been reduced to a bookmark, the
                // bookmark line IS the informative content — the file name
                // alone would not tell the user what they are still sending.
                row.findViewById<TextView>(R.id.summary_item_name)?.text =
                    if (include.form == IncludeForm.ARTIFACT) {
                        include.modelText()
                    } else {
                        include.fileName
                    }
                // Deliberately the CURRENT weight, not the weight recorded
                // when this turn was sent: after an item has been condensed or
                // reduced to a bookmark, the original figure would overstate
                // what this message still costs on every turn.
                row.findViewById<TextView>(R.id.summary_item_weight)?.text = context.getString(
                    R.string.include_weight,
                    NumberFormat.getIntegerInstance().format(include.currentTokens())
                )
                list.addView(row)
            }
        }

        private fun includeIcon(kind: IncludeKind): Int = when (kind) {
            IncludeKind.TXT -> R.drawable.ic_doc_text
            IncludeKind.MARKDOWN -> R.drawable.ic_doc_markdown
            IncludeKind.CSV -> R.drawable.ic_doc_table
            IncludeKind.DOCX -> R.drawable.ic_doc_word
            IncludeKind.IMAGE -> R.drawable.ic_image
        }

        private fun updateStatusMarker(chatMessage: HashMap<String, Any>) {
            val marker = statusMarker ?: return
            val state = chatMessage[MessageCompletionState.KEY_STATE]?.toString()
            if (chatMessage["isBot"] != true ||
                MessageCompletionState.isComplete(state) ||
                state == MessageCompletionState.STREAMING
            ) {
                marker.visibility = View.GONE
                return
            }
            val label = when (state) {
                MessageCompletionState.INTERRUPTED -> context.getString(R.string.message_state_interrupted)
                MessageCompletionState.STOPPED -> context.getString(R.string.message_state_stopped)
                MessageCompletionState.FAILED -> context.getString(R.string.message_state_failed)
                else -> context.getString(R.string.message_state_incomplete)
            }
            val errorText = chatMessage[MessageCompletionState.KEY_ERROR_TEXT]?.toString().orEmpty()
            marker.text = if (state == MessageCompletionState.FAILED &&
                preferences.showChatErrors() && errorText.isNotBlank()
            ) {
                "$label\n$errorText"
            } else {
                label
            }
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

        private fun updateUI(chatMessage: HashMap<String, Any>) {
            if (preferences.getLayout() == "bubbles" || isAssistant) {
                updateBubbleLayout(chatMessage)
            } else {
                updateClassicLayout(chatMessage)
            }
        }

        private fun updateReportButton(chatMessage: HashMap<String, Any>) {
            if (chatMessage["isBot"] == true) {
                btnReport.visibility = View.VISIBLE
            } else {
                btnReport.visibility = View.GONE
            }
        }

        private fun updateShareButton(chatMessage: HashMap<String, Any>) {
            if (chatMessage["isBot"] == true) {
                btnShare.visibility = View.VISIBLE
            } else {
                btnShare.visibility = View.GONE
            }
        }

        private fun updateSpeakButton(chatMessage: HashMap<String, Any>, position: Int) {
            // Re-read only makes sense for assistant text replies. Hide it for
            // user messages and for image/file messages (nothing to speak).
            val msg = chatMessage["message"].toString()
            val speakable = chatMessage["isBot"] == true &&
                    !msg.contains("data:image") && !msg.contains("~file:")
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

        private fun updateBubbleLayout(chatMessage: HashMap<String, Any>) {
            ui.setBackgroundColor(0x00000000)
            if (chatMessage["isBot"] == true) {
                displayAvatar()

                if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
                    bubbleBg?.setBackgroundResource(R.drawable.bubble_out_dark)
                    message.setTextColor(ResourcesCompat.getColor(context.resources, R.color.white, null))
                }
            } else {
                displayUserAvatar()

                if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
                    bubbleBg?.setBackgroundResource(R.drawable.bubble_in_dark)
                    message.setTextColor(ResourcesCompat.getColor(context.resources, R.color.white, null))
                }
            }
        }

        private fun updateClassicLayout(chatMessage: HashMap<String, Any>) {
            if (chatMessage["isBot"] == true) {
                displayAvatar()

                username.text = preferences.getAssistantName()
                ui.setBackgroundColor(getSurfaceColor(context))
            } else {
                displayUserAvatar()
                username.text = context.getString(R.string.chat_role_user)
                btnCopy.visibility = View.VISIBLE
                ui.setBackgroundColor(getSurface2Color(context))
            }
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
            updateUI(dataArray[position])
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
            } else {
                message.text = chatMessage["message"].toString()
            }
            enableTextSelection()
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

        @SuppressLint("SetTextI18n")
        private fun processFile(chatMessage: HashMap<String, Any>, position: Int, imageType: String, searchArray: ArrayList<String>, u: Boolean) {
            val mimeType = if (u || imageType == "png") "image/png" else "image/jpeg"

            val path = if(u) {
                chatMessage["message"].toString().replace("~file:", "")
            } else {
                chatMessage["image"]
            }

            try {
                val fullPath = context.getExternalFilesDir("images")?.absolutePath + "/" + path + "." + imageType

                while (searchArray.size < itemCount + 1) {
                    searchArray.add("")
                }

                if (searchArray[position] == "") {
                    context.contentResolver?.openFileDescriptor(
                        Uri.fromFile(
                            File(fullPath)
                        ), "r"
                    )?.use { file ->
                        FileInputStream(file.fileDescriptor).use { stream ->
                            run {
                                val c: ByteArray = stream.readBytes()
                                searchArray[position] = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(c)
                                loadImage(searchArray[position])
                                updateImageClickListener(searchArray[position])
                            }
                        }
                    }
                } else {
                    loadImage(searchArray[position])
                    updateImageClickListener(searchArray[position])
                }
            } catch (e: Exception) {
                e.printStackTrace()
                dalleImage.visibility = View.GONE
                message.visibility = View.VISIBLE
                btnCopy.visibility = View.VISIBLE
                message.text = "${message.text}\n<IMAGE NOT FOUND: $path.$mimeType>\nStacktrace: ${e.stackTraceToString()}"
            }
        }

        private fun loadImage(url: String) {
            val requestOptions = RequestOptions().transform(CenterCrop(), RoundedCorners(convertDpToPixel(context).toInt()))
            Glide.with(context).load(url.toUri()).apply(requestOptions).into(dalleImage)

            btnShare.setOnClickListener {
                shareBase64Image(context, url, "png")
            }
        }

        private fun updateImageClickListener(url: String) {
            dalleImage.setOnClickListener {
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

            dalleImage.setOnLongClickListener {
                switchBulkActionState(bindingAdapterPosition)
                return@setOnLongClickListener true
            }
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

    private fun getSurfaceColor(context: Context): Int {
        return if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
            ResourcesCompat.getColor(context.resources, R.color.amoled_accent_50, null)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SurfaceColors.SURFACE_2.getColor(context)
            } else {
                context.getColor(R.color.accent_100)
            }
        }
    }

    private fun getSurface2Color(context: Context): Int {
        return if (isDarkThemeEnabled() && preferences.getAmoledPitchBlack()) {
            ResourcesCompat.getColor(context.resources, R.color.amoled_window_background, null)
        } else {
            SurfaceColors.SURFACE_1.getColor(context)
        }
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
        deleteMessage(position)

        if (chatId !== "") {
            ChatPreferences.getChatPreferences().deleteMessage(context, chatId, position)
        }

        listener?.onMessageDeleted()
    }

    interface OnUpdateListener {
        fun onRetryClick()
        fun onMessageEdited()
        fun onMessageDeleted()
        fun onBulkSelectionChanged(position: Int, selected: Boolean)
        fun onChangeBulkActionMode(mode: Boolean)
        fun onSpeakClick(message: String, position: Int)
    }
}
