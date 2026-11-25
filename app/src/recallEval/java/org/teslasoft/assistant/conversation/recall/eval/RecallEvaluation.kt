/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall.eval

import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.SpeakerTitlePolicy
import org.teslasoft.assistant.conversation.recall.ExclusionReason
import org.teslasoft.assistant.conversation.recall.RecallBudget
import org.teslasoft.assistant.conversation.recall.RecallEngine
import org.teslasoft.assistant.conversation.recall.RecallIndex
import org.teslasoft.assistant.conversation.recall.RecallMessageSource
import org.teslasoft.assistant.conversation.recall.RecallMode
import org.teslasoft.assistant.conversation.recall.RecallRequest
import org.teslasoft.assistant.conversation.recall.RecallResult
import org.teslasoft.assistant.conversation.recall.RecallTuning
import org.teslasoft.assistant.conversation.recall.SentInFull

/**
 * Where the evaluation's index and vectors come from: the real encrypted
 * index and the real model on a device, or an in-memory copy with vectors
 * read on a desktop while developing.
 */
interface RecallEvalBackend {
    val name: String
    val vectorModel: String
    val vectorDimensions: Int

    /** Indexes [messages] as chat [indexChatId] with [titles] (cutting, search text, keywords; vectors may wait). */
    fun index(indexChatId: String, messages: List<IndexSourceMessage>, titles: SpeakerTitlePolicy)

    /** Makes every waiting vector. */
    fun makeVectors()

    val recallIndex: RecallIndex
    val messages: RecallMessageSource

    /** The message's query vector, and the milliseconds the read took (0 when it was already read). */
    fun queryVector(text: String): Pair<FloatArray?, Long>
}

/**
 * Scores every conversation test question on its own with the real model's
 * numbers (memory development plan G.16 Step 14, "Done when"), under the four
 * speaker-title variants, and the follow-ups under each context-assistance
 * mode. Results only; nothing is tuned here.
 */
