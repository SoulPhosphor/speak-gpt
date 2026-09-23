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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariant
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariantRules
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import java.util.UUID

/**
 * The pure §6.3/§6.4 planning rules: which lorebook links survive, what the
 * report names, exactly which preference values are written, and the
 * roll-forward/roll-back recovery decision.
 */
class CompanionRestorePlannerTest {

    private fun profile(
        id: String = "p-1",
        label: String = "Aria",
        core: String = "",
        coreName: String? = null,
        additional: List<String> = emptyList(),
        additionalNames: Map<String, String> = emptyMap(),
        lastUsed: List<String> = emptyList(),
        autoLoad: Boolean = false
    ) = CompanionProfileEntry(
        id = id, label = label, prompt = "prompt-$id",
        promptVariants = PortablePromptVariantRules.legacySingleVariant(id, "prompt-$id"),
        activationPromptId = "ap-$id",
        coreLoreBookId = core, coreLoreBookName = coreName,
        additionalLoreBookIds = additional, additionalLoreBookNames = additionalNames,
        autoLoadLastLoreBooks = autoLoad, lastUsedLoreBookIds = lastUsed,
        avatarRef = "hash-$id"
    )

    private fun manifest(
        profiles: List<CompanionProfileEntry>,
        systemPrompts: List<SystemPromptEntry> = emptyList(),
        selected: String = ""
    ) = CompanionBackupManifest(
        formatVersion = 1, appVersion = "1.0", exportedAt = "2026-08-05T00:00:00Z",
        companionProfiles = profiles,
        activationPrompts = listOf(ActivationPromptEntry("ap-p-1", "Wake", "Hi")),
        systemPrompts = systemPrompts, selectedSystemPromptId = selected,
        roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
        images = emptyList()
    )

    /* ---------------- lorebook link resolution (§6.4) ---------------- */

