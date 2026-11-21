/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * A place in a chat, kept as message UUIDs only (never text). The first id is
 * the exact message; the rest are earlier messages, close ones first and then
 * at doubling distances, so the place can still be found when the exact
 * message was deleted. If every anchor is gone the place is unknown, and
 * callers then fall back to reading more rather than less.
 */
object ScanPosition {

    private const val NEAR_ANCHORS = 8

    /** Anchors for the message at [index] in [ids] (empty for index < 0). */
    fun anchors(ids: List<String>, index: Int): List<String> {
        if (index < 0 || index >= ids.size) return emptyList()
        val out = LinkedHashSet<String>()
        for (back in 0 until NEAR_ANCHORS) {
            val i = index - back
            if (i < 0) break
            out.add(ids[i])
        }
        var step = NEAR_ANCHORS * 2
        while (index - step >= 0) {
            out.add(ids[index - step])
            step *= 2
        }
        if (index > 0) out.add(ids[0])
        return out.toList()
    }

    /** The index of the first anchor still present, or null when none is. */
    fun resolve(ids: List<String>, anchors: List<String>): Int? {
        if (anchors.isEmpty()) return null
        val indexById = HashMap<String, Int>(ids.size)
        ids.forEachIndexed { i, id -> indexById.putIfAbsent(id, i) }
        for (anchor in anchors) indexById[anchor]?.let { return it }
        return null
    }

    fun encode(anchors: List<String>): String = JSONArray(anchors).toString()

    fun decode(raw: String?): List<String> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * The validated reading position inside one frozen chat range (memory
 * development plan M.2 Stage 6A): the fingerprint of every message already
 * read, in order, and the message read only partly with the offset reached.
 * Saved together with the working drafts after each completed section.
 */
data class ScanCheckpoint(
    val readFingerprints: LinkedHashMap<String, String> = LinkedHashMap(),
    val partialMessageId: String? = null,
    val partialOffset: Int = 0,
    val partialFingerprint: String? = null,
    val sectionsDone: Int = 0
) {
    val isEmpty: Boolean get() = readFingerprints.isEmpty() && partialMessageId == null

    /**
     * True while everything already read is still exactly what was read:
     * no read message was edited, regenerated, hidden, shown or deleted.
     */
    fun stillValid(rangeMessages: List<ScanMessage>): Boolean {
        val byId = rangeMessages.associateBy { it.id }
        for ((id, fingerprint) in readFingerprints) {
            if (byId[id]?.fingerprint != fingerprint) return false
        }
        val partial = partialMessageId ?: return true
        val message = byId[partial] ?: return false
        if (message.fingerprint != partialFingerprint) return false
        return partialOffset in 0..message.text.length
    }

    /** The first unread message of [rangeMessages] and the offset to start
     *  at inside it; null when the whole range has been read. */
    fun nextUnread(rangeMessages: List<ScanMessage>): Pair<Int, Int>? {
        for ((index, message) in rangeMessages.withIndex()) {
            if (message.id in readFingerprints) continue
            val offset = if (message.id == partialMessageId) partialOffset else 0
            return index to offset
        }
        return null
    }

    /**
     * The checkpoint after a section that ended at [endMessageIndex] /
     * [endOffset] (exclusive) succeeded. Every range message up to that point
     * is recorded as read, including messages the scanner does not send
     * (hidden or unfinished), so a later change to any of them is noticed.
     */
    fun advancedTo(rangeMessages: List<ScanMessage>, endMessageIndex: Int, endOffset: Int): ScanCheckpoint {
        val read = LinkedHashMap(readFingerprints)
        var partialId: String? = null
        var partialAt = 0
        var partialFp: String? = null
        for (index in 0..endMessageIndex.coerceAtMost(rangeMessages.lastIndex)) {
            val message = rangeMessages[index]
            if (index == endMessageIndex && endOffset < message.text.length && message.eligible) {
                partialId = message.id
                partialAt = endOffset
                partialFp = message.fingerprint
            } else {
                read[message.id] = message.fingerprint
            }
        }
        return ScanCheckpoint(read, partialId, partialAt, partialFp, sectionsDone + 1)
    }

    fun encode(): String = JSONObject().apply {
        put("read", JSONArray().apply {
            readFingerprints.forEach { (id, fp) -> put(JSONArray().put(id).put(fp)) }
        })
        partialMessageId?.let {
            put("partial", it)
            put("offset", partialOffset)
            put("partialFingerprint", partialFingerprint)
        }
        put("sections", sectionsDone)
    }.toString()

    companion object {
        fun decode(raw: String?): ScanCheckpoint {
            if (raw.isNullOrBlank()) return ScanCheckpoint()
            return try {
                val o = JSONObject(raw)
                val read = LinkedHashMap<String, String>()
                val a = o.optJSONArray("read") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val pair = a.optJSONArray(i) ?: continue
                    read[pair.optString(0)] = pair.optString(1)
                }
                ScanCheckpoint(
                    readFingerprints = read,
                    partialMessageId = o.optString("partial", "").takeIf { it.isNotBlank() },
                    partialOffset = o.optInt("offset", 0),
                    partialFingerprint = o.optString("partialFingerprint", "").takeIf { it.isNotBlank() },
                    sectionsDone = o.optInt("sections", 0)
                )
            } catch (_: Exception) {
                ScanCheckpoint()
            }
        }
    }
}
