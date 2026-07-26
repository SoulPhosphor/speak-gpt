package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a workbook is rendered as text, and how the row budget is spent across
 * its worksheets when it is too large to send whole.
 */
class WorkbookSizeGuardTest {

    private fun sheet(name: String, dataRows: Int, prefix: String = "r"): XlsxTextExtractor.SheetData {
        val rows = ArrayList<String>(dataRows + 1)
        rows.add("col1,col2")
        for (i in 1..dataRows) rows.add("$prefix$i,value$i")
        return XlsxTextExtractor.SheetData(name, rows)
    }

    /**
     * Rows wide enough that the workbook exceeds the token cap, which is what
     * puts the row limit into play at all — a narrow workbook of many rows is
     * still sent whole, exactly as a narrow CSV is.
     */
    private fun wideSheet(name: String, dataRows: Int): XlsxTextExtractor.SheetData {
        val padding = "x".repeat(140)
        val rows = ArrayList<String>(dataRows + 1)
        rows.add("col1,col2")
        for (i in 1..dataRows) rows.add("$i,$padding")
        return XlsxTextExtractor.SheetData(name, rows)
    }

    @Test
    fun `a workbook of many narrow rows is still sent whole`() {
        // The row limit is a consequence of the size cap, not a rule of its
        // own — matching how a CSV behaves.
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(listOf(sheet("A", 900)))
        assertEquals(IncludeNotice.None, sized.notice)
    }

    // ---- rendering --------------------------------------------------------

    @Test
    fun `each worksheet is labelled in the rendered text`() {
        val text = IncludeTextPolicy.renderWorkbook(
            listOf(sheet("Summary", 1), sheet("Detail", 1))
        )
        assertTrue(text.contains("[Sheet: Summary]"))
        assertTrue(text.contains("[Sheet: Detail]"))
    }

    @Test
    fun `rendering keeps worksheets in order and separated`() {
        val text = IncludeTextPolicy.renderWorkbook(
            listOf(sheet("First", 1, "a"), sheet("Second", 1, "b"))
        )
        assertTrue(text.indexOf("[Sheet: First]") < text.indexOf("[Sheet: Second]"))
        assertEquals(
            "[Sheet: First]\ncol1,col2\na1,value1\n\n[Sheet: Second]\ncol1,col2\nb1,value1",
            text
        )
    }

    @Test
    fun `an empty worksheet still gets its label`() {
        val text = IncludeTextPolicy.renderWorkbook(
            listOf(XlsxTextExtractor.SheetData("Blank", emptyList()))
        )
        assertEquals("[Sheet: Blank]", text)
    }

    // ---- counting ---------------------------------------------------------

    @Test
    fun `data rows are counted across the workbook excluding headers`() {
        val sheets = listOf(sheet("A", 10), sheet("B", 5))
        assertEquals(15, IncludeTextPolicy.countWorkbookDataRows(sheets))
    }

    @Test
    fun `a worksheet holding only a header contributes no data rows`() {
        val sheets = listOf(XlsxTextExtractor.SheetData("A", listOf("col1,col2")))
        assertEquals(0, IncludeTextPolicy.countWorkbookDataRows(sheets))
    }

    // ---- the workbook-wide row budget ------------------------------------

    @Test
    fun `a workbook within the row limit is not trimmed`() {
        assertNull(IncludeTextPolicy.trimWorkbook(listOf(sheet("A", 100), sheet("B", 100))))
    }

    @Test
    fun `the limit applies to the whole workbook not to each worksheet`() {
        // Three worksheets of 300 rows each. A per-worksheet limit would send
        // 900 rows; the workbook-wide limit sends 500.
        val sheets = listOf(sheet("A", 300), sheet("B", 300), sheet("C", 300))
        val trim = IncludeTextPolicy.trimWorkbook(sheets)!!
        assertEquals(500, trim.sentRows)
        assertEquals(900, trim.totalRows)
        assertEquals(3, trim.sheetsTotal)
    }

    @Test
    fun `the budget is spent in workbook order`() {
        val sheets = listOf(sheet("A", 300, "a"), sheet("B", 300, "b"), sheet("C", 300, "c"))
        val trim = IncludeTextPolicy.trimWorkbook(sheets)!!
        // A is exhausted first, B takes the remaining 200, C is never reached.
        assertTrue(trim.text.contains("a300,value300"))
        assertTrue(trim.text.contains("b200,value200"))
        assertTrue(!trim.text.contains("b201,value201"))
        assertTrue(!trim.text.contains("[Sheet: C]"))
    }

    @Test
    fun `every worksheet reached keeps its label and header row`() {
        val sheets = listOf(sheet("A", 300), sheet("B", 300), sheet("C", 300))
        val trim = IncludeTextPolicy.trimWorkbook(sheets)!!
        assertTrue(trim.text.contains("[Sheet: A]"))
        assertTrue(trim.text.contains("[Sheet: B]"))
        assertEquals(2, Regex("col1,col2").findAll(trim.text).count())
    }

