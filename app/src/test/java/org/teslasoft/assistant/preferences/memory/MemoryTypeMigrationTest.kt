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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 1 legacy-kind → Type migration decision (canonical recovery plan
 * §5, Phase 1 item 4). This is the shared logic both the store migration and
 * the backup import run, so it carries the owner's required migration cases:
 * every legacy kind maps correctly, `lore` becomes No Type, and an unknown or
 * absent kind becomes No Type rather than dropping the memory.
 */
class MemoryTypeMigrationTest {

    @Test
    fun seedsExactlyTheFiveStarterTypes() {
        val names = MemoryTypeMigration.STARTER_TYPES.map { it.name }
        assertEquals(listOf("Fact", "Preference", "Event", "Status", "Instruction"), names)
    }

    @Test
    fun starterTypeIdsAreStableAndUnique() {
        val ids = MemoryTypeMigration.STARTER_TYPES.map { it.typeId }
        assertEquals("no duplicate starter type ids", ids.size, ids.toSet().size)
        // Lock the exact ids: a migration and every stored type_id depend on
        // these never changing.
        assertEquals(
            listOf(
                "mtype-fact", "mtype-preference", "mtype-event",
                "mtype-status", "mtype-instruction"
            ),
            ids
        )
    }

    @Test
    fun everyRecognizedLegacyKindMapsToItsSeededType() {
        assertEquals("mtype-fact", MemoryTypeMigration.typeIdForLegacyKind("fact"))
        assertEquals("mtype-preference", MemoryTypeMigration.typeIdForLegacyKind("preference"))
        assertEquals("mtype-event", MemoryTypeMigration.typeIdForLegacyKind("event"))
        assertEquals("mtype-status", MemoryTypeMigration.typeIdForLegacyKind("status"))
        assertEquals("mtype-instruction", MemoryTypeMigration.typeIdForLegacyKind("instruction"))
    }

    @Test
    fun legacyLoreBecomesNoType() {
        assertNull(MemoryTypeMigration.typeIdForLegacyKind("lore"))
        assertTrue(MemoryTypeMigration.isLegacyLore("lore"))
        assertTrue(MemoryTypeMigration.isLegacyLore("Lore"))
    }

    @Test
    fun unknownAbsentOrBlankKindBecomesNoTypeNotADrop() {
        assertNull(MemoryTypeMigration.typeIdForLegacyKind(null))
        assertNull(MemoryTypeMigration.typeIdForLegacyKind(""))
        assertNull(MemoryTypeMigration.typeIdForLegacyKind("   "))
        assertNull(MemoryTypeMigration.typeIdForLegacyKind("identity"))
        assertNull(MemoryTypeMigration.typeIdForLegacyKind("classic_cars"))
    }

    @Test
    fun mappingIsCaseAndWhitespaceTolerant() {
        assertEquals("mtype-fact", MemoryTypeMigration.typeIdForLegacyKind("  FACT "))
        assertEquals("mtype-instruction", MemoryTypeMigration.typeIdForLegacyKind("Instruction"))
    }
}
