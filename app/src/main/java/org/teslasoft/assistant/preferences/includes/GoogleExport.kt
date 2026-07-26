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
 * Decides how a Google-native document (a Doc or a Sheet) becomes a file this
 * app can already read.
 *
 * A Google Doc is not a file. It has no bytes of its own — Android calls such
 * an entry a "virtual document" — so it cannot be opened the way a .txt or a
 * .docx on disk is opened. What it does have is a list of formats the Google
 * Drive app is willing to CONVERT it into on request. Android reports that
 * list, and this object picks one from it.
 *
 * Docs become .docx and Sheets become .xlsx, so that everything after this
 * point is an ordinary file import: the same extraction, the same size
 * guards, the same duplicate protection, the same storage.
 *
 * Google Slides is deliberately absent. There is no presentation extractor in
 * this app, so a Slides file is refused by name rather than being picked up
 * and then failing further down with a vaguer reason.
 *
 * Nothing here talks to Google. No Drive API, no OAuth scope, no account: the
 * conversion is performed by whatever Drive app is installed and signed in on
 * the device, and this app only ever sees the resulting bytes.
 *
 * Pure logic with no Android types, so all of it is unit-tested.
 */
object GoogleExport {

    /** MIME type Drive reports for a Google Doc. */
    const val NATIVE_DOCUMENT = "application/vnd.google-apps.document"

    /** MIME type Drive reports for a Google Sheet. */
    const val NATIVE_SPREADSHEET = "application/vnd.google-apps.spreadsheet"

    /** MIME type Drive reports for Google Slides — knowingly unsupported. */
    const val NATIVE_PRESENTATION = "application/vnd.google-apps.presentation"

    const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** The format a Google-native document will be converted into. */
    data class Export(val mimeType: String, val extension: String)

    private val DOCX_EXPORT = Export(DOCX_MIME, "docx")
    private val XLSX_EXPORT = Export(XLSX_MIME, "xlsx")

    /** The Google-native types offered to the system file picker. */
    val PICKER_MIME_TYPES = arrayOf(NATIVE_DOCUMENT, NATIVE_SPREADSHEET)

    /** Whether this is a Google-native type this app knows how to export. */
    fun isSupportedNative(mimeType: String?): Boolean =
        mimeType == NATIVE_DOCUMENT || mimeType == NATIVE_SPREADSHEET

    /**
     * Whether this is a Google-native type deliberately NOT supported.
     * Slides is filtered out of the picker, so this is a second line of
     * defence for a file that arrives by some other route — it earns a
     * plain "not supported" refusal instead of an obscure failure later.
     */
    fun isUnsupportedNative(mimeType: String?): Boolean =
        mimeType != null &&
            mimeType.startsWith("application/vnd.google-apps.") &&
            !isSupportedNative(mimeType)

    /**
     * The format to request for [mimeType], or null when this app cannot use
     * anything Drive is offering.
     *
     * [offered] is what Android reports the document can be converted into.
     * The wanted format must actually appear there — Drive's export list is
     * not guaranteed by any documentation this app can rely on, so it is
     * checked rather than assumed. A null or empty list means the document
     * cannot be exported at all right now.
     */
    fun chooseExport(mimeType: String?, offered: Array<String>?): Export? {
        val wanted = when (mimeType) {
            NATIVE_DOCUMENT -> DOCX_EXPORT
            NATIVE_SPREADSHEET -> XLSX_EXPORT
            else -> return null
        }
        if (offered == null) return null
        val match = offered.any { it.trim().equals(wanted.mimeType, ignoreCase = true) }
        return if (match) wanted else null
    }

    /**
     * The name the attachment carries once exported.
     *
     * A Google Doc's name has no file extension — the picker reports
     * "Quarterly Plan", not "Quarterly Plan.docx" — because the underlying
     * document genuinely has no file type until it is converted. Left alone,
     * the attachment would be labelled with a name that says nothing about
     * what was actually sent, and the importer's own extension check would
     * have nothing to read. So the exported extension is appended when the
     * name does not already end in it.
     */
    fun exportedFileName(displayName: String, export: Export): String {
        val base = displayName.trim().ifEmpty { "document" }
        val suffix = ".${export.extension}"
        return if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix
    }
}
