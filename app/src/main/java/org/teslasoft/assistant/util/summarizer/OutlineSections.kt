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
import org.json.JSONObject
import org.teslasoft.assistant.util.edits.ReferencedEdit
import org.teslasoft.assistant.util.edits.ReferencedEdits

/**
 * An outline being built during one Chat Outline run, divided into sections
 * the model may rewrite or add to. A section is a markdown header and
 * everything under it up to the next header; text before the first header is
 * a section of its own. The model is shown each section with a reference
 * ("S1", "S2"...) for that request only, and replies with JSON naming the
 * sections it rewrites and any new sections to add at the end.
 *
 * A reply is applied whole or not at all: if it cannot be read, or any edit
 * in it names a section that was not shown, names one section twice, or has
 * no content, nothing in it is applied and the run fails.
 */
object OutlineSections {

    private const val PREFIX = "S"
    private const val KEY_EDITS = "section_edits"
    private const val KEY_SECTION = "section"
    private const val KEY_CONTENT = "content"
    private const val KEY_NEW = "new_sections"

    private val HEADER = Regex("^ {0,3}#{1,6}(\\s|$)")
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")

    /** A backtick fence's info text may not contain a backtick (markdown
     *  reads such a line as inline code, not a fence). */
    private fun opens(fence: String, rest: String): Boolean = fence[0] != '`' || '`' !in rest

    /** A fence closes the open one only with the same character, at least as
     *  many of them, and nothing after it but spaces. */
    private fun closes(open: String, fence: String, rest: String): Boolean =
        fence[0] == open[0] && fence.length >= open.length && rest.isBlank()

    data class SectionEdit(
        override val refs: List<String>,
        override val content: String
    ) : ReferencedEdit

    /** The outline's sections in order. Headers inside fenced code do not
     *  start a section. */
    fun split(outline: String): List<String> {
        val sections = ArrayList<String>()
        val current = StringBuilder()
        // The open fence's run of backticks or tildes, or null outside one.
        var openFence: String? = null
        fun flush() {
            val text = current.toString().trimEnd().trimStart('\n', '\r')
            if (text.isNotBlank()) sections.add(text)
            current.setLength(0)
        }
        for (line in outline.lines()) {
            val fence = FENCE.find(line)
            val open = openFence
            if (open == null) {
                if (fence != null && opens(fence.groupValues[1], line.substring(fence.range.last + 1))) {
                    openFence = fence.groupValues[1]
                } else if (HEADER.containsMatchIn(line)) {
                    flush()
                }
            } else if (fence != null && closes(open, fence.groupValues[1], line.substring(fence.range.last + 1))) {
                openFence = null
            }
            current.append(line).append('\n')
        }
        flush()
        return sections
    }

    fun join(sections: List<String>): String = sections.joinToString("\n\n")

    /** The outline as the model is shown it: each section after its
     *  reference. Empty when there is no outline yet. */
    fun render(outline: String): String =
        split(outline).withIndex().joinToString("\n\n") { (index, text) -> "$PREFIX${index + 1}\n$text" }

    /**
     * [outline] with [reply]'s edits and new sections applied, or null when
     * the reply cannot be applied safely.
     */
    fun apply(outline: String, reply: String): String? {
        val json = try {
            JSONObject(ReferencedEdits.jsonObjectIn(reply))
        } catch (_: Exception) {
            return null
        }
        // A reply with neither field is not an answer in this form.
        if (!json.has(KEY_EDITS) && !json.has(KEY_NEW)) return null
        val edits = readEdits(json) ?: return null
        val added = when (val value = json.opt(KEY_NEW)) {
            null, JSONObject.NULL -> ""
            is String -> value.trim()
            else -> return null
        }

        val sections = split(outline)
        val exposed = sections.indices.associateBy { "$PREFIX${it + 1}" }
        val result = ReferencedEdits.validate(edits, emptyList(), exposed, sections.indices.toSet())
        if (result.failed.isNotEmpty()) return null

        val updated = sections.toMutableList()
        result.valid.forEach { op ->
            if (op is ReferencedEdits.Accepted.Edit) updated[op.ids.single()] = op.edit.content
        }
        if (added.isNotEmpty()) updated.add(added)
        return join(updated)
    }

    /** The section edits, or null when the field is malformed. */
    private fun readEdits(json: JSONObject): List<SectionEdit>? {
        val array = when (val value = json.opt(KEY_EDITS)) {
            null, JSONObject.NULL -> return emptyList()
            is JSONArray -> value
            else -> return null
        }
        return (0 until array.length()).map { index ->
            val item = array.opt(index) as? JSONObject ?: return null
            val ref = item.opt(KEY_SECTION) as? String ?: return null
            val content = item.opt(KEY_CONTENT) as? String ?: return null
            SectionEdit(listOf(ref), content.trim())
        }
    }
}
