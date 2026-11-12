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


package org.teslasoft.assistant.ui.adapters.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState

class ChatErrorAvatarStateTest {
    private fun user() = mapOf<String, Any?>("isBot" to false)
    private fun bot(state: String) = mapOf<String, Any?>("isBot" to true, MessageCompletionState.KEY_STATE to state)

    @Test
    fun aFirstFailedReplyTurnsTheErrorAvatarOn() {
        val chat = mutableListOf(user())
        val state = ChatErrorAvatarState(chat)
        assertFalse(state.showsError)
        chat += bot(MessageCompletionState.FAILED)
        assertTrue(state.update(chat))
        assertTrue(state.showsError)
    }

    @Test
    fun aFirstCompletedReplyTurnsItOffSoEarlierRowsAreRedrawn() {
        val chat = mutableListOf(user(), bot(MessageCompletionState.STOPPED), user(), bot(MessageCompletionState.FAILED))
        val state = ChatErrorAvatarState(chat)
        assertTrue(state.showsError)
        chat += user()
        chat += bot(MessageCompletionState.DONE)
        assertTrue(state.update(chat))
        assertFalse(state.showsError)
    }

    @Test
    fun noChangeMeansNoRedraw() {
        val chat = mutableListOf(user(), bot(MessageCompletionState.DONE))
        val state = ChatErrorAvatarState(chat)
        chat += user()
        chat += bot(MessageCompletionState.FAILED)
        assertFalse(state.update(chat))
        assertFalse(state.showsError)
    }
}
