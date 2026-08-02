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

package org.teslasoft.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderErrorInfoTest {

    @Test fun openRouterBodyYieldsProviderNameAndRawMessage() {
        val body = """{"error":{"code":429,"message":"Provider returned error","metadata":{"provider_name":"Nvidia","raw":"rate limited upstream"}}}"""
        val parsed = ProviderErrorInfo.parse(body)
        assertEquals("Nvidia", parsed.providerName)
        // metadata.raw is preferred over the generic top message.
        assertEquals("rate limited upstream", parsed.message)
    }

    @Test fun openRouterBodyWithoutRawFallsBackToMessage() {
        val body = """{"error":{"code":429,"message":"Provider returned error","metadata":{"provider_name":"Together"}}}"""
        val parsed = ProviderErrorInfo.parse(body)
        assertEquals("Together", parsed.providerName)
        assertEquals("Provider returned error", parsed.message)
    }

    @Test fun openAiStyleBodyHasMessageButNoProvider() {
        val body = """{"error":{"message":"Insufficient credits","type":"insufficient_quota"}}"""
        val parsed = ProviderErrorInfo.parse(body)
        assertNull(parsed.providerName)
        assertEquals("Insufficient credits", parsed.message)
    }

    @Test fun plainTextBodyBecomesTheMessage() {
        val parsed = ProviderErrorInfo.parse("Bad Gateway")
        assertNull(parsed.providerName)
        assertEquals("Bad Gateway", parsed.message)
    }

    @Test fun blankOrNullBodyYieldsNothing() {
        assertEquals(ProviderErrorInfo.Parsed(null, null), ProviderErrorInfo.parse(null))
        assertEquals(ProviderErrorInfo.Parsed(null, null), ProviderErrorInfo.parse("   "))
    }
}
