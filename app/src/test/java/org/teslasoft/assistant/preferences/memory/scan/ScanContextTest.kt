/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanContextTest {

    private val chars: (String) -> Int = { it.length }

    private fun msg(id: String, number: Long, text: String, eligible: Boolean = true) = ScanMessage(
        id = id, number = number, role = "user", text = text, eligible = eligible, scope = null,
        timeMillis = null, model = null, fingerprint = text
    )

    private fun row(message: ScanMessage, start: Int, end: Int) = ScanSourceRow(
        chatId = "c", messageId = message.id, messageNumber = message.number, role = message.role,
        start = start, end = end, text = message.text.substring(start, end), startedAt = null, modelTag = null
    )

    @Test fun partsAreNumberedAcrossTheWholeMessageWhereverASectionStarts() {
        val long = msg("m12", 12, "aaaa bbbb cccc dddd eeee ")
        val pieces = ScanTextSplitter.split(long.text, 10, chars)
        assertTrue(pieces.size > 2)
        // A later section that starts at the second piece still says "part 2".
        val resumed = ScanContext.labelParts(
            listOf(row(long, pieces[1].start, pieces[1].end)), { long.text }, 10, chars
        ).single()
        assertEquals(2, resumed.partIndex)
        assertEquals(pieces.size, resumed.partCount)
        assertEquals(12L, resumed.messageNumber)
    }

    @Test fun aMessageThatFitsHasNoPartMarker() {
        val short = msg("m1", 1, "short")
        val labeled = ScanContext.labelParts(listOf(row(short, 0, 5)), { short.text }, 100, chars).single()
        assertEquals(1, labeled.partCount)
    }

    @Test fun earlierContextIsTheMessagesJustBeforeTheSectionWithinItsAllowance() {
        val chat = listOf(
            msg("m1", 1, "old " + "x".repeat(50)),
            msg("m2", 2, "hidden", eligible = false),
            msg("m4", 4, "I'm waiting for their response."),
            msg("m6", 6, "They responded yesterday.")
        )
        val first = row(chat[3], 0, chat[3].text.length)
        val earlier = ScanContext.earlierExcerpts(chat, first, { ScanSourceRows.GENERAL }, perMessage = 40, total = 60, estimate = chars)
        assertEquals(listOf("m1", "m4"), earlier.map { it.messageId })
        assertEquals("I'm waiting for their response.", earlier.last().text)
        assertTrue(earlier.sumOf { it.text.length } <= 60)
        // The older message is cut to its tail, after a space.
        assertTrue(chat[0].text.endsWith(earlier.first().text))
    }

    @Test fun aSectionStartingInsideAMessageGetsThatMessagesEarlierText() {
        val long = msg("m9", 9, "first half of it second half of it")
        val first = row(long, 17, long.text.length)
        val earlier = ScanContext.earlierExcerpts(listOf(long), first, { ScanSourceRows.GENERAL }, estimate = chars)
        assertEquals("first half of it ", earlier.single().text)
        assertEquals(9L, earlier.single().messageNumber)
    }

    @Test fun aCompanionWithParticipationNoneIsNotUsedAsContext() {
        val chat = listOf(msg("m1", 1, "excluded"), msg("m2", 2, "section"))
        val earlier = ScanContext.earlierExcerpts(
            chat, row(chat[1], 0, 7), { ScanScopeResolution.Excluded }, estimate = chars
        )
        assertTrue(earlier.isEmpty())
    }
}
