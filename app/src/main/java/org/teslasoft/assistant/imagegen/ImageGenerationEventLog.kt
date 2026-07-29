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

import android.content.Context
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences
import java.util.Locale

/**
 * The two §13 log surfaces (image-generation-rebuild-plan.md, owner
 * rulings 2026-07-29). Entry wording follows the owner-approved examples,
 * Title Caps per ui-style-guide.md. Everything passing through here is
 * already sanitized upstream; the never-log list (keys, auth headers,
 * signed URLs, request bodies, raw image data, prompts, conversation
 * content) simply never enters these formatters.
 *
 * Recording gates: failures, fallbacks, tool mistakes, and capability
 * changes write only while Image Generation Error Recording is on;
 * successes write only while Successful Image Tracking is on. Actionable
 * errors reach chat regardless — that split lives at the call sites.
 */
object ImageGenerationEventLog {

    const val ERROR_CHANNEL = "image_gen_errors"
    const val SUCCESS_CHANNEL = "image_gen"
    private const val TAG = "ImageGeneration"

    /* ------------------------------ formatting (pure) ------------------------------ */

    fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.1f seconds", milliseconds / 1000.0)

    fun failureOutcomeLabel(cause: ImageErrorCause): String = when (cause) {
        ImageErrorCause.NO_GENERATOR_CONFIGURED -> "Not Configured"
        ImageErrorCause.GENERATOR_MODEL_REJECTED -> "Model Rejected"
        ImageErrorCause.ENDPOINT_UNREACHABLE -> "Service Unreachable"
        ImageErrorCause.AUTHENTICATION_FAILED -> "Authentication Failed"
        ImageErrorCause.PROMPT_REFUSED -> "Prompt Refused"
        ImageErrorCause.TIMED_OUT -> "Timed Out"
        ImageErrorCause.NO_USABLE_IMAGE -> "No Usable Image"
        ImageErrorCause.DOWNLOAD_INVALID -> "Invalid Download"
        ImageErrorCause.UNSUPPORTED_OPTION -> "Unsupported Option"
        ImageErrorCause.PROVIDER_ERROR -> "Provider Error"
        ImageErrorCause.CANCELLED -> "Cancelled"
    }

    /** §13 failure diagnostics, owner-approved layout: total duration, and
     *  generation/download separately when downloading was its own step. */
    fun formatFailureEntry(
        diagnostics: ImageRequestDiagnostics,
        cause: ImageErrorCause,
        sanitizedDetail: String?
    ): String = buildString {
        append("Image Request Failed")
        append("\nProvider: ").append(diagnostics.provider)
        append("\nModel: ").append(diagnostics.modelId)
        append("\nElapsed Time: ").append(formatSeconds(diagnostics.totalMs))
        if (diagnostics.downloadMs != null && diagnostics.generationMs != null) {
            append("\nGeneration Time: ").append(formatSeconds(diagnostics.generationMs))
            append("\nDownload Time: ").append(formatSeconds(diagnostics.downloadMs))
        }
        diagnostics.httpStatus?.let { append("\nHTTP Status: ").append(it) }
        diagnostics.providerRequestId?.let { append("\nProvider Request ID: ").append(it) }
        append("\nOutcome: ").append(failureOutcomeLabel(cause))
        if (!sanitizedDetail.isNullOrBlank()) append("\nDetail: ").append(sanitizedDetail)
    }

    /** §13 success diagnostics, owner-approved layout. */
    fun formatSuccessEntry(diagnostics: ImageRequestDiagnostics): String = buildString {
        append("Image Request Completed")
        append("\nProvider: ").append(diagnostics.provider)
        append("\nModel: ").append(diagnostics.modelId)
        if (diagnostics.downloadMs != null && diagnostics.generationMs != null) {
            append("\nGeneration Time: ").append(formatSeconds(diagnostics.generationMs))
            append("\nDownload Time: ").append(formatSeconds(diagnostics.downloadMs))
        } else {
            append("\nElapsed Time: ").append(formatSeconds(diagnostics.totalMs))
        }
        diagnostics.httpStatus?.let { append("\nHTTP Status: ").append(it) }
        diagnostics.providerRequestId?.let { append("\nProvider Request ID: ").append(it) }
        append("\nOutcome: Image Saved")
    }

