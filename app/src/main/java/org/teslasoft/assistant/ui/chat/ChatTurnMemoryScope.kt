/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.chat

import android.content.Context
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.scan.ScanMessageScope
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter

/**
 * What the memory system does when a chat turn finishes (Stage 6A). It
 * replaces the copy of every turn into the memory database:
 *
 *  - the companion, world, campaign, roleplay character, user persona and
 *    project in effect are recorded on the turn's messages in the original
 *    chat storage, whether or not the memory database is set up (memory
 *    development plan M.2, "Scope of each message");
 *  - a companion chatted with for the first time still gets its memory
 *    record (M.6), as the transcript capture used to do.
 */
object ChatTurnMemoryScope {

    /** The scope in effect for this chat now, read from its settings. */
    fun current(preferences: Preferences?): ScanMessageScope = ScanMessageScope(
        companionPersonaId = preferences?.getPersonaId()?.takeIf { it.isNotBlank() },
        worldId = preferences?.getChatWorldId()?.takeIf { it.isNotBlank() },
        campaignId = preferences?.getChatCampaignId()?.takeIf { it.isNotBlank() },
        roleplayCharacterId = preferences?.getChatRoleplayCharacterId()?.takeIf { it.isNotBlank() },
        userPersonaId = preferences?.getChatUserPersonaId()?.takeIf { it.isNotBlank() },
        projectId = preferences?.getChatProjectId()?.takeIf { it.isNotBlank() }
    )

    /**
     * Records [scope] on the finished turn: the reply (each finish of a
     * regenerated reply records the scope it was generated under) and the
     * user message it answers, if that message has no scope yet. Returns
     * true when a message changed and the chat must be saved.
     */
    fun stamp(messages: List<HashMap<String, Any>>, scope: ScanMessageScope): Boolean {
        val last = messages.lastOrNull() ?: return false
        if (last["isBot"] != true) return false
        var changed = ScanMessageScope.write(last, scope)
        if (changed) stampCanonicalVersion(last, scope)
        val user = messages.subList(0, messages.size - 1).lastOrNull { it["isBot"] != true }
        if (user != null && !ScanMessageScope.has(user)) {
            changed = ScanMessageScope.write(user, scope) || changed
        }
        return changed
    }

    /** A regenerated reply keeps its versions; the one just finished (the
     *  canonical version) records the same scope as the reply itself. */
    private fun stampCanonicalVersion(reply: HashMap<String, Any>, scope: ScanMessageScope) {
        val versions = ChatAdapter.parseVariants(reply[ChatAdapter.KEY_VARIANTS]?.toString())
        if (versions.isEmpty()) return
        val canonical = reply[ChatAdapter.KEY_CANONICAL_VARIANT]?.toString()?.toIntOrNull() ?: return
        val version = versions.getOrNull(canonical) ?: return
        version[ScanMessageScope.KEY] = scope.encode()
        reply[ChatAdapter.KEY_VARIANTS] = ChatAdapter.variantsToJson(versions)
    }

    /** The memory record of a companion chatted with for the first time
     *  (M.6). Worker thread only; best effort. */
    fun ensureCompanion(context: Context, personaId: String?) {
        if (personaId.isNullOrBlank()) return
        try {
            if (!MemoryStore.isProvisioned(context)) return
            MemoryCompanionSync.ensureCompanionForPersona(context, personaId)
        } catch (_: Exception) {
            // Memory is best-effort and must never disturb the chat.
        }
    }
}
