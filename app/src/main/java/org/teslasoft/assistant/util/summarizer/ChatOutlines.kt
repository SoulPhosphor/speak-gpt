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

import org.json.JSONArray

/**
 * A chat's outlines: an ordered list of markdown texts, one per tab on the
 * Chat Outline screen. Tabs are named by position (the first is "Chat
 * Outline", then "Outline 2", "Outline 3"...), so deleting one renumbers the
 * rest. Outlines are never sent to the chat's AI.
 */
object ChatOutlines {

    fun fromJson(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toJson(outlines: List<String>): String =
        if (outlines.isEmpty()) "" else JSONArray(outlines).toString()

    /** Generate New Outline: a new tab after the existing ones. */
    fun afterGenerateNew(outlines: List<String>, added: String): List<String> = outlines + added

    /** Replace All Outlines With New: one outline replaces every existing one. */
    fun afterReplaceAll(added: String): List<String> = listOf(added)

    /** Redo This Outline or an edit: only the tab at [index] changes. */
    fun afterReplace(outlines: List<String>, index: Int, text: String): List<String> =
        if (index !in outlines.indices) outlines
        else outlines.toMutableList().also { it[index] = text }

    /** Delete on one tab, offered only while more than one outline exists. */
    fun afterDelete(outlines: List<String>, index: Int): List<String> =
        if (outlines.size <= 1 || index !in outlines.indices) outlines
        else outlines.filterIndexed { i, _ -> i != index }

    /** Tab name: null for the first tab (named "Chat Outline"), otherwise its
     *  1-based number, for "Outline %d". */
    fun numberedLabel(index: Int): Int? = if (index <= 0) null else index + 1
}