    /** §13 automatic tool-capability change entry. */
    fun formatCapabilityChangeEntry(
        endpointLabel: String,
        modelId: String,
        previousState: String,
        newState: String,
        sanitizedError: String?,
        retriedWithoutTools: Boolean,
        retrySucceeded: Boolean
    ): String = buildString {
        append("Automatic Tool Capability Change")
        append("\nEndpoint: ").append(endpointLabel)
        append("\nModel: ").append(modelId)
        append("\nCapability: ").append(previousState).append(" → ").append(newState)
        append("\nChanged: Learned Automatically")
        if (!sanitizedError.isNullOrBlank()) {
            append("\nProvider Error: ").append(sanitizedError)
        }
        append("\nRetried Without Tools: ").append(if (retriedWithoutTools) "Yes" else "No")
        if (retriedWithoutTools) {
            append("\nRetry Outcome: ").append(if (retrySucceeded) "Succeeded" else "Failed")
        }
    }

    /* ------------------------------ gated recording ------------------------------ */

    private fun errorRecordingOn(context: Context): Boolean =
        Preferences.getPreferences(context, "").getImageGenErrorLogging()

    fun recordFailure(
        context: Context,
        diagnostics: ImageRequestDiagnostics,
        cause: ImageErrorCause,
        sanitizedDetail: String?
    ) {
        if (!errorRecordingOn(context)) return
        Logger.logAsync(
            context, ERROR_CHANNEL, TAG, "error",
            formatFailureEntry(diagnostics, cause, sanitizedDetail)
        )
    }

    /** §13 conversation-model tool mistakes: invalid JSON, empty/over-long
     *  prompts, unknown fields, extra attempts past the one-per-turn limit,
     *  attempts to set options the tool does not offer. */
    fun recordToolMistake(context: Context, description: String) {
        if (!errorRecordingOn(context)) return
        Logger.logAsync(
            context, ERROR_CHANNEL, TAG, "warning",
            "Conversation Model Tool Mistake\nDetail: $description"
        )
    }

    /** §13 silent fallbacks — the case the user cannot otherwise see: a
     *  model-initiated option fell back to the provider default because the
     *  selected generator could not support it. */
    fun recordSilentFallback(
        context: Context,
        optionLabels: String,
        provider: String,
        modelId: String
    ) {
        if (!errorRecordingOn(context)) return
        Logger.logAsync(
            context, ERROR_CHANNEL, TAG, "warning",
            "Silent Option Fallback\nProvider: $provider\nModel: $modelId" +
                "\nOption: $optionLabels\nOutcome: Provider Default Used"
        )
    }

    fun recordCapabilityChange(
        context: Context,
        endpointLabel: String,
        modelId: String,
        previousState: String,
        newState: String,
        sanitizedError: String?,
        retriedWithoutTools: Boolean,
        retrySucceeded: Boolean
    ) {
        if (!errorRecordingOn(context)) return
        Logger.logAsync(
            context, ERROR_CHANNEL, TAG, "warning",
            formatCapabilityChangeEntry(
                endpointLabel, modelId, previousState, newState,
                sanitizedError, retriedWithoutTools, retrySucceeded
            )
        )
    }

    fun recordSuccess(context: Context, diagnostics: ImageRequestDiagnostics) {
        if (!Preferences.getPreferences(context, "").getSuccessfulImageTracking()) return
        Logger.logAsync(
            context, SUCCESS_CHANNEL, TAG, "info",
            formatSuccessEntry(diagnostics)
        )
    }
}
