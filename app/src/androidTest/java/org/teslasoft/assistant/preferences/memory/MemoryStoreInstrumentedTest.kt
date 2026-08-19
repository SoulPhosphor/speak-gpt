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
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistExistingMemory
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistPrompt
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistRuntimeProtocol
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistSceneContext
import org.teslasoft.assistant.preferences.memory.librarian.RetrievalDocument
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec

/**
 * Real SQLCipher-backed migration and deletion coverage for the Phase 1 storage
 * work (canonical recovery plan §5, §7, §8.10, §4.6). These are instrumentation
 * tests: they open the actual [MemoryStore] against throwaway database files via
 * [MemoryStore.openForTest], so onCreate (the current schema), onUpgrade (from a
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

    /* --------------------------- fresh current schema ---------------------- */

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

        // Opening runs the complete supported upgrade chain through v28.
        val store = open(name)

        assertEquals(
            "complete",
            store.getMeta(MemoryStore.META_ASSOCIATIVE_BOOKMARK_CUTOVER)
        )

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

    /* ---------------- Stage B bookmark + frozen range -------------------- */

    @Test
    fun bookmarkMigrationUsesContiguousTerminalPrefixAndStopsAtPendingGap() {
        val store = open(freshDbName())
        insertTranscript(store, "t1", "chat-a", "1", "processed", "1")
        insertTranscript(store, "t2", "chat-a", "2", "excluded", null)
        insertTranscript(store, "t3", "chat-a", "3", "pending", null)
        insertTranscript(store, "t4", "chat-a", "4", "processed", "4")
        insertTranscript(store, "t5", "chat-a", "5", "excluded", null)
        store.insertArchivistRun(runRecord("run-stale"))
        store.writableDatabase.execSQL(
            "UPDATE transcripts SET claim_run_id = 'run-stale' WHERE transcript_id = 't3'"
        )

        store.rebuildBookmarkCutoverForTest()

        val bookmark = store.getAnalysisBookmark("chat-a")!!
        assertEquals("t2", bookmark.lastTranscriptId)
        assertEquals(listOf("t4", "t5"), bookmark.skippedTranscriptIds)
        assertEquals(listOf("t3"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
        assertNull(store.transcriptsByIds(listOf("t3")).single().claimRunId)
        assertEquals("interrupted", store.getArchivistRun("run-stale")!!.outcome)
    }

    @Test
    fun migrationPreservesLegacyExcludedRowsForAChatThatIsStillPaused() {
        val store = open(freshDbName())
        val chatId = "chat-paused-migration-${System.nanoTime()}"
        Preferences.getPreferences(ctx, chatId).setChatExcludedFromMemory(true)
        try {
            insertTranscript(store, "through-40", chatId, "1", "processed", "1")
            insertTranscript(store, "waiting-41", chatId, "2", "excluded", null)

            store.rebuildBookmarkCutoverForTest()

            val paused = store.getAnalysisBookmark(chatId)!!
            assertEquals("through-40", paused.lastTranscriptId)
            assertTrue(paused.archivePaused)
            assertTrue(store.bookmarkEligibleTranscripts().none { it.chatId == chatId })
            store.setChatTranscriptsExcluded(chatId, false)
            assertEquals(
                listOf("waiting-41"),
                store.bookmarkEligibleTranscripts().filter { it.chatId == chatId }.map { it.transcriptId }
            )
        } finally {
            Preferences.getPreferences(ctx, chatId).setChatExcludedFromMemory(false)
        }
    }

    @Test
    fun postCutoverLegacyReviewColumnsCannotChangeEligibility() {
        val store = open(freshDbName())
        insertTranscript(store, "old", "chat-a", "1", "processed", "1")
        insertTranscript(store, "new", "chat-a", "2", "pending", null)
        store.rebuildBookmarkCutoverForTest()

        assertEquals(listOf("new"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
        store.writableDatabase.execSQL(
            "UPDATE transcripts SET review_status = 'excluded', processed_at = 'changed' WHERE transcript_id = 'new'"
        )
        store.writableDatabase.execSQL(
            "UPDATE transcripts SET review_status = 'pending', processed_at = NULL WHERE transcript_id = 'old'"
        )
        assertEquals(listOf("new"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
    }

    @Test
    fun archivePauseCapturesWholeWaitingSpanAndResumeStartsAfterLastSuccess() {
        val store = open(freshDbName())
        insertTranscript(store, "through-40", "chat-paused", "1", "processed", "1")
        store.rebuildBookmarkCutoverForTest()

        store.setChatTranscriptsExcluded("chat-paused", true)
        for (messageNumber in 41..60) {
            store.appendTranscriptTurn(
                chatId = "chat-paused",
                companionId = null,
                userMessage = "user-$messageNumber",
                assistantMessage = "assistant-$messageNumber",
                modelTag = "model",
                quickSettingsJson = null,
                markExcluded = false,
                archivePaused = true
            )
        }

        val paused = store.getAnalysisBookmark("chat-paused")!!
        assertEquals("through-40", paused.lastTranscriptId)
        assertTrue(paused.archivePaused)
        assertTrue(store.bookmarkEligibleTranscripts().none { it.chatId == "chat-paused" })

        store.setChatTranscriptsExcluded("chat-paused", false)
        val waiting = store.bookmarkEligibleTranscripts().filter { it.chatId == "chat-paused" }
        assertTrue(waiting.isNotEmpty())
        val userMessages = waiting.sumOf { row ->
            val turns = org.json.JSONArray(row.content)
            (0 until turns.length()).count { index ->
                turns.getJSONObject(index).optString("role") == "user"
            }
        }
        assertEquals(20, userMessages)
        assertEquals("through-40", store.getAnalysisBookmark("chat-paused")!!.lastTranscriptId)
        assertFalse(store.getAnalysisBookmark("chat-paused")!!.archivePaused)
    }

    @Test
    fun frozenRangeCommitAdvancesBookmarkAndLeavesLateMessageForNextRun() {
        val store = open(freshDbName())
        insertTranscript(store, "t1", "chat-a", "1", "pending", null)
        insertTranscript(store, "t2", "chat-a", "2", "pending", null)
        val run = runRecord("run-stage-b")
        val frozen = store.beginAnalysisRun(
            run, mapOf("chat-a" to listOf("t1", "t2"))
        ).getValue("chat-a")

        // The newest row is claimed, so capture seals it and creates a new row.
        val lateOutcome = store.appendTranscriptTurn(
            chatId = "chat-a", companionId = null,
            userMessage = "late user", assistantMessage = "late assistant",
            modelTag = "model", quickSettingsJson = null, markExcluded = false
        )
        assertTrue(lateOutcome.startsWith("inserted "))

        val memory = mem("stage-b-memory", scope = "global", status = "draft")
        val rule = ModelRuleRecord(
            ruleId = "stage-b-rule", text = "Use complete sentences.",
            modelStringsJson = "[]", status = "draft", sourceModelString = "model",
            createdAt = "2026-08-08T00:00:00Z"
        )
        store.commitFrozenChatRange(frozen, listOf(memory), listOf(rule), emptyList())

        assertEquals("t2", store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
        assertNotNull(store.getMemory("stage-b-memory"))
        assertNotNull(store.getModelRule("stage-b-rule"))
        val next = store.bookmarkEligibleTranscripts().filter { it.chatId == "chat-a" }
        assertEquals(1, next.size)
        assertTrue(next.single().transcriptId != "t1" && next.single().transcriptId != "t2")
    }

    @Test
    fun cleanupRemovesOnlyUnavailableTargetsAndKeepsMultiModelRule() {
        val store = open(freshDbName())
        val unavailable = ModelIdentity("openrouter", "openai/gpt-5.1")
        val stillAvailable = ModelIdentity("openrouter", "tngtech/longcat")
        store.upsertModelRule(
            ModelRuleRecord(
                ruleId = "multi-target-rule",
                text = "Keep the useful rule.",
                modelStringsJson = "[\"obsolete-old-model\"]",
                status = "active",
                createdAt = "2026-08-10T00:00:00Z",
                modelTargetsJson = ModelIdentityCodec.encode(
                    listOf(unavailable, stillAvailable)
                )
            )
        )

        val first = store.removeModelTargets(setOf(unavailable))
        assertEquals(1, first.removedTargets)
        assertEquals(1, first.updatedRules)
        assertEquals(0, first.deletedRules)
        assertEquals(
            listOf(stillAvailable),
            ModelIdentityCodec.decode(store.getModelRule("multi-target-rule")!!.modelTargetsJson)
        )
        assertEquals("[]", store.getModelRule("multi-target-rule")!!.modelStringsJson)

        val second = store.removeModelTargets(setOf(stillAvailable))
        assertEquals(1, second.removedTargets)
        assertEquals(1, second.deletedRules)
        assertNull(store.getModelRule("multi-target-rule"))
    }

    @Test
    fun cleanupDoesNotKeepRuleForObsoleteFuzzyTarget() {
        val store = open(freshDbName())
        val unavailable = ModelIdentity("openrouter", "openai/gpt-5.1")
        store.upsertModelRule(
            ModelRuleRecord(
                ruleId = "obsolete-fuzzy-target",
                text = "Remove the unavailable exact target.",
                modelStringsJson = "[\"glm-5\"]",
                status = "active",
                createdAt = "2026-08-10T00:00:00Z",
                modelTargetsJson = ModelIdentityCodec.encode(listOf(unavailable))
            )
        )

        store.removeModelTargets(setOf(unavailable))
        assertNull(store.getModelRule("obsolete-fuzzy-target"))
    }

    @Test
    fun stageDCandidateBoundaryCommitsRelationshipHintAndCleansTemporaryState() {
        val store = open(freshDbName())
        store.insertMemory(
            mem("old-green", scope = "real_life", typeId = "mtype-fact")
                .copy(content = "The user's favorite color is green.")
        )
        insertTranscript(store, "t1", "chat-a", "1", "pending", null)
        val frozen = store.beginAnalysisRun(
            runRecord("run-stage-d"), mapOf("chat-a" to listOf("t1"))
        ).getValue("chat-a")
        store.stageAnalysisCandidates(
            collectionId = frozen.rangeId,
            chunkOrdinal = 1,
            candidates = listOf(
                StagedAnalysisCandidate(
                    stream = "memory",
                    targetType = null,
                    targetId = null,
                    candidateHash = "purple-hash",
                    payloadJson = "{\"content\":\"purple\"}"
                ),
                StagedAnalysisCandidate(
                    stream = "model_rule",
                    targetType = null,
                    targetId = null,
                    candidateHash = "rule-hash",
                    payloadJson = "{\"text\":\"be concise\"}"
                )
            )
        )
        assertTrue(rowExists(store, "analysis_run_state", "run_id", frozen.rangeId))
        assertEquals(
            2,
            store.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM analysis_candidates WHERE run_id = ?",
                arrayOf(frozen.rangeId)
            ).use { it.moveToFirst(); it.getInt(0) }
        )

        val changed = mem(
            "new-purple", scope = "real_life", typeId = "mtype-fact", status = "draft"
        ).copy(content = "The user's favorite color is purple now.")
        store.commitFrozenChatRange(
            frozen,
            memories = listOf(changed),
            rules = emptyList(),
            lorebookSuggestions = emptyList(),
            relationshipHintsByMemoryId = mapOf("new-purple" to listOf("old-green"))
        )

        assertEquals(
            listOf(MemoryMatch.Match("old-green", MemoryMatch.Relation.AI_RELATED)),
            store.relationshipHintsForDraft("new-purple")
        )
        assertFalse(rowExists(store, "analysis_run_state", "run_id", frozen.rangeId))
        assertFalse(rowExists(store, "analysis_candidates", "run_id", frozen.rangeId))
        assertEquals("t1", store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)

        val result = store.resolveSupersede("new-purple", listOf("old-green"))
        assertTrue(result is MemoryStore.ResolutionResult.Applied)
        assertTrue(store.relationshipHintsForDraft("new-purple").isEmpty())
        assertNotNull(store.supersededAt("old-green"))
        assertEquals("superseded", store.getMemory("old-green")!!.status)
    }

    @Test
    fun stageDFilingFailureRollsBackHintsAndBookmarkThenDiscardsCandidates() {
        val store = open(freshDbName())
        store.insertMemory(mem("existing", scope = "global"))
        insertTranscript(store, "t1", "chat-a", "1", "pending", null)
        val frozen = store.beginAnalysisRun(
            runRecord("run-stage-d-rollback"), mapOf("chat-a" to listOf("t1"))
        ).getValue("chat-a")
        store.stageAnalysisCandidates(
            frozen.rangeId, 1,
            listOf(
                StagedAnalysisCandidate(
                    "memory", null, null, "hash", "{\"content\":\"candidate\"}"
                )
            )
        )
        val invalidRule = ModelRuleRecord(
            "invalid-rule", "bad", "[]", "invalid", null,
            "2026-08-08T00:00:00Z"
        )

        var failed = false
        try {
            store.commitFrozenChatRange(
                frozen,
                memories = listOf(mem("candidate", scope = "global", status = "draft")),
                rules = listOf(invalidRule),
                lorebookSuggestions = emptyList(),
                relationshipHintsByMemoryId = mapOf("candidate" to listOf("existing"))
            )
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertNull(store.getMemory("candidate"))
        assertNull(store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
        assertTrue(rowExists(store, "analysis_run_state", "run_id", frozen.rangeId))
        store.discardCandidateCollection(frozen.rangeId)
        assertFalse(rowExists(store, "analysis_run_state", "run_id", frozen.rangeId))
        assertFalse(rowExists(store, "analysis_candidates", "run_id", frozen.rangeId))
    }

    @Test
    fun stageDRerunTemporaryStateIsOwnedByRunCleanup() {
        val store = open(freshDbName())
        val runId = "run-stage-d-rerun"
        store.beginCandidateCollection(runId, "chat-rerun")
        store.stageAnalysisCandidates(
            runId, 1,
            listOf(
                StagedAnalysisCandidate(
                    "memory", null, null, "rerun-hash", "{\"content\":\"candidate\"}"
                )
            )
        )

        assertTrue(rowExists(store, "analysis_run_state", "run_id", runId))
        assertTrue(rowExists(store, "analysis_candidates", "run_id", runId))

        store.releaseAnalysisClaims(runId)

        assertFalse(rowExists(store, "analysis_run_state", "run_id", runId))
        assertFalse(rowExists(store, "analysis_candidates", "run_id", runId))
    }

    @Test
    fun failedChatCommitRollsBackMemoryRuleAndBookmarkTogether() {
        val store = open(freshDbName())
        insertTranscript(store, "t1", "chat-a", "1", "pending", null)
        val frozen = store.beginAnalysisRun(
            runRecord("run-rollback"), mapOf("chat-a" to listOf("t1"))
        ).getValue("chat-a")
        val memory = mem("must-not-leak", scope = "global", status = "draft")
        val invalidRule = ModelRuleRecord(
            ruleId = "must-not-leak-rule", text = "draft",
            modelStringsJson = "[]", status = "invalid", sourceModelString = null,
            createdAt = "2026-08-08T00:00:00Z"
        )

        var failed = false
        try {
            store.commitFrozenChatRange(frozen, listOf(memory), listOf(invalidRule), emptyList())
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertNull(store.getMemory("must-not-leak"))
        assertNull(store.getModelRule("must-not-leak-rule"))
        assertNull(store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
        store.releaseAnalysisClaims("run-rollback")
        assertEquals(listOf("t1"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
    }

    @Test
    fun interruptedFrozenRangeReleasesClaimWithoutSkippingMaterial() {
        val store = open(freshDbName())
        insertTranscript(store, "t1", "chat-a", "1", "pending", null)
        store.beginAnalysisRun(
            runRecord("run-interrupted"), mapOf("chat-a" to listOf("t1"))
        )

        assertEquals(1, store.reconcileInterruptedAnalysisRuns())
        assertNull(store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
        assertEquals(listOf("t1"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
        assertNull(store.transcriptsByIds(listOf("t1")).single().claimRunId)
    }

    @Test
    fun multiChatRunCommitsSuccessfulChatWhileFailedChatKeepsBookmark() {
        val store = open(freshDbName())
        insertTranscript(store, "a1", "chat-a", "1", "pending", null)
        insertTranscript(store, "b1", "chat-b", "1", "pending", null)
        val ranges = store.beginAnalysisRun(
            runRecord("run-multi"),
            mapOf("chat-a" to listOf("a1"), "chat-b" to listOf("b1"))
        )

        store.commitFrozenChatRange(
            ranges.getValue("chat-a"),
            listOf(mem("visible-a", scope = "global", status = "draft")),
            emptyList(), emptyList()
        )
        try {
            store.commitFrozenChatRange(
                ranges.getValue("chat-b"),
                listOf(mem("hidden-b", scope = "global", status = "draft")),
                listOf(
                    ModelRuleRecord(
                        "invalid-b", "bad", "[]", "invalid", null,
                        "2026-08-08T00:00:00Z"
                    )
                ),
                emptyList()
            )
        } catch (_: Exception) {
            // Deterministic save failure for chat B.
        }

        assertEquals("a1", store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
        assertNotNull(store.getMemory("visible-a"))
        assertNull(store.getAnalysisBookmark("chat-b")!!.lastTranscriptId)
        assertNull(store.getMemory("hidden-b"))
        store.releaseAnalysisClaims("run-multi")
        assertEquals(listOf("b1"), store.bookmarkEligibleTranscripts().map { it.transcriptId })
    }

    @Test
    fun interruptedBookmarkMigrationInstallsNeitherRowsNorCutoverMarker() {
        val store = open(freshDbName())
        insertTranscript(store, "t1", "chat-a", "1", "processed", "1")

        var interrupted = false
        try {
            store.rebuildBookmarkCutoverForTest(interruptBeforeMarker = true)
        } catch (_: IllegalStateException) {
            interrupted = true
        }
        assertTrue(interrupted)
        assertNull(store.getMeta(MemoryStore.META_ASSOCIATIVE_BOOKMARK_CUTOVER))
        assertTrue(store.getAnalysisBookmarks().isEmpty())
        var eligibilityGuarded = false
        try {
            store.bookmarkEligibleTranscripts()
        } catch (_: IllegalStateException) {
            eligibilityGuarded = true
        }
        assertTrue(eligibilityGuarded)

        store.rebuildBookmarkCutoverForTest()
        assertEquals("complete", store.getMeta(MemoryStore.META_ASSOCIATIVE_BOOKMARK_CUTOVER))
        assertEquals("t1", store.getAnalysisBookmark("chat-a")!!.lastTranscriptId)
    }

    /* --------------------------- backup / restore -------------------------- */

    @Test
    fun backupAndRestorePreservesTypesNoTypeAssignmentsAndImportance() {
        val src = open(freshDbName())
        // Canonical ids: import now requires the canonical m-<uuid> format.
        val typedId = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        val untypedId = MemoryId.generate(MemoryId.Type.ASSOCIATIVE)
        src.insertMemory(mem(typedId, scope = "global", typeId = "mtype-fact", importance = 4))
        src.insertMemory(mem(untypedId, scope = "global", typeId = null, importance = 0))
        insertTranscript(src, "reviewed", "chat-backup", "1", "processed", "1")
        insertTranscript(src, "waiting", "chat-backup", "2", "pending", null)
        src.rebuildBookmarkCutoverForTest()
        src.setChatTranscriptsExcluded("chat-backup", true)
        val exported = MemorySeedCodec.serialize(src.exportData())

        // Restore into a fresh store via the first-seed (overwrite) path.
        val dest = open(freshDbName())
        dest.importData(MemorySeedCodec.parse(exported), overwriteSingletons = true)

        assertEquals("mtype-fact", dest.getMemory(typedId)!!.typeId)
        assertEquals(4, dest.getMemory(typedId)!!.importance)
        assertNull(dest.getMemory(untypedId)!!.typeId)
        assertEquals(0, dest.getMemory(untypedId)!!.importance)
        assertEquals("reviewed", dest.getAnalysisBookmark("chat-backup")!!.lastTranscriptId)
        assertTrue(dest.getAnalysisBookmark("chat-backup")!!.archivePaused)
        assertTrue(dest.bookmarkEligibleTranscripts().none { it.chatId == "chat-backup" })
        dest.setChatTranscriptsExcluded("chat-backup", false)
        assertEquals(listOf("waiting"), dest.bookmarkEligibleTranscripts().map { it.transcriptId })
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
        store.updateMemory(prior.copy(content = "an edited fact"), null)

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

    @Test
    fun generatedDraftDeletionRecordsRejection_manualDeletionDoesNot() {
        // Phase 2 review item 2: deleting a Memory Assistant / computer-generated
        // Pending draft records a content rejection (so a rerun does not refile
        // the exact proposal), while deleting a MANUAL Pending draft does not —
        // and the decision uses separate id-keyed bookkeeping, never a source
        // field on the memory (the canonical record has none).
        val store = open(freshDbName())

        // 1. A generated Pending draft is filed.
        store.insertPendingMemory(
            mem("gen", scope = "global", status = "draft").copy(content = "the harvest festival"),
            generated = true
        )
        assertFalse("not yet rejected", store.isDraftRejected("the harvest festival"))

        // 2. The user deletes it.
        store.deleteMemory("gen")
        // 3. An exact rerun proposal is now rejected — the analyzer consults this
        //    before filing, so it is not refiled.
        assertTrue("deleting a generated draft records a content rejection",
            store.isDraftRejected("the harvest festival"))

        // 4. A MANUALLY filed Pending draft's deletion records NO rejection.
        store.insertPendingMemory(
            mem("man", scope = "global", status = "draft").copy(content = "a manual note"),
            generated = false
        )
        store.deleteMemory("man")
        assertFalse("deleting a manual draft must not record a rejection",
            store.isDraftRejected("a manual note"))
    }

    /* --------------- Phase 2: companion memory isolation (item 5) ----------- */

    @Test
    fun aCompanionMemoryIsRetrievableOnlyByItsOwnCompanion() {
        // Test 10 (retrieval side). A memory filed for companion A is eligible in
        // A's chat and NOT in B's chat — the scope gate lives in the query.
        val store = open(freshDbName())
        store.insertCompanion(companion("c-a", "Ash"))
        store.insertCompanion(companion("c-b", "Blue"))
        store.insertMemory(
            mem("for-a", scope = "companion", companionIds = listOf("c-a"), status = "active")
                .copy(content = "Ash-only private memory")
        )
        store.insertMemory(
            mem("for-b", scope = "companion", companionIds = listOf("c-b"), status = "active")
                .copy(content = "Blue-only private memory")
        )

        val forA = store.activeMemoriesForScope(RetrievalScope("c-a", null, null, null))
        val forB = store.activeMemoriesForScope(RetrievalScope("c-b", null, null, null))

        assertTrue("companion A sees its own memory", forA.any { it.memoryId == "for-a" })
        assertFalse("companion A must NOT see companion B's memory", forA.any { it.memoryId == "for-b" })
        assertTrue("companion B sees its own memory", forB.any { it.memoryId == "for-b" })
        assertFalse("companion B must NOT see companion A's memory", forB.any { it.memoryId == "for-a" })

        val protocolForB = ArchivistRuntimeProtocol.create(
            scene = ArchivistSceneContext("c-b", null, null, null, null),
            existingMemories = forB.map {
                ArchivistExistingMemory(
                    it.memoryId, it.content, it.scope, listOf("Blue"), "Fact"
                )
            },
            validTargets = emptyList()
        )
        val outgoingPrompt = ArchivistPrompt.withRuntimeProtocol(
            "BASE", listOf("mtype-fact" to "Fact"), protocolForB
        )
        assertTrue(outgoingPrompt.contains("Blue-only private memory"))
        assertFalse(outgoingPrompt.contains("Ash-only private memory"))
    }

    /* ------------- Phase 2: canonical Pending filing (items 8, 13) ---------- */

    @Test
    fun insertPendingMemoryStoresACanonicalDraftWithoutTransportOrChatIdentity() {
        // Tests 12/13 (storage side). A validated candidate built by the canonical
        // factory and stored via the canonical insert lands as a draft with no
        // chat identity and no transport marker in the stored row.
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "a canonical fact", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-p", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record)

        val stored = store.getMemory("m-p")!!
        assertEquals("draft", stored.status)
        assertEquals("mtype-fact", stored.typeId)
        assertEquals(PendingMemoryRecordFactory.COMPAT_ORIGIN, stored.origin)
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

    /* --------- generated-draft acceptance clears the marker (item 1) ------ */

    @Test
    fun deletingAGeneratedPendingDraftRecordsARejection() {
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "generated fact", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-gen", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record, generated = true)

        assertTrue("generated marker present while draft",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-gen"))

        store.deleteMemory("m-gen")
        assertTrue("rejection recorded",
            rowExists(store, "rejected_drafts", "content_hash",
                store.javaClass.getDeclaredMethod("draftContentHash", String::class.java).apply {
                    isAccessible = true
                }.invoke(store, "generated fact") as String))
    }

    @Test
    fun acceptingAGeneratedPendingDraftClearsItsMarker() {
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "accepted fact", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-acc", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record, generated = true)

        assertTrue("marker present before acceptance",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-acc"))

        store.setMemoryStatus("m-acc", "active", "accepted by user")

        assertFalse("marker removed after acceptance",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-acc"))
    }

    @Test
    fun deletingAnAcceptedGeneratedMemoryDoesNotRecordARejection() {
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "later-deleted fact", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-del", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record, generated = true)

        store.setMemoryStatus("m-del", "active", "accepted")
        store.deleteMemory("m-del")

        assertFalse("no rejection for formerly-accepted memory",
            rowExists(store, "rejected_drafts", "content_hash",
                store.javaClass.getDeclaredMethod("draftContentHash", String::class.java).apply {
                    isAccessible = true
                }.invoke(store, "later-deleted fact") as String))
    }

    @Test
    fun acceptingAGeneratedDraftThroughResolutionClearsItsMarker() {
        // A Possible Match resolution is the second acceptance path (Save &
        // Edit Old / Replace / Supersede all activate via the same transaction)
        // and must clear the route marker exactly like setMemoryStatus.
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "resolution-accepted fact", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-res", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record, generated = true)

        assertTrue("marker present before resolution",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-res"))

        val result = store.resolveSaveAndEditOld("m-res", null)
        assertTrue(result is MemoryStore.ResolutionResult.Applied)

        assertEquals("active", store.getMemory("m-res")!!.status)
        assertFalse("marker removed after resolution acceptance",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-res"))
    }

    @Test
    fun manualPendingMemoryIsNeverMarkedGenerated() {
        val store = open(freshDbName())
        val candidate = (MemoryCandidateValidator.validateGeneral(
            scope = "global", content = "hand-written memory", typeId = "mtype-fact"
        ) as CandidateResult.Valid).candidate
        val record = PendingMemoryRecordFactory.build(candidate, "m-man", "2026-08-05T00:00:00Z")
        store.insertPendingMemory(record, generated = false)

        assertFalse("manual draft has no generated marker",
            rowExists(store, "generated_pending_drafts", "memory_id", "m-man"))

        store.deleteMemory("m-man")
        assertFalse("deleting manual draft records no rejection",
            rowExists(store, "rejected_drafts", "content_hash",
                store.javaClass.getDeclaredMethod("draftContentHash", String::class.java).apply {
                    isAccessible = true
                }.invoke(store, "hand-written memory") as String))
    }

    /* -- v24/v25 migrations: provenance, kind, and title columns removed --- */

    @Test
    fun freshDatabaseHasNoLegacyColumns() {
        val store = open(freshDbName())
        assertFalse("kind must not exist", columnExists(store, "memories", "kind"))
        assertFalse("title must not exist", columnExists(store, "memories", "title"))
        assertFalse("provenance_source must not exist",
            columnExists(store, "memories", "provenance_source"))
        assertFalse("provenance_confidence must not exist",
            columnExists(store, "memories", "provenance_confidence"))
        assertFalse("provenance_noted_on must not exist",
            columnExists(store, "memories", "provenance_noted_on"))
        assertFalse("provenance_context must not exist",
            columnExists(store, "memories", "provenance_context"))
        assertFalse("source_chat_id must not exist",
            columnExists(store, "memories", "source_chat_id"))
    }

    @Test
    fun upgradedDatabaseDropsLegacyColumnsAndPreservesData() {
        val name = freshDbName()
        buildV20Database(name) { db ->
            db.insert("memories", null, ContentValues().apply {
                put("memory_id", "m-legacy")
                put("scope", "global")
                put("kind", "fact")
                put("title", "legacy title")
                put("content", "important content")
                put("importance", 4)
                put("provenance_source", "inferred")
                put("provenance_confidence", "likely")
                put("provenance_noted_on", "2026-07-01T00:00:00Z")
                put("provenance_context", "some chat")
                put("source_chat_id", "chat-old-1")
                put("created_at", "2026-07-01T00:00:00Z")
                put("status", "active")
            })
        }
        val store = open(name)

        assertFalse("provenance_source dropped",
            columnExists(store, "memories", "provenance_source"))
        assertFalse("provenance_confidence dropped",
            columnExists(store, "memories", "provenance_confidence"))
        assertFalse("provenance_noted_on dropped",
            columnExists(store, "memories", "provenance_noted_on"))
        assertFalse("provenance_context dropped",
            columnExists(store, "memories", "provenance_context"))
        assertFalse("source_chat_id dropped",
            columnExists(store, "memories", "source_chat_id"))
        assertFalse("kind dropped", columnExists(store, "memories", "kind"))
        assertFalse("title dropped", columnExists(store, "memories", "title"))

        val m = store.getMemory("m-legacy")!!
        assertEquals("content preserved", "important content", m.content)
        assertEquals("importance preserved", 4, m.importance)
        assertEquals("status preserved", "active", m.status)
        assertEquals("scope preserved", "global", m.scope)
        assertEquals("created_at preserved", "2026-07-01T00:00:00Z", m.createdAt)
        assertNotNull("type_id migrated", m.typeId)

        // The rebuild must leave the same indexes a fresh install has — the
        // Type index in particular is easy to lose in a table rebuild.
        assertTrue("type index survives the rebuild",
            indexExists(store, "idx_memories_type"))
        assertTrue("status index survives the rebuild",
            indexExists(store, "idx_memories_status"))
    }

    /* --------------- v26 migration: rejected_drafts.chat_key dropped ------- */

    @Test
    fun freshDatabaseHasNoRejectedDraftsChatKeyColumn() {
        val store = open(freshDbName())
        assertFalse("chat_key must not exist",
            columnExists(store, "rejected_drafts", "chat_key"))
    }

    @Test
    fun upgradedDatabaseDropsRejectedDraftsChatKeyAndCollapsesDuplicateHashes() {
        val name = freshDbName()
        buildV20Database(name) { db ->
            // Two legacy rows sharing a content_hash under different chat_key
            // values (the pre-v26 PK) must collapse to the single row v26
            // keeps — the newer deleted_at wins.
            db.execSQL(
                "INSERT INTO rejected_drafts (content_hash, chat_key, deleted_at) VALUES (?, ?, ?)",
                arrayOf("hash-1", "chat-a", "2026-07-01T00:00:00Z")
            )
            db.execSQL(
                "INSERT INTO rejected_drafts (content_hash, chat_key, deleted_at) VALUES (?, ?, ?)",
                arrayOf("hash-1", "chat-b", "2026-07-05T00:00:00Z")
            )
        }
        val store = open(name)

        assertFalse("chat_key dropped", columnExists(store, "rejected_drafts", "chat_key"))
        assertTrue("row for the shared hash survives",
            rowExists(store, "rejected_drafts", "content_hash", "hash-1"))
        val count = store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM rejected_drafts WHERE content_hash = ?", arrayOf("hash-1")
        ).use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("duplicate chat_key rows collapse to one", 1, count)
        val deletedAt = store.readableDatabase.rawQuery(
            "SELECT deleted_at FROM rejected_drafts WHERE content_hash = ?", arrayOf("hash-1")
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals("newest deleted_at kept", "2026-07-05T00:00:00Z", deletedAt)
    }

    /* ------- full upgrade chain: a memory's child records all survive ------ */

    @Test
    fun upgradeFromV20PreservesAMemoryAndAllItsChildRecordsWithNoBrokenForeignKeys() {
        val name = freshDbName()
        buildV20Database(name) { db ->
            // The v20-era tables a real device already has, that no v21-v26
            // migration block touches — built here only so this fixture can
            // carry genuine child records through the whole upgrade chain.
            db.execSQL(
                "CREATE TABLE companions (" +
                    "companion_id TEXT PRIMARY KEY, " +
                    "current_name TEXT NOT NULL, " +
                    "essence TEXT NOT NULL, " +
                    "memory_participation TEXT NOT NULL DEFAULT 'full', " +
                    "hard_limits_json TEXT NOT NULL DEFAULT '[]', " +
                    "created_at TEXT NOT NULL, " +
                    "status TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE embeddings (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "embedding_model TEXT NOT NULL, " +
                    "vector BLOB NOT NULL, " +
                    "embedded_at TEXT NOT NULL, " +
                    "PRIMARY KEY (memory_id, embedding_model))"
            )
            db.execSQL(
                "CREATE TABLE memory_companions (" +
                    "memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "companion_id TEXT NOT NULL REFERENCES companions(companion_id), " +
                    "PRIMARY KEY (memory_id, companion_id))"
            )
            db.execSQL(
                "CREATE TABLE change_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "memory_id TEXT REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "at TEXT NOT NULL, " +
                    "actor TEXT NOT NULL, " +
                    "action TEXT NOT NULL, " +
                    "note TEXT, " +
                    "prior_state_json TEXT)"
            )
            db.execSQL(
                "CREATE TABLE memory_supersessions (" +
                    "new_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "old_memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE, " +
                    "at TEXT NOT NULL, " +
                    "PRIMARY KEY (new_memory_id, old_memory_id))"
            )

            db.insert("companions", null, ContentValues().apply {
                put("companion_id", "c-legacy")
                put("current_name", "Ash")
                put("essence", "e")
                put("created_at", "2026-07-01T00:00:00Z")
                put("status", "active")
            })
            insertV20Memory(db, "m-child-legacy", "fact", 4)
            insertV20Memory(db, "m-super-old", "fact", 2)
            db.insert("embeddings", null, ContentValues().apply {
                put("memory_id", "m-child-legacy")
                put("embedding_model", "test-model")
                put("vector", byteArrayOf(1, 2, 3, 4))
                put("embedded_at", "2026-07-01T00:00:00Z")
            })
            db.insert("memory_companions", null, ContentValues().apply {
                put("memory_id", "m-child-legacy")
                put("companion_id", "c-legacy")
            })
            db.insert("change_log", null, ContentValues().apply {
                put("memory_id", "m-child-legacy")
                put("at", "2026-07-01T00:00:00Z")
                put("actor", "user")
                put("action", "created")
            })
            db.insert("memory_supersessions", null, ContentValues().apply {
                put("new_memory_id", "m-child-legacy")
                put("old_memory_id", "m-super-old")
                put("at", "2026-07-02T00:00:00Z")
            })
        }

        val store = open(name)

        // The memory itself survives the whole v21-v26 chain.
        val m = store.getMemory("m-child-legacy")!!
        assertEquals("content preserved", "content of m-child-legacy", m.content)
        assertEquals("importance preserved", 4, m.importance)
        assertNotNull("legacy kind 'fact' migrated to a Type", m.typeId)

        // Every child record survives — none was silently dropped or orphaned.
        assertEquals("embedding survives", 1, embeddingCount(store, "m-child-legacy"))
        assertTrue("target join survives",
            store.readableDatabase.rawQuery(
                "SELECT 1 FROM memory_companions WHERE memory_id = ? AND companion_id = ?",
                arrayOf("m-child-legacy", "c-legacy")
            ).use { it.moveToFirst() })
        assertTrue("change_log entry survives",
            store.readableDatabase.rawQuery(
                "SELECT 1 FROM change_log WHERE memory_id = ? AND action = 'created'",
                arrayOf("m-child-legacy")
            ).use { it.moveToFirst() })
        assertTrue("supersession record survives",
            store.readableDatabase.rawQuery(
                "SELECT 1 FROM memory_supersessions WHERE new_memory_id = ? AND old_memory_id = ?",
                arrayOf("m-child-legacy", "m-super-old")
            ).use { it.moveToFirst() })
        assertNotNull("the superseded memory itself also survives",
            store.getMemory("m-super-old"))

        // The v21/v24/v25/v26 `memories` rebuilds run with foreign keys off
        // (onConfigure) precisely so dropping/recreating the parent table
        // cannot orphan these child rows; onOpen re-enables enforcement
        // afterward. Confirm directly that nothing was left dangling.
        val brokenRefs = store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null)
            .use { c -> val rows = ArrayList<String>(); while (c.moveToNext()) { rows.add(c.getString(0)) }; rows }
        assertTrue("no broken foreign-key references after the full upgrade chain: $brokenRefs",
            brokenRefs.isEmpty())
    }

    /* ------------------------------ helpers ------------------------------- */

    private fun insertTranscript(
        store: MemoryStore,
        id: String,
        chatId: String,
        startedAt: String,
        reviewStatus: String,
        processedAt: String?
    ) {
        store.writableDatabase.insertOrThrow("transcripts", null, ContentValues().apply {
            put("transcript_id", id)
            put("chat_id", chatId)
            put("source", "live")
            put("started_at", startedAt)
            put("ended_at", startedAt)
            put("content", "[]")
            put("model_tag", "model")
            put("review_status", reviewStatus)
            put("processed_at", processedAt)
        })
    }

    private fun runRecord(id: String) = ArchivistRunRecord(
        runId = id,
        startedAt = "2026-08-08T00:00:00Z",
        finishedAt = null,
        status = "running",
        chatIdsJson = "[]",
        transcriptIdsJson = "[]",
        memoryIdsJson = "[]",
        ruleIdsJson = "[]",
        foundCount = 0,
        failedChatIdsJson = "[]",
        error = null,
        transport = "api",
        analysisType = "associative"
    )

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

    private fun columnExists(store: MemoryStore, table: String, column: String): Boolean =
        store.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) { if (c.getString(1) == column) return true }; false
        }

    private fun indexExists(store: MemoryStore, name: String): Boolean =
        store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)
        ).use { it.moveToFirst() }

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
        memoryId = id, scope = scope,
        content = "content of $id", embeddingText = null, tagsJson = "[]",
        importance = importance, worldIds = worldIds, roleplayCharacterIds = roleplayCharacterIds,
        campaignIds = campaignIds, projectIds = emptyList(), protectionJson = null, modeHintsJson = "[]",
        createdAt = "2026-08-04T00:00:00Z", updatedAt = null, status = status,
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
        // rejected_drafts (created at v14, rekeyed v17) exists on every genuine
        // v20 database; the v26 migration reads it, so the fixture must carry
        // it too or upgrading this hand-built database would fail on a table
        // that only this minimal fixture — never a real device — lacks.
        db.execSQL(
            "CREATE TABLE rejected_drafts (" +
                "content_hash TEXT NOT NULL, " +
                "chat_key TEXT NOT NULL, " +
                "deleted_at TEXT NOT NULL, " +
                "PRIMARY KEY (content_hash, chat_key))"
        )
        // These operational tables existed on every genuine v20 store. The
        // v27 bookmark migration reconciles stale API claims and reads the
        // transcript queue before deriving its one-way cutover state.
        db.execSQL(
            "CREATE TABLE transcripts (" +
                "transcript_id TEXT PRIMARY KEY, chat_id TEXT, companion_id TEXT, " +
                "world_id TEXT, roleplay_character_id TEXT, user_persona_id TEXT, " +
                "campaign_id TEXT, project_id TEXT, source TEXT NOT NULL DEFAULT 'live', " +
                "started_at TEXT, ended_at TEXT, content TEXT NOT NULL, model_tag TEXT, " +
                "quick_settings_json TEXT, review_status TEXT NOT NULL DEFAULT 'pending', " +
                "processed_at TEXT, claim_run_id TEXT)"
        )
        db.execSQL(
            "CREATE TABLE archivist_runs (" +
                "run_id TEXT PRIMARY KEY, started_at TEXT NOT NULL, finished_at TEXT, " +
                "status TEXT NOT NULL, chat_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "transcript_ids_json TEXT NOT NULL DEFAULT '[]', memory_ids_json TEXT NOT NULL DEFAULT '[]', " +
                "rule_ids_json TEXT NOT NULL DEFAULT '[]', found_count INTEGER NOT NULL DEFAULT 0, " +
                "failed_chat_ids_json TEXT NOT NULL DEFAULT '[]', error TEXT, outcome TEXT, " +
                "failure_reason TEXT, transport TEXT NOT NULL DEFAULT 'api', " +
                "analysis_type TEXT NOT NULL DEFAULT 'associative')"
        )
        db.execSQL(
            "CREATE TABLE analysis_run_state (" +
                "run_id TEXT PRIMARY KEY, chat_id TEXT, frozen_end_marker TEXT, " +
                "effective_policy_json TEXT, processing_method TEXT, prompt_profile TEXT, " +
                "chunk_setting TEXT, budgets_json TEXT, chunk_ordinal INTEGER NOT NULL DEFAULT 0, " +
                "chunk_success_json TEXT NOT NULL DEFAULT '[]', retry_count INTEGER NOT NULL DEFAULT 0, " +
                "filed INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT)"
        )
        db.execSQL(
            "CREATE TABLE analysis_candidates (" +
                "candidate_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, " +
                "stream TEXT NOT NULL DEFAULT 'general', target_type TEXT, target_id TEXT, " +
                "candidate_hash TEXT, payload_json TEXT NOT NULL, created_at TEXT NOT NULL)"
        )
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
