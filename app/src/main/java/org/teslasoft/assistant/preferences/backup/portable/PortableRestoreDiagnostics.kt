/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupManifest

/**
 * Error Log detail for restore failures (owner-approved, September 2026).
 * Everything produced here names only restore steps, categories, reason
 * codes, table and column names, field names, counts and value types. It
 * never contains a stored value, a name, a prompt, message or memory text, a
 * credential, or a record ID.
 */
internal object PortableRestoreDiagnostics {

    /** The type of an error nobody expected, without its message (messages
     * can quote the data being read). */
    fun unexpected(error: Throwable): String = "unexpected_error: ${error.javaClass.simpleName}"

    /** First difference between two table snapshots, or null when equal. */
    fun tableDifference(
        expected: Map<String, List<Map<String, Any?>>>,
        actual: Map<String, List<Map<String, Any?>>>,
        prefix: String = ""
    ): String? {
        if (expected == actual) return null
        if (expected.keys != actual.keys) {
            return "${prefix}table set differs (missing: ${names(expected.keys - actual.keys)}; " +
                "extra: ${names(actual.keys - expected.keys)})"
        }
        for (table in expected.keys) {
            rowsDifference("$prefix$table", expected.getValue(table), actual.getValue(table))
                ?.let { return it }
        }
        return "${prefix}tables differ"
    }

    fun rowsDifference(
        table: String,
        expected: List<Map<String, Any?>>,
        actual: List<Map<String, Any?>>
    ): String? {
        if (expected == actual) return null
        if (expected.size != actual.size) {
            return "table $table: row count ${expected.size} vs ${actual.size}"
        }
        if (expected.toSet() == actual.toSet()) return "table $table: same rows in a different order"
        for (index in expected.indices) {
            val want = expected[index]
            val got = actual[index]
            if (want == got) continue
            if (want.keys != got.keys) {
                return "table $table: column set differs (missing: ${names(want.keys - got.keys)}; " +
                    "extra: ${names(got.keys - want.keys)})"
            }
            for (column in want.keys) {
                valueDifference("table $table column $column", want[column], got[column])
                    ?.let { return it }
            }
        }
        return "table $table: rows differ"
    }

    /** First difference between two companion/roleplay manifests. */
    fun manifestDifference(
        expected: CompanionBackupManifest,
        actual: CompanionBackupManifest
    ): String? {
        if (expected == actual) return null
        valueDifference("format version", expected.formatVersion, actual.formatVersion)?.let { return it }
        valueDifference("app version field", expected.appVersion, actual.appVersion)?.let { return it }
        valueDifference("export time field", expected.exportedAt, actual.exportedAt)?.let { return it }
        listDifference("companion profile", expected.companionProfiles, actual.companionProfiles) { a, b ->
            fieldDifference(
                "companion profile",
                listOf(
                    "id" to (a.id to b.id),
                    "label" to (a.label to b.label),
                    "prompt" to (a.prompt to b.prompt),
                    "activationPromptId" to (a.activationPromptId to b.activationPromptId),
                    "coreLoreBookId" to (a.coreLoreBookId to b.coreLoreBookId),
                    "coreLoreBookName" to (a.coreLoreBookName to b.coreLoreBookName),
                    "additionalLoreBookIds" to (a.additionalLoreBookIds to b.additionalLoreBookIds),
                    "additionalLoreBookNames" to (a.additionalLoreBookNames to b.additionalLoreBookNames),
                    "autoLoadLastLoreBooks" to (a.autoLoadLastLoreBooks to b.autoLoadLastLoreBooks),
                    "lastUsedLoreBookIds" to (a.lastUsedLoreBookIds to b.lastUsedLoreBookIds),
                    "avatarRef" to (a.avatarRef to b.avatarRef),
                    "chatNameFontId" to (a.chatNameFontId to b.chatNameFontId),
                    "chatNameSizeSp" to (a.chatNameSizeSp to b.chatNameSizeSp),
                    "chatNameFontStyle" to (a.chatNameFontStyle to b.chatNameFontStyle)
                )
            ) ?: listDifference("companion prompt variant", a.promptVariants, b.promptVariants) { x, y ->
                fieldDifference(
                    "companion prompt variant",
                    listOf(
                        "id" to (x.id to y.id),
                        "name" to (x.name to y.name),
                        "text" to (x.text to y.text),
                        "isDefault" to (x.isDefault to y.isDefault)
                    )
                )
            }
        }?.let { return it }
        listDifference("activation prompt", expected.activationPrompts, actual.activationPrompts) { a, b ->
            fieldDifference(
                "activation prompt",
                listOf("id" to (a.id to b.id), "label" to (a.label to b.label), "prompt" to (a.prompt to b.prompt))
            )
        }?.let { return it }
        listDifference("system prompt", expected.systemPrompts, actual.systemPrompts) { a, b ->
            fieldDifference(
                "system prompt",
                listOf("id" to (a.id to b.id), "title" to (a.title to b.title), "body" to (a.body to b.body))
            )
        }?.let { return it }
        valueDifference(
            "selected system prompt", expected.selectedSystemPromptId, actual.selectedSystemPromptId
        )?.let { return it }
        tableDifference(expected.roleplayTables, actual.roleplayTables, "roleplay ")?.let { return it }
        listDifference("profile image entry", expected.images, actual.images) { a, b ->
            fieldDifference("profile image entry", listOf("hash" to (a.hash to b.hash), "file" to (a.file to b.file)))
        }?.let { return it }
        return "manifest differs"
    }

