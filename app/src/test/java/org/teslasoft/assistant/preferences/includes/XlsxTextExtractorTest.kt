package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading cell values out of a real .xlsx container.
 *
 * The workbooks here are built as genuine zips rather than mocked, so the
 * zip walk, the part lookup, the shared string table and the cell decoding
 * are all exercised the way a file from Google Drive would exercise them.
 */
class XlsxTextExtractorTest {

    // ---- helpers ----------------------------------------------------------

    private fun zip(parts: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun workbookXml(vararg sheets: Triple<String, String, String?>): String {
        val entries = sheets.joinToString("") { (name, relId, state) ->
            val stateAttr = if (state == null) "" else """ state="$state""""
            """<sheet name="$name" sheetId="1"$stateAttr r:id="$relId"/>"""
        }
        return """<?xml version="1.0"?><workbook><sheets>$entries</sheets></workbook>"""
    }

    private fun relsXml(vararg rels: Pair<String, String>): String {
        val entries = rels.joinToString("") { (id, target) ->
            """<Relationship Id="$id" Type="http://x/worksheet" Target="$target"/>"""
        }
        return """<?xml version="1.0"?><Relationships>$entries</Relationships>"""
    }

    private fun sheetXml(rows: String): String =
        """<?xml version="1.0"?><worksheet><sheetData>$rows</sheetData></worksheet>"""

    private fun sharedXml(vararg values: String): String {
        val items = values.joinToString("") { "<si><t>$it</t></si>" }
        return """<?xml version="1.0"?><sst>$items</sst>"""
    }

    private fun success(bytes: ByteArray): XlsxTextExtractor.ExtractResult.Success {
        val result = XlsxTextExtractor.extract(bytes)
        assertTrue("expected success, got $result", result is XlsxTextExtractor.ExtractResult.Success)
        return result as XlsxTextExtractor.ExtractResult.Success
    }

    /** A one-worksheet workbook whose sheet XML is supplied by the caller. */
    private fun oneSheet(rows: String, shared: List<String> = emptyList()): ByteArray {
        val parts = mutableListOf(
            "xl/workbook.xml" to workbookXml(Triple("Sheet1", "rId1", null)),
            "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml" to sheetXml(rows)
        )
        if (shared.isNotEmpty()) {
            parts.add("xl/sharedStrings.xml" to sharedXml(*shared.toTypedArray()))
        }
        return zip(parts)
    }

    // ---- cell values ------------------------------------------------------

    @Test
    fun `shared strings are resolved into their text`() {
        val bytes = oneSheet(
            rows = """<row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>""",
            shared = listOf("Name", "Amount")
        )
        assertEquals(listOf("Name,Amount"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `inline strings are read`() {
        val bytes = oneSheet(
            """<row><c r="A1" t="inlineStr"><is><t>Hello</t></is></c></row>"""
        )
        assertEquals(listOf("Hello"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `numeric cells keep their number`() {
        val bytes = oneSheet("""<row><c r="A1"><v>42</v></c><c r="B1"><v>3.5</v></c></row>""")
        assertEquals(listOf("42,3.5"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `booleans read as TRUE and FALSE`() {
        val bytes = oneSheet(
            """<row><c r="A1" t="b"><v>1</v></c><c r="B1" t="b"><v>0</v></c></row>"""
        )
        assertEquals(listOf("TRUE,FALSE"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `a formula contributes its stored value and never its expression`() {
        val bytes = oneSheet("""<row><c r="A1"><f>SUM(B1:B9)</f><v>17</v></c></row>""")
        val rows = success(bytes).sheets.single().rows
        assertEquals(listOf("17"), rows)
        assertTrue(rows.none { it.contains("SUM") })
    }

    @Test
    fun `a formula with no stored value yields an empty cell not an expression`() {
        val bytes = oneSheet(
            """<row><c r="A1"><f>SUM(B1:B9)</f></c><c r="B1"><v>5</v></c></row>"""
        )
        assertEquals(listOf(",5"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `a skipped column becomes an empty field so values stay under their heading`() {
        val bytes = oneSheet("""<row><c r="A1"><v>1</v></c><c r="C1"><v>3</v></c></row>""")
        assertEquals(listOf("1,,3"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `a row starting past column A keeps its leading gap`() {
        val bytes = oneSheet("""<row><c r="C1"><v>3</v></c></row>""")
        assertEquals(listOf(",,3"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `trailing empty cells are not padded out`() {
        val bytes = oneSheet(
            """<row><c r="A1"><v>1</v></c><c r="B1"/><c r="C1"/></row>"""
        )
        assertEquals(listOf("1"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `values containing a comma or quote are csv quoted`() {
        val bytes = oneSheet(
            rows = """<row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>""",
            shared = listOf("Smith, Jane", "She said \"yes\"")
        )
        assertEquals(
            listOf("\"Smith, Jane\",\"She said \"\"yes\"\"\""),
            success(bytes).sheets.single().rows
        )
    }

    @Test
    fun `xml entities are decoded including numeric ones`() {
        val bytes = oneSheet(
            rows = """<row><c r="A1" t="s"><v>0</v></c></row>""",
            shared = listOf("R&amp;D &lt;core&gt;")
        )
        assertEquals(listOf("R&D <core>"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `a newline inside a cell survives as a quoted line break`() {
        val bytes = oneSheet(
            rows = """<row><c r="A1" t="s"><v>0</v></c></row>""",
            shared = listOf("line one&#10;line two")
        )
        assertEquals(listOf("\"line one\nline two\""), success(bytes).sheets.single().rows)
    }

    @Test
    fun `rich text runs are joined into one value`() {
        val shared = """<?xml version="1.0"?><sst>""" +
            """<si><r><t>Total</t></r><r><t> due</t></r></si></sst>"""
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(Triple("Sheet1", "rId1", null)),
                "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml"),
                "xl/worksheets/sheet1.xml" to
                    sheetXml("""<row><c r="A1" t="s"><v>0</v></c></row>"""),
                "xl/sharedStrings.xml" to shared
            )
        )
        assertEquals(listOf("Total due"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `blank rows are dropped so the first row really is the header`() {
        val bytes = oneSheet(
            """<row><c r="A1"/></row>""" +
                """<row><c r="A2" t="inlineStr"><is><t>Name</t></is></c></row>"""
        )
        assertEquals(listOf("Name"), success(bytes).sheets.single().rows)
    }

    // ---- worksheets -------------------------------------------------------

    @Test
    fun `every worksheet is read in workbook order`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(
                    Triple("Summary", "rId1", null),
                    Triple("Detail", "rId2", null)
                ),
                "xl/_rels/workbook.xml.rels" to relsXml(
                    "rId1" to "worksheets/sheet1.xml",
                    "rId2" to "worksheets/sheet2.xml"
                ),
                "xl/worksheets/sheet1.xml" to sheetXml("""<row><c r="A1"><v>1</v></c></row>"""),
                "xl/worksheets/sheet2.xml" to sheetXml("""<row><c r="A1"><v>2</v></c></row>""")
            )
        )
        val sheets = success(bytes).sheets
        assertEquals(listOf("Summary", "Detail"), sheets.map { it.name })
        assertEquals(listOf("1"), sheets[0].rows)
        assertEquals(listOf("2"), sheets[1].rows)
    }

    @Test
    fun `workbook order wins over the order parts appear in the zip`() {
        // rId1 points at sheet2.xml: a reader that assumed sheet1.xml comes
        // first would silently swap the two worksheets' contents.
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(
                    Triple("First", "rId1", null),
                    Triple("Second", "rId2", null)
                ),
                "xl/_rels/workbook.xml.rels" to relsXml(
                    "rId1" to "worksheets/sheet2.xml",
                    "rId2" to "worksheets/sheet1.xml"
                ),
                "xl/worksheets/sheet1.xml" to
                    sheetXml("""<row><c r="A1" t="inlineStr"><is><t>b</t></is></c></row>"""),
                "xl/worksheets/sheet2.xml" to
                    sheetXml("""<row><c r="A1" t="inlineStr"><is><t>a</t></is></c></row>""")
            )
        )
        val sheets = success(bytes).sheets
        assertEquals(listOf("First", "Second"), sheets.map { it.name })
        assertEquals(listOf("a"), sheets[0].rows)
        assertEquals(listOf("b"), sheets[1].rows)
    }

    @Test
    fun `hidden worksheets are not read`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(
                    Triple("Visible", "rId1", "visible"),
                    Triple("Secret", "rId2", "hidden"),
                    Triple("AlsoSecret", "rId3", "veryHidden")
                ),
                "xl/_rels/workbook.xml.rels" to relsXml(
                    "rId1" to "worksheets/sheet1.xml",
                    "rId2" to "worksheets/sheet2.xml",
                    "rId3" to "worksheets/sheet3.xml"
                ),
                "xl/worksheets/sheet1.xml" to sheetXml("""<row><c r="A1"><v>1</v></c></row>"""),
                "xl/worksheets/sheet2.xml" to sheetXml("""<row><c r="A1"><v>2</v></c></row>"""),
                "xl/worksheets/sheet3.xml" to sheetXml("""<row><c r="A1"><v>3</v></c></row>""")
            )
        )
        assertEquals(listOf("Visible"), success(bytes).sheets.map { it.name })
    }

    @Test
    fun `shared strings written after the worksheets are still resolved`() {
        // This is the normal layout for a real export: the strings the
        // worksheets refer to arrive last in the archive.
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(Triple("Sheet1", "rId1", null)),
                "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml"),
                "xl/worksheets/sheet1.xml" to
                    sheetXml("""<row><c r="A1" t="s"><v>0</v></c></row>"""),
                "xl/sharedStrings.xml" to sharedXml("Resolved")
            )
        )
        assertEquals(listOf("Resolved"), success(bytes).sheets.single().rows)
    }

    @Test
    fun `a worksheet name carrying an entity is decoded`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(Triple("Q1 &amp; Q2", "rId1", null)),
                "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml"),
                "xl/worksheets/sheet1.xml" to sheetXml("""<row><c r="A1"><v>1</v></c></row>""")
            )
        )
        assertEquals("Q1 & Q2", success(bytes).sheets.single().name)
    }

    @Test
    fun `a missing relationship table falls back to worksheet order`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(
                    Triple("One", "rId1", null),
                    Triple("Two", "rId2", null)
                ),
                "xl/worksheets/sheet1.xml" to sheetXml("""<row><c r="A1"><v>1</v></c></row>"""),
                "xl/worksheets/sheet2.xml" to sheetXml("""<row><c r="A1"><v>2</v></c></row>""")
            )
        )
        val sheets = success(bytes).sheets
        assertEquals(listOf("One", "Two"), sheets.map { it.name })
        assertEquals(listOf("1"), sheets[0].rows)
    }

    // ---- unsupported content ---------------------------------------------

    @Test
    fun `charts comments and validation rules are ignored rather than read`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(Triple("Sheet1", "rId1", null)),
                "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml"),
                "xl/worksheets/sheet1.xml" to
                    """<?xml version="1.0"?><worksheet>""" +
                    """<sheetData><row><c r="A1" t="inlineStr"><is><t>kept</t></is></c></row>""" +
                    """</sheetData>""" +
                    """<dataValidations><dataValidation><formula1>"a,b"</formula1>""" +
                    """</dataValidation></dataValidations>""" +
                    """<drawing r:id="rId9"/></worksheet>""",
                "xl/charts/chart1.xml" to "<chart><title>Sales</title></chart>",
                "xl/comments1.xml" to "<comments><comment>a note</comment></comments>"
            )
        )
        assertEquals(listOf("kept"), success(bytes).sheets.single().rows)
    }

    // ---- failures ---------------------------------------------------------

    @Test
    fun `plain bytes that are not a zip are not called a damaged workbook`() {
        assertEquals(
            XlsxTextExtractor.ExtractResult.NotXlsx,
            XlsxTextExtractor.extract("just some text".toByteArray())
        )
    }

    @Test
    fun `a zip with no workbook part is not a workbook`() {
        val bytes = zip(listOf("hello.txt" to "hi", "word/document.xml" to "<w:document/>"))
        assertEquals(XlsxTextExtractor.ExtractResult.NotXlsx, XlsxTextExtractor.extract(bytes))
    }

    @Test
    fun `an encrypted workbook is reported as password protected`() {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
            )
        )
        out.write("EncryptedPackage".toByteArray(Charsets.UTF_16LE))
        out.write("EncryptionInfo".toByteArray(Charsets.UTF_16LE))
        assertEquals(
            XlsxTextExtractor.ExtractResult.PasswordProtected,
            XlsxTextExtractor.extract(out.toByteArray())
        )
    }

    @Test
    fun `a legacy binary spreadsheet is not mistaken for an encrypted one`() {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
            )
        )
        out.write("Workbook".toByteArray(Charsets.UTF_16LE))
        assertEquals(
            XlsxTextExtractor.ExtractResult.NotXlsx,
            XlsxTextExtractor.extract(out.toByteArray())
        )
    }

    @Test
    fun `a workbook whose worksheets are all missing is damaged not unrecognised`() {
        val bytes = zip(
            listOf(
                "xl/workbook.xml" to workbookXml(Triple("Sheet1", "rId1", null)),
                "xl/_rels/workbook.xml.rels" to relsXml("rId1" to "worksheets/sheet1.xml")
            )
        )
        assertEquals(XlsxTextExtractor.ExtractResult.Corrupted, XlsxTextExtractor.extract(bytes))
    }

    @Test
    fun `an empty workbook reports its worksheet with no rows`() {
        val bytes = oneSheet("")
        val sheets = success(bytes).sheets
        assertEquals(1, sheets.size)
        assertTrue(sheets.single().rows.isEmpty())
    }

    // ---- small pieces -----------------------------------------------------

    @Test
    fun `column references map to positions`() {
        assertEquals(0, XlsxTextExtractor.columnIndex("A1"))
        assertEquals(1, XlsxTextExtractor.columnIndex("B12"))
        assertEquals(25, XlsxTextExtractor.columnIndex("Z9"))
        assertEquals(26, XlsxTextExtractor.columnIndex("AA1"))
        assertEquals(27, XlsxTextExtractor.columnIndex("AB1"))
        assertEquals(-1, XlsxTextExtractor.columnIndex("1"))
    }

    @Test
    fun `attributes are read whole and not by prefix`() {
        val body = """sheet name="Budget" sheetId="4" state="visible" r:id="rId7""""
        assertEquals("Budget", XlsxTextExtractor.attribute(body, "name"))
        assertEquals("visible", XlsxTextExtractor.attribute(body, "state"))
        assertEquals("rId7", XlsxTextExtractor.attribute(body, "r:id"))
        assertEquals(null, XlsxTextExtractor.attribute(body, "missing"))
    }
}
