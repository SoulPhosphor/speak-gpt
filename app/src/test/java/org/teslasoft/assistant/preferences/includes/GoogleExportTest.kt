package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which format a Google-native file is converted into, and what the
 * attachment ends up being called.
 */
class GoogleExportTest {

    private val docxOffered = arrayOf(
        "application/pdf",
        GoogleExport.DOCX_MIME,
        "text/plain"
    )

    private val xlsxOffered = arrayOf(
        "text/csv",
        GoogleExport.XLSX_MIME,
        "application/pdf"
    )

    // ---- export selection -------------------------------------------------

    @Test
    fun `google doc exports as docx`() {
        val export = GoogleExport.chooseExport(GoogleExport.NATIVE_DOCUMENT, docxOffered)
        assertEquals(GoogleExport.DOCX_MIME, export?.mimeType)
        assertEquals("docx", export?.extension)
    }

    @Test
    fun `google sheet exports as xlsx not csv`() {
        val export = GoogleExport.chooseExport(GoogleExport.NATIVE_SPREADSHEET, xlsxOffered)
        assertEquals(GoogleExport.XLSX_MIME, export?.mimeType)
        assertEquals("xlsx", export?.extension)
    }

    @Test
    fun `export is refused when the wanted format is not offered`() {
        assertNull(
            GoogleExport.chooseExport(
                GoogleExport.NATIVE_DOCUMENT,
                arrayOf("application/pdf", "text/plain")
            )
        )
    }

    @Test
    fun `export is refused when nothing is offered`() {
        assertNull(GoogleExport.chooseExport(GoogleExport.NATIVE_DOCUMENT, emptyArray()))
        assertNull(GoogleExport.chooseExport(GoogleExport.NATIVE_DOCUMENT, null))
    }

    @Test
    fun `a sheet is not satisfied by the document format and vice versa`() {
        assertNull(GoogleExport.chooseExport(GoogleExport.NATIVE_SPREADSHEET, docxOffered))
        assertNull(GoogleExport.chooseExport(GoogleExport.NATIVE_DOCUMENT, xlsxOffered))
    }

    @Test
    fun `offered types are matched ignoring case and surrounding space`() {
        val export = GoogleExport.chooseExport(
            GoogleExport.NATIVE_DOCUMENT,
            arrayOf("  " + GoogleExport.DOCX_MIME.uppercase() + " ")
        )
        assertEquals(GoogleExport.DOCX_MIME, export?.mimeType)
    }

    @Test
    fun `slides never selects an export`() {
        assertNull(
            GoogleExport.chooseExport(
                GoogleExport.NATIVE_PRESENTATION,
                arrayOf(GoogleExport.DOCX_MIME, GoogleExport.XLSX_MIME)
            )
        )
    }

    @Test
    fun `ordinary files never take the export path`() {
        assertNull(GoogleExport.chooseExport("text/plain", docxOffered))
        assertNull(GoogleExport.chooseExport(null, docxOffered))
    }

    // ---- supported and unsupported native types ---------------------------

    @Test
    fun `docs and sheets are supported native types`() {
        assertTrue(GoogleExport.isSupportedNative(GoogleExport.NATIVE_DOCUMENT))
        assertTrue(GoogleExport.isSupportedNative(GoogleExport.NATIVE_SPREADSHEET))
    }

    @Test
    fun `slides is a known unsupported native type`() {
        assertTrue(GoogleExport.isUnsupportedNative(GoogleExport.NATIVE_PRESENTATION))
        assertFalse(GoogleExport.isSupportedNative(GoogleExport.NATIVE_PRESENTATION))
    }

    @Test
    fun `other google native types are also refused rather than attempted`() {
        assertTrue(GoogleExport.isUnsupportedNative("application/vnd.google-apps.form"))
        assertTrue(GoogleExport.isUnsupportedNative("application/vnd.google-apps.drawing"))
    }

    @Test
    fun `ordinary types are neither supported nor unsupported natives`() {
        assertFalse(GoogleExport.isUnsupportedNative("text/plain"))
        assertFalse(GoogleExport.isUnsupportedNative(GoogleExport.DOCX_MIME))
        assertFalse(GoogleExport.isUnsupportedNative(null))
        assertFalse(GoogleExport.isSupportedNative(null))
    }

    @Test
    fun `slides is not offered to the picker`() {
        assertFalse(GoogleExport.PICKER_MIME_TYPES.contains(GoogleExport.NATIVE_PRESENTATION))
        assertTrue(GoogleExport.PICKER_MIME_TYPES.contains(GoogleExport.NATIVE_DOCUMENT))
        assertTrue(GoogleExport.PICKER_MIME_TYPES.contains(GoogleExport.NATIVE_SPREADSHEET))
    }

    // ---- exported file name -----------------------------------------------

    private val docx = GoogleExport.Export(GoogleExport.DOCX_MIME, "docx")
    private val xlsx = GoogleExport.Export(GoogleExport.XLSX_MIME, "xlsx")

    @Test
    fun `an extensionless google doc name gains docx`() {
        assertEquals("Quarterly Plan.docx", GoogleExport.exportedFileName("Quarterly Plan", docx))
    }

    @Test
    fun `an extensionless google sheet name gains xlsx`() {
        assertEquals("Budget 2026.xlsx", GoogleExport.exportedFileName("Budget 2026", xlsx))
    }

    @Test
    fun `a name that already ends in the extension is left alone`() {
        assertEquals("Report.docx", GoogleExport.exportedFileName("Report.docx", docx))
        assertEquals("Report.DOCX", GoogleExport.exportedFileName("Report.DOCX", docx))
    }

    @Test
    fun `a different extension is kept and the real one appended`() {
        // "Notes.2024" is a name, not a file type — truncating at the dot
        // would rename the user's document behind their back.
        assertEquals("Notes.2024.docx", GoogleExport.exportedFileName("Notes.2024", docx))
    }

    @Test
    fun `a blank name still produces something openable`() {
        assertEquals("document.docx", GoogleExport.exportedFileName("   ", docx))
        assertEquals("document.xlsx", GoogleExport.exportedFileName("", xlsx))
    }

    @Test
    fun `the exported name resolves to the matching include kind`() {
        assertEquals(
            IncludeKind.DOCX,
            IncludeKind.fromFileName(GoogleExport.exportedFileName("Plan", docx))
        )
        assertEquals(
            IncludeKind.XLSX,
            IncludeKind.fromFileName(GoogleExport.exportedFileName("Budget", xlsx))
        )
    }
}
