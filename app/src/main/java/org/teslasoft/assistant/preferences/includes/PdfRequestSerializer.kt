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
    val base64Data: String
)

/** Provider syntax lives here, after the canonical conversation has frozen. */
object PdfRequestSerializer {
    fun augmentOpenAiChatBody(
        body: String,
        pdfs: List<NativePdfPayload>,
        openRouterNative: Boolean
    ): String {
        if (pdfs.isEmpty()) return body
        val root = JSONObject(body)
        val messages = root.getJSONArray("messages")
        for (pdf in pdfs) {
            val message = findOwningMessage(messages, pdf.includeId)
                ?: throw IllegalStateException("PDF attachment slot is missing")
            val prior = message.opt("content")
            val parts = when (prior) {
                is JSONArray -> prior
                is String -> JSONArray().put(JSONObject().put("type", "text").put("text", prior))
                else -> JSONArray()
            }
            parts.put(
                JSONObject().put("type", "file").put(
                    "file", JSONObject()
                        .put("filename", pdf.fileName)
                        .put("file_data", "data:application/pdf;base64,${pdf.base64Data}")
                )
            )
            message.put("content", parts)
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

    fun geminiDocumentPart(pdf: NativePdfPayload): JSONObject = JSONObject()
        .put("type", "document")
        .put("data", pdf.base64Data)
        .put("mime_type", "application/pdf")

    fun xAiInputFile(fileId: String): JSONObject = JSONObject()
        .put("type", "input_file")
        .put("file_id", fileId)

    private fun findOwningMessage(messages: JSONArray, includeId: String): JSONObject? {
        val marker = "\"id\":\"" + includeId.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.opt("content")?.toString()?.contains(marker) == true) return message
        }
        return null
    }
}
