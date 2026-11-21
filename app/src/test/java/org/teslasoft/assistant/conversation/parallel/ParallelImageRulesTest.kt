package org.teslasoft.assistant.conversation.parallel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.imagegen.*
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import java.util.UUID

class ParallelImageRulesTest {
    private fun id() = UUID.randomUUID().toString()
    private fun owner(allow: Boolean = true): ParallelRequest {
        val assignment = ResponseModelAssignment("fixture", "Fixture", "endpoint", "https://fixture.invalid/", "chat_completions",
            emptyMap(), mapOf(ParallelChatSnapshot.ALLOW_IMAGES to allow.toString()), emptyMap(), emptyMap())
        return ParallelRequest(ResponseGroupTarget("chat", id(), id()), id(), id(), id(), assignment, emptyList(), emptyMap(),
            ProviderUsageAttempt("fixture", "Not Reported", assignment.endpoint))
    }
    private fun call() = JsonObject().apply {
        addProperty("role", "assistant"); addProperty("content", "")
        add("tool_calls", JsonArray().apply { add(JsonObject().apply {
            addProperty("id", "fixture-call"); addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", "create_image")
                addProperty("arguments", """{"prompt":"fixture image","description":"fixture description"}""") })
        }) })
    }.toString()
    private fun rules(enabled: Boolean = true, ask: Boolean = true) = ParallelImageRules(enabled, ask, "global-endpoint", "global-model",
        ImageShape.AUTOMATIC, ImageQuality.HIGH, mapOf("fixture-parameter" to "global-value"))

    @Test fun localOffAndGlobalOffNeverReserveOrGenerateAnImage() = runBlocking {
        val tool = ParallelImageTools({ rules(enabled = false) }, { _, _ -> error("No confirmation") }, { _, _, _, _ -> error("No image") })
        val result = tool.execute(owner(), call(), { error("No image identity") }, { _, _ -> error("No image receipt") })
        assertTrue(result.single().contains("not enabled"))
        val enabled = ParallelImageTools({ rules() }, { _, _ -> error("No confirmation") }, { _, _, _, _ -> error("No image") })
        val disabled = enabled.execute(owner(false), call(), { error("No identity") }, { _, _ -> error("No receipt") })
        assertTrue(disabled.single().contains("disabled for this model"))
    }

    @Test fun declinePreservesTheToolResultWithoutStartingAPaidGeneration() = runBlocking {
        var asked = 0
        val tool = ParallelImageTools({ rules() }, { _, _ -> asked++; false }, { _, _, _, _ -> error("No generation") })
        val result = tool.execute(owner(), call(), { error("No identity") }, { _, _ -> error("No receipt") })
        assertEquals(1, asked); assertTrue(result.single().contains("user declined"))
    }

    @Test fun confirmationOffStillUsesGlobalGeneratorAndParameters() = runBlocking {
        var generated = 0
        val tool = ParallelImageTools({ rules(ask = false) }, { _, _ -> error("Confirmation is globally off") }, { _, imageId, request, _ ->
            assertEquals("reserved-image", imageId); assertEquals("global-endpoint", request.endpointId)
            assertEquals("global-model", request.modelId); assertEquals(ImageQuality.HIGH, request.defaultQuality)
            assertEquals(mapOf("fixture-parameter" to "global-value"), request.parameters)
            generated++; CreateImageTool.successResult(imageId, request.description!!)
        })
        val result = tool.execute(owner(), call(), { "reserved-image" }, { _, _ -> })
        assertEquals(1, generated); assertTrue(result.single().contains("success"))
    }

    @Test fun imageFailureDoesNotChangeAnotherOwnersConsentOrResult() = runBlocking {
        val tool = ParallelImageTools({ rules(ask = false) }, { _, _ -> true }, { request, _, _, _ ->
            if (request.slotId == "failed-slot") throw IllegalStateException("fixture image failure")
            CreateImageTool.successResult("fixture-image", "fixture")
        })
        val one = owner().copy(slotId = "failed-slot")
        val two = owner()
        val failed = async { tool.execute(one, call(), { id() }, { _, _ -> }) }
        val success = async { tool.execute(two, call(), { id() }, { _, _ -> }) }
        assertTrue(failed.await().single().contains("generation failed"))
        assertTrue(success.await().single().contains("fixture-image"))
    }
}
