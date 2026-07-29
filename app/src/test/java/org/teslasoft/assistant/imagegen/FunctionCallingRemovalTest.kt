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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §15 of image-generation-rebuild-plan.md, kept true by a source scan in
 * the style of PerChatSettingKeysTest: the old Function Calling feature is
 * gone — the hidden gpt-4o routing request, the generateImage /
 * searchAtInternet function map and search stub, the settings tile, and
 * every read of the old preference outside the one migration-only reader.
 * A chat that had Function Calling enabled now behaves like any other
 * chat, including normal summarizer transmission — guaranteed structurally
 * because ChatActivity no longer reads the preference at all.
 */
class FunctionCallingRemovalTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
        return file.readText()
    }

    private val chatActivity =
        "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
    private val settingsActivity =
        "src/main/java/org/teslasoft/assistant/ui/activities/SettingsActivity.kt"
    private val preferences =
        "src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"
    private val addChatDialog =
        "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AddChatDialogFragment.kt"

    @Test
    fun theHiddenRoutingModelIsGoneFromTheChatPipeline() {
        // Acceptance 1: no hard-coded gpt-4o ROUTING request. (The chat
        // auto-naming fallback legitimately mentions the model name; the
        // router's signature was constructing ModelId("gpt-4o") for a
        // tool-bearing request, together with the function map below.)
        assertFalse(source(chatActivity).contains("ModelId(\"gpt-4o\")"))
    }

    @Test
    fun theFunctionMapAndSearchStubAreGone() {
        val chat = source(chatActivity)
        assertFalse(chat.contains("availableFunctions"))
        assertFalse(chat.contains("searchAtInternet"))
        assertFalse(chat.contains("Searching at Google"))
    }

    @Test
    fun nothingWritesTheOldPreferenceAnyMore() {
        for (path in listOf(chatActivity, settingsActivity, addChatDialog, preferences)) {
            assertFalse(
                "$path still writes function_calling",
                source(path).contains("setFunctionCalling(")
            )
        }
    }

    @Test
    fun onlyTheMigrationReaderTouchesTheStoredValue() {
        // ChatActivity reading nothing is what makes a formerly
        // Function-Calling chat an ordinary chat — summarizer transmission
        // included (§15's test consequence).
        assertFalse(source(chatActivity).contains("FunctionCalling"))
        assertFalse(source(settingsActivity).contains("FunctionCalling"))
        assertFalse(source(addChatDialog).contains("FunctionCalling("))
        assertTrue(source(preferences).contains("getLegacyFunctionCallingForMigration"))
    }

    @Test
    fun theLegacyImageClientsAreGone() {
        // §16 step 14 also removes the duplicate image clients: the
        // model-name-gated GPT-Image/DALL-E pair and the OpenAI-only
        // wording that rode along with them.
        val chat = source(chatActivity)
        assertFalse(chat.contains("generateImageR"))
        assertFalse(chat.contains("ImageGenerateParams"))
        assertFalse(chat.contains("DALL-E image generation is disabled"))
    }
}
