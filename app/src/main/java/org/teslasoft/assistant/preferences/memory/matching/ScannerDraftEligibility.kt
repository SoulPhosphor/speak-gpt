/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.matching

import org.teslasoft.assistant.preferences.memory.RetrievalScope
import org.teslasoft.assistant.preferences.memory.archivist.ArchivistResponseParser

/**
 * Whether a working draft may be compared in a scene's [RetrievalScope]
 * (Stage 6B.2). The same scope and target rule as filed memories
 * ([org.teslasoft.assistant.preferences.memory.MemoryStore.activeMemoriesForScope]):
 * global drafts always; real-life and project drafts outside roleplay, so an
 * earlier real-life draft stays discoverable after a companion or project
 * change; companion, world, campaign and roleplay-character drafts only for
 * their own target in this scene. Type plays no part.
 */
object ScannerDraftEligibility {

    fun eligible(draft: ArchivistResponseParser.DraftMemory, scope: RetrievalScope): Boolean {
        val targets = draft.targetIds.ifEmpty { listOfNotNull(draft.scene?.targetIdFor(draft.scope)) }
        return when (draft.scope) {
            "global" -> true
            "real_life", "project" -> !scope.isRoleplay
            "companion" -> scope.companionId != null &&
                (!scope.isRoleplay || scope.allowCompanionInRoleplay) &&
                scope.companionId in targets
            "world" -> scope.isRoleplay && scope.worldId != null && scope.worldId in targets
            "campaign" -> scope.isRoleplay && scope.campaignId != null && scope.campaignId in targets
            "rp_character" -> scope.isRoleplay && scope.roleplayCharacterId != null &&
                scope.roleplayCharacterId in targets
            else -> false
        }
    }
}
