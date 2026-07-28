package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportCapacityTest {

    @Test
    fun `retained allocations cannot cross the import allowance`() {
        val budget = ImportMemoryBudget.forTest(1024)
        val first = budget.claim(700)
        assertThrows(ImportMemoryBudget.LimitExceeded::class.java) {
            budget.claim(325)
        }
        first.release()
        val replacement = budget.claim(1024)
        assertEquals(1024, budget.retainedForTest())
        replacement.release()
        assertEquals(0, budget.retainedForTest())
    }

    @Test
    fun `resizing a retained allocation is admitted before growth`() {
        val budget = ImportMemoryBudget.forTest(1000)
        val charge = budget.claim(400)
        charge.resize(900)
        assertEquals(900, budget.retainedForTest())
        assertThrows(ImportMemoryBudget.LimitExceeded::class.java) {
            charge.resize(1001)
        }
    }

    @Test
    fun `archive ratio starts at one mebibyte of compressed input`() {
        val belowStart = OfficeArchiveExpansionGuard(
            compressedSourceBytes = 1024,
            maximumExpandedBytes = 256L * 1024L * 1024L
        )
        belowStart.preflightTotal(200_000)

        val enforced = OfficeArchiveExpansionGuard(
            compressedSourceBytes = 1L * 1024L * 1024L,
            maximumExpandedBytes = 256L * 1024L * 1024L
        )
        assertThrows(OfficeArchiveExpansionGuard.LimitExceeded::class.java) {
            enforced.preflightTotal(100L * 1024L * 1024L + 1L)
        }
    }

    @Test
    fun `archive total expanded data has its own ceiling`() {
        val guard = OfficeArchiveExpansionGuard(
            compressedSourceBytes = 512L * 1024L,
            maximumExpandedBytes = 10L * 1024L * 1024L
        )
        assertThrows(OfficeArchiveExpansionGuard.LimitExceeded::class.java) {
            guard.preflightTotal(10L * 1024L * 1024L + 1L)
        }
    }

    @Test
    fun `budgeted builder accounts for builder and immutable copy together`() {
        val budget = ImportMemoryBudget.forTest(100)
        val output = BudgetedTextBuilder(budget, initialCapacity = 20)
        output.append("x".repeat(20))
        assertThrows(ImportMemoryBudget.LimitExceeded::class.java) {
            output.append("y".repeat(40))
        }
        output.discard()
        assertEquals(0, budget.retainedForTest())
    }
}
