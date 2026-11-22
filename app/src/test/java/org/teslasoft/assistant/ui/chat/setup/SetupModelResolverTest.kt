package org.teslasoft.assistant.ui.chat.setup

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.conversation.setup.ExperimentalSetupTest.Companion.model
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

class SetupModelResolverTest {
    @Test fun changedEndpointAndKnownUnavailableModelsNeverSubstituteOrPromote() {
        val a = model("a")
        val b = model("b")
        val setup = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, models = listOf(a, b), primaryId = "b")
        fun endpoint(host: String = a.endpointHost, key: String = "test-key", path: String = a.chatPath,
            auth: String = a.authType) = ApiEndpointObject("Renamed profile", host, key, chatEndpoint = path,
                authType = auth, id = a.endpointId, identity = a.endpointIdentity)
        val good = endpoint()
        assertEquals(emptyList<Any>(), SetupModelResolver.unavailable(setup.copy(mode = ExperimentalSetup.Mode.OFF,
            models = listOf(a), primaryId = "a"), mapOf(a.endpointId to good), emptySet()))
        listOf(endpoint(host = "https://changed.invalid"), endpoint(key = ""),
            endpoint(path = "/different"), endpoint(auth = "x-api-key")).forEach { changed ->
            assertEquals(listOf(a, b), SetupModelResolver.unavailable(setup, mapOf(a.endpointId to changed), emptySet()))
        }
        assertEquals(listOf(a, b), SetupModelResolver.unavailable(setup, mapOf(a.endpointId to good), setOf(a.identity)))
        assertEquals("b", setup.primaryId)
        assertEquals(listOf(a, b), setup.models)
        // A changed active chat provider is deliberately absent from the resolver's inputs.
        assertFalse(a.settingsJson.contains("test-key"))
    }
}
