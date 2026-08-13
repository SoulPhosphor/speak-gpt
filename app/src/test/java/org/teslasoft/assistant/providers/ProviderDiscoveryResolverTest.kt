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

package org.teslasoft.assistant.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderDiscoveryResolverTest {

    @Test
    fun buildsSingleModelLookupUrlWithoutChangingTheModelId() {
        assertEquals(
            "https://openrouter.ai/api/v1/model/deepseek/deepseek-chat-v3.1",
            ProviderDiscoveryResolver.modelLookupUrl(
                "https://openrouter.ai/api/v1/", "deepseek/deepseek-chat-v3.1"
            )
        )
    }

    @Test
    fun resolvesCanonicalRelativeDetailsLink() {
        val body = """{"data":{"links":{"details":"/api/v1/models/deepseek/deepseek-v3.1/endpoints"}}}"""
        assertEquals(
            "https://openrouter.ai/api/v1/models/deepseek/deepseek-v3.1/endpoints",
            ProviderDiscoveryResolver.detailsUrl("https://openrouter.ai/api/v1", body)
        )
    }

    @Test
    fun preservesSameOriginAbsoluteDetailsLink() {
        val body = """{"data":{"links":{"details":"https://openrouter.ai/api/v1/models/canonical/endpoints"}}}"""
        assertEquals(
            "https://openrouter.ai/api/v1/models/canonical/endpoints",
            ProviderDiscoveryResolver.detailsUrl("https://openrouter.ai/api/v1", body)
        )
    }

    @Test
    fun rejectsCrossOriginDetailsLinkBecauseTheCallerSendsAuthorization() {
        val body = """{"data":{"links":{"details":"https://router.example/v1/models/canonical/endpoints"}}}"""
        assertNull(ProviderDiscoveryResolver.detailsUrl("https://openrouter.ai/api/v1", body))
    }

    @Test
    fun missingOrMalformedDetailsLinkReturnsNull() {
        assertNull(ProviderDiscoveryResolver.detailsUrl("https://openrouter.ai/api/v1", "{}"))
        assertNull(ProviderDiscoveryResolver.detailsUrl("https://openrouter.ai/api/v1", "not json"))
    }
}
