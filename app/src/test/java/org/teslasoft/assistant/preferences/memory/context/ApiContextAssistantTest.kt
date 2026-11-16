/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.context

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.librarian.LiveRetrieval
import org.teslasoft.assistant.preferences.memory.librarian.LongMessageSearch
import org.teslasoft.assistant.preferences.memory.librarian.SavedMemorySearch
import java.net.UnknownHostException

/** The API AI context helper with a stand-in provider (Step 4b; M.4). */
class ApiContextAssistantTest {

    private class StandIn(
        var window: Int? = null,
        var unavailable: Boolean = false,
        var reply: suspend (ApiContextAssistant.HelperRequest) -> String = { "Ada's birthday party plans" }
    ) : ApiContextAssistant.HelperTransport {
        val requests = ArrayList<ApiContextAssistant.HelperRequest>()
        val failures = ArrayList<Pair<ApiContextAssistant.Failure, List<String>>>()

        override fun contextWindowTokens(): Int? {
            if (unavailable) throw ApiContextAssistant.HelperUnavailable("no model")
            return window
        }

        override suspend fun complete(request: ApiContextAssistant.HelperRequest): String {
            requests.add(request)
            return reply(request)
        }

        override fun recordFailure(
            failure: ApiContextAssistant.Failure, error: Throwable?, privateValues: List<String>
        ) {
            failures.add(failure to privateValues)
        }
    }

    private val emptySearch = SavedMemorySearch.Result(
        LiveRetrieval.Result(emptyList(), emptyList(), LiveRetrieval.Diagnostics(0, 0, true, 0)),
        LongMessageSearch.Reading.NONE, emptyList(), emptyList(), emptySet()
    )

    private fun turns(exchanges: Int): List<ContextAssistant.Turn> = (1..exchanges).flatMap {
        listOf(ContextAssistant.Turn(true, "question $it"), ContextAssistant.Turn(false, "answer $it"))
    }

    private fun input(
        conversation: List<ContextAssistant.Turn> = turns(6),
        message: String = "What did she want for it?",
        handsFree: Boolean = false,
        fits: (String) -> Boolean = { true }
    ) = ContextAssistant.Input(
        chatId = "chat", modelTag = "model", own = emptySearch, eligible = emptyMap(),
        earlierUserMessages = emptyList(), searchEarlier = { null },
        message = message, conversation = conversation, handsFree = handsFree, queryFits = fits
    )

    private fun helper(
        transport: StandIn,
        past: Int = 4,
        text: Long = ApiContextAssistant.TEXT_ALLOWANCE_MS,
        handsFree: Long = ApiContextAssistant.HANDS_FREE_ALLOWANCE_MS
    ) = ApiContextAssistant(transport, { past }, text, handsFree)

    @Test
    fun validQueryIsReturnedAsTheSearchQuery() {
        val transport = StandIn()
        val result = helper(transport).assist(input())
        assertEquals(ContextAssistant.Output.Query("Ada's birthday party plans"), result.output)
        assertNull(result.failure)
        assertTrue(transport.failures.isEmpty())
    }

    @Test
    fun requestIsOrderedForPromptCaching() {
        val transport = StandIn()
        helper(transport).assist(input(conversation = turns(2)))
        val request = transport.requests.single()
        assertEquals(HelperPrompt.INSTRUCTIONS, request.instructions)
        assertEquals(turns(2), request.earlier)
        assertEquals(HelperPrompt.newest("What did she want for it?"), request.newest)
        assertEquals(ApiContextAssistant.CACHE_KEY_PREFIX + "chat", request.cacheKey)
    }

    @Test
    fun strictSlidingWindowSendsOnlyTheNewestFourExchanges() {
        val transport = StandIn()
        helper(transport).assist(input(conversation = turns(6)))
        val sent = transport.requests.single().earlier
        assertEquals(turns(6).drop(4), sent)
        assertFalse(sent.any { it.text == "question 2" })
    }

    @Test
    fun numberOfPastMessagesIsHonored() {
        val transport = StandIn()
        helper(transport, past = 1).assist(input(conversation = turns(3)))
        assertEquals(turns(3).takeLast(2), transport.requests.single().earlier)
        helper(transport, past = 0).assist(input(conversation = turns(3)))
        assertTrue(transport.requests.last().earlier.isEmpty())
    }

    @Test
    fun unknownContextWindowSendsEveryRequestedExchangeWithoutATokenCap() {
        val long = "word ".repeat(4_000)
        val conversation = (1..4).flatMap {
            listOf(ContextAssistant.Turn(true, "$it $long"), ContextAssistant.Turn(false, "reply $it"))
        }
        val transport = StandIn(window = null)
        helper(transport).assist(input(conversation = conversation))
        assertEquals(conversation, transport.requests.single().earlier)
    }

    @Test
    fun knownContextWindowLeavesOutTheOldestWholeExchanges() {
        val long = "word ".repeat(1_000)
        val conversation = (1..4).flatMap {
            listOf(ContextAssistant.Turn(true, "$it $long"), ContextAssistant.Turn(false, "reply $it"))
        }
        val exchanges = HelperConversation.exchanges(conversation)
        val twoFit = HelperConversation.estimatedTokens(exchanges.takeLast(2), "m")
        val transport = StandIn(window = twoFit)
        helper(transport).assist(input(conversation = conversation, message = "m"))
        assertEquals(exchanges.takeLast(2).flatten(), transport.requests.single().earlier)
    }

