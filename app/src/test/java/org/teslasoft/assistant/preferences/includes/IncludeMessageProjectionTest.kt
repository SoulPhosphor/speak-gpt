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
        assertTrue(projected.contains("Attached document: notes.txt"))
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
}
