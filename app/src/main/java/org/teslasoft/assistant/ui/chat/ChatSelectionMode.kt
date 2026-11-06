/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The chat's Selection Mode: which messages are selected, the area beneath
 * the header, tapping a bubble to toggle it, the dashed outline, and Copy and
 * Share. Delete stays with the chat screen, which owns the conversation's
 * saved boundaries; it reads the selection from [isSelected].
 */
internal class ChatSelectionMode(
    private val context: Context,
    private val host: Host
) {
    interface Host {
        /** Tell the message list whether Selection Mode is on. */
        fun setListSelectionMode(active: Boolean)

        /** Delete the selected messages, after the user confirms. */
        fun deleteSelected()
    }

    /** One entry per message, shared with the message list. */
    val entries: ArrayList<HashMap<String, Any>> = arrayListOf()

    var isActive: Boolean = false
        private set

    private var bar: ChatSelectionBar? = null
    private var transcript: RecyclerView? = null

    /** Binds the area beneath the header and the transcript's tap and outline handling. */
    fun attach(barRoot: View, transcript: RecyclerView) {
        val alreadyOnTranscript = this.transcript === transcript
        this.transcript = transcript
        bar = ChatSelectionBar(barRoot, object : ChatSelectionBar.Actions {
            override fun onCopySelected() = copySelected()
            override fun onShareSelected() = shareSelected()
            override fun onDeleteSelected() = host.deleteSelected()
            override fun onSelectAll() = selectAll()
            override fun onDeselectAll() = deselectAll()
        })
        if (alreadyOnTranscript) return
        transcript.addOnItemTouchListener(
            MessageSelectionTapListener(context, isActive = { isActive }, onBubbleTapped = ::toggle)
        )
        transcript.addItemDecoration(
            MessageSelectionOutline(context, isActive = { isActive }, isSelected = ::isSelected)
        )
    }

    /** Selection Mode starts with nothing selected; the header stays as it is. */
    fun enter() {
        setAll(false)
        isActive = true
        host.setListSelectionMode(true)
        bar?.show(0)
        redraw()
    }

    fun exit() {
        setAll(false)
        isActive = false
        host.setListSelectionMode(false)
        bar?.hide()
        redraw()
    }

    fun selectAll() {
        setAll(true)
        bar?.setCount(selectedCount())
        redraw()
    }

    fun deselectAll() {
        setAll(false)
        bar?.setCount(0)
        redraw()
    }

    fun toggle(position: Int) {
        val entry = entries.getOrNull(position) ?: return
        onSelectionChanged(position, entry["selected"].toString() != "true")
    }

    /** The message list toggled [position] through its own click path. */
    fun onSelectionChanged(position: Int, selected: Boolean) {
        val entry = entries.getOrNull(position) ?: return
        entry["selected"] = selected
        bar?.setCount(selectedCount())
        redraw()
    }

    fun isSelected(position: Int): Boolean =
        entries.getOrNull(position)?.get("selected").toString() == "true"

    /**
     * Rebuilds the entries after the conversation changes. In Selection
     * Mode, every message that is still in the chat keeps its selection, so
     * a reply arriving does not undo what the user picked.
     */
    fun rebuild(messages: List<HashMap<String, Any>>) {
        val stillSelected = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        if (isActive) {
            for (entry in entries) {
                if (entry["selected"].toString() == "true") {
                    entry[SOURCE_KEY]?.let { stillSelected.add(it) }
                }
            }
        }

        entries.clear()
        for (m in messages) {
            val entry = HashMap<String, Any>()
            m["message"]?.let { entry["message"] = it }
            m["isBot"]?.let { entry["isBot"] = it }
            entry["selected"] = m in stillSelected
            entry[SOURCE_KEY] = m
            entries.add(entry)
        }

        if (isActive) {
            bar?.setCount(selectedCount())
            redraw()
        }
    }

    private fun selectedCount(): Int = entries.count { it["selected"].toString() == "true" }

    private fun setAll(selected: Boolean) {
        for (entry in entries) entry["selected"] = selected
    }

    private fun redraw() {
        transcript?.invalidate()
    }

    private fun copySelected() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied messages", selectedText())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Messages copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareSelected() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, selectedText())
        context.startActivity(Intent.createChooser(intent, "Share messages"))
    }

    private fun selectedText(): String {
        val stringBuilder = StringBuilder()

        for (m in entries) {
            if (m["selected"].toString() == "true") {
                if (m["isBot"] == true) {
                    stringBuilder.append("[Bot] >\n")
                } else {
                    stringBuilder.append("[User] >\n")
                }
                stringBuilder.append(m["message"])
                stringBuilder.append("\n\n")
            }
        }

        return stringBuilder.toString()
    }

    private companion object {
        /** Entry key holding the conversation message the entry stands for. */
        const val SOURCE_KEY = "selectionSource"
    }
}
