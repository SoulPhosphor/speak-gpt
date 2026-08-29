package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionStorageGuardTest {
    @Test fun appendedMessagesDoNotInvalidateFrozenPrefix() {
        val frozen = listOf(ManualCompactionStorageGuard.Row(false, "one", "", ""))
        assertTrue(
            ManualCompactionStorageGuard.prefixStillCurrent(
                frozen,
                frozen + ManualCompactionStorageGuard.Row(true, "two", "", "")
            )
        )
    }

    @Test fun editsAndIncludeChangesInvalidateFrozenPrefix() {
        val frozen = listOf(ManualCompactionStorageGuard.Row(false, "one", "[]", ""))
        assertFalse(
            ManualCompactionStorageGuard.prefixStillCurrent(
                frozen,
                listOf(ManualCompactionStorageGuard.Row(false, "changed", "[]", ""))
            )
        )
        assertFalse(
            ManualCompactionStorageGuard.prefixStillCurrent(
                frozen,
                listOf(ManualCompactionStorageGuard.Row(false, "one", "[changed]", ""))
            )
        )
    }
}
