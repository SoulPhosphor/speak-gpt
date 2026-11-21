/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.json.JSONArray
import org.teslasoft.assistant.preferences.memory.AnalysisBookmark
import org.teslasoft.assistant.preferences.memory.AnalysisChatBookmark
import org.teslasoft.assistant.preferences.memory.LegacyTranscript

/**
 * The pure part of the Stage 6A migration away from the transcript store
 * (memory development plan M.2, "Removing the duplicate transcript store").
 * For one chat it decides, from the legacy rows and the chat's current
 * messages, where the converted bookmark goes, which messages receive their
 * recorded scope, and which message span each legacy row covered. Message
 * identity is matched by UUID only, never by text.
 */
object ScanSourceMigrationPlan {

    /** One chat message as the migration needs it. */
    data class Message(val id: String, val timeMillis: Long?, val hasScope: Boolean)

    data class Result(
        /** Index of the last scanned message, or -1 for nothing scanned. */
        val bookmarkIndex: Int,
        /** Earlier progress could not be converted reliably: the chat is
         *  rescanned in full automatically (owner decision, question 1). */
        val needsFullRescan: Boolean,
        /** Scope to record, by message id, for messages without one. */
        val scopes: Map<String, ScanMessageScope>,
        /** The first and last message index each legacy row covers. */
        val spans: Map<String, IntRange>,
        /** The last message index named by rows claimed by each run. */
        val claimedEnds: Map<String, Int>
    )

    private data class Turn(val messageId: String?, val atMillis: Long?)

    fun plan(
        messages: List<Message>,
        rows: List<LegacyTranscript>,
        legacyBookmark: AnalysisChatBookmark?,
        archivePaused: Boolean,
        personaIdForCompanion: (String) -> String?
    ): Result {
        val indexById = HashMap<String, Int>()
        messages.forEachIndexed { i, m -> indexById.putIfAbsent(m.id, i) }
        val turnsByRow = rows.associate { it.transcriptId to turns(it.content) }

        // Which legacy rows were already scanned: the legacy bookmark, or,
        // for an older backup without one, the contiguous terminal prefix.
        val scannedIds: Set<String> = if (legacyBookmark != null) {
            val boundary = legacyBookmark.lastTranscriptId?.let {
                AnalysisBookmark.Boundary(legacyBookmark.lastStartedAt, it)
            }
            val skipped = legacyBookmark.skippedTranscriptIds.toSet()
            rows.filter {
                it.transcriptId in skipped ||
                    !AnalysisBookmark.isAfter(AnalysisBookmark.Boundary(it.startedAt, it.transcriptId), boundary)
            }.map { it.transcriptId }.toSet()
        } else {
            val plan = AnalysisBookmark.planMigration(
                rows.map { AnalysisBookmark.LegacyRow(it.transcriptId, it.startedAt, it.reviewStatus) },
                archivePaused
            )
            val boundary = plan.boundary
            rows.filter {
                it.transcriptId in plan.skippedTranscriptIds ||
                    (boundary != null &&
                        !AnalysisBookmark.isAfter(AnalysisBookmark.Boundary(it.startedAt, it.transcriptId), boundary))
            }.map { it.transcriptId }.toSet()
        }
        // A row still claimed by a run at upgrade was not finished.
        val scanned = rows.filter { it.transcriptId in scannedIds && it.claimRunId == null }
        val unscanned = rows.filter { it !in scanned }

        val spans = HashMap<String, IntRange>()
        for (row in rows) {
            val indices = turnsByRow[row.transcriptId].orEmpty().mapNotNull { t -> t.messageId?.let(indexById::get) }
            if (indices.isNotEmpty()) spans[row.transcriptId] = indices.min()..indices.max()
        }
        val claimedEnds = HashMap<String, Int>()
        for (row in rows) {
            val run = row.claimRunId ?: continue
            val end = spans[row.transcriptId]?.last ?: continue
            claimedEnds[run] = maxOf(claimedEnds[run] ?: -1, end)
        }

        val scannedTurns = scanned.flatMap { turnsByRow[it.transcriptId].orEmpty() }
        val unscannedTurns = unscanned.flatMap { turnsByRow[it.transcriptId].orEmpty() }
        val scannedIdx = scannedTurns.mapNotNull { t -> t.messageId?.let(indexById::get) }
        val unscannedIdx = unscannedTurns.mapNotNull { t -> t.messageId?.let(indexById::get) }

        var bookmark = -1
        var fullRescan = false
        if (scannedTurns.isNotEmpty()) {
            val allNamed = scannedTurns.all { it.messageId != null }
            if (allNamed) {
                bookmark = when {
                    scannedIdx.isNotEmpty() -> scannedIdx.max()
                    unscannedIdx.isNotEmpty() -> unscannedIdx.min() - 1
                    else -> -1
                }
                if (unscannedIdx.isNotEmpty() && unscannedIdx.min() <= bookmark) fullRescan = true
            } else {
                val byTime = boundaryByTime(messages, scannedTurns, unscannedTurns)
                if (byTime == null) fullRescan = true else bookmark = byTime
            }
        }
        if (fullRescan) bookmark = -1

        // Recorded scope comes only from rows captured live with the turn;
        // an imported row's companion was the chat's setting at import time
        // and is not trusted for each message (owner decision, question 2).
        val scopes = HashMap<String, ScanMessageScope>()
        for (row in rows) {
            if (row.source != "live") continue
            val companionId = row.companionId?.takeIf { it.isNotBlank() }
            val scope = ScanMessageScope(
                companionPersonaId = companionId?.let(personaIdForCompanion),
                companionId = companionId?.takeIf { personaIdForCompanion(it) == null },
                worldId = row.worldId,
                campaignId = row.campaignId,
                roleplayCharacterId = row.roleplayCharacterId,
                userPersonaId = row.userPersonaId,
                projectId = row.projectId
            )
            for (turn in turnsByRow[row.transcriptId].orEmpty()) {
                val id = turn.messageId ?: continue
                val index = indexById[id] ?: continue
                if (messages[index].hasScope) continue
                scopes.putIfAbsent(id, scope)
            }
        }
        return Result(bookmark, fullRescan, scopes, spans, claimedEnds)
    }

