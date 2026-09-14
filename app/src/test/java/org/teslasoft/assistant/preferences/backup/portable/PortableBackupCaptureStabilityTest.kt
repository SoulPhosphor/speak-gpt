package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test

class PortableBackupCaptureStabilityTest {
    @Test
    fun mutationAtEveryCategoryBoundaryRetriesThenRefuses() {
        val boundaries = PortableBackupCaptureStability.CAPTURE_STEPS.zipWithNext()
        boundaries.forEachIndexed { index, boundary ->
            val before = mapOf("generation" to "before")
            val after = mapOf("generation" to "after-$index-${boundary.first}-${boundary.second}")
            assertEquals(
                PortableBackupCaptureStability.Decision.RETRY,
                PortableBackupCaptureStability.decide(before, after, 1)
            )
            assertEquals(
                PortableBackupCaptureStability.Decision.REFUSE,
                PortableBackupCaptureStability.decide(before, after, 2)
            )
        }
    }

    @Test
    fun unchangedTokensPublishOnEitherAttempt() {
        val tokens = mapOf("chats" to "one", "images" to "two")
        assertEquals(
            PortableBackupCaptureStability.Decision.STABLE,
            PortableBackupCaptureStability.decide(tokens, tokens, 1)
        )
        assertEquals(
            PortableBackupCaptureStability.Decision.STABLE,
            PortableBackupCaptureStability.decide(tokens, tokens, 2)
        )
    }

    @Test
    fun firstMutationRetriesAndSecondMutationRefuses() {
        val before = mapOf("store" to "old")
        val after = mapOf("store" to "new")
        assertEquals(
            PortableBackupCaptureStability.Decision.RETRY,
            PortableBackupCaptureStability.decide(before, after, 1)
        )
        assertEquals(
            PortableBackupCaptureStability.Decision.REFUSE,
            PortableBackupCaptureStability.decide(before, after, 2)
        )
    }

    @Test
    fun unreadableGenerationNeverPublishes() {
        assertEquals(
            PortableBackupCaptureStability.Decision.UNAVAILABLE,
            PortableBackupCaptureStability.decide(null, emptyMap(), 1)
        )
        assertEquals(
            PortableBackupCaptureStability.Decision.UNAVAILABLE,
            PortableBackupCaptureStability.decide(emptyMap(), null, 1)
        )
    }
}
