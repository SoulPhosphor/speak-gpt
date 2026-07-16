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

package org.teslasoft.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorSanitizerTest {

    @Test fun safeStackContainsClassesAndFramesButNotMessages() {
        val cause = IllegalStateException(
            "https://provider.example/v1?api_key=secret Authorization: Bearer secret response body SECRET"
        )
        val error = RuntimeException("prompt=PRIVATE", cause)

        val trace = GenerationErrorSanitizer.safeStackTrace(error)

        assertTrue(trace.contains("RuntimeException"))
        assertTrue(trace.contains("IllegalStateException"))
        assertFalse(trace.contains("provider.example"))
        assertFalse(trace.contains("api_key"))
        assertFalse(trace.contains("Bearer"))
        assertFalse(trace.contains("SECRET"))
        assertFalse(trace.contains("PRIVATE"))
    }

    @Test fun exceptionClassListIncludesCauseChainWithoutMessages() {
        val error = RuntimeException("private outer", IllegalArgumentException("private cause"))
        val classes = GenerationErrorSanitizer.exceptionClassNames(error).joinToString(" ")

        assertTrue(classes.contains("RuntimeException"))
        assertTrue(classes.contains("IllegalArgumentException"))
        assertFalse(classes.contains("private"))
    }

    @Test fun httpDetailNeverIncludesProviderBody() {
        val result = GenErrorResult(GenErrorCode.S4, 503, GenFailureKind.HTTP_RESPONSE)
        val detail = GenerationErrorSanitizer.safeDetail(result)

        assertTrue(detail.contains("omitted"))
    }
}
