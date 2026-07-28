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

    // ---- Images (Phase 3) --------------------------------------------------

    private fun image(
        id: String = "img-1",
        name: String = "photo.jpg",
        kind: IncludeKind = IncludeKind.JPEG,
        form: IncludeForm = IncludeForm.FULL,
        condensed: String? = null,
        artifact: String? = null,
        hash: String? = "img-bytes",
        mime: String? = "image/jpeg",
        width: Int = 1024,
        height: Int = 768
    ) = ChatInclude(
        id = id,
        fileName = name,
        kind = kind,
        form = form,
        fullText = "",
        condensedText = condensed,
        artifactLine = artifact,
        imageFileHash = if (form == IncludeForm.FULL) hash else null,
        imageMimeType = if (form == IncludeForm.FULL) mime else null,
        imageWidth = if (form == IncludeForm.FULL) width else 0,
        imageHeight = if (form == IncludeForm.FULL) height else 0
    )

    @Test fun aFullImageProducesNoTextSideContent() {
        // The image's bytes ride as a separate content part, so the text side
        // must not wrap it in an inline block — that would silently double up
        // "there is an image here" hints in the outgoing request.
        val out = IncludeRenderer.renderUserMessage("what is this?", listOf(image()))
        assertEquals("what is this?", out)
    }

    @Test fun aReducedImageBecomesAnImageBlockNotADocumentBlock() {
        val out = IncludeRenderer.renderUserMessage(
            "explain",
            listOf(image(form = IncludeForm.CONDENSED, condensed = "A chart of Q1 revenue."))
        )
        assertTrue(out.contains("<image name=\"photo.jpg\" form=\"reduced\">"))
        assertTrue(out.contains("A chart of Q1 revenue."))
        assertTrue(out.contains("</image>"))
        // Reduced images must not masquerade as document text.
        assertFalse(out.contains("<document"))
    }

    @Test fun aRemovedImageLeavesABookmarkJustLikeARemovedDocument() {
        val out = IncludeRenderer.renderUserMessage(
            "still relevant?",
            listOf(image(form = IncludeForm.ARTIFACT, artifact = "User sent a photo of a chart."))
        )
        assertTrue(out.contains("<bookmark name=\"photo.jpg\">User sent a photo of a chart.</bookmark>"))
    }

    @Test fun imagePartsAreEnumeratedForOnlyFullImages() {
        val includes = listOf(
            doc(id = "d1", name = "notes.txt"),
            image(id = "i1", name = "one.jpg"),
            image(id = "i2", form = IncludeForm.CONDENSED, condensed = "text"),
            image(id = "i3", form = IncludeForm.ARTIFACT, artifact = "bookmark")
        )
        val parts = IncludeRenderer.imagePartsFor(includes)
        assertEquals(1, parts.size)
        assertEquals("i1", parts[0].includeId)
        assertEquals("image/jpeg", parts[0].imageMimeType)
        assertEquals("one.jpg", parts[0].fileName)
    }

    @Test fun projectionExposesTextSideAndImagePartsTogether() {
        val includesJson = ChatInclude.listToJson(
            listOf(
                doc(id = "d1", name = "brief.txt", text = "DOC BODY"),
                image(id = "i1", name = "one.jpg"),
                image(id = "i2", name = "two.png",
                    kind = IncludeKind.PNG, mime = "image/png")
            )
        )
        val projected = IncludeMessageProjection.userMessageParts(
            typedText = "compare",
            includesJson = includesJson
        )
        // Text side contains the typed words + the document wrapper, and
        // NOTHING about the images (their bytes go separately).
        assertTrue(projected.text.contains("compare"))
        assertTrue(projected.text.contains("<document"))
        assertFalse(projected.text.contains("one.jpg"))
        // Image parts arrive in stable attachment order.
        assertEquals(listOf("i1", "i2"),
            projected.imageParts.map { it.includeId })
        assertFalse(projected.isTextOnly())
    }

    @Test fun projectionOfATextOnlyMessageReportsSo() {
        val projected = IncludeMessageProjection.userMessageParts(
            "just typing", null
        )
        assertTrue(projected.isTextOnly())
        assertEquals("just typing", projected.text)
    }
}
