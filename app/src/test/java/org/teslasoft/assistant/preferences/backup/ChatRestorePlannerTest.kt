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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-restore swap journal's recovery decision and the strict archive
 * entry whitelist (Build Phase 3 item 5). The invariants: an interrupted
 * swap with complete staging is FINISHED (never half-rolled-back); a stage
 * that never began swapping is discarded with the live files untouched; and
 * no archive can plant a file outside chat storage.
 */
class ChatRestorePlannerTest {

    /* ---- recovery decision ---- */

    @Test
    fun noJournalMeansNothing() {
        assertEquals(ChatRestorePlanner.Recovery.NOTHING, ChatRestorePlanner.planRecovery(null, false))
        assertEquals(ChatRestorePlanner.Recovery.NOTHING, ChatRestorePlanner.planRecovery("", true))
    }

    @Test
    fun interruptedSwapWithStagingResumes() {
        assertEquals(
            ChatRestorePlanner.Recovery.RESUME_SWAP,
            ChatRestorePlanner.planRecovery(ChatRestorePlanner.PHASE_SWAPPING, stagingComplete = true)
        )
    }

    @Test
    fun interruptedSwapWithoutStagingIsUnrecoverable() {
        assertEquals(
            ChatRestorePlanner.Recovery.UNRECOVERABLE,
            ChatRestorePlanner.planRecovery(ChatRestorePlanner.PHASE_SWAPPING, stagingComplete = false)
        )
    }

    @Test
    fun stagedButNeverSwappedIsDiscarded() {
        assertEquals(
            ChatRestorePlanner.Recovery.DISCARD_STAGING,
            ChatRestorePlanner.planRecovery(ChatRestorePlanner.PHASE_STAGED, stagingComplete = true)
        )
    }

    @Test
    fun unknownPhaseIsTreatedConservativelyAsDiscard() {
        assertEquals(
            ChatRestorePlanner.Recovery.DISCARD_STAGING,
            ChatRestorePlanner.planRecovery("future_phase", stagingComplete = true)
        )
    }

    /* ---- entry whitelist ---- */

    @Test
    fun acceptsExactlyTheChatStorageShapes() {
        assertTrue(ChatRestorePlanner.isAllowedEntryName("enc.chat_list.xml"))
        assertTrue(ChatRestorePlanner.isAllowedEntryName("enc.chat_1a2b3c4d.xml"))
        assertTrue(ChatRestorePlanner.isAllowedEntryName("enc.settings.1a2b3c4d.xml"))
    }

    @Test
    fun rejectsOtherPrefsFiles() {
        // Other shared_prefs tenants must never be replaceable by an archive.
        assertFalse(ChatRestorePlanner.isAllowedEntryName("enc.rename_journal.xml"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("settings.xml"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("storage_health.xml"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("enc.api_endpoint.xml"))
    }

    @Test
    fun rejectsPathTraversal() {
        assertFalse(ChatRestorePlanner.isAllowedEntryName("../enc.chat_list.xml"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("enc.chat_list.xml/../evil"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("..\\enc.chat_list.xml"))
        assertFalse(ChatRestorePlanner.isAllowedEntryName("dir/enc.chat_list.xml"))
    }

    @Test
    fun liveFileFilterMatchesTheSameShapes() {
        assertTrue(ChatRestorePlanner.isChatStorageFileName("enc.chat_list.xml"))
        assertFalse(ChatRestorePlanner.isChatStorageFileName("enc.other_store.xml"))
    }
}
