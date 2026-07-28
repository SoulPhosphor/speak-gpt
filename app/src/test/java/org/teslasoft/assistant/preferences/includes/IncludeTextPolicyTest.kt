/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludeTextPolicyTest {

    @Test fun emptyTextIsZeroTokens() {
        assertEquals(0, IncludeTextPolicy.estimateTokens(""))
    }

    @Test fun approximateTokensRoundUpSoNothingReadsAsFree() {
        assertEquals(1, IncludeTextPolicy.estimateTokens("a"))
        assertEquals(1, IncludeTextPolicy.estimateTokens("abcd"))
        assertEquals(2, IncludeTextPolicy.estimateTokens("abcde"))
    }

    @Test fun nonLatinTextIsNotSeverelyUndercounted() {
        assertEquals(5, IncludeTextPolicy.estimateTokens("漢字かな한"))
        assertEquals(2, IncludeTextPolicy.estimateTokens("😀"))
    }

    @Test fun ordinaryTextPassesTheGuard() {
        assertTrue(IncludeTextPolicy.looksLikeText("Hello world.\nSecond line\twith a tab.\r\n"))
    }

    @Test fun emptyTextIsNotUsableText() {
        assertFalse(IncludeTextPolicy.looksLikeText(""))
    }

    @Test fun binaryContentIsRefused() {
        val binary = buildString {
            repeat(200) { append("text") }
            repeat(200) { append('\u0000').append('\u0001').append('\u0007') }
        }
        assertFalse(IncludeTextPolicy.looksLikeText(binary))
    }

    @Test fun aStrayControlCharacterDoesNotCondemnARealDocument() {
        val mostlyText = "A perfectly ordinary document. ".repeat(50) + "\u0001"
        assertTrue(IncludeTextPolicy.looksLikeText(mostlyText))
    }

    @Test fun missingModelLineFallsBackToTheFileName() {
        assertEquals(
            "User sent resume.docx.",
            IncludeTextPolicy.sanitizeArtifactLine(null, "resume.docx")
        )
        assertEquals(
            "User sent resume.docx.",
            IncludeTextPolicy.sanitizeArtifactLine("   ", "resume.docx")
        )
    }

    @Test fun overlongModelLineIsClipped() {
        val rambling = "User sent a very detailed and unusually long description " +
            "that simply keeps going well past any reasonable bookmark length"
        val line = IncludeTextPolicy.sanitizeArtifactLine(
            rambling,
            "notes.txt",
            maxWords = 12
        )
        assertEquals(12, line.trimEnd('.').split(" ").size)
        assertTrue(line.endsWith("."))
    }

    @Test fun newlinesInAModelLineAreFlattened() {
        val line = IncludeTextPolicy.sanitizeArtifactLine(
            "User sent\n\n  a resume",
            "a.docx"
        )
        assertEquals("User sent a resume.", line)
    }

    @Test fun artifactReminderKeepsAtMostThreeSentences() {
        val line = IncludeTextPolicy.sanitizeArtifactLine(
            "One. Two! Three? Four.",
            "a.docx"
        )
        assertEquals("One. Two! Three?", line)
    }

    @Test fun supportedExtensionsMapToKinds() {
        assertEquals(IncludeKind.TXT, IncludeKind.fromFileName("notes.txt"))
        assertEquals(IncludeKind.MARKDOWN, IncludeKind.fromFileName("README.MD"))
        assertEquals(IncludeKind.CSV, IncludeKind.fromFileName("data.csv"))
        assertEquals(IncludeKind.DOCX, IncludeKind.fromFileName("resume.docx"))
        assertEquals(IncludeKind.XLSX, IncludeKind.fromFileName("budget.xlsx"))
        assertEquals(IncludeKind.JPEG, IncludeKind.fromFileName("Photo 07-27-26.jpg"))
        assertEquals(IncludeKind.JPEG, IncludeKind.fromFileName("Screenshot.JPEG"))
        assertEquals(IncludeKind.PNG, IncludeKind.fromFileName("chart.png"))
    }

    @Test fun deferredAndUnsupportedTypesAreRejected() {
        assertNull(IncludeKind.fromFileName("manual.pdf"))
        assertNull(IncludeKind.fromFileName("old.doc"))
        assertNull(IncludeKind.fromFileName("noextension"))
        // HEIC is converted to JPEG at import time, so a raw .heic name is not
        // a stored kind and never round-trips through this mapping.
        assertNull(IncludeKind.fromFileName("photo.heic"))
        assertNull(IncludeKind.fromFileName("motion.gif"))
    }

    @Test fun imageKindsClassifyAsImages() {
        assertTrue(IncludeKind.JPEG.isImage())
        assertTrue(IncludeKind.PNG.isImage())
        assertFalse(IncludeKind.TXT.isImage())
        assertFalse(IncludeKind.DOCX.isImage())
    }

    @Test fun imageTokenEstimateHasAFloor() {
        // A trivially small image still costs a base amount to send, so the
        // ~N tokens reading never claims "0" for a real attachment.
        assertEquals(IncludeTextPolicy.IMAGE_TOKEN_FLOOR,
            IncludeTextPolicy.estimateImageTokens(1, 1))
        assertEquals(IncludeTextPolicy.IMAGE_TOKEN_FLOOR,
            IncludeTextPolicy.estimateImageTokens(0, 0))
    }

    @Test fun imageTokenEstimateScalesWithPixels() {
        // Post-downsample cap of 2048 longest edge yields ~5.6k tokens at
        // 2048x2048, ~1k around 1024x768. The exact numbers matter less than
        // the property that a larger image reads as heavier than a smaller one.
        val small = IncludeTextPolicy.estimateImageTokens(512, 512)
        val medium = IncludeTextPolicy.estimateImageTokens(1024, 768)
        val large = IncludeTextPolicy.estimateImageTokens(2048, 2048)
        assertTrue(small < medium)
        assertTrue(medium < large)
    }

    @Test fun imageIncludeUsesDimensionEstimateInFullForm() {
        val image = ChatInclude(
            id = "img-1",
            fileName = "Photo 07-27-26 14-32.jpg",
            kind = IncludeKind.JPEG,
            form = IncludeForm.FULL,
            fullText = "",
            imageFileHash = "abc123",
            imageMimeType = "image/jpeg",
            imageWidth = 1024,
            imageHeight = 768
        )
        assertEquals(
            IncludeTextPolicy.estimateImageTokens(1024, 768),
            image.currentTokens()
        )
        // A FULL image contributes nothing on the TEXT side; its content is a
        // separate image part the caller emits.
        assertEquals("", image.modelText())
    }

    @Test fun reducedImageBehavesLikeCondensedText() {
        val reduced = ChatInclude(
            id = "img-2",
            fileName = "chart.png",
            kind = IncludeKind.PNG,
            form = IncludeForm.CONDENSED,
            fullText = "",
            condensedText = "A bar chart showing 2024 quarterly revenue."
        )
        assertEquals(
            "A bar chart showing 2024 quarterly revenue.",
            reduced.modelText()
        )
    }

    @Test fun imageBytesReferenceIsClearedOnceGone() {
        val image = ChatInclude(
            id = "img-3",
            fileName = "photo.jpg",
            kind = IncludeKind.JPEG,
            form = IncludeForm.FULL,
            fullText = "",
            imageFileHash = "abc123",
            imageMimeType = "image/jpeg",
            imageWidth = 1024,
            imageHeight = 768
        )
        assertTrue(image.hasLiveImageBytes())
        val gone = image.withoutImageBytes()
        assertNull(gone.imageFileHash)
        assertEquals(0, gone.imageWidth)
        assertFalse(gone.hasLiveImageBytes())
    }

    @Test fun imageIncludeRoundTripsThroughJson() {
        val image = ChatInclude(
            id = "img-4",
            fileName = "Photo 07-27-26 14-32.jpg",
            kind = IncludeKind.JPEG,
            form = IncludeForm.FULL,
            fullText = "",
            imageFileHash = "hash-abc",
            imageMimeType = "image/jpeg",
            imageWidth = 1600,
            imageHeight = 1200
        )
        val restored = ChatInclude.listFromJson(
            ChatInclude.listToJson(listOf(image))
        ).single()
        assertEquals(image, restored)
    }

    @Test fun oldPartialIncludesSurviveAJsonRoundTrip() {
        val items = listOf(
            ChatInclude(
                id = "inc-1",
                fileName = "a.txt",
                kind = IncludeKind.TXT,
                form = IncludeForm.FULL,
                fullText = "hello",
                sentTokens = 7,
                sourceFingerprint = "source-hash"
            ),
            ChatInclude(
                id = "inc-2",
                fileName = "b.csv",
                kind = IncludeKind.CSV,
                form = IncludeForm.ARTIFACT,
                fullText = "x,y",
                artifactLine = "User sent a spreadsheet.",
                notice = IncludeNotice.CsvTrimmed(500, 47_000)
            )
        )
        assertEquals(items, ChatInclude.listFromJson(ChatInclude.listToJson(items)))
    }

    @Test fun malformedStorageYieldsNothingRatherThanCrashing() {
        assertTrue(ChatInclude.listFromJson("not json at all").isEmpty())
        assertTrue(ChatInclude.listFromJson(null).isEmpty())
        assertTrue(ChatInclude.listFromJson("").isEmpty())
    }

    @Test fun artifactFallsBackWhenItHasNoLineStored() {
        val orphan = ChatInclude(
            id = "inc-3",
            fileName = "lost.txt",
            kind = IncludeKind.TXT,
            form = IncludeForm.ARTIFACT,
            fullText = "body"
        )
        assertEquals("User sent lost.txt.", orphan.modelText())
    }

    @Test fun condensedWithoutTextFallsBackToTheFullDocument() {
        val odd = ChatInclude(
            id = "inc-4",
            fileName = "n.md",
            kind = IncludeKind.MARKDOWN,
            form = IncludeForm.CONDENSED,
            fullText = "the whole thing"
        )
        assertEquals("the whole thing", odd.modelText())
    }

    @Test fun onlyArtifactsLeaveTheStrip() {
        fun withForm(form: IncludeForm) = ChatInclude(
            id = "i",
            fileName = "f.txt",
            kind = IncludeKind.TXT,
            form = form,
            fullText = "t"
        )
        assertTrue(withForm(IncludeForm.FULL).showsInStrip())
        assertTrue(withForm(IncludeForm.CONDENSED).showsInStrip())
        assertFalse(withForm(IncludeForm.ARTIFACT).showsInStrip())
    }

    @Test fun legacyNoticeEncodingSurvivesARoundTrip() {
        val cases = listOf(
            IncludeNotice.None,
            IncludeNotice.Truncated(30_000),
            IncludeNotice.CsvTrimmed(500, 47_000),
            IncludeNotice.WorkbookTrimmed(4, 500, 47_000)
        )
        for (case in cases) assertEquals(case, IncludeNotice.decode(case.encode()))
        assertEquals(IncludeNotice.None, IncludeNotice.decode("large:9000"))
        assertEquals(IncludeNotice.None, IncludeNotice.decode("garbage:::"))
    }

    @Test fun sentSnapshotDropsThePendingSourceFingerprint() {
        val pending = ChatInclude(
            id = "inc-5",
            fileName = "notes.txt",
            kind = IncludeKind.TXT,
            form = IncludeForm.FULL,
            fullText = "hello",
            sourceFingerprint = "source-hash"
        )
        val sent = pending.forSentMessage()
        assertNull(sent.sourceFingerprint)
        assertEquals(pending.currentTokens(), sent.sentTokens)
    }

    @Test fun sourceFingerprintMatchesOnlyTheSameDocumentIdentity() {
        val first = DocumentImporter.sourceFingerprint("content://drive/document/123")
        assertEquals(first, DocumentImporter.sourceFingerprint("content://drive/document/123"))
        assertFalse(first == DocumentImporter.sourceFingerprint("content://drive/document/456"))
    }

    @Test fun docxIsRecognisedFromItsMimeShapedName() {
        assertNotNull(IncludeKind.fromFileName("Contract v2.docx"))
    }
}
