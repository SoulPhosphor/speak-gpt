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
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/**
 * API AI context assistance (conversation_recall_plan.md G.9b, M.4): a model
 * the user chose on Memory Context Helper Tool API reads the new message and
 * the recent exchanges of the same chat and writes one self-contained search
 * query. The query is searched in addition to the message; it never answers
 * the user and never changes the message or the chat request.
 *
 * Only the helper's own model is used ([HelperTransport]); nothing here
 * falls back to the chat's or any other job's model. Any failure, timeout or
 * unusable output means no helper this turn: the message is sent with its
 * own results, and the failure is reported through [Assistance.failure].
 */
class ApiContextAssistant(
    private val transport: HelperTransport,
    /** Number of Past Messages to Send (M.4 item 1). */
    private val pastExchanges: () -> Int,
    private val textAllowanceMs: Long = TEXT_ALLOWANCE_MS,
    private val handsFreeAllowanceMs: Long = HANDS_FREE_ALLOWANCE_MS
) : ContextAssistant {

    /** The helper's own model and the request it is sent. */
    interface HelperTransport {
        /** The selected model's context window in tokens, or null when the
         *  app has none for it. Throws [HelperUnavailable] when no usable
         *  model is set. */
        fun contextWindowTokens(): Int?

        /** Sends [request] to the helper's own model and returns its reply
         *  text. Throws on any provider or network failure. */
        suspend fun complete(request: HelperRequest): String

        /** The API profile's name and the model, for a failure report. */
        fun profileAndModel(): Pair<String?, String?> = null to null

        /** Describes a failed [complete] from the error and the provider's
         *  response, sanitized of [privateValues] and credentials. */
        fun describe(error: Throwable, privateValues: List<String>): HelperFailure {
            val (profile, model) = profileAndModel()
            return HelperFailure(HelperFailure.Category.UNEXPECTED, profile, model)
        }
    }

    /** No model is set on Memory Context Helper Tool API, or it can no
     *  longer be used. */
    class HelperUnavailable(val reason: HelperFailure.Local) : Exception(reason.toString())

    /**
     * One helper request, ordered so a provider's prompt cache can reuse its
     * start: the fixed instructions, then each earlier message in order as
     * its own chat message, then the newest message last. Nothing that
     * changes every turn comes before the earlier messages.
     */
    data class HelperRequest(
        val instructions: String,
        val earlier: List<ContextAssistant.Turn>,
        val newest: String,
        /** A stable key for this chat's helper requests, so a router can send
         *  them where an earlier one may already be cached. */
        val cacheKey: String?,
        /** The chat the request belongs to, for its usage log. */
        val chatId: String? = null
    )

    override fun assist(input: ContextAssistant.Input): ContextAssistant.Assistance {
        val private = listOf(input.message) + input.conversation.map { it.text }
        fun failed(failure: HelperFailure) =
            ContextAssistant.Assistance(ContextAssistant.Output.Nothing, failure = failure)
        fun local(category: HelperFailure.Category, detail: HelperFailure.Local): ContextAssistant.Assistance {
            val (profile, model) = transport.profileAndModel()
            return failed(HelperFailure(category, profile, model, local = detail))
        }
        val window = try {
            transport.contextWindowTokens()
        } catch (e: HelperUnavailable) {
            return local(HelperFailure.Category.CONFIGURATION_NEEDED, e.reason)
        }
        val exchanges = HelperConversation.select(
            HelperConversation.exchanges(input.conversation),
            pastExchanges().coerceAtLeast(0),
            input.message,
            window
        )
        val request = HelperRequest(
            HelperPrompt.INSTRUCTIONS,
            exchanges.flatten(),
            HelperPrompt.newest(input.message),
            input.chatId.takeIf { it.isNotBlank() }?.let { CACHE_KEY_PREFIX + it },
            input.chatId.takeIf { it.isNotBlank() }
        )
        val budget = if (input.handsFree) handsFreeAllowanceMs else textAllowanceMs
        val reply = try {
            runBlocking(Dispatchers.IO) { withTimeout(budget) { transport.complete(request) } }
        } catch (_: TimeoutCancellationException) {
            return local(HelperFailure.Category.TIME_ALLOWANCE, HelperFailure.Local.NoReplyWithin(budget / 1000))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HelperUnavailable) {
            return local(HelperFailure.Category.CONFIGURATION_NEEDED, e.reason)
        } catch (e: Exception) {
            return failed(transport.describe(e, private))
        }
        return when (val checked = HelperOutput.check(reply, input.queryFits)) {
            is HelperOutput.Result.Query -> ContextAssistant.Assistance(ContextAssistant.Output.Query(checked.text))
            HelperOutput.Result.Empty -> local(HelperFailure.Category.UNUSABLE, HelperFailure.Local.ReplyEmpty)
            HelperOutput.Result.MultipleLines ->
                local(HelperFailure.Category.UNUSABLE, HelperFailure.Local.ReplyMultipleLines)
            HelperOutput.Result.TooLong ->
                local(HelperFailure.Category.UNUSABLE, HelperFailure.Local.ReplyTooLong(input.queryTokenLimit))
        }
    }

    companion object {
        /** Time allowance (M.4 item 3): 8 s in text chat, 3 s in hands-free
         *  voice; no automatic retries. */
        const val TEXT_ALLOWANCE_MS = 8_000L
        const val HANDS_FREE_ALLOWANCE_MS = 3_000L

        /** Keeps the helper's cache key apart from the chat request's. */
        const val CACHE_KEY_PREFIX = "memory-context-helper:"
    }
}

