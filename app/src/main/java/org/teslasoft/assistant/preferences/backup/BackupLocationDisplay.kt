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
 * The ONE centralized destination-display resolver for every backup/export
 * location in the app (Automatic Backup Location, Recovery Backup, the
 * Human-Readable Chat Backup, the Portable Data Copy, and any future
 * restore/backup-folder display) — owner directive, July 22 2026, breadcrumb
 * correction. A display value is NEVER an encoded URI, document ID, account
 * token, provider authority string, or other internal Android value; it is a
 * human-readable breadcrumb — "Provider > Parent Folder > Selected Folder" —
 * built by walking [DocumentsContract.findDocumentPath] and resolving each
 * document id to its DISPLAY_NAME through the provider. When the provider
 * cannot expose the full hierarchy the label degrades honestly through the
 * fallback ladder in [composeBreadcrumb] — it never invents a missing parent
 * name and never presents a leaf folder as though it were the full path.
 *
 * [composeBreadcrumb] and [looksLikeName] are pure and unit-tested. Every
 * other function here is the thin Android touchpoint (ContentResolver
 * queries) that feeds them.
 */
object BackupLocationDisplay {

    /** The one breadcrumb separator used everywhere (owner requirement 13) —
     *  never a hyphen or dash flattening provider and folder into one label. */
    const val SEPARATOR = " > "

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
     *  the folder/file name). Never the raw authority. */
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

    /** A folder/file document's DISPLAY_NAME queried directly through the
     *  provider (never the raw document id). */
    private fun queryDisplayName(context: Context, documentUri: Uri): String? {
        return try {
            var display: String? = null
            val cursor: Cursor? = context.contentResolver.query(
                documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )
            cursor?.use { if (it.moveToFirst() && !it.isNull(0)) display = it.getString(0) }
            display
        } catch (_: Exception) {
            null
        }
    }

    data class SaveAsDescription(
        val providerLabel: String?,
        val fileName: String?,
        /** The full breadcrumb for the FOLDER the file was saved into (never
         *  includes the filename itself, which is shown separately) — null
         *  when the provider exposes no more than the provider name. */
        val breadcrumb: String?
    )

    /** Describe where a Save As landed: provider, filename, and — where the
     *  provider exposes it — the parent-folder breadcrumb. Any field may be
     *  null when the OS does not expose it; nothing is ever invented. */
    fun describeSaveAs(context: Context, uri: Uri): SaveAsDescription {
        val provider = providerLabel(context, uri)
        val fileName = displayName(context, uri)
        val treeUri = try {
            if (DocumentsContract.isTreeUri(uri)) uri else null
        } catch (_: Exception) {
            null
        }
        val fullPath = resolvePathSegmentNames(context, uri, treeUri)
        // The resolved path includes the file itself as its last segment —
        // the breadcrumb here is the FOLDER the file landed in, so drop it.
        val folderSegments = fullPath?.let { if (it.size > 1) it.dropLast(1) else null }
        val breadcrumb = composeBreadcrumb(provider, folderSegments, fallbackLeaf = null)
        return SaveAsDescription(provider, fileName, breadcrumb)
    }

    /**
     * Resolve a human-readable BREADCRUMB for a picked SAF TREE uri at pick
     * time (owner correction, July 22 2026): "Provider > Parent > Selected
     * Folder", not just the leaf folder name. Walks
     * [DocumentsContract.findDocumentPath] from the tree's own document up to
     * the provider root, resolving each id to its DISPLAY_NAME via
     * [DocumentsContract.buildDocumentUriUsingTree]. The label is persisted
     * separately from the URI; on any failure this degrades through the
     * fallback ladder in [composeBreadcrumb] — NEVER the raw URI or the tree
     * document id, which for cloud providers is an encoded internal token,
     * not a name.
     */
    fun treeFolderLabel(context: Context, treeUri: Uri): String? {
        val provider = friendlyProvider(treeUri.authority) ?: providerLabel(context, treeUri)
        val docUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        } catch (_: Exception) {
            return composeBreadcrumb(provider, null, null)
        }
        val segments = resolvePathSegmentNames(context, docUri, treeUri)
        val leaf = queryDisplayName(context, docUri)?.takeIf { looksLikeName(it) }
        return composeBreadcrumb(provider, segments, leaf)
    }

    /**
     * Walk the provider's document path for [targetDocUri] (a tree's own
     * document, or a single Save As document) via
     * [DocumentsContract.findDocumentPath] and resolve every id ABOVE the
     * provider root to its DISPLAY_NAME, ending with [targetDocUri] itself as
     * the last element. Returns null when the provider does not support path
     * resolution, when the target IS the root (nothing to show beyond the
     * provider name), or when ANY segment fails to resolve to a real name —
     * a partial hierarchy that silently dropped a missing parent would
     * misrepresent where the target actually sits, so the whole breadcrumb is
     * abandoned rather than shown incomplete (the caller then falls back to
     * provider + leaf name alone).
     */
    private fun resolvePathSegmentNames(context: Context, targetDocUri: Uri, treeUri: Uri?): List<String>? {
        return try {
            val path = DocumentsContract.findDocumentPath(context.contentResolver, targetDocUri) ?: return null
            val ids = path.path
            if (ids.isNullOrEmpty() || ids.size <= 1) return null
            val authority = targetDocUri.authority ?: return null
            val names = ArrayList<String>(ids.size - 1)
            for (id in ids.drop(1)) {
                val segUri = if (treeUri != null) {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                } else {
                    DocumentsContract.buildDocumentUri(authority, id)
                }
                val name = queryDisplayName(context, segUri)?.takeIf { looksLikeName(it) } ?: return null
                names.add(name)
            }
            names
        } catch (_: Exception) {
            null
        }
    }

    /** The mid-tier truthful placeholder used inside a breadcrumb when a
     *  provider is known but the actual folder name cannot be safely shown
     *  (unresolved, or opaque) — "Google Drive > Selected folder", never a
     *  bare provider name on its own: a provider alone does not identify
     *  WHICH folder was picked, so it is never an acceptable final display. */
    const val GENERIC_FOLDER_NAME = "Selected folder"

    /**
     * Pure breadcrumb composition (unit-tested) implementing the required
     * fallback ladder in order:
     *  1. Provider > resolved breadcrumb (all [segments] resolved to names)
     *  2. Provider > selected folder — the real [fallbackLeaf] name when it
     *     resolves, otherwise the literal, truthful placeholder
     *     [GENERIC_FOLDER_NAME] ("Google Drive > Selected folder") — a
     *     provider is NEVER shown by itself, since that fails to identify
     *     which folder was chosen.
     *  3. Selected folder — [fallbackLeaf] alone when no provider label
     *     exists but the leaf name resolves.
     *  4. null — the caller shows its own generic "Selected folder location"
     *     phrase; reached only when NEITHER a provider NOR a folder name is
     *     known. Nothing here is ever invented.
     * Any segment or leaf name that does not [looksLikeName] (an opaque id,
     * an encoded token, a raw URI) is rejected outright rather than shown —
     * an opaque document id must never reach the screen.
     */
    fun composeBreadcrumb(provider: String?, segments: List<String>?, fallbackLeaf: String?): String? {
        val validSegments = segments?.takeIf { list -> list.isNotEmpty() && list.all { looksLikeName(it) } }
        val validLeaf = fallbackLeaf?.takeIf { looksLikeName(it) }
        return when {
            provider != null && validSegments != null -> (listOf(provider) + validSegments).joinToString(SEPARATOR)
            provider != null && validLeaf != null -> "$provider$SEPARATOR$validLeaf"
            provider != null -> "$provider$SEPARATOR$GENERIC_FOLDER_NAME"
            validLeaf != null -> validLeaf
            else -> null
        }
    }

    /** What a location line must show: no folder chosen yet, a chosen folder
     *  whose access grant was lost ("Folder unavailable. Choose a new
     *  location." — owner requirement 11, exact wording), or the resolved
     *  breadcrumb label. Pure and unit-tested; [org.teslasoft.assistant.ui.activities.MemoryBackupRestoreActivity]
     *  is the only caller that also knows the URI/label strings themselves. */
    enum class DestinationDisplayKind { NONE, UNAVAILABLE, LABELED }

    /** Classify a destination line from its persisted-URI presence and
     *  current SAF access grant, independent of what label (if any) is
     *  stored — an unavailable folder is unavailable regardless of a stale
     *  cached label, and a missing URI is "none" regardless of a leftover
     *  label from a previous pick. */
    fun classifyDestination(hasUri: Boolean, accessible: Boolean): DestinationDisplayKind = when {
        !hasUri -> DestinationDisplayKind.NONE
        !accessible -> DestinationDisplayKind.UNAVAILABLE
        else -> DestinationDisplayKind.LABELED
    }

    /** The two values a persisted backup destination is made of: the URI used
     *  for ACCESS and the breadcrumb used for DISPLAY, stored under separate
     *  keys (owner correction, July 22 2026) so the label survives a restart
     *  without ever being re-derived from — or confused with — the URI. */
    data class FolderDestination(val uri: String?, val label: String?)

    /**
     * What choosing a NEW folder must do to a persisted [FolderDestination]:
     * adopt the new URI immediately and clear any previous breadcrumb right
     * away, rather than leaving the OLD folder's label on screen while the
     * new one resolves in the background, or letting it survive into the
     * next app restart as though it still described the new folder. The
     * caller re-resolves and stores the real label once
     * [treeFolderLabel] returns.
     */
    fun applyFolderPick(newUri: String): FolderDestination = FolderDestination(uri = newUri, label = null)

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
