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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for the per-chat settings inventory. Preferences.kt stores
 * per-chat values through its private non-Global helpers (getString /
 * putString / getBoolean / putBoolean, plus the direct SecurePrefs
 * "settings.<id>" reads); global values go through the *Global* helpers.
 * This test scans the source for literal keys passed to the per-chat
 * helpers and compares them against PerChatSettingKeys.ALL, so adding a
 * per-chat setting without registering it fails the build. The rename copy
 * itself is wholesale (ChatRenameTransaction copies the whole file), so the
 * registry is the audited inventory, not the copy list — but history showed
 * hand-maintained lists silently drifting, and this keeps the inventory
 * honest.
 */
class PerChatSettingKeysTest {

    private fun preferencesSource(): String {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"),
            File("app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "Preferences.kt not found relative to the test working directory " +
                    File(".").absolutePath
            )
        return file.readText()
    }

    @Test
    fun registryMatchesThePerChatKeysUsedInPreferences() {
        val source = preferencesSource()
        // A literal first argument to a per-chat helper is a real stored key.
        // The Global helpers have different names, and the helper definitions
        // themselves pass a variable, so neither matches.
        val regex = Regex("""(?<![A-Za-z])(?:getString|putString|getBoolean|putBoolean)\(\s*"([^"]+)"""")
        val usedKeys = regex.findAll(source).map { it.groupValues[1] }.toSortedSet()
        val registered = PerChatSettingKeys.ALL.toSortedSet()

        val missing = usedKeys - registered
        val stale = registered - usedKeys

        assertTrue(
            "Per-chat keys stored in Preferences.kt but missing from PerChatSettingKeys.ALL — " +
                "register them so the rename inventory stays truthful: $missing",
            missing.isEmpty()
        )
        assertTrue(
            "Keys registered in PerChatSettingKeys.ALL but no longer stored by Preferences.kt — " +
                "remove them from the registry: $stale",
            stale.isEmpty()
        )
    }
}