    private fun <T> listDifference(
        label: String,
        expected: List<T>,
        actual: List<T>,
        item: (T, T) -> String?
    ): String? {
        if (expected == actual) return null
        if (expected.size != actual.size) return "$label count ${expected.size} vs ${actual.size}"
        for (index in expected.indices) {
            if (expected[index] == actual[index]) continue
            return item(expected[index], actual[index]) ?: "$label differs"
        }
        return "$label list differs"
    }

    private fun fieldDifference(
        label: String,
        fields: List<Pair<String, Pair<Any?, Any?>>>
    ): String? {
        for ((name, values) in fields) {
            valueDifference("$label field $name", values.first, values.second)?.let { return it }
        }
        return null
    }

    private fun valueDifference(label: String, expected: Any?, actual: Any?): String? {
        if (expected == actual) return null
        val expectedType = typeName(expected)
        val actualType = typeName(actual)
        return if (expectedType != actualType) "$label: value type $expectedType vs $actualType"
        else "$label: value differs ($expectedType)"
    }

    fun typeName(value: Any?): String = value?.javaClass?.simpleName ?: "null"

    private fun names(values: Collection<String>): String =
        values.sorted().joinToString(",").ifEmpty { "none" }
}

/** Holds a participant's most recent non-content failure reason. */
internal class PortableRestoreFailureNote {
    var detail: String? = null
        private set

    fun reset() {
        detail = null
    }

    /** Records [reason] and returns false, so a check can `return note.fail(...)`. */
    fun fail(reason: String): Boolean {
        detail = reason
        return false
    }

    fun unexpected(error: Throwable): Boolean = fail(PortableRestoreDiagnostics.unexpected(error))
}

/**
 * The Error Log lines for a restore that failed or partly failed. Each line
 * gives the restore step, the categories involved (every category a shared
 * participant covers), the internal reason code and non-content detail.
 */
internal object PortableRestoreFailureLog {
    enum class Step(val label: String) {
        PACKAGE_VALIDATION("package validation"),
        ARTIFACT_PARSING("reading the backup package contents"),
        CATEGORY_SELECTION("category selection"),
        BACKUP_CATEGORY_READ("reading backup category data"),
        RECORD_COUNT_CHECK("backup record count check"),
        CURRENT_DATA_READ("reading current device data"),
        CATEGORY_PLANNING("category planning"),
        DEPENDENCY_VALIDATION("dependency validation"),
        PARTICIPANT_VALIDATION("validation before staging"),
        STAGING("staging"),
        JOURNAL("restore journal"),
        APPLY("apply"),
        ROLLBACK("rollback")
    }

    data class Entry(
        val step: Step,
        val categories: List<PortableRestoreCategory>,
        val reason: String,
        val detail: String? = null,
        val participant: String? = null
    )

    fun line(context: Context, entry: Entry): String = buildString {
        append("Step: ").append(entry.step.label)
        append(" | Categories: ")
        append(
            entry.categories.joinToString(", ") { PortableRestoreIssueText.categoryName(context, it) }
                .ifEmpty { "none identified" }
        )
        entry.participant?.let { append(" | Participant: ").append(it) }
        append(" | Reason: ").append(entry.reason)
        append(" | Detail: ").append(entry.detail ?: "none recorded")
    }

    fun categoryFailure(failure: UnifiedPortableRestore.CategoryFailure): Entry = Entry(
        when (failure.reason) {
            UnifiedPortableRestore.CategoryFailureReason.MISSING_DATA,
            UnifiedPortableRestore.CategoryFailureReason.INVALID_DATA,
            UnifiedPortableRestore.CategoryFailureReason.CHAT_DATA -> Step.BACKUP_CATEGORY_READ
            UnifiedPortableRestore.CategoryFailureReason.COUNT_MISMATCH -> Step.RECORD_COUNT_CHECK
            UnifiedPortableRestore.CategoryFailureReason.CURRENT_DATA_UNAVAILABLE -> Step.CURRENT_DATA_READ
            UnifiedPortableRestore.CategoryFailureReason.PLANNING_FAILED -> Step.CATEGORY_PLANNING
        },
        listOf(failure.category),
        failure.reason.name,
        failure.detail
    )

    fun transactionFailure(
        failed: SelectedCategoryRestoreTransaction.Result.Failed,
        categories: List<PortableRestoreCategory>
    ): Entry = Entry(
        when (failed.reason) {
            SelectedCategoryRestoreTransaction.Failure.VALIDATION_FAILED -> Step.PARTICIPANT_VALIDATION
            SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED -> Step.STAGING
            SelectedCategoryRestoreTransaction.Failure.APPLY_FAILED -> Step.APPLY
            SelectedCategoryRestoreTransaction.Failure.ROLLBACK_FAILED -> Step.ROLLBACK
            SelectedCategoryRestoreTransaction.Failure.JOURNAL_FAILED,
            SelectedCategoryRestoreTransaction.Failure.PENDING_RECOVERY -> Step.JOURNAL
            SelectedCategoryRestoreTransaction.Failure.EMPTY_SELECTION,
            SelectedCategoryRestoreTransaction.Failure.DUPLICATE_CATEGORY -> Step.DEPENDENCY_VALIDATION
        },
        categories,
        failed.reason.name,
        "data state ${failed.dataState.name}; " + (failed.detail ?: "none recorded"),
        failed.categoryKey
    )
}
