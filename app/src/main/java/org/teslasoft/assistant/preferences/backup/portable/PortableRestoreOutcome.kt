/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.util.AtomicFileWriter

/**
 * Process-independent result of a unified portable restore. The service writes
 * this before it removes coordinator-owned staging or requests the mandatory
 * process restart. A recreated activity, or the first activity in the new
 * process, consumes it exactly once.
 */
sealed interface PortableRestoreOutcome {
    data class Success(val report: Report) : PortableRestoreOutcome
    data class PackageFailure(val error: PortablePackageFormat.RestoreError) : PortableRestoreOutcome
    data class BuildFailure(
        val reason: UnifiedPortableRestore.BuildFailure,
        val category: PortableRestoreCategory?
    ) : PortableRestoreOutcome
    data object NothingAvailable : PortableRestoreOutcome
    data class TransactionFailure(
        val reason: SelectedCategoryRestoreTransaction.Failure,
        val categoryKey: String?,
        val dataState: SelectedCategoryRestoreTransaction.DataState,
        val chatValidationFailure: PortableChatRestorePlan.Reason?
    ) : PortableRestoreOutcome

    data class Report(
        val lines: List<Line>,
        val removedLorebookLinks: List<RemovedLorebookLink>
    ) {
        sealed interface Line {
            data class ConflictCount(
                val category: PortableRestoreCategory,
                val count: Int
            ) : Line
            data class NamedConflicts(
                val category: PortableRestoreCategory,
                val names: List<String>,
                val fallbackCount: Int
            ) : Line
            data class LongerChats(val count: Int) : Line
            data class ProtectedImages(val count: Int) : Line
        }
    }
}

