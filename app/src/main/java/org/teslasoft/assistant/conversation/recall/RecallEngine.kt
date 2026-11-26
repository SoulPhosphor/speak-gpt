/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import org.teslasoft.assistant.conversation.index.IndexEligibility
import org.teslasoft.assistant.conversation.index.IndexFingerprint
import org.teslasoft.assistant.conversation.index.IndexSourceMessage
import org.teslasoft.assistant.conversation.index.Speaker
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.memory.librarian.LiveRetrieval
import org.teslasoft.assistant.preferences.memory.librarian.LongMessageSearch
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/** One read of a chat: every stored message in stored order, and the version it was read at. */
class ChatSnapshot(val messages: List<IndexSourceMessage>, val version: String)

/** The chat as stored now, read through the app's chat-reading layer (never written). */
interface RecallMessageSource {
    /** The chat as stored now, or null when it cannot be read now. */
    fun read(chatId: String): ChatSnapshot?

    /** Whether the chat may have changed since [snapshot] was read; true whenever that cannot be told. */
    fun changedSince(chatId: String, snapshot: ChatSnapshot): Boolean
}

/**
 * Conversation recall inside the current chat: retrieval stages 1 to 8 of
 * the memory development plan's G.6, with the follow-up contract and time
 * limit approved in G36 (Owner, 2026-11-25). Given a chat and a message it
 * returns the exchanges to offer, best first, with every score and reason
 * (G.8a point 2). It writes nothing, adds nothing to any request, makes no
 * paid call and never searches another chat. Stage 9 and a re-ranker are not
 * built.
 *
 * The index is updated in the background while a search runs, so nothing
 * read from it is trusted alone: every passage is used only while its
 * message is eligible and has the same text as when it was indexed, and
 * every exchange is checked again against the stored chat just before it is
 * handed on.
 */
