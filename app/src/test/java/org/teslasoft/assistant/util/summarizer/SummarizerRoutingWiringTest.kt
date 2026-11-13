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
import org.junit.Assert.assertEquals
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
    private val rowsPath =
        "src/main/java/org/teslasoft/assistant/ui/dedicated/DedicatedModelRows.kt"

    @Test
    fun summaryModelIsExplicitAndNeverFallsBackToEndpointChatModel() {
        val controller = source(controllerPath)
        assertTrue(controller.contains("val model = prefs.getSummarizerModel()"))
        assertFalse(controller.contains("getSummarizerModel().ifBlank"))
    }

    @Test
    fun everySummaryRequestUsesTheResolvedDedicatedRoutingPayload() {
        val controller = source(controllerPath)
        // The Summarizer's own routing (its favorites and its own routing),
        // never the chat's favorites.
        assertTrue(controller.contains("DedicatedModelRouting.SUMMARIZER.effective"))
        assertFalse(controller.contains("FavoriteModelsPreferences"))
        assertTrue(controller.contains("ProviderRoutingResolver.resolve"))
        assertTrue(controller.contains("ProviderRoutingSerializer.augmentBody"))
        // Image summaries, backup titles and Chat Outline tag suggestions all
        // carry dedicated routing and request-local response evidence. Sections
        // and compaction do likewise, all through the one request helper that
        // builds the client.
        fun calls(text: String) = Regex(Regex.escape(text)).findAll(controller).count()
        assertEquals(3, calls("routingResolution.providerJson, UsageFunction."))
        assertEquals(2, calls("runtime.providerJson, UsageFunction."))
        assertEquals(1, calls("buildClient(endpoint, providerJson, evidence, attempt)"))
    }

    @Test
    fun settingsUseTheSummarizersOwnFavoritesThenItsPicker() {
        val settings = source(settingsPath)
        assertTrue(settings.contains("DedicatedModelRows("))
        assertTrue(settings.contains("ModelPickerScope.SUMMARIZER"))
        val rows = source(rowsPath)
        assertTrue(rows.contains("FavoriteModelsPreferences.getPreferences(activity, scope)"))
        assertTrue(rows.contains("getFavoriteModels(endpointId)"))
        assertTrue(rows.contains("newDedicatedModelPickerInstance(scope, endpointId, startOnViewAll = true)"))
        // A profile change clears the model and the job's own routing.
        assertTrue(rows.contains("routing.select(activity, pickedId, \"\")"))
        assertTrue(rows.contains("routing.clear(activity)"))
    }
}
