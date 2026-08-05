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

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.memory.librarian.RetrievalDocument

/**
 * Real SQLCipher-backed migration and deletion coverage for the Phase 1 storage
 * work (canonical recovery plan §5, §7, §8.10, §4.6). These are instrumentation
 * tests: they open the actual [MemoryStore] against throwaway database files via
 * [MemoryStore.openForTest], so onCreate (fresh v21), onUpgrade (from a
 * hand-built v20 database), import, and companion deletion all execute against
 * genuine encrypted SQLite — not a pure mapping stand-in.
 *
 * The app ships arm64-only native code, so these run on an arm64 device or
 * emulator via `gradlew connectedAndroidTest`. A standard x86_64 CI emulator
 * cannot install the APK; the pure decision logic they depend on
 * (MemoryTypeMigration, AnalysisRunReconciler, MemorySeedCodec) is additionally
 * covered by the JVM unit tests that DO run in CI.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "phase1-instrumented-test-key".toByteArray()
    private val dbNames = ArrayList<String>()

    @Before
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @After
    fun cleanup() {
        for (name in dbNames) {
            try { ctx.getDatabasePath(name).delete() } catch (_: Exception) {}
            try { ctx.getDatabasePath("$name-wal").delete() } catch (_: Exception) {}
            try { ctx.getDatabasePath("$name-shm").delete() } catch (_: Exception) {}
        }
    }

    private fun freshDbName(): String {
        val name = "phase1_test_${System.nanoTime()}.db"
        dbNames.add(name)
        return name
    }

    private fun open(name: String): MemoryStore = MemoryStore.openForTest(ctx, name, key)

    /* ------------------------------ fresh v21 ------------------------------ */

    @Test
    fun freshV21_seedsFiveStarterTypes() {
        val store = open(freshDbName())
        val names = store.getMemoryTypes().map { it.name }
        assertEquals(listOf("Fact", "Preference", "Event", "Status", "Instruction"), names)
    }

    @Test
    fun freshV21_newMemoryDefaultsImportanceToZero() {
        val store = open(freshDbName())
        // Raw insert omitting importance exercises the COLUMN default, proving a
        // fresh store defaults new memories to the neutral 0 (§7).
        store.writableDatabase.execSQL(
            "INSERT INTO memories (memory_id, scope, content, created_at, status) " +
                "VALUES ('m-fresh', 'global', 'hello', '2026-08-04T00:00:00Z', 'active')"
        )
        assertEquals(0, importanceOf(store, "m-fresh"))
    }

    /* --------------------------- upgrade from v20 --------------------------- */

    @Test
    fun upgradeFromV20_mapsKindsPreservesImportance_loreAndUnknownBecomeNoType() {
        val name = freshDbName()
        buildV20Database(name) { db ->
            insertV20Memory(db, "m-fact", "fact", 4)
            insertV20Memory(db, "m-pref", "preference", 2)
            insertV20Memory(db, "m-event", "event", 5)
            insertV20Memory(db, "m-status", "status", 1)
            insertV20Memory(db, "m-inst", "instruction", 3)
            insertV20Memory(db, "m-lore", "lore", 2)
            insertV20Memory(db, "m-weird", "spell", 5)
            insertV20Memory(db, "m-blank", "", 4)
        }

        // Opening at v21 runs onUpgrade(20 -> 21).
        val store = open(name)

        assertEquals("mtype-fact", typeIdOf(store, "m-fact"))
        assertEquals("mtype-preference", typeIdOf(store, "m-pref"))
        assertEquals("mtype-event", typeIdOf(store, "m-event"))
        assertEquals("mtype-status", typeIdOf(store, "m-status"))
        assertEquals("mtype-instruction", typeIdOf(store, "m-inst"))
        // Lore is not a Type; an unknown or blank kind is not a Type either.
        assertNull(typeIdOf(store, "m-lore"))
        assertNull(typeIdOf(store, "m-weird"))
        assertNull(typeIdOf(store, "m-blank"))

        // Every existing importance value is preserved verbatim.
        assertEquals(4, importanceOf(store, "m-fact"))
        assertEquals(2, importanceOf(store, "m-pref"))
        assertEquals(5, importanceOf(store, "m-event"))
        assertEquals(1, importanceOf(store, "m-status"))
        assertEquals(3, importanceOf(store, "m-inst"))
        assertEquals(2, importanceOf(store, "m-lore"))

        // The five starter Types exist after the upgrade.
        assertEquals(5, store.getMemoryTypes().size)

        // An upgraded database also defaults NEW memories to 0 (not the legacy 3).
        store.writableDatabase.execSQL(
            "INSERT INTO memories (memory_id, scope, content, created_at, status) " +
                "VALUES ('m-new', 'global', 'after upgrade', '2026-08-04T00:00:00Z', 'active')"
        )
        assertEquals(0, importanceOf(store, "m-new"))
    }

    /* --------------------------- scopes and joins --------------------------- */

    @Test
    fun scopesAndTargetJoinsSurviveInsertAndRead() {
        val store = open(freshDbName())
        store.insertCompanion(companion("c-1", "Ash"))
        store.writableDatabase.execSQL("INSERT INTO worlds (world_id, name, premise, status) VALUES ('w-1','World','p','active')")
        store.writableDatabase.execSQL("INSERT INTO campaigns (campaign_id, name, status) VALUES ('camp-1','Camp','active')")
        store.writableDatabase.execSQL("INSERT INTO roleplay_characters (roleplay_character_id, name, played_by, description, worlds_played_json, status) VALUES ('rc-1','Mara','user','d','[]','active')")

        store.insertMemory(mem("g", scope = "global"))
        store.insertMemory(mem("c", scope = "companion", companionIds = listOf("c-1")))
        store.insertMemory(mem("w", scope = "world", worldIds = listOf("w-1")))
        store.insertMemory(mem("cm", scope = "campaign", campaignIds = listOf("camp-1")))
        store.insertMemory(mem("rc", scope = "rp_character", roleplayCharacterIds = listOf("rc-1")))

        assertEquals(listOf("c-1"), store.getMemory("c")!!.companionIds)
        assertEquals(listOf("w-1"), store.getMemory("w")!!.worldIds)
        assertEquals(listOf("camp-1"), store.getMemory("cm")!!.campaignIds)
        assertEquals(listOf("rc-1"), store.getMemory("rc")!!.roleplayCharacterIds)
        assertEquals("global", store.getMemory("g")!!.scope)
    }

    /* ---------------------- companion deletion cascade ---------------------- */

    @Test
    fun companionDeletionCascadesSoleOwned_keepsSharedAndGeneral_andTempCandidates() {
        val store = open(freshDbName())
        store.insertCompanion(companion("c-a", "Ash"))
        store.insertCompanion(companion("c-b", "Blue"))

        // Sole-owned companion memories in EVERY lifecycle state.
        for (status in listOf("draft", "active", "archived", "superseded")) {
            store.insertMemory(mem("sole-$status", scope = "companion", companionIds = listOf("c-a"), status = status))
            store.upsertEmbedding("sole-$status", "test-model", byteArrayOf(1, 2, 3))
        }
        // Shared with another companion — must survive, linked to c-b.
        store.insertMemory(mem("shared", scope = "companion", companionIds = listOf("c-a", "c-b")))
        // A General memory (no companion link) proposed from c-a's chats — kept.
        store.insertMemory(mem("general", scope = "global"))

        // Temporary analysis candidates: one targeting c-a (must go), one general.
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_run_state (run_id, filed, created_at) VALUES ('run-1', 0, '2026-08-04T00:00:00Z')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_candidates (candidate_id, run_id, target_type, target_id, payload_json, created_at) " +
                "VALUES ('cand-a', 'run-1', 'companion', 'c-a', '{}', '2026-08-04T00:00:00Z')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_candidates (candidate_id, run_id, target_type, target_id, payload_json, created_at) " +
                "VALUES ('cand-gen', 'run-1', NULL, NULL, '{}', '2026-08-04T00:00:00Z')"
        )

        assertEquals(4, store.companionSoleOwnedMemoryCount("c-a"))

        store.deleteCompanion("c-a", deleteMemories = true)

        // Every sole-owned companion memory (all four lifecycle states) is gone.
        for (status in listOf("draft", "active", "archived", "superseded")) {
            assertNull("sole-$status should be deleted", store.getMemory("sole-$status"))
            assertTrue("embedding for sole-$status should cascade", embeddingCount(store, "sole-$status") == 0)
        }
        // The shared memory survives, now linked only to c-b.
        assertEquals(listOf("c-b"), store.getMemory("shared")!!.companionIds)
        // The General memory is untouched.
        assertEquals("global", store.getMemory("general")!!.scope)
        // The company-targeted temp candidate is gone; the general one remains.
        assertNull(candidateTargetId(store, "cand-a"))
        assertEquals("", candidateTargetId(store, "cand-gen") ?: "")
        assertTrue(rowExists(store, "analysis_candidates", "candidate_id", "cand-gen"))
        assertFalse(rowExists(store, "analysis_candidates", "candidate_id", "cand-a"))
    }

    /* ------------------------ interrupted temp run ------------------------- */

    @Test
    fun interruptedTemporaryRunIsDiscardedOnReconcile() {
        val store = open(freshDbName())
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_run_state (run_id, filed, created_at) VALUES ('dead', 0, '2026-08-04T00:00:00Z')"
        )
        store.writableDatabase.execSQL(
            "INSERT INTO analysis_candidates (candidate_id, run_id, payload_json, created_at) " +
                "VALUES ('c1', 'dead', '{}', '2026-08-04T00:00:00Z')"
        )
        store.reconcileInterruptedAnalysisRuns()
        assertFalse(rowExists(store, "analysis_run_state", "run_id", "dead"))
        assertFalse(rowExists(store, "analysis_candidates", "candidate_id", "c1"))
    }

    /* --------------------------- backup / restore -------------------------- */

    @Test
    fun backupAndRestorePreservesTypesNoTypeAssignmentsAndImportance() {
        val src = open(freshDbName())
        src.insertMemory(mem("typed", scope = "global", typeId = "mtype-fact", importance = 4))
        src.insertMemory(mem("untyped", scope = "global", typeId = null, importance = 0))
        val exported = MemorySeedCodec.serialize(src.exportData())

        // Restore into a fresh store via the first-seed (overwrite) path.
        val dest = open(freshDbName())
        dest.importData(MemorySeedCodec.parse(exported), overwriteSingletons = true)

        assertEquals("mtype-fact", dest.getMemory("typed")!!.typeId)
        assertEquals(4, dest.getMemory("typed")!!.importance)
        assertNull(dest.getMemory("untyped")!!.typeId)
        assertEquals(0, dest.getMemory("untyped")!!.importance)
    }

    @Test
    fun restoreDoesNotResurrectADeletedStarterType() {
        // A Type-aware backup whose Type set omits a starter (the user deleted
        // it) must NOT be silently rebuilt on restore into a fresh store (item 7).
        val src = open(freshDbName())
        val exported = src.exportData().let { data ->
            // Simulate a backup taken after the user deleted the Event starter.
            MemorySeedCodec.serialize(
                data.copy(memoryTypes = data.memoryTypes.filterNot { it.typeId == "mtype-event" })
            )
        }
        val dest = open(freshDbName())
        dest.importData(MemorySeedCodec.parse(exported), overwriteSingletons = true)
        val ids = dest.getMemoryTypes().map { it.typeId }
        assertFalse("deleted Event starter must not be resurrected", ids.contains("mtype-event"))
        assertTrue(ids.contains("mtype-fact"))
    }

    @Test
    fun updateMemoryPreservesACustomTypeThroughAContentChange() {
        // Store-level persistence contract behind item 2. This does NOT drive
        // MemoryEditorActivity; it exercises MemoryStore.updateMemory directly to
        // prove the guarantee the editor depends on: a record carrying a
        // user-created custom Type keeps its exact type_id when only its content
        // changes. The editor's own fix (holding the real type_id rather than a
        // starter display key) is verified by reading that code, not by this test.
        val store = open(freshDbName())
        store.upsertMemoryType(MemoryTypeRecord("mtype-pets", "Pets", "2026-08-04T00:00:00Z"))
        store.insertMemory(mem("m-1", scope = "global", typeId = "mtype-pets"))

        val prior = store.getMemory("m-1")!!
        // Only content changes; the custom type_id is carried through verbatim.
        store.updateMemory(prior.copy(content = "an edited fact", title = ""), null)

        assertEquals("mtype-pets", store.getMemory("m-1")!!.typeId)
        assertEquals("an edited fact", store.getMemory("m-1")!!.content)
    }

    /* -------------------- Phase 2: canonical Type service ------------------- */

    @Test
    fun typeListAddAndRenameWorkByStableId() {
        // Test 1. Add a Type; it gets a stable id. Rename by that id; the id is
        // unchanged and only the name moves.
        val store = open(freshDbName())
        val created = store.addMemoryType("Recipes")
        assertTrue(store.getMemoryTypes().any { it.typeId == created.typeId && it.name == "Recipes" })

        store.renameMemoryType(created.typeId, "Cooking")
        val after = store.getMemoryTypes().first { it.typeId == created.typeId }
        assertEquals(created.typeId, after.typeId)   // stable id
        assertEquals("Cooking", after.name)          // editable display name
    }

    @Test
    fun renamingATypePreservesEveryAssignedMemory() {
        // Test 2. A rename edits only the name; every assigned memory keeps its
        // type_id and all its content.
        val store = open(freshDbName())
        val type = store.addMemoryType("Pets")
        store.insertMemory(mem("m-1", scope = "global", typeId = type.typeId, importance = 3))
        store.insertMemory(mem("m-2", scope = "global", typeId = type.typeId, importance = 4))

        val affected = store.renameMemoryType(type.typeId, "Animals")

        assertEquals(setOf("m-1", "m-2"), affected.toSet())
        assertEquals(type.typeId, store.getMemory("m-1")!!.typeId)
        assertEquals(type.typeId, store.getMemory("m-2")!!.typeId)
        assertEquals("content of m-1", store.getMemory("m-1")!!.content)
        assertEquals(3, store.getMemory("m-1")!!.importance)
    }

    @Test
    fun deletingATypeLeavesItsMemoriesIntactAsNoType() {
        // Test 3. Deletion reassigns memories to No Type atomically; nothing about
        // the memories' content/scope/importance/lifecycle changes, and the Type
        // is gone (a deleted starter is not re-seeded).
        val store = open(freshDbName())
        store.insertMemory(mem("m-fact", scope = "global", typeId = "mtype-fact", importance = 5, status = "active"))

        val affected = store.deleteMemoryType("mtype-fact")

        assertEquals(listOf("m-fact"), affected)
        val m = store.getMemory("m-fact")!!
        assertNull("memory kept, now No Type", m.typeId)
        assertEquals("content of m-fact", m.content)
        assertEquals("global", m.scope)
        assertEquals(5, m.importance)
        assertEquals("active", m.status)
        assertFalse("deleted starter Type must not be re-seeded",
            store.getMemoryTypes().any { it.typeId == "mtype-fact" })
    }

    @Test
    fun typeRenameAndDeleteQueueEmbeddingRefreshForAffectedMemories() {
        // Test 4. A rename or delete drops the affected memories' vectors so the
        // librarian's background self-repair re-embeds them — old Type wording
        // cannot linger in an active embedding document. Unaffected memories keep
        // their vectors.
        val store = open(freshDbName())
        val a = store.addMemoryType("A")
        val b = store.addMemoryType("B")
        store.insertMemory(mem("m-a", scope = "global", typeId = a.typeId))
        store.insertMemory(mem("m-b", scope = "global", typeId = b.typeId))
        store.upsertEmbedding("m-a", "test-model", byteArrayOf(1, 2, 3))
        store.upsertEmbedding("m-b", "test-model", byteArrayOf(4, 5, 6))

        val renamed = store.renameMemoryType(a.typeId, "A2")
        assertEquals(listOf("m-a"), renamed)
        assertEquals("affected memory queued for refresh", 0, embeddingCount(store, "m-a"))
        assertEquals("unrelated memory's vector is untouched", 1, embeddingCount(store, "m-b"))

        // Re-embed m-a, then delete its Type: the vector is dropped (queued) again.
        store.upsertEmbedding("m-a", "test-model", byteArrayOf(7, 8, 9))
        val deleted = store.deleteMemoryType(a.typeId)
        assertEquals(listOf("m-a"), deleted)
        assertEquals(0, embeddingCount(store, "m-a"))
        assertEquals(1, embeddingCount(store, "m-b"))
    }

    @Test
    fun instructionBehaviorKeysOnStableTypeId_renameKeepsIt_deleteEndsIt() {
        // Item B: Instruction behavior is keyed on the stable Instruction Type
        // id, carried through retrieval. Renaming the Type (id unchanged) keeps a
        // memory behaving as an Instruction; deleting it (memory -> No Type) ends
        // that behavior without touching the memory's content or lifecycle.
        val store = open(freshDbName())
        store.insertMemory(
            mem("inst", scope = "global", typeId = MemoryTypeMigration.INSTRUCTION_TYPE_ID, status = "active")
        )

        fun retrievedTypeId(): String? =
            store.activeMemoriesForScope(RetrievalScope.NONE).first { it.memoryId == "inst" }.typeId

        // Retrieval carries the stable Type id (not the inert legacy kind).
        assertEquals(MemoryTypeMigration.INSTRUCTION_TYPE_ID, retrievedTypeId())

        // Rename the Instruction Type: the id does not change, so it still behaves
        // as an Instruction.
        store.renameMemoryType(MemoryTypeMigration.INSTRUCTION_TYPE_ID, "Directives")
        assertEquals(MemoryTypeMigration.INSTRUCTION_TYPE_ID, retrievedTypeId())

        // Delete the Instruction Type: the memory becomes No Type and stops
        // behaving as an Instruction; its content and lifecycle are untouched.
        store.deleteMemoryType(MemoryTypeMigration.INSTRUCTION_TYPE_ID)
        assertNull(retrievedTypeId())
        assertEquals("content of inst", store.getMemory("inst")!!.content)
        assertEquals("active", store.getMemory("inst")!!.status)
    }

    @Test
    fun legacyTitleKindProvenanceColumnsDoNotEnterRetrieval() {
        // Item R4: populated legacy title/kind/provenance columns must not reach
        // the runtime retrieval object, ranking, matching, or embedding text.
        val store = open(freshDbName())
        // A distinctive title word ("Zephyrgloom") appears ONLY in the legacy
        // title column, never in the content; legacy kind/provenance are set too.
        store.writableDatabase.execSQL(
            "INSERT INTO memories (memory_id, scope, kind, type_id, title, content, importance, created_at, status, " +
                "provenance_source, provenance_confidence) " +
                "VALUES ('m-leg', 'global', 'fact', 'mtype-fact', 'Zephyrgloom', 'the harvest festival', 0, " +
                "'2026-08-05T00:00:00Z', 'active', 'user_stated', 'tentative')"
        )

        val retrieved = store.activeMemoriesForScope(RetrievalScope.NONE).first { it.memoryId == "m-leg" }
        // typeId is the sole Type authority; content is preserved.
        assertEquals("mtype-fact", retrieved.typeId)
        assertEquals("the harvest festival", retrieved.content)
        // The runtime object carries no title/kind/provenance to leak.
        val fields = retrieved.javaClass.declaredFields.map { it.name }
        assertFalse(fields.any { it.equals("title", true) })
        assertFalse(fields.any { it.equals("kind", true) })
        assertFalse(fields.any { it.contains("provenance", true) })
        // The embedding/semantic document is built from content (+tags), never
        // the legacy title: the distinctive title word does not enter it.
        val doc = RetrievalDocument.semanticDocument(retrieved.content, retrieved.embeddingText, emptyList())
        assertFalse("legacy title must not enter embedding text", doc.contains("Zephyrgloom"))
        assertTrue(doc.contains("harvest festival"))
    }

    /* --------------- Phase 2: companion memory isolation (item 5) ----------- */

    @Test
    fun aCompanionMemoryIsRetrievableOnlyByItsOwnCompanion() {
        // Test 10 (retrieval side). A memory filed for companion A is eligible in
        // A's chat and NOT in B's chat — the scope gate lives in the query.
        val store = open(freshDbName())
        store.insertCompanion(companion("c-a", "Ash"))
        store.insertCompanion(companion("c-b", "Blue"))
        store.insertMemory(mem("for-a", scope = "companion", companionIds = listOf("c-a"), status = "active"))

        val forA = store.activeMemoriesForScope(RetrievalScope("c-a", null, null, null)).map { it.memoryId }
        val forB = store.activeMemoriesForScope(RetrievalScope("c-b", null, null, null)).map { it.memoryId }

        assertTrue("companion A sees its own memory", forA.contains("for-a"))
        assertFalse("companion B must NOT see companion A's memory", forB.contains("for-a"))
    }

    /* ------------- Phase 2: canonical Pending filing (items 8, 13) ---------- */

    @Test
    fun insertPendingMemoryStoresACanonicalDraftWithoutTransportOrChatIdentity() {
        // Tests 12/13 (storage side). A validated candidate built by the canonical
        // factory and stored via the canonical insert lands as a draft with no
        // chat identity and no transport marker in the stored row.
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "a canonical fact", typeId = "mtype-fact", origin = "archivist"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-p", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record)

        val stored = store.getMemory("m-p")!!
        assertEquals("draft", stored.status)
        assertEquals("mtype-fact", stored.typeId)
        // No permanent provenance is stored on a canonical Pending memory
        // (review finding 1): source, confidence, noted-on, chat name, chat id.
        assertNull("no provenance source stored", stored.provenanceSource)
        assertNull("no provenance confidence stored", stored.provenanceConfidence)
        assertNull("no provenance noted-on stored", stored.provenanceNotedOn)
        assertNull("no chat-name provenance stored", stored.provenanceContext)
        assertNull("no chat identity stored", stored.sourceChatId)
        assertTrue("origin is authorship, not transport", stored.origin in setOf("user", "archivist", "seed"))
        // The stored draft is counted as Pending.
        assertTrue(store.countDrafts() >= 1)
    }

    /* ------- Phase 2 review finding 3: durable companion-deletion marker ---- */

    @Test
    fun pendingCompanionDeletionMarkerReconcilesAnIncompleteCascade() {
        // The failure path: a confirmed deletion wrote its durable marker but the
        // cascade never ran (interrupted). A later reconcile must complete it —
        // sole-owned companion memories deleted (embeddings cascaded), a memory
        // shared with a surviving companion kept (relinked), General untouched,
        // and the marker cleared.
        val store = open(freshDbName())
        store.insertCompanion(companion("c-a", "Ash"))
        store.insertCompanion(companion("c-b", "Blue"))
        store.insertMemory(mem("sole", scope = "companion", companionIds = listOf("c-a")))
        store.upsertEmbedding("sole", "test-model", byteArrayOf(1, 2, 3))
        store.insertMemory(mem("shared", scope = "companion", companionIds = listOf("c-a", "c-b")))
        store.insertMemory(mem("general", scope = "global"))

        // Interrupted after the marker was written, before the cascade.
        store.markCompanionPendingDeletion("c-a")
        assertEquals(listOf("c-a"), store.pendingCompanionDeletionIds())
        assertNotNull("cascade has not run yet", store.getMemory("sole"))

        // Reconcile: drain each marker with the same cascade, then clear it. This
        // is exactly what CompanionDeletionService.reconcilePendingDeletions does;
        // it is exercised here against the real store (the service resolves the
        // app-singleton store, which these throwaway-DB tests do not use).
        for (id in store.pendingCompanionDeletionIds()) {
            store.deleteCompanion(id, deleteMemories = true)
            store.clearCompanionPendingDeletion(id)
        }

        assertNull("sole-owned companion memory deleted on reconcile", store.getMemory("sole"))
        assertEquals("embedding cascaded", 0, embeddingCount(store, "sole"))
        assertEquals("shared memory survives, relinked to c-b", listOf("c-b"), store.getMemory("shared")!!.companionIds)
        assertEquals("General memory untouched", "global", store.getMemory("general")!!.scope)
        assertTrue("marker cleared after completion", store.pendingCompanionDeletionIds().isEmpty())
    }

    @Test
    fun completedCompanionDeletionClearsItsMarkerAndRetryIsIdempotent() {
        // The success path plus retry-safety: the durable mark→cascade→clear flow
        // leaves no marker, and re-running the cascade on an already-deleted
        // companion is a safe no-op (so a reconcile after a completed run cannot
        // corrupt anything).
        val store = open(freshDbName())
        store.insertCompanion(companion("c-a", "Ash"))
        store.insertMemory(mem("sole", scope = "companion", companionIds = listOf("c-a")))

        store.markCompanionPendingDeletion("c-a")
        store.deleteCompanion("c-a", deleteMemories = true)
        store.clearCompanionPendingDeletion("c-a")

        assertTrue(store.pendingCompanionDeletionIds().isEmpty())
        assertNull(store.getMemory("sole"))

        // Idempotent retry: must not throw and must leave state consistent.
        store.deleteCompanion("c-a", deleteMemories = true)
        assertNull(store.getMemory("sole"))
        assertTrue(store.pendingCompanionDeletionIds().isEmpty())
    }

    /* ------------------------------ helpers ------------------------------- */

    private fun importanceOf(store: MemoryStore, id: String): Int =
        store.readableDatabase.rawQuery("SELECT importance FROM memories WHERE memory_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getInt(0) else -999 }

    private fun typeIdOf(store: MemoryStore, id: String): String? =
        store.readableDatabase.rawQuery("SELECT type_id FROM memories WHERE memory_id = ?", arrayOf(id))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun embeddingCount(store: MemoryStore, memoryId: String): Int =
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM embeddings WHERE memory_id = ?", arrayOf(memoryId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun candidateTargetId(store: MemoryStore, id: String): String? =
        store.readableDatabase.rawQuery("SELECT target_id FROM analysis_candidates WHERE candidate_id = ?", arrayOf(id))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun rowExists(store: MemoryStore, table: String, col: String, value: String): Boolean =
        store.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE $col = ?", arrayOf(value))
            .use { it.moveToFirst() }

    private fun companion(id: String, name: String) = CompanionRecord(
        companionId = id, currentName = name, essence = "e", relationshipNotes = null,
        memoryParticipation = "full", hardLimitsJson = "[]", appCharacterId = null,
        mirrorText = null, mirrorSyncedAt = null, modelAdaptationsJson = "[]",
        createdAt = "2026-08-04T00:00:00Z", status = "active", nameHistory = emptyList()
    )

    private fun mem(
        id: String,
        scope: String,
        typeId: String? = null,
        importance: Int = 0,
        status: String = "active",
        companionIds: List<String> = emptyList(),
        worldIds: List<String> = emptyList(),
        campaignIds: List<String> = emptyList(),
        roleplayCharacterIds: List<String> = emptyList()
    ) = MemoryRecord(
        memoryId = id, scope = scope, kind = MemoryTypeMigration.legacyKindForTypeId(typeId),
        title = "", content = "content of $id", embeddingText = null, tagsJson = "[]",
        importance = importance, worldIds = worldIds, roleplayCharacterIds = roleplayCharacterIds,
        campaignIds = campaignIds, projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        provenanceSource = null, provenanceConfidence = null, provenanceNotedOn = null,
        provenanceContext = null, createdAt = "2026-08-04T00:00:00Z", updatedAt = null, status = status,
        supersedes = null, companionIds = companionIds, entityRefs = emptyList(), changeLog = emptyList(),
        origin = "user", typeId = typeId
    )

    /**
     * Build a minimal pre-Phase-1 (schema version 20) database directly: enough
     * of the memories table (every column the v21 rebuild copies) plus meta, with
     * user_version = 20 so the production [MemoryStore] opened afterwards runs the
     * real onUpgrade(20 -> 21). Uses the raw SQLCipher database to avoid depending
     * on a particular SQLiteOpenHelper constructor overload.
     */
    private fun buildV20Database(name: String, fill: (SQLiteDatabase) -> Unit) {
        val file = ctx.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, key, null, null)
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO meta (key, value) VALUES ('db_migration', '20')")
        db.execSQL(
            "CREATE TABLE memories (" +
                "memory_id TEXT PRIMARY KEY, " +
                "scope TEXT NOT NULL CHECK (scope IN ('global','real_life','companion','project','world','campaign','rp_character')), " +
                "kind TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "embedding_text TEXT, " +
                "tags_json TEXT DEFAULT '[]', " +
                "importance INTEGER NOT NULL DEFAULT 3, " +
                "always_load INTEGER NOT NULL DEFAULT 0, " +
                "world_id TEXT, roleplay_character_id TEXT, campaign_id TEXT, project_id TEXT, " +
                "protection_json TEXT, mode_hints_json TEXT DEFAULT '[]', " +
                "provenance_source TEXT, provenance_confidence TEXT, provenance_noted_on TEXT, provenance_context TEXT, " +
                "created_at TEXT NOT NULL, updated_at TEXT, " +
                "status TEXT NOT NULL CHECK (status IN ('draft','active','archived','superseded')), " +
                "supersedes TEXT, origin TEXT NOT NULL DEFAULT 'user', " +
                "suggested_card_type TEXT, suggested_card_id TEXT, suggested_section TEXT, source_chat_id TEXT)"
        )
        fill(db)
        db.version = 20
        db.close()
    }

    private fun insertV20Memory(db: SQLiteDatabase, id: String, kind: String, importance: Int) {
        db.insert("memories", null, ContentValues().apply {
            put("memory_id", id)
            put("scope", "global")
            put("kind", kind)
            put("title", "legacy title for $id")
            put("content", "content of $id")
            put("importance", importance)
            put("created_at", "2026-08-04T00:00:00Z")
            put("status", "active")
        })
    }
}
