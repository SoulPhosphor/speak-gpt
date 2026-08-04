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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the UI/request-boundary contract that makes Summarizer routing real
 * rather than a settings-only decoration. */
class SummarizerRoutingWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
        return file.readText()
    }

    private val controllerPath =
        "src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt"
    private val settingsPath =
        "src/main/java/org/teslasoft/assistant/ui/activities/SummarizerSettingsActivity.kt"

    @Test
    fun summaryModelIsExplicitAndNeverFallsBackToEndpointChatModel() {
        val controller = source(controllerPath)
        assertTrue(controller.contains("val model = prefs.getSummarizerModel()"))
        assertFalse(controller.contains("getSummarizerModel().ifBlank"))
    }

    @Test
    fun everySummaryRequestUsesTheResolvedDedicatedRoutingPayload() {
        val controller = source(controllerPath)
        assertTrue(controller.contains("DedicatedModelRoutingPolicy.favoriteForRequest"))
        assertTrue(controller.contains("ProviderRoutingResolver.resolve"))
        assertTrue(controller.contains("ProviderRoutingSerializer.augmentBody"))
        assertTrue(controller.contains("buildClient(endpoint, routingResolution.providerJson)"))
    }

    @Test
    fun settingsUseFavoritesThenDirectEndpointCatalog() {
        val settings = source(settingsPath)
        assertTrue(settings.contains("getFavoriteModels(endpointId)"))
        assertTrue(settings.contains("AdvancedModelSelectorDialogFragment.newAllModelsInstance"))
        assertTrue(settings.contains("setSummarizerModel(\"\")"))
        assertTrue(settings.contains("setSummarizerRoutingType(FavoriteModelObject.ROUTING_AUTOMATIC)"))
    }
}
