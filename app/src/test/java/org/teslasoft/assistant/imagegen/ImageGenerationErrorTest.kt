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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §13 owner rulings (2026-07-29): each failure cause offers the action
 * matching it — Edit Prompt for a refused prompt, Change Settings for an
 * unsupported option, Retry only for failures that may succeed unchanged,
 * a settings link for configuration and authentication failures — and
 * nothing credential-shaped survives sanitization.
 */
class ImageGenerationErrorTest {

    @Test
    fun refusedPromptOffersEditPrompt() {
        assertEquals(
            ImageFailureAction.EDIT_PROMPT,
            failureActionFor(ImageErrorCause.PROMPT_REFUSED)
        )
    }

    @Test
    fun unsupportedOptionOffersChangeSettings() {
        assertEquals(
            ImageFailureAction.CHANGE_SETTINGS,
            failureActionFor(ImageErrorCause.UNSUPPORTED_OPTION)
        )
    }

    @Test
    fun onlyFailuresThatMaySucceedUnchangedOfferRetry() {
        val retryCauses = ImageErrorCause.entries.filter {
            failureActionFor(it) == ImageFailureAction.RETRY
        }
        assertEquals(
            setOf(
                ImageErrorCause.TIMED_OUT,
                ImageErrorCause.PROVIDER_ERROR,
                ImageErrorCause.NO_USABLE_IMAGE,
                ImageErrorCause.DOWNLOAD_INVALID,
                ImageErrorCause.ENDPOINT_UNREACHABLE
            ),
            retryCauses.toSet()
        )
    }

    @Test
    fun configurationAndAuthenticationFailuresLinkToSettings() {
        assertEquals(
            ImageFailureAction.OPEN_IMAGE_SETTINGS,
            failureActionFor(ImageErrorCause.NO_GENERATOR_CONFIGURED)
        )
        assertEquals(
            ImageFailureAction.OPEN_IMAGE_SETTINGS,
            failureActionFor(ImageErrorCause.AUTHENTICATION_FAILED)
        )
        assertEquals(
            ImageFailureAction.OPEN_IMAGE_SETTINGS,
            failureActionFor(ImageErrorCause.GENERATOR_MODEL_REJECTED)
        )
    }

    @Test
    fun cancellationOffersNothing() {
        assertEquals(ImageFailureAction.NONE, failureActionFor(ImageErrorCause.CANCELLED))
    }

    // --- sanitization (§13 never-log list) ---

    @Test
    fun apiKeyOccurrencesAreMasked() {
        val sanitized = ImageErrorSanitizer.sanitize(
            "Request with key sk-secret-123 failed", "sk-secret-123"
        )
        assertFalse(sanitized!!.contains("sk-secret-123"))
    }

    @Test
    fun authorizationHeadersAreMasked() {
        val sanitized = ImageErrorSanitizer.sanitize(
            "headers: Authorization: Bearer abc123token x-api-key: zzz", null
        )
        assertFalse(sanitized!!.contains("abc123token"))
        assertFalse(sanitized.contains("zzz"))
    }

    @Test
    fun longDetailsAreLengthLimited() {
        val sanitized = ImageErrorSanitizer.sanitize("x".repeat(2000), null)
        assertTrue(sanitized!!.length <= 310)
    }

    @Test
    fun blankDetailSanitizesToNull() {
        assertNull(ImageErrorSanitizer.sanitize(null, null))
        assertNull(ImageErrorSanitizer.sanitize("   ", null))
    }

    // --- provider request id sanitization (§13) ---

    @Test
    fun requestIdKeepsOnlySafeCharactersAndBoundsLength() {
        assertEquals(
            "req_abc-123.x:9",
            ImageRequestDiagnostics.sanitizeRequestId("  req_abc-123.x:9\n")
        )
        assertEquals(120, ImageRequestDiagnostics.sanitizeRequestId("a".repeat(500))!!.length)
        assertNull(ImageRequestDiagnostics.sanitizeRequestId("   "))
        assertNull(ImageRequestDiagnostics.sanitizeRequestId(null))
        assertNull(ImageRequestDiagnostics.sanitizeRequestId("\"<>!@#"))
    }
}
