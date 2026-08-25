/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 */

package org.teslasoft.assistant.ui.chat

/**
 * Small state machine for transcript movement during a generated reply.
 *
 * A touch during a generation permanently hands positioning to the user for
 * that generation. Releasing the finger does not re-arm automatic scrolling;
 * only the next generation may do that. A touch outside generation preserves
 * the old behavior of suppressing incidental scrolls until a new generation
 * explicitly starts.
 */
class TranscriptAutoScrollState {
    private var generationActive = false
    private var touchActive = false
    private var interruptedGeneration = false
    private var idleScrollSuppressed = false

    val isTouchActive: Boolean
        get() = touchActive

    val shouldPreserveGrowingContentAnchor: Boolean
        get() = touchActive || (generationActive && interruptedGeneration)

    fun onGenerationStarted() {
        generationActive = true
        interruptedGeneration = touchActive
        idleScrollSuppressed = false
    }

    fun onGenerationFinished() {
        generationActive = false
        if (interruptedGeneration || touchActive) idleScrollSuppressed = true
        interruptedGeneration = false
    }

    fun onTouchStarted() {
        touchActive = true
        if (generationActive) {
            interruptedGeneration = true
        } else {
            idleScrollSuppressed = true
        }
    }

    fun onTouchFinished() {
        touchActive = false
    }

    fun allowsAutomaticScroll(): Boolean =
        !touchActive && !interruptedGeneration && !idleScrollSuppressed
}
