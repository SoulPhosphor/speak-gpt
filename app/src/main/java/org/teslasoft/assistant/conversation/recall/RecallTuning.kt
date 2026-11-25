/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

/**
 * Every number conversation recall uses (memory development plan G.6 and
 * G36, approved by the owner on 2026-11-25 as starting values). They are
 * engineering values for EmbeddingGemma, measured against the conversation
 * test chats in Steps 14 and 15 and adjusted from those measurements; none
 * is a fixed requirement.
 *
 * Two values differ from the approved starting values after Step 14's first
 * measurement, because on the test chats they removed wrong additions without
 * losing a correct exchange (conversation_recall_baseline.md):
 * [similarityFloor] 0.35 → 0.40, and [keywordMaxShare] off → 0.10. Both were
 * chosen on the same chats they were measured on, so Step 15 measures them
 * again. [APPROVED_STARTING] keeps the approved values for comparison.
 */
data class RecallTuning(
    // Retrieval stage 3: candidates.
    /** Distinct exchanges each method (keywords, meaning) keeps. */
    val exchangesPerMethod: Int = 50,
    /** Supporting passages kept per exchange besides its strongest one. */
    val supportingPassages: Int = 3,
    /** Passage vectors compared per batch. */
    val vectorBatch: Int = 256,
    /** Most informative words of one search looked up in the keyword index, rarest first. */
    val maxQueryTerms: Int = 32,

    // Retrieval stage 4: the relevance gate.
    /** Meaning: the similarity a passage must reach. */
    val similarityFloor: Double = 0.40,
    /** Meaning: how far above the chat's typical similarity it must stand, in robust spreads. */
    val standoutMin: Double = 2.53,
    /** Smallest spread used for stand-out. */
    val minSpread: Double = 0.034,
    /** A certain match: passes without standing out. */
    val certainSimilarity: Double = 0.60,
    /** Below this many passages with vectors, stand-out is not meaningful. */
    val smallChatSize: Int = 8,
    /** The similarity used alone in a small chat. */
    val smallChatFloor: Double = 0.40,
    /** Keywords: rarity-weighted share of the message's informative words a passage must contain. */
    val keywordMin: Double = 0.5,
    /**
     * Keywords: the words a passage shares must also be rare in this chat,
     * together at least as rare as one word found in this share of the chat's
     * passages, so a short message's one common word ("two") is not enough on
     * its own (G.6: one accidental shared word does not pass). 1.0 turns it off.
     */
    val keywordMaxShare: Double = 0.10,
    /** A piece's match also needs this similarity and one shared informative word (G.10). */
    val pieceSimilarityMin: Double = 0.48,
    /** A failed candidate within these margins is recorded as borderline (reported, never offered). */
    val borderlineSimilarity: Double = 0.03,
    val borderlineStandout: Double = 0.5,
    val borderlineKeyword: Double = 0.1,

    // Retrieval stage 5: one score.
    /** The meaning score is 0 at [similarityFloor] and 1 here. */
    val fullStrengthSimilarity: Double = 0.55,
    val meaningWeight: Double = 0.7,
    val keywordWeight: Double = 0.3,
    /** Added to a passage written inside a recognised time range (stage 2); ranking only. */
    val timeRangeBonus: Double = 0.10,
    /** Rank fusion's constant, computed alongside only for comparison. */
    val rankFusionK: Int = 60,

    // Retrieval stage 6: exchanges.
    /** Added for each further matching passage of an exchange. */
    val extraPassageBonus: Double = 0.05,
    /** At most this many further passages add to an exchange's score. */
    val maxExtraPassages: Int = 3,

    // Retrieval stage 7, for callers that send the whole chat.
    /** Exchanges at the end of the chat the AI is already reading when the whole chat is sent. */
    val recentExchangesSentInFull: Int = 3,

    /** Most exchanges retrieval hands on. */
    val maxExchanges: Int = 10,

    // Pieces of a long message (G.10).
    val pieceWords: Int = 80,
    val maxPieces: Int = 8,

    // Time (G36).
    val typedAllowanceMillis: Long = 1_500,
    val handsFreeAllowanceMillis: Long = 750,
    /** Optional work does not start with less than this left. */
    val minOptionalMillis: Long = 300,

    // Code-based follow-ups (G36, Owner 2026-11-25).
    /** Exchanges remembered per chat as delivered. */
    val maxCarriedExchanges: Int = 3,
    /** A message longer than this is never treated as a follow-up. */
    val followUpMaxWords: Int = 25,
    /** Share of the message's own informative words that a carried exchange must contain. */
    val followUpTermShare: Double = 0.5
) {
    companion object {
        /** The values the owner approved as starting values on 2026-11-25, before any measurement. */
        val APPROVED_STARTING = RecallTuning(similarityFloor = 0.35, keywordMaxShare = 1.0)
    }
}
