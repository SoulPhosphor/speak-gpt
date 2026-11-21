/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.memory

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

enum class MemoryPortableGroup { MEMORIES, MODEL_RULES }

data class MemoryPortableRows(val tables: Map<String, List<Map<String, Any?>>>)

object MemoryPortableRowFormat {
    data class Spec(val table: String, val keys: List<String>, val where: String? = null)

    val memorySpecs = listOf(
        Spec("owner_profile", listOf("id")),
        Spec("entities", listOf("entity_id")),
        Spec("projects", listOf("project_id")),
        Spec("memory_types", listOf("type_id")),
        Spec("memories", listOf("memory_id")),
        Spec("memory_companions", listOf("memory_id", "companion_id")),
        Spec("memory_entities", listOf("memory_id", "entity_id")),
        Spec("memory_worlds", listOf("memory_id", "world_id")),
        Spec("memory_campaigns", listOf("memory_id", "campaign_id")),
        Spec("memory_roleplay_characters", listOf("memory_id", "roleplay_character_id")),
        Spec("memory_projects", listOf("memory_id", "project_id")),
        Spec("memory_supersessions", listOf("new_memory_id", "old_memory_id")),
        Spec("memory_possible_match_hints", listOf("draft_memory_id", "existing_memory_id")),
        Spec("memory_sources", listOf("source_id")),
        Spec("change_log", listOf("memory_id", "at", "actor", "action")),
        Spec("modes", listOf("mode_id")),
        Spec("directives", listOf("directive_id")),
        Spec("archivist_settings", listOf("id")),
        Spec("memory_handling_preferences", listOf("id")),
        Spec("proposals", listOf("proposal_id")),
        // Stage 6A: the scanner's bookmarks (message UUID references, no
        // conversation text). The transcript store is no longer exported.
        Spec("scan_chat_progress", listOf("chat_id")),
        Spec("rp_tag_links", listOf("tag_id", "target_type", "target_id"), "target_type = 'memory'"),
        Spec("deleted_ids", listOf("record_type", "record_id"), "record_type IN ('memory','entity','project')")
    )

    val modelRuleSpecs = listOf(
        Spec("model_rules", listOf("rule_id")),
        Spec("model_rule_tags", listOf("tag_id")),
        Spec("model_rule_tag_links", listOf("rule_id", "tag_id"))
    )

    /**
     * Tables added after a backup format was first released. A backup made
     * before one existed (an older database file, or older readable JSON)
     * reads as having none of its rows, never as an error.
     */
    val tablesAddedLater: Set<String> = setOf(
        "memory_sources", "memory_handling_preferences", "scan_chat_progress"
    )

    /**
     * Tables a backup made before Stage 6A may carry. Their rows are used
     * only by the scanner migration to map earlier scan progress and message
     * scope onto the restored chats, then their text is deleted (memory
     * development plan M.2, "Removing the duplicate transcript store",
     * step 3). New backups never contain them.
     */
    val legacyTables: Set<String> = setOf("transcripts", "analysis_chat_bookmarks")

    fun specs(group: MemoryPortableGroup): List<Spec> = when (group) {
        MemoryPortableGroup.MEMORIES -> memorySpecs
        MemoryPortableGroup.MODEL_RULES -> modelRuleSpecs
    }

    /**
     * [includeLegacy] reads the [legacyTables] too, when they exist and hold
     * rows: only for a backup's memory database made before Stage 6A, so
     * its scan progress and message scope reach the scanner migration.
     */
    fun read(db: SQLiteDatabase, group: MemoryPortableGroup, includeLegacy: Boolean = false): MemoryPortableRows {
        val out = LinkedHashMap<String, List<Map<String, Any?>>>()
        for (spec in specs(group)) {
            if (spec.table in tablesAddedLater && !tableExists(db, spec.table)) {
                out[spec.table] = ArrayList()
                continue
            }
            out[spec.table] = readRows(db, spec.table, spec.where)
        }
        if (includeLegacy && group == MemoryPortableGroup.MEMORIES) {
            for (legacy in legacyTables) {
                if (!tableExists(db, legacy)) continue
                val rows = readRows(db, legacy, null)
                if (rows.isNotEmpty()) out[legacy] = rows
            }
        }
        return MemoryPortableRows(out)
    }

