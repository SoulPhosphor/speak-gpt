package org.teslasoft.assistant.preferences.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdentityCodecTest {
    @Test
    fun roundTripPreservesExactEndpointAndModelIds() {
        val identities = listOf(
            ModelIdentity("openrouter", "openai/gpt-5.1"),
            ModelIdentity("deepseek", "gpt-5.1")
        )
        assertEquals(identities, ModelIdentityCodec.decode(ModelIdentityCodec.encode(identities)))
    }

    @Test
    fun malformedAndIncompleteEntriesAreIgnored() {
        assertTrue(ModelIdentityCodec.decode("not json").isEmpty())
        assertEquals(
            listOf(ModelIdentity("ep", "model")),
            ModelIdentityCodec.decode(
                """[{"endpoint_id":"ep","model_id":"model"},{"endpoint_id":"","model_id":"x"}]"""
            )
        )
    }

    @Test
    fun exactIdsAreNotTrimmedOrNormalized() {
        val exact = ModelIdentity("Endpoint-A", " provider/Model-X ")
        assertEquals(listOf(exact), ModelIdentityCodec.decode(ModelIdentityCodec.encode(listOf(exact))))
    }

    @Test
    fun legacyResolutionRequiresOneExactEndpointModelPair() {
        val known = listOf(
            ModelIdentity("openrouter", "glm-5"),
            ModelIdentity("deepseek", "glm-5"),
            ModelIdentity("openrouter", "glm-5-0502")
        )
        val result = LegacyModelTargetResolver.resolve(
            listOf("glm-5", "glm-5-0502", "glm-5-02"),
            known
        )

        assertEquals(
            listOf(ModelIdentity("openrouter", "glm-5-0502")),
            result.resolved
        )
        assertEquals(listOf("glm-5", "glm-5-02"), result.unresolved)
    }
}
