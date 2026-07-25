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

package org.teslasoft.assistant.preferences.includes

/**
 * Turns a user message's attached includes into the text the model actually
 * receives. Pure, so it is unit-tested.
 *
 * Placement matters and is the whole reason this is a separate step: an
 * include rides INSIDE the user message it was attached to, so it sits at a
 * fixed point in the conversation history that never moves. History only ever
 * grows at the end, so every later turn re-sends this message byte-identically
 * and the provider's prefix cache covers it — which is what makes asking many
 * questions about one document cheap. Rendering must therefore be
 * deterministic: same includes in the same forms must produce the same bytes
 * every single turn. Never introduce timestamps, ordering by hash-map
 * iteration, or anything else that varies between calls.
 */
object IncludeRenderer {

    /**
     * Builds the model-facing content of a user message.
     *
     * The user's own words form the stable prefix. Includes follow in their
     * original attachment order, so editing or reducing one item invalidates
     * no more of the provider's prefix cache than necessary.
     */
    fun renderUserMessage(typedText: String, includes: List<ChatInclude>): String {
        if (includes.isEmpty()) return typedText

        val body = StringBuilder(typedText)
        for (include in includes) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(
                if (include.form == IncludeForm.ARTIFACT) {
                    renderBookmark(include)
                } else {
                    renderDocument(include)
                }
            )
        }
        return body.toString()
    }

    private fun renderDocument(include: ChatInclude): String {
        return buildString {
            append("<document name=\"")
                .append(escapeAttribute(include.fileName))
                .append('"')
            if (include.form == IncludeForm.CONDENSED) {
                append(" form=\"condensed\"")
            }
            when (val notice = include.notice) {
                is IncludeNotice.None,
                is IncludeNotice.Large -> Unit
                is IncludeNotice.Truncated ->
                    append(" partial=\"beginning only\"")
                is IncludeNotice.CsvTrimmed -> {
                    append(" rows=\"header + first ")
                        .append(notice.sentRows)
                        .append(" of ")
                        .append(notice.totalRows)
                        .append('"')
                }
            }
            append(">\n")
            append(include.modelText())
            append("\n</document>")
        }
    }

    private fun renderBookmark(include: ChatInclude): String = buildString {
        append("<bookmark name=\"")
            .append(escapeAttribute(include.fileName))
            .append("\">")
        append(include.modelText())
        append("</bookmark>")
    }

    private fun escapeAttribute(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(character)
            }
        }
    }
}
