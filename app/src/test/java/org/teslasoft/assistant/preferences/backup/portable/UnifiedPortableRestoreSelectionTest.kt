/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Stage 1 selection-aware restore: only whole-package integrity is checked
 * before the selection, and a selected category whose own data is rejected is
 * set aside while every other selected category is still planned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UnifiedPortableRestoreSelectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun packageCheckDoesNotReadCategoryContent() {
        val result = PortableRecoverySemanticValidator.validatePackage(
            listOf(malformedChats(), settingsArtifact())
        )

        assertTrue(result is PortableRecoverySemanticValidator.Result.Valid)
        val available = (result as PortableRecoverySemanticValidator.Result.Valid).inventory.available
        assertTrue(PortableRestoreCategory.CHATS in available)
        assertTrue(PortableRestoreCategory.SETTINGS in available)
    }

    @Test
    fun unselectedMalformedChatsDoNotAffectSettings() {
        val built = build(listOf(select(PortableRestoreCategory.SETTINGS, PortableRestoreMode.REPLACE)))

        assertTrue(built is UnifiedPortableRestore.BuildResult.Ready)
        built as UnifiedPortableRestore.BuildResult.Ready
        assertTrue(built.categoryFailures.isEmpty())
        assertEquals(1, built.participants.count { it is AppSettingsRestoreParticipant })
    }

    @Test
    fun selectedMalformedChatsAreSetAsideAndSettingsStillRestore() {
        val built = build(listOf(
            select(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE),
            select(PortableRestoreCategory.SETTINGS, PortableRestoreMode.REPLACE)
        ))

        assertTrue(built is UnifiedPortableRestore.BuildResult.Ready)
        built as UnifiedPortableRestore.BuildResult.Ready
        assertEquals(
            listOf(UnifiedPortableRestore.CategoryFailure(
                PortableRestoreCategory.CHATS,
                UnifiedPortableRestore.CategoryFailureReason.CHAT_DATA,
                PortableChatRestorePlan.Reason.MALFORMED
            )),
            built.categoryFailures
        )
        assertEquals(1, built.participants.count { it is AppSettingsRestoreParticipant })
        assertTrue(built.participants.none { it is ChatRestoreParticipant })
        assertEquals(setOf(PortableRestoreCategory.SETTINGS), built.finalState.categoryModes.keys)
    }

    @Test
    fun nothingRestorableWhenEverySelectedCategoryFails() {
        val built = build(listOf(select(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)))

        assertTrue(built is UnifiedPortableRestore.BuildResult.NothingRestorable)
        assertEquals(
            listOf(PortableRestoreCategory.CHATS),
            (built as UnifiedPortableRestore.BuildResult.NothingRestorable).failures.map { it.category }
        )
    }

    @Test
    fun declaredCountIsCheckedOnlyForTheSelectedCategory() {
        val settings = settingsArtifact()
        val count = (AppSettingsPortableCodec.parse(settings.stagedFile.readText()) as
            AppSettingsPortableCodec.Result.Ok).data.recordCount
        val built = UnifiedPortableRestore.build(
            context,
            listOf(malformedChats(), settings),
            UnifiedPortableRestore.Request(
                listOf(select(PortableRestoreCategory.SETTINGS, PortableRestoreMode.REPLACE)),
                declaredRecordCounts = mapOf(
                    PortableRestoreCategory.CHATS to 5L,
                    PortableRestoreCategory.SETTINGS to count
                )
            ),
            tmp.newFolder()
        )

        assertTrue(built is UnifiedPortableRestore.BuildResult.Ready)
        assertTrue((built as UnifiedPortableRestore.BuildResult.Ready).categoryFailures.isEmpty())
    }

    @Test
    fun promptIsShortenedToItsFirstTenWords() {
        assertEquals(
            "one two three four five six seven eight nine ten…",
            PortableRestoreIssueText.firstWords(
                "one two  three four five six seven eight nine ten eleven", 10
            )
        )
        assertEquals("short prompt", PortableRestoreIssueText.firstWords(" short prompt ", 10))
    }

    private fun build(selections: List<PortableRestoreSelectionPlan.Selection>) =
        UnifiedPortableRestore.build(
            context,
            listOf(malformedChats(), settingsArtifact()),
            UnifiedPortableRestore.Request(selections),
            tmp.newFolder()
        )

    private fun select(category: PortableRestoreCategory, mode: PortableRestoreMode) =
        PortableRestoreSelectionPlan.Selection(category, mode)

    private fun malformedChats() = artifact(
        "chats.json", PortablePackage.TYPE_CHATS_JSON, "not-json".toByteArray()
    )

    private fun settingsArtifact(): PortablePackage.ValidatedArtifact {
        val current = AppSettingsPortableStore.capture(context).getOrThrow()
        return artifact(
            AppSettingsPortableCodec.ENTRY_NAME,
            PortablePackage.TYPE_APP_SETTINGS,
            AppSettingsPortableCodec.encode(current).toByteArray(),
            schemaVersion = AppSettingsPortableCodec.SCHEMA_VERSION
        )
    }

    private fun artifact(
        entryName: String,
        type: String,
        bytes: ByteArray,
        schemaVersion: Int? = null
    ): PortablePackage.ValidatedArtifact = PortablePackage.ValidatedArtifact(
        entryName,
        type,
        tmp.newFile("artifact_${System.nanoTime()}").apply { writeBytes(bytes) },
        null,
        null,
        schemaVersion
    )
}
