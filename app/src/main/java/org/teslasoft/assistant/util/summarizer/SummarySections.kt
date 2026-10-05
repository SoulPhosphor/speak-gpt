/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Summarizer Bookmarks and summary sections (owner-approved design, Oct 4
 * 2026). The automatic Summarizer keeps the chat's older history as separate,
 * chronological sections. Each section owns specific stored messages by their
 * permanent IDs and has its own summary text, written only from its own
 * messages (plus, as context only, the exchange just before it). The chat AI
 * receives every section's text in order, exactly as the Summary screen shows
 * it. Editing or deleting a message affects only the section that owns it
 * (and a section that used it as context). Compact keeps its own separate
 * single summary and is not part of this.
 */
object SummarySections {

    /** One stored, model-visible conversation message, in chat order. */
    data class SourceMessage(
        val id: String,
        val isBot: Boolean,
        val text: String,
        val timeMillis: Long?
    )

    data class Section(
        val id: String,
        /** The messages this section summarizes, in chat order. */
        val messageIds: List<String>,
        /** Each owned message's text fingerprint when the text was written. */
        val fingerprints: List<Int>,
        /** The preceding exchange the summarizer saw as context only. */
        val contextIds: List<String>,
        val contextFingerprints: List<Int>,
        val text: String,
        /** The user saved their own wording for this section. */
        val edited: Boolean = false,
        /** Its source messages changed since the text was written. */
        val needsUpdate: Boolean = false,
        /** A summary from before sections existed, carried over as one block. */
        val legacy: Boolean = false,
        /** The user chose to keep this text after its messages changed. */
        val kept: Boolean = false
    ) {
        /** Rewritten automatically: only unedited, non-legacy sections the
         *  user did not choose to keep. A user's edit, a legacy block, or a
         *  kept summary is never silently replaced. */
        val awaitsRegeneration: Boolean get() = needsUpdate && !edited && !legacy && !kept
    }

    fun fingerprint(text: String): Int = text.hashCode()

    /* ------------------------------ storage ------------------------------ */

