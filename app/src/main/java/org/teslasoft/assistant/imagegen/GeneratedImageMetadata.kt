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

package org.teslasoft.assistant.imagegen

import org.json.JSONObject

/**
 * The structured record stored WITH a generated assistant message
 * (image-generation-rebuild-plan.md §12): `~file:<hash>` stops being the
 * complete data model. The record rides the message map as one JSON string
 * under [KEY]; the message text keeps the legacy `~file:` form purely as
 * the compatibility projection the existing adapter render path still
 * reads during migration, so legacy messages (which have no record) and
 * new ones render through the same code.
 *
 * §12 never-store list: this record deliberately has no field for an API
 * key, authorization header, or signed temporary download URL, so none
 * can enter chat history through it.
 */
class GeneratedImageMetadata(
    /** Stable generated-image ID, independent of file content. */
    val imageId: String,
    /** The stored file's content hash — the same value as the message's
     *  `~file:` projection. Null for failed/cancelled records that never
     *  produced a file. */
    val fileHash: String?,
    /** Real detected MIME type (§4.5), null when no file exists. */
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val endpointId: String,
    val modelId: String,
    val prompt: String,
    /** The model-supplied accessible description; null on `/imagine`. */
    val description: String?,
    val createdAt: Long,
    /** One of [STATUS_GENERATING], [STATUS_COMPLETE], [STATUS_FAILED],
     *  [STATUS_CANCELLED]. */
    val status: String,
    /** Sanitized failure code (an [ImageErrorCause] name) when applicable. */
    val failureCode: String?,
    /** The Summarizer's short version of [prompt], used as the model-facing
     *  reminder to save tokens. Null until a summarizer has produced one (or
     *  none is configured). */
    val imageSummary: String? = null,
    /** The user's edited image summary from the prompt box. When present it
     *  overrides [imageSummary] both in the box and in the text sent to the
     *  model. Null unless the user has saved an edit. */
    val summaryEdited: String? = null
) {

    /** True when this image was produced by the user's `/imagine` command
     *  rather than a model tool call. The `create_image` tool always supplies
     *  a non-blank description; `/imagine` never does (see [description]), so
     *  the presence of a description is the stored who-made-it signal. */
    fun initiatedByUser(): Boolean = description.isNullOrBlank()

    /** The image summary the model should receive, and the box should show:
     *  the user's edit, else the summarizer's version, else null when neither
     *  exists (caller falls back to the full prompt). */
    fun effectiveSummary(): String? =
        summaryEdited?.takeIf { it.isNotBlank() }
            ?: imageSummary?.takeIf { it.isNotBlank() }

    fun withImageSummary(summary: String?): GeneratedImageMetadata =
        copyWith(imageSummary = summary)

    fun withSummaryEdited(edited: String?): GeneratedImageMetadata =
        copyWith(summaryEdited = edited)

    private fun copyWith(
        imageSummary: String? = this.imageSummary,
        summaryEdited: String? = this.summaryEdited
    ): GeneratedImageMetadata = GeneratedImageMetadata(
        imageId, fileHash, mimeType, width, height, endpointId, modelId,
        prompt, description, createdAt, status, failureCode,
        imageSummary, summaryEdited
    )

    fun toJson(): String {
        val json = JSONObject()
        json.put("imageId", imageId)
        if (fileHash != null) json.put("fileHash", fileHash)
        if (mimeType != null) json.put("mimeType", mimeType)
        if (width != null) json.put("width", width)
        if (height != null) json.put("height", height)
        json.put("endpointId", endpointId)
        json.put("modelId", modelId)
        json.put("prompt", prompt)
        if (description != null) json.put("description", description)
        json.put("createdAt", createdAt)
        json.put("status", status)
        if (failureCode != null) json.put("failureCode", failureCode)
        if (imageSummary != null) json.put("imageSummary", imageSummary)
        if (summaryEdited != null) json.put("summaryEdited", summaryEdited)
        return json.toString()
    }

    companion object {
        /** Message-map key carrying the JSON record. Rides the same Gson
         *  blob as the message text, like the completion-state marker. */
        const val KEY = "generatedImage"

        const val STATUS_GENERATING = "generating"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val LEGACY_MARKER_PREFIX = "~file:"

        /** Tolerant decode: legacy messages have no record, and a damaged
         *  record must degrade to the legacy rendering path, never crash. */
        fun fromJson(raw: String?): GeneratedImageMetadata? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                GeneratedImageMetadata(
                    imageId = json.optString("imageId"),
                    fileHash = json.optString("fileHash").ifBlank { null },
                    mimeType = json.optString("mimeType").ifBlank { null },
                    width = if (json.has("width")) json.optInt("width") else null,
                    height = if (json.has("height")) json.optInt("height") else null,
                    endpointId = json.optString("endpointId"),
                    modelId = json.optString("modelId"),
                    prompt = json.optString("prompt"),
                    description = json.optString("description").ifBlank { null },
                    createdAt = json.optLong("createdAt"),
                    status = json.optString("status"),
                    failureCode = json.optString("failureCode").ifBlank { null },
                    imageSummary = json.optString("imageSummary").ifBlank { null },
                    summaryEdited = json.optString("summaryEdited").ifBlank { null }
                )
            } catch (_: Exception) {
                null
            }
        }

        /** The image-file hash a stored chat message references, or null.
         *  New messages answer from their structured record; legacy
         *  messages from their `~file:` text — the one shared definition
         *  of "references a generated image file". */
        fun referencedFileHash(message: Map<String, Any?>): String? {
            fromJson(message[KEY]?.toString())?.fileHash?.let { hash ->
                if (hash.isNotBlank()) return hash
            }
            val text = message["message"]?.toString() ?: return null
            if (!text.startsWith(LEGACY_MARKER_PREFIX)) return null
            return text.removePrefix(LEGACY_MARKER_PREFIX).trim().ifBlank { null }
        }
    }
}
