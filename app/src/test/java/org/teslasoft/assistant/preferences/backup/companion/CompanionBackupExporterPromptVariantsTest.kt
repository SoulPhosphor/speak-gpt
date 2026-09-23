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
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariant
import org.teslasoft.assistant.preferences.backup.portable.PortablePromptVariantRules
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * The old-client exporter emits the new client's companion archive version-2
 * prompt contract (beta/new-client commit 580da22): every prompt variant, in
 * order, with its stable id, name, text and default flag, plus the legacy
 * `prompt` mirror of the default. Synthetic companions only; live
 * preferences are never written.
 */
class CompanionBackupExporterPromptVariantsTest {

    private object NoSync : PersonaPreferences.CompanionSync {
        override fun onPersonaSaved(persona: PersonaObject) {}
        override fun onPersonaDeleted(personaId: String) {}
    }

    /** Same synthetic fixture as the new client's receiving-side tests. */
    private val ariaVariants = listOf(
        CompanionPromptVariant("aria-v-alt", "Alternate", "Line one\nLine two", false),
        CompanionPromptVariant("aria-v-main", "Main ✦ 主要", "You are Aria. 🌙", true),
        CompanionPromptVariant("aria-v-blank", "", "", false)
    )

    private val noxVariants = listOf(
        CompanionPromptVariant("nox-v-1", "Calm", "You are Nox.", true),
        CompanionPromptVariant("nox-v-2", "Stormy", "You are Nox, but louder.", false)
    )

    /** The exact `prompt_variants` the receiving contract expects for Aria. */
    private val ariaContract = JSONArray(
        """
        [
          {"id": "aria-v-alt", "name": "Alternate", "text": "Line one\nLine two", "isDefault": false},
          {"id": "aria-v-main", "name": "Main ✦ 主要", "text": "You are Aria. 🌙", "isDefault": true},
          {"id": "aria-v-blank", "name": "", "text": "", "isDefault": false}
        ]
        """
    )

    private fun seed(prefs: FakeSharedPreferences, id: String, label: String, variants: List<CompanionPromptVariant>) {
        PersonaPreferences.createForTest(prefs, NoSync).setPersona(
            PersonaObject(
                label = label,
                prompt = CompanionPromptVariant.defaultPrompt(variants),
                promptVariants = ArrayList(variants.map { it.copy() }),
                id = id
            )
        )
    }

    private fun manifestJson(prefs: FakeSharedPreferences): String {
        val personas = PersonaPreferences.createForTest(prefs, NoSync).getPersonasList().sortedBy { it.id }
        return CompanionBackupCodec.toJson(
            CompanionBackupManifest(
                formatVersion = CompanionBackupFormat.FORMAT_VERSION,
                appVersion = "test",
                exportedAt = "2026-09-22T00:00:00Z",
                companionProfiles = CompanionBackupExporter.profileEntries(personas, emptyMap()),
                activationPrompts = emptyList(),
                systemPrompts = emptyList(),
                selectedSystemPromptId = "",
                roleplayTables = CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
                images = emptyList()
            )
        )
    }

    private fun profile(json: String, id: String): JSONObject {
        val profiles = JSONObject(json).getJSONArray("companion_profiles")
        return (0 until profiles.length()).map { profiles.getJSONObject(it) }.single { it.getString("id") == id }
    }

    private fun variantsOf(array: JSONArray): List<Map<String, Any>> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        o.keys().asSequence().associateWith { o.get(it) }
    }

    @Test
    fun emittedManifestMatchesTheVersionTwoContractFixture() {
        val prefs = FakeSharedPreferences()
        seed(prefs, "p-aria", "Aria", ariaVariants)
        seed(prefs, "p-nox", "Nox", noxVariants)

        val json = manifestJson(prefs)
        val root = JSONObject(json)
        assertEquals("companion-roleplay-backup", root.getString("format"))
        assertEquals(2, root.getInt("format_version"))

        val aria = profile(json, "p-aria")
        assertEquals(variantsOf(ariaContract), variantsOf(aria.getJSONArray("prompt_variants")))
        assertEquals("You are Aria. 🌙", aria.getString("prompt"))

        // The receiving contract's strict reader accepts it unchanged.
        val parsed = (CompanionBackupCodec.parse(json) as CompanionBackupCodec.ParseResult.Ok).manifest
        assertEquals(
            ariaVariants.map(PortablePromptVariant::fromCompanionVariant),
            parsed.companionProfiles.single { it.id == "p-aria" }.promptVariants
        )
        assertEquals(
            noxVariants.map(PortablePromptVariant::fromCompanionVariant),
            parsed.companionProfiles.single { it.id == "p-nox" }.promptVariants
        )
        assertEquals("You are Nox.", parsed.companionProfiles.single { it.id == "p-nox" }.prompt)
    }

    @Test
    fun exportNeverWritesLivePreferencesOrReMintsIds() {
        val prefs = FakeSharedPreferences()
        seed(prefs, "p-aria", "Aria", ariaVariants)
        val before = prefs.all

        val first = manifestJson(prefs)
        val second = manifestJson(prefs)

        assertEquals(before, prefs.all)
        assertEquals(first, second)
    }

    @Test
    fun singlePromptCompanionExportsTheDeterministicLegacyVariant() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("p-old_label", "Old").putString("p-old_prompt", "Legacy text").commit()
        val before = prefs.all

        val parsed = (CompanionBackupCodec.parse(manifestJson(prefs)) as CompanionBackupCodec.ParseResult.Ok).manifest
        val old = parsed.companionProfiles.single()

        assertEquals(PortablePromptVariantRules.legacySingleVariant("p-old", "Legacy text"), old.promptVariants)
        assertEquals("Legacy text", old.prompt)
        assertEquals(before, prefs.all)
        assertFalse(prefs.contains("p-old_prompt_variants"))
    }

    @Test
    fun storedListWithoutADefaultIsCopiedWithTheFirstPromptAsDefault() {
        val prefs = FakeSharedPreferences()
        val stored = listOf(
            CompanionPromptVariant("x-1", "First", "first text", false),
            CompanionPromptVariant("x-2", "Second", "second text", false)
        )
        prefs.edit()
            .putString("p-x_label", "X")
            .putString("p-x_prompt_variants", CompanionPromptVariant.toJson(stored))
            .commit()
        val before = prefs.all

        val parsed = (CompanionBackupCodec.parse(manifestJson(prefs)) as CompanionBackupCodec.ParseResult.Ok).manifest
        val x = parsed.companionProfiles.single()

        assertEquals(listOf("x-1", "x-2"), x.promptVariants.map { it.id })
        assertEquals(listOf("first text", "second text"), x.promptVariants.map { it.text })
        assertEquals(listOf(true, false), x.promptVariants.map { it.isDefault })
        assertEquals("first text", x.prompt)
        assertEquals(before, prefs.all)
    }
}
