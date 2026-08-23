package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCommandTest {
    @Test fun commandAloneDoesNotProduceAChatMessage() {
        assertTrue(CompactCommand.parse("/compact") is CompactCommand.Parse.CompactOnly)
        assertTrue(CompactCommand.parse("  /COMPACT   ") is CompactCommand.Parse.CompactOnly)
    }

    @Test fun commandPrefixIsRemovedFromTextSentToChatModel() {
        assertEquals(
            CompactCommand.Parse.CompactAndSend("Continue from here."),
            CompactCommand.parse("/compact   Continue from here.")
        )
    }

    @Test fun longerSlashWordsRemainOrdinaryMessages() {
        assertTrue(CompactCommand.parse("/compactor hello") is CompactCommand.Parse.NotCompact)
    }
}
