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

package org.teslasoft.assistant.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * §9 of image-generation-rebuild-plan.md: the provider adapter is chosen
 * from saved endpoint configuration, never from the image model's name —
 * and network failures split into the §13 causes that need different
 * user explanations.
 */
class ImageProviderSelectionTest {

    private fun endpoint(host: String, model: String = "any-model") =
        ApiEndpointObject("Label", host, "key", model = model)

    @Test
    fun openRouterHostsGetTheOpenRouterAdapter() {
        assertSame(
            OpenRouterImageAdapter,
            ImageProviderAdapters.forEndpoint(endpoint("https://openrouter.ai/api/v1/"))
        )
        assertSame(
            OpenRouterImageAdapter,
            ImageProviderAdapters.forEndpoint(endpoint("https://OPENROUTER.AI/api/v1"))
        )
    }

    @Test
    fun everyOtherHostGetsTheOpenAiCompatibleAdapter() {
        assertSame(
            OpenAiImageAdapter,
            ImageProviderAdapters.forEndpoint(endpoint("https://api.openai.com/v1/"))
        )
        assertSame(
            OpenAiImageAdapter,
            ImageProviderAdapters.forEndpoint(endpoint("https://my-local-box:8080/v1/"))
        )
    }

    @Test
    fun theModelNameNeverInfluencesAdapterSelection() {
        // The same host with wildly different model names selects the same
        // adapter — behavior comes from endpoint configuration (§4.3).
        val a = ImageProviderAdapters.forEndpoint(
            endpoint("https://example.com/v1/", model = "gpt-image-1")
        )
        val b = ImageProviderAdapters.forEndpoint(
            endpoint("https://example.com/v1/", model = "dall-e-3")
        )
        val c = ImageProviderAdapters.forEndpoint(
            endpoint("https://example.com/v1/", model = "totally/unknown-model")
        )
        assertSame(a, b)
        assertSame(b, c)
    }

    @Test
    fun networkFailuresSplitIntoTimeoutAndUnreachable() {
        assertEquals(
            ImageErrorCause.TIMED_OUT,
            ImageGeneratorCoordinator.classifyNetworkException(SocketTimeoutException("timeout"))
        )
        assertEquals(
            ImageErrorCause.ENDPOINT_UNREACHABLE,
            ImageGeneratorCoordinator.classifyNetworkException(UnknownHostException("no such host"))
        )
        assertEquals(
            ImageErrorCause.ENDPOINT_UNREACHABLE,
            ImageGeneratorCoordinator.classifyNetworkException(ConnectException("refused"))
        )
        assertEquals(
            ImageErrorCause.TIMED_OUT,
            ImageGeneratorCoordinator.classifyNetworkException(IOException("call timeout exceeded"))
        )
        assertEquals(
            ImageErrorCause.ENDPOINT_UNREACHABLE,
            ImageGeneratorCoordinator.classifyNetworkException(IOException("connection reset"))
        )
    }
}
