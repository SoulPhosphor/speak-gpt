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
import org.teslasoft.assistant.preferences.RenameJournal.Action
import org.teslasoft.assistant.preferences.RenameJournal.Pending

/**
 * The cross-store recovery decision at every boundary. `editChat` moves the
 * prefs (history/settings/pointer) and then re-points the memory DB; a death
 * or DB failure in between leaves a journal entry that startup recovery must
 * resolve WITHOUT trusting the entry blindly — it consults the live chat list.
 * These tests pin that decision (the pure [RenameJournal.planReconcile]) plus a
 * fake-executor simulation of interruption, DB exception, and retry-after-
 * restart, proving transcripts are never lost or duplicated.
 */
class RenameJournalReconcileTest {

    private val old = "oldid"
    private val new = "newid"
    private fun one() = listOf(Pending(old, new))

    // ---- pure decision at each boundary -----------------------------------

    @Test fun renameBecameAuthoritative_repoints() {
        // new id live, old id gone, store exists → finish the memory re-point.
        val plan = RenameJournal.planReconcile(one(), setOf(new), provisioned = true)
        assertEquals(listOf(Action.Repoint(old, new)), plan)
    }

    @Test fun renameNeverTook_discardsWithoutRepoint() {
        // old id still live (died before the pointer flip / rename failed) →
        // transcripts belong to the still-live old chat; never re-point.
        val plan = RenameJournal.planReconcile(one(), setOf(old), provisioned = true)
        assertEquals(listOf(Action.Discard(old, new)), plan)
    }

    @Test fun chatDeletedAfterRename_discards() {
        // neither id live → the re-point is moot.
        val plan = RenameJournal.planReconcile(one(), emptySet(), provisioned = true)
        assertEquals(listOf(Action.Discard(old, new)), plan)
    }

    @Test fun oldIdReusedByNewChat_neverRepoints() {
        // both live: the old id was reused by a brand-new chat. Re-pointing
        // would STEAL that chat's rows — must discard.
        val plan = RenameJournal.planReconcile(one(), setOf(old, new), provisioned = true)
        assertEquals(listOf(Action.Discard(old, new)), plan)
    }

    @Test fun noMemoryStore_discardsInsteadOfRepointing() {
        // Store absent → there are no transcripts to move; drop the entry.
        val plan = RenameJournal.planReconcile(one(), setOf(new), provisioned = false)
        assertEquals(listOf(Action.Discard(old, new)), plan)
    }

    @Test fun emptyJournalPlansNothing() {
        assertTrue(RenameJournal.planReconcile(emptyList(), setOf(new), true).isEmpty())
    }

    // ---- executor simulation: interruption, DB failure, retry -------------

    /** A fake memory store keyed by chat id, with optional injected failures. */
    private class FakeStore {
        val transcripts = HashMap<String, Int>()   // chatId -> row count
        var failTimes = 0                            // repoints that throw before succeeding
        var repointCalls = 0

        fun repoint(o: String, n: String) {
            repointCalls++
            if (failTimes > 0) { failTimes--; throw RuntimeException("db locked") }
            // idempotent, atomic: move all rows o -> n; a second run finds none.
            val moved = transcripts.remove(o) ?: 0
            if (moved > 0) transcripts[n] = (transcripts[n] ?: 0) + moved
        }
    }

    /** Run one recovery pass over a mutable journal, mirroring
     *  RenameJournal.reconcile's execute step (Repoint→move+clear, but keep the
     *  entry if the move throws; Discard→clear). */
    private fun runRecovery(journal: MutableList<Pending>, liveIds: Set<String>, store: FakeStore?) {
        val plan = RenameJournal.planReconcile(journal.toList(), liveIds, store != null)
        for (action in plan) {
            when (action) {
                is Action.Repoint -> try {
                    store!!.repoint(action.old, action.new)
                    journal.removeAll { it.old == action.old && it.new == action.new }
                } catch (_: Exception) {
                    /* keep the entry for the next pass */
                }
                is Action.Discard -> journal.removeAll { it.old == action.old && it.new == action.new }
            }
        }
    }

    @Test fun interruptedAfterPointerFlip_recoveryMovesTranscripts() {
        val store = FakeStore().apply { transcripts[old] = 3 }
        val journal = mutableListOf(Pending(old, new)) // entry survived the crash
        // The renamed chat is live under the new id; old id is gone.
        runRecovery(journal, setOf(new), store)
        assertEquals("transcripts moved to the renamed chat", 3, store.transcripts[new])
        assertFalse("old id no longer holds rows", store.transcripts.containsKey(old))
        assertTrue("journal entry cleared after success", journal.isEmpty())
    }

    @Test fun databaseExceptionKeepsEntryThenRetrySucceeds() {
        val store = FakeStore().apply { transcripts[old] = 2; failTimes = 1 }
        val journal = mutableListOf(Pending(old, new))

        // First restart: the DB throws → entry retained, transcripts untouched.
        runRecovery(journal, setOf(new), store)
        assertEquals(1, journal.size)
        assertEquals("transcripts preserved under old id after a failed retry", 2, store.transcripts[old])

        // Next restart: DB healthy → completes and clears.
        runRecovery(journal, setOf(new), store)
        assertTrue(journal.isEmpty())
        assertEquals(2, store.transcripts[new])
        assertFalse(store.transcripts.containsKey(old))
    }

    @Test fun repeatedRecoveryIsIdempotent() {
        val store = FakeStore().apply { transcripts[old] = 5 }
        val journal = mutableListOf(Pending(old, new))
        runRecovery(journal, setOf(new), store)   // moves + clears
        val callsAfterFirst = store.repointCalls
        // Re-run several times: journal empty → no more work, counts unchanged.
        repeat(3) { runRecovery(journal, setOf(new), store) }
        assertEquals(callsAfterFirst, store.repointCalls)
        assertEquals(5, store.transcripts[new])
        assertTrue(journal.isEmpty())
    }

    @Test fun transcriptsPreservedWhenRenameNeverBecameAuthoritative() {
        // Interrupted BEFORE the pointer flip: old chat still live. Recovery
        // must NOT move rows; the transcripts stay with the still-live old chat.
        val store = FakeStore().apply { transcripts[old] = 4 }
        val journal = mutableListOf(Pending(old, new))
        runRecovery(journal, setOf(old), store)
        assertEquals("transcripts stay under the still-live old chat", 4, store.transcripts[old])
        assertFalse(store.transcripts.containsKey(new))
        assertTrue("stale entry discarded", journal.isEmpty())
        assertEquals("no re-point attempted", 0, store.repointCalls)
    }

    @Test fun reusedOldIdIsNeverClobbered() {
        // old id reused by a fresh chat (its own transcripts under old); the
        // renamed chat is also live under new. Recovery must not touch either.
        val store = FakeStore().apply { transcripts[old] = 9 } // the NEW chat's rows
        val journal = mutableListOf(Pending(old, new))
        runRecovery(journal, setOf(old, new), store)
        assertEquals("reused old id's rows untouched", 9, store.transcripts[old])
        assertFalse(store.transcripts.containsKey(new))
        assertEquals(0, store.repointCalls)
        assertTrue(journal.isEmpty())
    }
}
