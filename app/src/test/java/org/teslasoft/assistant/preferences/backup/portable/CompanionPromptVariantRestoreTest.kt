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

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionRestorePlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionSettingsPayload
import org.teslasoft.assistant.preferences.backup.companion.CompanionSettingsPayloadCodec
import org.teslasoft.assistant.preferences.dto.CompanionPromptVariant
import org.teslasoft.assistant.preferences.dto.PersonaObject
import java.io.File

/**
 * Companion prompt variants through the real new-client export, archive,
 * restore-planning, Replace/Merge, rollback and journal paths, using only
 * synthetic companions held in in-memory preference stores
 * (multi-prompt-backup-restore-plan.md, Phase A verification).
 */
class CompanionPromptVariantRestoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private object NoSync : PersonaPreferences.CompanionSync {
        override fun onPersonaSaved(persona: PersonaObject) {}
        override fun onPersonaDeleted(personaId: String) {}
    }

    /** Three ordered variants with stable ids; the default is the middle one. */
    private val ariaVariants = listOf(
        CompanionPromptVariant("aria-v-alt", "Alternate", "Line one\nLine two", false),
        CompanionPromptVariant("aria-v-main", "Main ✦ 主要", "You are Aria. 🌙", true),
        CompanionPromptVariant("aria-v-blank", "", "", false)
    )

    private val noxVariants = listOf(
        CompanionPromptVariant("nox-v-1", "Calm", "You are Nox.", true),
        CompanionPromptVariant("nox-v-2", "Stormy", "You are Nox, but louder.", false)
    )

    /** Seeds the companion keys exactly as the app stores them. */
    private fun seed(
        prefs: FakeSharedPreferences,
        id: String,
        label: String,
        variants: List<CompanionPromptVariant>
    ) {
        PersonaPreferences.createForTest(prefs, NoSync).setPersona(
            PersonaObject(
                label = label,
                prompt = CompanionPromptVariant.defaultPrompt(variants),
                promptVariants = ArrayList(variants.map { it.copy() }),
                id = id
            )
        )
    }

    private fun seededStore(): FakeSharedPreferences = FakeSharedPreferences().also {
        seed(it, "p-aria", "Aria", ariaVariants)
        seed(it, "p-nox", "Nox", noxVariants)
    }

    /** The exporter's companion section, built from a preference store. */
    private fun exportManifest(prefs: FakeSharedPreferences): CompanionBackupManifest {
        val personas = PersonaPreferences.createForTest(prefs, NoSync)
            .getPersonasList().sortedBy { it.id }
        return CompanionBackupManifest(
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
    }

    private fun archiveOf(prefs: FakeSharedPreferences, name: String): File =
        tmp.newFile(name).also {
            CompanionBackupExporter.writeZip(it, exportManifest(prefs), emptyMap())
        }

    private fun validated(archive: File): CompanionBackupManifest =
        (CompanionBackupValidator.validate(archive) as CompanionBackupValidator.Verdict.Valid).manifest

    /** Whole-file replacement, as CompanionSettingsApplier does for `personas`. */
    private fun replaceAll(prefs: FakeSharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit().clear()
        for ((key, value) in values) editor.putString(key, value as String)
        editor.commit()
    }

    /** Logical archive content, excluding only app_version and exported_at. */
    private fun logical(manifest: CompanionBackupManifest): String =
        CompanionBackupCodec.toJson(manifest.copy(appVersion = "", exportedAt = ""))

    private fun storedVariants(prefs: FakeSharedPreferences, id: String): List<CompanionPromptVariant> =
        CompanionPromptVariant.fromJson(prefs.getString(id + "_prompt_variants", "")!!)

    /* ------------------------------- export ------------------------------- */

    @Test
    fun exporterCarriesEveryVariantWithoutMutatingOrReMinting() {
        val prefs = seededStore()
        val before = prefs.all

        val first = exportManifest(prefs)
        val second = exportManifest(prefs)

        assertEquals("export never writes to the companion store", before, prefs.all)
        assertEquals("ids are stable across captures", first, second)
        val aria = first.companionProfiles.first { it.id == "p-aria" }
        assertEquals(ariaVariants.map(PortablePromptVariant::fromCompanionVariant), aria.promptVariants)
        assertEquals("You are Aria. 🌙", aria.prompt)
        val nox = first.companionProfiles.first { it.id == "p-nox" }
        assertEquals(noxVariants.map(PortablePromptVariant::fromCompanionVariant), nox.promptVariants)

        // And the archive itself parses back to the identical list.
        val parsed = validated(archiveOf(prefs, "a.zip"))
        assertEquals(first.companionProfiles, parsed.companionProfiles)
    }

    @Test
    fun legacyCompanionExportsOneDeterministicVariantAndIsNotRewritten() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("p-old_label", "Old").putString("p-old_prompt", "Legacy text").commit()
        val before = prefs.all

        val profile = exportManifest(prefs).companionProfiles.single()

        assertEquals(PortablePromptVariantRules.legacySingleVariant("p-old", "Legacy text"), profile.promptVariants)
        assertEquals("Legacy text", profile.prompt)
        assertEquals(before, prefs.all)
        assertFalse(prefs.contains("p-old_prompt_variants"))
    }

    @Test
    fun storedListWithoutADefaultIsCarriedWithTheAppsEffectiveDefault() {
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

        val profile = exportManifest(prefs).companionProfiles.single()

        assertEquals(listOf("x-1", "x-2"), profile.promptVariants.map { it.id })
        assertEquals(listOf(true, false), profile.promptVariants.map { it.isDefault })
        assertEquals(CompanionPromptVariant.defaultPrompt(stored), profile.prompt)
        assertEquals(before, prefs.all)
    }

    /* ------------------- standalone export -> restore -> export ------------------- */

    @Test
    fun standaloneExportRestoreExportIsLogicallyEqual() {
        val source = seededStore()
        val archiveA = archiveOf(source, "a.zip")
        val manifestA = validated(archiveA)

        val target = FakeSharedPreferences()
        replaceAll(target, CompanionRestorePlanner.plan(manifestA, emptySet()).settingsNew.personas)

        // Both stored representations are written.
        assertEquals(ariaVariants, storedVariants(target, "p-aria"))
        assertEquals("You are Aria. 🌙", target.getString("p-aria_prompt", null))
        assertEquals(noxVariants, storedVariants(target, "p-nox"))
        assertEquals("You are Nox.", target.getString("p-nox_prompt", null))

        // The app loads the restored companion with the same prompts.
        val loaded = PersonaPreferences.createForTest(target, NoSync).getPersona("p-aria")
        assertEquals(ariaVariants, loaded.promptVariants)
        assertEquals("You are Aria. 🌙", loaded.prompt)

        val manifestB = validated(archiveOf(target, "b.zip"))
        assertEquals(logical(manifestA), logical(manifestB))
        assertEquals(manifestA.companionProfiles, manifestB.companionProfiles)
    }

    @Test
    fun versionOneArchiveRestoresAsOneVariantAndReExportsAsVersionTwo() {
        val v1 = tmp.newFile("v1.zip")
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("p-old_label", "Old").putString("p-old_prompt", "Legacy text").commit()
        val v2Json = org.json.JSONObject(CompanionBackupCodec.toJson(exportManifest(prefs)))
        v2Json.put("format_version", 1)
        v2Json.getJSONArray("companion_profiles").getJSONObject(0).remove("prompt_variants")
        java.util.zip.ZipOutputStream(v1.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(CompanionBackupFormat.MANIFEST_ENTRY))
            zip.write(v2Json.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val v1Bytes = v1.readBytes()

        val parsed = validated(v1)
        val target = FakeSharedPreferences()
        replaceAll(target, CompanionRestorePlanner.plan(parsed, emptySet()).settingsNew.personas)

        val expected = PortablePromptVariantRules.legacySingleVariant("p-old", "Legacy text")
            .map { it.toCompanionVariant() }
        assertEquals(expected, storedVariants(target, "p-old"))
        assertEquals("Legacy text", target.getString("p-old_prompt", null))
        assertTrue("the source backup is never rewritten", v1Bytes.contentEquals(v1.readBytes()))

        val reExported = validated(archiveOf(target, "again.zip"))
        assertEquals(CompanionBackupFormat.FORMAT_VERSION, reExported.formatVersion)
        assertEquals(parsed.companionProfiles, reExported.companionProfiles)
    }

    /* ------------------ complete portable Recovery Backup path ------------------ */

    @Test
    fun completeRecoveryBackupPathRestoresTheSameLogicalFixture() {
        val source = seededStore()
        val archiveA = archiveOf(source, "companion_archive_a.zip")
        val manifestA = validated(archiveA)

        val inner = File(tmp.root, "inner.zip")
        PortablePackage.buildInnerZip(
            listOf(
                PortablePackage.Artifact(
                    "companion_roleplay.zip",
                    PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE,
                    archiveA,
                    null,
                    null,
                    CompanionBackupFormat.FORMAT_VERSION
                )
            ),
            "2026-09-22T00:00:00Z",
            inner
        )
        val pkg = File(tmp.root, "package.bin")
        val secret = PackageCrypto.newRecoverySecret()
        PortablePackage.envelope(inner, pkg, "2026-09-22T00:00:00Z", "test", secret, null)
        val pkgBytes = pkg.readBytes()

        val staging = tmp.newFolder("staging")
        val decoded = PortablePackage.decodeWithSecret(pkg, secret, staging) as PortablePackage.DecodeResult.Ok
        val extracted = PortablePackage.validateAndExtract(decoded.innerZip, staging) as
            PortablePackage.ValidateResult.Ok
        val artifact = extracted.artifacts.single {
            it.type == PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE
        }
        assertEquals(CompanionBackupFormat.FORMAT_VERSION, artifact.schemaVersion)
        val incoming = validated(artifact.stagedFile)
        assertEquals(manifestA, incoming)

        // A device that already has a different companion: Replace takes the
        // backup's complete companion list.
        val target = FakeSharedPreferences()
        seed(target, "p-other", "Other", listOf(CompanionPromptVariant("o-1", "Only", "Other", true)))
        val currentArchive = archiveOf(target, "current.zip")
        val current = validated(currentArchive)
        val selections = listOf(
            CompanionCategoryPlanner.Selection(PortableRestoreCategory.COMPANIONS, PortableRestoreMode.REPLACE)
        )
        val planned = CompanionCategoryPlanner.plan(current, incoming, selections) as
            CompanionCategoryPlanner.Result.Ready
        val prepared = CompanionCategoryRestoreParticipant.PreparedPlan(
            currentArchive,
            current,
            incoming,
            planned.manifest,
            planned.report,
            CompanionRestorePlanner.plan(planned.manifest, emptySet()),
            CompanionRestorePlanner.plan(current, emptySet())
        )
        val participant = CompanionCategoryRestoreParticipant(
            artifact.stagedFile, selections, File(tmp.root, "stage"), PrefsBackend(target), prepared
        )
        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertTrue(participant.apply())

        assertEquals(ariaVariants, storedVariants(target, "p-aria"))
        assertEquals(noxVariants, storedVariants(target, "p-nox"))
        assertFalse(target.contains("p-other_label"))
        val manifestB = validated(archiveOf(target, "companion_archive_b.zip"))
        assertEquals(logical(manifestA), logical(manifestB))
        assertTrue("the source package is never rewritten", pkgBytes.contentEquals(pkg.readBytes()))
        participant.cleanup()
    }

    /* ---------------------------- Replace / Merge ---------------------------- */

    private fun participant(
        incomingArchive: File,
        mode: PortableRestoreMode,
        backend: PrefsBackend,
        stage: String
    ) = CompanionCategoryRestoreParticipant(
        incomingArchive,
        listOf(CompanionCategoryPlanner.Selection(PortableRestoreCategory.COMPANIONS, mode)),
        File(tmp.root, stage),
        backend
    )

    @Test
    fun replaceTakesTheCompleteIncomingListAndRollbackReturnsTheOldListExactly() {
        val incoming = archiveOf(seededStore(), "incoming.zip")
        val target = FakeSharedPreferences()
        val oldAria = listOf(
            CompanionPromptVariant("aria-old-1", "Before", "Old Aria", true),
            CompanionPromptVariant("aria-old-2", "Before 2", "Old Aria 2", false)
        )
        seed(target, "p-aria", "Aria", oldAria)
        val before = target.all

        val restore = participant(incoming, PortableRestoreMode.REPLACE, PrefsBackend(target), "replace")
        assertTrue(restore.validate())
        assertTrue(restore.stage())
        assertTrue(restore.apply())
        assertEquals(ariaVariants, storedVariants(target, "p-aria"))
        assertEquals(noxVariants, storedVariants(target, "p-nox"))

        assertTrue(restore.rollback())
        assertEquals(oldAria, storedVariants(target, "p-aria"))
        assertEquals("Old Aria", target.getString("p-aria_prompt", null))
        assertEquals(before, target.all)
        restore.cleanup()
    }

    @Test
    fun mergeAddsNewCompanionWithFullListAndKeepsCurrentOnVariantConflict() {
        val incoming = archiveOf(seededStore(), "incoming.zip")
        val target = FakeSharedPreferences()
        // Same companion id; the ONLY difference is one variant's text.
        val currentAria = ariaVariants.map { it.copy() }.also { it[2].text = "edited locally" }
        seed(target, "p-aria", "Aria", currentAria)

        val restore = participant(incoming, PortableRestoreMode.MERGE, PrefsBackend(target), "merge")
        assertTrue(restore.validate())
        assertTrue(restore.stage())
        assertTrue(restore.apply())

        assertEquals("current companion wins", currentAria, storedVariants(target, "p-aria"))
        assertEquals("new companion keeps its full list", noxVariants, storedVariants(target, "p-nox"))
        val conflict = restore.report!!.conflicts.single()
        assertEquals(PortableRestoreCategory.COMPANIONS, conflict.category)
        assertEquals("p-aria", conflict.itemId)
        restore.cleanup()
    }

    /* ------------------- change detection / source generation ------------------- */

    @Test
    fun anyVariantChangeChangesCompanionEqualityAndTheIdentityToken() {
        val base = exportManifest(seededStore())
        val baseToken = UnifiedPortableRestore.identityToken(base)
        assertEquals(baseToken, UnifiedPortableRestore.identityToken(exportManifest(seededStore())))

        val edits = listOf<(List<CompanionPromptVariant>) -> List<CompanionPromptVariant>>(
            { list -> list.map { it.copy() }.also { it[0].text = "changed" } },
            { list -> list.map { it.copy() }.also { it[0].name = "renamed" } },
            { list -> list.map { if (it.id == "aria-v-blank") it.copy(id = "aria-v-new") else it.copy() } },
            { list -> list.reversed().map { it.copy() } },
            { list -> list.map { it.copy(isDefault = it.id == "aria-v-alt") } },
            { list -> list + CompanionPromptVariant("aria-v-extra", "Extra", "", false) }
        )
        for (edit in edits) {
            val prefs = FakeSharedPreferences()
            seed(prefs, "p-aria", "Aria", edit(ariaVariants))
            seed(prefs, "p-nox", "Nox", noxVariants)
            val changed = exportManifest(prefs)
            assertFalse(
                base.companionProfiles.first { it.id == "p-aria" } ==
                    changed.companionProfiles.first { it.id == "p-aria" }
            )
            assertFalse(baseToken == UnifiedPortableRestore.identityToken(changed))
        }
    }

    /* ------------------------ journal / interrupted restore ------------------------ */

    @Test
    fun journalSnapshotPreservesTheVariantKeyAndRollsBackExactly() {
        val target = FakeSharedPreferences()
        val oldAria = listOf(
            CompanionPromptVariant("aria-old-1", "Before", "Old Aria", false),
            CompanionPromptVariant("aria-old-2", "Before 2", "Old Aria 2", true)
        )
        seed(target, "p-aria", "Aria", oldAria)
        val snapshot = CompanionSettingsPayload(HashMap(target.all), emptyMap(), emptyMap(), "")
        val incoming = validated(archiveOf(seededStore(), "incoming.zip"))
        val settingsNew = CompanionRestorePlanner.plan(incoming, emptySet()).settingsNew

        // Both payloads cross the journal before anything is written.
        val journaledOld = CompanionSettingsPayloadCodec.fromJson(CompanionSettingsPayloadCodec.toJson(snapshot))
        val journaledNew = CompanionSettingsPayloadCodec.fromJson(CompanionSettingsPayloadCodec.toJson(settingsNew))
        assertEquals(snapshot.personas, journaledOld.personas)
        assertEquals(settingsNew.personas, journaledNew.personas)
        assertTrue(journaledOld.personas.containsKey("p-aria_prompt_variants"))
        assertTrue(journaledNew.personas.containsKey("p-nox_prompt_variants"))

        // Interrupted after applying: recovery rolls back to the journaled snapshot.
        replaceAll(target, journaledNew.personas)
        assertEquals(ariaVariants, storedVariants(target, "p-aria"))
        replaceAll(target, journaledOld.personas)
        assertEquals(oldAria, storedVariants(target, "p-aria"))
        assertEquals("Old Aria 2", target.getString("p-aria_prompt", null))
        assertFalse(target.contains("p-nox_label"))
        assertEquals(snapshot.personas, target.all)
    }

    /** Applies restore plans into an in-memory `personas` store. */
    private inner class PrefsBackend(
        private val prefs: FakeSharedPreferences
    ) : CompanionCategoryRestoreParticipant.Backend {
        override fun snapshot(destination: File): CompanionBackupManifest {
            val manifest = exportManifest(prefs)
            CompanionBackupExporter.writeZip(destination, manifest, emptyMap())
            return manifest
        }

        override fun imagePresence(hash: String) =
            CompanionCategoryRestoreParticipant.ImagePresence(file = false, catalog = false)

        override fun recoverPending(): Boolean = true

        override fun apply(manifest: CompanionBackupManifest, archive: File): Boolean =
            apply(manifest, archive, CompanionRestorePlanner.plan(manifest, emptySet()))

        override fun apply(
            manifest: CompanionBackupManifest,
            archive: File,
            restorePlan: CompanionRestorePlanner.Plan
        ): Boolean {
            replaceAll(prefs, restorePlan.settingsNew.personas)
            return true
        }

        override fun removeRestoredImage(hash: String, removeFile: Boolean, removeCatalog: Boolean) = true
    }
}
