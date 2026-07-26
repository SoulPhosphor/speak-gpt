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

class IncludeRendererTest {

    private fun doc(
        id: String = "inc-1",
        name: String = "report.txt",
        form: IncludeForm = IncludeForm.FULL,
        text: String = "THE BODY",
        condensed: String? = null,
        artifact: String? = null,
        notice: IncludeNotice = IncludeNotice.None
    ) = ChatInclude(
        id = id, fileName = name, kind = IncludeKind.TXT, form = form,
        fullText = text, condensedText = condensed, artifactLine = artifact,
        notice = notice
    )

    @Test fun aMessageWithNoAttachmentsIsUntouched() {
        assertEquals("just typing", IncludeRenderer.renderUserMessage("just typing", emptyList()))
    }

    @Test fun theUsersOwnWordsComeFirst() {
        val out = IncludeRenderer.renderUserMessage("what do you make of this?", listOf(doc()))
        assertTrue(out.startsWith("what do you make of this?"))
        assertTrue(out.contains("THE BODY"))
    }

    @Test fun theDocumentIsNamedAndDelimited() {
        val out = IncludeRenderer.renderUserMessage("hi", listOf(doc(name = "resume.docx")))
        assertTrue(out.contains("<document name=\"resume.docx\">"))
        assertTrue(out.contains("</document>"))
    }

    @Test fun aCondensedDocumentSaysSoAndSendsTheSummary() {
        val out = IncludeRenderer.renderUserMessage(
            "", listOf(doc(form = IncludeForm.CONDENSED, condensed = "SHORT VERSION"))
        )
        assertTrue(out.contains("form=\"condensed\""))
        assertTrue(out.contains("SHORT VERSION"))
        assertFalse(out.contains("THE BODY"))
    }

    @Test fun aRemovedDocumentLeavesItsBookmarkBehind() {
        val out = IncludeRenderer.renderUserMessage(
            "thoughts?",
            listOf(doc(form = IncludeForm.ARTIFACT, artifact = "User sent a resume."))
        )
        // The heavy text is gone, but the conversation still makes sense.
        assertFalse(out.contains("THE BODY"))
        assertTrue(out.contains("User sent a resume."))
        assertTrue(out.contains("thoughts?"))
        assertTrue(out.indexOf("thoughts?") < out.indexOf("User sent a resume."))
    }

    @Test fun truncationIsDisclosedToTheModelToo() {
        val out = IncludeRenderer.renderUserMessage(
            "", listOf(doc(notice = IncludeNotice.Truncated(30000)))
        )
        assertTrue(out.contains("partial=\"beginning only\""))
    }

    @Test fun aTrimmedSpreadsheetTellsTheModelWhatItIsMissing() {
        val out = IncludeRenderer.renderUserMessage(
            "", listOf(doc(name = "sales.csv", notice = IncludeNotice.CsvTrimmed(500, 47000)))
        )
        assertTrue(out.contains("rows=\"header + first 500 of 47000\""))
    }

    @Test fun severalDocumentsKeepTheirAttachOrder() {
        val out = IncludeRenderer.renderUserMessage(
            "compare these",
            listOf(
                doc(id = "a", name = "first.txt", text = "ALPHA"),
                doc(id = "b", name = "second.txt", text = "BETA")
            )
        )
        assertTrue(out.indexOf("ALPHA") < out.indexOf("BETA"))
    }

    @Test fun userWordsComeFirstAndIncludesKeepTheirStableOrderForCaching() {
        val out = IncludeRenderer.renderUserMessage(
            "hi",
            listOf(
                doc(id = "a", form = IncludeForm.ARTIFACT, artifact = "User sent an old file."),
                doc(id = "b", name = "live.txt", text = "STILL HERE")
            )
        )
        assertTrue(out.indexOf("hi") < out.indexOf("User sent an old file."))
        assertTrue(out.indexOf("User sent an old file.") < out.indexOf("STILL HERE"))
    }

    /**
     * The caching guarantee: identical includes must produce identical bytes
     * on every turn, or the provider's prefix cache misses and follow-up
     * questions about a document stop being cheap.
     */
    @Test fun renderingIsDeterministic() {
        val includes = listOf(
            doc(id = "a", name = "one.txt", text = "AAA"),
            doc(id = "b", name = "two.csv", text = "BBB"),
            doc(id = "c", form = IncludeForm.ARTIFACT, artifact = "User sent a note.")
        )
        val first = IncludeRenderer.renderUserMessage("stable", includes)
        repeat(5) {
            assertEquals(first, IncludeRenderer.renderUserMessage("stable", includes))
        }
    }

    @Test fun anEmptyTypedMessageStillCarriesItsDocument() {
        val out = IncludeRenderer.renderUserMessage("", listOf(doc()))
        assertTrue(out.startsWith("<document name=\"report.txt\">"))
        assertTrue(out.contains("THE BODY"))
    }

    @Test fun fileNamesAreEscapedInsideTheCompactWrapper() {
        val out = IncludeRenderer.renderUserMessage(
            "",
            listOf(doc(name = "A&B \"draft\".txt"))
        )
        assertTrue(out.startsWith("<document name=\"A&amp;B &quot;draft&quot;.txt\">"))
    }
}
