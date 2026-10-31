package org.teslasoft.assistant.ui.chat

/** A queued movement belongs to the turn and user permission that scheduled it. */
class ChatAutoScrollGate {
    private var revision = 0L

    var disabled = false
        set(value) {
            field = value
            revision++
        }

    fun post(
        enqueue: (Runnable) -> Unit,
        keyboardHolding: () -> Boolean,
        move: () -> Unit
    ) {
        if (disabled || keyboardHolding()) return
        val scheduledRevision = revision
        enqueue(Runnable {
            if (scheduledRevision == revision && !disabled && !keyboardHolding()) move()
        })
    }
}