/**
 * Which earlier exchanges the helper receives (M.4 item 1): up to the
 * requested number, whole and in chronological order, as far as the selected
 * model's context window allows. No fixed token cap.
 */
object HelperConversation {

    /**
     * The user and AI messages before the newest message of [requestMessages]
     * (the one being sent), oldest first, with their text only; attachments,
     * images and system messages are left out.
     */
    fun turnsBefore(requestMessages: List<ChatMessage>): List<ContextAssistant.Turn> =
        requestMessages.dropLast(1).mapNotNull { message ->
            val isUser = when (message.role) {
                ChatRole.User -> true
                ChatRole.Assistant -> false
                else -> return@mapNotNull null
            }
            val text = when (val content = message.messageContent) {
                is TextContent -> content.content
                is ListContent -> content.content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
                else -> null
            }?.trim()
            if (text.isNullOrEmpty()) null else ContextAssistant.Turn(isUser, text)
        }

    /** One exchange: a user message and the replies that follow it. A reply
     *  with no user message before it is an exchange of its own. */
    fun exchanges(turns: List<ContextAssistant.Turn>): List<List<ContextAssistant.Turn>> {
        val out = ArrayList<MutableList<ContextAssistant.Turn>>()
        for (turn in turns) {
            if (turn.text.isBlank()) continue
            if (turn.isUser || out.isEmpty() || out.last().isEmpty()) out.add(ArrayList())
            out.last().add(turn)
        }
        return out
    }

    /**
     * The newest [count] of [exchanges]: a strict sliding window, so an
     * older subject drops out as the chat moves on (Owner, 2026-11-16). With
     * a known [contextWindowTokens],
     * the oldest whole exchanges are left out until the request and room for
     * the reply fit; an exchange is never cut. With no known window, all
     * [count] are kept, and a provider refusal for size is a failure.
     */
    fun select(
        exchanges: List<List<ContextAssistant.Turn>>,
        count: Int,
        message: String,
        contextWindowTokens: Int?
    ): List<List<ContextAssistant.Turn>> {
        var kept = exchanges.takeLast(count.coerceAtLeast(0))
        if (contextWindowTokens == null) return kept
        while (kept.isNotEmpty() && estimatedTokens(kept, message) > contextWindowTokens) {
            kept = kept.drop(1)
        }
        return kept
    }

    /** The request's estimated size plus room for the reply. */
    fun estimatedTokens(exchanges: List<List<ContextAssistant.Turn>>, message: String): Int =
        IncludeTextPolicy.estimateTokens(HelperPrompt.INSTRUCTIONS) +
            exchanges.flatten().sumOf { IncludeTextPolicy.estimateTokens(it.text) } +
            IncludeTextPolicy.estimateTokens(HelperPrompt.newest(message)) +
            REPLY_ROOM_TOKENS

    /** Room kept for the reply: one search query, which the output check
     *  limits to the embedding model's message limit (512 tokens). */
    const val REPLY_ROOM_TOKENS = 512
}

/** The fixed, app-managed instructions and the newest message's wording
 *  (M.4 item 2). The earlier messages are sent as they are, in their own
 *  roles. */
object HelperPrompt {
    const val INSTRUCTIONS =
        "You write a search query for a memory lookup. The messages after these " +
            "instructions are a recent conversation between a user and an AI. The last " +
            "message gives the user's newest message.\n\n" +
            "Write one self-contained search query that states what the newest message " +
            "is about. Replace pronouns and vague references, such as \"she\", \"that one\" " +
            "or \"the same thing\", with the names, things or topics they refer to in the " +
            "conversation. If the newest message changes the subject, write the query " +
            "about the new subject. If it needs nothing from the conversation, restate " +
            "its subject.\n\n" +
            "Use only what the conversation says. Do not add facts, guesses or details " +
            "that are not in it. Do not answer the message, continue the conversation " +
            "or comment on it.\n\n" +
            "Reply with the query only, on one line, with no quotation marks, labels " +
            "or explanation."

    /** The last message of the request: the newest message, marked so the
     *  model writes the query rather than replying to it. */
    fun newest(message: String): String =
        "Newest message:\n" + message.trim() + "\n\nWrite the search query."
}

/** The output check (G.9b): one query of plain text, within the embedding
 *  model's message limit. Anything else is discarded, with the reason. */
object HelperOutput {
    sealed class Result {
        data class Query(val text: String) : Result()
        object Empty : Result()
        object MultipleLines : Result()
        object TooLong : Result()
    }

    fun check(reply: String?, fits: (String) -> Boolean): Result {
        val query = reply?.trim().orEmpty()
        if (query.isEmpty()) return Result.Empty
        if (query.any { it == '\n' || it == '\r' }) return Result.MultipleLines
        if (!fits(query)) return Result.TooLong
        return Result.Query(query)
    }
}
