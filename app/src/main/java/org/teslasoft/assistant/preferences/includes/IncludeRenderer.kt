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
 * Every attachment is introduced by a [label] naming its type, file name and
 * current form. A FULL image is delivered as its label followed by the image
 * part itself ([segmentsFor]); a reduced or removed image is text like any
 * document. Condense, Reduce, Edit and Remove replace an attachment's content
 * in place, so only that message and the ones after it leave the cache once.
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
            body.append(label(include)).append('\n').append(block)
        }
        return body.toString()
    }

    /**
     * The complete ordered content of a user message as the model receives
     * it: the user's own words first, then each attachment in attachment
     * order, each introduced by its [label]. A FULL image is its label
     * followed by the image itself; a FULL PDF is its label followed by a
     * [RenderedSegment.Pdf] the request builder resolves to native bytes or
     * locally extracted text. Every other form is one text block. Whatever
     * form an attachment is in, it occupies this same position in its
     * message.
     */
    fun segmentsFor(typedText: String, includes: List<ChatInclude>): List<RenderedSegment> {
        val out = ArrayList<RenderedSegment>()
        if (typedText.isNotEmpty()) out += RenderedSegment.Text(typedText)
        for (include in includes) {
            when {
                include.hasLiveImageBytes() -> {
                    val part = imagePartsFor(listOf(include)).singleOrNull() ?: continue
                    out += RenderedSegment.Text(label(include))
                    out += RenderedSegment.Image(part)
                }
                include.hasLivePdfBytes() -> {
                    out += RenderedSegment.Text(label(include))
                    out += RenderedSegment.Pdf(include)
                }
                else -> {
                    val block = renderInline(include) ?: continue
                    out += RenderedSegment.Text(label(include) + "\n" + block)
                }
            }
        }
        return out
    }

    /**
     * Owner-approved line introducing an attachment, so the model knows
     * what the text or image that follows is and which upload it stands for.
     */
    fun label(include: ChatInclude): String {
        val type = typeName(include.kind)
        val name = include.fileName
        return when {
            include.form == IncludeForm.ARTIFACT ->
                "Short reminder of removed uploaded $type ($name):"
            include.form == IncludeForm.CONDENSED && include.kind.isImage() ->
                "Description of original uploaded image ($name):"
            include.form == IncludeForm.CONDENSED ->
                "Summary of original uploaded $type ($name):"
            else -> "Uploaded $type ($name):"
        }
    }

    private fun typeName(kind: IncludeKind): String = when (kind) {
        IncludeKind.TXT -> "text file"
        IncludeKind.MARKDOWN -> "Markdown file"
        IncludeKind.JSON -> "JSON file"
        IncludeKind.CSV -> "CSV file"
        IncludeKind.DOCX -> "Word document"
        IncludeKind.XLSX -> "Excel spreadsheet"
        IncludeKind.PDF -> "PDF"
        IncludeKind.JPEG, IncludeKind.PNG -> "image"
    }

    /** Body of a FULL PDF sent as locally extracted text instead of the file. */
    fun renderLocalPdf(include: ChatInclude, extractedText: String): String = buildString {
        append("<document name=\"")
            .append(escapeAttribute(include.fileName))
            .append("\">\n")
        append("This is locally extracted PDF text. Page layout, diagrams, charts, ")
        append("and other visual details may not be preserved.\n\n")
        append(extractedText)
        append("\n</document>")
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

    /** FULL PDFs stay out of the text projection. Delivery is selected at the
     * final provider boundary, using either these original bytes or a local
     * extracted/OCR representation. */
    fun pdfPartsFor(includes: List<ChatInclude>): List<RenderedPdfPart> {
        if (includes.isEmpty()) return emptyList()
        return includes.mapNotNull { include ->
            if (!include.hasLivePdfBytes()) return@mapNotNull null
            RenderedPdfPart(
                includeId = include.id,
                pdfFileHash = include.pdfFileHash ?: return@mapNotNull null,
                fileName = include.fileName,
                byteSize = include.pdfByteSize,
                pageCount = include.pdfPageCount,
                cachedFallbackText = include.pdfFallbackText,
                fallbackProvenance = include.pdfFallbackProvenance
            )
        }
    }

    /** Text-side rendering of one include, or null if this include has no
     *  text-side representation (a FULL image is delivered as bytes, not text). */
    private fun renderInline(include: ChatInclude): String? = when {
        include.form == IncludeForm.ARTIFACT -> renderBookmark(include)
        include.form == IncludeForm.FULL && (include.kind.isImage() || include.kind == IncludeKind.PDF) -> null
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

/** One ordered piece of a user message's model-facing content. */
sealed class RenderedSegment {
    data class Text(val text: String) : RenderedSegment()
    data class Image(val part: RenderedImagePart) : RenderedSegment()
    /** A FULL PDF whose delivery (native file or local text) is chosen per request. */
    data class Pdf(val include: ChatInclude) : RenderedSegment()
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

data class RenderedPdfPart(
    val includeId: String,
    val pdfFileHash: String,
    val fileName: String,
    val byteSize: Long,
    val pageCount: Int,
    val cachedFallbackText: String?,
    val fallbackProvenance: PdfFallbackProvenance?
)
