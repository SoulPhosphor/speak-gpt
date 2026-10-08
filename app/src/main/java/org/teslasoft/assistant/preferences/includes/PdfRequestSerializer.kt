/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.json.JSONArray
import org.json.JSONObject

data class NativePdfPayload(
    val includeId: String,
    val fileName: String,
    /** Inline PDF bytes; empty when the provider receives [fileId] instead. */
    val base64Data: String,
    val originalByteSize: Long = base64Data.length.toLong() * 3L / 4L,
    val pageCount: Int = 0,
    /** A provider-side uploaded copy (xAI Files API), referenced by id. */
    val fileId: String? = null
)

/** Provider syntax lives here, after the canonical conversation has frozen. */
object PdfRequestSerializer {
    internal fun canInline(byteSize: Long, maxBytes: Long): Boolean =
        byteSize in 0..maxBytes

    fun augmentOpenAiChatBody(
        body: String,
        pdfs: List<NativePdfPayload>,
        openRouterNative: Boolean
    ): String {
        if (pdfs.isEmpty()) return body
        val root = JSONObject(body)
        val messages = root.getJSONArray("messages")
        for (pdf in pdfs) {
            // The slot is the marker text part the request builder placed
            // right after the PDF's label; the file takes exactly its place.
            val (parts, index) = findSlot(messages, pdf.includeId)
                ?: throw IllegalStateException("PDF attachment slot is missing")
            val file = if (pdf.fileId != null) {
                JSONObject().put("file_id", pdf.fileId)
            } else {
                JSONObject()
                    .put("filename", pdf.fileName)
                    .put("file_data", "data:application/pdf;base64,${pdf.base64Data}")
            }
            parts.put(index, JSONObject().put("type", "file").put("file", file))
        }
        if (openRouterNative) {
            // An explicit native engine prevents OpenRouter from silently
            // choosing its paid parser for a request the app classified native.
            val plugins = root.optJSONArray("plugins") ?: JSONArray().also { root.put("plugins", it) }
            var replaced = false
            for (i in 0 until plugins.length()) {
                val plugin = plugins.optJSONObject(i) ?: continue
                if (plugin.optString("id") == "file-parser") {
                    plugin.put("pdf", JSONObject().put("engine", "native")); replaced = true
                }
            }
            if (!replaced) plugins.put(
                JSONObject().put("id", "file-parser")
                    .put("pdf", JSONObject().put("engine", "native"))
            )
        }
        return root.toString()
    }

    fun anthropicDocumentBlock(pdf: NativePdfPayload): JSONObject = JSONObject()
        .put("type", "document")
        .put("source", JSONObject()
            .put("type", "base64")
            .put("media_type", "application/pdf")
            .put("data", pdf.base64Data))

    /** Gemini's native generateContent part (not its OpenAI-compatible endpoint). */
    fun geminiDocumentPart(pdf: NativePdfPayload): JSONObject = JSONObject()
        .put("inline_data", JSONObject()
            .put("mime_type", "application/pdf")
            .put("data", pdf.base64Data))

    fun xAiInputFile(fileId: String): JSONObject = JSONObject()
        .put("type", "input_file")
        .put("file_id", fileId)

    private fun findSlot(messages: JSONArray, includeId: String): Pair<JSONArray, Int>? {
        for (i in 0 until messages.length()) {
            val parts = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") != "text") continue
                if (markerId(part.optString("text")) == includeId) return parts to j
            }
        }
        return null
    }

    /** The include id of a text part that is exactly one attachment marker. */
    private fun markerId(text: String): String? {
        val open = StableAttachmentReference.OPEN_TAG
        val close = "</attachment-reference>"
        if (!text.startsWith(open) || !text.endsWith(close)) return null
        return try {
            JSONObject(text.substring(open.length, text.length - close.length)).optString("id")
        } catch (_: Exception) {
            null
        }
    }
}
