package org.teslasoft.assistant.preferences.username

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.username.UsernameFillState.Notice

class UsernameFillNoticeRulesTest {

    private fun run(
        outcome: DefaultUsernameFill.RunOutcome,
        queued: Set<Notice> = emptySet(),
        shown: Set<Notice> = emptySet(),
        queuedDamaged: Set<String> = emptySet(),
        shownDamaged: Set<String> = emptySet()
    ) = UsernameFillState.NoticeRules.afterRun(queued, shown, queuedDamaged, shownDamaged, outcome)

    @Test
    fun eachProblemQueuesItsNoticeAndRelatedFailuresCombine() {
        val update = run(DefaultUsernameFill.RunOutcome(locked = true, insufficientStorage = true, damagedChatIds = setOf("a")))
        assertEquals(setOf(Notice.LOCKED, Notice.STORAGE, Notice.DAMAGED), update.queued)
        assertEquals(setOf("a"), update.queuedDamaged)
    }

    @Test
    fun aShownNoticeIsNotRepeatedWhileTheSameProblemIsUnresolved() {
        val update = run(DefaultUsernameFill.RunOutcome(locked = true), shown = setOf(Notice.LOCKED))
        assertFalse(Notice.LOCKED in update.queued)
        assertTrue(Notice.LOCKED in update.shown)
    }

    @Test
    fun aResolvedProblemIsForgottenSoARecurrenceIsAnnouncedAgain() {
        val resolved = run(DefaultUsernameFill.RunOutcome(), shown = setOf(Notice.LOCKED))
        assertFalse(Notice.LOCKED in resolved.shown)
        val again = run(DefaultUsernameFill.RunOutcome(locked = true), shown = resolved.shown)
        assertTrue(Notice.LOCKED in again.queued)
    }

    @Test
    fun aRunStoppedByARestoreDoesNotClearOtherUnresolvedProblems() {
        val update = run(DefaultUsernameFill.RunOutcome(restoreInProgress = true), shown = setOf(Notice.LOCKED))
        assertTrue(Notice.RESTORE in update.queued)
        assertTrue(Notice.LOCKED in update.shown)
    }

    @Test
    fun onlyNewlyDamagedChatsProduceANewNotice() {
        val same = run(DefaultUsernameFill.RunOutcome(damagedChatIds = setOf("a")), shownDamaged = setOf("a"))
        assertFalse(Notice.DAMAGED in same.queued)
        val fresh = run(DefaultUsernameFill.RunOutcome(damagedChatIds = setOf("a", "b")), shownDamaged = setOf("a"))
        assertTrue(Notice.DAMAGED in fresh.queued)
        assertEquals(setOf("b"), fresh.queuedDamaged)
    }

    @Test
    fun anUnclassifiedSaveFailureQueuesItsOwnNotice() {
        val update = run(DefaultUsernameFill.RunOutcome(otherFailure = true))
        assertEquals(setOf(Notice.OTHER), update.queued)
    }

    @Test
    fun retryIsNeededForEverythingButDamage() {
        assertTrue(DefaultUsernameFill.RunOutcome(restoreInProgress = true).needsRetry)
        assertTrue(DefaultUsernameFill.RunOutcome(locked = true).needsRetry)
        assertTrue(DefaultUsernameFill.RunOutcome(insufficientStorage = true).needsRetry)
        assertTrue(DefaultUsernameFill.RunOutcome(otherFailure = true).needsRetry)
        assertFalse(DefaultUsernameFill.RunOutcome(damagedChatIds = setOf("a")).needsRetry)
    }
}
