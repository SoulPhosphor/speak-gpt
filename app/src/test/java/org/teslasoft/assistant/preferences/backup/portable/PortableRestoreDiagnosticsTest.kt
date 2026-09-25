/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupCodec
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupFormat
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest
import org.teslasoft.assistant.preferences.backup.companion.CompanionProfileEntry

class PortableRestoreDiagnosticsTest {
    @Test
    fun equalTablesHaveNoDifference() {
        val tables = mapOf("worlds" to listOf(mapOf<String, Any?>("world_id" to "w1", "name" to "Aldra")))
        assertNull(PortableRestoreDiagnostics.tableDifference(tables, tables))
    }

    @Test
    fun valueTypeDifferenceNamesTableColumnAndTypesButNotValues() {
        val expected = mapOf("worlds" to listOf(mapOf<String, Any?>("world_id" to "w1", "weight" to 1.0)))
        val actual = mapOf("worlds" to listOf(mapOf<String, Any?>("world_id" to "w1", "weight" to 1L)))

        assertEquals(
            "table worlds column weight: value type Double vs Long",
            PortableRestoreDiagnostics.tableDifference(expected, actual)
        )
    }

    @Test
    fun rowCountAndColumnSetDifferencesAreNamed() {
        val one = mapOf<String, Any?>("world_id" to "w1", "name" to "Aldra")
        assertEquals(
            "table worlds: row count 1 vs 0",
            PortableRestoreDiagnostics.tableDifference(
                mapOf("worlds" to listOf(one)), mapOf("worlds" to emptyList())
            )
        )
        assertEquals(
            "table worlds: column set differs (missing: name; extra: none)",
            PortableRestoreDiagnostics.tableDifference(
                mapOf("worlds" to listOf(one)),
                mapOf("worlds" to listOf(mapOf<String, Any?>("world_id" to "w1")))
            )
        )
    }

    @Test
    fun manifestDifferenceNamesTheProfileFieldWithoutItsText() {
        val base = manifest(profile(label = "Aria"))
        val changed = manifest(profile(label = "Private Name"))

        val difference = PortableRestoreDiagnostics.manifestDifference(base, changed)

        assertEquals("companion profile field label: value differs (String)", difference)
        assertFalse(difference!!.contains("Aria"))
        assertFalse(difference.contains("Private"))
        assertNull(PortableRestoreDiagnostics.manifestDifference(base, base.copy()))
    }

    @Test
    fun manifestDifferenceReachesRoleplayTables() {
        val base = manifest(profile(label = "Aria"))
        val changed = base.copy(
            roleplayTables = base.roleplayTables + ("worlds" to listOf(mapOf<String, Any?>("world_id" to "w1")))
        )

        assertEquals(
            "table roleplay worlds: row count 0 vs 1",
            PortableRestoreDiagnostics.manifestDifference(base, changed)
        )
    }

    @Test
    fun unexpectedErrorKeepsOnlyTheType() {
        assertEquals(
            "unexpected_error: IllegalArgumentException",
            PortableRestoreDiagnostics.unexpected(IllegalArgumentException("contains private text"))
        )
    }

    @Test
    fun codecNamesTheRuleThatRejectedAManifest() {
        val json = CompanionBackupCodec.toJson(manifest(profile(label = "Aria")))
            .replace(Regex("\"isDefault\"\\s*:\\s*true"), "\"isDefault\": false")

        val (result, detail) = CompanionBackupCodec.parseWithDetail(json)

        assertEquals(CompanionBackupCodec.ParseResult.Damaged, result)
        assertEquals("unsound prompt variants: NO_DEFAULT", detail)
    }

    @Test
    fun codecDoesNotReportTextFromUnreadableJson() {
        val (result, detail) = CompanionBackupCodec.parseWithDetail("{ private text")

        assertEquals(CompanionBackupCodec.ParseResult.Damaged, result)
        assertEquals("manifest is not readable JSON", detail)
    }

    private fun profile(label: String) = CompanionProfileEntry(
        id = "c1",
        label = label,
        prompt = "prompt",
        promptVariants = PortablePromptVariantRules.legacySingleVariant("c1", "prompt"),
        activationPromptId = "",
        coreLoreBookId = "",
        coreLoreBookName = null,
        additionalLoreBookIds = emptyList(),
        additionalLoreBookNames = emptyMap(),
        autoLoadLastLoreBooks = false,
        lastUsedLoreBookIds = emptyList(),
        avatarRef = ""
    )

    private fun manifest(profile: CompanionProfileEntry) = CompanionBackupManifest(
        CompanionBackupFormat.FORMAT_VERSION,
        "test",
        "2026-09-25T00:00:00Z",
        listOf(profile),
        emptyList(),
        emptyList(),
        "",
        CompanionBackupFormat.ROLEPLAY_TABLES.associateWith { emptyList() },
        emptyList()
    )
}
