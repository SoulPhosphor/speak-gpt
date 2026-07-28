package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compatibility checks for chats that already contain partial-workbook
 * notices. New imports never create these values.
 */
class WorkbookSizeGuardTest {

    @Test
    fun `the workbook notice survives being stored and read back`() {
        val notice = IncludeNotice.WorkbookTrimmed(
            sheets = 4,
            sentRows = 500,
            totalRows = 47_000
        )
        assertEquals(notice, IncludeNotice.decode(notice.encode()))
    }

    @Test
    fun `a malformed stored workbook notice degrades to no notice`() {
        assertEquals(IncludeNotice.None, IncludeNotice.decode("wb:4:500"))
        assertEquals(IncludeNotice.None, IncludeNotice.decode("wb:x:y:z"))
    }

    @Test
    fun `an existing partial xlsx include round trips without being relabelled`() {
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
    fun `the model is still told what an old partial workbook contains`() {
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
