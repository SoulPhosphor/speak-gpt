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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * [MemoryCompanionSync.resolveCompanion] self-healing: when the store's
 * mirrorText is stale relative to the authoritative [PersonaObject.prompt],
 * the mirror is refreshed through the existing update path. An
 * already-current mirror causes no write.
 */
class MirrorSelfHealingTest {

    private class FakeDeps : MemoryCompanionSync.Deps {
        var companion: CompanionRecord? = null
        var persona: PersonaObject? = null

        var updateCount = 0
        var lastUpdateMirror: String? = null
        var lastUpdateLabel: String? = null
        var insertCount = 0
        var lastInserted: CompanionRecord? = null

        override fun findByAppCharacterId(personaId: String): CompanionRecord? = companion
        override fun getPersona(personaId: String): PersonaObject? = persona
        override fun insertCompanion(record: CompanionRecord) {
            insertCount++
            lastInserted = record
            companion = record
        }
        override fun updateMirror(companionId: String, appCharacterId: String, label: String, mirrorText: String) {
            updateCount++
            lastUpdateMirror = mirrorText
            lastUpdateLabel = label
        }
    }

    private fun record(name: String, mirror: String) = CompanionRecord(
        companionId = "c-test",
        currentName = name,
        essence = "",
        relationshipNotes = null,
        memoryParticipation = "full",
        hardLimitsJson = "[]",
        appCharacterId = "p-abc",
        mirrorText = mirror,
        mirrorSyncedAt = "2026-01-01T00:00:00Z",
        modelAdaptationsJson = "[]",
        createdAt = "2026-01-01T00:00:00Z",
        status = "active",
        nameHistory = emptyList()
    )

    private fun persona(label: String, prompt: String) = PersonaObject(
        label = label, prompt = prompt, id = "p-abc"
    )

    @Test fun currentMirrorCausesNoUpdate() {
        val deps = FakeDeps().apply {
            companion = record("Aria", "You are Aria")
            persona = persona("Aria", "You are Aria")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertNotNull(result)
        assertEquals(0, deps.updateCount)
        assertEquals("You are Aria", result!!.mirrorText)
    }

    @Test fun staleMirrorIsRepaired() {
        val deps = FakeDeps().apply {
            companion = record("Aria", "old prompt")
            persona = persona("Aria", "new default prompt")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertNotNull(result)
        assertEquals(1, deps.updateCount)
        assertEquals("new default prompt", deps.lastUpdateMirror)
        assertEquals("new default prompt", result!!.mirrorText)
    }

    @Test fun switchedDefaultIsRepairedWhenSaveTimeSyncWasMissed() {
        val deps = FakeDeps().apply {
            companion = record("Twin", "You are A")
            persona = persona("Twin", "You are B")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertEquals(1, deps.updateCount)
        assertEquals("You are B", deps.lastUpdateMirror)
        assertEquals("You are B", result!!.mirrorText)
    }

    @Test fun refreshedRecordIsReturned() {
        val deps = FakeDeps().apply {
            companion = record("Aria", "stale")
            persona = persona("Aria", "fresh")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertEquals("returned record reflects the refresh", "fresh", result!!.mirrorText)
        assertEquals("Aria", result.currentName)
    }

    @Test fun staleNameIsReconciledAlongsideMirror() {
        val deps = FakeDeps().apply {
            companion = record("Old Name", "stale")
            persona = persona("New Name", "fresh")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertEquals(1, deps.updateCount)
        assertEquals("New Name", deps.lastUpdateLabel)
        assertEquals("fresh", deps.lastUpdateMirror)
        assertEquals("New Name", result!!.currentName)
        assertEquals("fresh", result.mirrorText)
    }

    @Test fun staleNameAloneTriggersReconciliation() {
        val deps = FakeDeps().apply {
            companion = record("Old Name", "same prompt")
            persona = persona("New Name", "same prompt")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertEquals(1, deps.updateCount)
        assertEquals("New Name", result!!.currentName)
        assertEquals("same prompt", result.mirrorText)
    }

    @Test fun storeFailureReturnNull() {
        val deps = object : MemoryCompanionSync.Deps {
            override fun findByAppCharacterId(personaId: String): CompanionRecord? = throw RuntimeException("store broken")
            override fun getPersona(personaId: String): PersonaObject? = null
            override fun insertCompanion(record: CompanionRecord) {}
            override fun updateMirror(companionId: String, appCharacterId: String, label: String, mirrorText: String) {}
        }

        var caught = false
        try {
            MemoryCompanionSync.resolveCompanion(deps, "p-abc")
        } catch (_: Exception) {
            caught = true
        }
        assertTrue("exception propagates to caller (ensureCompanionForPersona catches it)", caught)
    }

    @Test fun missingCompanionIsCreatedFromCurrentPersona() {
        val deps = FakeDeps().apply {
            companion = null
            persona = persona("Brand New", "hello world")
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertNotNull(result)
        assertEquals(1, deps.insertCount)
        assertEquals("Brand New", result!!.currentName)
        assertEquals("hello world", result.mirrorText)
    }

    @Test fun stalePersonaIdReturnsNull() {
        val deps = FakeDeps().apply {
            companion = null
            persona = null
        }

        val result = MemoryCompanionSync.resolveCompanion(deps, "p-abc")

        assertNull(result)
        assertEquals(0, deps.insertCount)
        assertEquals(0, deps.updateCount)
    }
}
