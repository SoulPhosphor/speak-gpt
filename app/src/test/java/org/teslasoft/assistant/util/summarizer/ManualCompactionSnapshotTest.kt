/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompactionSnapshotTest {

    private fun user(text: String) = SummarizerController.Entry(false, text)
    private fun assistant(text: String) = SummarizerController.Entry(true, text)

    @Test
    fun appendedMessagesBelongToTheNextCheckpoint() {
        val frozen = listOf(user("one"), assistant("two"))
        val current = frozen + user("three")

        assertTrue(ManualCompactionSnapshot.prefixStillCurrent(frozen, current))
    }

    @Test
    fun editOrRemovalInsideFrozenPrefixRejectsCommit() {
        val frozen = listOf(user("one"), assistant("two"))

        assertFalse(
            ManualCompactionSnapshot.prefixStillCurrent(
                frozen,
                listOf(user("changed"), assistant("two"))
            )
        )
        assertFalse(
            ManualCompactionSnapshot.prefixStillCurrent(
                frozen,
                listOf(user("one"))
            )
        )
    }

    @Test
    fun stableAttachmentReferenceParticipatesInSnapshotIdentity() {
        val frozen = listOf(
            user("<attachment-reference>{\"id\":\"one\"}</attachment-reference>")
        )
        val changed = listOf(
            user("<attachment-reference>{\"id\":\"two\"}</attachment-reference>")
        )

        assertFalse(ManualCompactionSnapshot.prefixStillCurrent(frozen, changed))
    }
}
