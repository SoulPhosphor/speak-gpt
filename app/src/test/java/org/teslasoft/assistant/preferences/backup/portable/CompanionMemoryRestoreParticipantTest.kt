package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRowFormat
import org.teslasoft.assistant.preferences.memory.MemorySharedRestoreRows

class CompanionMemoryRestoreParticipantTest {
    @Test
    fun `later participant failure restores the exact shared snapshot`() {
        val current = state("before")
        val desired = state("after")
        val backend = FakeBackend(current)
        val transactionRoot = Files.createTempDirectory("shared-memory-participant").toFile()
        val journal = File(transactionRoot, "journal")
        val participant = participant(current, desired, backend, File(transactionRoot, "shared"))

        val result = SelectedCategoryRestoreTransaction.execute(
            journal,
            listOf(participant, failingParticipant())
        )

        assertEquals(
            SelectedCategoryRestoreTransaction.Failure.APPLY_FAILED,
            (result as SelectedCategoryRestoreTransaction.Result.Failed).reason
        )
        assertEquals(current, backend.rows)
        assertFalse(journal.exists())
    }

    @Test
    fun `failure before shared commit changes no logical rows`() {
        val current = state("before")
        val backend = FakeBackend(current).apply { failNextReplace = true }
        val root = Files.createTempDirectory("shared-memory-before-commit").toFile()

        val result = SelectedCategoryRestoreTransaction.execute(
            File(root, "journal"),
            listOf(participant(current, state("after"), backend, File(root, "shared")))
        )

        assertEquals(
            SelectedCategoryRestoreTransaction.Failure.APPLY_FAILED,
            (result as SelectedCategoryRestoreTransaction.Result.Failed).reason
        )
        assertEquals(current, backend.rows)
    }

    @Test
    fun `process interruption recovers through one stable shared participant key`() {
        val current = state("before")
        val backend = FakeBackend(current)
        val root = Files.createTempDirectory("shared-memory-process-death").toFile()
        val journal = File(root, "journal")
        val staging = File(root, "shared")
        val original = participant(current, state("after"), backend, staging)

        try {
            SelectedCategoryRestoreTransaction.execute(
                journal,
                listOf(original),
                SelectedCategoryRestoreTransaction.InterruptionPoint.AFTER_FIRST_APPLY
            )
            throw AssertionError("expected simulated interruption")
        } catch (_: Error) {
            // Durable outer journal and shared snapshot deliberately remain.
        }
        assertEquals("after", memoryContent(backend.rows))

        val reconstructed = CompanionMemoryRestoreParticipant(staging, backend)
        assertTrue(SelectedCategoryRestoreTransaction.recover(
            journal,
            mapOf(CompanionMemoryRestoreParticipant.CATEGORY_KEY to reconstructed)
        ))
        assertEquals(current, backend.rows)
        assertFalse(journal.exists())
    }

    @Test
    fun `recovery before first database open does not provision an absent store`() {
        val current = MemorySharedRestoreRowFormat.empty()
        val backend = FakeBackend(current, initiallyProvisioned = false)
        val root = Files.createTempDirectory("shared-memory-pre-open-death").toFile()
        val journal = File(root, "journal")
        val staging = File(root, "shared")
        val original = participant(current, state("incoming"), backend, staging)

        try {
            SelectedCategoryRestoreTransaction.execute(
                journal,
                listOf(original),
                SelectedCategoryRestoreTransaction.InterruptionPoint.AFTER_FIRST_PARTICIPANT_STARTED
            )
            throw AssertionError("expected simulated interruption")
        } catch (_: Error) {
            // The participant is journaled as started, but apply never opened a database.
        }

        val reconstructed = CompanionMemoryRestoreParticipant(staging, backend)
        assertTrue(SelectedCategoryRestoreTransaction.recover(
            journal,
            mapOf(CompanionMemoryRestoreParticipant.CATEGORY_KEY to reconstructed)
        ))
        assertFalse(backend.provisioned)
        assertEquals(0, backend.replaceCalls)
    }

    @Test
    fun `stage refuses a changed source generation without mutation`() {
        val plannedCurrent = state("planned")
        val liveCurrent = state("changed")
        val backend = FakeBackend(liveCurrent)
        val root = Files.createTempDirectory("shared-memory-generation").toFile()
        val participant = participant(plannedCurrent, state("desired"), backend, File(root, "shared"))

        val result = SelectedCategoryRestoreTransaction.execute(File(root, "journal"), listOf(participant))

        assertEquals(
            SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED,
            (result as SelectedCategoryRestoreTransaction.Result.Failed).reason
        )
        assertEquals(liveCurrent, backend.rows)
        assertFalse(File(root, "journal").exists())
    }

    private fun participant(
        current: MemorySharedRestoreRows,
        desired: MemorySharedRestoreRows,
        backend: FakeBackend,
        staging: File
    ) = CompanionMemoryRestoreParticipant(
        staging,
        backend,
        CompanionMemoryRestoreParticipant.PreparedPlan(
            current,
            desired,
            setOf("memories")
        )
    )

    private fun failingParticipant() = object : SelectedCategoryRestoreTransaction.Participant {
        override val categoryKey = "later"
        override fun validate() = true
        override fun stage() = true
        override fun apply() = false
        override fun rollback() = true
        override fun cleanup() = Unit
    }

    private class FakeBackend(
        initial: MemorySharedRestoreRows,
        initiallyProvisioned: Boolean = true
    ) :
        CompanionMemoryRestoreParticipant.Backend {
        var rows = initial
        var provisioned = initiallyProvisioned
        var failNextReplace = false
        var replaceCalls = 0

        override fun isProvisioned() = provisioned
        override fun snapshot() = rows

        override fun replace(
            rows: MemorySharedRestoreRows,
            affectedTables: Set<String>,
            requireExisting: Boolean
        ): Boolean {
            replaceCalls++
            if (requireExisting && !provisioned) return false
            if (failNextReplace) {
                failNextReplace = false
                return false
            }
            this.rows = rows
            provisioned = true
            return true
        }

        override fun removeProvisionedStore(): Boolean {
            provisioned = false
            return true
        }
    }

    private fun state(content: String): MemorySharedRestoreRows {
        val tables = MemorySharedRestoreRowFormat.empty().tables.mapValuesTo(LinkedHashMap()) {
            (table, rows) -> if (table == "memories") {
                listOf(linkedMapOf<String, Any?>("memory_id" to "memory", "content" to content))
            } else rows
        }
        return MemorySharedRestoreRows(tables)
    }

    private fun memoryContent(rows: MemorySharedRestoreRows): String =
        rows.tables.getValue("memories").single()["content"] as String
}
