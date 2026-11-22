package org.teslasoft.assistant.ui.chat.setup

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupCodec
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupTest.Companion.model

class ParallelModelSelectionTest {
    @Test fun limitAndDeselectUseEndpointIdentityRatherThanDisplayName() {
        val draft = ParallelModelSelection(ExperimentalSetupCodec.encode(ExperimentalSetup()))
        (1..4).forEach { assertTrue(draft.toggle(model(it.toString()))) }
        val full = draft.raw()
        assertFalse(draft.toggle(model("5")))
        assertEquals(full, draft.raw())
        // A second picker visit mints another draft id, but toggles the same endpoint/model.
        assertTrue(draft.toggle(model("new-id", "profile-2")))
        assertEquals(listOf("1", "3", "4"), draft.models.map { it.id })
        assertTrue(draft.toggle(model("5")))
        assertEquals(listOf("1", "3", "4", "5"), draft.models.map { it.id })
        assertEquals(draft.models, ParallelModelSelection(draft.raw()).models)
    }

    @Test fun cancelledPickerCannotMutateTheOriginalSetupOrItsPrimary() {
        val setup = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, models = listOf(model("a"), model("b")), primaryId = "b")
        val raw = ExperimentalSetupCodec.encode(setup)
        val draft = ParallelModelSelection(raw)
        draft.toggle(model("fresh", "profile-b"))
        assertEquals(1, draft.models.size)
        assertEquals(setup, ExperimentalSetupCodec.decode(raw))
        assertEquals("b", setup.primaryId)
    }
}
