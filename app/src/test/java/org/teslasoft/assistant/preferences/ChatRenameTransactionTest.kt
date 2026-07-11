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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rename ladder's contract: a rename either fully applies or changes
 * NOTHING — the old chat (history, settings, list pointer) stays intact on
 * every failure and at every interruption boundary. These tests drive the
 * pure transaction against an in-memory store with failure injection and a
 * kill-at-every-write simulation.
 */
class ChatRenameTransactionTest {

    private class Killed : RuntimeException("simulated process death")

    private class FakeFiles : ChatRenameTransaction.FileAccess {
        val files = HashMap<String, LinkedHashMap<String, Any?>>()
        val failReplaceAll = HashSet<String>()
        val failWriteString = HashSet<String>()
        val failClear = HashSet<String>()
        val corruptReadsOf = HashSet<String>()
        var writesUntilKill = Int.MAX_VALUE
        private var writesDone = 0

        fun file(name: String): LinkedHashMap<String, Any?> = files.getOrPut(name) { LinkedHashMap() }

        private fun beforeWrite() {
            if (writesDone >= writesUntilKill) throw Killed()
            writesDone++
        }

        override fun readAll(fileName: String): Map<String, Any?> {
            val copy = LinkedHashMap<String, Any?>(file(fileName))
            if (fileName in corruptReadsOf && copy.isNotEmpty()) {
                copy[copy.keys.first()] = "CORRUPTED"
            }
            return copy
        }

        override fun readString(fileName: String, key: String): String? {
            val v = file(fileName)[key] as? String ?: return null
            return if (fileName in corruptReadsOf) "$v-CORRUPTED" else v
        }

        override fun replaceAll(fileName: String, entries: Map<String, Any?>): Boolean {
            if (fileName in failReplaceAll) return false
            beforeWrite()
            val f = file(fileName)
            f.clear()
            f.putAll(entries)
            return true
        }

        override fun writeString(fileName: String, key: String, value: String): Boolean {
            if (fileName in failWriteString) return false
            beforeWrite()
            file(fileName)[key] = value
            return true
        }

        override fun clear(fileName: String): Boolean {
            if (fileName in failClear) return false
            beforeWrite()
            file(fileName).clear()
            return true
        }
    }

    private val oldId = "oldhash"
    private val newId = "newhash"
    private val history = """[{"message":"hello there","isBot":"false"}]"""
    private val settings: Map<String, Any?> = mapOf(
        "model" to "glm-4-plus",
        "persona_id" to "persona42",
        "memory_enabled" to "", // tri-state "follow global" must survive as ""
        "persona_activation_seeded" to true,
        "max_tokens" to "1500",
        "voice" to "en-us-x-iom-network",
        "an_int" to 7,
        "a_long" to 7L,
        "a_float" to 0.7f,
        "a_set" to setOf("a", "b")
    )
    private val oldListJson = """[{"name":"Old","id":"oldhash"}]"""
    private val newListJson = """[{"name":"New","id":"newhash"}]"""

    private fun freshFake(): FakeFiles {
        val fake = FakeFiles()
        fake.file(ChatRenameTransaction.chatFile(oldId))["chat"] = history
        fake.file(ChatRenameTransaction.settingsFile(oldId)).putAll(settings)
        fake.file(ChatRenameTransaction.CHAT_LIST_FILE)[ChatRenameTransaction.CHAT_LIST_KEY] = oldListJson
        return fake
    }

    private fun rename(fake: FakeFiles, from: String = oldId, to: String = newId) =
        ChatRenameTransaction.rename(fake, from, to, newListJson)

    private fun listNow(fake: FakeFiles): String? =
        fake.files[ChatRenameTransaction.CHAT_LIST_FILE]?.get(ChatRenameTransaction.CHAT_LIST_KEY) as? String

