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

/**
 * The app-persona <-> companion-record bridge (integration plan D6).
 *
 * The APP owns character configuration; the STORE owns continuity. Each app
 * persona links to a companion via companions.app_character_id = the persona's
 * STABLE id ([PersonaObject.id]). Because that id no longer changes on rename
 * (July 2026 stable-id fix — it used to be Hash.hash(label), so a rename was a
 * delete+recreate under a new id), the save path locates the companion by the
 * persona's own id and a rename simply updates the existing record's name;
 * the stable companion_id keeps every memory attached. Existing companions
 * created before the fix keep their app_character_id = the persona's legacy
 * hashed id, which is exactly the id existing personas still carry.
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
            if (persona.label.isBlank() || persona.id.isBlank()) continue
            val appCharacterId = persona.id
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
     * Persona save hook. The companion is located by the persona's STABLE id,
     * so a rename (same id, new label) updates the existing record's name
     * rather than creating a second companion. New personas created after
     * bootstrap get a companion automatically so the store never falls behind
     * the app's cast.
     */
    fun onPersonaSaved(context: Context, persona: PersonaObject) {
        try {
            if (persona.label.isBlank() || persona.id.isBlank()) return
            if (!MemoryStore.isProvisioned(context)) return
            val store = MemoryStore.getInstance(context)
            val appCharacterId = persona.id

            val existing = store.findCompanionByAppCharacterId(appCharacterId)

            if (existing != null) {
                store.updateCompanionForPersona(existing.companionId, appCharacterId, persona.label, persona.prompt)
            } else if (store.getMeta(MemoryStore.META_BOOTSTRAP_DONE) == "1") {
                store.insertCompanion(newCompanion(appCharacterId, persona))
            }
        } catch (e: Exception) {
            // Persona saves must always succeed; the mirror can be re-synced on
            // the next edit or a bootstrap re-run.
            MemoryLog.log(context, "MemorySync", "error", "Persona->companion sync failed: ${e.message}")
        }
    }

    /**
     * Persona delete hook (owner ruling, July 20 2026). Deleting a companion
     * from the app now ALSO deletes its memory-store record and the memories
     * owned solely by it — memories shared with another companion survive with
     * this companion's link removed (the sole-owner rule in
     * [MemoryStore.deleteCompanion] / TargetTeardownPlanner). This reverses the
     * old "profile-only delete, memories dangle" behaviour: a dangling record
     * could only ever be reached again by recreating a companion with the exact
     * same name, which the owner judged more surprising than useful.
     *
     * Best-effort like the rest of this bridge: a store failure must never
     * break deleting the app persona, so failures are logged and swallowed.
     * Runs nothing until the store is provisioned, and no-ops when this persona
     * never had a companion record.
     */
    fun onPersonaDeleted(context: Context, personaId: String) {
        try {
            if (personaId.isBlank() || !MemoryStore.isProvisioned(context)) return
            val store = MemoryStore.getInstance(context)
            val existing = store.findCompanionByAppCharacterId(personaId) ?: return
            store.deleteCompanion(existing.companionId, deleteMemories = true)
        } catch (e: Exception) {
            // Persona deletes must always succeed; an orphaned companion record
            // can be cleaned up later and never blocks the app-side delete.
            MemoryLog.log(context, "MemorySync", "error", "Persona->companion delete failed: ${e.message}")
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
