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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariant
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariantRules
import java.util.UUID

/**
 * Manifest round-trip, §2 field coverage, and the §6.1 structural rejection
 * causes (companion-roleplay-backup-plan.md Build step 1/2).
 */
class CompanionBackupCodecTest {

    /** Three ordered variants; the default is deliberately NOT first. */
    private val ariaVariants = listOf(
        PortablePromptVariant("variant-alt", "Alternate", "Line one\nLine two", false),
        PortablePromptVariant("variant-main", "Main ✦ 主要", "You are Aria. 🌙", true),
        PortablePromptVariant("variant-blank", "", "", false)
    )

    private fun fullManifest(): CompanionBackupManifest = CompanionBackupManifest(
        formatVersion = CompanionBackupFormat.FORMAT_VERSION,
        appVersion = "9.9.9",
        exportedAt = "2026-08-05T12:00:00Z",
        companionProfiles = listOf(
            CompanionProfileEntry(
                id = "p-1",
                label = "Aria",
                prompt = "You are Aria. 🌙",
                promptVariants = ariaVariants,
                activationPromptId = "ap-1",
                coreLoreBookId = "lb-core",
                coreLoreBookName = "Core Book",
                additionalLoreBookIds = listOf("lb-a", "lb-b"),
                additionalLoreBookNames = mapOf("lb-a" to "Book A", "lb-b" to "Book B"),
                autoLoadLastLoreBooks = true,
                lastUsedLoreBookIds = listOf("lb-a"),
                avatarRef = "a".repeat(64),
                chatNameFontId = "solitreo",
                chatNameSizeSp = 22
            ),
            CompanionProfileEntry(
                id = "p-2",
                label = "Nox",
                prompt = "",
                promptVariants = listOf(PortablePromptVariant("nox-only", "Prompt 1", "", true)),
                activationPromptId = "",
                coreLoreBookId = "",
                coreLoreBookName = null,
                additionalLoreBookIds = emptyList(),
                additionalLoreBookNames = emptyMap(),
                autoLoadLastLoreBooks = false,
                lastUsedLoreBookIds = emptyList(),
                avatarRef = ""
            )
        ),
        activationPrompts = listOf(ActivationPromptEntry("ap-1", "Wake", "Hello")),
        systemPrompts = listOf(
            SystemPromptEntry("sp-1", "Default", "Be helpful."),
            SystemPromptEntry("sp-2", "Roleplay", "Stay in character.")
        ),
        selectedSystemPromptId = "sp-2",
        roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { table ->
            when (table) {
                "companions" -> listOf(
                    mapOf(
                        "companion_id" to "c-1", "current_name" to "Aria",
                        "essence" to "warm", "relationship_notes" to null,
                        "memory_participation" to "full", "hard_limits_json" to "[]",
                        "app_character_id" to "p-1",
                        "base_personality_mirror_text" to "You are Aria.",
                        "base_personality_mirror_synced_at" to "2026-08-01T00:00:00Z",
                        "model_adaptations_json" to "[]",
                        "created_at" to "2026-07-01T00:00:00Z",
                        "status" to "active", "origin" to "user"
                    )
                )
                "companion_name_history" -> listOf(
                    mapOf(
                        "id" to 1L, "companion_id" to "c-1", "name" to "Aria",
                        "effective_from" to "2026-07-01T00:00:00Z", "effective_until" to null
                    )
                )
                "card_entries" -> listOf(
                    mapOf(
                        "entry_id" to "ce-1", "card_type" to "world", "card_id" to "w-1",
                        "section" to "locations", "name" to "The Vale",
                        "quantity" to 3L, "created_at" to "2026-07-02T00:00:00Z"
                    )
                )
                "rp_tag_links" -> listOf(
                    mapOf("tag_id" to "t-1", "target_type" to "card_entry", "target_id" to "ce-1")
                )
                "rp_tags" -> listOf(
                    mapOf("tag_id" to "t-1", "name" to "vale", "auto_trigger" to 1L, "created_at" to null)
                )
                else -> emptyList()
            }
        },
        images = listOf(
            CompanionBackupImage("a".repeat(64), CompanionBackupFormat.imageEntryName("a".repeat(64)))
        )
    )

    private fun parseOk(json: String): CompanionBackupManifest {
        val result = CompanionBackupCodec.parse(json)
        assertTrue("expected Ok, got $result", result is CompanionBackupCodec.ParseResult.Ok)
        return (result as CompanionBackupCodec.ParseResult.Ok).manifest
    }