    private fun assertOldFullyIntact(fake: FakeFiles) {
        assertEquals(history, fake.files[ChatRenameTransaction.chatFile(oldId)]?.get("chat"))
        assertEquals(settings, fake.files[ChatRenameTransaction.settingsFile(oldId)]?.toMap())
        assertEquals(oldListJson, listNow(fake))
    }

    private fun assertNewSideComplete(fake: FakeFiles) {
        assertEquals(history, fake.files[ChatRenameTransaction.chatFile(newId)]?.get("chat"))
        assertEquals(settings, fake.files[ChatRenameTransaction.settingsFile(newId)]?.toMap())
    }

    @Test
    fun successMovesHistoryCopiesSettingsAndFlipsPointer() {
        val fake = freshFake()
        val outcome = rename(fake)
        assertTrue(outcome.success)
        assertNull(outcome.failedStage)
        assertTrue(outcome.warnings.isEmpty())
        assertNewSideComplete(fake)
        assertEquals(newListJson, listNow(fake))
        // Old history cleared only after everything else was durable.
        assertTrue(fake.files[ChatRenameTransaction.chatFile(oldId)]?.isEmpty() == true)
    }

    @Test
    fun settingsAreCopiedVerbatimNeverReDerived() {
        val fake = freshFake()
        rename(fake)
        val copied = fake.files[ChatRenameTransaction.settingsFile(newId)]!!
        // Exact values, exact types, and NOTHING extra or missing: the copy is
        // wholesale, so per-chat tuning can't be replaced by profile defaults
        // and unset keys stay unset.
        assertEquals(settings, copied.toMap())
        assertEquals("", copied["memory_enabled"])
        assertFalse(copied.containsKey("some_key_the_chat_never_set"))
    }

    @Test
    fun staleKeysUnderTheTargetIdDoNotSurvive() {
        // A previously deleted chat with the same name hash may have left
        // settings under the target file; they must not leak into the
        // renamed chat.
        val fake = freshFake()
        fake.file(ChatRenameTransaction.settingsFile(newId))["stale_key"] = "zombie"
        rename(fake)
        assertFalse(fake.files[ChatRenameTransaction.settingsFile(newId)]!!.containsKey("stale_key"))
    }

    @Test
    fun copierCarriesEveryRegisteredPerChatKey() {
        // Ties the audited inventory (PerChatSettingKeys) to the mechanism:
        // every registered key present on the old chat arrives on the new one.
        val allKeys = PerChatSettingKeys.ALL.associateWith { "value-of-$it" }
        val fake = FakeFiles()
        fake.file(ChatRenameTransaction.chatFile(oldId))["chat"] = history
        fake.file(ChatRenameTransaction.settingsFile(oldId)).putAll(allKeys)
        fake.file(ChatRenameTransaction.CHAT_LIST_FILE)[ChatRenameTransaction.CHAT_LIST_KEY] = oldListJson

        assertTrue(rename(fake).success)

        val copied = fake.files[ChatRenameTransaction.settingsFile(newId)]!!
        for (key in PerChatSettingKeys.ALL) {
            assertEquals("per-chat key '$key' must survive a rename", "value-of-$key", copied[key])
        }
    }

    @Test
    fun failedNewChatWriteLeavesEverythingUntouched() {
        val fake = freshFake()
        fake.failReplaceAll.add(ChatRenameTransaction.chatFile(newId))
        val outcome = rename(fake)
        assertFalse(outcome.success)
        assertEquals(ChatRenameTransaction.STAGE_WRITE_NEW_CHAT, outcome.failedStage)
        assertOldFullyIntact(fake)
    }

    @Test
    fun failedNewChatVerificationAborts() {
        val fake = freshFake()
        fake.corruptReadsOf.add(ChatRenameTransaction.chatFile(newId))
        val outcome = rename(fake)
        assertFalse(outcome.success)
        assertEquals(ChatRenameTransaction.STAGE_VERIFY_NEW_CHAT, outcome.failedStage)
        assertOldFullyIntact(fake)
    }

