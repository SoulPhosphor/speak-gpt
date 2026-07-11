/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure decision logic for the on-device Whisper capture loop's failure
 * handling (no Android dependencies, unit-tested in app/src/test). The
 * capture loop in [LocalWhisperEngine] is impossible to exercise on the JVM
 * (AudioRecord, coroutines against a live mic), so every decision that CAN
 * be pulled out lives here where a test can pin it down:
 *
 *  - what a given AudioRecord.read() return value means ([AudioReadClassifier]),
 *  - when a listening turn has been running too long ([CaptureWatchdog]),
 *  - what to do when startRecording finds the previous session's flag still
 *    set ([StaleCapturePolicy]),
 *  - "exactly once" semantics for cleanup and failure callbacks ([OnceFlag]),
 *  - the bounded automatic-recovery budget ([BoundedRetryBudget]).
 */

/**
 * Why a capture session ended on its own, delivered to the UI through the
 * typed capture-error callback. Engine failure is NEVER encoded as silence,
 * a null transcript, or an empty transcript — a session that dies abnormally
 * always reports one of these instead, so the UI can tell "the mic broke"
 * apart from "the user said nothing".
 */
enum class CaptureErrorReason {
    /** AudioRecord.read() threw, returned an error code, or stopped returning data. */
    READ_FAILED,

    /** The capture loop itself crashed (VAD/diagnostics code threw). */
    CAPTURE_CRASHED,

    /** The VAD declared end-of-turn but nothing collected the audio within the
     *  grace window — the end-of-turn callback was lost (activity torn down
     *  mid-handoff) and the mic would otherwise stay open forever. */
    TURN_NOT_COLLECTED,

    /** The absolute wall-clock ceiling for one listening session elapsed. */
    LISTENING_TIMEOUT
}

/** Classification of a single AudioRecord.read() return value. */
object AudioReadClassifier {

    enum class Outcome { DATA, EMPTY, ERROR }

    fun classify(read: Int): Outcome = when {
        read > 0 -> Outcome.DATA
        read == 0 -> Outcome.EMPTY
        else -> Outcome.ERROR
    }

    /**
     * Human-readable name for a negative read() return. Values mirror the
     * AudioRecord constants (kept as literals so this file stays free of
     * android.* imports and runs on the plain JVM).
     */
    fun describe(read: Int): String = when (read) {
        -1 -> "$read (ERROR)"
        -2 -> "$read (ERROR_BAD_VALUE)"
        -3 -> "$read (ERROR_INVALID_OPERATION)"
        -6 -> "$read (ERROR_DEAD_OBJECT)"
        else -> "$read"
    }
}

/**
 * Wall-clock watchdog for one VAD-driven (hands-free) listening turn.
 *
 * The VAD's own silence / no-speech timers run on *audio* time (samples
 * captured), which is the right clock for "was the user silent" but means a
 * loop whose callbacks are lost, whose detector is pinned "speech" by steady
 * noise, or whose stream stalls can listen forever with the mic held open.
 * This watchdog is the independent wall-clock bound on top:
 *
 *  - Soft cap ([maxTurnMs], default 10 min): the turn is ENDED THE NORMAL WAY
 *    — as end-of-turn if speech was heard (the audio is transcribed and
 *    submitted, nothing the user said is lost) or as the no-speech timeout if
 *    not. It does not interfere with intended hands-free use: turns normally
 *    end via the user-configured silence window (settable up to 120 s), so
 *    the cap is only reachable by speaking continuously for 10 minutes
 *    without a single silence-window pause, or by a detector wedged open by
 *    noise — which is exactly the failure being bounded.
 *  - Post-turn grace ([postTurnGraceMs], default 60 s): once the VAD fired,
 *    the UI must collect the audio (stopAndTranscribe) promptly; if nothing
 *    does, the callback was lost and the session is aborted so the mic is
 *    released.
 *  - Hard limit ([hardLimitMs], default 12 min): absolute ceiling on the
 *    whole session no matter what state it is in.
 *
 * Manual push-to-talk capture is deliberately NOT watched: there the user
 * explicitly owns start/stop and a cap would cut off intended long dictation.
 * Manual sessions are still protected by read-failure detection and by
 * stale-session recovery at the next arm.
 */
