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

import org.teslasoft.assistant.preferences.memory.RetrievableMemory
import org.teslasoft.assistant.preferences.memory.ScoredMemory
import org.teslasoft.assistant.preferences.memory.librarian.SavedMemorySearch

/**
 * Context assistance (conversation_recall_plan.md G.9, G.9b): helps a
 * follow-up message keep the saved memories it refers to. One app-wide
 * choice, each choice its own implementation of [ContextAssistant]:
 *  - Off ([Off]): nothing; each message is searched on its own.
 *  - Code-based ([CodeBasedAssistant]): subject tracking, on the phone.
 *  - API AI ([ApiContextAssistant]) and Local AI (later) return a search
 *    query instead.
 *
 * Every choice keeps the same rules: the new message's own search results
 * come first and are never changed; assistance only adds candidates after
 * them, and every added memory goes through the enforcer's normal selection
 * (scope, Maximum Memories, the +3 rule, lorebook overlap and Memory Space).
 */
interface ContextAssistant {
    /**
     * Called once for each message about to be sent, after the message's own
     * search. Nothing it keeps between messages changes until the caller runs
     * [Assistance.commit], once the message is actually sent: a draft that is
     * blocked or cancelled leaves no trace.
     */
    fun assist(input: Input): Assistance

    data class Assistance(
        val output: Output,
        val commit: () -> Unit = {},
        /** Set when the API helper gave nothing because it failed. */
        val failure: ApiContextAssistant.Failure? = null
    )

    /** One earlier message of the chat, as the user or the AI wrote it. */
    data class Turn(val isUser: Boolean, val text: String)

    data class Input(
        val chatId: String,
        /** The embedding model's tag, or null when no model is usable. */
        val modelTag: String?,
        /** The message's own search. */
        val own: SavedMemorySearch.Result,
        /** The memories the chat may use right now, read for this send. */
        val eligible: Map<String, RetrievableMemory>,
        /** The chat's earlier user messages with text, newest first. */
        val earlierUserMessages: List<String>,
        /** Searches an earlier message the same way as the new one. Null when
         *  nothing can be searched. */
        val searchEarlier: (String) -> SavedMemorySearch.Result?,
        /** The message being sent. */
        val message: String = "",
        /** The chat's earlier user and AI messages with text, oldest first,
         *  not including [message]. */
        val conversation: List<Turn> = emptyList(),
        /** True while hands-free voice is running. */
        val handsFree: Boolean = false,
        /** Whether a query fits the embedding model's message limit. */
        val queryFits: (String) -> Boolean = { true }
    )

    sealed class Output {
        /** Nothing to add. */
        object Nothing : Output()

        /** Memories to add after the message's own results, in order. Each
         *  holds the current stored memory and the numbers it was found
         *  with. */
        data class Carry(val memories: List<ScoredMemory>) : Output()

        /** One self-contained search query (API AI, Local AI). Searched in
         *  addition to the new message; what only it finds follows the
         *  message's own results, through the same relevance gate. */
        data class Query(val text: String) : Output()
    }

    /** Off: no context assistance. */
    object Off : ContextAssistant {
        override fun assist(input: Input): Assistance = Assistance(Output.Nothing)
    }

    companion object {
        /**
         * The candidates handed to the enforcer's selection: [own] first and
         * unchanged, then what assistance added that [own] does not hold,
         * each memory once. The second list holds the ids that came only
         * from assistance.
         */
        fun candidates(own: List<ScoredMemory>, added: List<ScoredMemory>): Pair<List<ScoredMemory>, Set<String>> {
            val seen = own.map { it.memory.memoryId }.toHashSet()
            val extra = added.filter { seen.add(it.memory.memoryId) }
            return (own + extra) to extra.map { it.memory.memoryId }.toSet()
        }
    }
}
