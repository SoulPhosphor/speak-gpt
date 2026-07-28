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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompleteOfficeTextExtractorTest {

    @Test
    fun xlsxIncludesVisibleAndHiddenWorksheets() {
        val file = workbook(
            relationships = mapOf(
                "rId1" to "worksheets/sheet1.xml",
                "rId2" to "worksheets/sheet2.xml"
            )
        )
        try {
            val result = CompleteOfficeTextExtractor.extractXlsx(
                file,
                ImportMemoryBudget.forTest(32L * 1024L * 1024L),
                OfficeArchiveExpansionGuard(file.length(), 256L * 1024L * 1024L)
            )
            assertTrue(result is CompleteOfficeTextExtractor.Result.Success)
            val text = (result as CompleteOfficeTextExtractor.Result.Success).text
            assertTrue(text.contains("[Sheet: Visible]"))
            assertTrue(text.contains("alpha"))
            assertTrue(text.contains("[Sheet: Hidden]"))
            assertTrue(text.contains("omega"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingWorksheetRelationshipFailsInsteadOfSubstitutingAnotherSheet() {
        val file = workbook(
            relationships = mapOf("rId1" to "worksheets/sheet1.xml")
        )
        try {
            val result = CompleteOfficeTextExtractor.extractXlsx(
                file,
                ImportMemoryBudget.forTest(32L * 1024L * 1024L),
                OfficeArchiveExpansionGuard(file.length(), 256L * 1024L * 1024L)
            )
            assertTrue(result is CompleteOfficeTextExtractor.Result.Corrupted)
        } finally {
            file.delete()
        }
    }

    private fun workbook(relationships: Map<String, String>): File {
        val file = File.createTempFile("complete-workbook-", ".xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.add(
                "xl/workbook.xml",
                """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Visible" sheetId="1" r:id="rId1"/>
                        <sheet name="Hidden" sheetId="2" state="hidden" r:id="rId2"/>
                      </sheets>
                    </workbook>
                """.trimIndent()
            )
            zip.add(
                "xl/_rels/workbook.xml.rels",
                buildString {
                    append(
                        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
                    )
                    for ((id, target) in relationships) {
                        append("""<Relationship Id="$id" Target="$target"/>""")
                    }
                    append("</Relationships>")
                }
            )
            zip.add("xl/worksheets/sheet1.xml", worksheet("alpha"))
            zip.add("xl/worksheets/sheet2.xml", worksheet("omega"))
        }
        return file
    }

    private fun worksheet(value: String): String =
        """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>$value</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()

    private fun ZipOutputStream.add(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
