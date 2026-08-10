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

package org.teslasoft.assistant.preferences.memory.archivist

/**
 * Credential-free seam around one Archivist model request. Production supplies
 * the OpenAI-compatible transport; tests supply deterministic responses or
 * failures while exercising the exact prompt construction and parser used by
 * the app.
 */
internal fun interface ArchivistModelTransport {
    suspend fun complete(request: ArchivistModelRequest): String
}

internal data class ArchivistModelRequest(
    val systemPrompt: String,
    val conversationData: String,
    val model: String,
    val temperature: Double,
    /** One for the normal request, two for the single bounded JSON repair. */
    val attempt: Int
)

/** The provider reported a response that Stage E can prove was truncated. */
internal class ArchivistClearlyTruncatedException : Exception(
    "Archivist response was clearly truncated"
)

internal object ArchivistRequestExecutor {
    const val MAX_PARSE_ATTEMPTS = 2

    private const val REPAIR_PROTOCOL = """

## App-Owned Output Repair — mandatory
The previous response was not readable as the required JSON object. Analyze the
same delimited data again and return exactly one JSON object in the app-owned
schema. Do not add prose or Markdown fences. This is the final repair attempt.
"""

    suspend fun associative(
        basePrompt: String,
        memoryTypes: List<Pair<String, String>>,
        protocol: ArchivistRequestProtocol,
        conversationData: String,
        model: String,
        temperature: Double,
        transport: ArchivistModelTransport
    ): ArchivistResponseParser.Parsed {
        val systemPrompt = ArchivistPrompt.withRuntimeProtocol(
            basePrompt, memoryTypes, protocol
        )
        return associativePrepared(
            systemPrompt = systemPrompt,
            protocol = protocol,
            conversationData = conversationData,
            model = model,
            temperature = temperature,
            transport = transport
        )
    }

    /** Stage E has already built this exact prompt to calculate request
     * headroom. Reuse it rather than rebuilding a subtly different request. */
    suspend fun associativePrepared(
        systemPrompt: String,
        protocol: ArchivistRequestProtocol,
        conversationData: String,
        model: String,
        temperature: Double,
        transport: ArchivistModelTransport
    ): ArchivistResponseParser.Parsed = executeParsed(
        systemPrompt = systemPrompt,
        conversationData = conversationData,
        model = model,
        temperature = temperature,
        transport = transport
    ) { raw -> ArchivistResponseParser.parse(raw, protocol) }

    private suspend fun <T> executeParsed(
        systemPrompt: String,
        conversationData: String,
        model: String,
        temperature: Double,
        transport: ArchivistModelTransport,
        parse: (String) -> T
    ): T {
        var lastParseFailure: Exception? = null
        for (attempt in 1..MAX_PARSE_ATTEMPTS) {
            val effectivePrompt = if (attempt == 1) {
                systemPrompt
            } else {
                systemPrompt + REPAIR_PROTOCOL
            }
            // Transport/provider failures are truthful failures, not malformed
            // model output. They propagate immediately and are never relabeled
            // or retried by the JSON repair path.
            val raw = transport.complete(
                ArchivistModelRequest(
                    systemPrompt = effectivePrompt,
                    conversationData = conversationData,
                    model = model,
                    temperature = temperature,
                    attempt = attempt
                )
            )
            try {
                return parse(raw)
            } catch (e: Exception) {
                lastParseFailure = e
            }
        }
        throw TaggedArchivistException(
            ArchivistFailure.UNREADABLE,
            lastParseFailure ?: IllegalArgumentException("unreadable Archivist response")
        )
    }
}
