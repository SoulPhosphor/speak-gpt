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

    @Test fun earlierContextIsWholeMessagesJustBeforeTheSectionUntilTheAverage() {
        val chat = listOf(
            msg("m1", 1, "too old to be needed"),
            msg("m2", 2, "hidden", eligible = false),
            msg("m4", 4, "I'm waiting for their response."),
            msg("m5", 5, "Let me know."),
            msg("m6", 6, "They responded yesterday.")
        )
        val first = row(chat[4], 0, chat[4].text.length)
        val earlier = ScanContext.earlierExcerpts(
            chat, first, { ScanSourceRows.GENERAL }, average = 40, maximum = 80, estimate = chars
        )
        // 12 + 31 = 43 reaches the average of 40, so nothing older is added;
        // the hidden message is never context.
        assertEquals(listOf("m4", "m5"), earlier.map { it.messageId })
        assertEquals("I'm waiting for their response.", earlier.first().text)
        assertEquals(0, earlier.first().start)
    }

    @Test fun theAverageCanBeExceededToFinishAMessageButNeverTheMaximum() {
        val chat = listOf(
            msg("m1", 1, "x".repeat(30)),
            msg("m2", 2, "y".repeat(25)),
            msg("m3", 3, "section")
        )
        val first = row(chat[2], 0, 7)
        val finished = ScanContext.earlierExcerpts(
            chat, first, { ScanSourceRows.GENERAL }, average = 30, maximum = 60, estimate = chars
        )
        // 25 is under the average; the 30-character message is added whole
        // (55 total), past the average and within the maximum.
        assertEquals(listOf("m1", "m2"), finished.map { it.messageId })
        assertEquals(55, finished.sumOf { it.text.length })

        val capped = ScanContext.earlierExcerpts(
            chat, first, { ScanSourceRows.GENERAL }, average = 30, maximum = 40, estimate = chars
        )
        assertTrue(capped.sumOf { it.text.length } <= 40)
        assertEquals("m2", capped.last().messageId)
    }

    @Test fun aMessageIsCutOnlyWhenItCannotFitUnderTheMaximumAndKeepsItsEnd() {
        val chat = listOf(
            msg("m0", 0, "never reached"),
            msg("m1", 1, "the beginning of a long story that ends with she finally did it"),
            msg("m2", 2, "section")
        )
        val earlier = ScanContext.earlierExcerpts(
            chat, row(chat[2], 0, 7), { ScanSourceRows.GENERAL }, average = 10, maximum = 30, estimate = chars
        )
        val cut = earlier.single()
        assertEquals("m1", cut.messageId)
        assertTrue(chat[1].text.endsWith(cut.text))
        assertTrue(cut.text.length <= 30)
        // The cut falls after a space, never inside a word.
        assertTrue(chat[1].text[cut.start - 1].isWhitespace())
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