class RecallEngine(
    private val index: RecallIndex,
    private val messages: RecallMessageSource,
    private val reader: QueryVectorReader?,
    private val tuning: RecallTuning = RecallTuning(),
    private val clock: RecallClock = RecallClock.SYSTEM
) {
    private class Search(val kind: QueryKind, val pieceNumber: Int, val text: String, val vector: FloatArray?) {
        var terms: List<String> = emptyList()
        var termWeight: Double = 0.0
        val similarities = HashMap<Long, Double>()
        var median = 0.0
        var spread = 0.0
        val meaningRank = HashMap<Long, Int>()
        val keywordRank = HashMap<Long, Int>()
    }

    /** One stored message as first read, with what retrieval needs to know about it. */
    private class Stored(val message: IndexSourceMessage, val position: Int, val exchangeKey: String?) {
        val eligible = IndexEligibility.isEligible(message)
        val fingerprint: String by lazy { IndexFingerprint.of(message.modelText) }
    }

    private class OutOfTime : RuntimeException()

    /** The index could not be read: closed by a recovery or deletion, damaged, or locked. */
    private class IndexUnavailable : RuntimeException()

    /** The chat could not be read: a save in progress, locked or damaged storage. */
    private class ChatUnavailable : RuntimeException()

    /** A read of the index; any failure means recall hands on nothing for this message. Cancellation is never swallowed. */
    private inline fun <T> fromIndex(read: () -> T): T = try {
        read()
    } catch (e: OutOfTime) {
        throw e
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (_: Exception) {
        throw IndexUnavailable()
    }

    /** A read of the chat; any failure means recall hands on nothing for this message. */
    private inline fun <T> fromChat(read: () -> T): T = try {
        read()
    } catch (e: OutOfTime) {
        throw e
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (_: Exception) {
        throw ChatUnavailable()
    }

    fun search(request: RecallRequest, budget: RecallBudget): RecallResult {
        val started = clock.nanos()
        return try {
            run(request, budget, started)
        } catch (_: OutOfTime) {
            RecallResult.Skipped(SkipReason.OUT_OF_TIME, null)
        } catch (_: IndexUnavailable) {
            RecallResult.Skipped(SkipReason.INDEX_UNAVAILABLE, null)
        } catch (_: ChatUnavailable) {
            RecallResult.Skipped(SkipReason.CHAT_UNAVAILABLE, null)
        }
    }

    private fun check(budget: RecallBudget) {
        if (budget.expired()) throw OutOfTime()
    }

    private fun run(request: RecallRequest, budget: RecallBudget, started: Long): RecallResult {
        val chatId = request.chatId
        val read = fromChat { messages.read(chatId) } ?: return RecallResult.Skipped(SkipReason.CHAT_UNAVAILABLE, null)
        val recheck = Recheck(chatId, read)
        val stored = read.messages
        check(budget)

        // The chat as stored now: exchanges, as the index defines them (a user
        // message and everything after it up to the next user message), and
        // which messages may be recalled.
        val all = ArrayList<Stored>(stored.size)
        var exchange: String? = null
        stored.forEachIndexed { position, m ->
            if (!m.isBot) exchange = m.messageId.takeIf { it.isNotBlank() }
            all.add(Stored(m, position, exchange ?: m.messageId.takeIf { it.isNotBlank() }))
        }
        val eligibleNow = HashMap<String, Stored>()
        for (s in all) if (s.eligible && s.message.messageId !in eligibleNow) eligibleNow[s.message.messageId] = s
        val members = all.filter { it.exchangeKey != null && it.message.messageId.isNotBlank() }.groupBy { it.exchangeKey!! }

        // Retrieval stage 3, eligibility: a passage counts only while its
        // message is eligible now with the text it was indexed from.
        val indexed = fromIndex { index.passages(chatId) }
        check(budget)
        val usable = indexed.filter { p ->
            val now = eligibleNow[p.messageId]
            now != null && now.fingerprint == p.textFingerprint && p.messageId != request.currentMessageId
        }
        val outdated = indexed.count { p -> eligibleNow[p.messageId]?.fingerprint != p.textFingerprint }
        // The identity filter (evaluation variant C) limits which passages may be
        // offered; the chat's typical similarity and word rarity are still
        // measured over every eligible passage (G.6 stage 4).
        val filteredOut = HashSet<String>()
        val offerable: Set<Long>? = request.identityFilter?.takeIf { it.isNotEmpty() }?.let { groups ->
            val kept = usable.filter { p -> groups.all { group -> p.identityIds.any { it in group } } }
            val keptKeys = kept.map { keyOf(it) }.toSet()
            usable.map { keyOf(it) }.filterTo(filteredOut) { it !in keptKeys }
            kept.map { it.rowId }.toSet()
        }
        val byRow = usable.associateBy { it.rowId }
        val rowsByExchange = usable.groupBy { keyOf(it) }
        val passageCount = usable.size

        // Retrieval stage 2.
        val timeRange = RecallTimeReference.read(request.message, request.nowMillis, request.zone)

        // Retrieval stage 1: what to search with. Each is a separate search.
        val skipped = ArrayList<Pair<QueryKind, String>>()
        val modelReady = request.vectorModel != null
        fun vectorFor(text: String, given: FloatArray?): FloatArray? {
            if (!modelReady) return null
            if (given != null) return given
            val r = reader ?: return null
            // A read that fails leaves that search to keywords; it never fails the send.
            val v = try {
                r.read(text, budget.remainingMillis())
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            check(budget)
            return v
        }
        val searches = ArrayList<Search>()
        searches.add(Search(QueryKind.WHOLE, 0, request.message, vectorFor(request.message, request.messageVector)))
        if (!modelReady) skipped.add(QueryKind.WHOLE to "no embedding model: keywords only")

        // Keyword rarity in this chat: how many usable passages hold a term. A
        // long message has many words, so at most maxTermLookups are looked up:
        // the helper's query first, then the message's words shared out evenly
        // across its pieces, longest first within each piece, so every part of
        // the message is searched. A message of up to one piece has all its
        // words looked up. A word not looked up counts as one the chat does not
        // hold, which can only make a keyword match harder.
        val pieces = LongMessageSearch.cut(request.message, tuning.pieceWords)
        val lookable: Set<String> = run {
            val order = LinkedHashSet<String>()
            if (request.mode == RecallMode.API_AI) request.helperQuery?.let { order.addAll(LiveRetrieval.queryTerms(it)) }
            val parts = (if (pieces.size > 1) pieces.map { it.text } else listOf(request.message))
                .map { part -> LiveRetrieval.queryTerms(part).sortedByDescending { it.length }.toMutableList() }
            while (parts.any { it.isNotEmpty() }) for (part in parts) if (part.isNotEmpty()) order.add(part.removeAt(0))
            order.take(tuning.maxTermLookups).toSet()
        }
        val termRows = HashMap<String, Set<Long>>()
        fun rowsOf(term: String): Set<Long> = termRows.getOrPut(term) {
            if (term !in lookable) return@getOrPut emptySet()
            check(budget)
            fromIndex { index.rowsWithTerm(chatId, term) }.filterTo(HashSet()) { it in byRow }
        }
        fun idf(term: String): Double {
            val df = rowsOf(term).size
            return ln(1.0 + (passageCount - df + 0.5) / (df + 0.5))
        }

        if (pieces.size > 1) {
            if (!budget.mayStartOptional()) skipped.add(QueryKind.PIECE to "no time")
            else {
                val ordered = piecesByRarity(pieces) { t -> rowsOf(t).size }
                for (piece in ordered.take(tuning.maxPieces)) {
                    if (!budget.mayStartOptional()) {
                        skipped.add(QueryKind.PIECE to "no time from piece ${piece.number}")
                        break
                    }
                    searches.add(Search(QueryKind.PIECE, piece.number, piece.text, vectorFor(piece.text, request.pieceVectors[piece.text])))
                }
                if (pieces.size > tuning.maxPieces) skipped.add(QueryKind.PIECE to "piece limit")
            }
        }
        if (request.mode == RecallMode.API_AI) {
            val helper = request.helperQuery?.trim()?.takeIf { it.isNotEmpty() }
            when {
                helper == null -> skipped.add(QueryKind.HELPER to "no helper query")
                !budget.mayStartOptional() -> skipped.add(QueryKind.HELPER to "no time")
                else -> searches.add(Search(QueryKind.HELPER, 0, helper, vectorFor(helper, request.helperVector)))
            }
        }

        for (s in searches) if (modelReady && s.vector == null) skipped.add(s.kind to "not read: keywords only")

        // Keywords: each search's informative words, the rarest first.
        // Only words the chat holds can match, so the places go to them; every
        // word still weighs in the message's total, so a long message's one
        // accidental shared word stays a small share (G.6 stage 4).
        for (s in searches) {
            val terms = LiveRetrieval.queryTerms(s.text).toList()
            val (present, absent) = terms.partition { rowsOf(it).isNotEmpty() }
            s.terms = present.sortedWith(compareBy({ rowsOf(it).size }, { terms.indexOf(it) })).take(tuning.maxQueryTerms)
            s.termWeight = present.sumOf { idf(it) } + absent.size * ln(1.0 + (passageCount + 0.5) / 0.5)
        }

        // Meaning: every usable passage vector of this model, a batch at a
        // time, for every search together. Typical similarity is measured
        // over all of them, never over the best few (G.6 stage 4).
        val vectorSearches = searches.filter { it.vector != null }
        var withVector = 0
        if (modelReady) {
            var after = Long.MIN_VALUE
            while (true) {
                check(budget)
                val batch = fromIndex { index.vectors(chatId, request.vectorModel!!, request.vectorDimensions, after, tuning.vectorBatch) }
                if (batch.isEmpty()) break
                for (row in batch) {
                    if (row.rowId !in byRow) continue
                    withVector++
                    for (s in vectorSearches) {
                        if (s.vector!!.size != row.vector.size) continue
                        s.similarities[row.rowId] = VectorMath.cosine(s.vector, row.vector).toDouble()
                    }
                }
                after = batch.last().rowId
                if (batch.size < tuning.vectorBatch) break
            }
        }
        for (s in searches) {
            val values = s.similarities.values.toList()
            s.median = median(values)
            s.spread = if (values.isEmpty()) tuning.minSpread else max(1.4826 * median(values.map { abs(it - s.median) }), tuning.minSpread)
            s.similarities.entries.sortedByDescending { it.value }.forEachIndexed { i, e -> s.meaningRank[e.key] = i + 1 }
        }
        check(budget)

        // Stage 4: the gate for every passage that has a similarity or shares a word.
        val evidence = ArrayList<PassageEvidence>()
        val borderline = ArrayList<PassageEvidence>()
        for (s in searches) {
            val keywordScores = HashMap<Long, Pair<Double, List<String>>>()
            if (s.termWeight > 0) {
                for (t in s.terms) for (row in rowsOf(t)) {
                    val (score, matched) = keywordScores[row] ?: (0.0 to emptyList())
                    keywordScores[row] = (score + idf(t) / s.termWeight) to (matched + t)
                }
                keywordScores.entries.sortedByDescending { it.value.first }.forEachIndexed { i, e -> s.keywordRank[e.key] = i + 1 }
            }
            // The rarity a passage's shared words must reach together (keywordMaxShare).
            val shareRows = (tuning.keywordMaxShare * passageCount).coerceAtLeast(1.0)
            val rarityNeeded = if (tuning.keywordMaxShare >= 1.0) 0.0 else ln(1.0 + (passageCount - shareRows + 0.5) / (shareRows + 0.5))
            val rows = s.similarities.keys + keywordScores.keys
            val small = s.similarities.size < tuning.smallChatSize
            for (row in rows) {
                val passage = byRow[row] ?: continue
                if (offerable != null && row !in offerable) continue
                val sim = s.similarities[row]
                val standout = sim?.let { (it - s.median) / s.spread }
                val (kw, matched) = keywordScores[row] ?: (0.0 to emptyList())
                val rareEnough = matched.sumOf { idf(it) } >= rarityNeeded - 1e-9
                val e = evaluate(s, passage, sim, standout, kw.coerceIn(0.0, 1.0), matched.sorted(), small, timeRange, rareEnough)
                if (e.passed) evidence.add(e) else if (isBorderline(e, small)) borderline.add(e)
            }
        }
        check(budget)

        // Stage 3's bounded lists: each method keeps its best exchanges, each
        // with its strongest passage and a few supporting ones, so one long
        // exchange cannot fill the list with its own passages.
        val ownKinds = setOf(QueryKind.WHOLE, QueryKind.PIECE)
        // The message's own searches and the helper's lower-priority query are
        // bounded separately, so the helper's stronger matches can never push
        // out an exchange the message found itself.
        val kept = HashSet<PassageEvidence>()
        for (tier in evidence.partition { it.query in ownKinds }.toList()) {
            kept.addAll(boundedByExchange(tier.filter { it.passedMeaning || it.passedCertain }) { it.similarity ?: 0.0 })
            kept.addAll(boundedByExchange(tier.filter { it.passedKeyword }) { it.keywordScore })
        }

        // Stages 5 and 6: one score per passage (its best search), grouped into exchanges.
        val bestPerPassage = kept.groupBy { it.passage.rowId }.mapValues { (_, list) ->
            list.maxWith(compareBy<PassageEvidence> { if (it.query in ownKinds) 1 else 0 }.thenBy { it.score })
        }
        val exchanges = bestPerPassage.values.groupBy { keyOf(it.passage) }.map { (key, list) ->
            val sorted = list.sortedByDescending { it.score }
            val own = list.any { it.query in ownKinds }
            val ranked = if (own) sorted.filter { it.query in ownKinds } + sorted.filter { it.query !in ownKinds } else sorted
            val best = ranked.first()
            val extra = (list.size - 1).coerceAtMost(tuning.maxExtraPassages)
            val inRange = list.any { it.inTimeRange }
            val score = best.score + tuning.extraPassageBonus * extra + if (inRange) tuning.timeRangeBonus else 0.0
            Candidate(key, if (own) RecallOrigin.OWN else RecallOrigin.HELPER, score, best,
                ranked.drop(1).take(tuning.supportingPassages), list.size, inRange,
                list.maxOf { it.rankFusion })
        }.sortedWith(compareBy<Candidate> { it.origin.ordinal }.thenByDescending { it.score }.thenByDescending { it.best?.passage?.chatPosition ?: 0 })

        // Stages 7 and 8. The cheap checks run on every candidate; the check
        // against the chat as stored at this moment runs on the exchanges
        // about to be handed on, best first, until enough are kept.
        val excluded = ArrayList<Exclusion>()
        filteredOut.forEach { excluded.add(Exclusion(it, ExclusionReason.IDENTITY_FILTER)) }
        val inFull = inFullExchanges(request.sentInFull, all, request.currentMessageId)
        val accepted = ArrayList<Pair<Candidate, String>>()
        for (c in exchanges) {
            val reason = quickExclusion(c.key, request, members, inFull)
            if (reason != null) excluded.add(Exclusion(c.key, reason))
            else accepted.add(c to repeatKey(members.getValue(c.key)))
        }
        // Stage 8: identical exchanges (text and speaker order) count once, at
        // the rank of the best of them: the latest copy that is still stored as
        // it was is kept, and the others are repeats. A copy that changed during
        // the search gives way to the next latest one.
        val byText = accepted.groupBy({ it.second }, { it.first })
        val settled = HashSet<String>()
        val offered = ArrayList<RecalledExchange>()
        for ((c, text) in accepted) {
            if (!settled.add(text)) continue
            if (offered.size >= tuning.maxExchanges) continue
            val copies = byText.getValue(text).sortedByDescending { members.getValue(it.key).first().position }
            var kept: Candidate? = null
            for (copy in copies) {
                if (kept != null) {
                    excluded.add(Exclusion(copy.key, ExclusionReason.REPEAT))
                    continue
                }
                check(budget)
                if (recheck.stillStored(copy.key, members)) kept = copy
                else excluded.add(Exclusion(copy.key, ExclusionReason.NO_LONGER_ELIGIBLE))
            }
            kept?.let { offered.add(toExchange(it, members.getValue(it.key))) }
        }

        // Code-based continuity (G36): a carried exchange is offered again
        // only for a real follow-up, never merely because nothing else matched.
        val carriedChecks = ArrayList<CarriedCheck>()
        val followUp = followUp(request, offered, budget)
        if (followUp == FollowUpDecision.CHECKED) {
            val terms = RecallContinuity.subjectTerms(request.message)
            for (id in request.carriedExchangeIds.distinct().take(tuning.maxCarriedExchanges)) {
                if (offered.any { it.exchangeId == id }) {
                    carriedChecks.add(CarriedCheck(id, null, false, "already found"))
                    continue
                }
                val rows = rowsByExchange[id].orEmpty().map { it.rowId }.toSet()
                val share = RecallContinuity.followUpShare(terms) { t -> rowsOf(t).any { it in rows } }
                if (share != null && share < tuning.followUpTermShare) {
                    carriedChecks.add(CarriedCheck(id, share, false, "names something else"))
                    continue
                }
                val reason = when {
                    rows.isEmpty() -> ExclusionReason.NO_LONGER_ELIGIBLE
                    else -> quickExclusion(id, request, members, inFull)
                        ?: if (recheck.stillStored(id, members)) null else ExclusionReason.NO_LONGER_ELIGIBLE
                }
                if (reason != null) {
                    carriedChecks.add(CarriedCheck(id, share, false, reason.name))
                    excluded.add(Exclusion(id, reason))
                    continue
                }
                carriedChecks.add(CarriedCheck(id, share, true, "follow-up"))
                offered.add(toExchange(Candidate(id, RecallOrigin.CARRIED, 0.0, null, emptyList(), 0, false, 0.0), members.getValue(id)))
            }
        }

        check(budget)
        val report = RecallReport(
            eligiblePassages = passageCount,
            passagesWithVectors = withVector,
            passagesWithoutVector = (passageCount - withVector).coerceAtLeast(0),
            outdatedPassages = outdated,
            queriesRun = searches.map { it.kind to it.pieceNumber },
            queriesSkipped = skipped,
            timeRange = timeRange,
            borderline = borderline.sortedByDescending { it.score }.take(BORDERLINE_REPORTED),
            excluded = excluded,
            followUp = followUp,
            carriedChecks = carriedChecks,
            millis = (clock.nanos() - started) / 1_000_000
        )
        return RecallResult.Found(offered.take(tuning.maxExchanges), report)
    }

    private class Candidate(
        val key: String,
        val origin: RecallOrigin,
        val score: Double,
        val best: PassageEvidence?,
        val supporting: List<PassageEvidence>,
        val matching: Int,
        val inRange: Boolean,
        val rankFusion: Double
    )

    private fun keyOf(p: RecallPassage): String = p.exchangeId ?: p.messageId

    private fun evaluate(
        s: Search, passage: RecallPassage, sim: Double?, standout: Double?, kw: Double,
        matched: List<String>, small: Boolean, range: RecallTimeReference.Range?, rareEnough: Boolean
    ): PassageEvidence {
        val passedMeaning = sim != null && if (small) sim >= max(tuning.similarityFloor, tuning.smallChatFloor)
        else sim >= tuning.similarityFloor && (standout ?: 0.0) >= tuning.standoutMin
        val passedCertain = sim != null && sim >= tuning.certainSimilarity
        val passedKeyword = s.termWeight > 0 && kw >= tuning.keywordMin && rareEnough
        val pieceRule = s.kind != QueryKind.PIECE || (sim != null && sim >= tuning.pieceSimilarityMin && matched.isNotEmpty())
        val range0 = (tuning.fullStrengthSimilarity - tuning.similarityFloor).coerceAtLeast(1e-6)
        val meaning = sim?.let { ((it - tuning.similarityFloor) / range0).coerceIn(0.0, 1.0) } ?: 0.0
        val combined = tuning.meaningWeight * meaning + tuning.keywordWeight * kw
        val inRange = range != null && passage.timeMillis != null && passage.timeMillis in range
        val k = tuning.rankFusionK.toDouble()
        val rrf = (s.meaningRank[passage.rowId]?.let { 1.0 / (k + it) } ?: 0.0) +
            (s.keywordRank[passage.rowId]?.let { 1.0 / (k + it) } ?: 0.0)
        return PassageEvidence(passage, s.kind, s.pieceNumber, sim, standout, kw, matched, passedMeaning,
            passedCertain, passedKeyword, pieceRule, meaning, combined, inRange, rrf)
    }

    private fun isBorderline(e: PassageEvidence, small: Boolean): Boolean {
        val sim = e.similarity
        val nearMeaning = sim != null && if (small) sim >= max(tuning.similarityFloor, tuning.smallChatFloor) - tuning.borderlineSimilarity
        else sim >= tuning.similarityFloor - tuning.borderlineSimilarity && (e.standout ?: 0.0) >= tuning.standoutMin - tuning.borderlineStandout
        val nearKeyword = e.keywordScore > 0 && e.keywordScore >= tuning.keywordMin - tuning.borderlineKeyword
        return nearMeaning || nearKeyword
    }

    private fun boundedByExchange(list: List<PassageEvidence>, strength: (PassageEvidence) -> Double): List<PassageEvidence> =
        list.groupBy { keyOf(it.passage) }.values
            .map { group -> group.sortedByDescending(strength) }
            .sortedByDescending { strength(it.first()) }
            .take(tuning.exchangesPerMethod)
            .flatMap { group -> group.distinctBy { it.passage.rowId to it.query to it.pieceNumber }.take(1 + tuning.supportingPassages * QueryKind.entries.size) }

    /** Pieces holding the chat's rarest words first, each new word counted once (G.10). */
    private fun piecesByRarity(pieces: List<LongMessageSearch.Section>, df: (String) -> Int): List<LongMessageSearch.Section> {
        val weights = pieces.associate { p ->
            p.number to LiveRetrieval.queryTerms(p.text).associateWith { t -> df(t).let { if (it == 0) 0.0 else 1.0 / it } }
        }
        val remaining = pieces.toMutableList()
        val covered = HashSet<String>()
        val order = ArrayList<LongMessageSearch.Section>()
        while (remaining.isNotEmpty()) {
            val best = remaining.maxWith(compareBy<LongMessageSearch.Section> { s ->
                weights.getValue(s.number).entries.sumOf { (t, w) -> if (t in covered) 0.0 else w }
            }.thenByDescending { it.number })
            order.add(best)
            remaining.remove(best)
            covered.addAll(weights.getValue(best.number).keys)
        }
        return order
    }

    /** Exchange keys the AI is already reading in full (stage 7). */
    private fun inFullExchanges(sent: SentInFull, all: List<Stored>, currentMessageId: String?): Set<String> = when (sent) {
        is SentInFull.Messages -> all.filter { it.message.messageId in sent.messageIds }.mapNotNull { it.exchangeKey }.toSet()
        // The AI reads the exchanges it is sent: those with an eligible message,
        // not counting the exchange of the message being sent.
        is SentInFull.LastExchanges -> {
            val current = all.firstOrNull { currentMessageId != null && it.message.messageId == currentMessageId }?.exchangeKey
            all.filter { it.eligible && it.exchangeKey != null && it.exchangeKey != current }
                .mapNotNull { it.exchangeKey }.distinct().takeLast(sent.count).toSet()
        }
    }

    /**
     * Stage 7 from the chat as first read: the current message, what the AI
     * already reads in full, and an exchange holding a message that is
     * hidden from the AI or an unfinished reply.
     */
    private fun quickExclusion(key: String, request: RecallRequest, members: Map<String, List<Stored>>, inFull: Set<String>): ExclusionReason? {
        val list = members[key] ?: return ExclusionReason.NO_LONGER_ELIGIBLE
        if (request.currentMessageId != null && list.any { it.message.messageId == request.currentMessageId }) {
            return ExclusionReason.CURRENT_MESSAGE
        }
        if (key in inFull) return ExclusionReason.SENT_IN_FULL
        for (s in list) {
            val m = s.message
            if (m.hiddenFromAi) return ExclusionReason.NO_LONGER_ELIGIBLE
            if (m.isBot && !MessageCompletionState.isComplete(m.completionState)) return ExclusionReason.NO_LONGER_ELIGIBLE
        }
        if (list.none { it.eligible }) return ExclusionReason.NO_LONGER_ELIGIBLE
        return null
    }

    /**
     * Stage 7 against the chat as stored at this moment: every message of the
     * exchange that was eligible is still there, still eligible, with the same
     * text. A message edited, hidden or deleted during the search fails it.
     */
    /**
     * The chat as stored at the moment results are handed on, for one search:
     * each eligible message's fingerprint (a repeated ID counts by its first
     * eligible copy, as the index does), or null when the chat cannot be read
     * now. Unchanged since the search began, the first read answers; changed,
     * the chat is read once more. Kept per search, so searches running at the
     * same time never share it.
     */
    private inner class Recheck(private val chatId: String, private val first: ChatSnapshot) {
        private val fingerprints: Map<String, String>? by lazy {
            val chat = if (!fromChat { messages.changedSince(chatId, first) }) first else fromChat { messages.read(chatId) }
            chat?.messages?.let { list ->
                val map = LinkedHashMap<String, String>()
                for (m in list) if (IndexEligibility.isEligible(m) && m.messageId !in map) map[m.messageId] = IndexFingerprint.of(m.modelText)
                map
            }
        }

        fun stillStored(key: String, members: Map<String, List<Stored>>): Boolean {
            val now = fingerprints ?: return false
            return members[key].orEmpty().filter { it.eligible }.all { s -> now[s.message.messageId] == s.fingerprint }
        }
    }

    /** Text and speaker order after whitespace normalisation; wording, numbers and names unchanged (stage 8). */
    private fun repeatKey(list: List<Stored>): String =
        list.filter { it.eligible }.joinToString("\u0000") { s ->
            s.message.isBot.toString() + ":" + s.message.modelText.trim().replace(WHITESPACE, " ")
        }

    private fun toExchange(c: Candidate, list: List<Stored>): RecalledExchange = RecalledExchange(
        exchangeId = c.key,
        messages = list.filter { it.eligible }.map {
            RecalledMessage(it.message.messageId, Speaker.of(it.message.isBot), it.message.timeMillis, it.position)
        },
        origin = c.origin,
        score = c.score,
        best = c.best,
        supporting = c.supporting,
        matchingPassages = c.matching,
        inTimeRange = c.inRange,
        rankFusion = c.rankFusion
    )

    private fun followUp(request: RecallRequest, offered: List<RecalledExchange>, budget: RecallBudget): FollowUpDecision = when {
        request.mode != RecallMode.CODE_BASED -> FollowUpDecision.NOT_CODE_BASED
        request.carriedExchangeIds.isEmpty() -> FollowUpDecision.NOTHING_CARRIED
        offered.isNotEmpty() -> FollowUpDecision.OWN_RESULTS_FOUND
        request.memorySubjectChanged == true -> FollowUpDecision.SUBJECT_CHANGED
        WORD.findAll(request.message).count() > tuning.followUpMaxWords -> FollowUpDecision.TOO_LONG
        !budget.mayStartOptional() -> FollowUpDecision.NO_TIME
        else -> FollowUpDecision.CHECKED
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val WORD = Regex("\\S+")
        private const val BORDERLINE_REPORTED = 20
    }
}
