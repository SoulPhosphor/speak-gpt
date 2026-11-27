package org.teslasoft.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.ResponseLifecycle

/** The deletion and the reply's own cancellation handler share this plan; it
 *  must keep producing what the handler wrote before it was extracted. */
class CancelledReplyTerminalTest {
    private val closed = "the screen closed"

    @Test fun userStopIsANonErrorStop() {
        val plan = CancelledReplyTerminal.plan(true, false, true, true, closed)
        assertEquals(MessageCompletionState.STOPPED, plan.state)
        assertEquals(MessageCompletionState.DETAIL_USER_STOP, plan.detail)
        assertFalse(plan.logAsError)
        assertNull(plan.reason)
        assertEquals(ResponseLifecycle.Outcome.STOPPED, plan.outcome)
        assertEquals(ResponseLifecycle.Termination.USER_STOP, plan.termination)
        assertNull(plan.lifecycleError)
    }

    @Test fun aReplyThatNeverStartedIsAStartFailure() {
        val plan = CancelledReplyTerminal.plan(false, false, false, true, closed)
        assertEquals(MessageCompletionState.DETAIL_START_FAILED, plan.detail)
        assertTrue(plan.logAsError)
        assertEquals("GenStartFailed", plan.errorTag)
        assertEquals("reply could not start", plan.errorSummary)
        assertNull(plan.reason)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, plan.outcome)
        assertEquals(ResponseLifecycle.Termination.STREAM_CLOSED, plan.termination)
        assertEquals("reply could not start", plan.lifecycleError)

        val torn = CancelledReplyTerminal.plan(false, true, false, true, closed)
        assertFalse(torn.logAsError)
        assertEquals(closed, torn.reason)
        assertEquals("reply could not start: $closed", torn.errorSummary)
        assertEquals(ResponseLifecycle.Outcome.CANCELLED, torn.outcome)
        assertEquals(ResponseLifecycle.Termination.APP_CANCEL, torn.termination)
    }

    @Test fun teardownMidReplyIsAnInterruption() {
        val plan = CancelledReplyTerminal.plan(false, true, true, true, closed)
        assertFalse(plan.logAsError)
        assertEquals(MessageCompletionState.INTERRUPTED, plan.state)
        assertEquals(MessageCompletionState.DETAIL_SCREEN_CLOSED, plan.detail)
        assertEquals("GenInterrupted", plan.errorTag)
        assertEquals("app interrupted the reply: $closed", plan.errorSummary)
        assertEquals("app interrupted: $closed", plan.lifecycleError)
        assertEquals(ResponseLifecycle.Termination.APP_CANCEL, plan.termination)
    }

    @Test fun anUnexplainedEndSplitsOnWhetherTheRequestWasSent() {
        val sent = CancelledReplyTerminal.plan(false, false, true, true, closed)
        assertEquals(MessageCompletionState.DETAIL_UNKNOWN_END, sent.detail)
        assertEquals("GenUnknownEnd", sent.errorTag)
        assertEquals("reply ended early; cause unknown", sent.errorSummary)
        assertEquals(ResponseLifecycle.Outcome.INCOMPLETE, sent.outcome)
        assertEquals(ResponseLifecycle.Termination.STREAM_CLOSED, sent.termination)
        assertEquals("ended early; cause unknown", sent.lifecycleError)

        val notSent = CancelledReplyTerminal.plan(false, false, true, false, closed)
        assertEquals(MessageCompletionState.DETAIL_UNKNOWN_END, notSent.detail)
        assertEquals(ResponseLifecycle.Outcome.CANCELLED, notSent.outcome)
        assertEquals(ResponseLifecycle.Termination.REQUEST_NOT_SENT, notSent.termination)
        assertNull(notSent.lifecycleError)
    }
}