    /**
     * Progress mapped by stored times, used only when the scanned rows have
     * turns without message ids. Every message and every turn involved must
     * have a time and the boundary must be unambiguous; otherwise null.
     */
    private fun boundaryByTime(messages: List<Message>, scanned: List<Turn>, unscanned: List<Turn>): Int? {
        if (messages.any { it.timeMillis == null }) return null
        if (scanned.any { it.atMillis == null } || unscanned.any { it.atMillis == null }) return null
        val lastScanned = scanned.maxOf { it.atMillis!! }
        if (unscanned.any { it.atMillis!! <= lastScanned }) return null
        val index = messages.indexOfLast { it.timeMillis!! <= lastScanned }
        if (index < 0) return null
        val next = messages.getOrNull(index + 1)
        if (next != null && next.timeMillis!! <= lastScanned) return null
        // Times must be in the chat's order up to the boundary.
        for (i in 1..index) if (messages[i].timeMillis!! < messages[i - 1].timeMillis!!) return null
        return index
    }

    private fun turns(content: String): List<Turn> = try {
        val a = JSONArray(content)
        (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            Turn(
                messageId = t.optString(LEGACY_MESSAGE_ID, "").takeIf { it.isNotBlank() },
                atMillis = t.optString("at", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                }
            )
        }
    } catch (_: Exception) {
        // Unreadable legacy content: one turn with no identity, so a chat
        // with such a scanned row is rescanned rather than guessed.
        listOf(Turn(null, null))
    }

    /** The key legacy transcript turns used for their message UUID. */
    const val LEGACY_MESSAGE_ID = "messageId"
}