class CaptureWatchdog(
    private val maxTurnMs: Long = MAX_TURN_MS,
    private val postTurnGraceMs: Long = POST_TURN_GRACE_MS,
    private val hardLimitMs: Long = HARD_LIMIT_MS
) {
    enum class Action { CONTINUE, FORCE_END_OF_TURN, FORCE_NO_SPEECH, ABORT_TURN_NOT_COLLECTED, ABORT_HARD_LIMIT }

    private var softCapFired = false

    /**
     * @param elapsedMs wall-clock ms since the session started
     * @param speechStarted whether the VAD has heard speech this turn
     * @param vadFired whether end-of-turn / no-speech has already fired
     * @param sinceVadFiredMs wall-clock ms since the VAD fired (0 if it hasn't)
     */
    fun check(elapsedMs: Long, speechStarted: Boolean, vadFired: Boolean, sinceVadFiredMs: Long): Action {
        if (elapsedMs >= hardLimitMs) return Action.ABORT_HARD_LIMIT
        if (vadFired && sinceVadFiredMs >= postTurnGraceMs) return Action.ABORT_TURN_NOT_COLLECTED
        if (!vadFired && !softCapFired && elapsedMs >= maxTurnMs) {
            softCapFired = true
            return if (speechStarted) Action.FORCE_END_OF_TURN else Action.FORCE_NO_SPEECH
        }
        return Action.CONTINUE
    }

    companion object {
        const val MAX_TURN_MS = 10 * 60_000L
        const val POST_TURN_GRACE_MS = 60_000L
        const val HARD_LIMIT_MS = 12 * 60_000L
    }
}

/**
 * What startRecording should do when it finds capture apparently already
 * active. The old behavior — return true and do nothing — reported a
 * successful new listening turn that did not exist (no new callbacks, no new
 * session): the "app says it's listening but nothing is" bug. Now:
 *
 *  - the previous session's loop is provably dead but left `isCapturing`
 *    stuck true → RECOVER_STALE: tear the corpse down and start fresh;
 *  - the previous session is genuinely alive → BUSY: refuse, so the caller's
 *    failure path (bounded retry / visible stop) runs instead of a lie.
 */
object StaleCapturePolicy {

    enum class Decision { PROCEED, RECOVER_STALE, BUSY }

    /**
     * @param isCapturing the engine-level "a session exists" flag
     * @param loopAlive true only when the capture coroutine is still active
     *   AND the session object hasn't been closed
     */
    fun decide(isCapturing: Boolean, loopAlive: Boolean): Decision = when {
        !isCapturing -> Decision.PROCEED
        loopAlive -> Decision.BUSY
        else -> Decision.RECOVER_STALE
    }
}

/**
 * One-shot latch: [tryClaim] returns true for exactly one caller, no matter
 * how many threads race it. Backs both "cleanup runs exactly once per
 * session" and "the failure callback fires at most once per session".
 */
class OnceFlag {
    private val done = AtomicBoolean(false)
    fun tryClaim(): Boolean = done.compareAndSet(false, true)
    fun isClaimed(): Boolean = done.get()
}

/**
 * Bounded budget for automatic voice-loop recovery, so a persistent failure
 * can never become an infinite retry cycle. [tryConsume] grants an attempt
 * while any remain; [reset] restores the budget when real progress is made
 * (a turn actually completed), not merely when a retry managed to start.
 */
class BoundedRetryBudget(private val max: Int) {
    private var used = 0

    /** Consume one attempt. False = budget exhausted; stop retrying. */
    fun tryConsume(): Boolean {
        if (used >= max) return false
        used++
        return true
    }

    fun reset() { used = 0 }

    fun attemptsUsed(): Int = used
}
