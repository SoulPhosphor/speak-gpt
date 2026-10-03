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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludeMessageProjectionTest {

    @Test fun persistedDocumentTextReachesTheModelRequestProjection() {
        val includesJson = ChatInclude.listToJson(
            listOf(
                ChatInclude(
                    id = "inc-1",
                    fileName = "notes.txt",
                    kind = IncludeKind.TXT,
                    form = IncludeForm.FULL,
                    fullText = "LOAD-BEARING DOCUMENT TEXT"
                )
            )
        )

        val projected = IncludeMessageProjection.userContent(
            typedText = "What do you think?",
            includesJson = includesJson
        )

        assertTrue(projected.contains("What do you think?"))
        assertTrue(projected.contains("LOAD-BEARING DOCUMENT TEXT"))
        assertTrue(projected.contains("<document name=\"notes.txt\">"))
    }

    @Test fun anArtifactProjectionDoesNotResendTheRemovedDocumentBody() {
        val includesJson = ChatInclude.listToJson(
            listOf(
                ChatInclude(
                    id = "inc-1",
                    fileName = "notes.txt",
                    kind = IncludeKind.TXT,
                    form = IncludeForm.ARTIFACT,
                    fullText = "BODY MUST NOT SURVIVE",
                    artifactLine = "User sent notes."
                )
            )
        )

        val projected = IncludeMessageProjection.userContent("Continue.", includesJson)

        assertTrue(projected.contains("User sent notes."))
        assertFalse(projected.contains("BODY MUST NOT SURVIVE"))
    }

    @Test fun removingOneOfTwoSentDocumentsKeepsTheOtherBodyAndBothHistoryRows() {
        val first = ChatInclude("first", "first.txt", IncludeKind.TXT, IncludeForm.FULL, "FIRST BODY", sentTokens = 123)
        val second = ChatInclude("second", "second.txt", IncludeKind.TXT, IncludeForm.FULL, "SECOND BODY", sentTokens = 456)
        val persisted = ChatInclude.listFromJson(ChatInclude.listToJson(listOf(
            first.copy(form = IncludeForm.ARTIFACT, artifactLine = "User sent first.txt."), second
        )))
        val projected = IncludeMessageProjection.userContent("Continue.", ChatInclude.listToJson(persisted))

        assertFalse(projected.contains("FIRST BODY"))
        assertTrue(projected.contains("SECOND BODY"))
        org.junit.Assert.assertEquals(listOf("first", "second"), IncludeHistoryPresentation.historyRows(persisted).map { it.id })
        org.junit.Assert.assertEquals(second, persisted[1])
        org.junit.Assert.assertEquals(123, persisted[0].sentTokens)
    }

    @Test fun removingOneImageKeepsTheOtherImagePayloadAndOriginalHistoryOrder() {
        val first = ChatInclude("first", "first.png", IncludeKind.PNG, IncludeForm.FULL, "",
            imageFileHash = "first-hash", imageMimeType = "image/png", imageWidth = 800, imageHeight = 600)
        val second = first.copy(id = "second", fileName = "second.png", imageFileHash = "second-hash")
        val persisted = ChatInclude.listFromJson(ChatInclude.listToJson(listOf(
            first.copy(form = IncludeForm.ARTIFACT, artifactLine = "User sent first.png.").withoutImageBytes(), second
        )))
        val projected = IncludeMessageProjection.userMessageParts("Continue.", ChatInclude.listToJson(persisted))

        org.junit.Assert.assertEquals(1, projected.imageParts.size)
        org.junit.Assert.assertEquals(second, persisted[1])
        assertFalse(persisted[0].hasLiveImageBytes())
        assertTrue(persisted[1].hasLiveImageBytes())
        org.junit.Assert.assertEquals(listOf("first", "second"), IncludeHistoryPresentation.historyRows(persisted).map { it.id })
    }


    @Test fun jsonDocumentRetainsItsFormatAndCompleteStructuredTextInTheRequest() {
        val json = "{\"models\":[\"Gemma\",\"Grok\"],\"enabled\":true}"
        val include = ChatInclude("json", "settings.JSON", IncludeKind.JSON, IncludeForm.FULL, json)
        val persisted = ChatInclude.listFromJson(ChatInclude.listToJson(listOf(include)))
        org.junit.Assert.assertEquals(IncludeKind.JSON, IncludeKind.fromFileName("settings.JSON"))
        org.junit.Assert.assertEquals(include, persisted.single())
        assertTrue(IncludeMessageProjection.userContent("Read this.", ChatInclude.listToJson(persisted)).contains(json))
    }

}
