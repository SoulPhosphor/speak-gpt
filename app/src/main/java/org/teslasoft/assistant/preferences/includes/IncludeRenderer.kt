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
 * Turns a user message's attached includes into the text (and image parts)
 * the model actually receives. Pure, so it is unit-tested.
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
 *
 * Images are handled in two halves. Their inline text (a `<image>` block once
 * reduced, or a `<bookmark>` once removed) rides in the text side alongside
 * documents. FULL images contribute no text-side content — their bytes are
 * emitted as separate image parts by [imagePartsFor] and are always the LAST
 * content in the message so that everything preceding them still fills a
 * prefix cache when a provider cannot cache image bytes.
 */
object IncludeRenderer {

    /**
     * Builds the TEXT-SIDE content of a user message.
     *
     * The user's own words form the stable prefix. Includes follow in their
     * original attachment order, so editing or reducing one item invalidates
     * no more of the provider's prefix cache than necessary.
     *
     * FULL images contribute nothing here; use [imagePartsFor] to enumerate
     * the accompanying image parts for the caller's multi-part message.
     */
    fun renderUserMessage(typedText: String, includes: List<ChatInclude>): String {
        if (includes.isEmpty()) return typedText

        val body = StringBuilder(typedText)
        for (include in includes) {
            val block = renderInline(include) ?: continue
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(block)
        }
        return body.toString()
    }

    /**
     * Enumerates the FULL image includes whose on-disk bytes accompany a
     * multi-part user message. Order matches the includes list; the caller
     * appends them AFTER every text piece the message carries.
     */
    fun imagePartsFor(includes: List<ChatInclude>): List<RenderedImagePart> {
        if (includes.isEmpty()) return emptyList()
        val out = ArrayList<RenderedImagePart>()
        for (include in includes) {
            if (!include.hasLiveImageBytes()) continue
            val hash = include.imageFileHash ?: continue
            val mime = include.imageMimeType ?: continue
            out.add(
                RenderedImagePart(
                    includeId = include.id,
                    imageFileHash = hash,
                    imageMimeType = mime,
                    fileName = include.fileName
                )
            )
        }
        return out
    }

    /** Text-side rendering of one include, or null if this include has no
     *  text-side representation (a FULL image is delivered as bytes, not text). */
    private fun renderInline(include: ChatInclude): String? = when {
        include.form == IncludeForm.ARTIFACT -> renderBookmark(include)
        include.form == IncludeForm.FULL && include.kind.isImage() -> null
        include.kind.isImage() -> renderImage(include)
        else -> renderDocument(include)
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
                is IncludeNotice.None -> Unit
                is IncludeNotice.Truncated ->
                    append(" partial=\"beginning only\"")
                is IncludeNotice.CsvTrimmed -> {
                    append(" rows=\"header + first ")
                        .append(notice.sentRows)
                        .append(" of ")
                        .append(notice.totalRows)
                        .append('"')
                }
                // The worksheet count has to reach the model too. Without it
                // the AI would read a fragment of a large workbook as though
                // it were the whole thing — the same mistake the row counts
                // exist to prevent, one level up.
                is IncludeNotice.WorkbookTrimmed -> {
                    append(" sheets=\"")
                        .append(notice.sheets)
                        .append("\" rows=\"header + first ")
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

    /**
     * A reduced image reaches the model as a text description of the image it
     * replaced. The wrapper says `<image>` rather than `<document>` so the
     * model can tell that the block is describing a picture, not summarising
     * a text file.
     */
    private fun renderImage(include: ChatInclude): String = buildString {
        append("<image name=\"")
            .append(escapeAttribute(include.fileName))
            .append("\" form=\"reduced\">\n")
        append(include.modelText())
        append("\n</image>")
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

/**
 * A live image content part that must accompany a multi-part user message.
 * Callers assemble the outbound "data:image/…;base64,…" URL themselves from
 * the on-disk bytes at Send time — this record only names what to send, not
 * how to encode it.
 */
data class RenderedImagePart(
    /** The include's stable id. Kept so a caller building a request can trace
     *  back to which pending or sent record the image part came from. */
    val includeId: String,
    /** Hash portion of the on-disk file name, without extension. */
    val imageFileHash: String,
    /** MIME type of the on-disk bytes ("image/jpeg" or "image/png"). */
    val imageMimeType: String,
    /** Display file name (not used to look up the file — the hash is — but
     *  useful for logging and for diagnostics if the file has gone missing). */
    val fileName: String
)
