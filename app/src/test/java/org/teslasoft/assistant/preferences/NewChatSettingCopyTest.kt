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
 * profile (NewConversationCoordinator), in the same source-scan style as
 * PerChatSettingKeysTest. Pinned before the image generation rebuild
 * (image-generation-rebuild-plan.md, step 1): the rebuild's migration work
 * (plan sections 4.7 and 14) changes this list deliberately, and this test
 * makes any change to it a conscious one.
 */
class NewChatSettingCopyTest {

    private fun coordinatorSource(): String {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant/conversation/NewConversationCoordinator.kt"),
            File("app/src/main/java/org/teslasoft/assistant/conversation/NewConversationCoordinator.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "NewConversationCoordinator.kt not found relative to the test working directory " +
                    File(".").absolutePath
            )
        return file.readText()
    }

    @Test
    fun newChatCopyListMatchesTheCurrentInventory() {
        val source = coordinatorSource()
        val copied = Regex("""created\.set(\w+)\(""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { it != "Preferences" } // setPreferences binds the chat id, it copies nothing
            .toSortedSet()

        val expected = sortedSetOf(
            "ApiEndpointId",
            "AssistantName",
            // AudioModel (the speech-to-text engine) became a single global
            // setting: a new chat no longer copies it or writes a cached
            // per-chat default back — a deliberate change, not drift.
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
            // Voice identity and engine compatibility are global; new chats
            // must neither copy them nor write a cached default back.
            "Prefix",
            "PresencePenalty",
            "Prompt",
            "Resolution",
            "Streaming",
            "SystemMessage",
            "Temperature",
            "TopP"
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
        val source = coordinatorSource()
        val createBody = source.substringAfter("fun createPendingConversation(")
            .substringBefore("fun commitPendingConversation(")
        val initialize = createBody.indexOf("initializeSettings(chatId, request)")
        val pendingMarker = createBody.indexOf("ConversationMode.PENDING_KEY")
        val open = createBody.indexOf("return PendingConversationState")

        val initializeBody = source.substringAfter("private fun initializeSettings(")
        val reset = initializeBody.indexOf("created.resetNewChatInheritance()")
        val quickSettings = initializeBody.indexOf("created.initializeNewChatQuickSettings()")
        val lastSettingsWrite = initializeBody.indexOf("created.setAssistantName")

        assertTrue("New-chat inheritance must be reset during initialization", reset >= 0)
        assertTrue(
            "New-chat Quick Settings must resolve after stale inheritance is reset",
            quickSettings > reset
        )
        assertTrue("All copied settings must follow inheritance initialization", lastSettingsWrite > quickSettings)
        assertTrue(
            "Settings initialization and the pending marker must finish before the provisional chat opens",
            initialize >= 0 && pendingMarker > initialize && open > pendingMarker
        )
    }

    /** The known gap the rebuild's app-wide settings make moot (plan 4.7):
     *  today a new chat copies the legacy resolution setting but
     *  never the selected image model. */
    @Test
    fun imageModelIsNotCopiedToNewChats() {
        assertFalse(coordinatorSource().contains("created.setImageModel("))
    }
}
