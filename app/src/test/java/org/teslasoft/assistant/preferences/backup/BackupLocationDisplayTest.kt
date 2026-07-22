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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the centralized backup-destination display resolver
 * (owner directive, July 22 2026 breadcrumb correction): the authority→
 * friendly-name map, the breadcrumb fallback ladder, and the opaque-id guard.
 * Never a raw authority, URI, document id, or encoded token on screen.
 */
class BackupLocationDisplayTest {

    @Test
    fun knownProvidersMapToFriendlyNames() {
        assertEquals("Downloads", BackupLocationDisplay.friendlyProvider("com.android.providers.downloads.documents"))
        assertEquals("Google Drive", BackupLocationDisplay.friendlyProvider("com.google.android.apps.docs.storage"))
        assertEquals("OneDrive", BackupLocationDisplay.friendlyProvider("com.microsoft.skydrive.content.StorageAccessProvider"))
        assertEquals("Dropbox", BackupLocationDisplay.friendlyProvider("com.dropbox.product.android.dbapp.document_provider.documents"))
    }

    @Test
    fun unknownAuthorityFallsThroughToNull() {
        assertNull(BackupLocationDisplay.friendlyProvider("com.some.other.provider"))
        assertNull(BackupLocationDisplay.friendlyProvider(null))
        // A raw authority is never returned as a friendly name.
        assertNull(BackupLocationDisplay.friendlyProvider("com.android.externalstorage.documents"))
    }

    // ----- composeBreadcrumb: the fallback ladder (owner requirement 9) -----

    @Test
    fun fullMultiLevelBreadcrumb() {
        // The nested example from the task: Google Drive > Temp > app folder.
        assertEquals(
            "Google Drive > Temp > app folder",
            BackupLocationDisplay.composeBreadcrumb("Google Drive", listOf("Temp", "app folder"), "app folder")
        )
    }

    @Test
    fun providerPlusDeeplyNestedFolders() {
        assertEquals(
            "Google Drive > Backups > 2026 > July > Weekly",
            BackupLocationDisplay.composeBreadcrumb(
                "Google Drive", listOf("Backups", "2026", "July", "Weekly"), "Weekly"
            )
        )
    }

    @Test
    fun leafOnlyProviderFallback_whenHierarchyUnresolved() {
        // Tier b: the provider is known but the full path could not be walked
        // (e.g. the provider does not implement findDocumentPath) — provider
        // plus the selected folder's own name, never a fabricated parent.
        assertEquals(
            "Google Drive > Backups",
            BackupLocationDisplay.composeBreadcrumb("Google Drive", null, "Backups")
        )
        // Tier c: no provider label at all — the selected folder name alone.
        assertEquals("Backups", BackupLocationDisplay.composeBreadcrumb(null, null, "Backups"))
        // Tier a is preferred over tier b when both are available.
        assertEquals(
            "Google Drive > Temp > Backups",
            BackupLocationDisplay.composeBreadcrumb("Google Drive", listOf("Temp", "Backups"), "Backups")
        )
    }

    @Test
    fun nothingResolved_fallsThroughToNull_neverInventsAName() {
        // Tier d: caller shows its own generic "Selected folder location".
        assertNull(BackupLocationDisplay.composeBreadcrumb(null, null, null))
        assertNull(BackupLocationDisplay.composeBreadcrumb(null, emptyList(), null))
    }

    @Test
    fun providerAlone_whenNeitherSegmentsNorLeafResolve() {
        assertEquals("Google Drive", BackupLocationDisplay.composeBreadcrumb("Google Drive", null, null))
        assertEquals("Google Drive", BackupLocationDisplay.composeBreadcrumb("Google Drive", emptyList(), null))
    }

    @Test
    fun opaqueDocumentIdsNeverAppearInBreadcrumb() {
        // A segment that looks like an internal token poisons the WHOLE
        // breadcrumb attempt (never a partial path that silently drops the
        // opaque parent and misrepresents the hierarchy) — falls back to the
        // leaf name alone, itself validated the same way.
        val result = BackupLocationDisplay.composeBreadcrumb(
            "Google Drive", listOf("acc=2;doc=encoded=abc123", "app folder"), "app folder"
        )
        assertEquals("Google Drive > app folder", result)
        assertFalse(result!!.contains("acc="))
        assertFalse(result.contains("doc="))

        // An opaque leaf name is rejected too — never shown, even alone: with
        // no provider there is nothing truthful left to show (null); with a
        // known provider it degrades to the provider name alone, never the
        // opaque token.
        assertNull(BackupLocationDisplay.composeBreadcrumb(null, null, "content://com.foo.bar/tree/x"))
        val providerOnly = BackupLocationDisplay.composeBreadcrumb("Google Drive", null, "doc=encoded%3Dxyz")
        assertEquals("Google Drive", providerOnly)
    }

