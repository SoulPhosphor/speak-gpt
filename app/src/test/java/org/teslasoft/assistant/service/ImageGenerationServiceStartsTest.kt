package org.teslasoft.assistant.service

import org.junit.Assert.*
import org.junit.Test

class ImageGenerationServiceStartsTest {
    @Test fun instantCompletionCannotStopAnUnpromotedForegroundStart() {
        val starts = ImageGenerationServiceStarts()
        starts.requested()
        starts.released()
        assertFalse(starts.idle)
        starts.delivered()
        assertTrue(starts.idle)
    }

    @Test fun aPromotedServiceStaysAliveUntilItsRequestFinishes() {
        val starts = ImageGenerationServiceStarts()
        starts.requested()
        starts.delivered()
        assertFalse(starts.idle)
        starts.released()
        assertTrue(starts.idle)
    }

    @Test fun fourInstantFailuresMustAcknowledgeEveryQueuedStartBeforeStopping() {
        val starts = ImageGenerationServiceStarts()
        repeat(4) { starts.requested() }
        repeat(4) { starts.released(); assertFalse(starts.idle) }
        repeat(3) { starts.delivered(); assertFalse(starts.idle) }
        starts.delivered()
        assertTrue(starts.idle)
    }

    @Test fun aNewBurstCannotReuseThePreviousBurstsForegroundAcknowledgement() {
        val starts = ImageGenerationServiceStarts()
        starts.requested()
        starts.delivered()
        starts.released()
        assertTrue(starts.idle)
        starts.requested()
        starts.released()
        assertFalse(starts.idle)
        starts.delivered()
        assertTrue(starts.idle)
    }

    @Test fun finishingOneRequestCannotStopItsRunningOrQueuedSiblings() {
        val starts = ImageGenerationServiceStarts()
        starts.requested()
        starts.delivered()
        starts.requested()
        starts.released()
        assertFalse(starts.idle)
        starts.delivered()
        assertFalse(starts.idle)
        starts.released()
        assertTrue(starts.idle)
    }

    @Test fun aRefusedStartReleasesOnlyItsOwnReservation() {
        val starts = ImageGenerationServiceStarts()
        starts.requested()
        starts.requested()
        starts.refused()
        starts.released()
        assertFalse(starts.idle)
        starts.delivered()
        assertTrue(starts.idle)
    }
}
