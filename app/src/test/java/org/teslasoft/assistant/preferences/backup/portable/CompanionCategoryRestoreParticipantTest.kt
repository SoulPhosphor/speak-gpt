package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.companion.ActivationPromptEntry
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupValidator
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner
import org.teslasoft.assistant.preferences.backup.companion.CompanionProfileEntry
import org.teslasoft.assistant.preferences.backup.companion.CompanionRestorePlanner
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink

class CompanionCategoryRestoreParticipantTest {
    @Test
    fun `participant applies only selected logical categories and restores exact current archive`() {
        val root = Files.createTempDirectory("identity-participant").toFile()
        try {
            val current = manifest(
                activation = listOf(ActivationPromptEntry("current", "Current", "current")),
                glamourId = "current-glamour"
            )
            val incoming = manifest(
                activation = listOf(ActivationPromptEntry("backup", "Backup", "backup")),
                glamourId = "backup-glamour"
            )
            val incomingArchive = File(root, "incoming.zip")
            CompanionBackupExporter.writeZip(incomingArchive, incoming, emptyMap())
            val backend = FakeBackend(current)
            val participant = CompanionCategoryRestoreParticipant(
                incomingArchive,
                listOf(
                    CompanionCategoryPlanner.Selection(
                        PortableRestoreCategory.ACTIVATION_PROMPTS,
                        PortableRestoreMode.REPLACE
                    )
                ),
                File(root, "stage"),
                backend
            )

            assertTrue(participant.validate())
            assertTrue(participant.stage())
            assertEquals(
                setOf("current-glamour"),
                participant.memoryReferenceIds()?.userPersonas
            )
            assertTrue(participant.apply())
            assertEquals("backup", backend.live.activationPrompts.single().id)
            assertEquals("current-glamour", backend.live.roleplayTables.getValue("user_personas").single()["persona_id"])

            assertTrue(participant.rollback())
            assertEquals(current, backend.live)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rollback removes only image state that the participant introduced`() {
        val root = Files.createTempDirectory("identity-image-rollback").toFile()
        try {
            val current = manifest()
            val incoming = manifest()
            val archive = File(root, "incoming.zip")
            CompanionBackupExporter.writeZip(archive, incoming, emptyMap())
            val backend = FakeBackend(current)
            val participant = CompanionCategoryRestoreParticipant(
                archive,
                listOf(
                    CompanionCategoryPlanner.Selection(
                        PortableRestoreCategory.COMPANIONS,
                        PortableRestoreMode.MERGE
                    )
                ),
                File(root, "stage"),
                backend
            )
            assertTrue(participant.validate())
            assertTrue(participant.stage())
            assertTrue(participant.apply())
            assertTrue(participant.rollback())
            assertTrue(backend.removed.isEmpty())
            assertFalse(File(root, "stage").let { participant.cleanup(); it.exists() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `precomputed plan retains removed lorebook links and applies that exact plan`() {
        val root = Files.createTempDirectory("identity-removed-links").toFile()
        try {
            val prepared = preparedPlan(
                root,
                finalLorebookIds = emptySet(),
                rollbackLorebookIds = setOf("lb-current")
            )
            val backend = FakeBackend(prepared.current)
            val participant = CompanionCategoryRestoreParticipant(
                prepared.incomingArchive,
                listOf(replaceCompanions()),
                File(root, "stage"),
                backend,
                prepared.plan
            )

            // Item 4: the removed link survives as a structured outcome instead
            // of being discarded behind the Boolean apply result.
            assertTrue(participant.validate())
            assertEquals(
                listOf(RemovedLorebookLink("Aria", "Missing Book")),
                participant.removedLorebookLinks
            )

            assertTrue(participant.stage())
            assertTrue(participant.apply())
            // Apply consumes the desired-context plan (the missing link removed)…
            assertEquals(
                listOf(RemovedLorebookLink("Aria", "Missing Book")),
                backend.appliedRestorePlan?.removedLinks
            )

            // …and rollback consumes the original-context plan (nothing removed).
            assertTrue(participant.rollback())
            assertEquals(emptyList<RemovedLorebookLink>(), backend.appliedRestorePlan?.removedLinks)
            assertEquals(prepared.current, backend.live)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `participant reordering keeps identity removed links and applied state stable`() {
        val root = Files.createTempDirectory("identity-reorder-success").toFile()
        try {
            val identityFirst = runOrderedSuccess(File(root, "identity-first"), lorebookFirst = false)
            val lorebookFirst = runOrderedSuccess(File(root, "lorebook-first"), lorebookFirst = true)
            // Acceptance gate: participant order cannot change the planned final
            // links or the applied identity state.
            assertEquals(identityFirst.first, lorebookFirst.first)
            assertEquals(identityFirst.second, lorebookFirst.second)
            assertEquals(listOf(RemovedLorebookLink("Aria", "Missing Book")), identityFirst.first)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `participant reordering keeps the identity rollback result stable`() {
        val root = Files.createTempDirectory("identity-reorder-rollback").toFile()
        try {
            val identityFirst = runOrderedRollback(File(root, "identity-first"), lorebookFirst = false)
            val lorebookFirst = runOrderedRollback(File(root, "lorebook-first"), lorebookFirst = true)
            // Acceptance gate: the rollback result is identical regardless of the
            // participant ordering the coordinator uses.
            assertEquals(identityFirst, lorebookFirst)
        } finally {
            root.deleteRecursively()
        }
    }

    /** Runs a successful transaction and returns the applied removed links and
     *  the identity state the backend ended on. */
    private fun runOrderedSuccess(
        runRoot: File,
        lorebookFirst: Boolean
    ): Pair<List<RemovedLorebookLink>, CompanionBackupManifest> {
        runRoot.mkdirs()
        val prepared = preparedPlan(
            runRoot,
            finalLorebookIds = emptySet(),
            rollbackLorebookIds = setOf("lb-current")
        )
        val backend = FakeBackend(prepared.current)
        val identity = CompanionCategoryRestoreParticipant(
            prepared.incomingArchive,
            listOf(replaceCompanions()),
            File(runRoot, "stage"),
            backend,
            prepared.plan
        )
        val lorebook = StubParticipant("lorebooks")
        val order = if (lorebookFirst) listOf(lorebook, identity) else listOf(identity, lorebook)
        val result = SelectedCategoryRestoreTransaction.execute(File(runRoot, "journal"), order)
        assertTrue(result is SelectedCategoryRestoreTransaction.Result.Success)
        return backend.appliedRestorePlan!!.removedLinks to backend.live
    }

    /** Runs a transaction that fails on a trailing participant, forcing the
     *  identity participant to roll back, and returns its restored state. */
    private fun runOrderedRollback(runRoot: File, lorebookFirst: Boolean): CompanionBackupManifest {
        runRoot.mkdirs()
        val prepared = preparedPlan(
            runRoot,
            finalLorebookIds = emptySet(),
            rollbackLorebookIds = setOf("lb-current")
        )
        val backend = FakeBackend(prepared.current)
        val identity = CompanionCategoryRestoreParticipant(
            prepared.incomingArchive,
            listOf(replaceCompanions()),
            File(runRoot, "stage"),
            backend,
            prepared.plan
        )
        val lorebook = StubParticipant("lorebooks")
        val failing = StubParticipant("failing_stub", applyResult = false)
        val order = if (lorebookFirst) listOf(lorebook, identity, failing)
        else listOf(identity, lorebook, failing)
        val result = SelectedCategoryRestoreTransaction.execute(File(runRoot, "journal"), order)
        assertTrue(result is SelectedCategoryRestoreTransaction.Result.Failed)
        return backend.live
    }

    private fun replaceCompanions() = CompanionCategoryPlanner.Selection(
        PortableRestoreCategory.COMPANIONS, PortableRestoreMode.REPLACE
    )

    /** A companion whose core lorebook resolves in the current library
     *  ("lb-current") but not in the backup's final library ("lb-missing"), so
     *  a combined restore removes exactly one link. */
    private fun preparedPlan(
        root: File,
        finalLorebookIds: Set<String>,
        rollbackLorebookIds: Set<String>
    ): Prepared {
        val currentSeed = manifestWith(profile(core = "lb-current", coreName = "Current Book"))
        val incomingSeed = manifestWith(profile(core = "lb-missing", coreName = "Missing Book"))
        val currentArchive = File(root, "current-seed.zip")
        val incomingArchive = File(root, "incoming-seed.zip")
        CompanionBackupExporter.writeZip(currentArchive, currentSeed, emptyMap())
        CompanionBackupExporter.writeZip(incomingArchive, incomingSeed, emptyMap())
        val current = validated(currentArchive)
        val incoming = validated(incomingArchive)
        val desired = incoming
        val plan = CompanionCategoryRestoreParticipant.PreparedPlan(
            currentArchive = currentArchive,
            current = current,
            incoming = incoming,
            desired = desired,
            report = CompanionCategoryPlanner.Report(emptyList(), 0),
            restorePlan = CompanionRestorePlanner.plan(desired, finalLorebookIds),
            rollbackPlan = CompanionRestorePlanner.plan(current, rollbackLorebookIds)
        )
        return Prepared(plan, incomingArchive, current)
    }

    private data class Prepared(
        val plan: CompanionCategoryRestoreParticipant.PreparedPlan,
        val incomingArchive: File,
        val current: CompanionBackupManifest
    )

    private fun validated(archive: File): CompanionBackupManifest =
        (CompanionBackupValidator.validate(archive) as CompanionBackupValidator.Verdict.Valid).manifest

    private fun profile(core: String, coreName: String) = CompanionProfileEntry(
        id = "c1",
        label = "Aria",
        prompt = "prompt",
        activationPromptId = "",
        coreLoreBookId = core,
        coreLoreBookName = coreName,
        additionalLoreBookIds = emptyList(),
        additionalLoreBookNames = emptyMap(),
        autoLoadLastLoreBooks = false,
        lastUsedLoreBookIds = emptyList(),
        avatarRef = ""
    )

    private fun manifestWith(profile: CompanionProfileEntry) = CompanionBackupManifest(
        CompanionBackupFormat.FORMAT_VERSION,
        "test",
        "2026-09-12T00:00:00Z",
        listOf(profile),
        emptyList(),
        emptyList(),
        "",
        CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
        emptyList()
    )

    /** A minimal co-participant used only to vary participant ordering. */
    private class StubParticipant(
        override val categoryKey: String,
        private val applyResult: Boolean = true
    ) : SelectedCategoryRestoreTransaction.Participant {
        override fun validate(): Boolean = true
        override fun stage(): Boolean = true
        override fun apply(): Boolean = applyResult
        override fun rollback(): Boolean = true
        override fun cleanup() {}
    }

    private class FakeBackend(initial: CompanionBackupManifest) :
        CompanionCategoryRestoreParticipant.Backend {
        var live = initial
        val removed = ArrayList<String>()
        var appliedRestorePlan: CompanionRestorePlanner.Plan? = null

        override fun snapshot(destination: File): CompanionBackupManifest {
            CompanionBackupExporter.writeZip(destination, live, emptyMap())
            return live
        }

        override fun imagePresence(hash: String) =
            CompanionCategoryRestoreParticipant.ImagePresence(file = false, catalog = false)

        override fun recoverPending(): Boolean = true

        override fun apply(manifest: CompanionBackupManifest, archive: File): Boolean {
            live = manifest
            return true
        }

        override fun apply(
            manifest: CompanionBackupManifest,
            archive: File,
            restorePlan: CompanionRestorePlanner.Plan
        ): Boolean {
            appliedRestorePlan = restorePlan
            live = manifest
            return true
        }

        override fun removeRestoredImage(
            hash: String,
            removeFile: Boolean,
            removeCatalog: Boolean
        ): Boolean {
            if (removeFile || removeCatalog) removed.add(hash)
            return true
        }
    }

    private fun manifest(
        activation: List<ActivationPromptEntry> = emptyList(),
        glamourId: String? = null
    ) = CompanionBackupManifest(
        CompanionBackupFormat.FORMAT_VERSION,
        "test",
        "2026-09-08T00:00:00Z",
        emptyList(),
        activation,
        emptyList(),
        "",
        CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { table ->
            if (table == "user_personas" && glamourId != null) {
                listOf(linkedMapOf("persona_id" to glamourId, "name" to glamourId))
            } else emptyList()
        },
        emptyList()
    )
}
