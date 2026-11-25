package org.teslasoft.assistant.ui.chat

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.util.ProcessExitSnapshot.Activity

class ChatExitActivityTest {
    @Test fun voicePhasesAreClearedWhenTheyEndOrTheScreenCloses() {
        var reported = emptySet<Activity>()
        val state = ChatExitActivity { _, activities -> reported = activities }
        state.open()
        state.voice(true)
        state.recording(true)
        assertTrue(Activity.VOICE_LISTENING in reported)
        state.recording(false)
        state.transcribing(true)
        assertTrue(Activity.VOICE_TRANSCRIBING in reported)
        assertFalse(Activity.VOICE_LISTENING in reported)
        state.transcribing(false)
        val generation = state.beginGeneration()
        assertTrue(Activity.VOICE_GENERATING in reported)
        state.handsFreeReading(true)
        generation.close()
        assertTrue(Activity.VOICE_READING in reported)
        assertFalse(Activity.VOICE_GENERATING in reported)
        state.handsFreeReading(false)
        state.voice(false)
        assertEquals(setOf(Activity.CHAT), reported)
        state.close()
        state.recording(true) // Late callback after destruction must not resurrect the screen.
        assertTrue(reported.isEmpty())
    }

    @Test fun anOldGenerationFinishingCannotClearANewerGeneration() {
        var reported = emptySet<Activity>()
        val state = ChatExitActivity { _, activities -> reported = activities }
        val old = state.beginGeneration()
        val next = state.beginGeneration()
        old.close(); old.close()
        assertTrue(Activity.GENERATING in reported)
        next.close()
        assertEquals(setOf(Activity.CHAT), reported)
    }
}
