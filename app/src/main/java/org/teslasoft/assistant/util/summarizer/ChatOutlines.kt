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

package org.teslasoft.assistant.util.summarizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.R

/**
 * One Chat Outline tab: its markdown, its name, the outline prompt it was
 * made with, and whether the user renamed it (a user's name is never
 * replaced by the app).
 */
data class ChatOutline(
    val text: String,
    val name: String,
    val promptId: String? = null,
    val customName: Boolean = false
)

/**
 * A chat's outlines, one per tab on the Chat Outline screen, at most
 * [MAX_OUTLINES]. A new outline is named after the prompt that made it: the
 * first "Chat Outline", then "Chat Outline 1", "Chat Outline 2"..., taking the
 * lowest free number. Outlines are never sent to the chat's AI.
 */
object ChatOutlines {

    const val MAX_OUTLINES = 5

    /**
     * Saved outlines. Outlines saved before tabs had names are plain strings;
     * they keep the names they were shown with, from [legacyName] (their
     * position).
     */
    fun fromJson(json: String?, legacyName: (Int) -> String = { "" }): List<ChatOutline> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.opt(index)
                if (item is JSONObject) {
                    ChatOutline(
                        text = item.optString(KEY_TEXT),
                        name = item.optString(KEY_NAME).ifBlank { legacyName(index) },
                        promptId = item.optString(KEY_PROMPT_ID).ifBlank { null },
                        customName = item.optBoolean(KEY_CUSTOM_NAME, false)
                    )
                } else {
                    ChatOutline(text = array.optString(index), name = legacyName(index))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Saved outlines, with older unnamed outlines named as they were shown. */
    fun load(context: Context, json: String?): List<ChatOutline> = fromJson(json) { index ->
        legacyNumber(index)?.let { context.getString(R.string.chat_outline_tab_numbered, it) }
            ?: context.getString(R.string.chat_outline_tab_first)
    }

    fun toJson(outlines: List<ChatOutline>): String {
        if (outlines.isEmpty()) return ""
        val array = JSONArray()
        outlines.forEach { outline ->
            array.put(
                JSONObject()
                    .put(KEY_TEXT, outline.text)
                    .put(KEY_NAME, outline.name)
                    .put(KEY_PROMPT_ID, outline.promptId ?: "")
                    .put(KEY_CUSTOM_NAME, outline.customName)
            )
        }
        return array.toString()
    }

    fun isFull(outlines: List<ChatOutline>): Boolean = outlines.size >= MAX_OUTLINES

    /**
     * The name for an outline made with the prompt named [promptName]: the
     * prompt's name when no other tab has it, else the prompt's name and the
     * lowest number from 1 that no other tab has.
     */
    fun nextName(promptName: String, takenNames: Collection<String>): String {
        if (promptName !in takenNames) return promptName
        var number = 1
        while ("$promptName $number" in takenNames) number++
        return "$promptName $number"
    }

    /** Generate New Outline: a new tab after the existing ones, named after its prompt. */
    fun afterGenerateNew(
        outlines: List<ChatOutline>,
        text: String,
        promptId: String?,
        promptName: String
    ): List<ChatOutline> =
        outlines + ChatOutline(
            text = text,
            name = nextName(promptName, outlines.map { it.name }),
            promptId = promptId
        )

    /**
     * Redo This Outline: only the tab at [index] changes. Redone with a
     * different prompt, the tab is renamed after that prompt unless the user
     * named it.
     */
    fun afterRedo(
        outlines: List<ChatOutline>,
        index: Int,
        text: String,
        promptId: String?,
        promptName: String
    ): List<ChatOutline> {
        if (index !in outlines.indices) return outlines
        val old = outlines[index]
        val keepName = old.customName || (old.promptId != null && old.promptId == promptId)
        val others = outlines.filterIndexed { i, _ -> i != index }.map { it.name }
        val updated = old.copy(
            text = text,
            name = if (keepName) old.name else nextName(promptName, others),
            promptId = promptId
        )
        return outlines.toMutableList().also { it[index] = updated }
    }

    /** An edit on the tab at [index]: only its text changes. */
    fun afterEdit(outlines: List<ChatOutline>, index: Int, text: String): List<ChatOutline> =
        if (index !in outlines.indices) outlines
        else outlines.toMutableList().also { it[index] = it[index].copy(text = text) }

    /** Rename: the user's name; a blank name leaves the tab unchanged. */
    fun afterRename(outlines: List<ChatOutline>, index: Int, name: String): List<ChatOutline> {
        val trimmed = name.trim()
        if (index !in outlines.indices || trimmed.isEmpty()) return outlines
        return outlines.toMutableList().also { it[index] = it[index].copy(name = trimmed, customName = true) }
    }

    /** Delete on one tab, offered only while more than one outline exists. */
    fun afterDelete(outlines: List<ChatOutline>, index: Int): List<ChatOutline> =
        if (outlines.size <= 1 || index !in outlines.indices) outlines
        else outlines.filterIndexed { i, _ -> i != index }

    /** The position-based name of an outline saved before tabs had names:
     *  null for the first (named "Chat Outline"), else its 1-based number,
     *  for "Outline %d". */
    fun legacyNumber(index: Int): Int? = if (index <= 0) null else index + 1

    private const val KEY_TEXT = "text"
    private const val KEY_NAME = "name"
    private const val KEY_PROMPT_ID = "promptId"
    private const val KEY_CUSTOM_NAME = "customName"
}
