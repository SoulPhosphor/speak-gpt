/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMemoryAttributionTest {

    @Test
    fun finalSelectionKeepsMemoriesFirstAndPreservesSourceOrder() {
        val references = ActiveMemoryAttribution.fromFinalSelection(
            memoryIds = listOf("m-one", "m-two"),
            lorebookIds = listOf("lore-one", "lore-two")
        )

        assertEquals(
            listOf("m-one", "m-two", "lore-one", "lore-two"),
            references.map { it.id }
        )
        assertEquals(
            listOf("memory", "memory", "lorebook", "lorebook"),
            references.map { it.source }
        )
    }

    @Test
    fun attributionRoundTripsWithoutCopyingEntryText() {
        val references = ActiveMemoryAttribution.fromFinalSelection(
            listOf("m-uuid"),
            listOf("lore-uuid")
        )

        val json = ActiveMemoryAttribution.encode(references)

        assertEquals(references, ActiveMemoryAttribution.decode(json))
        assertTrue(json?.contains("content") == false)
        assertTrue(json?.contains("label") == false)
    }

    @Test
    fun emptyOrUnreadableAttributionStaysAbsent() {
        assertNull(ActiveMemoryAttribution.encode(emptyList()))
        assertTrue(ActiveMemoryAttribution.decode(null).isEmpty())
        assertTrue(ActiveMemoryAttribution.decode("not json").isEmpty())
    }
}
