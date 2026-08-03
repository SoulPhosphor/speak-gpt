package org.teslasoft.assistant.util

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleChatRequestConfigTest {

    @Test
    fun `endpoint profile 8000 reaches outgoing max_tokens field`() {
        val configuredMaximum = VisibleChatRequestConfig.maximumResponseTokens(
            endpointProfileValue = 8_000,
            perChatValue = 1_500
        )
        val request = ChatCompletionRequest(
            model = ModelId("visible-model"),
            maxTokens = configuredMaximum,
            messages = listOf(ChatMessage(ChatRole.User, "hello"))
        )

        val outgoingJson = JSONObject(Json.encodeToString(request))

        assertEquals(8_000, outgoingJson.getInt("max_tokens"))
    }

    @Test
    fun `invalid profile and chat values use 8000 default`() {
        assertEquals(
            8_000,
            VisibleChatRequestConfig.maximumResponseTokens(0, -1)
        )
    }
}
