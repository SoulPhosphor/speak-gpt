package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAutoScrollGateTest {
    @Test
    fun aTouchCancelsMovementAlreadyInTheQueue() {
        val gate = ChatAutoScrollGate()
        val queue = ArrayList<Runnable>()
        var moves = 0
        gate.post({ queue.add(it) }, { false }) { moves++ }

        gate.disabled = true
        queue.single().run()

        assertEquals(0, moves)
    }

    @Test
    fun openingTheKeyboardCancelsQueuedMovement() {
        val gate = ChatAutoScrollGate()
        val queue = ArrayList<Runnable>()
        var keyboard = false
        var moves = 0
        gate.post({ queue.add(it) }, { keyboard }) { moves++ }

        keyboard = true
        queue.single().run()

        assertEquals(0, moves)
    }

    @Test
    fun startingAnotherTurnDoesNotReviveAnOldQueuedMovement() {
        val gate = ChatAutoScrollGate()
        val queue = ArrayList<Runnable>()
        var moves = 0
        gate.post({ queue.add(it) }, { false }) { moves++ }

        gate.disabled = true
        gate.disabled = false
        gate.post({ queue.add(it) }, { false }) { moves += 10 }
        queue.forEach { it.run() }

        assertEquals(10, moves)
    }

    @Test
    fun aTouchBetweenTheTwoLayoutPassesCancelsTheSecondMove() {
        val gate = ChatAutoScrollGate()
        val queue = ArrayList<Runnable>()
        var moves = 0
        gate.post({ queue.add(it) }, { false }) {
            gate.post({ queue.add(it) }, { false }) { moves++ }
        }
        queue[0].run()

        gate.disabled = true
        queue[1].run()

        assertEquals(0, moves)
    }
}
