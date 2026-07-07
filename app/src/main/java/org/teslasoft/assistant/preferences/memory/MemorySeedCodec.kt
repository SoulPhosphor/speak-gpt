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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Seeds, backups and cross-device transfers all use ONE format: the schema
 * shape from `Memory System/companion_memory_schema.json` (v1.11). This codec
 * translates that JSON to/from [MemoryStoreData]; MemoryStore does the SQL.
 *
 * Two deliberate extensions beyond the schema's record types, both ignored by
 * any strict schema reader: a top-level `transcripts` array (exports must
 * carry the raw material the store can be re-derived from) and `app_chats`
 * (the app's own chat history rides along so "chats & memories" both travel
 * to a future device — imported chats are handled in the sync phase, not
 * here). `export_meta` labels the file. change_log `prior_state` snapshots
 * are device-local undo state and are NOT exported.
 *
 * Pure Kotlin + org.json only — unit-tested on the JVM (MemorySeedCodecTest).
 */
object MemorySeedCodec {

    const val EXPORT_FORMAT = "companion-memory-export-v1"

    /* ------------------------------ helpers ------------------------------ */

    /** Absent OR JSON-null both mean "no value" — optString would return "" or "null". */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.reqStr(key: String): String = getString(key)

    /** JSON column passthrough: array field -> compact JSON text (default "[]"). */
    private fun JSONObject.arrText(key: String): String =
        if (!has(key) || isNull(key)) "[]" else getJSONArray(key).toString()

    /** JSON column passthrough: object field -> compact JSON text, or null. */
    private fun JSONObject.objText(key: String): String? =
        if (!has(key) || isNull(key)) null else getJSONObject(key).toString()

