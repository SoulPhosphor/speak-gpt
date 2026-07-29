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

import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.core.Parameters
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject

/**
 * The one client-side tool of the rebuilt image pipeline
 * (image-generation-rebuild-plan.md §6): `create_image` with a required
 * prompt, a required plain-language description, and an optional shape.
 * There is deliberately NO quality field (owner ruling, 2026-07-29): a
 * model-initiated image always uses the user's saved quality default, and
 * the conversation model can never touch a cost-affecting setting.
 *
 * Everything here that talks to the model (the tool description and the
 * tool result / error texts) is model-facing text, not user-facing
 * wording.
 */
object CreateImageTool {

    const val NAME = "create_image"

    /** §6: an excessive prompt is rejected with a clean tool error. */
    const val MAX_PROMPT_LENGTH = 4000

    private val KNOWN_FIELDS = setOf("prompt", "description", "shape")

    /** Whether the tool is offered at all (§7/§13): the user has Let the
     *  AI Create Images on AND a generator endpoint and model are
     *  configured. Without a generator, natural-language tool use is not
     *  offered to the conversation model. */
    fun shouldOfferTool(
        aiCreateImagesEnabled: Boolean,
        generatorEndpointId: String,
        generatorModelId: String
    ): Boolean = aiCreateImagesEnabled &&
        generatorEndpointId.isNotBlank() && generatorModelId.isNotBlank()

    fun definition(): Tool = Tool.function(
        name = NAME,
        description = "Creates one visible image in the current conversation from your " +
            "prompt. The app may ask the user to approve the image before it is " +
            "generated. Write the full artistic prompt yourself.",
        parameters = Parameters.buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "The detailed text sent to the image generator.")
                }
                putJsonObject("description") {
                    put("type", "string")
                    put(
                        "description",
                        "A short plain-language description of the image, shown with it " +
                            "and used for accessibility."
                    )
                }
                putJsonObject("shape") {
                    put("type", "string")
                    putJsonArray("enum") {
                        ImageShape.entries.forEach { add(it.storedValue) }
                    }
                    put(
                        "description",
                        "Optional image shape. Omit it to use the user's saved default."
                    )
                }
            }
            putJsonArray("required") {
                add("prompt")
                add("description")
            }
        }
    )

    /* ------------------------------ Validation (§6) ------------------------------ */

    sealed class Validation {
        class Valid(
            val prompt: String,
            val description: String,
            val shapeOverride: ImageShape?
        ) : Validation()

        /** The clean tool-error text returned to the conversation model. */
        class Invalid(val toolError: String) : Validation()
    }

    fun validate(argumentsJson: String): Validation {
        val root = try {
            JSONObject(argumentsJson)
        } catch (_: Exception) {
            return Validation.Invalid("the tool arguments were not valid JSON")
        }

        // §6: unknown fields that would change behavior are rejected — this
        // is also what blocks any attempt to smuggle a quality or count in.
        for (key in root.keys()) {
            if (key !in KNOWN_FIELDS) {
                return Validation.Invalid(
                    "unknown field \"$key\" — create_image accepts only: prompt, description, shape"
                )
            }
        }

        val prompt = root.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            return Validation.Invalid("prompt must be a non-empty string")
        }
        if (prompt.length > MAX_PROMPT_LENGTH) {
            return Validation.Invalid(
                "the prompt is too long (over $MAX_PROMPT_LENGTH characters)"
            )
        }

        val description = root.optString("description", "").trim()
        if (description.isEmpty()) {
            return Validation.Invalid("description must be a non-empty string")
        }

        val shapeOverride: ImageShape?
        if (root.has("shape") && !root.isNull("shape")) {
            val raw = root.optString("shape", "").trim().lowercase()
            shapeOverride = ImageShape.entries.firstOrNull { it.storedValue == raw }
                ?: return Validation.Invalid(
                    "shape must be one of: " +
                        ImageShape.entries.joinToString(", ") { it.storedValue }
                )
        } else {
            shapeOverride = null
        }

        return Validation.Valid(prompt, description, shapeOverride)
    }

    /* --------------------- Tool results returned to the model (§7.6) --------------------- */

    fun successResult(imageId: String, description: String, fallbackNote: String? = null): String {
        val result = JSONObject()
        result.put("success", true)
        result.put("image_id", imageId)
        result.put("description", description)
        if (fallbackNote != null) result.put("note", fallbackNote)
        return result.toString()
    }

    fun cancelledResult(): String {
        val result = JSONObject()
        result.put("success", false)
        result.put("outcome", "the user declined the image")
        return result.toString()
    }

    fun errorResult(message: String): String {
        val result = JSONObject()
        result.put("success", false)
        result.put("error", message)
        return result.toString()
    }
}
