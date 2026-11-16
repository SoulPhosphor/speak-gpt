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

package org.teslasoft.assistant.preferences.memory.context

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.memory.context.HelperFailure.Category
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.ProviderLimitKind

/** The Memory Context Helper's failure categories come only from evidence (M.4 item 4). */
class HelperFailureTest {

    private fun of(code: GenErrorCode, status: Int? = null, limit: ProviderLimitKind? = null) =
        HelperFailure.categoryOf(GenErrorResult(code, status, limit))

    @Test
    fun networkFailuresKeepTheirOwnKind() {
        assertEquals(Category.CONNECT_TIMEOUT, of(GenErrorCode.N2))
        assertEquals(Category.UNREACHABLE, of(GenErrorCode.N3))
        assertEquals(Category.RESPONSE_TIMEOUT, of(GenErrorCode.N4))
    }

    @Test
    fun providerRejectionsAreDistinguished() {
        assertEquals(Category.ACCESS_REJECTED, of(GenErrorCode.A1, 401))
        assertEquals(Category.ACCESS_REJECTED, of(GenErrorCode.A2, 403))
        assertEquals(Category.MODEL_UNAVAILABLE, of(GenErrorCode.M2, 404))
        assertEquals(Category.TOO_LARGE, of(GenErrorCode.M3, 400))
        assertEquals(Category.REJECTED, of(GenErrorCode.M4, 400))
        assertEquals(Category.REJECTED, of(GenErrorCode.S3, 400))
        assertEquals(Category.QUOTA, of(GenErrorCode.Q1, 429))
        assertEquals(Category.SERVICE_ERROR, of(GenErrorCode.S6, 502))
    }

    @Test
    fun providerLimitsDecideFirst() {
        assertEquals(Category.RATE_LIMIT, of(GenErrorCode.U0, 429, ProviderLimitKind.RATE_OR_THROUGHPUT))
        assertEquals(Category.QUOTA, of(GenErrorCode.U0, 402, ProviderLimitKind.OUT_OF_CREDITS))
        assertEquals(Category.TOO_LARGE, of(GenErrorCode.U0, 400, ProviderLimitKind.MODEL_CONTEXT))
    }

    @Test
    fun anUnplacedFailureIsNeverGivenAGuessedCause() {
        assertEquals(Category.UNEXPECTED, of(GenErrorCode.U0))
        assertEquals(Category.UNEXPECTED, of(GenErrorCode.N1))
        assertEquals(Category.SERVICE_ERROR, of(GenErrorCode.U0, 500))
    }
}
