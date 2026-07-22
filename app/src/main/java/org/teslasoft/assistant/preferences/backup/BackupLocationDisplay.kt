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

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * Human-readable backup location display (owner ruling 4, July 22 2026):
 * NEVER an encoded URI, document ID, account token, or other internal Android
 * value. Friendly names for the common providers; the Android-supplied label
 * for anything else; and for a one-file Save As grant — which often does not
 * expose its parent folder — the provider and filename, never an invented
 * path.
 *
 * The authority→friendly-name map is pure and unit-tested. [describeSaveAs]
 * is the one Android touchpoint (a ContentResolver query for the display
 * name), used after a Save As returns a single-document URI.
 */
object BackupLocationDisplay {

    /**
     * Friendly names for well-known SAF providers (owner-approved set). Returns
     * null for an unknown authority — the caller falls back to the OS-provided
     * provider label, never a raw authority string.
     */
    fun friendlyProvider(authority: String?): String? = when (authority) {
        "com.android.providers.downloads.documents" -> "Downloads"
        "com.google.android.apps.docs.storage",
        "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
        "com.microsoft.skydrive.content.StorageAccessProvider",
        "com.microsoft.skydrive.content.external" -> "OneDrive"
        "com.dropbox.product.android.dbapp.document_provider.documents" -> "Dropbox"
        else -> null
    }

    /** The provider label to show: the friendly name if known, otherwise the
     *  OS-provided package label for the authority, otherwise null (show only
     *  the filename). Never the raw authority. */
    fun providerLabel(context: Context, uri: Uri): String? {
        val authority = uri.authority
        friendlyProvider(authority)?.let { return it }
        if (authority == null) return null
        return try {
            val info = context.packageManager.resolveContentProvider(authority, 0) ?: return null
            info.loadLabel(context.packageManager).toString().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** The document's own display name (its filename) for a Save As URI. */
    fun displayName(context: Context, uri: Uri): String? {
        return try {
            var name: String? = null
            val cursor: Cursor? = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            cursor?.use { if (it.moveToFirst()) name = it.getString(0) }
            name ?: DocumentsContract.getDocumentId(uri)?.substringAfterLast('/')?.substringAfterLast(':')
        } catch (_: Exception) {
            null
        }
    }

    data class SaveAsDescription(val providerLabel: String?, val fileName: String?)

    /** Describe where a Save As landed: provider + filename. Either may be null
     *  when the OS does not expose it — the UI shows what it has and invents
     *  nothing. */
    fun describeSaveAs(context: Context, uri: Uri): SaveAsDescription =
        SaveAsDescription(providerLabel(context, uri), displayName(context, uri))

    /**
     * Pure display-label composition for a picked backup FOLDER (unit-tested):
     * cloud-provider name plus folder name where useful, the folder name alone
     * for local storage, the provider alone when the folder name is unknown,
     * and null when nothing human-readable exists — the caller then shows a
     * generic phrase. Never a URI, tree id, or authority string.
     */
    fun composeFolderLabel(providerFriendly: String?, folderName: String?): String? = when {
        providerFriendly != null && !folderName.isNullOrBlank() -> "$providerFriendly – $folderName"
        !folderName.isNullOrBlank() -> folderName
        providerFriendly != null -> providerFriendly
        else -> null
    }

    /**
     * Resolve a human-readable label for a SAF TREE uri at pick time (owner
     * correction, July 22 2026). The label is persisted separately from the
     * URI; on any failure this returns null and the UI falls back to a generic
     * phrase — NEVER the raw URI or the tree document id, which for cloud
     * providers is an encoded internal token, not a name.
     */
    fun treeFolderLabel(context: Context, treeUri: Uri): String? {
        val name = try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            var display: String? = null
            val cursor: Cursor? = context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )
            cursor?.use { if (it.moveToFirst() && !it.isNull(0)) display = it.getString(0) }
            display?.takeIf { looksLikeName(it) }
        } catch (_: Exception) {
            null
        }
        return composeFolderLabel(friendlyProvider(treeUri.authority), name)
    }

    /**
     * Guard against providers that report an internal token AS the display
     * name: anything that looks like an encoded id/URI is rejected so it can
     * never reach the screen (pure, unit-tested).
     */
    fun looksLikeName(candidate: String): Boolean {
        val c = candidate.trim()
        if (c.isEmpty()) return false
        if (c.contains("://")) return false
        // Key=value token shapes ("acc=2;doc=encoded=...") are ids, not names.
        if (Regex("^[A-Za-z0-9_]+=").containsMatchIn(c) && (c.contains(';') || c.contains("%3D") || c.contains("=="))) return false
        return true
    }
}
