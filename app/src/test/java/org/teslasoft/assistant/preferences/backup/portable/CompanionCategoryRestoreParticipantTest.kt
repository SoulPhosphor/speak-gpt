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
import org.teslasoft.assistant.preferences.backup.companion.CompanionCategoryPlanner

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

    private class FakeBackend(initial: CompanionBackupManifest) :
        CompanionCategoryRestoreParticipant.Backend {
        var live = initial
        val removed = ArrayList<String>()

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
