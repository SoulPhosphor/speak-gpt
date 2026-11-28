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

package org.teslasoft.assistant.conversation.reply

import com.aallam.openai.api.chat.ChatCompletionRequest
import org.teslasoft.assistant.preferences.memory.ActiveMemoryReference
import org.teslasoft.assistant.reasoning.ReasoningStreamAccumulator

/**
 * What the follow-up request of a turn waiting for an image approval needs,
 * kept in memory only (never saved): the exact request the model answered
 * with its tool call, and that turn's settings. After the app was closed it
 * is gone, and the follow-up is not rebuilt from anything else.
 */
internal class ToolTurnContext(
    val approvalId: String,
    val endpointId: String,
    val request: ChatCompletionRequest,
    val streamingEnabled: Boolean,
    val memoryReferences: List<ActiveMemoryReference>,
    val reasoning: ReasoningStreamAccumulator?
)

internal object PendingToolTurns {
    private val contexts = HashMap<String, ToolTurnContext>()

    fun keep(chatId: String, context: ToolTurnContext) = synchronized(contexts) { contexts[chatId] = context }

    /** The context of exactly [approvalId], removed; null when it is not in memory. */
    fun take(chatId: String, approvalId: String): ToolTurnContext? = synchronized(contexts) {
        contexts[chatId]?.takeIf { it.approvalId == approvalId }?.also { contexts.remove(chatId) }
    }

    fun forget(chatId: String) = synchronized(contexts) { contexts.remove(chatId) }
}
