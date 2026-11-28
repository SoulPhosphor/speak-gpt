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

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.completion.TextCompletion

/** One piece of a provider's reply, in the form the reply's bookkeeping reads. */
internal data class ReplyPiece(
    /** New text, or null when this piece carries none. */
    val content: String?,
    /** Characters counted for the Response Lifecycle and usage diagnostics. */
    val contentLength: Int,
    val finishReason: String?,
    val id: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val toolCalls: List<ToolPiece> = emptyList(),
    /** A completed (non-streaming) response: [content] is the whole reply. */
    val whole: Boolean = false
) {
    data class ToolPiece(val index: Int, val id: String?, val name: String?, val arguments: String?)

    companion object {
        /** A streamed Chat Completions chunk. A usage-only final chunk has no choices. */
        fun of(chunk: ChatCompletionChunk): ReplyPiece {
            val choice = chunk.choices.firstOrNull()
            val content = choice?.delta?.content?.takeIf { it != "null" }
            return ReplyPiece(
                content = content,
                contentLength = content?.length ?: 0,
                finishReason = choice?.finishReason?.value,
                id = chunk.id,
                promptTokens = chunk.usage?.promptTokens,
                completionTokens = chunk.usage?.completionTokens,
                totalTokens = chunk.usage?.totalTokens,
                toolCalls = choice?.delta?.toolCalls.orEmpty().map {
                    ToolPiece(it.index, it.id?.id, it.function?.nameOrNull, it.function?.argumentsOrNull)
                }
            )
        }

        /** A streamed legacy Completions piece (fine-tuned models). */
        fun of(completion: TextCompletion): ReplyPiece {
            val choice = completion.choices.firstOrNull()
            val counted = choice?.text?.takeIf { it != "null" }
            val first = completion.choices[0]
            val content = if (first != null && first.text != null && first.text.toString() != "null") first.text else null
            return ReplyPiece(
                content = content,
                contentLength = counted?.length ?: 0,
                finishReason = choice?.finishReason?.value,
                id = completion.id,
                promptTokens = completion.usage?.promptTokens,
                completionTokens = completion.usage?.completionTokens,
                totalTokens = completion.usage?.totalTokens
            )
        }

        /** A completed (non-streaming) Chat Completions response. */
        fun of(completion: ChatCompletion): ReplyPiece {
            val choice = completion.choices.firstOrNull()
            val content = choice?.message?.content?.takeIf { it != "null" }.orEmpty()
            return ReplyPiece(
                content = content,
                contentLength = content.length,
                finishReason = choice?.finishReason?.value,
                id = completion.id,
                promptTokens = completion.usage?.promptTokens,
                completionTokens = completion.usage?.completionTokens,
                totalTokens = completion.usage?.totalTokens,
                toolCalls = choice?.message?.toolCalls.orEmpty().mapIndexedNotNull { index, call ->
                    (call as? ToolCall.Function)?.let {
                        ToolPiece(index, it.id.id, it.function.nameOrNull, it.function.argumentsOrNull)
                    }
                },
                whole = true
            )
        }
    }
}
