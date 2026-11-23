/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Marks the work a chat screen starts for one reply, so that work can be
 * retired at once when the user deletes messages.
 *
 * A reply's coroutine carries the fence's epoch from when it started. After
 * [retire], code running inside that older coroutine (its cancellation
 * handlers and cleanup) sees [isStale] true and leaves the conversation, the
 * composer and the screen's current-turn state alone: the deletion already
 * settled them, and a new turn may own them by then. Code outside any reply
 * (taps, other callbacks) is never stale.
 */
class ConversationTurnFence {
    @Volatile private var epoch = 0L
    private val running = ThreadLocal<Long?>()

    /** Context element for a reply's scope; captures the current epoch. */
    fun element(): ThreadContextElement<Long?> = running.asContextElement(epoch)

    /** Every reply started before this call becomes stale. Main thread. */
    fun retire() {
        epoch += 1
    }

    /** A callback may run outside the reply's coroutine (for example Ktor's
     * response observer). Capture its owner before dispatch, then check again
     * on the main thread before delivering anything. */
    fun deliveryGuard(): () -> Boolean {
        val started = epoch
        return { started == epoch }
    }

    /** True inside a reply that started before the latest [retire]. */
    fun isStale(): Boolean {
        val started = running.get() ?: return false
        return started != epoch
    }
}
