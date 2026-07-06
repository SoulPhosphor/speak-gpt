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

import android.content.Context
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.util.Hash

/**
 * The app-persona <-> companion-record bridge (integration plan D6).
 *
 * The APP owns character configuration; the STORE owns continuity. Each app
 * persona links to a companion via companions.app_character_id =
 * Hash.hash(label). Because a persona rename changes that id (edit = delete +
 * recreate, a documented app invariant), the persona save path calls
 * [onPersonaSaved] with the old id so the link can follow the rename while the
 * stable companion_id keeps every memory attached.
 *
 * Everything here is best-effort by design: a memory-store hiccup must never
 * break saving a persona, so failures are logged and swallowed. Nothing runs
 * until the store has been provisioned (the user touched the memory system at
 * least once) — the hook must not create an encrypted database as a side
 * effect of editing a persona.
 */
object MemoryCompanionSync {

    /**
     * The bootstrap migration from app_adaptation_notes: create an ACTIVE
     * companion record for every existing app persona that doesn't have one
     * (user-created entries are canon — unlike Archivist drafts they need no
     * approval). Idempotent: personas already linked are left alone. Returns
     * the number of companions created.
     */
    fun bootstrapFromPersonas(context: Context): Int {
        val store = MemoryStore.getInstance(context)
        val personas = PersonaPreferences.getPersonaPreferences(context).getPersonasList()
        var created = 0
        for (persona in personas) {
            if (persona.label.isBlank()) continue
            val appCharacterId = Hash.hash(persona.label)
            if (store.findCompanionByAppCharacterId(appCharacterId) != null) continue
            store.insertCompanion(newCompanion(appCharacterId, persona))
            created++
        }
        store.setMeta(MemoryStore.META_BOOTSTRAP_DONE, "1")
        return created
    }

    /**
     * The lazy half of the bootstrap: resolve the companion for an app persona
     * id, creating the record on the spot when it doesn't exist yet. A chat
     * with a persona selected must ALWAYS resolve to a companion (owner
     * requirement, July 2026 — the manual bootstrap button had left every
     * pre-existing chat unattributed as "companion=none"). Returns null only
     * when the store isn't provisioned or the persona id is stale (a dangling
     * per-chat persona_id must not manufacture a ghost companion).
     */
    fun ensureCompanionForPersona(context: Context, personaId: String): CompanionRecord? {
        return try {
            if (personaId.isBlank() || !MemoryStore.isProvisioned(context)) return null
            val store = MemoryStore.getInstance(context)
            store.findCompanionByAppCharacterId(personaId)?.let { return it }
            val persona = PersonaPreferences.getPersonaPreferences(context).getPersona(personaId)
            if (persona.label.isBlank()) return null
            val record = newCompanion(personaId, persona)
            store.insertCompanion(record)
            MemoryLog.log(context, "MemorySync", "info",
                "Companion auto-created for persona \"${persona.label}\"")
            record
        } catch (e: Exception) {
            MemoryLog.log(context, "MemorySync", "error", "ensureCompanion failed: ${e.message}")
            null
        }
    }

    /**
     * Persona save hook. [oldPersonaId] is non-null only for renames (the edit
     * path), where the companion must be found under the pre-rename id.
     * New personas created after bootstrap get a companion automatically so
     * the store never falls behind the app's cast.
     */
    fun onPersonaSaved(context: Context, oldPersonaId: String?, persona: PersonaObject) {
        try {
            if (persona.label.isBlank()) return
            if (!MemoryStore.isProvisioned(context)) return
            val store = MemoryStore.getInstance(context)
            val newId = Hash.hash(persona.label)

            val existing = (if (oldPersonaId != null) store.findCompanionByAppCharacterId(oldPersonaId) else null)
                ?: store.findCompanionByAppCharacterId(newId)

            if (existing != null) {
                store.updateCompanionForPersona(existing.companionId, newId, persona.label, persona.prompt)
            } else if (store.getMeta(MemoryStore.META_BOOTSTRAP_DONE) == "1") {
                store.insertCompanion(newCompanion(newId, persona))
            }
        } catch (e: Exception) {
            // Persona saves must always succeed; the mirror can be re-synced on
            // the next edit or a bootstrap re-run.
            MemoryLog.log(context, "MemorySync", "error", "Persona->companion sync failed: ${e.message}")
        }
    }

    private fun newCompanion(appCharacterId: String, persona: PersonaObject): CompanionRecord {
        val now = MemoryStore.nowIso()
        return CompanionRecord(
            companionId = MemoryStore.newId("c-"),
            currentName = persona.label,
            // Essence stays empty for app-linked companions: the app's persona
            // prompt IS the personality (enforcer spec's essence guardrail);
            // essence only ever grows into a short continuity summary.
            essence = "",
            relationshipNotes = null,
            memoryParticipation = "full",
            hardLimitsJson = "[]",
            appCharacterId = appCharacterId,
            mirrorText = persona.prompt,
            mirrorSyncedAt = now,
            modelAdaptationsJson = "[]",
            createdAt = now,
            status = "active",
            nameHistory = listOf(NameHistoryEntry(persona.label, now, null))
        )
    }
}
