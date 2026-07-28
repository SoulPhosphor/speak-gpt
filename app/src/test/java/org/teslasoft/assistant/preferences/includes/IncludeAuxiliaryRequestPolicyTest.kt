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

package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludeAuxiliaryRequestPolicyTest {

    private val plan = ChatInclude(
        id = "plan",
        fileName = "launch-plan.md",
        kind = IncludeKind.MARKDOWN,
        form = IncludeForm.FULL,
        fullText = "Phase 1 has a dependency. Risk: vendor delay. Open question: launch date."
    )

    @Test fun condenseUsesSelectedModelConfiguredLimitAndCliffNotesContract() {
        val request = IncludeAuxiliaryRequestPolicy.condense(
            include = plan,
            selectedModel = "owner-selected-model",
            configuredMaxTokens = 777
        )

        assertEquals("owner-selected-model", request.model)
        assertEquals(777, request.maxTokens)
        assertTrue(request.prompt.contains("substantially shorter"))
        assertTrue(request.prompt.contains("Cliff Notes"))
        assertTrue(request.prompt.contains("main sections, subjects, arguments, or sequence"))
        assertTrue(request.prompt.contains("warnings, limitations, uncertainty"))
        assertTrue(request.prompt.contains("résumé"))
        assertTrue(request.prompt.contains("report"))
        assertTrue(request.prompt.contains("plan"))
        assertTrue(request.prompt.contains(plan.fullText))
        assertFalse(request.prompt.contains("half"))
    }

    @Test fun artifactUsesSelectedModelSmallLimitAndReminderContract() {
        val request = IncludeAuxiliaryRequestPolicy.artifact(
            include = plan,
            selectedModel = "custom-endpoint-model",
            excerptCharacters = 2_000
        )

        assertEquals("custom-endpoint-model", request.model)
        assertEquals(IncludeAuxiliaryRequestPolicy.ARTIFACT_MAX_TOKENS, request.maxTokens)
        assertTrue(request.prompt.contains("very short reminder"))
        assertTrue(request.prompt.contains("one or two especially important details"))
        assertTrue(request.prompt.contains("no more than three short sentences"))
        assertTrue(request.prompt.contains("Do not try to preserve the document's full contents"))
        assertFalse(request.prompt.contains("Cliff Notes or a structured outline of the document below"))
    }

    private val image = ChatInclude(
        id = "img",
        fileName = "chart.png",
        kind = IncludeKind.PNG,
        form = IncludeForm.FULL,
        fullText = "",
        imageFileHash = "abc",
        imageMimeType = "image/png",
        imageWidth = 1024,
        imageHeight = 768
    )

    @Test fun reduceImageUsesSelectedModelConfiguredLimitAndTextMemoryContract() {
        val request = IncludeAuxiliaryRequestPolicy.reduceImage(
            include = image,
            accompanyingUserMessage = "Compare Q1 with Q2",
            selectedModel = "vision-capable-model",
            configuredMaxTokens = 900
        )

        assertEquals("vision-capable-model", request.model)
        assertEquals(900, request.maxTokens)
        assertTrue(request.prompt.contains("concise, self-contained text memory of this image"))
        assertTrue(request.prompt.contains("main subject and apparent purpose"))
        assertTrue(request.prompt.contains("visible text that matters"))
        assertTrue(request.prompt.contains("Do not invent identities"))
        assertTrue(request.prompt.contains("Return only the text memory"))
        assertTrue(request.prompt.contains("File name: chart.png"))
        // The typed-message context is passed to the model so it prioritises
        // details the user cared about, not everything in the frame.
        assertTrue(request.prompt.contains("Accompanying message: Compare Q1 with Q2"))
    }

    @Test fun reduceImageHandlesAnEmptyAccompanyingMessageGracefully() {
        val request = IncludeAuxiliaryRequestPolicy.reduceImage(
            include = image,
            accompanyingUserMessage = "",
            selectedModel = "m",
            configuredMaxTokens = 500
        )
        assertTrue(request.prompt.contains("Accompanying message: (none)"))
    }

    @Test fun reduceImageMaxTokensNeverDropsBelowOne() {
        val request = IncludeAuxiliaryRequestPolicy.reduceImage(
            include = image,
            accompanyingUserMessage = "",
            selectedModel = "m",
            configuredMaxTokens = 0
        )
        assertEquals(1, request.maxTokens)
    }
}
