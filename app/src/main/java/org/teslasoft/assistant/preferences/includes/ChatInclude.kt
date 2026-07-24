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

import org.json.JSONArray
import org.json.JSONObject

/**
 * One attached item ("include") in a chat — a document today, an image in a
 * later step. See `document-includes-plan.md` for the owner-approved design.
 *
 * The ladder (heaviest to lightest) is FULL -> CONDENSED -> ARTIFACT, and it
 * only ever moves DOWN, by an explicit user action. Nothing automatic changes
 * an include's form.
 *
 * One record carries two views of the same item, deliberately:
 *  - the MUTABLE side ([form], [condensedText], [artifactLine]) is what gets
 *    sent to the model today, and it changes as the user condenses/removes;
 *  - the SNAPSHOT side ([sentTokens]) records what this item weighed when the
 *    message carrying it was sent, so the transcript's own record of that
 *    turn never rewrites itself under the user.
 */
data class ChatInclude(
    val id: String,
    val fileName: String,
    val kind: IncludeKind,
    val form: IncludeForm,
    /** Extracted text, already size-guarded by [IncludeTextPolicy]. */
    val fullText: String,
    /** User-visible/editable condensed text; null until the user condenses. */
    val condensedText: String? = null,
    /** One-line bookmark; null until the user removes the item. */
    val artifactLine: String? = null,
    /** Which size notice (if any) this item must display. */
    val notice: IncludeNotice = IncludeNotice.None,
    /** Token estimate recorded when this item was first sent (0 if unsent). */
    val sentTokens: Int = 0
) {
    /** Estimated tokens of what would be SENT for this include right now. */
    fun currentTokens(): Int = IncludeTextPolicy.estimateTokens(modelText())

    /** Exactly the text the model should receive for this include today. */
    fun modelText(): String = when (form) {
        IncludeForm.FULL -> fullText
        IncludeForm.CONDENSED -> condensedText ?: fullText
        IncludeForm.ARTIFACT -> artifactLine ?: IncludeTextPolicy.fallbackArtifactLine(fileName)
    }

    /**
     * Whether this include shows a row in the strip above the message box.
     * The owner's rule: anything heavier than an artifact is a live data
     * drain, so it stays plainly visible; artifacts retire to the bookmark.
     */
    fun showsInStrip(): Boolean = form != IncludeForm.ARTIFACT

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, fileName)
        put(KEY_KIND, kind.key)
        put(KEY_FORM, form.key)
        put(KEY_FULL, fullText)
        if (condensedText != null) put(KEY_CONDENSED, condensedText)
        if (artifactLine != null) put(KEY_ARTIFACT, artifactLine)
        if (notice != IncludeNotice.None) put(KEY_NOTICE, notice.encode())
        if (sentTokens > 0) put(KEY_SENT_TOKENS, sentTokens)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_KIND = "kind"
        private const val KEY_FORM = "form"
        private const val KEY_FULL = "full"
        private const val KEY_CONDENSED = "condensed"
        private const val KEY_ARTIFACT = "artifact"
        private const val KEY_NOTICE = "notice"
        private const val KEY_SENT_TOKENS = "sentTokens"

        fun fromJson(o: JSONObject): ChatInclude? {
            val id = o.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: return null
            return ChatInclude(
                id = id,
                fileName = o.optString(KEY_NAME),
                kind = IncludeKind.fromKey(o.optString(KEY_KIND)),
                form = IncludeForm.fromKey(o.optString(KEY_FORM)),
                fullText = o.optString(KEY_FULL),
                condensedText = if (o.has(KEY_CONDENSED)) o.optString(KEY_CONDENSED) else null,
                artifactLine = if (o.has(KEY_ARTIFACT)) o.optString(KEY_ARTIFACT) else null,
                notice = IncludeNotice.decode(o.optString(KEY_NOTICE)),
                sentTokens = o.optInt(KEY_SENT_TOKENS, 0)
            )
        }

        fun listToJson(items: List<ChatInclude>): String {
            val arr = JSONArray()
            for (i in items) arr.put(i.toJson())
            return arr.toString()
        }

        /** Tolerant of anything malformed — a bad record is skipped, never
         *  allowed to take the whole list (or the chat) down with it. */
        fun listFromJson(raw: String?): List<ChatInclude> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<ChatInclude>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    fromJson(o)?.let { out.add(it) }
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/** File type of an include — drives which icon its row shows. */
enum class IncludeKind(val key: String) {
    TXT("txt"),
    MARKDOWN("md"),
    CSV("csv"),
    DOCX("docx"),
    IMAGE("image");

    companion object {
        fun fromKey(key: String?): IncludeKind =
            entries.firstOrNull { it.key == key } ?: TXT

        /** Maps a file name's extension to a kind, or null if unsupported. */
        fun fromFileName(fileName: String): IncludeKind? =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "txt" -> TXT
                "md", "markdown" -> MARKDOWN
                "csv" -> CSV
                "docx" -> DOCX
                else -> null
            }
    }
}

/** Which rung of the ladder an include currently sits on. */
enum class IncludeForm(val key: String) {
    FULL("full"),
    CONDENSED("condensed"),
    ARTIFACT("artifact");

    companion object {
        fun fromKey(key: String?): IncludeForm =
            entries.firstOrNull { it.key == key } ?: FULL
    }
}

/**
 * A size notice attached to an include, shown as persistent inline text under
 * its row (never a toast). Carries the numbers the owner-approved wording
 * needs so the strings can be filled in without recomputing anything.
 */
sealed class IncludeNotice {
    data object None : IncludeNotice()

    /** Large but sent whole. */
    data class Large(val tokens: Int) : IncludeNotice()

    /** Too large — only the beginning was included. */
    data class Truncated(val tokens: Int) : IncludeNotice()

    /** Oversized spreadsheet — header + [sentRows] of [totalRows]. */
    data class CsvTrimmed(val sentRows: Int, val totalRows: Int) : IncludeNotice()

    fun encode(): String = when (this) {
        is None -> ""
        is Large -> "large:$tokens"
        is Truncated -> "trunc:$tokens"
        is CsvTrimmed -> "csv:$sentRows:$totalRows"
    }

    companion object {
        fun decode(raw: String?): IncludeNotice {
            if (raw.isNullOrEmpty()) return None
            val parts = raw.split(":")
            return try {
                when (parts[0]) {
                    "large" -> Large(parts[1].toInt())
                    "trunc" -> Truncated(parts[1].toInt())
                    "csv" -> CsvTrimmed(parts[1].toInt(), parts[2].toInt())
                    else -> None
                }
            } catch (_: Exception) {
                None
            }
        }
    }
}
