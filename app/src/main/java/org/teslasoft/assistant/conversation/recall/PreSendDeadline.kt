/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall

/** A monotonic clock, replaceable in tests. */
fun interface RecallClock {
    fun nanos(): Long

    companion object {
        val SYSTEM = RecallClock { System.nanoTime() }
    }
}

/**
 * The one total time allowance shared by all optional work before a message
 * is sent: context assistance, embedding queue waits and reads, saved-memory
 * search and conversation recall (memory development plan G36, approved
 * 2026-11-25 as starting values). Each part's own limit stays an upper
 * bound inside it. Recall gives way first; the message's own saved-memory
 * search and the normal send never give way.
 *
 * Step 14 builds it for recall only. Neither send path uses it yet: Step 16
 * connects it, after Step 15 measures the real delays on the phone.
 */
class PreSendDeadline(
    val allowanceMillis: Long,
    private val clock: RecallClock = RecallClock.SYSTEM,
    private val startedNanos: Long = clock.nanos()
) {
    fun elapsedMillis(): Long = (clock.nanos() - startedNanos) / 1_000_000

    fun remainingMillis(): Long = allowanceMillis - elapsedMillis()

    companion object {
        const val TYPED_MILLIS = 12_000L
        const val HANDS_FREE_MILLIS = 5_000L

        fun start(handsFree: Boolean, clock: RecallClock = RecallClock.SYSTEM) =
            PreSendDeadline(if (handsFree) HANDS_FREE_MILLIS else TYPED_MILLIS, clock)
    }
}

/**
 * Recall's own allowance inside the shared deadline: whichever ends first.
 * When it is used up recall hands on nothing, and the message is sent as it
 * would be without recall (G.6, "A time limit").
 */
class RecallBudget(
    private val allowanceMillis: Long,
    private val shared: PreSendDeadline? = null,
    private val minOptionalMillis: Long = RecallTuning().minOptionalMillis,
    private val clock: RecallClock = RecallClock.SYSTEM
) {
    private val startedNanos = clock.nanos()

    fun elapsedMillis(): Long = (clock.nanos() - startedNanos) / 1_000_000

    fun remainingMillis(): Long {
        val own = allowanceMillis - elapsedMillis()
        return shared?.let { minOf(own, it.remainingMillis()) } ?: own
    }

    fun expired(): Boolean = remainingMillis() < 0

    /** Whether optional work (pieces, the helper's query, carried exchanges) may start now. */
    fun mayStartOptional(): Boolean = remainingMillis() >= minOptionalMillis

    companion object {
        fun forSend(handsFree: Boolean, shared: PreSendDeadline?, tuning: RecallTuning = RecallTuning(), clock: RecallClock = RecallClock.SYSTEM) =
            RecallBudget(
                if (handsFree) tuning.handsFreeAllowanceMillis else tuning.typedAllowanceMillis,
                shared, tuning.minOptionalMillis, clock
            )

        /** No time limit: quality evaluation reads everything. */
        fun unlimited(clock: RecallClock = RecallClock.SYSTEM) = RecallBudget(Long.MAX_VALUE / 4, null, 0, clock)
    }
}
