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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * CI source-scan guard (chat-id-stable-identity-plan.md §6, same pattern as
 * PerChatSettingKeysTest): every `Hash.hash(` occurrence in the app's main
 * sources is pinned to an explicit allowlist. Chat ids must come from
 * ChatIdentity.effectiveId — a new call site that hashes a chat NAME to
 * derive its id is exactly the dual-source bug this work removes, and this
 * test makes such a site fail the build instead of shipping.
 *
 * The count is raw (comments included) and per file, deliberately blunt: any
 * new or moved occurrence forces a conscious allowlist edit in the same
 * commit, where a reviewer can see whether the use hashes a chat name
 * (forbidden outside the sanctioned sites) or something unrelated (image
 * bytes, legacy label ids), which merely needs the allowlist updated.
 */
class ChatIdHashGuardTest {

    /**
     * file (relative to the java source root) -> expected occurrences of
     * "Hash.hash(". Keep the categories honest when editing:
     *  - chat-identity sanctioned: the resolver's legacy fallback plus the
     *    legacy chat creation/rename paths (removed by the stable-creation
     *    commit of the plan).
     *  - non-chat uses: byte hashing (images) and legacy label-derived ids
     *    of OTHER record types (endpoints, logit bias, system prompts).
     *  - comments that mention the call literally.
     */
    private val allowlist = mapOf(
        // Chat identity — sanctioned.
        "org/teslasoft/assistant/util/ChatIdentity.kt" to 1,          // the resolver's legacy fallback
        "org/teslasoft/assistant/preferences/ChatPreferences.kt" to 5, // legacy addChat (2), legacy editChat (2), checkDuplicate (1)
        "org/teslasoft/assistant/ui/fragments/dialogs/AddChatDialogFragment.kt" to 4, // legacy create/rename paths
        "org/teslasoft/assistant/ui/activities/ChatActivity.kt" to 5,  // 4 image-byte hashes + the legacy auto-name rename

        // Non-chat uses (bytes or other record types' legacy ids).
        "org/teslasoft/assistant/preferences/Preferences.kt" to 1,
        "org/teslasoft/assistant/preferences/dto/ApiEndpointObject.kt" to 1,
        "org/teslasoft/assistant/preferences/LogitBiasPreferences.kt" to 3,
        "org/teslasoft/assistant/preferences/SystemPromptsPreferences.kt" to 1,
        "org/teslasoft/assistant/preferences/profileimages/ProfileImageStore.kt" to 1,
        "org/teslasoft/assistant/preferences/profileimages/ProfileImageFileStore.kt" to 1,
        "org/teslasoft/assistant/ui/fragments/dialogs/CustomizeAssistantDialog.kt" to 1,

        // Comment-only mentions.
        "org/teslasoft/assistant/util/StableId.kt" to 1,
        "org/teslasoft/assistant/preferences/memory/MemoryCompanionSync.kt" to 1,
        "org/teslasoft/assistant/preferences/LogitBiasConfigPreferences.kt" to 1
    )

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "main source root not found relative to the test working directory " +
                    File(".").absolutePath
            )
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun everyHashHashCallSiteIsPinned() {
        val root = sourceRoot()
        val actual = HashMap<String, Int>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val n = countOccurrences(file.readText(), "Hash.hash(")
                if (n > 0) actual[file.relativeTo(root).invariantSeparatorsPath] = n
            }

        assertEquals(
            "The set of files containing Hash.hash( changed. If a new site derives a CHAT id " +
                "from a chat name, convert it to ChatIdentity.effectiveId instead — that recompute " +
                "is the dual-source identity bug. If the use is genuinely unrelated to chat names " +
                "(byte hashing, another record type), update the allowlist in this test in the same " +
                "commit so the change is reviewed.",
            allowlist.toSortedMap().toString(),
            actual.toSortedMap().toString()
        )
    }
}
