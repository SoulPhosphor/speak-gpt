package org.teslasoft.assistant.conversation.setup

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

class ExperimentalSetupTest {
    companion object {
        fun model(id: String, endpoint: String = "profile-$id", name: String = "model") = SetupModel(
            id, endpoint, name, "https://example.invalid/$endpoint/", "/chat/completions", "bearer", "generic",
            Gson().toJson(FavoriteModelObject(name, endpoint, routingType = "only", selectedProvider = "provider-$id",
                providerOrder = listOf("one", "two"), ignoredProviders = listOf("three"),
                reasoningEffort = "high", streaming = false, temperature = 0.33f))
        )
    }

    @Test fun legacyMappingDoesNotWriteOrDropContextChoices() {
        assertEquals(ExperimentalSetup.Mode.OFF, ExperimentalSetup.legacy(false).mode)
        assertEquals(ExperimentalSetup.Mode.SINGLE, ExperimentalSetup.legacy(true).mode)
        assertTrue(ExperimentalSetup.legacy(true).usesLastMessageOnly)
        assertFalse(ExperimentalSetup.legacy(false).usesLastMessageOnly)
        assertFalse(ExperimentalSetup.legacy(true).copy(lastMessageOnly = false).usesLastMessageOnly)
    }

    @Test fun sameModelNameAcrossEndpointsRetainsOrderRoutingAndExplicitPrimary() {
        val expected = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, false,
            listOf(model("b"), model("a")), "a", false)
        val actual = ExperimentalSetupCodec.decode(ExperimentalSetupCodec.encode(expected))
        assertEquals(expected, actual)
        assertEquals(listOf("profile-b", "profile-a"), actual.models.map { it.endpointId })
        assertEquals("provider-b", actual.models.first().settings().selectedProvider)
        assertEquals(listOf("one", "two"), actual.models.first().settings().providerOrder)
        assertEquals(listOf("three"), actual.models.first().settings().ignoredProviders)
        assertEquals("high", actual.models.first().settings().reasoningEffort)
        assertEquals(0.33f, actual.models.first().settings().temperature)
        assertFalse(actual.models.first().settings().streaming!!)
    }

    @Test fun invalidSelectionsCannotBecomeProductionDefaults() {
        val a = model("a")
        val invalid = listOf(
            ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL),
            ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, models = listOf(a), primaryId = a.id),
            ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, models = listOf(a, model("b"))),
            ExperimentalSetup(models = listOf(a, a.copy(id = "b"))),
            ExperimentalSetup(models = listOf(a, model("b").copy(id = "a"))),
            ExperimentalSetup(models = listOf(a), primaryId = "missing"),
            ExperimentalSetup(models = (1..5).map { model(it.toString()) }),
            ExperimentalSetup(models = listOf(a.copy(endpointHost = ""))),
            ExperimentalSetup(models = listOf(a.copy(modelId = "different")))
        )
        invalid.forEach { setup -> assertTrue(setup.toString(), runCatching { ExperimentalSetupCodec.encode(setup) }.isFailure) }
    }

    @Test fun malformedAndUnknownSettingsCannotMasqueradeAsOff() {
        val raw = ExperimentalSetupCodec.encode(ExperimentalSetup())
        listOf("", "{", "[]", raw.replace("\"schema\":1", "\"schema\":2"),
            raw.replace("\"OFF\"", "\"FUTURE\""), raw.replace("\"lastMessageOnly\":true", "\"lastMessageOnly\":\"true\""),
            raw.replace("\"models\":[]", "\"models\":[null]"))
            .forEach { assertTrue(it, runCatching { ExperimentalSetupCodec.decode(it) }.isFailure) }
    }
    @Test fun removingADeadPrimaryRequiresExplicitReplacementAndRetainsOtherChoices() {
        val setup = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, models = listOf(model("a"), model("b"), model("c")), primaryId = "b")
        val remaining = setup.withoutModel("b")
        assertNull(remaining.primaryId)
        assertEquals(listOf("a", "c"), remaining.models.map { it.id })
        assertTrue(runCatching { remaining.validate() }.isFailure)
        remaining.copy(primaryId = "c").validate()
        assertEquals("b", setup.primaryId)
    }

}