    @Test
    fun roundTripPreservesEveryField() {
        val original = fullManifest()
        val restored = parseOk(CompanionBackupCodec.toJson(original))

        assertEquals(original.formatVersion, restored.formatVersion)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.companionProfiles, restored.companionProfiles)
        assertEquals(original.activationPrompts, restored.activationPrompts)
        assertEquals(original.systemPrompts, restored.systemPrompts)
        assertEquals(original.selectedSystemPromptId, restored.selectedSystemPromptId)
        assertEquals(original.images, restored.images)
        for (table in CompanionBackupFormat.ROLEPLAY_TABLES) {
            assertEquals("table $table", original.roleplayTables[table], restored.roleplayTables[table])
        }
    }

    @Test
    fun roundTripPreservesValueTypes() {
        val restored = parseOk(CompanionBackupCodec.toJson(fullManifest()))
        val historyRow = restored.roleplayTables["companion_name_history"]!!.first()
        assertEquals(1L, historyRow["id"])
        assertNull(historyRow["effective_until"])
        val cardRow = restored.roleplayTables["card_entries"]!!.first()
        assertEquals(3L, cardRow["quantity"])
    }

    @Test
    fun formatMarkerIsWritten() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        assertEquals(CompanionBackupFormat.FORMAT_MARKER, root.getString("format"))
        assertEquals(CompanionBackupFormat.FORMAT_VERSION, root.getInt("format_version"))
    }

    @Test
    fun wrongMarkerIsWrongFile() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.put("format", "some-other-export")
        assertEquals(
            CompanionBackupCodec.ParseResult.WrongFile,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun missingMarkerIsWrongFile() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.remove("format")
        assertEquals(
            CompanionBackupCodec.ParseResult.WrongFile,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun newerVersionIsNewerFormat() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.put("format_version", CompanionBackupFormat.FORMAT_VERSION + 1)
        assertEquals(
            CompanionBackupCodec.ParseResult.NewerFormat,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun impossibleVersionIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.put("format_version", 0)
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun nonIntegerVersionIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.put("format_version", "one")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun unparseableJsonIsDamaged() {
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse("{ not json")
        )
    }

    @Test
    fun missingSectionIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.remove("activation_prompts")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun blankProfileIdIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONArray("companion_profiles").getJSONObject(0).put("id", "")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun coreLorebookLinkWithoutANameIsDamaged() {
        // Every carried link carries its name — the restore report names
        // removed connections and never shows an internal id.
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONArray("companion_profiles").getJSONObject(0)
            .put("core_lorebook_name", JSONObject.NULL)
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun additionalLorebookLinkWithoutANameIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONArray("companion_profiles").getJSONObject(0)
            .getJSONObject("additional_lorebook_names").remove("lb-b")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun nestedValueInRoleplayRowIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONObject("roleplay").getJSONArray("companions").getJSONObject(0)
            .put("essence", JSONObject().put("oops", true))
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun malformedImageHashIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONArray("images").getJSONObject(0).put("hash", "not-a-hash")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun imagePathOutsideImagesDirIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.getJSONArray("images").getJSONObject(0).put("file", "../escape.jpg")
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun emptyBackupRoundTrips() {
        val empty = CompanionBackupManifest(
            formatVersion = CompanionBackupFormat.FORMAT_VERSION,
            appVersion = "1.0",
            exportedAt = "2026-08-05T00:00:00Z",
            companionProfiles = emptyList(),
            activationPrompts = emptyList(),
            systemPrompts = emptyList(),
            selectedSystemPromptId = "",
            roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
            images = emptyList()
        )
        val restored = parseOk(CompanionBackupCodec.toJson(empty))
        assertEquals(empty, restored)
        assertTrue(!restored.hasRoleplayRecords())
    }

    /* ------------------------- version 2: prompt variants ------------------------- */

    private fun firstProfile(root: JSONObject): JSONObject =
        root.getJSONArray("companion_profiles").getJSONObject(0)

    private fun assertDamaged(root: JSONObject) {
        assertEquals(
            CompanionBackupCodec.ParseResult.Damaged,
            CompanionBackupCodec.parse(root.toString())
        )
    }

    @Test
    fun writerIsVersionTwo() {
        assertEquals(2, CompanionBackupFormat.FORMAT_VERSION)
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        assertEquals(2, root.getInt("format_version"))
        val variants = firstProfile(root).getJSONArray("prompt_variants")
        assertEquals(3, variants.length())
        val main = variants.getJSONObject(1)
        assertEquals(setOf("id", "name", "text", "isDefault"), main.keys().asSequence().toSet())
        assertEquals("variant-main", main.getString("id"))
        assertEquals(true, main.getBoolean("isDefault"))
        // The legacy mirror stays and equals the default variant's text.
        assertEquals("You are Aria. 🌙", firstProfile(root).getString("prompt"))
    }

    @Test
    fun versionTwoRoundTripPreservesEveryVariantFieldInOrder() {
        val restored = parseOk(CompanionBackupCodec.toJson(fullManifest()))
        val aria = restored.companionProfiles.first { it.id == "p-1" }
        assertEquals(ariaVariants, aria.promptVariants)
        assertEquals(listOf("variant-alt", "variant-main", "variant-blank"), aria.promptVariants.map { it.id })
        assertEquals("Line one\nLine two", aria.promptVariants[0].text)
        assertEquals("Main ✦ 主要", aria.promptVariants[1].name)
        assertEquals("", aria.promptVariants[2].name)
        assertEquals("", aria.promptVariants[2].text)
        assertEquals(listOf(false, true, false), aria.promptVariants.map { it.isDefault })
        assertEquals("You are Aria. 🌙", aria.prompt)
        // Re-encoding the parsed manifest is byte-identical: nothing is renamed,
        // re-ordered, or re-minted on the way through.
        assertEquals(CompanionBackupCodec.toJson(fullManifest()), CompanionBackupCodec.toJson(restored))
    }

    @Test
    fun versionOneSinglePromptBecomesOneDeterministicDefaultVariant() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        root.put("format_version", 1)
        val profiles = root.getJSONArray("companion_profiles")
        for (i in 0 until profiles.length()) profiles.getJSONObject(i).remove("prompt_variants")
        val v1Text = root.toString()

        val restored = parseOk(v1Text)
        assertEquals(1, restored.formatVersion)
        val aria = restored.companionProfiles.first { it.id == "p-1" }
        val expectedId = UUID.nameUUIDFromBytes("p-1_prompt_1".toByteArray()).toString()
        assertEquals(
            listOf(PortablePromptVariant(expectedId, "Prompt 1", "You are Aria. 🌙", true)),
            aria.promptVariants
        )
        assertEquals("You are Aria. 🌙", aria.prompt)
        val nox = restored.companionProfiles.first { it.id == "p-2" }
        assertEquals(PortablePromptVariantRules.legacySingleVariant("p-2", ""), nox.promptVariants)
        // Deterministic: reading the same file again yields the same identity.
        assertEquals(aria.promptVariants, parseOk(v1Text).companionProfiles.first { it.id == "p-1" }.promptVariants)
    }

    @Test
    fun versionTwoWithoutVariantArrayIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).remove("prompt_variants")
        assertDamaged(root)
    }

    @Test
    fun versionTwoWithEmptyVariantArrayIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).put("prompt_variants", org.json.JSONArray())
        assertDamaged(root)
    }

    @Test
    fun blankVariantIdIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(0).put("id", "  ")
        assertDamaged(root)
    }

    @Test
    fun missingVariantIdIsDamagedAndNeverReMinted() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(0).remove("id")
        assertDamaged(root)
    }

    @Test
    fun duplicateVariantIdIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(2).put("id", "variant-alt")
        assertDamaged(root)
    }

    @Test
    fun noDefaultVariantIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(1).put("isDefault", false)
        assertDamaged(root)
    }

    @Test
    fun multipleDefaultVariantsAreDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(0).put("isDefault", true)
        assertDamaged(root)
    }

    @Test
    fun promptMirrorDifferentFromDefaultIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).put("prompt", "Line one\nLine two")
        assertDamaged(root)
    }

    @Test
    fun missingPromptMirrorInVersionTwoIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).remove("prompt")
        assertDamaged(root)
    }

    @Test
    fun wronglyTypedVariantFieldsAreDamaged() {
        for ((field, value) in listOf<Pair<String, Any>>(
            "isDefault" to "true", "id" to 7, "name" to JSONObject.NULL, "text" to 1.5
        )) {
            val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
            firstProfile(root).getJSONArray("prompt_variants").getJSONObject(1).put(field, value)
            assertDamaged(root)
        }
    }

    @Test
    fun unknownVariantFieldIsDamaged() {
        val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
        firstProfile(root).getJSONArray("prompt_variants").getJSONObject(1).put("color", "red")
        assertDamaged(root)
    }

    @Test
    fun versionsAboveTwoRemainNewerFormat() {
        for (version in listOf(3, 99)) {
            val root = JSONObject(CompanionBackupCodec.toJson(fullManifest()))
            root.put("format_version", version)
            assertEquals(
                CompanionBackupCodec.ParseResult.NewerFormat,
                CompanionBackupCodec.parse(root.toString())
            )
        }
    }
}