    fun toJson(sections: List<Section>): String {
        val array = JSONArray()
        for (s in sections) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("messageIds", JSONArray(s.messageIds))
                    .put("fingerprints", JSONArray(s.fingerprints))
                    .put("contextIds", JSONArray(s.contextIds))
                    .put("contextFingerprints", JSONArray(s.contextFingerprints))
                    .put("text", s.text)
                    .put("edited", s.edited)
                    .put("needsUpdate", s.needsUpdate)
                    .put("legacy", s.legacy)
                    .put("kept", s.kept)
            )
        }
        return array.toString()
    }

    fun fromJson(json: String, onFailure: ((Exception) -> Unit)? = null): List<Section> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: run {
                    if (onFailure != null) throw IllegalStateException("Invalid Summary Section record at index $index")
                    return@mapNotNull null
                }
                fun strings(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }
                }
                fun ints(key: String): List<Int> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.optInt(it) }
                }
                val ids = strings("messageIds")
                val prints = ints("fingerprints")
                val contextIds = strings("contextIds")
                val contextPrints = ints("contextFingerprints")
                if (ids.isEmpty() || ids.size != prints.size || contextIds.size != contextPrints.size) {
                    if (onFailure != null) throw IllegalStateException("Invalid Summary Section ownership at index $index")
                    return@mapNotNull null
                }
                Section(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    messageIds = ids,
                    fingerprints = prints,
                    contextIds = contextIds,
                    contextFingerprints = contextPrints,
                    text = o.optString("text"),
                    edited = o.optBoolean("edited"),
                    needsUpdate = o.optBoolean("needsUpdate"),
                    legacy = o.optBoolean("legacy"),
                    kept = o.optBoolean("kept")
                )
            }
        } catch (e: Exception) {
            onFailure?.invoke(e)
            emptyList()
        }
    }

    /* ------------------------------ repair ------------------------------ */

    data class Reconciled(val sections: List<Section>, val changed: Boolean)

    /**
     * Brings sections in line with the current messages. A deleted message
     * leaves its section (a section with no messages left is removed). An
     * edited or deleted owned message, or a changed context message, marks
     * only that section as needing an update. Sections are never rebalanced.
     */
    fun reconcile(sections: List<Section>, current: List<SourceMessage>): Reconciled {
        val byId = current.associateBy { it.id }
        var changed = false
        val out = ArrayList<Section>()
        for (s in sections) {
            val kept = s.messageIds.zip(s.fingerprints).filter { (id, _) -> id in byId }
            if (kept.isEmpty()) {
                changed = true
                continue
            }
            val removed = kept.size != s.messageIds.size
            val edited = kept.any { (id, print) -> fingerprint(byId.getValue(id).text) != print }
            val contextChanged = s.contextIds.zip(s.contextFingerprints).any { (id, print) ->
                byId[id]?.let { fingerprint(it.text) != print } ?: true
            }
            if (removed || edited || contextChanged) {
                changed = true
                val keptContext = s.contextIds.filter { it in byId }
                out.add(
                    s.copy(
                        messageIds = kept.map { it.first },
                        fingerprints = kept.map { fingerprint(byId.getValue(it.first).text) },
                        contextIds = keptContext,
                        contextFingerprints = keptContext.map { fingerprint(byId.getValue(it).text) },
                        needsUpdate = true
                    )
                )
            } else {
                out.add(s)
            }
        }
        return Reconciled(out, changed)
    }

    /** How many of the chat's first messages the sections cover. Sections
     *  always cover one unbroken run from the start of the chat. */
    fun coveredCount(sections: List<Section>, current: List<SourceMessage>): Int {
        val owned = sections.flatMapTo(HashSet()) { it.messageIds }
        var count = 0
        while (count < current.size && current[count].id in owned) count++
        return count
    }

    /** Sections that [reconciled] newly marks as needing an update compared
     *  with [before]: the sections an edit just affected. */
    fun newlyAffected(before: List<Section>, reconciled: List<Section>): List<Section> {
        val byId = before.associateBy { it.id }
        return reconciled.filter { it.needsUpdate && it != byId[it.id] }
    }

    /** The section owning [messageId], if any. */
    fun owner(sections: List<Section>, messageId: String): Section? =
        sections.firstOrNull { messageId in it.messageIds }

    /* ------------------------------ forming sections ------------------------------ */

    /**
     * The next new section: messages from [covered] up to a cut before a user
     * message, so a prompt is never separated from its reply. It forms once
     * it holds at least [batchSize] messages, or earlier when the next user
     * prompt is on a later calendar date (the older date is finished as its
     * own section). Only messages already outside the Complete Messages
     * window ([windowEdge]) are eligible. [force] also takes a final short
     * run up to the window edge.
     */
    fun nextRange(
        current: List<SourceMessage>,
        covered: Int,
        windowEdge: Int,
        batchSize: Int,
        force: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): IntRange? {
        val edge = windowEdge.coerceAtMost(current.size)
        if (covered >= edge) return null
        var firstUserDate: LocalDate? = current[covered].takeIf { !it.isBot }?.let { dateOf(it, zone) }
        var lastUserCut = -1
        for (i in covered + 1..edge) {
            if (i == edge) {
                val cut = if (i == current.size || !current[i].isBot) i else lastUserCut
                if (!force && (cut <= covered || cut - covered < batchSize)) return null
                return if (cut > covered) covered until cut else null
            }
            val message = current[i]
            if (message.isBot) continue
            lastUserCut = i
            val date = dateOf(message, zone)
            val dateChanged = firstUserDate != null && date != null && date != firstUserDate
            if (i - covered >= batchSize || dateChanged) return covered until i
            if (firstUserDate == null) firstUserDate = date
        }
        return null
    }

    /** The exchange just before [start]: its user prompt and the replies
     *  that followed. Context only — never owned by the next section. */
    fun contextBefore(current: List<SourceMessage>, start: Int): List<SourceMessage> {
        if (start <= 0) return emptyList()
        val prompt = (start - 1 downTo 0).firstOrNull { !current[it].isBot } ?: return emptyList()
        return current.subList(prompt, start)
    }

    fun newSection(
        owned: List<SourceMessage>,
        context: List<SourceMessage>,
        text: String,
        id: String = UUID.randomUUID().toString()
    ): Section = Section(
        id = id,
        messageIds = owned.map { it.id },
        fingerprints = owned.map { fingerprint(it.text) },
        contextIds = context.map { it.id },
        contextFingerprints = context.map { fingerprint(it.text) },
        text = text
    )

    /** True when [owned] and [context] still match the current messages, so
     *  a summary written from them may be saved. */
    fun sourceStillCurrent(
        owned: List<SourceMessage>,
        context: List<SourceMessage>,
        current: List<SourceMessage>
    ): Boolean {
        val byId = current.associateBy { it.id }
        if (current.map { it.id }.distinct().size != current.size) return false
        val start = current.indexOfFirst { it.id == owned.firstOrNull()?.id }
        if (start < 0 || current.drop(start).take(owned.size) != owned) return false
        return contextBefore(current, start) == context &&
            (owned + context).all { m -> byId[m.id] == m }
    }

    /** A changed decision or a manual edit invalidates an in-flight rewrite. */
    fun canCommitReplacement(started: Section, current: Section?): Boolean =
        current != null && current == started && current.awaitsRegeneration

    /**
     * An old single summary, from before sections, becomes one legacy block
     * covering the messages it was written from. No finer provenance is
     * invented, and nothing is re-summarized to manufacture it.
     */
    fun legacyBlock(summary: String, folded: Int, current: List<SourceMessage>): Section? {
        if (summary.isBlank() || folded <= 0) return null
        val owned = current.take(folded.coerceAtMost(current.size))
        if (owned.isEmpty()) return null
        return newSection(owned, emptyList(), summary).copy(legacy = true)
    }

    /* ------------------------------ presentation ------------------------------ */

    /** The section's time span from its first and last user prompts (falling
     *  back to any of its messages), or null when none carry a time. */
    fun timeSpan(section: Section, current: List<SourceMessage>): Pair<Long, Long>? {
        val byId = current.associateBy { it.id }
        val owned = section.messageIds.mapNotNull { byId[it] }
        val userTimes = owned.filter { !it.isBot }.mapNotNull { it.timeMillis }
        val times = userTimes.ifEmpty { owned.mapNotNull { it.timeMillis } }
        if (times.isEmpty()) return null
        return times.first() to times.last()
    }

    /** The text the chat AI receives for the sections: a short direction
     *  note, then every section's text in order. No dates or IDs. */
    fun injection(header: String, sections: List<Section>): String? {
        val texts = sections.map { it.text.trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return null
        return header + "\n\n" + texts.joinToString("\n\n")
    }

    private fun dateOf(message: SourceMessage, zone: ZoneId): LocalDate? =
        message.timeMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
