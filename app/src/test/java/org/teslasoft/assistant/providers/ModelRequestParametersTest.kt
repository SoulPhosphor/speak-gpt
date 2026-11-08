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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.ModelRequestParameters.Companion.FREQUENCY_PENALTY
import org.teslasoft.assistant.providers.ModelRequestParameters.Companion.PRESENCE_PENALTY
import org.teslasoft.assistant.providers.ModelRequestParameters.Companion.STREAM
import org.teslasoft.assistant.providers.ModelRequestParameters.Companion.TEMPERATURE
import org.teslasoft.assistant.providers.ModelRequestParameters.Companion.TOP_P

class ModelRequestParametersTest {

    private val values = FavoriteModelObject("model-a", "ep-1").apply {
        streaming = true
        temperature = 0.3f
        topP = 1.0f
        frequencyPenalty = 0.0f
        presencePenalty = 0.5f
    }

    @Test
    fun everythingConfiguredIsSentWhenTheServiceHasNotReported() {
        val sent = ModelRequestParameters.from(values, null)
        assertEquals(setOf(STREAM, TEMPERATURE, TOP_P, FREQUENCY_PENALTY, PRESENCE_PENALTY), sent.sentNames())
        assertEquals(0.5, sent.presencePenalty!!, 0.0001)
    }

    @Test
    fun parametersTheServiceSaysTheModelDoesNotAcceptAreLeftOut() {
        val sent = ModelRequestParameters.from(values, setOf("max_tokens", "top_p", "presence_penalty"))
        assertNull(sent.temperature)
        assertNull(sent.frequencyPenalty)
        assertEquals(1.0, sent.topP!!, 0.0001)
        // Streaming is a delivery choice, not a catalog parameter.
        assertTrue(sent.stream)
    }

    @Test
    fun streamingOffSendsNoStreamField() {
        val sent = ModelRequestParameters.from(values.copy().apply { streaming = false }, null)
        assertFalse(STREAM in sent.sentNames())
    }

    @Test
    fun aRefusedParameterIsLeftOutAndTheRestAreKept() {
        val retry = ModelRequestParameters.from(values, null).without(setOf(TEMPERATURE, STREAM))
        assertNull(retry.temperature)
        assertFalse(retry.stream)
        assertEquals(1.0, retry.topP!!, 0.0001)
    }

    @Test
    fun theErrorsParamFieldNamesTheRefusedParameter() {
        val body = """{"error":{"message":"Unsupported value: 'temperature' does not support 0.3 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}"""
        assertEquals(setOf(TEMPERATURE), reject(400, body))
    }

    @Test
    fun aRefusalNamedOnlyInTheMessageIsRecognized() {
        val body = """{"error":{"message":"Unsupported parameter: 'top_p' is not supported with this model."}}"""
        assertEquals(setOf(TOP_P), reject(400, body))
    }

    @Test
    fun aRefusalInPlainTextIsRecognized() {
        assertEquals(setOf(STREAM), reject(422, "stream is not supported for this model"))
    }

    @Test
    fun anErrorThatNamesNoSentParameterIsNotARefusal() {
        val body = """{"error":{"message":"This model's maximum context length is 8192 tokens."}}"""
        assertTrue(reject(400, body).isEmpty())
    }

    @Test
    fun aParameterMentionedWithoutARefusalIsNotARefusal() {
        val body = """{"error":{"message":"Invalid API key. The temperature was fine."}}"""
        assertTrue(reject(400, body).isEmpty())
    }

    @Test
    fun otherStatusesAreNeverParameterRefusals() {
        val body = """{"error":{"param":"temperature","message":"temperature is not supported"}}"""
        assertTrue(reject(401, body).isEmpty())
        assertTrue(reject(429, body).isEmpty())
        assertTrue(reject(500, body).isEmpty())
        assertTrue(reject(null, body).isEmpty())
    }

    @Test
    fun aWordContainingAParameterNameDoesNotCount() {
        assertTrue(reject(400, "upstream provider does not support this request").isEmpty())
    }

    @Test
    fun aParameterThatWasNotSentIsNeverNamed() {
        val sent = setOf(TEMPERATURE)
        val body = """{"error":{"param":"top_p","message":"top_p is not supported"}}"""
        assertTrue(ModelParameterRejection.rejected(400, body, null, sent).isEmpty())
    }

    private fun reject(status: Int?, body: String) = ModelParameterRejection.rejected(
        status, body, null, setOf(STREAM, TEMPERATURE, TOP_P, FREQUENCY_PENALTY, PRESENCE_PENALTY)
    )
}
