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
     * The user's own words come FIRST, then the attachments. Documents that
     * have been reduced to a bookmark are gathered into one short block so a
     * conversation that once discussed a removed file still reads coherently
     * — the model is never left answering questions about something that has
     * silently vanished from its view.
     */
    fun renderUserMessage(typedText: String, includes: List<ChatInclude>): String {
        if (includes.isEmpty()) return typedText

        val body = StringBuilder(typedText)
        val artifacts = ArrayList<String>()

        for (include in includes) {
            when (include.form) {
                IncludeForm.ARTIFACT -> artifacts.add(include.modelText())
                else -> {
                    if (body.isNotEmpty()) body.append("\n\n")
                    body.append(renderDocument(include))
                }
            }
        }

        if (artifacts.isNotEmpty()) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(ARTIFACT_HEADER)
            for (line in artifacts) body.append('\n').append(line)
        }

        return body.toString()
    }

    private fun renderDocument(include: ChatInclude): String {
        val label = if (include.form == IncludeForm.CONDENSED) {
            "Attached document (condensed by the user): ${include.fileName}"
        } else {
            "Attached document: ${include.fileName}"
        }
        val note = sizeNote(include.notice)
        val head = if (note == null) label else "$label — $note"
        return buildString {
            append("--- ").append(head).append(" ---\n")
            append(include.modelText())
            append("\n--- End of ").append(include.fileName).append(" ---")
        }
    }

    /**
     * The model's own copy of a size warning. The user sees the approved
     * wording in the UI; the model needs the same fact in its own view, or it
     * will confidently reason about a truncated file as if it were complete.
     */
    private fun sizeNote(notice: IncludeNotice): String? = when (notice) {
        is IncludeNotice.None -> null
        is IncludeNotice.Large -> null
        is IncludeNotice.Truncated ->
            "only the beginning of this file is included; the rest was too long to send"
        is IncludeNotice.CsvTrimmed ->
            "column names and the first ${notice.sentRows} rows of ${notice.totalRows} total rows"
    }

    private const val ARTIFACT_HEADER = "Previously attached (content no longer included):"
}
