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

package org.teslasoft.assistant.preferences.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.dto.LoreBookEntry
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore

/**
 * Real SQLCipher coverage for memory-id hardening: canonical minting,
 * id/created_at immutability across edits, non-reuse of deleted ids, and the
 * import same-record vs. identity-collision rule end to end. Runs on an arm64
 * device/emulator (the app ships arm64-only native code); the format and
 * disposition LOGIC these depend on is additionally covered by the JVM unit
 * tests (MemoryIdTest, MemoryIdImportTest) that DO run in CI.
 */
@RunWith(AndroidJUnit4::class)
class MemoryIdHardeningInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "id-hardening-test-key".toByteArray()
    private val dbNames = ArrayList<String>()
    private var seq = 0

    @After
    fun cleanup() {
        for (name in dbNames) {
            try { ctx.getDatabasePath(name).delete() } catch (_: Exception) {}
        }
    }

    private fun freshDbName(): String {
        val name = "id-hardening-${System.nanoTime()}-${seq++}.db"
        dbNames.add(name)
        return name
    }

    private fun open(name: String): MemoryStore = MemoryStore.openForTest(ctx, name, key)
    private fun openLore(name: String): LoreBookStore = LoreBookStore.openForTest(ctx, name, key)

    private fun mem(createdAt: String, content: String = "a fact"): MemoryRecord = MemoryRecord(
        memoryId = MemoryId.generate(MemoryId.Type.ASSOCIATIVE),
        scope = "global", content = content, embeddingText = null, tagsJson = "[]",
        importance = 0, worldIds = emptyList(), roleplayCharacterIds = emptyList(),
        campaignIds = emptyList(), projectIds = emptyList(), protectionJson = null,
        modeHintsJson = "[]", createdAt = createdAt, updatedAt = null, status = "active",
        supersedes = null, companionIds = emptyList(), entityRefs = emptyList(),
        changeLog = emptyList(), origin = "user", typeId = null
    )

    /* ---- associative memories -------------------------------------------- */

    @Test
    fun twoNewMemoriesGetDifferentCanonicalIds() {
        val store = open(freshDbName())
        val a = mem("2026-01-01T00:00:00Z")
        val b = mem("2026-01-01T00:00:00Z")
        store.insertMemory(a)
        store.insertMemory(b)
        assertNotEquals(a.memoryId, b.memoryId)
        assertTrue(MemoryId.isCanonical(a.memoryId, MemoryId.Type.ASSOCIATIVE))
        assertNotNull(store.getMemory(a.memoryId))
        assertNotNull(store.getMemory(b.memoryId))
    }

    @Test
    fun editingMemoryPreservesIdAndCreatedAt() {
        val store = open(freshDbName())
        val rec = mem("2026-01-01T00:00:00Z", content = "original")
        store.insertMemory(rec)
        // Edit content AND attempt to change created_at via the caller record.
        store.updateMemory(rec.copy(content = "edited", createdAt = "2099-12-31T00:00:00Z"), null)
        val after = store.getMemory(rec.memoryId)!!
        assertEquals(rec.memoryId, after.memoryId)
        assertEquals("edited", after.content)
        assertEquals("2026-01-01T00:00:00Z", after.createdAt) // immutable
    }

    @Test
    fun deletedMemoryIdIsNeverReusedByNewCreation() {
        val store = open(freshDbName())
        val rec = mem("2026-01-01T00:00:00Z")
        store.insertMemory(rec)
        store.deleteMemory(rec.memoryId)
        try {
            store.insertMemory(rec.copy(content = "different content, same id"))
            fail("expected refusal to reuse a deleted memory id")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun importPreservesIdSkipsSameRecordAndRejectsCollision() {
        val src = open(freshDbName())
        val rec = mem("2026-01-01T00:00:00Z", content = "the original")
        src.insertMemory(rec)
        val data = src.exportData()

        val dest = open(freshDbName())
        val added = dest.importData(data, overwriteSingletons = true)
        assertEquals(1, added.added["memories"])
        assertNotNull(dest.getMemory(rec.memoryId)) // id preserved

        // Re-import the SAME record (birth timestamp matches) → preserved/skipped.
        val again = dest.importData(data, overwriteSingletons = false)
        assertEquals(1, again.skipped["memories"])
        assertNull(again.conflicts["memories"])

        // A DIFFERENT record wearing the same id (different birth) → collision,
        // reported distinctly, and the stored record is NOT overwritten.
        val colliding = data.copy(
            memories = data.memories.map { it.copy(content = "impostor", createdAt = "2026-09-09T00:00:00Z") }
        )
        val conflict = dest.importData(colliding, overwriteSingletons = false)
        assertEquals(1, conflict.conflicts["memories"])
        assertEquals("the original", dest.getMemory(rec.memoryId)!!.content)
    }

    @Test
    fun restoreOfDeletedMemoryAdmittedButDifferentRecordRejected() {
        val store = open(freshDbName())
        val rec = mem("2026-01-01T00:00:00Z", content = "kept")
        store.insertMemory(rec)
        val backup = store.exportData()          // captured before deletion
        store.deleteMemory(rec.memoryId)          // tombstoned with created_at

        // Restoring the SAME record (matching birth timestamp) is admitted.
        val restore = store.importData(backup, overwriteSingletons = false)
        assertEquals(1, restore.added["memories"])
        assertNotNull(store.getMemory(rec.memoryId))

        // Delete again, then a DIFFERENT record reusing the freed id is refused.
        store.deleteMemory(rec.memoryId)
        val impostor = backup.copy(
            memories = backup.memories.map { it.copy(content = "impostor", createdAt = "2026-09-09T00:00:00Z") }
        )
        val conflict = store.importData(impostor, overwriteSingletons = false)
        assertEquals(1, conflict.conflicts["memories"])
        assertNull(store.getMemory(rec.memoryId))
    }

    /* ---- lorebook entries ------------------------------------------------ */

    @Test
    fun twoNewLorebookEntriesGetDifferentCanonicalIds() {
        val store = openLore(freshDbName())
        val a = store.saveEntry(LoreBookEntry(label = "a", content = "one"))
        val b = store.saveEntry(LoreBookEntry(label = "b", content = "two"))
        assertNotEquals(a.id, b.id)
        assertTrue(MemoryId.isCanonical(a.id, MemoryId.Type.LOREBOOK))
        assertTrue(MemoryId.isCanonical(b.id, MemoryId.Type.LOREBOOK))
    }

    @Test
    fun editingLorebookEntryPreservesId() {
        val store = openLore(freshDbName())
        val created = store.saveEntry(LoreBookEntry(label = "x", content = "before"))
        val edited = store.saveEntry(created.copy(content = "after"))
        assertEquals(created.id, edited.id)
        assertEquals("after", store.getEntry(created.id)!!.content)
    }
}
