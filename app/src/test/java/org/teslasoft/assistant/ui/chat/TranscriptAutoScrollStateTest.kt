/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAutoScrollStateTest {

    @Test
    fun untouchedGenerationMayFollowGrowingReply() {
        val state = TranscriptAutoScrollState()

        state.onGenerationStarted()

        assertTrue(state.allowsAutomaticScroll())
        assertFalse(state.shouldPreserveGrowingContentAnchor)
    }

    @Test
    fun touchDuringGenerationStaysLatchedAfterFingerLifts() {
        val state = TranscriptAutoScrollState()
        state.onGenerationStarted()

        state.onTouchStarted()
        assertFalse(state.allowsAutomaticScroll())
        assertTrue(state.shouldPreserveGrowingContentAnchor)

        state.onTouchFinished()
        assertFalse(state.allowsAutomaticScroll())
        assertTrue(state.shouldPreserveGrowingContentAnchor)
    }

    @Test
    fun nextGenerationRearmsOnlyWhenFingerIsNotDown() {
        val state = TranscriptAutoScrollState()
        state.onGenerationStarted()
        state.onTouchStarted()
        state.onTouchFinished()
        state.onGenerationFinished()

        state.onGenerationStarted()
        assertTrue(state.allowsAutomaticScroll())

        state.onGenerationFinished()
        state.onTouchStarted()
        state.onGenerationStarted()
        assertFalse(state.allowsAutomaticScroll())
        assertTrue(state.shouldPreserveGrowingContentAnchor)
    }

    @Test
    fun idleTouchSuppressesDelayedAutomaticMovement() {
        val state = TranscriptAutoScrollState()

        state.onTouchStarted()
        state.onTouchFinished()

        assertFalse(state.allowsAutomaticScroll())
        state.onGenerationStarted()
        assertTrue(state.allowsAutomaticScroll())
    }
}
