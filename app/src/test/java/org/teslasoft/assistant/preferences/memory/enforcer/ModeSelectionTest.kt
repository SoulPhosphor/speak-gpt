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

package org.teslasoft.assistant.preferences.memory.enforcer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode selection's protective bias is behavioral safety logic (the spec's
 * tie-break and stickiness rules exist because dropping someone mid-hard-
 * moment is the worst failure) — pinned here as pure JVM tests.
 */
class ModeSelectionTest {

    private fun c(id: String, name: String, score: Double, overrides: List<String> = emptyList()) =
        ModeSelection.Candidate(id, name, score, overrides)

    private val threshold = ModeSelection.DEFAULT_THRESHOLD

    @Test
    fun nothingAboveThresholdMeansNoModes() {
        val out = ModeSelection.select(
            listOf(c("a", "Technical Help", 0.2), c("b", "Emotional Support", 0.3)),
            threshold
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun atMostTwoModes() {
        val out = ModeSelection.select(
            listOf(
                c("a", "Technical Help", 0.9),
                c("b", "Creative Writing", 0.8),
                c("c", "Companion Presence", 0.7)
            ),
            threshold
        )
        assertEquals(2, out.size)
    }

    @Test
    fun adjacentGradientResolvesTowardTheProtectiveMode() {
        // Emotional support barely outscores steady presence: close scores on
        // the gradient resolve to the MORE protective mode.
        val out = ModeSelection.select(
            listOf(
                c("emo", "Emotional Support", 0.62),
                c("steady", "Steady Presence", 0.58)
            ),
            threshold
        )
        assertEquals("steady", out.first().modeId)
    }

    @Test
    fun clearGapDoesNotTieBreak() {
        val out = ModeSelection.select(
            listOf(
                c("emo", "Emotional Support", 0.80),
                c("steady", "Steady Presence", 0.50)
            ),
            threshold
        )
        assertEquals("emo", out.first().modeId)
    }

    @Test
    fun suggestedModeActivatesRegardlessOfScore() {
        val out = ModeSelection.select(
            listOf(c("steady", "Steady Presence", 0.05)),
            threshold,
            suggestedIds = setOf("steady")
        )
        assertEquals(listOf("steady"), out.map { it.modeId })
    }

    @Test
    fun stickyModeStaysActiveWithoutRetrigger() {
        val out = ModeSelection.select(
            listOf(c("steady", "Steady Presence", 0.1), c("tech", "Technical Help", 0.2)),
            threshold,
            stickyIds = setOf("steady")
        )
        assertEquals(listOf("steady"), out.map { it.modeId })
    }

    @Test
    fun overridesDropTheOverriddenMode() {
        val out = ModeSelection.select(
            listOf(
                c("steady", "Steady Presence", 0.7, overrides = listOf("presence")),
                c("presence", "Companion Presence", 0.65)
            ),
            threshold
        )
        assertEquals(listOf("steady"), out.map { it.modeId })
    }

    @Test
    fun protectiveModeWinsAScarceSlot() {
        val out = ModeSelection.select(
            listOf(
                c("tech", "Technical Help", 0.9),
                c("write", "Creative Writing", 0.85),
                c("emo", "Emotional Support", 0.6)
            ),
            threshold
        )
        // Two slots: the protective mode takes precedence, the best task mode
        // keeps the other.
        assertTrue(out.any { it.modeId == "emo" })
        assertEquals("emo", out.first().modeId)
        assertEquals(2, out.size)
    }

    /* ---------------- stickiness state machine ---------------- */

    @Test
    fun enteringProtectiveModeBecomesSticky() {
        val s = ModeSelection.updateSticky(
            ModeSelection.StickyState(), setOf("steady"),
            retriggered = true, taskModeCleared = false, exitSignal = false
        )
        assertEquals(setOf("steady"), s.modeIds)
        assertEquals(0, s.misses)
    }

    @Test
    fun oneQuietTurnNeverExits() {
        val s = ModeSelection.updateSticky(
            ModeSelection.StickyState(setOf("steady"), 0), emptySet(),
            retriggered = false, taskModeCleared = false, exitSignal = false
        )
        assertEquals(setOf("steady"), s.modeIds)
        assertEquals(1, s.misses)
    }

    @Test
    fun exitSignalClearsStickiness() {
        val s = ModeSelection.updateSticky(
            ModeSelection.StickyState(setOf("steady"), 1), emptySet(),
            retriggered = false, taskModeCleared = false, exitSignal = true
        )
        assertTrue(s.modeIds.isEmpty())
    }

    @Test
    fun sustainedTaskWorkEventuallyExits() {
        var s = ModeSelection.StickyState(setOf("steady"), 0)
        repeat(ModeSelection.STICKY_MAX_MISSES + 1) {
            s = ModeSelection.updateSticky(
                s, emptySet(), retriggered = false, taskModeCleared = true, exitSignal = false
            )
        }
        assertTrue(s.modeIds.isEmpty())
    }

    @Test
    fun exitPhrasesAreRecognized() {
        assertTrue(ModeSelection.isExitSignal("thanks, I'm okay now, what about the build?"))
        assertTrue(ModeSelection.isExitSignal("let's move on"))
        org.junit.Assert.assertFalse(ModeSelection.isExitSignal("everything is falling apart"))
    }

    /* ---------------- near-duplicate fallback ---------------- */

    @Test
    fun jaccardCatchesRestatedFacts() {
        assertTrue(
            NearDuplicate.isTextNearDup(
                "Cat: The user's cat is named Biscuit and is orange",
                "Pets: the user's orange cat is named Biscuit"
            )
        )
        org.junit.Assert.assertFalse(
            NearDuplicate.isTextNearDup(
                "Cat: The user's cat is named Biscuit",
                "Work: they are building an Android voice assistant"
            )
        )
    }
}
