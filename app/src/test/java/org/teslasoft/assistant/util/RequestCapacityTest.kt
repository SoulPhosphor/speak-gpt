package org.teslasoft.assistant.util

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCapacityTest {

    private fun payload(
        content: String = "hello",
        message: FrozenPayloadMessage = FrozenPayloadMessage("user", content)
    ) = FrozenChatPayload(
        model = "exact-model-id",
        messages = listOf(message),
        maximumResponseTokens = 1500,
        temperature = null,
        topP = null,
        frequencyPenalty = null,
        presencePenalty = null,
        seed = null,
        logitBias = null
    )

    @Test
    fun `measurement counts escaped JSON and UTF8 without building it`() {
        val measured = RequestCapacity.measure(payload("line one\n漢😀"))
        val expected =
            """{"model":"exact-model-id","max_tokens":1500,"messages":[{"role":"user","content":"line one\n漢😀"}],"stream":true}"""
        assertEquals(expected.length.toLong(), measured.requestCharacters)
        assertEquals(
            expected.toByteArray(Charsets.UTF_8).size.toLong(),
            measured.serializedUtf8Bytes
        )
    }

    @Test
    fun `multimodal message freezes without using text-only content accessor`() {
        val message = ChatMessage(
            role = ChatRole.User,
            content = listOf(
                TextPart("what is this?"),
                ImagePart("data:image/png;base64,YWJj", detail = "low")
            )
        )

        assertEquals(
            FrozenPayloadMessage(
                role = "user",
                content = "",
                parts = listOf(
                    FrozenTextPayloadPart("what is this?"),
                    FrozenImagePayloadPart("data:image/png;base64,YWJj", "low")
                )
            ),
            RequestMessageSnapshot.freeze("user", message)
        )
    }

    @Test
    fun `measurement counts multimodal content as structured JSON`() {
        val message = FrozenPayloadMessage(
            role = "user",
            content = "",
            parts = listOf(
                FrozenTextPayloadPart("look"),
                FrozenImagePayloadPart("data:image/png;base64,YWJj", "low")
            )
        )
        val measured = RequestCapacity.measure(payload(message = message))
        val expected =
            """{"model":"exact-model-id","max_tokens":1500,"messages":[{"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/png;base64,YWJj","detail":"low"}}]}],"stream":true}"""

        assertEquals(expected.length.toLong(), measured.requestCharacters)
        assertEquals(
            expected.toByteArray(Charsets.UTF_8).size.toLong(),
            measured.serializedUtf8Bytes
        )
    }

    @Test
    fun `request heap rule includes both character copies bytes overhead and reserve`() {
        val measurement = SerializedRequestMeasurement(
            requestCharacters = 2L * 1024L * 1024L,
            serializedUtf8Bytes = 2L * 1024L * 1024L
        )
        assertTrue(
            RequestCapacity.canAssemble(
                measurement,
                RequestHeapState(
                    heapLimit = 128L * 1024L * 1024L,
                    heapUsed = 20L * 1024L * 1024L
                )
            )
        )
        assertFalse(
            RequestCapacity.canAssemble(
                measurement,
                RequestHeapState(
                    heapLimit = 64L * 1024L * 1024L,
                    heapUsed = 28L * 1024L * 1024L
                )
            )
        )
    }

    @Test
    fun `exact overflow blocks`() {
        assertEquals(
            ModelContextDecision.Block(9_500, 8_000),
            ModelContextCapacity.decide(8_000, TokenMeasurement.Exact(8_000), 1_500)
        )
    }

    @Test
    fun `range wholly below sends and wholly above blocks`() {
        assertEquals(
            ModelContextDecision.Send,
            ModelContextCapacity.decide(10_000, TokenMeasurement.Range(4_000, 8_000), 1_000)
        )
        assertEquals(
            ModelContextDecision.Block(11_000, 10_000),
            ModelContextCapacity.decide(10_000, TokenMeasurement.Range(10_000, 12_000), 1_000)
        )
    }

    @Test
    fun `range crossing the limit warns`() {
        assertEquals(
            ModelContextDecision.WarnRange(9_000, 11_000, 10_000),
            ModelContextCapacity.decide(10_000, TokenMeasurement.Range(8_000, 10_000), 1_000)
        )
    }

    @Test
    fun `approximate overflow warns but never hard blocks`() {
        assertEquals(
            ModelContextDecision.WarnApproximate(11_000, 10_000),
            ModelContextCapacity.decide(
                10_000,
                TokenMeasurement.Approximate(9_500),
                1_500
            )
        )
    }

    @Test
    fun `unknown context and unknown tokenization do not warn`() {
        assertEquals(
            ModelContextDecision.Send,
            ModelContextCapacity.decide(null, TokenMeasurement.Approximate(500_000), 1_500)
        )
        assertEquals(
            ModelContextDecision.Send,
            ModelContextCapacity.decide(8_000, TokenMeasurement.Unknown, 1_500)
        )
    }
}
