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
    @Test fun providerChangeReplacesOnlyThatDraftAssignmentAndKeepsItsIdAndOrder() {
        val a = model("a")
        val b = model("b")
        val draft = ParallelModelSelection(ExperimentalSetupCodec.encode(ExperimentalSetup(models = listOf(a, b))))
        assertTrue(draft.update(model("new-id", "profile-b").copy(endpointLabel = "Changed label")))
        assertEquals(listOf("a", "b"), draft.models.map { it.id })
        assertEquals(a, draft.models.first())
        assertEquals("Changed label", draft.models.last().endpointLabel)
    }

    @Test fun configurationEditsReachTheSelectedSnapshotWithoutReplacingRoutingOrIdentity() {
        val a = model("a")
        val b = model("b")
        val draft = ParallelModelSelection(ExperimentalSetupCodec.encode(ExperimentalSetup(models = listOf(a, b))))
        draft.editSettings(a.identity) { it.reasoningEffort = "low"; it.showReasoning = false }
        draft.editSettings(a.identity) { it.temperature = 0.9f; it.streaming = true }
        val actual = draft.models.first()
        assertEquals(a.id, actual.id)
        assertEquals(a.endpointHost, actual.endpointHost)
        assertEquals(listOf("a", "b"), draft.models.map { it.id })
        assertEquals(b, draft.models.last())
        assertEquals("provider-a", actual.settings().selectedProvider)
        assertEquals(listOf("one", "two"), actual.settings().providerOrder)
        assertEquals(listOf("three"), actual.settings().ignoredProviders)
        assertEquals("low", actual.settings().reasoningEffort)
        assertFalse(actual.settings().showReasoning)
        assertEquals(0.9f, actual.settings().temperature)
        assertTrue(actual.settings().streaming!!)
        draft.editSettings(a.identity) { model("changed").settings().copyRoutingTo(it) }
        assertEquals("provider-changed", draft.models.first().settings().selectedProvider)
        assertEquals(0.9f, draft.models.first().settings().temperature)
        assertEquals("low", draft.models.first().settings().reasoningEffort)
        assertEquals(draft.models, ParallelModelSelection(draft.raw()).models)
        draft.editSettings(model("unselected").identity) { fail("Must not edit or add an unselected model") }
        assertEquals(2, draft.models.size)
    }

}
