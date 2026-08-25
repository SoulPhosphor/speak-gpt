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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for the settings new-chat creation copies from the default
 * profile (AddChatDialogFragment), in the same source-scan style as
 * PerChatSettingKeysTest. Pinned before the image generation rebuild
 * (image-generation-rebuild-plan.md, step 1): the rebuild's migration work
 * (plan sections 4.7 and 14) changes this list deliberately, and this test
 * makes any change to it a conscious one.
 */
class NewChatSettingCopyTest {

    private fun addChatDialogSource(): String {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AddChatDialogFragment.kt"),
            File("app/src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AddChatDialogFragment.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "AddChatDialogFragment.kt not found relative to the test working directory " +
                    File(".").absolutePath
            )
        return file.readText()
    }

    @Test
    fun newChatCopyListMatchesTheCurrentInventory() {
        val source = addChatDialogSource()
        val copied = Regex("""newPreferences\.set(\w+)\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { it != "Preferences" } // setPreferences binds the chat id, it copies nothing
            .toSortedSet()

        val expected = sortedSetOf(
            "ApiEndpointId",
            "AssistantName",
            "AudioModel",
            "AutoLangDetect",
            "AvatarId",
            "AvatarType",
            "EndSeparator",
            "FrequencyPenalty",
            // FunctionCalling was removed from the copy list with the whole
            // feature (image-generation-rebuild-plan.md §15) — a deliberate
            // migration-era change, not drift.
            // ImagineCommand was removed from the copy list once its
            // per-chat value had no remaining reader anywhere in the app —
            // likewise deliberate, not drift.
            "LogitBiasesConfigId",
            "MaxTokens",
            "Model",
            "OpenAITtsModel",
            "OpenAIVoice",
            "Prefix",
            "PresencePenalty",
            "Prompt",
            "Resolution",
            "Streaming",
            "SystemMessage",
            "Temperature",
            "TopP",
            "TtsEngine",
            "Voice"
        )

        assertEquals(
            "The set of settings new-chat creation copies changed. If this is part of the " +
                "image generation rebuild's migration (plan section 14), update this " +
                "inventory in the same change; otherwise investigate the drift.",
            expected,
            copied
        )
    }

    @Test
    fun inheritanceIsInitializedBeforeTheNewChatOpens() {
        val source = addChatDialogSource()
        val reset = source.indexOf("newPreferences.resetNewChatInheritance()")
        val lastSettingsWrite = source.indexOf("newPreferences.setAssistantName")
        val open = source.indexOf("listener?.onAdd")

        assertTrue("New-chat inheritance must be reset before opening the chat", reset >= 0 && reset < open)
        assertTrue("All new-chat settings must be in memory before opening the chat", lastSettingsWrite >= 0 && lastSettingsWrite < open)
    }

    /** The known gap the rebuild's app-wide settings make moot (plan 4.7):
     *  today a new chat copies the legacy resolution setting but
     *  never the selected image model. */
    @Test
    fun imageModelIsNotCopiedToNewChats() {
        assertFalse(addChatDialogSource().contains("newPreferences.setImageModel("))
    }
}