    private fun readRows(db: SQLiteDatabase, table: String, where: String?): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>()
        db.query(table, null, where, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                repeat(cursor.columnCount) { index ->
                    val column = cursor.getColumnName(index)
                    if (table == "change_log" && column == "id") return@repeat
                    row[column] = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        else -> throw IllegalStateException("unsupported portable column")
                    }
                }
                rows.add(row)
            }
        }
        return rows
    }

    fun replace(db: SQLiteDatabase, group: MemoryPortableGroup, data: MemoryPortableRows): Boolean {
        if (!valid(group, data)) return false
        val specs = specs(group)
        db.beginTransaction()
        return try {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            when (group) {
                MemoryPortableGroup.MEMORIES -> {
                    db.delete("rp_tag_links", "target_type = 'memory'", null)
                    db.delete("injection_cooldowns", "source_type = 'memory'", null)
                    db.delete("import_conflicts", null, null)
                    for (spec in specs.asReversed()) {
                        if (spec.where == null) db.delete(spec.table, null, null)
                    }
                    db.delete(
                        "deleted_ids",
                        "record_type IN ('memory','entity','project')",
                        null
                    )
                }
                MemoryPortableGroup.MODEL_RULES -> specs.asReversed().forEach {
                    db.delete(it.table, null, null)
                }
            }
            for (spec in specs) {
                val liveColumns = columns(db, spec.table)
                for (row in data.tables[spec.table].orEmpty()) {
                    val values = ContentValues()
                    for ((column, value) in row) {
                        if (column !in liveColumns) continue
                        when (value) {
                            null -> values.putNull(column)
                            is Long -> values.put(column, value)
                            is Int -> values.put(column, value.toLong())
                            is Double -> values.put(column, value)
                            is Boolean -> values.put(column, if (value) 1L else 0L)
                            else -> values.put(column, value.toString())
                        }
                    }
                    db.insertOrThrow(spec.table, null, values)
                }
            }
            if (group == MemoryPortableGroup.MEMORIES) {
                stageLegacyScanRows(db, data)
                endScansAfterRestore(db)
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun valid(group: MemoryPortableGroup, data: MemoryPortableRows): Boolean {
        val specs = specs(group)
        if (data.tables.keys - legacyTables != specs.mapTo(LinkedHashSet(), Spec::table)) return false
        for (spec in specs) {
            val seen = HashSet<String>()
            for (row in data.tables[spec.table].orEmpty()) {
                val identity = identity(spec, row) ?: return false
                if (!seen.add(identity)) return false
            }
        }
        return true
    }

    fun identity(spec: Spec, row: Map<String, Any?>): String? {
        val parts = spec.keys.map { row[it]?.toString().orEmpty() }
        if (parts.any(String::isBlank)) return null
        return parts.joinToString("\u0000")
    }

    fun toJson(group: MemoryPortableGroup, data: MemoryPortableRows): String {
        require(valid(group, data))
        return JSONObject().put("version", 1).put("group", group.name).put("tables", JSONObject().apply {
            specs(group).forEach { spec -> put(spec.table, JSONArray().apply {
                data.tables.getValue(spec.table).forEach { row -> put(JSONObject().apply {
                    row.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                }) }
            }) }
        }).toString()
    }

    fun parse(text: String, expected: MemoryPortableGroup): MemoryPortableRows? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("version", -1) != 1 || root.optString("group") != expected.name) return null
            val tablesJson = root.getJSONObject("tables")
            val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
            for (spec in specs(expected)) {
                if (spec.table in tablesAddedLater && !tablesJson.has(spec.table)) {
                    tables[spec.table] = emptyList()
                    continue
                }
                val array = tablesJson.getJSONArray(spec.table)
                val rows = ArrayList<Map<String, Any?>>(array.length())
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val row = LinkedHashMap<String, Any?>()
                    item.keys().forEach { key -> row[key] = when (val value = item.get(key)) {
                        JSONObject.NULL -> null
                        is Int -> value.toLong()
                        is String, is Long, is Double, is Boolean -> value
                        else -> return null
                    } }
                    rows.add(row)
                }
                tables[spec.table] = rows
            }
            if (expected == MemoryPortableGroup.MEMORIES) {
                for (legacy in legacyTables) {
                    val array = tablesJson.optJSONArray(legacy) ?: continue
                    val rows = ArrayList<Map<String, Any?>>(array.length())
                    repeat(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        val row = LinkedHashMap<String, Any?>()
                        item.keys().forEach { key -> row[key] = when (val value = item.get(key)) {
                            JSONObject.NULL -> null
                            is Int -> value.toLong()
                            is String, is Long, is Double, is Boolean -> value
                            else -> return null
                        } }
                        rows.add(row)
                    }
                    if (rows.isNotEmpty()) tables[legacy] = rows
                }
            }
            MemoryPortableRows(tables).takeIf { valid(expected, it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * An older backup's transcript rows wait in the legacy tables for the
     * scanner migration, which maps them against the restored chats and then
     * deletes them. Nothing else reads them.
     */
    fun stageLegacyScanRows(db: SQLiteDatabase, data: MemoryPortableRows) {
        val legacy = legacyTables.filter { data.tables[it].orEmpty().isNotEmpty() }
        if (legacy.isEmpty()) return
        MemoryStore.createLegacyScanTables(db)
        for (table in legacy) {
            val liveColumns = columns(db, table)
            for (row in data.tables[table].orEmpty()) {
                val values = ContentValues()
                for ((column, value) in row) {
                    if (column !in liveColumns) continue
                    when (value) {
                        null -> values.putNull(column)
                        is Long -> values.put(column, value)
                        is Int -> values.put(column, value.toLong())
                        is Double -> values.put(column, value)
                        is Boolean -> values.put(column, if (value) 1L else 0L)
                        else -> values.put(column, value.toString())
                    }
                }
                db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        MemoryStore.markScanSourceMigrationPending(db)
    }

    /**
     * The restored bookmarks replace the device's, so a scan that was paused
     * or interrupted before the restore cannot continue against them: its
     * temporary ranges and working drafts are discarded, its run ends as
     * stopped, and no claim survives. Restoring never brings back a paused
     * scan (owner decision, Stage 6A question 12).
     */
    fun endScansAfterRestore(db: SQLiteDatabase) {
        db.delete("analysis_candidates", null, null)
        db.delete("analysis_run_state", null, null)
        db.delete("scan_ranges", null, null)
        db.execSQL("UPDATE scan_chat_progress SET claim_run_id = NULL")
        db.execSQL(
            "UPDATE archivist_runs SET status = 'failed', suspended = NULL, " +
                "finished_at = COALESCE(finished_at, ?), outcome = COALESCE(outcome, 'cancelled') " +
                "WHERE status = 'running' AND suspended IS NOT NULL",
            arrayOf(java.time.Instant.now().toString())
        )
    }

    fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)
        ).use { it.moveToFirst() }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", emptyArray<String>()).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) out.add(cursor.getString(name))
        }
        return out
    }
}