    private fun JSONObject.strList(key: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        val arr = getJSONArray(key)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun jsonArrayOrEmpty(text: String?): JSONArray =
        if (text.isNullOrBlank()) JSONArray() else JSONArray(text)

    /** Multi-target set (§2): the plural array key, else a legacy single key
     *  wrapped as one element, else empty — so pre-restructure backups still
     *  import their single world/campaign/RP/project link. */
    private fun JSONObject.targetSet(arrayKey: String, singleKey: String): List<String> {
        val arr = strList(arrayKey)
        return if (arr.isNotEmpty()) arr else listOfNotNull(str(singleKey))
    }

    private fun JSONObject.putJsonText(key: String, text: String?) {
        if (text.isNullOrBlank()) return
        val trimmed = text.trim()
        when {
            trimmed.startsWith("[") -> put(key, JSONArray(trimmed))
            trimmed.startsWith("{") -> put(key, JSONObject(trimmed))
            // proposed_change may be any JSON type; a bare string round-trips as-is
            else -> put(key, trimmed)
        }
    }

    private fun JSONObject.putIfNotNull(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun each(obj: JSONObject, key: String): List<JSONObject> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        val arr = obj.getJSONArray(key)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /* ------------------------------- parse ------------------------------- */

    fun parse(jsonText: String): MemoryStoreData {
        val root = JSONObject(jsonText)

        val owner = if (root.has("owner_profile") && !root.isNull("owner_profile")) {
            val o = root.getJSONObject("owner_profile")
            OwnerProfile(
                portrait = o.reqStr("portrait"),
                standingContext = o.str("standing_context"),
                updatedAt = o.str("updated_at")
            )
        } else null

        val companions = each(root, "companions").map { c ->
            val mirror = if (c.has("base_personality_mirror") && !c.isNull("base_personality_mirror"))
                c.getJSONObject("base_personality_mirror") else null
            CompanionRecord(
                companionId = c.reqStr("companion_id"),
                currentName = c.reqStr("current_name"),
                essence = c.reqStr("essence"),
                relationshipNotes = c.str("relationship_notes"),
                memoryParticipation = c.str("memory_participation") ?: "full",
                hardLimitsJson = c.arrText("hard_limits"),
                appCharacterId = c.str("app_character_id"),
                mirrorText = mirror?.str("text"),
                mirrorSyncedAt = mirror?.str("synced_at"),
                modelAdaptationsJson = c.arrText("model_adaptations"),
                createdAt = c.str("created_at") ?: "",
                status = c.reqStr("status"),
                origin = c.str("origin") ?: "user",
                nameHistory = each(c, "name_history").map { h ->
                    NameHistoryEntry(
                        name = h.reqStr("name"),
                        effectiveFrom = h.str("effective_from") ?: "",
                        effectiveUntil = h.str("effective_until")
                    )
                }
            )
        }

        val entities = each(root, "entities").map { e ->
            EntityRecord(
                entityId = e.reqStr("entity_id"),
                kind = e.reqStr("kind"),
                name = e.reqStr("name"),
                aliasesJson = e.arrText("aliases"),
                summary = e.reqStr("summary"),
                status = e.str("status"),
                importance = e.optInt("importance", 3),
                lastTouched = e.str("last_touched"),
                origin = e.str("origin") ?: "user"
            )
        }

        val memories = each(root, "memories").map { m ->
            val prov = if (m.has("provenance") && !m.isNull("provenance")) m.getJSONObject("provenance") else null
            MemoryRecord(
                memoryId = m.reqStr("memory_id"),
                scope = m.reqStr("scope"),
                kind = m.reqStr("kind"),
                title = m.reqStr("title"),
                content = m.reqStr("content"),
                embeddingText = m.str("embedding_text"),
                tagsJson = m.arrText("tags"),
                importance = m.optInt("importance", 3),
                worldIds = m.targetSet("world_ids", "world_id"),
                roleplayCharacterIds = m.targetSet("roleplay_character_ids", "roleplay_character_id"),
                campaignIds = m.targetSet("campaign_ids", "campaign_id"),
                projectIds = m.targetSet("project_ids", "project_id"),
                protectionJson = m.objText("protection"),
                modeHintsJson = m.arrText("mode_hints"),
                provenanceSource = prov?.str("source"),
                provenanceConfidence = prov?.str("confidence"),
                provenanceNotedOn = prov?.str("noted_on"),
                provenanceContext = prov?.str("context"),
                createdAt = m.reqStr("created_at"),
                updatedAt = m.str("updated_at"),
                status = m.reqStr("status"),
                supersedes = m.str("supersedes"),
                origin = m.str("origin") ?: "user",
                companionIds = m.strList("companion_ids"),
                entityRefs = m.strList("entity_refs"),
                changeLog = each(m, "change_log").map { l ->
                    ChangeLogEntry(
                        at = l.str("at") ?: "",
                        actor = l.str("actor") ?: "system",
                        action = l.str("action") ?: "",
                        note = l.str("note"),
                        priorStateJson = null
                    )
                }
            )
        }

        val modes = each(root, "modes").map { m ->
            ModeRecord(
                modeId = m.reqStr("mode_id"),
                name = m.reqStr("name"),
                purpose = m.str("purpose"),
                signalsJson = m.arrText("signals"),
                respondJson = m.arrText("respond"),
                avoidJson = m.arrText("avoid"),
                transitionNote = m.str("transition_note"),
                overridesJson = m.arrText("overrides"),
                scope = m.str("scope") ?: "global",
                companionIdsJson = m.arrText("companion_ids"),
                origin = m.str("origin") ?: "user"
            )
        }

        val directives = each(root, "directives").map { d ->
            DirectiveRecord(
                directiveId = d.reqStr("directive_id"),
                text = d.reqStr("text"),
                rationale = d.str("rationale"),
                appliesToJson = d.arrText("applies_to"),
                priority = d.optInt("priority", 3),
                origin = d.str("origin") ?: "user"
            )
        }

        val worlds = each(root, "worlds").map { w ->
            WorldRecord(
                worldId = w.reqStr("world_id"),
                name = w.reqStr("name"),
                premise = w.reqStr("premise"),
                rules = w.str("rules"),
                cosmology = w.str("cosmology"),
                premiseVibe = w.str("premise_vibe"),
                magicRules = w.str("magic_rules"),
                companionIdsJson = w.arrText("companion_ids"),
                status = w.reqStr("status"),
                createdAt = w.str("created_at")
            )
        }

        val userPersonas = each(root, "user_personas").map { p ->
            UserPersonaRecord(
                personaId = p.reqStr("persona_id"),
                name = p.reqStr("name"),
                presentation = p.reqStr("presentation"),
                status = p.reqStr("status"),
                createdAt = p.str("created_at")
            )
        }

        val roleplayCharacters = each(root, "roleplay_characters").map { r ->
            RoleplayCharacterRecord(
                roleplayCharacterId = r.reqStr("roleplay_character_id"),
                name = r.reqStr("name"),
                playedBy = r.reqStr("played_by"),
                description = r.reqStr("description"),
                arc = r.str("arc"),
                worldsPlayedJson = r.arrText("worlds_played"),
                status = r.reqStr("status"),
                createdAt = r.str("created_at"),
                species = r.str("species"),
                charClass = r.str("class"),
                corePersonality = r.str("core_personality"),
                physicalDescription = r.str("physical_description"),
                goalsDrives = r.str("goals_drives")
            )
        }

        val archivist = if (root.has("archivist_settings") && !root.isNull("archivist_settings")) {
            val a = root.getJSONObject("archivist_settings")
            ArchivistSettingsRecord(
                runTrigger = a.str("trigger") ?: "manual",
                harvestGenerosity = a.str("harvest_generosity") ?: "generous",
                autonomyJson = a.objText("autonomy") ?: "{}",
                notes = a.str("notes")
            )
        } else null

        val proposals = each(root, "proposals").map { p ->
            val change = if (!p.has("proposed_change") || p.isNull("proposed_change")) null
            else p.get("proposed_change").toString()
            ProposalRecord(
                proposalId = p.reqStr("proposal_id"),
                targetType = p.reqStr("target_type"),
                targetId = p.str("target_id"),
                summary = p.reqStr("summary"),
                proposedChangeJson = change,
                rationale = p.str("rationale"),
                status = p.reqStr("status"),
                createdAt = p.reqStr("created_at"),
                resolvedAt = p.str("resolved_at")
            )
        }

        val campaigns = each(root, "campaigns").map { c ->
            CampaignRecord(
                campaignId = c.reqStr("campaign_id"),
                name = c.reqStr("name"),
                worldId = c.str("world_id"),
                roleplayCharacterId = c.str("roleplay_character_id"),
                companionId = c.str("companion_id"),
                status = c.str("status") ?: "active",
                storySoFar = c.str("story_so_far"),
                createdAt = c.str("created_at"),
                questAnchor = c.str("quest_anchor"),
                activeScene = c.str("active_scene"),
                partyMemberIds = c.strList("party_member_ids")
            )
        }

        val projects = each(root, "projects").map { p ->
            ProjectRecord(
                projectId = p.reqStr("project_id"),
                name = p.reqStr("name"),
                status = p.str("status") ?: "active",
                createdAt = p.str("created_at"),
                updatedAt = p.str("updated_at")
            )
        }

        // Roleplay cards + tags (Stage 3.6a) — backups carry the card layer.
        val partyMembers = each(root, "party_members").map { p ->
            PartyMemberRecord(
                partyMemberId = p.reqStr("party_member_id"),
                name = p.reqStr("name"),
                species = p.str("species"),
                charClass = p.str("class"),
                corePersonality = p.str("core_personality"),
                physicalDescription = p.str("physical_description"),
                goalsDrives = p.str("goals_drives"),
                speechStyle = p.str("speech_style"),
                status = p.str("status") ?: "alive",
                archived = p.optBoolean("archived", false),
                createdAt = p.str("created_at") ?: "",
                updatedAt = p.str("updated_at")
            )
        }

        val cardEntries = each(root, "card_entries").map { e ->
            CardEntryRecord(
                entryId = e.reqStr("entry_id"),
                cardType = e.reqStr("card_type"),
                cardId = e.reqStr("card_id"),
                section = e.reqStr("section"),
                name = e.reqStr("name"),
                description = e.str("description"),
                entryKind = e.str("entry_kind"),
                quantity = if (e.has("quantity") && !e.isNull("quantity")) e.getInt("quantity") else null,
                parentEntryId = e.str("parent_entry_id"),
                worldEntryId = e.str("world_entry_id"),
                partyMemberId = e.str("party_member_id"),
                holder = e.str("holder"),
                significance = e.str("significance"),
                castIdentity = e.str("cast_identity"),
                castDisposition = e.str("cast_disposition"),
                castStatus = e.str("cast_status"),
                locationCondition = e.str("location_condition"),
                locationChanges = e.str("location_changes"),
                createdAt = e.str("created_at") ?: "",
                updatedAt = e.str("updated_at")
            )
        }

        val rpTags = each(root, "rp_tags").map { t ->
            RpTagRecord(
                tagId = t.reqStr("tag_id"),
                name = t.reqStr("name"),
                autoTrigger = t.optBoolean("auto_trigger", true),
                createdAt = t.str("created_at"),
                targets = each(t, "targets").map { l -> l.reqStr("type") to l.reqStr("id") }
            )
        }

        // Model rules (Stage 4, rules §11) — user-authored, so backups carry them.
        val modelRuleProfiles = each(root, "model_rule_profiles").map { p ->
            ModelRuleProfileRecord(
                profileId = p.reqStr("profile_id"),
                nickname = p.reqStr("nickname"),
                modelStringsJson = p.arrText("model_strings"),
                createdAt = p.str("created_at") ?: "",
                updatedAt = p.str("updated_at")
            )
        }

        val modelRules = each(root, "model_rules").map { r ->
            ModelRuleRecord(
                ruleId = r.reqStr("rule_id"),
                profileId = r.str("profile_id"),
                text = r.reqStr("text"),
                status = r.str("status") ?: "active",
                sourceModelString = r.str("source_model_string"),
                createdAt = r.str("created_at") ?: "",
                updatedAt = r.str("updated_at")
            )
        }

        val retrievalPolicy = if (root.has("retrieval_policy") && !root.isNull("retrieval_policy"))
            root.getJSONObject("retrieval_policy").toString() else null

        val transcripts = each(root, "transcripts").map { t ->
            TranscriptRecord(
                transcriptId = t.reqStr("transcript_id"),
                chatId = t.str("chat_id"),
                companionId = t.str("companion_id"),
                worldId = t.str("world_id"),
                roleplayCharacterId = t.str("roleplay_character_id"),
                userPersonaId = t.str("user_persona_id"),
                source = t.str("source") ?: "live",
                startedAt = t.str("started_at"),
                endedAt = t.str("ended_at"),
                content = t.reqStr("content"),
                modelTag = t.str("model_tag"),
                quickSettingsJson = t.objText("quick_settings"),
                reviewStatus = t.str("review_status") ?: "pending",
                processedAt = t.str("processed_at")
            )
        }

        return MemoryStoreData(
            schemaVersion = root.str("schema_version") ?: "1.11.0",
            ownerProfile = owner,
            companions = companions,
            entities = entities,
            memories = memories,
            modes = modes,
            directives = directives,
            worlds = worlds,
            userPersonas = userPersonas,
            roleplayCharacters = roleplayCharacters,
            archivistSettings = archivist,
            proposals = proposals,
            retrievalPolicyJson = retrievalPolicy,
            transcripts = transcripts,
            campaigns = campaigns,
            projects = projects,
            partyMembers = partyMembers,
            cardEntries = cardEntries,
            rpTags = rpTags,
            modelRuleProfiles = modelRuleProfiles,
            modelRules = modelRules
        )
    }

    /* ----------------------------- serialize ----------------------------- */

    fun serialize(data: MemoryStoreData, appChats: JSONArray? = null, exportedAtIso: String? = null): String {
        val root = JSONObject()
        root.put("schema_version", data.schemaVersion)

        data.ownerProfile?.let { o ->
            val obj = JSONObject()
            obj.put("portrait", o.portrait)
            obj.putIfNotNull("standing_context", o.standingContext)
            obj.putIfNotNull("updated_at", o.updatedAt)
            root.put("owner_profile", obj)
        }

        root.put("companions", JSONArray().apply {
            data.companions.forEach { c ->
                put(JSONObject().apply {
                    put("companion_id", c.companionId)
                    put("current_name", c.currentName)
                    put("essence", c.essence)
                    putIfNotNull("relationship_notes", c.relationshipNotes)
                    put("memory_participation", c.memoryParticipation)
                    put("hard_limits", jsonArrayOrEmpty(c.hardLimitsJson))
                    putIfNotNull("app_character_id", c.appCharacterId)
                    if (c.mirrorText != null || c.mirrorSyncedAt != null) {
                        put("base_personality_mirror", JSONObject().apply {
                            putIfNotNull("text", c.mirrorText)
                            putIfNotNull("synced_at", c.mirrorSyncedAt)
                        })
                    }
                    put("model_adaptations", jsonArrayOrEmpty(c.modelAdaptationsJson))
                    if (c.nameHistory.isNotEmpty()) {
                        put("name_history", JSONArray().apply {
                            c.nameHistory.forEach { h ->
                                put(JSONObject().apply {
                                    put("name", h.name)
                                    put("effective_from", h.effectiveFrom)
                                    putIfNotNull("effective_until", h.effectiveUntil)
                                })
                            }
                        })
                    }
                    put("created_at", c.createdAt)
                    put("status", c.status)
                    if (c.origin != "user") put("origin", c.origin)
                })
            }
        })

        root.put("entities", JSONArray().apply {
            data.entities.forEach { e ->
                put(JSONObject().apply {
                    put("entity_id", e.entityId)
                    put("kind", e.kind)
                    put("name", e.name)
                    put("aliases", jsonArrayOrEmpty(e.aliasesJson))
                    put("summary", e.summary)
                    putIfNotNull("status", e.status)
                    put("importance", e.importance)
                    putIfNotNull("last_touched", e.lastTouched)
                    if (e.origin != "user") put("origin", e.origin)
                })
            }
        })

        root.put("memories", JSONArray().apply {
            data.memories.forEach { m ->
                put(JSONObject().apply {
                    put("memory_id", m.memoryId)
                    put("scope", m.scope)
                    if (m.companionIds.isNotEmpty()) put("companion_ids", JSONArray(m.companionIds))
                    if (m.worldIds.isNotEmpty()) put("world_ids", JSONArray(m.worldIds))
                    if (m.roleplayCharacterIds.isNotEmpty()) put("roleplay_character_ids", JSONArray(m.roleplayCharacterIds))
                    if (m.campaignIds.isNotEmpty()) put("campaign_ids", JSONArray(m.campaignIds))
                    if (m.projectIds.isNotEmpty()) put("project_ids", JSONArray(m.projectIds))
                    put("kind", m.kind)
                    put("title", m.title)
                    put("content", m.content)
                    putIfNotNull("embedding_text", m.embeddingText)
                    put("tags", jsonArrayOrEmpty(m.tagsJson))
                    if (m.entityRefs.isNotEmpty()) put("entity_refs", JSONArray(m.entityRefs))
                    put("importance", m.importance)
                    m.protectionJson?.let { putJsonText("protection", it) }
                    put("mode_hints", jsonArrayOrEmpty(m.modeHintsJson))
                    if (m.provenanceSource != null || m.provenanceConfidence != null ||
                        m.provenanceNotedOn != null || m.provenanceContext != null
                    ) {
                        put("provenance", JSONObject().apply {
                            putIfNotNull("source", m.provenanceSource)
                            putIfNotNull("confidence", m.provenanceConfidence)
                            putIfNotNull("noted_on", m.provenanceNotedOn)
                            putIfNotNull("context", m.provenanceContext)
                        })
                    }
                    if (m.changeLog.isNotEmpty()) {
                        put("change_log", JSONArray().apply {
                            m.changeLog.forEach { l ->
                                put(JSONObject().apply {
                                    put("at", l.at)
                                    put("actor", l.actor)
                                    put("action", l.action)
                                    putIfNotNull("note", l.note)
                                    // prior_state deliberately omitted: device-local undo state
                                })
                            }
                        })
                    }
                    put("created_at", m.createdAt)
                    putIfNotNull("updated_at", m.updatedAt)
                    put("status", m.status)
                    putIfNotNull("supersedes", m.supersedes)
                    if (m.origin != "user") put("origin", m.origin)
                })
            }
        })

        root.put("modes", JSONArray().apply {
            data.modes.forEach { m ->
                put(JSONObject().apply {
                    put("mode_id", m.modeId)
                    put("name", m.name)
                    putIfNotNull("purpose", m.purpose)
                    put("signals", jsonArrayOrEmpty(m.signalsJson))
                    put("respond", jsonArrayOrEmpty(m.respondJson))
                    put("avoid", jsonArrayOrEmpty(m.avoidJson))
                    putIfNotNull("transition_note", m.transitionNote)
                    put("overrides", jsonArrayOrEmpty(m.overridesJson))
                    put("scope", m.scope)
                    put("companion_ids", jsonArrayOrEmpty(m.companionIdsJson))
                    if (m.origin != "user") put("origin", m.origin)
                })
            }
        })

        root.put("directives", JSONArray().apply {
            data.directives.forEach { d ->
                put(JSONObject().apply {
                    put("directive_id", d.directiveId)
                    put("text", d.text)
                    putIfNotNull("rationale", d.rationale)
                    put("applies_to", jsonArrayOrEmpty(d.appliesToJson))
                    put("priority", d.priority)
                    if (d.origin != "user") put("origin", d.origin)
                })
            }
        })

        root.put("worlds", JSONArray().apply {
            data.worlds.forEach { w ->
                put(JSONObject().apply {
                    put("world_id", w.worldId)
                    put("name", w.name)
                    put("premise", w.premise)
                    putIfNotNull("rules", w.rules)
                    putIfNotNull("cosmology", w.cosmology)
                    putIfNotNull("premise_vibe", w.premiseVibe)
                    putIfNotNull("magic_rules", w.magicRules)
                    put("companion_ids", jsonArrayOrEmpty(w.companionIdsJson))
                    put("status", w.status)
                    putIfNotNull("created_at", w.createdAt)
                })
            }
        })

        root.put("user_personas", JSONArray().apply {
            data.userPersonas.forEach { p ->
                put(JSONObject().apply {
                    put("persona_id", p.personaId)
                    put("name", p.name)
                    put("presentation", p.presentation)
                    put("status", p.status)
                    putIfNotNull("created_at", p.createdAt)
                })
            }
        })

        root.put("roleplay_characters", JSONArray().apply {
            data.roleplayCharacters.forEach { r ->
                put(JSONObject().apply {
                    put("roleplay_character_id", r.roleplayCharacterId)
                    put("name", r.name)
                    put("played_by", r.playedBy)
                    put("description", r.description)
                    putIfNotNull("arc", r.arc)
                    put("worlds_played", jsonArrayOrEmpty(r.worldsPlayedJson))
                    put("status", r.status)
                    putIfNotNull("created_at", r.createdAt)
                    putIfNotNull("species", r.species)
                    putIfNotNull("class", r.charClass)
                    putIfNotNull("core_personality", r.corePersonality)
                    putIfNotNull("physical_description", r.physicalDescription)
                    putIfNotNull("goals_drives", r.goalsDrives)
                })
            }
        })

        if (data.campaigns.isNotEmpty()) {
            root.put("campaigns", JSONArray().apply {
                data.campaigns.forEach { c ->
                    put(JSONObject().apply {
                        put("campaign_id", c.campaignId)
                        put("name", c.name)
                        putIfNotNull("world_id", c.worldId)
                        putIfNotNull("roleplay_character_id", c.roleplayCharacterId)
                        putIfNotNull("companion_id", c.companionId)
                        put("status", c.status)
                        putIfNotNull("story_so_far", c.storySoFar)
                        putIfNotNull("created_at", c.createdAt)
                        putIfNotNull("quest_anchor", c.questAnchor)
                        putIfNotNull("active_scene", c.activeScene)
                        if (c.partyMemberIds.isNotEmpty()) put("party_member_ids", JSONArray(c.partyMemberIds))
                    })
                }
            })
        }

        // Roleplay cards + tags (Stage 3.6a).
        if (data.partyMembers.isNotEmpty()) {
            root.put("party_members", JSONArray().apply {
                data.partyMembers.forEach { p ->
                    put(JSONObject().apply {
                        put("party_member_id", p.partyMemberId)
                        put("name", p.name)
                        putIfNotNull("species", p.species)
                        putIfNotNull("class", p.charClass)
                        putIfNotNull("core_personality", p.corePersonality)
                        putIfNotNull("physical_description", p.physicalDescription)
                        putIfNotNull("goals_drives", p.goalsDrives)
                        putIfNotNull("speech_style", p.speechStyle)
                        put("status", p.status)
                        if (p.archived) put("archived", true)
                        put("created_at", p.createdAt)
                        putIfNotNull("updated_at", p.updatedAt)
                    })
                }
            })
        }

        if (data.cardEntries.isNotEmpty()) {
            root.put("card_entries", JSONArray().apply {
                data.cardEntries.forEach { e ->
                    put(JSONObject().apply {
                        put("entry_id", e.entryId)
                        put("card_type", e.cardType)
                        put("card_id", e.cardId)
                        put("section", e.section)
                        put("name", e.name)
                        putIfNotNull("description", e.description)
                        putIfNotNull("entry_kind", e.entryKind)
                        if (e.quantity != null) put("quantity", e.quantity)
                        putIfNotNull("parent_entry_id", e.parentEntryId)
                        putIfNotNull("world_entry_id", e.worldEntryId)
                        putIfNotNull("party_member_id", e.partyMemberId)
                        putIfNotNull("holder", e.holder)
                        putIfNotNull("significance", e.significance)
                        putIfNotNull("cast_identity", e.castIdentity)
                        putIfNotNull("cast_disposition", e.castDisposition)
                        putIfNotNull("cast_status", e.castStatus)
                        putIfNotNull("location_condition", e.locationCondition)
                        putIfNotNull("location_changes", e.locationChanges)
                        put("created_at", e.createdAt)
                        putIfNotNull("updated_at", e.updatedAt)
                    })
                }
            })
        }

        if (data.rpTags.isNotEmpty()) {
            root.put("rp_tags", JSONArray().apply {
                data.rpTags.forEach { t ->
                    put(JSONObject().apply {
                        put("tag_id", t.tagId)
                        put("name", t.name)
                        put("auto_trigger", t.autoTrigger)
                        putIfNotNull("created_at", t.createdAt)
                        if (t.targets.isNotEmpty()) {
                            put("targets", JSONArray().apply {
                                t.targets.forEach { (type, id) ->
                                    put(JSONObject().apply { put("type", type); put("id", id) })
                                }
                            })
                        }
                    })
                }
            })
        }

        // Model rules (Stage 4, rules §11).
        if (data.modelRuleProfiles.isNotEmpty()) {
            root.put("model_rule_profiles", JSONArray().apply {
                data.modelRuleProfiles.forEach { p ->
                    put(JSONObject().apply {
                        put("profile_id", p.profileId)
                        put("nickname", p.nickname)
                        put("model_strings", jsonArrayOrEmpty(p.modelStringsJson))
                        put("created_at", p.createdAt)
                        putIfNotNull("updated_at", p.updatedAt)
                    })
                }
            })
        }

        if (data.modelRules.isNotEmpty()) {
            root.put("model_rules", JSONArray().apply {
                data.modelRules.forEach { r ->
                    put(JSONObject().apply {
                        put("rule_id", r.ruleId)
                        putIfNotNull("profile_id", r.profileId)
                        put("text", r.text)
                        put("status", r.status)
                        putIfNotNull("source_model_string", r.sourceModelString)
                        put("created_at", r.createdAt)
                        putIfNotNull("updated_at", r.updatedAt)
                    })
                }
            })
        }

        if (data.projects.isNotEmpty()) {
            root.put("projects", JSONArray().apply {
                data.projects.forEach { p ->
                    put(JSONObject().apply {
                        put("project_id", p.projectId)
                        put("name", p.name)
                        put("status", p.status)
                        putIfNotNull("created_at", p.createdAt)
                        putIfNotNull("updated_at", p.updatedAt)
                    })
                }
            })
        }

        data.archivistSettings?.let { a ->
            root.put("archivist_settings", JSONObject().apply {
                put("trigger", a.runTrigger)
                put("harvest_generosity", a.harvestGenerosity)
                put("autonomy", JSONObject(a.autonomyJson))
                putIfNotNull("notes", a.notes)
            })
        }

        root.put("proposals", JSONArray().apply {
            data.proposals.forEach { p ->
                put(JSONObject().apply {
                    put("proposal_id", p.proposalId)
                    put("target_type", p.targetType)
                    putIfNotNull("target_id", p.targetId)
                    put("summary", p.summary)
                    p.proposedChangeJson?.let { putJsonText("proposed_change", it) }
                    putIfNotNull("rationale", p.rationale)
                    put("status", p.status)
                    put("created_at", p.createdAt)
                    putIfNotNull("resolved_at", p.resolvedAt)
                })
            }
        })

        data.retrievalPolicyJson?.let { root.put("retrieval_policy", JSONObject(it)) }

        if (data.transcripts.isNotEmpty()) {
            root.put("transcripts", JSONArray().apply {
                data.transcripts.forEach { t ->
                    put(JSONObject().apply {
                        put("transcript_id", t.transcriptId)
                        putIfNotNull("chat_id", t.chatId)
                        putIfNotNull("companion_id", t.companionId)
                        putIfNotNull("world_id", t.worldId)
                        putIfNotNull("roleplay_character_id", t.roleplayCharacterId)
                        putIfNotNull("user_persona_id", t.userPersonaId)
                        put("source", t.source)
                        putIfNotNull("started_at", t.startedAt)
                        putIfNotNull("ended_at", t.endedAt)
                        put("content", t.content)
                        putIfNotNull("model_tag", t.modelTag)
                        t.quickSettingsJson?.let { putJsonText("quick_settings", it) }
                        put("review_status", t.reviewStatus)
                        putIfNotNull("processed_at", t.processedAt)
                    })
                }
            })
        }

        appChats?.let { root.put("app_chats", it) }

        root.put("export_meta", JSONObject().apply {
            put("app", "Phosphor Shines")
            put("format", EXPORT_FORMAT)
            putIfNotNull("exported_at", exportedAtIso)
        })

        return root.toString(2)
    }
}
