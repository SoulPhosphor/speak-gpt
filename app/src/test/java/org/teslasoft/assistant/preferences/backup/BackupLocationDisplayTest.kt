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
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure authority→friendly-name map (owner ruling 4): never a raw
 *  authority; unknown authorities fall through to the OS label (null here). */
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
}
