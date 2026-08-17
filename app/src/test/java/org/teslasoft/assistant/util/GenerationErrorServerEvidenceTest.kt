package org.teslasoft.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorServerEvidenceTest {

    @Test fun localParserFailureWithoutHttpEvidenceIsNotProviderFailure() {
        assertFalse(GenErrorResult(GenErrorCode.S2, httpStatus = null).reachedServer())
    }

    @Test fun unknownFailureWithoutHttpEvidenceIsNotProviderFailure() {
        assertFalse(GenErrorResult(GenErrorCode.U0, httpStatus = null).reachedServer())
    }

    @Test fun explicitHttpResponseIsServerEvidenceEvenForParserBucket() {
        assertTrue(GenErrorResult(GenErrorCode.S2, httpStatus = 502).reachedServer())
    }

    @Test fun structuredProviderLimitIsServerEvidenceWithoutScrapedStatus() {
        assertTrue(
            GenErrorResult(
                GenErrorCode.Q1,
                httpStatus = null,
                providerLimit = ProviderLimitKind.QUOTA_OR_SPENDING
            ).reachedServer()
        )
    }
}
