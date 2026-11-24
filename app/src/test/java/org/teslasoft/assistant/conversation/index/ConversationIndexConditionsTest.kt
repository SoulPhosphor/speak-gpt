package org.teslasoft.assistant.conversation.index

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Step 11 (G20, Owner 2026-11-24): the Index Conversations setting is saved
 * and starts off; each new ongoing condition gets one notice, the same
 * ongoing condition never another; closing a notice keeps the condition; a
 * condition is cleared only when recovery is verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConversationIndexConditionsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before @After fun clean() {
        ConversationIndexPrefs.of(context).edit().clear().commit()
        ConversationIndexSetting.overrideForTest = null
        ConversationIndexSetting.set(context, false)
    }

    @Test fun indexConversationsStartsOffAndIsSaved() {
        ConversationIndexPrefs.of(context).edit().clear().commit()
        assertFalse(ConversationIndexPrefs.of(context).getBoolean("index_conversations", false))
        ConversationIndexSetting.set(context, true)
        assertTrue(ConversationIndexSetting.isOn(context))
        assertTrue(ConversationIndexPrefs.of(context).getBoolean("index_conversations", false))
        ConversationIndexSetting.set(context, false)
        assertFalse(ConversationIndexSetting.isOn(context))
    }

    @Test fun aModelProblemGetsOneNoticeAndClosingItKeepsTheCondition() {
        ConversationIndexSetting.set(context, true)
        assertTrue(ConversationIndexConditions.setModelProblem(context, IndexModelProblem.REMOVED))
        assertEquals(IndexNotice.MODEL_REMOVED, ConversationIndexConditions.pendingNotice(context))
        ConversationIndexConditions.markNoticed(context, IndexNotice.MODEL_REMOVED)
        assertNull(ConversationIndexConditions.pendingNotice(context))
        // The same ongoing condition, reported again on later changes, gets no new notice.
        assertFalse(ConversationIndexConditions.setModelProblem(context, IndexModelProblem.REMOVED))
        assertNull(ConversationIndexConditions.pendingNotice(context))
        assertEquals(IndexModelProblem.REMOVED, ConversationIndexConditions.modelProblem(context))
    }

    @Test fun aDifferentCauseIsANewConditionWithItsOwnNotice() {
        ConversationIndexSetting.set(context, true)
        ConversationIndexConditions.setModelProblem(context, IndexModelProblem.REMOVED)
        ConversationIndexConditions.markNoticed(context, IndexNotice.MODEL_REMOVED)
        assertTrue(ConversationIndexConditions.setModelProblem(context, IndexModelProblem.CANNOT_LOAD))
        assertEquals(IndexNotice.MODEL_CANNOT_LOAD, ConversationIndexConditions.pendingNotice(context))
    }

    @Test fun aConditionThatRecoveredAndReturnsGetsANewNotice() {
        ConversationIndexSetting.set(context, true)
        ConversationIndexConditions.setModelProblem(context, IndexModelProblem.CANNOT_LOAD)
        ConversationIndexConditions.markNoticed(context, IndexNotice.MODEL_CANNOT_LOAD)
        assertTrue(ConversationIndexConditions.clearModelProblem(context))
        assertNull(ConversationIndexConditions.modelProblem(context))
        assertTrue(ConversationIndexConditions.setModelProblem(context, IndexModelProblem.CANNOT_LOAD))
        assertEquals(IndexNotice.MODEL_CANNOT_LOAD, ConversationIndexConditions.pendingNotice(context))
    }

    @Test fun aModelNoticeIsOnlyDueWhileTheSettingIsOn() {
        ConversationIndexConditions.setModelProblem(context, IndexModelProblem.REMOVED)
        assertNull(ConversationIndexConditions.pendingNotice(context))
        ConversationIndexSetting.set(context, true)
        assertEquals(IndexNotice.MODEL_REMOVED, ConversationIndexConditions.pendingNotice(context))
    }

    @Test fun aFailedRecoveryOutlivesItsNoticeUntilARecoveryIsVerified() {
        ConversationIndexConditions.setRecoveryFailed(context, notice = true)
        assertEquals(IndexNotice.STOPPED, ConversationIndexConditions.pendingNotice(context))
        ConversationIndexConditions.markNoticed(context, IndexNotice.STOPPED)
        assertNull(ConversationIndexConditions.pendingNotice(context))
        assertTrue(ConversationIndexConditions.recoveryFailed(context))
        // A new attempt that fails again is reported again.
        ConversationIndexConditions.setRecoveryFailed(context, notice = true)
        assertEquals(IndexNotice.STOPPED, ConversationIndexConditions.pendingNotice(context))
        ConversationIndexConditions.clearRecoveryFailed(context)
        assertFalse(ConversationIndexConditions.recoveryFailed(context))
        assertNull(ConversationIndexConditions.pendingNotice(context))
    }

    @Test fun aRecoveryThatFailedWhileTheSettingWasOffShowsOnlyInTheStatus() {
        ConversationIndexConditions.setRecoveryFailed(context, notice = false)
        assertTrue(ConversationIndexConditions.recoveryFailed(context))
        assertNull(ConversationIndexConditions.pendingNotice(context))
    }

    @Test fun theRecoveryNoticeComesBeforeAModelNotice() {
        assertEquals(
            IndexNotice.STOPPED,
            ConversationIndexConditions.noticeFor(true, true, false, IndexModelProblem.REMOVED, false)
        )
        assertNull(ConversationIndexConditions.noticeFor(true, true, true, IndexModelProblem.REMOVED, false))
        assertEquals(
            IndexNotice.MODEL_REMOVED,
            ConversationIndexConditions.noticeFor(true, false, false, IndexModelProblem.REMOVED, false)
        )
    }

    @Test fun theHookupsReadTheSavedSetting() {
        ConversationIndexSetting.set(context, false)
        val before = ConversationIndexChatChanges.count("setting-chat")
        ConversationIndexHooks.chatSaved(context, "setting-chat")
        assertEquals(before, ConversationIndexChatChanges.count("setting-chat"))
        ConversationIndexSetting.set(context, true)
        try {
            ConversationIndexHooks.chatSaved(context, "setting-chat")
            assertEquals(before + 1, ConversationIndexChatChanges.count("setting-chat"))
        } finally {
            ConversationIndexSetting.set(context, false)
        }
    }
}
