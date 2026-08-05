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

package org.teslasoft.assistant.preferences.backup.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure restore-planning rules (companion-roleplay-backup-plan.md §6.3/§6.4):
 * given the parsed manifest and the set of lorebook ids that exist on this
 * device, decide the exact preference values to write and which
 * companion -> lorebook connections are removed (the one §6.4 removal that is
 * user-visible, reported by lorebook name). No Android APIs — every rule is
 * unit-testable on the JVM.
 */
object CompanionRestorePlanner {

    data class Plan(
        /** The §6.3 step-3 values to write (built from the backup). */
        val settingsNew: CompanionSettingsPayload,
        /** Removed companion -> lorebook connections, in manifest order (§9). */
        val removedLinks: List<RemovedLorebookLink>
    )

    /**
     * [existingLorebookIds] — the lorebooks present on this device (empty when
     * the device has none). A link that resolves is kept; one that does not is
     * removed and reported. Last-used bookkeeping ids that no longer resolve
     * are dropped silently (invisible bookkeeping, §6.4). The activation-
     * prompt link always reconnects because the prompts ride in the backup.
     */
    fun plan(
        manifest: CompanionBackupManifest,
        existingLorebookIds: Set<String>
    ): Plan {
        val removed = ArrayList<RemovedLorebookLink>()
        val personas = LinkedHashMap<String, Any?>()

        for (p in manifest.companionProfiles) {
            // Removed connections are reported by NAME, never by internal id
            // (owner ruling, August 5 2026). The export and the codec both
            // guarantee every carried link has its name; the orEmpty() below
            // is unreachable for a validated file.
            val coreResolves = p.coreLoreBookId.isBlank() ||
                p.coreLoreBookId in existingLorebookIds
            if (!coreResolves) {
                removed.add(RemovedLorebookLink(p.label, p.coreLoreBookName.orEmpty()))
            }
            val keptAdditional = ArrayList<String>()
            for (id in p.additionalLoreBookIds) {
                if (id in existingLorebookIds) {
                    keptAdditional.add(id)
                } else {
                    removed.add(
                        RemovedLorebookLink(p.label, p.additionalLoreBookNames[id].orEmpty())
                    )
                }
            }
            val keptLastUsed = p.lastUsedLoreBookIds.filter { it in existingLorebookIds }

            personas[p.id + "_label"] = p.label
            personas[p.id + "_prompt"] = p.prompt
            personas[p.id + "_activation_prompt_id"] = p.activationPromptId
            personas[p.id + "_core_lorebook_id"] = if (coreResolves) p.coreLoreBookId else ""
            personas[p.id + "_additional_lorebook_ids"] = keptAdditional.joinToString(",")
            personas[p.id + "_autoload_last_lorebooks"] =
                if (p.autoLoadLastLoreBooks) "true" else "false"
            personas[p.id + "_last_used_lorebook_ids"] = keptLastUsed.joinToString(",")
            personas[p.id + "_avatar_ref"] = p.avatarRef
        }

        val activation = LinkedHashMap<String, Any?>()
        for (a in manifest.activationPrompts) {
            activation[a.id + "_label"] = a.label
            activation[a.id + "_prompt"] = a.prompt
        }

        // The system prompts library keeps its stored shape: one ordered JSON
        // array under "list" plus the selected id (SystemPromptsPreferences).
        val entries = JSONArray()
        for (s in manifest.systemPrompts) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("title", s.title)
            o.put("body", s.body)
            entries.put(o)
        }
        val systemPrompts = LinkedHashMap<String, Any?>()
        systemPrompts["list"] = entries.toString()
        systemPrompts["selected_id"] = manifest.selectedSystemPromptId

        return Plan(
            settingsNew = CompanionSettingsPayload(
                personas = personas,
                activationPrompts = activation,
                systemPrompts = systemPrompts,
                systemMessage = effectiveSystemMessage(manifest)
            ),
            removedLinks = removed
        )
    }

    /**
     * The §2.3 re-mirror: the effective prompt is the selected entry if it
     * exists, otherwise the top of the list, otherwise nothing — exactly what
     * the library does after any change (SystemPromptsPreferences).
     */
    fun effectiveSystemMessage(manifest: CompanionBackupManifest): String {
        val list = manifest.systemPrompts
        if (list.isEmpty()) return ""
        val selected = list.firstOrNull { it.id == manifest.selectedSystemPromptId }
        return (selected ?: list.first()).body
    }

    /**
     * The startup-recovery decision (§6.3 crash protection): an interrupted
     * restore rolls FORWARD only when its database transaction provably
     * committed — the journal's token is present in the store's meta table.
     * Everything else rolls back to the captured snapshot. A restore that
     * never involved the database has no commit pivot, so it always rolls
     * back; re-running the restore is cheap and safe.
     */
    fun shouldRollForward(dbInvolved: Boolean, tokenInStore: String?, journalToken: String): Boolean =
        dbInvolved && tokenInStore == journalToken
}