    @Test
    fun resolvingCoreLinkIsKept() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(core = "lb-1", coreName = "Book"))),
            existingLorebookIds = setOf("lb-1")
        )
        assertEquals("lb-1", plan.settingsNew.personas["p-1_core_lorebook_id"])
        assertTrue(plan.removedLinks.isEmpty())
    }

    @Test
    fun missingCoreLinkIsRemovedAndReportedByName() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(core = "lb-gone", coreName = "Lost Book"))),
            existingLorebookIds = emptySet()
        )
        assertEquals("", plan.settingsNew.personas["p-1_core_lorebook_id"])
        assertEquals(listOf(RemovedLorebookLink("Aria", "Lost Book")), plan.removedLinks)
    }

    @Test
    fun removedLinksNeverShowAnInternalId() {
        // The id must not leak into the report even for a degenerate entry;
        // the codec rejects nameless links, so this is a belt-and-braces
        // check on the planner itself.
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(core = "lb-gone", coreName = null))),
            existingLorebookIds = emptySet()
        )
        assertEquals(1, plan.removedLinks.size)
        assertFalse(plan.removedLinks[0].lorebookName.contains("lb-gone"))
    }

    @Test
    fun blankCoreLinkIsNotReported() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(core = ""))), existingLorebookIds = emptySet()
        )
        assertTrue(plan.removedLinks.isEmpty())
    }

    @Test
    fun additionalLinksSplitIntoKeptAndRemoved() {
        val plan = CompanionRestorePlanner.plan(
            manifest(
                listOf(
                    profile(
                        additional = listOf("lb-1", "lb-2", "lb-3"),
                        additionalNames = mapOf("lb-2" to "Second Book")
                    )
                )
            ),
            existingLorebookIds = setOf("lb-1", "lb-3")
        )
        assertEquals("lb-1,lb-3", plan.settingsNew.personas["p-1_additional_lorebook_ids"])
        assertEquals(listOf(RemovedLorebookLink("Aria", "Second Book")), plan.removedLinks)
    }

    @Test
    fun lastUsedBookkeepingIsDroppedSilently() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(lastUsed = listOf("lb-1", "lb-gone")))),
            existingLorebookIds = setOf("lb-1")
        )
        assertEquals("lb-1", plan.settingsNew.personas["p-1_last_used_lorebook_ids"])
        // Invisible bookkeeping: never in the user-facing report.
        assertTrue(plan.removedLinks.isEmpty())
    }

    /* ---------------- written preference values (§6.3 step 3) ---------------- */

    @Test
    fun personaKeysCarryEveryStoredField() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile(autoLoad = true))), existingLorebookIds = emptySet()
        )
        val personas = plan.settingsNew.personas
        assertEquals("Aria", personas["p-1_label"])
        assertEquals("prompt-p-1", personas["p-1_prompt"])
        assertEquals("ap-p-1", personas["p-1_activation_prompt_id"])
        assertEquals("true", personas["p-1_autoload_last_lorebooks"])
        assertEquals("hash-p-1", personas["p-1_avatar_ref"])
        assertEquals("", personas["p-1_chat_name_font_id"])
        assertEquals("0", personas["p-1_chat_name_size_sp"])
        assertEquals("", personas["p-1_chat_name_font_style"])
        assertEquals(
            PortablePromptVariantRules.legacySingleVariant("p-1", "prompt-p-1").map { it.toCompanionVariant() },
            CompanionPromptVariant.fromJson(personas["p-1_prompt_variants"] as String)
        )
        assertEquals(12, personas.keys.count { it.startsWith("p-1_") })
    }

    @Test
    fun bothPromptRepresentationsAreWrittenWithOrderAndIdsExact() {
        val variants = listOf(
            PortablePromptVariant("v-z", "Zeta", "Last-created but first", false),
            PortablePromptVariant("v-a", "Alpha", "Default\ntext ✦", true),
            PortablePromptVariant("v-m", "", "", false)
        )
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile().copy(prompt = "Default\ntext ✦", promptVariants = variants))),
            existingLorebookIds = emptySet()
        )
        val personas = plan.settingsNew.personas
        assertEquals("Default\ntext ✦", personas["p-1_prompt"])
        val stored = JSONArray(personas["p-1_prompt_variants"] as String)
        assertEquals(3, stored.length())
        assertEquals(listOf("v-z", "v-a", "v-m"), (0 until 3).map { stored.getJSONObject(it).getString("id") })
        assertEquals(
            variants.map { it.toCompanionVariant() },
            CompanionPromptVariant.fromJson(personas["p-1_prompt_variants"] as String)
        )
    }

    @Test
    fun versionOneParsedProfileRestoresAsOneVariant() {
        val v1 = JSONObject(CompanionBackupCodec.toJson(manifest(listOf(profile()))))
        v1.put("format_version", 1)
        v1.getJSONArray("companion_profiles").getJSONObject(0).remove("prompt_variants")
        val parsed = (CompanionBackupCodec.parse(v1.toString()) as CompanionBackupCodec.ParseResult.Ok).manifest

        val personas = CompanionRestorePlanner.plan(parsed, emptySet()).settingsNew.personas
        val stored = CompanionPromptVariant.fromJson(personas["p-1_prompt_variants"] as String)
        assertEquals(1, stored.size)
        assertEquals(UUID.nameUUIDFromBytes("p-1_prompt_1".toByteArray()).toString(), stored[0].id)
        assertEquals("Prompt 1", stored[0].name)
        assertEquals("prompt-p-1", stored[0].text)
        assertTrue(stored[0].isDefault)
        assertEquals("prompt-p-1", personas["p-1_prompt"])
    }

    @Test
    fun journalCarriesTheVariantKeyUnchanged() {
        val plan = CompanionRestorePlanner.plan(manifest(listOf(profile())), emptySet())
        val journaled = CompanionSettingsPayloadCodec.fromJson(
            CompanionSettingsPayloadCodec.toJson(plan.settingsNew)
        )
        assertEquals(plan.settingsNew.personas, journaled.personas)
        assertEquals(
            plan.settingsNew.personas["p-1_prompt_variants"],
            journaled.personas["p-1_prompt_variants"]
        )
    }

    @Test
    fun activationPromptKeysMatchStoreLayout() {
        val plan = CompanionRestorePlanner.plan(
            manifest(listOf(profile())), existingLorebookIds = emptySet()
        )
        assertEquals("Wake", plan.settingsNew.activationPrompts["ap-p-1_label"])
        assertEquals("Hi", plan.settingsNew.activationPrompts["ap-p-1_prompt"])
    }

    @Test
    fun systemPromptsKeepOrderAndSelection() {
        val plan = CompanionRestorePlanner.plan(
            manifest(
                listOf(profile()),
                systemPrompts = listOf(
                    SystemPromptEntry("sp-1", "One", "Body one"),
                    SystemPromptEntry("sp-2", "Two", "Body two")
                ),
                selected = "sp-2"
            ),
            existingLorebookIds = emptySet()
        )
        val list = JSONArray(plan.settingsNew.systemPrompts["list"] as String)
        assertEquals(2, list.length())
        assertEquals("sp-1", list.getJSONObject(0).getString("id"))
        assertEquals("sp-2", plan.settingsNew.systemPrompts["selected_id"])
        // Re-mirror: the selected entry's body becomes the global message.
        assertEquals("Body two", plan.settingsNew.systemMessage)
    }

    @Test
    fun effectiveMessageFallsBackToTopOfList() {
        val m = manifest(
            emptyList(),
            systemPrompts = listOf(SystemPromptEntry("sp-1", "One", "Body one")),
            selected = "sp-missing"
        )
        assertEquals("Body one", CompanionRestorePlanner.effectiveSystemMessage(m))
    }

    @Test
    fun effectiveMessageEmptyWhenLibraryEmpty() {
        assertEquals("", CompanionRestorePlanner.effectiveSystemMessage(manifest(emptyList())))
    }

    /* ---------------- recovery decision (crash protection) ---------------- */

    @Test
    fun rollsForwardOnlyWhenCommittedTokenMatches() {
        assertTrue(CompanionRestorePlanner.shouldRollForward(true, "tok-1", "tok-1"))
        assertFalse(CompanionRestorePlanner.shouldRollForward(true, "tok-other", "tok-1"))
        assertFalse(CompanionRestorePlanner.shouldRollForward(true, null, "tok-1"))
    }

    @Test
    fun neverRollsForwardWithoutDatabasePivot() {
        assertFalse(CompanionRestorePlanner.shouldRollForward(false, "tok-1", "tok-1"))
        assertFalse(CompanionRestorePlanner.shouldRollForward(false, null, "tok-1"))
    }
}
