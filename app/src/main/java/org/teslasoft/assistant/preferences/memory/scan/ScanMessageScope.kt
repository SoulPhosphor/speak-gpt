/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.json.JSONObject

/**
 * The companion, world, campaign, roleplay character, user persona and
 * project in effect when a message's turn finished (memory development plan
 * M.2, "Scope of each message"). It is saved as metadata on the message in
 * the original chat storage, whether or not the memory database is set up.
 *
 * A message without this metadata has unknown scope. Unknown scope is never
 * filled in from the chat's present settings; the Memory Scanner reads such a
 * message with no companion, project or roleplay placement, so its
 * suggestions are general (owner decision, Stage 6A question 2).
 *
 * [companionPersonaId] is the app's persona id (the memory companion's
 * app_character_id); [companionId] is a memory-database companion id, used
 * only when the migration found a recorded companion that has no persona id.
 */
data class ScanMessageScope(
    val companionPersonaId: String? = null,
    val companionId: String? = null,
    val worldId: String? = null,
    val campaignId: String? = null,
    val roleplayCharacterId: String? = null,
    val userPersonaId: String? = null,
    val projectId: String? = null
) {
    fun encode(): String = JSONObject().apply {
        put(VERSION, 1)
        companionPersonaId?.takeIf { it.isNotBlank() }?.let { put(PERSONA, it) }
        companionId?.takeIf { it.isNotBlank() }?.let { put(COMPANION, it) }
        worldId?.takeIf { it.isNotBlank() }?.let { put(WORLD, it) }
        campaignId?.takeIf { it.isNotBlank() }?.let { put(CAMPAIGN, it) }
        roleplayCharacterId?.takeIf { it.isNotBlank() }?.let { put(CHARACTER, it) }
        userPersonaId?.takeIf { it.isNotBlank() }?.let { put(USER_PERSONA, it) }
        projectId?.takeIf { it.isNotBlank() }?.let { put(PROJECT, it) }
    }.toString()

    companion object {
        /** The message map key holding the encoded scope. */
        const val KEY = "memoryScope"

        private const val VERSION = "v"
        private const val PERSONA = "persona"
        private const val COMPANION = "companion"
        private const val WORLD = "world"
        private const val CAMPAIGN = "campaign"
        private const val CHARACTER = "character"
        private const val USER_PERSONA = "userPersona"
        private const val PROJECT = "project"

        /** The recorded scope, or null when the message's scope is unknown. */
        fun read(message: Map<String, *>): ScanMessageScope? =
            decode(message[KEY]?.toString())

        fun decode(raw: String?): ScanMessageScope? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                fun text(key: String) = o.optString(key, "").takeIf { it.isNotBlank() }
                ScanMessageScope(
                    companionPersonaId = text(PERSONA),
                    companionId = text(COMPANION),
                    worldId = text(WORLD),
                    campaignId = text(CAMPAIGN),
                    roleplayCharacterId = text(CHARACTER),
                    userPersonaId = text(USER_PERSONA),
                    projectId = text(PROJECT)
                )
            } catch (_: Exception) {
                null
            }
        }

        fun has(message: Map<String, *>): Boolean = read(message) != null

        /** Records [scope] on [message]. Returns true when the stored value changed. */
        fun write(message: MutableMap<String, Any>, scope: ScanMessageScope): Boolean {
            val encoded = scope.encode()
            if (message[KEY]?.toString() == encoded) return false
            message[KEY] = encoded
            return true
        }
    }
}
