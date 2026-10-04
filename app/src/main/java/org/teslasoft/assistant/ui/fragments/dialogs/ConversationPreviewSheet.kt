/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.fragments.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.util.summarizer.SummarySectionTime
import org.teslasoft.assistant.util.summarizer.SummarySections

/**
 * Conversation preview (owner-approved design, Oct 4 2026): a nearly
 * full-height, read-only view of the real conversation over the Summary
 * screen, opened at a summary section's first message with its starting
 * point marked. The whole conversation scrolls in both directions for
 * context. Tapping a message asks the host to go to that exact message in the
 * chat, where all editing stays. The double chevron or a swipe down closes
 * it, leaving the Summary screen exactly where it was.
 */
class ConversationPreviewSheet : BottomSheetDialogFragment() {

    /** Implemented by the host: go to [messageId] in the real chat. */
    fun interface Host {
        fun onPreviewMessageChosen(messageId: String)
    }

    private sealed interface Row {
        data class Message(val id: String, val speaker: String, val text: String) : Row
        data object StartMarker : Row
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_conversation_preview, container, false)

    override fun onStart() {
        super.onStart()
        // Nearly full height, opened fully; a downward swipe dismisses it.
        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatId = requireArguments().getString(ARG_CHAT_ID).orEmpty()
        val sectionId = requireArguments().getString(ARG_SECTION_ID).orEmpty()
        view.findViewById<View>(R.id.btn_close_preview).setOnClickListener { dismiss() }

        val stored = ChatPreferences.getChatPreferences().getChatById(requireContext(), chatId)
        val section = SummarySections.fromJson(
            Preferences.getPreferences(requireContext(), chatId).getSummarySections()
        ).firstOrNull { it.id == sectionId }
        val owned = section?.messageIds?.toSet().orEmpty()
        val startIndex = stored.indexOfFirst { MessageIdentity.idOf(it) in owned && it["isBot"] != true }
            .takeIf { it >= 0 }
            ?: stored.indexOfFirst { MessageIdentity.idOf(it) in owned }

        val startTime = stored.getOrNull(startIndex)
            ?.get(ChatAdapter.KEY_MESSAGE_TIME)?.toString()?.toLongOrNull()
        view.findViewById<TextView>(R.id.preview_start_time).text =
            startTime?.let { SummarySectionTime.format(it, it) }
                ?: getString(R.string.summary_section_time_unknown)

        val rows = ArrayList<Row>()
        var markerRow = 0
        stored.forEachIndexed { index, message ->
            if (index == startIndex) {
                markerRow = rows.size
                rows.add(Row.StartMarker)
            }
            val isBot = message["isBot"] == true
            val raw = message["message"]?.toString().orEmpty()
            rows.add(
                Row.Message(
                    MessageIdentity.idOf(message),
                    getString(if (isBot) R.string.chat_role_assistant else R.string.chat_role_user),
                    if (raw.trimStart().startsWith("~file:")) getString(R.string.image_gen_injection_legacy) else raw
                )
            )
        }

        val list = view.findViewById<RecyclerView>(R.id.preview_messages)
        val layoutManager = LinearLayoutManager(requireContext())
        list.layoutManager = layoutManager
        list.adapter = PreviewAdapter(rows) { messageId ->
            (activity as? Host)?.onPreviewMessageChosen(messageId)
            dismiss()
        }
        if (startIndex >= 0) layoutManager.scrollToPositionWithOffset(markerRow, 0)
    }

    private class PreviewAdapter(
        private val rows: List<Row>,
        private val onMessage: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.StartMarker) TYPE_MARKER else TYPE_MESSAGE

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_MARKER) {
                R.layout.view_conversation_preview_marker
            } else {
                R.layout.view_conversation_preview_message
            }
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position] as? Row.Message ?: return
            holder.itemView.findViewById<TextView>(R.id.preview_speaker).text = row.speaker
            holder.itemView.findViewById<TextView>(R.id.preview_text).text = row.text
            holder.itemView.setOnClickListener {
                if (row.id.isNotBlank()) onMessage(row.id)
            }
        }

        private companion object {
            const val TYPE_MESSAGE = 0
            const val TYPE_MARKER = 1
        }
    }

    companion object {
        const val TAG = "ConversationPreviewSheet"
        private const val ARG_CHAT_ID = "chatId"
        private const val ARG_SECTION_ID = "sectionId"

        fun newInstance(chatId: String, sectionId: String): ConversationPreviewSheet =
            ConversationPreviewSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHAT_ID, chatId)
                    putString(ARG_SECTION_ID, sectionId)
                }
            }
    }
}
