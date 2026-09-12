/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink

class PortableRestoreOutcomeTest {
    @Test
    fun structuredSuccessReportRoundTrips() {
        val expected = PortableRestoreOutcome.Success(
            PortableRestoreOutcome.Report(
                listOf(
                    PortableRestoreOutcome.Report.Line.ConflictCount(
                        PortableRestoreCategory.CHATS, 2
                    ),
                    PortableRestoreOutcome.Report.Line.NamedConflicts(
                        PortableRestoreCategory.COMPANIONS, listOf("A", "B"), 2
                    ),
                    PortableRestoreOutcome.Report.Line.LongerChats(1),
                    PortableRestoreOutcome.Report.Line.ProtectedImages(3)
                ),
                listOf(RemovedLorebookLink("Companion", "World"))
            )
        )

        assertEquals(
            expected,
            PortableRestoreOutcomeStore.decode(PortableRestoreOutcomeStore.encode(expected))
        )
    }

    @Test
    fun transactionFailureRoundTripsWithoutRenderingStrings() {
        val expected = PortableRestoreOutcome.TransactionFailure(
            SelectedCategoryRestoreTransaction.Failure.VALIDATION_FAILED,
            PortableRestoreCategory.CHATS.key,
            SelectedCategoryRestoreTransaction.DataState.UNCHANGED,
            PortableChatRestorePlan.Reason.IDENTITY_MISMATCH
        )

        assertEquals(
            expected,
            PortableRestoreOutcomeStore.decode(PortableRestoreOutcomeStore.encode(expected))
        )
    }

    @Test
    fun unknownOutcomeVersionIsRejected() {
        assertNull(PortableRestoreOutcomeStore.decode(
            org.json.JSONObject().put("version", 999).put("kind", "success")
        ))
    }
}
