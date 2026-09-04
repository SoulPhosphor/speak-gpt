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

    /* ---- manifest cross-check (Phase 9.2) ---- */

    /** Builds the exact hashed-entry set a well-formed archive carries for the
     *  given chat ids: the chat list plus each chat's history and settings. */
    private fun entriesFor(vararg chatIds: String): Set<String> {
        val set = linkedSetOf(ChatRestorePlanner.CHAT_LIST_ENTRY)
        for (id in chatIds) {
            set.add("enc.chat_$id.xml")
            set.add("enc.settings.$id.xml")
        }
        return set
    }

    @Test
    fun theReaderVersionMatchesTheProducer() {
        // The reader must understand exactly the version the snapshot writes;
        // a silent divergence is precisely what UNSUPPORTED_VERSION guards, so
        // it must never be introduced by a producer bump left un-mirrored here.
        assertEquals(
            ChatSnapshotManifest.MANIFEST_VERSION,
            ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION
        )
    }

    @Test
    fun aCoherentManifestHasNoDefect() {
        assertNull(
            ChatRestorePlanner.manifestDefect(
                manifestVersion = ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                chatIds = listOf("1a2b3c4d", "de305d54-75b4-431b-adb2-eb6b9e546014"),
                hashedEntryNames = entriesFor("1a2b3c4d", "de305d54-75b4-431b-adb2-eb6b9e546014")
            )
        )
    }

    @Test
    fun anEmptyChatSetIsCoherentIfTheListIsPresent() {
        // A backup of an account with no chats is a chat list and nothing else.
        assertNull(
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                emptyList(),
                setOf(ChatRestorePlanner.CHAT_LIST_ENTRY)
            )
        )
    }

    @Test
    fun anAbsentOrUnknownVersionIsRejected() {
        assertEquals(
            ChatRestorePlanner.ManifestDefect.UNSUPPORTED_VERSION,
            ChatRestorePlanner.manifestDefect(null, listOf("a1"), entriesFor("a1"))
        )
        assertEquals(
            ChatRestorePlanner.ManifestDefect.UNSUPPORTED_VERSION,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION + 1, listOf("a1"), entriesFor("a1")
            )
        )
    }

    @Test
    fun anArchiveWithNoChatListIsRejected() {
        assertEquals(
            ChatRestorePlanner.ManifestDefect.MISSING_CHAT_LIST,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("a1"),
                setOf("enc.chat_a1.xml", "enc.settings.a1.xml")
            )
        )
    }

    @Test
    fun aChatMissingItsHistoryOrSettingsIsRejected() {
        assertEquals(
            ChatRestorePlanner.ManifestDefect.CHAT_MISSING_HISTORY,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("a1"),
                setOf(ChatRestorePlanner.CHAT_LIST_ENTRY, "enc.settings.a1.xml")
            )
        )
        assertEquals(
            ChatRestorePlanner.ManifestDefect.CHAT_MISSING_SETTINGS,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("a1"),
                setOf(ChatRestorePlanner.CHAT_LIST_ENTRY, "enc.chat_a1.xml")
            )
        )
    }

    @Test
    fun aPerChatFileForAnUndeclaredChatIsRejected() {
        // The manifest declares a1, but the archive also carries a2's files.
        assertEquals(
            ChatRestorePlanner.ManifestDefect.UNLISTED_CHAT_FILE,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("a1"),
                entriesFor("a1") + setOf("enc.chat_a2.xml", "enc.settings.a2.xml")
            )
        )
    }

    @Test
    fun aDuplicateChatIdIsRejected() {
        assertEquals(
            ChatRestorePlanner.ManifestDefect.DUPLICATE_CHAT_ID,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("a1", "a1"),
                entriesFor("a1")
            )
        )
    }

    @Test
    fun anUnsafeChatIdIsRejected() {
        assertEquals(
            ChatRestorePlanner.ManifestDefect.UNSAFE_CHAT_ID,
            ChatRestorePlanner.manifestDefect(
                ChatRestorePlanner.SUPPORTED_MANIFEST_VERSION,
                listOf("../evil"),
                setOf(ChatRestorePlanner.CHAT_LIST_ENTRY)
            )
        )
    }
}
