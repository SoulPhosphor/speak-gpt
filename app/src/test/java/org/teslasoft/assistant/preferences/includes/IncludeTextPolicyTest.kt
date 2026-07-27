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
    }

    @Test fun deferredAndUnsupportedTypesAreRejected() {
        assertNull(IncludeKind.fromFileName("manual.pdf"))
        assertNull(IncludeKind.fromFileName("old.doc"))
        assertNull(IncludeKind.fromFileName("noextension"))
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
