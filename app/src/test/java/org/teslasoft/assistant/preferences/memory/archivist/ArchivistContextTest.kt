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

package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.TranscriptRecord

class ArchivistContextTest {

    private fun transcript(
        id: String,
        companionId: String? = null,
        worldId: String? = null,
        campaignId: String? = null,
        roleplayCharacterId: String? = null,
        projectId: String? = null
    ) = TranscriptRecord(
        transcriptId = id,
        chatId = "chat",
        companionId = companionId,
        worldId = worldId,
        roleplayCharacterId = roleplayCharacterId,
        userPersonaId = null,
        campaignId = campaignId,
        projectId = projectId,
        source = "live",
        startedAt = "2026-08-08T00:00:0${id.last()}Z",
        endedAt = null,
        content = "[]",
        modelTag = "model",
        quickSettingsJson = null,
        reviewStatus = "pending",
        processedAt = null
    )

    @Test
    fun runtimeProtocolUsesOnlyRequestLocalAliasesAndCompleteBoundedMemories() {
        val scene = ArchivistSceneContext(
            companionId = "stable-companion-7",
            worldId = null,
            campaignId = null,
            roleplayCharacterId = null,
            projectId = "stable-project-9"
        )
        val memories = (1..14).map {
            ArchivistExistingMemory(
                stableId = "stable-memory-$it",
                content = "Complete memory $it",
                scope = "real_life",
                targetNames = emptyList(),
                typeName = "Fact"
            )
        }
        val protocol = ArchivistRuntimeProtocol.create(
            scene,
            memories,
            listOf(
                ArchivistTarget("stable-companion-7", "companion", "Slate", "active"),
                ArchivistTarget("stable-project-9", "project", "Memory Repair", "active")
            )
        )

        assertEquals(ArchivistRuntimeProtocol.MAX_MEMORIES_IN_PROMPT, protocol.memories.size)
        assertEquals("M1", protocol.memories.first().alias)
        assertEquals("T1", protocol.targets.first().alias)
        val rendered = ArchivistRuntimeProtocol.render(protocol)
        assertTrue(rendered.contains("\"ref\":\"M1\""))
        assertTrue(rendered.contains("\"ref\":\"T1\""))
        assertTrue(rendered.contains("related_existing_memory_refs"))
        assertFalse(rendered.contains("stable-memory-1"))
        assertFalse(rendered.contains("stable-companion-7"))
        assertFalse(rendered.contains("stable-project-9"))
    }

    @Test
    fun targetCatalogHonorsSceneWallAndCompanionIsolation() {
        val available = listOf(
            ArchivistTarget("comp-a", "companion", "A", "active"),
            ArchivistTarget("comp-b", "companion", "B", "active"),
            ArchivistTarget("world-a", "world", "World A", "active"),
            ArchivistTarget("world-b", "world", "World B", "active"),
            ArchivistTarget("campaign-a", "campaign", "Campaign A", "active"),
            ArchivistTarget("project-a", "project", "Project A", "active")
        )
        val selected = ArchivistTargetCatalog.select(
            scene = ArchivistSceneContext(
                companionId = "comp-a",
                worldId = "world-a",
                campaignId = "campaign-a",
                roleplayCharacterId = null,
                projectId = "project-a"
            ),
            eligibleCompanionId = "comp-a",
            relevantTargetIds = mapOf(
                "world" to setOf("world-a"),
                "project" to setOf("project-a")
            ),
            availableTargets = available
        )

        assertEquals(
            setOf("comp-a", "world-a", "campaign-a"),
            selected.map { it.stableId }.toSet()
        )
        assertFalse(selected.any { it.stableId == "comp-b" })
        assertFalse(selected.any { it.stableId == "world-b" })
        assertFalse("project is blocked in roleplay", selected.any { it.kind == "project" })
    }

    @Test
    fun ordinarySceneMayAddTargetsFromBoundedRelevantProjectMemories() {
        val selected = ArchivistTargetCatalog.select(
            scene = ArchivistSceneContext("comp-a", null, null, null, "project-a"),
            eligibleCompanionId = "comp-a",
            relevantTargetIds = mapOf("project" to setOf("project-b")),
            availableTargets = listOf(
                ArchivistTarget("comp-a", "companion", "A", "active"),
                ArchivistTarget("project-a", "project", "A", "active"),
                ArchivistTarget("project-b", "project", "B", "active"),
                ArchivistTarget("project-c", "project", "C", "active")
            )
        )
        assertEquals(
            setOf("comp-a", "project-a", "project-b"),
            selected.map { it.stableId }.toSet()
        )
    }

    @Test
    fun oversizedMemoryIsOmittedWholeInsteadOfTruncated() {
        val oversized = "x".repeat(ArchivistRuntimeProtocol.MAX_EXISTING_MEMORY_CHARS + 1)
        val protocol = ArchivistRuntimeProtocol.create(
            ArchivistSceneContext(null, null, null, null, null),
            listOf(
                ArchivistExistingMemory("too-big", oversized, "global", emptyList(), null),
                ArchivistExistingMemory("fits", "complete small memory", "global", emptyList(), null)
            ),
            emptyList()
        )

        assertEquals(listOf("fits"), protocol.memories.map { it.memory.stableId })
        assertEquals("complete small memory", protocol.memories.single().memory.content)
    }
}
