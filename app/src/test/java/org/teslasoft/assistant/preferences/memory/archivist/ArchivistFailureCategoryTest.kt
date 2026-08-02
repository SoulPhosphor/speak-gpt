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

package org.teslasoft.assistant.preferences.memory.archivist

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

class ArchivistFailureCategoryTest {

    private fun gen(code: GenErrorCode, status: Int? = null, limit: ProviderLimitKind? = null) =
        GenErrorResult(code, status, limit)

    @Test fun parseFailureTagIsInvalidResult() {
        // The chat classifier would call an unparseable body U0; the Archivist's
        // own UNREADABLE tag must win so it reads as "Invalid Analysis Result".
        assertEquals(
            ArchivistFailureCategory.INVALID_RESULT,
            ArchivistFailureCategory.of(ArchivistFailure.UNREADABLE, gen(GenErrorCode.U0))
        )
    }

    @Test fun saveFailureTagIsSaveFailed() {
        assertEquals(
            ArchivistFailureCategory.SAVE_FAILED,
            ArchivistFailureCategory.of(ArchivistFailure.SAVE_FAILED, gen(GenErrorCode.S2))
        )
    }

    @Test fun nullGenIsUnknown() {
        assertEquals(ArchivistFailureCategory.UNKNOWN, ArchivistFailureCategory.of(null, null))
    }

    @Test fun connectionCauses() {
        assertEquals(ArchivistFailureCategory.CONNECTION, ArchivistFailureCategory.of(null, gen(GenErrorCode.N1)))
        assertEquals(ArchivistFailureCategory.CONNECTION, ArchivistFailureCategory.of(null, gen(GenErrorCode.N3)))
    }

    @Test fun timeoutCauses() {
        assertEquals(ArchivistFailureCategory.TIMEOUT, ArchivistFailureCategory.of(null, gen(GenErrorCode.N2)))
        assertEquals(ArchivistFailureCategory.TIMEOUT, ArchivistFailureCategory.of(null, gen(GenErrorCode.N4)))
    }

    @Test fun rejectedCauses() {
        assertEquals(ArchivistFailureCategory.REJECTED, ArchivistFailureCategory.of(null, gen(GenErrorCode.A1, 401)))
        assertEquals(ArchivistFailureCategory.REJECTED, ArchivistFailureCategory.of(null, gen(GenErrorCode.S3)))
        // 403 access-denied folds into Request Rejected.
        assertEquals(ArchivistFailureCategory.REJECTED, ArchivistFailureCategory.of(null, gen(GenErrorCode.U0, 403)))
    }

    @Test fun rateLimitUsageLimitAndCreditsAreSeparate() {
        // A temporary throttle, a usage/spending cap, and an empty balance are
        // three distinct states.
        assertEquals(
            ArchivistFailureCategory.RATE_LIMIT,
            ArchivistFailureCategory.of(null, gen(GenErrorCode.Q1, 429, ProviderLimitKind.RATE_OR_THROUGHPUT))
        )
        assertEquals(
            ArchivistFailureCategory.USAGE_LIMIT,
            ArchivistFailureCategory.of(null, gen(GenErrorCode.Q1, 429, ProviderLimitKind.QUOTA_OR_SPENDING))
        )
        assertEquals(
            ArchivistFailureCategory.CREDITS,
            ArchivistFailureCategory.of(null, gen(GenErrorCode.Q1, 402, ProviderLimitKind.OUT_OF_CREDITS))
        )
    }

    @Test fun requestTooLargeFoldsIntoConfig() {
        assertEquals(
            ArchivistFailureCategory.CONFIG,
            ArchivistFailureCategory.of(null, gen(GenErrorCode.M3, 400, ProviderLimitKind.MODEL_CONTEXT))
        )
        assertEquals(
            ArchivistFailureCategory.CONFIG,
            ArchivistFailureCategory.of(null, gen(GenErrorCode.U0, 413, ProviderLimitKind.REQUEST_BODY))
        )
        assertEquals(ArchivistFailureCategory.CONFIG, ArchivistFailureCategory.of(null, gen(GenErrorCode.M1)))
    }

    @Test fun modelUnavailableOnlyWhenModelNamed() {
        // A body that names the model missing (M2) is Model Unavailable; a bare
        // 404 (S1) is most often a wrong endpoint URL → Invalid Configuration.
        assertEquals(ArchivistFailureCategory.MODEL_UNAVAILABLE, ArchivistFailureCategory.of(null, gen(GenErrorCode.M2, 404)))
        assertEquals(ArchivistFailureCategory.CONFIG, ArchivistFailureCategory.of(null, gen(GenErrorCode.S1, 404)))
    }

    @Test fun transportUnreadableAndUnknown() {
        assertEquals(ArchivistFailureCategory.UNREADABLE, ArchivistFailureCategory.of(null, gen(GenErrorCode.S2)))
        assertEquals(ArchivistFailureCategory.UNKNOWN, ArchivistFailureCategory.of(null, gen(GenErrorCode.U0)))
    }
}
