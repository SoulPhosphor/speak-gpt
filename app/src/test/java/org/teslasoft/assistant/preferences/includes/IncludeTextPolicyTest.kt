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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class IncludeTextPolicyTest {

    private fun charsFor(tokens: Int) = "a".repeat(tokens * 4)

    // ---- token estimate -------------------------------------------------

    @Test fun emptyTextIsZeroTokens() {
        assertEquals(0, IncludeTextPolicy.estimateTokens(""))
    }

    @Test fun tokensRoundUpSoNothingReadsAsFree() {
        assertEquals(1, IncludeTextPolicy.estimateTokens("a"))
        assertEquals(1, IncludeTextPolicy.estimateTokens("abcd"))
        assertEquals(2, IncludeTextPolicy.estimateTokens("abcde"))
    }

    @Test fun nonLatinTextIsNotSeverelyUndercounted() {
        assertEquals(5, IncludeTextPolicy.estimateTokens("漢字かな한"))
        assertEquals(2, IncludeTextPolicy.estimateTokens("😀"))
    }

    // ---- binary guard ---------------------------------------------------

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

    // ---- size guard -----------------------------------------------------

    @Test fun smallFileGoesWholeWithNoNotice() {
        val result = IncludeTextPolicy.applySizeGuard(charsFor(100), IncludeKind.TXT)
        assertEquals(IncludeNotice.None, result.notice)
        assertEquals(charsFor(100), result.text)
    }

    @Test fun fileAtTheLargeThresholdIsStillQuiet() {
        val result = IncludeTextPolicy.applySizeGuard(
            charsFor(IncludeTextPolicy.LARGE_TOKENS), IncludeKind.TXT
        )
        assertEquals(IncludeNotice.None, result.notice)
    }

    @Test fun largeFileIsSentWholeButFlagged() {
        val text = charsFor(IncludeTextPolicy.LARGE_TOKENS + 1000)
        val result = IncludeTextPolicy.applySizeGuard(text, IncludeKind.TXT)
        assertEquals(text, result.text)
        assertTrue(result.notice is IncludeNotice.Large)
    }

    @Test fun oversizeFileIsCutAtTheCapAndSaysSo() {
        val text = charsFor(IncludeTextPolicy.MAX_TOKENS * 2)
        val result = IncludeTextPolicy.applySizeGuard(text, IncludeKind.TXT)
        assertTrue(result.text.length < text.length)
        assertTrue(result.notice is IncludeNotice.Truncated)
        assertTrue(IncludeTextPolicy.estimateTokens(result.text) <= IncludeTextPolicy.MAX_TOKENS)
    }

    // ---- csv --------------------------------------------------------------

    @Test fun shortSpreadsheetIsNotTrimmed() {
        val csv = (0..10).joinToString("\n") { "col$it,value$it" }
        assertNull(IncludeTextPolicy.trimCsv(csv))
    }

    @Test fun oversizeSpreadsheetKeepsHeaderAndReportsTrueTotal() {
        val rows = 15_000
        val csv = buildString {
            append("name,amount")
            repeat(rows) { append("\nrow$it,$it") }
        }
        val result = IncludeTextPolicy.applySizeGuard(csv, IncludeKind.CSV)
        val notice = result.notice
        assertTrue(notice is IncludeNotice.CsvTrimmed)
        notice as IncludeNotice.CsvTrimmed
        assertEquals(IncludeTextPolicy.CSV_MAX_ROWS, notice.sentRows)
        assertEquals(rows, notice.totalRows)
        // The header must survive — a headerless CSV fragment is unreadable.
        assertTrue(result.text.startsWith("name,amount"))
        assertTrue(result.text.contains("row0,0"))
        assertFalse(result.text.contains("row4999,4999"))
    }

    @Test fun largeButNotOversizeSpreadsheetIsSentWhole() {
        val csv = buildString {
            append("name,amount")
            repeat(3_300) { append("\nrow$it,$it") }
        }
        val result = IncludeTextPolicy.applySizeGuard(csv, IncludeKind.CSV)
        assertEquals(csv, result.text)
        assertTrue(result.notice is IncludeNotice.Large)
    }

    @Test fun quotedNewlinesDoNotInflateCsvRowCounts() {
        val csv = buildString {
            append("name,notes")
            repeat(600) { append("\nrow$it,\"line one\nline two\"") }
        }
        val result = IncludeTextPolicy.trimCsv(csv) ?: error("CSV should be trimmed")
        val notice = result.notice as IncludeNotice.CsvTrimmed
        assertEquals(500, notice.sentRows)
        assertEquals(600, notice.totalRows)
        assertTrue(result.text.contains("\"line one\nline two\""))
    }

    @Test fun streamingCsvCounterCountsLogicalRowsAndCrLfOnce() {
        val csv = "name,notes\r\none,\"line one\r\nline two\"\r\ntwo,plain\r\n"
        assertEquals(2, IncludeTextPolicy.countCsvDataRows(StringReader(csv)))
    }

    @Test fun truncatedCsvUsesTheKnownWholeFileRowCount() {
        val prefix = buildString {
            append("name,value")
            repeat(700) { append("\nrow$it,$it") }
            append("\npartial")
        }
        val result = IncludeTextPolicy.applySizeGuard(
            text = prefix,
            kind = IncludeKind.CSV,
            sourceTruncated = true,
            csvTotalRows = 47_000
        )
        val notice = result.notice as IncludeNotice.CsvTrimmed
        assertEquals(500, notice.sentRows)
        assertEquals(47_000, notice.totalRows)
        assertFalse(result.text.contains("partial"))
    }

    @Test fun bigSingleLineCsvFallsBackToPlainTruncation() {
        // One enormous line is not row-shaped, so the row rule cannot apply.
        val csv = charsFor(IncludeTextPolicy.MAX_TOKENS * 2)
        val result = IncludeTextPolicy.applySizeGuard(csv, IncludeKind.CSV)
        assertTrue(result.notice is IncludeNotice.Truncated)
    }

    @Test fun nonLatinTruncationRespectsTheTokenCap() {
        val text = "漢".repeat(IncludeTextPolicy.MAX_TOKENS * 2)
        val result = IncludeTextPolicy.applySizeGuard(text, IncludeKind.TXT)
        assertTrue(result.notice is IncludeNotice.Truncated)
        assertTrue(IncludeTextPolicy.estimateTokens(result.text) <= IncludeTextPolicy.MAX_TOKENS)
    }

    // ---- artifact lines ---------------------------------------------------

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
        val line = IncludeTextPolicy.sanitizeArtifactLine(rambling, "notes.txt", maxWords = 12)
        assertEquals(12, line.trimEnd('.').split(" ").size)
        assertTrue(line.endsWith("."))
    }

    @Test fun shortModelLineIsKeptAsWritten() {
        assertEquals(
            "User sent a photo of an amethyst cluster.",
            IncludeTextPolicy.sanitizeArtifactLine(
                "User sent a photo of an amethyst cluster.", "rock.jpg"
            )
        )
    }

    @Test fun newlinesInAModelLineAreFlattened() {
        val line = IncludeTextPolicy.sanitizeArtifactLine("User sent\n\n  a resume", "a.docx")
        assertEquals("User sent a resume.", line)
    }

    // ---- kind detection ---------------------------------------------------

    @Test fun supportedExtensionsMapToKinds() {
        assertEquals(IncludeKind.TXT, IncludeKind.fromFileName("notes.txt"))
        assertEquals(IncludeKind.MARKDOWN, IncludeKind.fromFileName("README.MD"))
        assertEquals(IncludeKind.CSV, IncludeKind.fromFileName("data.csv"))
        assertEquals(IncludeKind.DOCX, IncludeKind.fromFileName("resume.docx"))
    }

    @Test fun deferredAndUnsupportedTypesAreRejected() {
        // PDF is deliberately deferred; legacy .doc is a different format.
        assertNull(IncludeKind.fromFileName("manual.pdf"))
        assertNull(IncludeKind.fromFileName("old.doc"))
        assertNull(IncludeKind.fromFileName("noextension"))
    }

    // ---- record round-trip -------------------------------------------------

    @Test fun includesSurviveAJsonRoundTrip() {
        val items = listOf(
            ChatInclude(
                id = "inc-1", fileName = "a.txt", kind = IncludeKind.TXT,
                form = IncludeForm.FULL, fullText = "hello",
                notice = IncludeNotice.Large(12345), sentTokens = 7
            ),
            ChatInclude(
                id = "inc-2", fileName = "b.csv", kind = IncludeKind.CSV,
                form = IncludeForm.ARTIFACT, fullText = "x,y",
                artifactLine = "User sent a spreadsheet.",
                notice = IncludeNotice.CsvTrimmed(500, 47000)
            )
        )
        val restored = ChatInclude.listFromJson(ChatInclude.listToJson(items))
        assertEquals(items, restored)
    }

    @Test fun malformedStorageYieldsNothingRatherThanCrashing() {
        assertTrue(ChatInclude.listFromJson("not json at all").isEmpty())
        assertTrue(ChatInclude.listFromJson(null).isEmpty())
        assertTrue(ChatInclude.listFromJson("").isEmpty())
    }

    @Test fun artifactFallsBackWhenItHasNoLineStored() {
        val orphan = ChatInclude(
            id = "inc-3", fileName = "lost.txt", kind = IncludeKind.TXT,
            form = IncludeForm.ARTIFACT, fullText = "body"
        )
        assertEquals("User sent lost.txt.", orphan.modelText())
    }

    @Test fun condensedWithoutTextFallsBackToTheFullDocument() {
        val odd = ChatInclude(
            id = "inc-4", fileName = "n.md", kind = IncludeKind.MARKDOWN,
            form = IncludeForm.CONDENSED, fullText = "the whole thing"
        )
        assertEquals("the whole thing", odd.modelText())
    }

    @Test fun onlyArtifactsLeaveTheStrip() {
        fun withForm(form: IncludeForm) = ChatInclude(
            id = "i", fileName = "f.txt", kind = IncludeKind.TXT,
            form = form, fullText = "t"
        )
        assertTrue(withForm(IncludeForm.FULL).showsInStrip())
        assertTrue(withForm(IncludeForm.CONDENSED).showsInStrip())
        assertFalse(withForm(IncludeForm.ARTIFACT).showsInStrip())
    }

    @Test fun noticeEncodingSurvivesARoundTrip() {
        val cases = listOf(
            IncludeNotice.None,
            IncludeNotice.Large(9),
            IncludeNotice.Truncated(30000),
            IncludeNotice.CsvTrimmed(500, 47000)
        )
        for (c in cases) assertEquals(c, IncludeNotice.decode(c.encode()))
        assertEquals(IncludeNotice.None, IncludeNotice.decode("garbage:::"))
    }

    @Test fun docxIsRecognisedFromItsMimeShapedName() {
        assertNotNull(IncludeKind.fromFileName("Contract v2.docx"))
    }
}
