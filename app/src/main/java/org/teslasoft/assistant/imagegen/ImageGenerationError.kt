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

/**
 * The failure causes an image-generation request can end in
 * (image-generation-rebuild-plan.md §13). Errors must identify which side
 * failed; an umbrella code must never conceal the specific cause from the
 * user.
 */
enum class ImageErrorCause {
    /** No image service and model are configured. */
    NO_GENERATOR_CONFIGURED,

    /** The provider rejected the selected generator model. */
    GENERATOR_MODEL_REJECTED,

    /** The generator endpoint could not be reached. */
    ENDPOINT_UNREACHABLE,

    /** Authentication with the generator endpoint failed. */
    AUTHENTICATION_FAILED,

    /** The provider refused the prompt (moderation / content policy). */
    PROMPT_REFUSED,

    /** Generation timed out. */
    TIMED_OUT,

    /** The response did not contain a usable image. */
    NO_USABLE_IMAGE,

    /** The image download was too large or invalid. */
    DOWNLOAD_INVALID,

    /** An explicitly requested option the selected generator cannot accept. */
    UNSUPPORTED_OPTION,

    /** A provider-side error that may succeed unchanged on retry
     *  (5xx, rate limits, out-of-credit responses carry their detail). */
    PROVIDER_ERROR,

    /** The user cancelled the request. */
    CANCELLED
}

/**
 * The action a failed request offers, matched to its cause (owner ruling,
 * 2026-07-29): Edit Prompt for a refused prompt, Change Settings for an
 * unsupported option, Retry ONLY for failures that may succeed without
 * changing the request, and a link to the image-generator settings for
 * configuration and authentication failures.
 */
enum class ImageFailureAction {
    EDIT_PROMPT,
    CHANGE_SETTINGS,
    RETRY,
    OPEN_IMAGE_SETTINGS,
    NONE
}

/** The §13 cause-to-action mapping, pure and unit-tested. */
fun failureActionFor(cause: ImageErrorCause): ImageFailureAction = when (cause) {
    ImageErrorCause.PROMPT_REFUSED -> ImageFailureAction.EDIT_PROMPT
    ImageErrorCause.UNSUPPORTED_OPTION -> ImageFailureAction.CHANGE_SETTINGS
    // May succeed without changing the request: timeouts, temporary
    // provider errors, malformed image responses, and a network that
    // could not be reached this time.
    ImageErrorCause.TIMED_OUT,
    ImageErrorCause.PROVIDER_ERROR,
    ImageErrorCause.NO_USABLE_IMAGE,
    ImageErrorCause.DOWNLOAD_INVALID,
    ImageErrorCause.ENDPOINT_UNREACHABLE -> ImageFailureAction.RETRY
    // Configuration and authentication failures link to the settings.
    ImageErrorCause.NO_GENERATOR_CONFIGURED,
    ImageErrorCause.GENERATOR_MODEL_REJECTED,
    ImageErrorCause.AUTHENTICATION_FAILED -> ImageFailureAction.OPEN_IMAGE_SETTINGS
    ImageErrorCause.CANCELLED -> ImageFailureAction.NONE
}

/**
 * Internal failure signal for the image pipeline. `errorCause` (not
 * `cause`, which Throwable owns) carries the §13 classification;
 * `sanitizedDetail` is already credential-free and length-limited.
 */
class ImageGenerationException(
    val errorCause: ImageErrorCause,
    val sanitizedDetail: String? = null
) : Exception(errorCause.name + (sanitizedDetail?.let { ": $it" } ?: ""))

/**
 * Strips credentials from provider error text before it can reach a log
 * or a diagnostic (§13 never-log list) and bounds its length. The prompt
 * itself is never passed through here — it simply never enters
 * diagnostics.
 */
object ImageErrorSanitizer {

    private const val MAX_DETAIL_LENGTH = 300

    private val headerPattern =
        Regex("""(?i)(authorization|x-api-key|api-key)\s*[:=]\s*\S+""")
    private val bearerPattern = Regex("""(?i)bearer\s+\S+""")

    fun sanitize(raw: String?, apiKey: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = raw
        if (!apiKey.isNullOrBlank()) text = text.replace(apiKey, "•••")
        // Bearer tokens first: the header mask below stops at the first
        // whitespace, so masking "Authorization: Bearer" without this
        // would leave the token itself standing.
        text = bearerPattern.replace(text, "Bearer •••")
        text = headerPattern.replace(text) { m -> m.groupValues[1] + ": •••" }
        text = text.replace(Regex("""\s+"""), " ").trim()
        if (text.length > MAX_DETAIL_LENGTH) text = text.take(MAX_DETAIL_LENGTH) + "…"
        return text.ifBlank { null }
    }
}
