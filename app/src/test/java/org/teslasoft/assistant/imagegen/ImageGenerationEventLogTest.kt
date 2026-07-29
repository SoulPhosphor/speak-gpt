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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.Preferences

/**
 * §13 of image-generation-rebuild-plan.md: entries follow the
 * owner-approved example layouts, and the Image Generation Log's retention
 * clamp allows ZERO (unlimited) — a rule the error logs never get.
 */
class ImageGenerationEventLogTest {

    private fun diagnostics(
        generationMs: Long? = null,
        downloadMs: Long? = null,
        httpStatus: Int? = 500,
        requestId: String? = "req_xyz789"
    ) = ImageRequestDiagnostics(
        provider = "OpenAI",
        endpointLabel = "My Service",
        modelId = "example/image-model",
        timestamp = 1L,
        totalMs = 31700,
        generationMs = generationMs,
        downloadMs = downloadMs,
        httpStatus = httpStatus,
        providerRequestId = requestId
    )

    @Test
    fun failureEntryMatchesTheApprovedExample() {
        val entry = ImageGenerationEventLog.formatFailureEntry(
            diagnostics(), ImageErrorCause.PROVIDER_ERROR, null
        )
        assertEquals(
            "Image Request Failed\n" +
                "Provider: OpenAI\n" +
                "Model: example/image-model\n" +
                "Elapsed Time: 31.7 seconds\n" +
                "HTTP Status: 500\n" +
                "Provider Request ID: req_xyz789\n" +
                "Outcome: Provider Error",
            entry
        )
    }

    @Test
    fun successEntryMatchesTheApprovedExample() {
        val entry = ImageGenerationEventLog.formatSuccessEntry(
            ImageRequestDiagnostics(
                provider = "OpenRouter",
                endpointLabel = "OpenRouter",
                modelId = "example/image-model",
                timestamp = 1L,
                totalMs = 19600,
                generationMs = 18400,
                downloadMs = 1200,
                httpStatus = 200,
                providerRequestId = "req_abc123"
            )
        )
        assertEquals(
            "Image Request Completed\n" +
                "Provider: OpenRouter\n" +
                "Model: example/image-model\n" +
                "Generation Time: 18.4 seconds\n" +
                "Download Time: 1.2 seconds\n" +
                "HTTP Status: 200\n" +
                "Provider Request ID: req_abc123\n" +
                "Outcome: Image Saved",
            entry
        )
    }

    @Test
    fun absentStatusAndRequestIdLinesAreOmittedNotFaked() {
        val entry = ImageGenerationEventLog.formatFailureEntry(
            diagnostics(httpStatus = null, requestId = null),
            ImageErrorCause.TIMED_OUT,
            null
        )
        assertFalse(entry.contains("HTTP Status"))
        assertFalse(entry.contains("Provider Request ID"))
        assertTrue(entry.endsWith("Outcome: Timed Out"))
    }

    @Test
    fun separateDownloadStepAddsBothTimingLinesToFailures() {
        val entry = ImageGenerationEventLog.formatFailureEntry(
            diagnostics(generationMs = 30000, downloadMs = 1700),
            ImageErrorCause.DOWNLOAD_INVALID,
            null
        )
        assertTrue(entry.contains("Elapsed Time: 31.7 seconds"))
        assertTrue(entry.contains("Generation Time: 30.0 seconds"))
        assertTrue(entry.contains("Download Time: 1.7 seconds"))
    }

    @Test
    fun capabilityChangeEntryCarriesEveryRuledField() {
        val entry = ImageGenerationEventLog.formatCapabilityChangeEntry(
            endpointLabel = "My Service",
            modelId = "some/chat-model",
            previousState = "Unknown",
            newState = "Unsupported",
            sanitizedError = "tools are not supported",
            retriedWithoutTools = true,
            retrySucceeded = true
        )
        assertTrue(entry.startsWith("Automatic Tool Capability Change"))
        assertTrue(entry.contains("Endpoint: My Service"))
        assertTrue(entry.contains("Model: some/chat-model"))
        assertTrue(entry.contains("Capability: Unknown → Unsupported"))
        assertTrue(entry.contains("Changed: Learned Automatically"))
        assertTrue(entry.contains("Provider Error: tools are not supported"))
        assertTrue(entry.contains("Retried Without Tools: Yes"))
        assertTrue(entry.contains("Retry Outcome: Succeeded"))
    }

    @Test
    fun everyFailureCauseHasATitleCapsOutcomeLabel() {
        for (cause in ImageErrorCause.entries) {
            val label = ImageGenerationEventLog.failureOutcomeLabel(cause)
            assertTrue(label.isNotBlank())
            assertTrue("label '$label' must start uppercase", label.first().isUpperCase())
        }
    }

    // --- §13 retention: zero means unlimited, only for the success log ---

    @Test
    fun imageGenRetentionAllowsZeroAsUnlimited() {
        assertEquals(0, Preferences.coerceImageGenRetention(0, Preferences.LOG_MAX_ENTRIES_LIMIT))
        assertEquals(0, Preferences.coerceImageGenRetention(-5, Preferences.LOG_MAX_ENTRIES_LIMIT))
        assertEquals(
            Preferences.LOG_MAX_ENTRIES_LIMIT,
            Preferences.coerceImageGenRetention(99999, Preferences.LOG_MAX_ENTRIES_LIMIT)
        )
        assertEquals(500, Preferences.coerceImageGenRetention(500, Preferences.LOG_MAX_ENTRIES_LIMIT))
    }

    @Test
    fun errorLogRetentionStillFloorsAtOne() {
        // The zero-means-unlimited rule never applies to the error logs.
        assertEquals(1, Preferences.coerceLogMaxEntries(0))
        assertEquals(1, Preferences.coerceLogMaxDays(0))
    }
}