object PortableRestoreOutcomeStore {
    private val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "portable-restore-outcome")
    }

    fun persist(context: Context, outcome: PortableRestoreOutcome): Boolean =
        AtomicFileWriter.writeAndVerify(file(context), encode(outcome).toString())

    fun peek(context: Context): PortableRestoreOutcome? = decode(file(context))

    fun hasPending(context: Context): Boolean = file(context).isFile

    fun loadAsync(context: Context, complete: (PortableRestoreOutcome?) -> Unit) {
        val app = context.applicationContext
        reader.execute { complete(peek(app)) }
    }

    fun acknowledge(context: Context): Boolean {
        val source = file(context)
        return source.delete() || !source.exists()
    }

    /** The returned result has already been removed from durable storage. */
    @Synchronized
    fun consume(context: Context): PortableRestoreOutcome? {
        val source = file(context)
        val outcome = decode(source) ?: return null
        if (!source.delete() && source.exists()) return null
        return outcome
    }

    internal fun encode(outcome: PortableRestoreOutcome): JSONObject {
        val root = JSONObject().put("version", VERSION)
        when (outcome) {
            is PortableRestoreOutcome.Success -> root
                .put("kind", "success")
                .put("report", encodeReport(outcome.report))
            is PortableRestoreOutcome.PackageFailure -> root
                .put("kind", "package_failure")
                .put("error", outcome.error.name)
            is PortableRestoreOutcome.BuildFailure -> root
                .put("kind", "build_failure")
                .put("reason", outcome.reason.name)
                .put("category", outcome.category?.name)
            PortableRestoreOutcome.NothingAvailable -> root.put("kind", "nothing_available")
            is PortableRestoreOutcome.TransactionFailure -> root
                .put("kind", "transaction_failure")
                .put("reason", outcome.reason.name)
                .put("category_key", outcome.categoryKey)
                .put("data_state", outcome.dataState.name)
                .put("chat_validation", outcome.chatValidationFailure?.name)
        }
        return root
    }

    internal fun decode(source: File): PortableRestoreOutcome? = try {
        if (!source.isFile || source.length() > MAX_BYTES) return null
        decode(JSONObject(source.readText(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }

    internal fun decode(root: JSONObject): PortableRestoreOutcome? = try {
        if (root.optInt("version", -1) != VERSION) return null
        when (root.getString("kind")) {
            "success" -> PortableRestoreOutcome.Success(decodeReport(root.getJSONObject("report")) ?: return null)
            "package_failure" -> PortableRestoreOutcome.PackageFailure(
                PortablePackageFormat.RestoreError.valueOf(root.getString("error"))
            )
            "build_failure" -> PortableRestoreOutcome.BuildFailure(
                UnifiedPortableRestore.BuildFailure.valueOf(root.getString("reason")),
                root.optString("category").takeIf(String::isNotBlank)
                    ?.let(PortableRestoreCategory::valueOf)
            )
            "nothing_available" -> PortableRestoreOutcome.NothingAvailable
            "transaction_failure" -> PortableRestoreOutcome.TransactionFailure(
                SelectedCategoryRestoreTransaction.Failure.valueOf(root.getString("reason")),
                root.optString("category_key").takeIf(String::isNotBlank),
                SelectedCategoryRestoreTransaction.DataState.valueOf(root.getString("data_state")),
                root.optString("chat_validation").takeIf(String::isNotBlank)
                    ?.let(PortableChatRestorePlan.Reason::valueOf)
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun encodeReport(report: PortableRestoreOutcome.Report) = JSONObject()
        .put("lines", JSONArray().apply {
            report.lines.forEach { line ->
                put(when (line) {
                    is PortableRestoreOutcome.Report.Line.ConflictCount -> JSONObject()
                        .put("kind", "conflict_count")
                        .put("category", line.category.name)
                        .put("count", line.count)
                    is PortableRestoreOutcome.Report.Line.NamedConflicts -> JSONObject()
                        .put("kind", "named_conflicts")
                        .put("category", line.category.name)
                        .put("names", JSONArray(line.names))
                        .put("fallback_count", line.fallbackCount)
                    is PortableRestoreOutcome.Report.Line.LongerChats -> JSONObject()
                        .put("kind", "longer_chats")
                        .put("count", line.count)
                    is PortableRestoreOutcome.Report.Line.ProtectedImages -> JSONObject()
                        .put("kind", "protected_images")
                        .put("count", line.count)
                })
            }
        })
        .put("removed_lorebook_links", JSONArray().apply {
            report.removedLorebookLinks.forEach { link ->
                put(JSONObject()
                    .put("companion_label", link.companionLabel)
                    .put("lorebook_name", link.lorebookName))
            }
        })

    private fun decodeReport(root: JSONObject): PortableRestoreOutcome.Report? = try {
        val linesJson = root.getJSONArray("lines")
        val lines = ArrayList<PortableRestoreOutcome.Report.Line>(linesJson.length())
        repeat(linesJson.length()) { index ->
            val item = linesJson.getJSONObject(index)
            val count = item.optInt("count", -1)
            lines.add(when (item.getString("kind")) {
                "conflict_count" -> PortableRestoreOutcome.Report.Line.ConflictCount(
                    PortableRestoreCategory.valueOf(item.getString("category")),
                    count.takeIf { it > 0 } ?: return null
                )
                "named_conflicts" -> {
                    val namesJson = item.getJSONArray("names")
                    val names = ArrayList<String>(namesJson.length())
                    repeat(namesJson.length()) { names.add(namesJson.getString(it)) }
                    val fallback = item.optInt("fallback_count", -1)
                    if (fallback <= 0) return null
                    PortableRestoreOutcome.Report.Line.NamedConflicts(
                        PortableRestoreCategory.valueOf(item.getString("category")),
                        names,
                        fallback
                    )
                }
                "longer_chats" -> PortableRestoreOutcome.Report.Line.LongerChats(
                    count.takeIf { it > 0 } ?: return null
                )
                "protected_images" -> PortableRestoreOutcome.Report.Line.ProtectedImages(
                    count.takeIf { it > 0 } ?: return null
                )
                else -> return null
            })
        }
        val linksJson = root.getJSONArray("removed_lorebook_links")
        val links = ArrayList<RemovedLorebookLink>(linksJson.length())
        repeat(linksJson.length()) { index ->
            val item = linksJson.getJSONObject(index)
            links.add(RemovedLorebookLink(
                item.getString("companion_label"), item.getString("lorebook_name")
            ))
        }
        PortableRestoreOutcome.Report(lines, links)
    } catch (_: Exception) {
        null
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val VERSION = 1
    private const val FILE_NAME = "portable_restore_terminal_outcome.json"
    private const val MAX_BYTES = 512L * 1024L
}
