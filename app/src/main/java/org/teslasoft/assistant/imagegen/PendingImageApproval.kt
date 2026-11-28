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

package org.teslasoft.assistant.imagegen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * An image the AI asked to create, waiting for the user's Create or Cancel,
 * saved with its chat so it survives the chat screen closing and the app
 * being closed. It keeps the turn's tool calls exactly as the model sent
 * them, the text the model wrote with them, and the conversation model and
 * endpoint the request was made with. Nothing is generated or sent until
 * the user answers.
 */
data class PendingImageApproval(
    val id: String,
    val calls: List<Call>,
    val streamedText: String,
    val model: String,
    val endpointId: String
) {
    data class Call(val id: String?, val name: String, val arguments: String)

    fun toAssembledCalls(): List<StreamedToolCallAssembler.AssembledToolCall> =
        calls.map { StreamedToolCallAssembler.AssembledToolCall(it.id, it.name, it.arguments) }

    fun encode(): String = JsonObject().apply {
        addProperty("version", VERSION)
        addProperty("id", id)
        addProperty("streamedText", streamedText)
        addProperty("model", model)
        addProperty("endpointId", endpointId)
        add("calls", JsonArray().apply {
            calls.forEach { call ->
                add(JsonObject().apply {
                    call.id?.let { addProperty("id", it) }
                    addProperty("name", call.name)
                    addProperty("arguments", call.arguments)
                })
            }
        })
    }.toString()

    companion object {
        private const val VERSION = 1

        fun of(
            id: String,
            calls: List<StreamedToolCallAssembler.AssembledToolCall>,
            streamedText: String,
            model: String,
            endpointId: String
        ) = PendingImageApproval(id, calls.map { Call(it.id, it.name, it.arguments) }, streamedText, model, endpointId)

        /** The saved approval, or null when none is saved or it cannot be read. */
        fun decode(raw: String?): PendingImageApproval? {
            if (raw.isNullOrBlank()) return null
            return try {
                val root = JsonParser.parseString(raw).asJsonObject
                if (root.get("version")?.asInt != VERSION) return null
                val calls = root.getAsJsonArray("calls")?.map { element ->
                    val call = element.asJsonObject
                    Call(
                        id = call.get("id")?.takeUnless { it.isJsonNull }?.asString,
                        name = call.get("name").asString,
                        arguments = call.get("arguments").asString
                    )
                }.orEmpty()
                if (calls.isEmpty()) return null
                PendingImageApproval(
                    id = root.get("id").asString,
                    calls = calls,
                    streamedText = root.get("streamedText")?.asString.orEmpty(),
                    model = root.get("model")?.asString.orEmpty(),
                    endpointId = root.get("endpointId")?.asString.orEmpty()
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