    @Test
    fun `one oversized first worksheet spends the whole budget`() {
        // Documented consequence of a workbook-wide budget: the later
        // worksheets are not reached, and the notice has to say so.
        val sheets = listOf(sheet("Big", 5_000), sheet("Small", 3))
        val trim = IncludeTextPolicy.trimWorkbook(sheets)!!
        assertEquals(500, trim.sentRows)
        assertEquals(5_003, trim.totalRows)
        assertEquals(2, trim.sheetsTotal)
        assertTrue(!trim.text.contains("[Sheet: Small]"))
    }

    @Test
    fun `a worksheet with no rows does not consume budget`() {
        val sheets = listOf(
            XlsxTextExtractor.SheetData("Empty", emptyList()),
            sheet("Data", 600)
        )
        val trim = IncludeTextPolicy.trimWorkbook(sheets)!!
        assertEquals(500, trim.sentRows)
        assertTrue(trim.text.contains("[Sheet: Empty]"))
        assertTrue(trim.text.contains("[Sheet: Data]"))
    }

    // ---- the size guard as a whole ---------------------------------------

    @Test
    fun `a small workbook is sent whole with no notice`() {
        val sheets = listOf(sheet("A", 3), sheet("B", 2))
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(sheets)
        assertEquals(IncludeNotice.None, sized.notice)
        assertEquals(IncludeTextPolicy.renderWorkbook(sheets), sized.text)
    }

    @Test
    fun `an oversized multi worksheet workbook reports all three counts`() {
        val sheets = listOf(wideSheet("A", 400), wideSheet("B", 400), wideSheet("C", 400))
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(sheets)
        val notice = sized.notice as IncludeNotice.WorkbookTrimmed
        assertEquals(3, notice.sheets)
        assertEquals(500, notice.sentRows)
        assertEquals(1_200, notice.totalRows)
    }

    @Test
    fun `a single worksheet workbook uses the existing spreadsheet notice`() {
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(listOf(wideSheet("Only", 900)))
        val notice = sized.notice as IncludeNotice.CsvTrimmed
        assertEquals(500, notice.sentRows)
        assertEquals(900, notice.totalRows)
    }

    @Test
    fun `a trimmed workbook that is still too heavy falls back to a plain cut`() {
        val wide = "x".repeat(4_000)
        val rows = ArrayList<String>()
        rows.add("header")
        for (i in 1..600) rows.add(wide)
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(
            listOf(XlsxTextExtractor.SheetData("Wide", rows))
        )
        assertTrue(sized.notice is IncludeNotice.Truncated)
        assertTrue(IncludeTextPolicy.estimateTokens(sized.text) <= IncludeTextPolicy.MAX_TOKENS)
    }

    @Test
    fun `a workbook cut short at the byte cap is never reported as complete`() {
        val sized = IncludeTextPolicy.applyWorkbookSizeGuard(
            sheets = listOf(sheet("A", 2)),
            sourceTruncated = true
        )
        assertTrue(sized.notice != IncludeNotice.None)
    }

    // ---- persistence ------------------------------------------------------

    @Test
    fun `the workbook notice survives being stored and read back`() {
        val notice = IncludeNotice.WorkbookTrimmed(sheets = 4, sentRows = 500, totalRows = 47_000)
        assertEquals(notice, IncludeNotice.decode(notice.encode()))
    }

    @Test
    fun `a malformed stored workbook notice degrades to no notice`() {
        assertEquals(IncludeNotice.None, IncludeNotice.decode("wb:4:500"))
        assertEquals(IncludeNotice.None, IncludeNotice.decode("wb:x:y:z"))
    }

    @Test
    fun `an xlsx include round trips through storage`() {
        val include = ChatInclude(
            id = "inc-1",
            fileName = "Budget.xlsx",
            kind = IncludeKind.XLSX,
            form = IncludeForm.FULL,
            fullText = "[Sheet: A]\ncol1\n1",
            notice = IncludeNotice.WorkbookTrimmed(2, 500, 900)
        )
        val restored = ChatInclude.fromJson(include.toJson())!!
        assertEquals(IncludeKind.XLSX, restored.kind)
        assertEquals(include.notice, restored.notice)
        assertEquals(include.fileName, restored.fileName)
    }

    @Test
    fun `the model is told the worksheet and row counts of a trimmed workbook`() {
        val rendered = IncludeRenderer.renderUserMessage(
            "",
            listOf(
                ChatInclude(
                    id = "inc-1",
                    fileName = "Budget.xlsx",
                    kind = IncludeKind.XLSX,
                    form = IncludeForm.FULL,
                    fullText = "[Sheet: A]\ncol1\n1",
                    notice = IncludeNotice.WorkbookTrimmed(4, 500, 47_000)
                )
            )
        )
        assertTrue(rendered.contains("sheets=\"4\""))
        assertTrue(rendered.contains("rows=\"header + first 500 of 47000\""))
    }

    @Test
    fun `xlsx is recognised from a file name`() {
        assertEquals(IncludeKind.XLSX, IncludeKind.fromFileName("Budget.xlsx"))
        assertEquals(IncludeKind.XLSX, IncludeKind.fromFileName("Budget.XLSX"))
        assertNull(IncludeKind.fromFileName("Budget.xls"))
    }
}