    @Test
    fun exchangesKeepAUserMessageWithItsReplies() {
        val conversation = listOf(
            ContextAssistant.Turn(false, "greeting"),
            ContextAssistant.Turn(true, "q1"),
            ContextAssistant.Turn(false, "a1"),
            ContextAssistant.Turn(false, "a1 continued"),
            ContextAssistant.Turn(true, "q2")
        )
        assertEquals(
            listOf(listOf("greeting"), listOf("q1", "a1", "a1 continued"), listOf("q2")),
            HelperConversation.exchanges(conversation).map { e -> e.map { it.text } }
        )
    }

    @Test
    fun discardedOutputSendsNoHelper() {
        for (reply in listOf("", "   ", "line one\nline two")) {
            val transport = StandIn(reply = { reply })
            val result = helper(transport).assist(input())
            assertEquals(ContextAssistant.Output.Nothing, result.output)
            assertEquals(ApiContextAssistant.Failure.INVALID_OUTPUT, result.failure)
        }
        val tooLong = StandIn()
        val result = helper(tooLong).assist(input(fits = { false }))
        assertEquals(ApiContextAssistant.Failure.INVALID_OUTPUT, result.failure)
    }

    @Test
    fun timeoutSendsNoHelperWithinTheAllowance() {
        val transport = StandIn(reply = { delay(10_000); "late" })
        val started = System.nanoTime()
        val result = helper(transport, text = 100, handsFree = 50).assist(input())
        assertEquals(ContextAssistant.Output.Nothing, result.output)
        assertEquals(ApiContextAssistant.Failure.TIMED_OUT, result.failure)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun handsFreeUsesItsShorterAllowance() {
        val transport = StandIn(reply = { delay(200); "query" })
        val voice = helper(transport, text = 5_000, handsFree = 50).assist(input(handsFree = true))
        assertEquals(ApiContextAssistant.Failure.TIMED_OUT, voice.failure)
        val typed = helper(transport, text = 5_000, handsFree = 50).assist(input(handsFree = false))
        assertEquals(ContextAssistant.Output.Query("query"), typed.output)
    }

    @Test
    fun allowancesAreTheApprovedDefaults() {
        assertEquals(8_000L, ApiContextAssistant.TEXT_ALLOWANCE_MS)
        assertEquals(3_000L, ApiContextAssistant.HANDS_FREE_ALLOWANCE_MS)
    }

    @Test
    fun providerErrorAndNoNetworkSendNoHelperAndNeverRetry() {
        for (error in listOf(IllegalStateException("HTTP 500"), UnknownHostException("no network"))) {
            val transport = StandIn(reply = { throw error })
            val result = helper(transport).assist(input())
            assertEquals(ContextAssistant.Output.Nothing, result.output)
            assertEquals(ApiContextAssistant.Failure.REQUEST_FAILED, result.failure)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun noModelMakesNoRequest() {
        val transport = StandIn(unavailable = true)
        val result = helper(transport).assist(input())
        assertEquals(ApiContextAssistant.Failure.NO_MODEL, result.failure)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun failureRecordsNameConversationTextAsPrivate() {
        val transport = StandIn(reply = { throw IllegalStateException("boom") })
        helper(transport).assist(input(conversation = turns(1), message = "secret message"))
        val private = transport.failures.single().second
        assertTrue("secret message" in private)
        assertTrue("question 1" in private && "answer 1" in private)
    }

    @Test
    fun turnsBeforeKeepsUserAndAiTextOnlyAndLeavesOutTheNewestMessage() {
        val request = listOf(
            ChatMessage(role = ChatRole.System, content = "system"),
            ChatMessage(role = ChatRole.User, content = "q1"),
            ChatMessage(role = ChatRole.Assistant, content = "a1"),
            ChatMessage(role = ChatRole.User, content = "  "),
            ChatMessage(role = ChatRole.User, content = "newest")
        )
        assertEquals(
            listOf(ContextAssistant.Turn(true, "q1"), ContextAssistant.Turn(false, "a1")),
            HelperConversation.turnsBefore(request)
        )
    }

    @Test
    fun outputCheckTrimsAndAcceptsOneLine() {
        assertEquals("query", HelperOutput.check("  query \n", { true }))
        assertNull(HelperOutput.check(null, { true }))
    }

    @Test
    fun noticeIsShownOnceWhileAFailureContinues() {
        ContextHelperNotice.reset()
        val first = ContextHelperNotice.outcome(ApiContextAssistant.Failure.TIMED_OUT)
        assertTrue(first != null)
        assertNull(ContextHelperNotice.outcome(ApiContextAssistant.Failure.REQUEST_FAILED))
        assertNull(ContextHelperNotice.outcome(null))
        assertTrue(ContextHelperNotice.outcome(ApiContextAssistant.Failure.NO_MODEL) != null)
        ContextHelperNotice.reset()
    }
}
