package org.teslasoft.assistant.providers

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCachingRequestSerializerTest {

    @Test
    fun singleMessageRequestGetsCacheKeyWithoutChangingContentShape() {
        val body = """{"model":"qwen/test","messages":[{"role":"user","content":"Hello"}]}"""

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(body, "chat-123")
        ).asJsonObject

        assertEquals("chat-123", root.get("prompt_cache_key").asString)
        assertTrue(root.getAsJsonArray("messages")[0].asJsonObject.get("content").isJsonPrimitive)
    }

    @Test
    fun marksTheLastMessageBeforeTheNewestMessage() {
        val body = """{
            "model":"qwen/test",
            "messages":[
                {"role":"system","content":"Instructions"},
                {"role":"user","content":"First question"},
                {"role":"assistant","content":"First answer"},
                {"role":"user","content":"Next question"}
            ]
        }""".trimIndent()

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(
                body, "chat-123", addExplicitBreakpoint = true
            )
        ).asJsonObject
        val messages = root.getAsJsonArray("messages")
        val markedContent = messages[2].asJsonObject.getAsJsonArray("content")
        val markedBlock = markedContent[0].asJsonObject

        assertEquals("First answer", markedBlock.get("text").asString)
        assertEquals(
            "ephemeral",
            markedBlock.getAsJsonObject("cache_control").get("type").asString
        )
        assertEquals("chat-123", root.get("prompt_cache_key").asString)
        assertTrue(messages[3].asJsonObject.get("content").isJsonPrimitive)
        assertFalse(messages[1].asJsonObject.get("content").isJsonArray)
    }

    @Test
    fun marksLastTextBlockAndPreservesOtherContentBlocks() {
        val body = """{
            "model":"qwen/test",
            "messages":[
                {"role":"user","content":[
                    {"type":"text","text":"Describe this"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}
                ]},
                {"role":"assistant","content":"Description"},
                {"role":"user","content":"Continue"}
            ]
        }""".trimIndent()

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(
                body, addExplicitBreakpoint = true
            )
        ).asJsonObject
        val messages = root.getAsJsonArray("messages")
        val marked = messages[1].asJsonObject.getAsJsonArray("content")[0].asJsonObject
        val earlierBlocks = messages[0].asJsonObject.getAsJsonArray("content")

        assertEquals("Description", marked.get("text").asString)
        assertEquals(2, earlierBlocks.size())
        assertEquals("image_url", earlierBlocks[1].asJsonObject.get("type").asString)
    }

    @Test
    fun skipsNonTextToolMessageAndMarksEarlierText() {
        val body = """{
            "model":"qwen/test",
            "messages":[
                {"role":"user","content":"Use the tool"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"1"}]},
                {"role":"tool","content":"Tool result"}
            ]
        }""".trimIndent()

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(
                body, addExplicitBreakpoint = true
            )
        ).asJsonObject
        val messages = root.getAsJsonArray("messages")
        val marked = messages[0].asJsonObject.getAsJsonArray("content")[0].asJsonObject

        assertEquals("Use the tool", marked.get("text").asString)
        assertTrue(messages[1].asJsonObject.get("content").isJsonNull)
    }

    @Test
    fun addingTheBreakpointTwiceIsIdempotent() {
        val body = """{
            "model":"qwen/test",
            "messages":[
                {"role":"system","content":"Instructions"},
                {"role":"user","content":"Hello"}
            ]
        }""".trimIndent()

        val once = PromptCachingRequestSerializer.augmentOpenRouterChatBody(
            body, addExplicitBreakpoint = true
        )
        val twice = PromptCachingRequestSerializer.augmentOpenRouterChatBody(
            once, addExplicitBreakpoint = true
        )

        assertEquals(JsonParser.parseString(once), JsonParser.parseString(twice))
    }

    @Test
    fun malformedBodyIsUnchanged() {
        assertEquals(
            "not json",
            PromptCachingRequestSerializer.augmentOpenRouterChatBody("not json")
        )
    }

    @Test
    fun cacheKeyIsLimitedToOpenRouterMaximumLength() {
        val body = """{
            "model":"qwen/test",
            "messages":[
                {"role":"system","content":"Instructions"},
                {"role":"user","content":"Hello"}
            ]
        }""".trimIndent()

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(body, "x".repeat(300))
        ).asJsonObject

        assertEquals(256, root.get("prompt_cache_key").asString.length)
    }

    @Test
    fun cacheKeyDoesNotRequireExplicitBreakpoint() {
        val body = """{
            "model":"openai/test",
            "messages":[
                {"role":"assistant","content":"Earlier answer"},
                {"role":"user","content":"Continue"}
            ]
        }""".trimIndent()

        val root = JsonParser.parseString(
            PromptCachingRequestSerializer.augmentOpenRouterChatBody(body, "chat-123")
        ).asJsonObject

        assertEquals("chat-123", root.get("prompt_cache_key").asString)
        assertTrue(root.getAsJsonArray("messages")[0].asJsonObject.get("content").isJsonPrimitive)
    }
}
