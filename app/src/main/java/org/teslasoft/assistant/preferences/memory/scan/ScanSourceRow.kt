/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

/**
 * One message, or one fragment of a long message, as the Memory Scanner
 * reads it for a request. Built in working memory from the original chat and
 * never saved. [start] and [end] are offsets into the message's current text,
 * so a fragment is identified by its message UUID and offsets, including
 * after an oversized-message retry. The scope fields are the message's
 * recorded scope (all null when unknown) with the companion resolved to its
 * memory-database id.
 */
data class ScanSourceRow(
    val chatId: String,
    val messageId: String,
    /** The message's permanent number, the reference the scanner AI sees. */
    val messageNumber: Long?,
    val role: String,
    val start: Int,
    val end: Int,
    val text: String,
    val startedAt: String?,
    val modelTag: String?,
    val companionId: String? = null,
    val worldId: String? = null,
    val campaignId: String? = null,
    val roleplayCharacterId: String? = null,
    val userPersonaId: String? = null,
    val projectId: String? = null,
    /** 1-based part of a message split into several pieces, and how many
     *  pieces it was split into; 1 of 1 for a message read whole. */
    val partIndex: Int = 1,
    val partCount: Int = 1
) {
    val isAssistant: Boolean get() = role == "assistant"

    /** A sub-fragment of this row; [from] and [to] are offsets into [text]. */
    fun slice(from: Int, to: Int): ScanSourceRow =
        copy(start = start + from, end = start + to, text = text.substring(from, to))
}

/** How a message's recorded scope applies to scanning. */
sealed interface ScanScopeResolution {
    /** The memory-database ids to read the message with. */
    data class Read(
        val companionId: String?,
        val worldId: String?,
        val campaignId: String?,
        val roleplayCharacterId: String?,
        val userPersonaId: String?,
        val projectId: String?
    ) : ScanScopeResolution

    /** The message's companion takes no part in memory ("none"). */
    data object Excluded : ScanScopeResolution
}

object ScanSourceRows {

    val GENERAL = ScanScopeResolution.Read(null, null, null, null, null, null)

    /**
     * Rows for the eligible messages of [rangeMessages] from [startIndex],
     * the first starting at [startOffset]. Hidden, unfinished and blank
     * messages and messages of a companion whose memory participation is
     * "none" are not read.
     */
    fun build(
        chatId: String,
        rangeMessages: List<ScanMessage>,
        startIndex: Int,
        startOffset: Int,
        resolveScope: (ScanMessageScope?) -> ScanScopeResolution
    ): List<ScanSourceRow> {
        val out = ArrayList<ScanSourceRow>()
        for (index in startIndex until rangeMessages.size) {
            val message = rangeMessages[index]
            if (!message.eligible) continue
            val scope = resolveScope(message.scope) as? ScanScopeResolution.Read ?: continue
            val from = if (index == startIndex) startOffset.coerceIn(0, message.text.length) else 0
            if (from >= message.text.length) continue
            out.add(
                ScanSourceRow(
                    chatId = chatId,
                    messageId = message.id,
                    messageNumber = message.number,
                    role = message.role,
                    start = from,
                    end = message.text.length,
                    text = message.text.substring(from),
                    startedAt = message.startedAt,
                    modelTag = message.model,
                    companionId = scope.companionId,
                    worldId = scope.worldId,
                    campaignId = scope.campaignId,
                    roleplayCharacterId = scope.roleplayCharacterId,
                    userPersonaId = scope.userPersonaId,
                    projectId = scope.projectId
                )
            )
        }
        return out
    }
}
