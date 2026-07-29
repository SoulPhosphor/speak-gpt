/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.util.summarizer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

/**
 * Mapping from the shared classifier's result onto the summarizer's
 * categories (conversation-summary-errors.md §2). Distinctions the plan
 * cares about: connect vs response timeout, rate limit vs quota, and every
 * kind of too-large limit landing on Summary Request Too Large.
 */
class SummarizerErrorClassifierTest {

    private fun category(
        code: GenErrorCode,
        status: Int? = null,
        limit: ProviderLimitKind? = null
    ) = SummarizerErrorClassifier.categorize(GenErrorResult(code, status, limit))

    @Test
    fun transportFailuresMapToTheirOwnCategories() {
        assertEquals(SummarizerErrorCategory.SERVICE_UNREACHABLE, category(GenErrorCode.N1))
        assertEquals(SummarizerErrorCategory.SERVICE_UNREACHABLE, category(GenErrorCode.N3))
        assertEquals(SummarizerErrorCategory.CONNECT_TIMEOUT, category(GenErrorCode.N2))
        assertEquals(SummarizerErrorCategory.RESPONSE_TIMEOUT, category(GenErrorCode.N4))
    }

    @Test
    fun accessRejectionCoversAuthAndForbidden() {
        assertEquals(SummarizerErrorCategory.ACCESS_REJECTED, category(GenErrorCode.A1, 401))
        assertEquals(SummarizerErrorCategory.ACCESS_REJECTED, category(GenErrorCode.U0, 403))
    }

    @Test
    fun modelProblemsReportedByTheEndpointAreModelUnavailable() {
        assertEquals(SummarizerErrorCategory.MODEL_UNAVAILABLE, category(GenErrorCode.M1))
        assertEquals(SummarizerErrorCategory.MODEL_UNAVAILABLE, category(GenErrorCode.M2, 404))
    }

    @Test
    fun rateLimitAndQuotaAreDistinguishedByTheProviderLimitKind() {
        assertEquals(
            SummarizerErrorCategory.RATE_LIMIT,
            category(GenErrorCode.Q1, 429, ProviderLimitKind.RATE_OR_THROUGHPUT)
        )
        assertEquals(
            SummarizerErrorCategory.QUOTA,
            category(GenErrorCode.Q1, 429, ProviderLimitKind.QUOTA_OR_SPENDING)
        )
        // A bare 429 with no finer evidence reads as the temporary limit.
        assertEquals(SummarizerErrorCategory.RATE_LIMIT, category(GenErrorCode.Q1, 429))
    }

    @Test
    fun everyTooLargeSignalLandsOnSummaryRequestTooLarge() {
        assertEquals(
            SummarizerErrorCategory.REQUEST_TOO_LARGE,
            category(GenErrorCode.M3, null, ProviderLimitKind.MODEL_CONTEXT)
        )
        assertEquals(
            SummarizerErrorCategory.REQUEST_TOO_LARGE,
            category(GenErrorCode.M3, null, ProviderLimitKind.MODEL_INPUT)
        )
        assertEquals(
            SummarizerErrorCategory.REQUEST_TOO_LARGE,
            category(GenErrorCode.S2, 413, ProviderLimitKind.REQUEST_BODY)
        )
        assertEquals(SummarizerErrorCategory.REQUEST_TOO_LARGE, category(GenErrorCode.M3))
    }

    @Test
    fun contentRejectionAndServerErrorsKeepTheirOwnWording() {
        assertEquals(SummarizerErrorCategory.CONTENT_REJECTED, category(GenErrorCode.S3, 400))
        assertEquals(SummarizerErrorCategory.SERVICE_ERROR, category(GenErrorCode.S1, 404))
        assertEquals(SummarizerErrorCategory.SERVICE_ERROR, category(GenErrorCode.U0, 500))
    }

    @Test
    fun aResponseThatCannotBeReadIsUnreadableNotUnknown() {
        assertEquals(SummarizerErrorCategory.RESPONSE_UNREADABLE, category(GenErrorCode.S2))
    }

    @Test
    fun trulyUnknownFailuresFallToTheUnexpectedCatchAll() {
        assertEquals(SummarizerErrorCategory.UNEXPECTED, category(GenErrorCode.U0))
    }
}
