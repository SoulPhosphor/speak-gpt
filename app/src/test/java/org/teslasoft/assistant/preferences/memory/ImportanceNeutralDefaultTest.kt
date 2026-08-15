package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the owner rule that an unassigned memory is neutral. */
class ImportanceNeutralDefaultTest {

    @Test
    fun backupMemoryWithoutImportanceParsesAsNeutralZero() {
        val json = """
            {
              "schema_version": "1.11.0",
              "companions": [],
              "entities": [],
              "memories": [
                {
                  "memory_id": "m-no-importance",
                  "scope": "global",
                  "content": "A memory with no explicit importance field.",
                  "created_at": "2026-08-15T00:00:00Z",
                  "status": "active"
                }
              ],
              "modes": [],
              "directives": [],
              "worlds": [],
              "user_personas": [],
              "roleplay_characters": [],
              "proposals": []
            }
        """.trimIndent()

        val parsed = MemorySeedCodec.parse(json)
        assertEquals(0, parsed.memories.single().importance)

        val roundTripped = MemorySeedCodec.parse(MemorySeedCodec.serialize(parsed))
        assertEquals(0, roundTripped.memories.single().importance)
    }
}