class RecallEvaluation(
    private val fixtures: RecallFixtures,
    private val backend: RecallEvalBackend,
    private val tuning: RecallTuning = RecallTuning()
) {
    enum class Variant(val label: String, val titles: SpeakerTitlePolicy, val filter: Boolean) {
        A("A: Human/Companion", RecallFixtures.GENERIC, false),
        B("B: titles with recorded names", RecallFixtures.WITH_NAMES, false),
        C("C: generic titles, identity-ID filter", RecallFixtures.GENERIC, true),
        BC("B+C: names and identity-ID filter", RecallFixtures.WITH_NAMES, true)
    }

    private fun indexId(titles: SpeakerTitlePolicy, chat: String) =
        (if (titles === RecallFixtures.WITH_NAMES) "B/" else "A/") + chat

    private val engine by lazy { RecallEngine(backend.recallIndex, backend.messages, null, tuning) }

    fun buildIndex() {
        for (chat in fixtures.chats.values) {
            backend.index(indexId(RecallFixtures.GENERIC, chat.id), chat.messages, RecallFixtures.GENERIC)
            backend.index(indexId(RecallFixtures.WITH_NAMES, chat.id), chat.messages, RecallFixtures.WITH_NAMES)
        }
        backend.makeVectors()
    }

    /**
     * Retrieval is scored on what it finds (G.15 point 1): only a summary's
     * kept messages count as already read. What the whole-chat rule (the last
     * few exchanges) would remove is reported separately ([lastExchanges]).
     */
    private fun sentInFull(chat: RecallFixtures.Chat): SentInFull = SentInFull.Messages(chat.keptInFull().orEmpty())

    /** The opening messages of the chat's last few exchanges, which the whole-chat rule treats as already read. */
    private fun lastExchanges(chat: RecallFixtures.Chat): Set<String> =
        if (chat.keptInFull() != null) emptySet()
        else chat.messages.filter { !it.isBot }.map { it.messageId }.takeLast(tuning.recentExchangesSentInFull).toSet()

    private fun request(
        indexChat: String, chat: RecallFixtures.Chat, message: String, at: Long,
        mode: RecallMode = RecallMode.OFF, helper: String? = null, carried: List<String> = emptyList(),
        filter: List<Set<String>>? = null
    ): Pair<RecallRequest, Long> {
        val (vector, readMillis) = backend.queryVector(message)
        val pieces = org.teslasoft.assistant.preferences.memory.librarian.LongMessageSearch.cut(message, tuning.pieceWords)
        val pieceVectors = if (pieces.size > 1) pieces.associate { it.text to backend.queryVector(it.text).first }
            .filterValues { it != null }.mapValues { it.value!! } else emptyMap()
        val helperVector = helper?.let { backend.queryVector(it).first }
        return RecallRequest(
            chatId = indexChat,
            message = message,
            currentMessageId = null,
            nowMillis = at,
            zone = ZoneOffset.UTC,
            vectorModel = backend.vectorModel,
            vectorDimensions = backend.vectorDimensions,
            sentInFull = sentInFull(chat),
            messageVector = vector,
            pieceVectors = pieceVectors,
            mode = mode,
            helperQuery = helper,
            helperVector = helperVector,
            carriedExchangeIds = carried,
            memorySubjectChanged = null,
            identityFilter = filter
        ) to readMillis
    }

    class Scored(
        val id: String,
        val group: String,
        val variant: String,
        val expect: List<String>,
        val mustNot: List<String>,
        val returned: List<String>,
        val alreadySent: List<String>,
        val details: JSONArray,
        val searchMillis: Long,
        val queryReadMillis: Long,
        val skipped: String?,
        val extra: JSONObject = JSONObject()
    ) {
        val found get() = expect.filter { it in returned }
        val missed get() = expect.filter { it !in returned && it !in alreadySent }
        val wronglyAdded get() = returned.filter { it !in expect }
        val mustNotAdded get() = returned.filter { it in mustNot }

        fun json(): JSONObject = JSONObject()
            .put("id", id).put("group", group).put("variant", variant)
            .put("expect", JSONArray(expect)).put("found", JSONArray(found)).put("missed", JSONArray(missed))
            .put("already_in_request", JSONArray(alreadySent))
            .put("wrongly_added", JSONArray(wronglyAdded)).put("must_not_added", JSONArray(mustNotAdded))
            .put("returned", details).put("search_ms", searchMillis).put("query_read_ms", queryReadMillis)
            .put("skipped", skipped ?: JSONObject.NULL).put("extra", extra)
    }

    private fun score(
        id: String, group: String, variant: String, expect: List<String>, mustNot: List<String>,
        result: RecallResult, readMillis: Long, extra: JSONObject = JSONObject()
    ): Scored {
        val details = JSONArray()
        val returned = ArrayList<String>()
        var alreadySent = emptyList<String>()
        when (result) {
            is RecallResult.Found -> {
                for (e in result.exchanges) {
                    returned.add(e.exchangeId)
                    details.put(JSONObject()
                        .put("exchange", e.exchangeId).put("origin", e.origin.name).put("score", round(e.score))
                        .put("similarity", e.best?.similarity?.let(::round) ?: JSONObject.NULL)
                        .put("standout", e.best?.standout?.let(::round) ?: JSONObject.NULL)
                        .put("keyword", e.best?.keywordScore?.let(::round) ?: JSONObject.NULL)
                        .put("terms", JSONArray(e.best?.matchedTerms.orEmpty()))
                        .put("query", e.best?.let { "${it.query}${if (it.pieceNumber > 0) it.pieceNumber else ""}" } ?: JSONObject.NULL)
                        .put("passed", e.best?.let { listOfNotNull("meaning".takeIf { _ -> it.passedMeaning }, "certain".takeIf { _ -> it.passedCertain }, "keyword".takeIf { _ -> it.passedKeyword }).joinToString("+") } ?: "carried")
                        .put("in_time_range", e.inTimeRange).put("rrf", round(e.rankFusion, 5)))
                }
                alreadySent = expect.filter { exp -> result.report.excluded.any { it.exchangeId == exp && it.reason == ExclusionReason.SENT_IN_FULL } }
                extra.put("follow_up", result.report.followUp.name)
                extra.put("waiting_passages", result.report.waitingPassages)
                // Why each expected exchange was missed, when the report can tell.
                val why = JSONObject()
                for (exp in expect.filter { it !in returned }) {
                    val excl = result.report.excluded.firstOrNull { it.exchangeId == exp }
                    val near = result.report.borderline.firstOrNull { (it.passage.exchangeId ?: it.passage.messageId) == exp }
                    why.put(exp, when {
                        excl != null -> "excluded: ${excl.reason}"
                        near != null -> "borderline: similarity ${near.similarity?.let(::round)}, stand-out ${near.standout?.let(::round)}, keywords ${round(near.keywordScore)}"
                        else -> "did not pass the gate"
                    })
                }
                extra.put("why_missed", why)
                // Rank fusion's order of the same exchanges, for the comparison.
                extra.put("top3_blend", JSONArray(result.exchanges.take(3).map { it.exchangeId }))
                extra.put("top3_rrf", JSONArray(result.exchanges.sortedByDescending { it.rankFusion }.take(3).map { it.exchangeId }))
            }
            is RecallResult.Skipped -> Unit
        }
        val millis = result.report?.millis ?: 0
        return Scored(id, group, variant, expect, mustNot, returned, alreadySent, details, millis, readMillis,
            (result as? RecallResult.Skipped)?.reason?.name, extra)
    }

    fun questions(): List<Scored> {
        val out = ArrayList<Scored>()
        for (variant in Variant.entries) {
            for (q in fixtures.questions) {
                val chat = fixtures.chats.getValue(q.chat)
                val filter = if (variant.filter) fixtures.resolve(q.names).takeIf { it.isNotEmpty() } else null
                val (req, readMillis) = request(indexId(variant.titles, q.chat), chat, q.message, q.askedAtMillis, filter = filter)
                val result = engine.search(req, RecallBudget.unlimited())
                val extra = JSONObject().put("situation", q.situation).put("set", q.set)
                    .put("identity_filter", filter?.let { JSONArray(it.map { g -> JSONArray(g.sorted()) }) } ?: JSONObject.NULL)
                q.early?.let { extra.put("early", it) }
                q.later?.let { extra.put("later", it) }
                q.required?.let { extra.put("required", it) }
                extra.put("expected_in_last_exchanges", JSONArray(q.expect.filter { it in lastExchanges(chat) }))
                out.add(score(q.id, group(q.set, q.situation), variant.name, q.expect, q.mustNot, result, readMillis, extra))
            }
        }
        return out
    }

    /** Follow-ups in chats without saved memories, under each context-assistance mode (variant A). */
    fun followUps(): List<Scored> {
        val out = ArrayList<Scored>()
        for (f in fixtures.followUps) {
            val chat = fixtures.chats.getValue(f.chat)
            val id = indexId(RecallFixtures.GENERIC, f.chat)
            val group = group(f.set, "follow_up_${f.kind}")
            // Off: the message alone.
            run {
                val (req, ms) = request(id, chat, f.message, f.askedAtMillis)
                out.add(score(f.id, group, "OFF", f.expect, f.mustNot, engine.search(req, RecallBudget.unlimited()), ms))
            }
            // API AI: the helper's query as a second search (hand-written stand-in, no model call).
            run {
                val (req, ms) = request(id, chat, f.message, f.askedAtMillis, RecallMode.API_AI, helper = f.helperQuery)
                out.add(score(f.id, group, "API_AI", f.expect, f.mustNot, engine.search(req, RecallBudget.unlimited()), ms))
            }
            // Code-based: the previous message's results are delivered (at most the assembly's count),
            // then the follow-up is searched with them carried and no saved-memory subject.
            run {
                val (prev, _) = request(id, chat, f.previousMessage, f.askedAtMillis, RecallMode.CODE_BASED)
                val delivered = (engine.search(prev, RecallBudget.unlimited()) as? RecallResult.Found)
                    ?.exchanges?.take(tuning.maxCarriedExchanges)?.map { it.exchangeId }.orEmpty()
                val (req, ms) = request(id, chat, f.message, f.askedAtMillis, RecallMode.CODE_BASED, carried = delivered)
                val extra = JSONObject().put("delivered_before", JSONArray(delivered))
                out.add(score(f.id, group, "CODE_BASED", f.expect, f.mustNot, engine.search(req, RecallBudget.unlimited()), ms, extra))
            }
        }
        return out
    }

    /** Recall's own time with the typed and hands-free allowances, reading the message with the real model each time. */
    fun timing(read: (String) -> Long): JSONArray {
        val rows = JSONArray()
        for (q in fixtures.questions) {
            val chat = fixtures.chats.getValue(q.chat)
            val readMillis = read(q.message)
            val (req, _) = request(indexId(RecallFixtures.GENERIC, q.chat), chat, q.message, q.askedAtMillis)
            val started = System.nanoTime()
            val result = engine.search(req, RecallBudget.unlimited())
            val searchMillis = (System.nanoTime() - started) / 1_000_000
            val total = readMillis + searchMillis
            rows.put(JSONObject().put("id", q.id).put("query_read_ms", readMillis).put("search_ms", searchMillis)
                .put("total_ms", total).put("within_typed", total <= tuning.typedAllowanceMillis)
                .put("within_hands_free", total <= tuning.handsFreeAllowanceMillis)
                .put("passages", result.report?.eligiblePassages ?: 0))
        }
        return rows
    }

    private fun group(set: String, name: String) = if (set == "holdout") "holdout/$name" else name

    companion object {
        fun round(v: Double, digits: Int = 3): Double {
            var f = 1.0
            repeat(digits) { f *= 10 }
            return Math.round(v * f) / f
        }

        /** Totals by group and variant: questions, expected, found, missed, already in the request, wrongly added, must-not added. */
        fun totals(rows: List<Scored>): JSONArray {
            val out = JSONArray()
            rows.groupBy { it.variant to it.group }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (key, list) ->
                out.put(JSONObject().put("variant", key.first).put("group", key.second).put("questions", list.size)
                    .put("expected", list.sumOf { it.expect.size }).put("found", list.sumOf { it.found.size })
                    .put("missed", list.sumOf { it.missed.size }).put("already_in_request", list.sumOf { it.alreadySent.size })
                    .put("wrongly_added", list.sumOf { it.wronglyAdded.size })
                    .put("must_not_added", list.sumOf { it.mustNotAdded.size })
                    .put("skipped", list.count { it.skipped != null }))
            }
            return out
        }
    }
}
