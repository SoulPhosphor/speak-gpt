/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStartDateFillTest {

    private val chats = ChatPreferences.getChatPreferences()

    private fun row(id: String, createdAt: String? = null) = hashMapOf("id" to id, "name" to id).apply {
        if (createdAt != null) put("created_at", createdAt)
    }

    @Test fun fillsOnlyRowsWithoutADate() {
        val rows = listOf(row("a", "100"), row("b"))
        val fill = chats.applyStartDates(rows) { ChatPreferences.StartDate.Known(500) }
        assertEquals("100", rows[0]["created_at"])
        assertEquals("500", rows[1]["created_at"])
        assertTrue(fill.changed)
        assertFalse(fill.waiting)
    }

    @Test fun nothingToWriteWhenEveryRowHasADate() {
        val fill = chats.applyStartDates(listOf(row("a", "100"))) { error("not asked") }
        assertFalse(fill.changed)
        assertFalse(fill.waiting)
    }

    @Test fun aChatNotYetPreparedKeepsTheFillWaiting() {
        val rows = listOf(row("a"))
        val fill = chats.applyStartDates(rows) { null }
        assertNull(rows[0]["created_at"])
        assertFalse(fill.changed)
        assertTrue(fill.waiting)
    }

    @Test fun aChatWithNoDatedMessageIsLeftAsItIs() {
        val rows = listOf(row("a"), row("b", "0"))
        val fill = chats.applyStartDates(rows) { ChatPreferences.StartDate.None }
        assertNull(rows[0]["created_at"])
        assertEquals("0", rows[1]["created_at"])
        assertFalse(fill.changed)
        assertFalse(fill.waiting)
    }
}
