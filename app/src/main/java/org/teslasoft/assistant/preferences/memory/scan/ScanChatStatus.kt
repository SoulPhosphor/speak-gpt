/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.teslasoft.assistant.preferences.memory.AnalysisBookmark
import org.teslasoft.assistant.preferences.memory.ChatScanCoverage
import org.teslasoft.assistant.preferences.memory.ScanChatProgress

/**
 * How much of a chat has been scanned, from its bookmark (message UUID
 * anchors) and its current messages. Shared by the scanner, the chat list's
 * memory status and the Create Outline screen's scan progress line.
 */
object ScanChatStatus {

    /**
     * Index of the last scanned message, or -1 when nothing counts as
     * scanned: no bookmark, a pending complete rescan, or a bookmark none of
     * whose anchors still exist (then the chat is read again rather than
     * risk skipping anything).
     */
    fun bookmarkIndex(progress: ScanChatProgress?, messages: List<ScanMessage>): Int {
        if (progress == null || progress.needsFullRescan) return -1
        return ScanPosition.resolve(messages.map { it.id }, progress.bookmarkAnchors) ?: -1
    }

    /** True when an eligible message lies after the bookmark. */
    fun hasUnscanned(progress: ScanChatProgress?, messages: List<ScanMessage>): Boolean {
        val index = bookmarkIndex(progress, messages)
        return (index + 1 until messages.size).any { messages[it].eligible }
    }

    /** The chat-list memory status ([AnalysisBookmark.chatReviewState]), or
     *  null when the chat holds nothing the scanner reads. */
    fun reviewState(progress: ScanChatProgress?, messages: List<ScanMessage>): String? {
        if (progress?.claimRunId != null) return "processing"
        if (messages.none { it.eligible }) return null
        val index = bookmarkIndex(progress, messages)
        return AnalysisBookmark.chatReviewState(
            hasClaimedRows = false,
            hasUnscannedRows = (index + 1 until messages.size).any { messages[it].eligible },
            hasScannedPrefix = (0..index).any { messages[it].eligible }
        )
    }

    /** The Create Outline screen's coverage, in permanent message numbers
     *  (the numbers Message Details shows), which deleting a message never
     *  changes. A message saved before it had a number counts by position. */
    fun coverage(progress: ScanChatProgress?, messages: List<ScanMessage>): ChatScanCoverage.Status? {
        val index = bookmarkIndex(progress, messages)
        val scanned = (0..index).filter { messages[it].eligible }
        if (scanned.isEmpty()) return ChatScanCoverage.Status.Unscanned
        if (!hasUnscanned(progress, messages)) return ChatScanCoverage.Status.Complete
        fun numberAt(index: Int): Int = messages[index].number?.toInt() ?: (index + 1)
        return ChatScanCoverage.Status.Range(numberAt(scanned.first()), numberAt(scanned.last()))
    }
}
