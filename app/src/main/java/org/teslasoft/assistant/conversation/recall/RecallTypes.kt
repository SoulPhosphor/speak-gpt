/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

import java.time.ZoneId
import org.teslasoft.assistant.conversation.index.Speaker

/** The context-assistance choice in effect for the message (G.9b). */
enum class RecallMode { OFF, CODE_BASED, API_AI }

/** What a search was made with (retrieval stage 1). */
enum class QueryKind { WHOLE, PIECE, HELPER }

/** Why an exchange is offered: the message's own searches, the API helper's query, or Code-based continuity. */
enum class RecallOrigin { OWN, HELPER, CARRIED }

/** Reads a search text with the embedding model as a query, within [remainingMillis]; null when it could not be read. */
fun interface QueryVectorReader {
    fun read(text: String, remainingMillis: Long): FloatArray?
}

/** Which messages the AI is already reading in full (retrieval stage 7). */
sealed interface SentInFull {
    /** Exactly these messages (a summarized or compacted chat: the ones kept in full). */
    data class Messages(val messageIds: Set<String>) : SentInFull

    /** The whole chat is sent: the last [count] exchanges count as already read. */
    data class LastExchanges(val count: Int) : SentInFull
}

/**
 * One recall search for the message being sent (G.6, G.8a point 2). The
 * message's own vector and its pieces' vectors are passed in when
 * saved-memory search already read them, so nothing is read twice (G.10);
 * any that are missing are read through [RecallEngine]'s reader.
 */
data class RecallRequest(
    val chatId: String,
    val message: String,
    /** The message being sent, when it is already stored; it is never recalled. */
    val currentMessageId: String?,
    val nowMillis: Long,
    val zone: ZoneId,
    /** The embedding model's tag and vector size; null when no model is usable (keywords only). */
    val vectorModel: String?,
    val vectorDimensions: Int,
    val sentInFull: SentInFull,
    val messageVector: FloatArray? = null,
    /** Vectors of the message's pieces already read, keyed by the piece's text. */
    val pieceVectors: Map<String, FloatArray> = emptyMap(),
    val mode: RecallMode = RecallMode.OFF,
    /** API AI: the helper's checked query, or null when it gave none or failed. */
    val helperQuery: String? = null,
    val helperVector: FloatArray? = null,
    /** Code-based: the exchanges delivered with the chat's previous messages ([RecallContinuity]). */
    val carriedExchangeIds: List<String> = emptyList(),
    /**
     * Code-based: whether this message set a new saved-memory subject (a
     * confident match of its own). Null when saved-memory search did not run
     * or gave no answer, for example in a chat without saved memories.
     */
    val memorySubjectChanged: Boolean? = null,
    /**
     * Evaluation only (speaker-title variant C): only passages recorded under
     * one ID of every group. Each group is the permanent IDs one name in the
     * question resolves to (two identities can share a name).
     */
    val identityFilter: List<Set<String>>? = null
)

/** One passage's numbers against one search (retrieval stages 4 and 5). */
data class PassageEvidence(
    val passage: RecallPassage,
    val query: QueryKind,
    /** The piece's number counting from 1, or 0 for the whole message and the helper's query. */
    val pieceNumber: Int,
    val similarity: Double?,
    val standout: Double?,
    val keywordScore: Double,
    val matchedTerms: List<String>,
    val passedMeaning: Boolean,
    val passedCertain: Boolean,
    val passedKeyword: Boolean,
    /** Pieces only: the similarity and shared-word requirements of G.10 were met. */
    val passedPieceRule: Boolean,
    val meaningScore: Double,
    /** The blended score before any time bonus. */
    val combined: Double,
    val inTimeRange: Boolean,
    /** Reciprocal rank fusion, computed alongside for comparison only. */
    val rankFusion: Double
) {
    val passed: Boolean get() = (passedMeaning || passedCertain || passedKeyword) && (query != QueryKind.PIECE || passedPieceRule)
    val score: Double get() = combined
}

/** One message of a recalled exchange. */
data class RecalledMessage(val messageId: String, val speaker: Speaker, val timeMillis: Long?, val chatPosition: Int)

/** One exchange handed on to assembly (G.8a point 2). */
data class RecalledExchange(
    val exchangeId: String,
    /** Its eligible messages as stored now, in chat order. */
    val messages: List<RecalledMessage>,
    val origin: RecallOrigin,
    /** Best passage's score, a small addition for further matching passages, and the time bonus. Zero for a carried exchange. */
    val score: Double,
    /** The strongest matching passage; null for a carried exchange. */
    val best: PassageEvidence?,
    val supporting: List<PassageEvidence>,
    val matchingPassages: Int,
    val inTimeRange: Boolean,
    /** Best rank-fusion number of its passages, for comparison only. */
    val rankFusion: Double
) {
    val chatPosition: Int get() = messages.first().chatPosition
}

enum class ExclusionReason { CURRENT_MESSAGE, SENT_IN_FULL, NO_LONGER_ELIGIBLE, REPEAT, IDENTITY_FILTER }

data class Exclusion(val exchangeId: String, val reason: ExclusionReason)

/** Why a carried exchange was or was not offered again. */
enum class FollowUpDecision {
    NOT_CODE_BASED,
    NOTHING_CARRIED,
    OWN_RESULTS_FOUND,
    SUBJECT_CHANGED,
    TOO_LONG,
    NO_TIME,
    /** A short follow-up with no results of its own: each carried exchange was checked ([CarriedCheck]). */
    CHECKED
}

data class CarriedCheck(val exchangeId: String, val termShare: Double?, val carried: Boolean, val reason: String)

/** Every count and reason behind one search, for the inspector (Step 15) and the evaluation. */
data class RecallReport(
    val eligiblePassages: Int,
    val passagesWithVectors: Int,
    /** Eligible passages still waiting for their vector: not found by meaning (G30). */
    val waitingPassages: Int,
    /** Indexed passages whose message changed, was hidden or deleted since indexing. */
    val outdatedPassages: Int,
    val queriesRun: List<Pair<QueryKind, Int>>,
    val queriesSkipped: List<Pair<QueryKind, String>>,
    val timeRange: RecallTimeReference.Range?,
    val borderline: List<PassageEvidence>,
    val excluded: List<Exclusion>,
    val followUp: FollowUpDecision,
    val carriedChecks: List<CarriedCheck>,
    val millis: Long
)

sealed interface RecallResult {
    val report: RecallReport?

    /** Exchanges to offer, best first; empty when nothing qualifies (a correct result). */
    data class Found(val exchanges: List<RecalledExchange>, override val report: RecallReport) : RecallResult

    /** Recall hands on nothing; the message is sent as it would be without recall. */
    data class Skipped(val reason: SkipReason, override val report: RecallReport?) : RecallResult
}

enum class SkipReason {
    OUT_OF_TIME,
    /** The chat could not be read now (a save in progress, locked or damaged storage). */
    CHAT_UNAVAILABLE,
    /** The index could not be read now (being recovered or deleted, damaged, or locked). */
    INDEX_UNAVAILABLE
}
