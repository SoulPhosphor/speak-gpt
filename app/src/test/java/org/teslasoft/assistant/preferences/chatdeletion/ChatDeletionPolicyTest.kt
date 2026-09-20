package org.teslasoft.assistant.preferences.chatdeletion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDeletionPolicyTest {
    private val one = setOf("stable-chat-id")
    private val many = setOf("chat-a", "chat-b")

    @Test fun completeTargetSettingOwnershipAndLockMatrixHasOnlyTheApprovedDecisions() {
        data class Case(
            val target: Set<String>,
            val setting: Boolean,
            val owned: Int,
            val locked: Int,
            val variant: ChatDeletionDialogVariant,
            val deleteAllAllowed: Boolean
        )
        val cases = listOf(
            Case(one, false, 0, 0, ChatDeletionDialogVariant.ORDINARY, false),
            Case(one, true, 0, 0, ChatDeletionDialogVariant.ORDINARY, false),
            Case(one, false, 2, 0, ChatDeletionDialogVariant.KEEP_IMAGES_NOTICE, false),
            Case(many, false, 2, 2, ChatDeletionDialogVariant.KEEP_IMAGES_NOTICE, false),
            Case(many, true, 2, 0, ChatDeletionDialogVariant.DELETE_ALL, true),
            Case(many, true, 2, 1, ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES, true),
            Case(many, true, 2, 2, ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES, true),
            Case(emptySet(), true, 0, 0, ChatDeletionDialogVariant.ORDINARY, false)
        )

        cases.forEach { case ->
            val result = ChatDeletionPolicy.decide(
                case.target,
                case.setting,
                case.owned,
                case.locked
            )
            assertEquals(case.variant, result.variant)
            assertEquals(case.target, result.targetChatIds)
            assertTrue(ChatDeletionDecision.CANCEL in result.allowedDecisions)
            assertTrue(ChatDeletionDecision.DELETE_CHAT_ONLY in result.allowedDecisions)
            assertEquals(
                case.deleteAllAllowed,
                ChatDeletionDecision.DELETE_ALL in result.allowedDecisions
            )
        }
    }

    @Test fun settingOffWithNoOwnedImagesUsesOrdinaryConfirmation() {
        val result = ChatDeletionPolicy.decide(one, false, 0, 0)
        assertEquals(ChatDeletionDialogVariant.ORDINARY, result.variant)
        assertFalse(ChatDeletionDecision.DELETE_ALL in result.allowedDecisions)
    }

    @Test fun settingOffWithOwnedImagesExplainsThatTheyAreKept() {
        val result = ChatDeletionPolicy.decide(one, false, 3, 1)
        assertEquals(ChatDeletionDialogVariant.KEEP_IMAGES_NOTICE, result.variant)
        assertEquals(setOf(ChatDeletionDecision.CANCEL, ChatDeletionDecision.DELETE_CHAT_ONLY), result.allowedDecisions)
    }

    @Test fun settingOnWithOwnedImagesOffersAllThreeActions() {
        val result = ChatDeletionPolicy.decide(many, true, 3, 0)
        assertEquals(ChatDeletionDialogVariant.DELETE_ALL, result.variant)
        assertTrue(ChatDeletionDecision.DELETE_ALL in result.allowedDecisions)
        assertEquals(many, result.targetChatIds)
    }

    @Test fun anyLockedImageSelectsTheProtectedWarningIncludingAllLocked() {
        assertEquals(
            ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES,
            ChatDeletionPolicy.decide(one, true, 3, 1).variant
        )
        val allLocked = ChatDeletionPolicy.decide(many, true, 2, 2)
        assertEquals(ChatDeletionDialogVariant.DELETE_ALL_WITH_LOCKED_IMAGES, allLocked.variant)
        assertTrue(ChatDeletionDecision.DELETE_ALL in allLocked.allowedDecisions)
    }

    @Test fun referenceOnlyImagesAreRepresentedByZeroOwnedImages() {
        val result = ChatDeletionPolicy.decide(many, true, ownedImageCount = 0, lockedImageCount = 0)
        assertEquals(ChatDeletionDialogVariant.ORDINARY, result.variant)
    }

    @Test fun emptyFolderTargetCanUseTheOrdinaryPolicyWithoutInventingAChatId() {
        val result = ChatDeletionPolicy.decide(emptySet(), true, 0, 0)
        assertTrue(result.targetChatIds.isEmpty())
        assertEquals(ChatDeletionDialogVariant.ORDINARY, result.variant)
    }
}
