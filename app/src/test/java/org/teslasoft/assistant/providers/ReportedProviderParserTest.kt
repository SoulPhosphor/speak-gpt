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

class ReportedProviderParserTest {

    @Test fun readsOpenRouterSelectedEndpointFromOfficialRouterMetadata() {
        assertEquals(
            "Open Inference",
            ReportedProviderParser.fromResponseLine(
                "data: {\"openrouter_metadata\":{\"endpoints\":{\"available\":[" +
                    "{\"provider\":\"Backup\",\"selected\":false}," +
                    "{\"provider\":\"Open Inference\",\"selected\":true}]}}}"
            )
        )
    }

    @Test fun readsProviderReturnedByOpenRouterStream() {
        assertEquals(
            "Open Inference",
            ReportedProviderParser.fromResponseLine(
                "data: {\"id\":\"gen-1\",\"provider\":\"Open Inference\",\"choices\":[]}"
            )
        )
    }

    @Test fun readsProviderFromPlainJsonToo() {
        assertEquals(
            "OpenAI",
            ReportedProviderParser.fromResponseLine("{\"provider\":\"OpenAI\",\"choices\":[]}")
        )
    }

    @Test fun neverInventsProviderWhenResponseDoesNotReportOne() {
        assertNull(ReportedProviderParser.fromResponseLine("data: {\"choices\":[]}"))
        assertNull(ReportedProviderParser.fromResponseLine(
            "data: {\"openrouter_metadata\":{\"endpoints\":{\"available\":[" +
                "{\"provider\":\"Candidate\",\"selected\":false}]}}}"
        ))
        assertNull(ReportedProviderParser.fromResponseLine("data: [DONE]"))
        assertNull(ReportedProviderParser.fromResponseLine(": OPENROUTER PROCESSING"))
        assertNull(ReportedProviderParser.fromResponseLine("data: {broken"))
    }
}
