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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the default/per-chat Streaming preference split. */
class StreamingSettingsTest {

    private fun preferences(
        chatId: String,
        store: FakeSharedPreferences = FakeSharedPreferences()
    ): Preferences = Preferences(store, FakeSharedPreferences(), chatId)

    @Test
    fun missingValuePreservesTheExistingStreamingDefault() {
        assertTrue(preferences("new").getStreaming())
    }

    @Test
    fun globalDefaultCanBeChangedAndPersistsInItsOwnSettingsFile() {
        val store = FakeSharedPreferences()
        val global = preferences("", store)

        global.setStreaming(false)

        assertFalse(global.getStreaming())
        assertFalse(preferences("", store).getStreaming())
    }

    @Test
    fun eachChatRestoresItsOwnValueIndependently() {
        val chatAStore = FakeSharedPreferences()
        val chatA = preferences("chat-a", chatAStore)
        val chatB = preferences("chat-b")

        chatA.setStreaming(false)

        assertFalse(preferences("chat-a", chatAStore).getStreaming())
        assertTrue(chatB.getStreaming())
    }

    @Test
    fun aNewChatIsInitializedFromTheCurrentGlobalDefaultOnlyOnce() {
        val globalStore = FakeSharedPreferences()
        val global = preferences("", globalStore)
        global.setStreaming(false)

        val chatB = preferences("chat-b", FakeSharedPreferences())
        chatB.setStreaming(global.getStreaming())
        assertFalse(chatB.getStreaming())

        // A later global change must not retroactively change the chat.
        global.setStreaming(true)
        assertFalse(chatB.getStreaming())
    }

    @Test
    fun anOnDefaultIsStoredInTheNewChatAndSurvivesLaterGlobalChanges() {
        val globalStore = FakeSharedPreferences()
        val global = preferences("", globalStore)
        global.setStreaming(true)

        val chat = preferences("chat", FakeSharedPreferences())
        chat.setStreaming(global.getStreaming())

        global.setStreaming(false)
        assertTrue(chat.getStreaming())
    }
}