    @Test
    fun failedSettingsCopyAbortsAndCleansUp() {
        val fake = freshFake()
        fake.failReplaceAll.add(ChatRenameTransaction.settingsFile(newId))
        val outcome = rename(fake)
        assertFalse(outcome.success)
        assertEquals(ChatRenameTransaction.STAGE_COPY_SETTINGS, outcome.failedStage)
        assertOldFullyIntact(fake)
        // The half-created new chat file was removed on abort.
        assertTrue(fake.files[ChatRenameTransaction.chatFile(newId)]?.isEmpty() != false)
    }

    @Test
    fun failedSettingsVerificationAborts() {
        val fake = freshFake()
        fake.corruptReadsOf.add(ChatRenameTransaction.settingsFile(newId))
        val outcome = rename(fake)
        assertFalse(outcome.success)
        assertEquals(ChatRenameTransaction.STAGE_VERIFY_SETTINGS, outcome.failedStage)
        assertOldFullyIntact(fake)
    }

    @Test
    fun failedListWriteAbortsWithOldChatIntact() {
        val fake = freshFake()
        fake.failWriteString.add(ChatRenameTransaction.CHAT_LIST_FILE)
        val outcome = rename(fake)
        assertFalse(outcome.success)
        assertEquals(ChatRenameTransaction.STAGE_WRITE_LIST, outcome.failedStage)
        assertOldFullyIntact(fake)
    }

    @Test
    fun failedOldClearIsAWarningNotAFailure() {
        val fake = freshFake()
        fake.failClear.add(ChatRenameTransaction.chatFile(oldId))
        val outcome = rename(fake)
        // The rename IS applied — only the cleanup of the orphaned old file
        // failed, which risks no data.
        assertTrue(outcome.success)
        assertTrue(outcome.warnings.isNotEmpty())
        assertNewSideComplete(fake)
        assertEquals(newListJson, listNow(fake))
        assertEquals(history, fake.files[ChatRenameTransaction.chatFile(oldId)]?.get("chat"))
    }

    @Test
    fun identicalOrBlankIdsAreRefused() {
        val fake = freshFake()
        assertFalse(rename(fake, oldId, oldId).success)
        assertFalse(rename(fake, oldId, "").success)
        assertFalse(rename(fake, "", newId).success)
        assertOldFullyIntact(fake)
    }

    @Test
    fun renamingANeverWrittenChatStillProducesAValidHistory() {
        val fake = FakeFiles()
        fake.file(ChatRenameTransaction.CHAT_LIST_FILE)[ChatRenameTransaction.CHAT_LIST_KEY] = oldListJson
        val outcome = rename(fake)
        assertTrue(outcome.success)
        assertEquals("[]", fake.files[ChatRenameTransaction.chatFile(newId)]?.get("chat"))
    }

    @Test
    fun interruptionAtEveryWriteBoundaryLeavesARecoverableState() {
        // The success path performs 4 writes (new history, new settings, list
        // pointer, old clear). Kill the process before each one in turn and
        // assert the on-disk state is always one of the two legal states:
        // "still fully the old chat" or "fully the new chat".
        for (killAt in 0..4) {
            val fake = freshFake()
            fake.writesUntilKill = killAt
            try {
                rename(fake)
            } catch (_: Killed) {
                // simulated process death — only the on-disk state matters now
            }
            val list = listNow(fake)
            if (list == newListJson) {
                assertNewSideComplete(fake)
            } else {
                assertEquals("kill boundary $killAt: pointer must still be the old list", oldListJson, list)
                assertEquals(
                    "kill boundary $killAt: old history must be intact",
                    history, fake.files[ChatRenameTransaction.chatFile(oldId)]?.get("chat")
                )
                assertEquals(
                    "kill boundary $killAt: old settings must be intact",
                    settings, fake.files[ChatRenameTransaction.settingsFile(oldId)]?.toMap()
                )
            }
        }
    }
}
