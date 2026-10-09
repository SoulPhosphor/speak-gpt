package org.teslasoft.assistant.stt.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttVocabularyTest {
    @Test fun addsTrimmedEntriesNewestLast() {
        val first = SttVocabulary.add(emptyList(), "  Seket ") as SttVocabulary.AddResult.Added
        val second = SttVocabulary.add(first.entries, "Phosphor Shines") as SttVocabulary.AddResult.Added
        assertEquals(listOf("Seket", "Phosphor Shines"), second.entries)
        assertEquals("Seket, Phosphor Shines", SttVocabulary.prompt(second.entries))
    }

    @Test fun rejectsBlankAndCaseInsensitiveDuplicates() {
        assertEquals(SttVocabulary.AddResult.Blank, SttVocabulary.add(listOf("a"), "   "))
        assertEquals(SttVocabulary.AddResult.Duplicate, SttVocabulary.add(listOf("Seket"), "seket"))
    }

    @Test fun capsAtSeventyFiveEntries() {
        val full = (1..SttVocabulary.MAX_ENTRIES).map { "word$it" }
        assertEquals(75, SttVocabulary.MAX_ENTRIES)
        assertTrue(SttVocabulary.isFull(full))
        assertEquals(SttVocabulary.AddResult.Full, SttVocabulary.add(full, "another"))
        assertTrue(SttVocabulary.add(full.dropLast(1), "another") is SttVocabulary.AddResult.Added)
    }

    @Test fun emptyListSendsNoHint() {
        assertNull(SttVocabulary.prompt(emptyList()))
    }
}