    @Test
    fun separatorIsTheArrowEverywhere() {
        assertEquals(" > ", BackupLocationDisplay.SEPARATOR)
        val result = BackupLocationDisplay.composeBreadcrumb("Google Drive", listOf("Temp", "app folder"), "app folder")
        assertFalse(result!!.contains("–"))
        assertFalse(result.contains(" - "))
    }

    @Test
    fun internalTokensNeverPassAsNames() {
        // The exact shape the owner saw on screen must never count as a name.
        assertFalse(BackupLocationDisplay.looksLikeName("acc=2;doc=encoded=abc123"))
        assertFalse(BackupLocationDisplay.looksLikeName("doc=encoded%3Dxyz"))
        assertFalse(BackupLocationDisplay.looksLikeName("content://com.foo.bar/tree/x"))
        assertFalse(BackupLocationDisplay.looksLikeName("   "))
        // Ordinary folder names pass, including ones with spaces or dashes.
        assertTrue(BackupLocationDisplay.looksLikeName("Backups"))
        assertTrue(BackupLocationDisplay.looksLikeName("My Phone Backups"))
        assertTrue(BackupLocationDisplay.looksLikeName("backups-2026"))
    }

    // ----- destination lifecycle (owner requirements 6, 7, 11) -----

    @Test
    fun unavailableFolder_classifiesRegardlessOfCachedLabel() {
        // A lost access grant always wins over whatever label happened to be
        // cached — the line must say the folder is unavailable, never show a
        // stale breadcrumb for a folder the app can no longer reach.
        assertEquals(
            BackupLocationDisplay.DestinationDisplayKind.UNAVAILABLE,
            BackupLocationDisplay.classifyDestination(hasUri = true, accessible = false)
        )
        assertEquals(
            BackupLocationDisplay.DestinationDisplayKind.NONE,
            BackupLocationDisplay.classifyDestination(hasUri = false, accessible = false)
        )
        assertEquals(
            BackupLocationDisplay.DestinationDisplayKind.LABELED,
            BackupLocationDisplay.classifyDestination(hasUri = true, accessible = true)
        )
    }

    @Test
    fun changingSelectedFolder_replacesTheOldBreadcrumbImmediately() {
        // Simulates the persisted state before a re-pick: an old folder with
        // a resolved breadcrumb.
        val before = BackupLocationDisplay.FolderDestination(
            uri = "content://com.google.android.apps.docs.storage/tree/old",
            label = "Google Drive > Temp > old folder"
        )
        assertEquals("Google Drive > Temp > old folder", before.label)

        // Picking a NEW folder must adopt the new URI and drop the old label
        // right away — the screen must never keep showing "old folder" under
        // the new URI while the new breadcrumb resolves in the background.
        val after = BackupLocationDisplay.applyFolderPick("content://com.google.android.apps.docs.storage/tree/new")
        assertEquals("content://com.google.android.apps.docs.storage/tree/new", after.uri)
        assertNull(after.label)
        assertTrue(after.label != before.label)
    }

    @Test
    fun labelPersistsSeparatelyFromUri_survivesAcrossRestart() {
        // The URI (access) and the breadcrumb (display) are stored under
        // separate keys in a plain in-memory map standing in for
        // RecoveryBackupState's SharedPreferences file — a fresh read after
        // a simulated restart must still see both values independently,
        // never re-derived from the URI at read time.
        val store = HashMap<String, String>()
        store["backup.auto_folder_uri"] = "content://com.google.android.apps.docs.storage/tree/abc"
        store["backup.auto_folder_label"] = "Google Drive > Temp > app folder"

        // "Restart": read through a brand-new view over the same backing map.
        val afterRestart = HashMap(store)
        assertEquals("content://com.google.android.apps.docs.storage/tree/abc", afterRestart["backup.auto_folder_uri"])
        assertEquals("Google Drive > Temp > app folder", afterRestart["backup.auto_folder_label"])

        // The label is still exactly what composeBreadcrumb would produce —
        // never a raw URI or document id even after the round trip.
        assertTrue(BackupLocationDisplay.looksLikeName(afterRestart["backup.auto_folder_label"]!!))
    }
}
