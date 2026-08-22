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

package org.teslasoft.assistant.reasoning

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningSelectorWiringTest {
    private val source by lazy { selectorSource().readText() }

    @Test
    fun openRouterUsesTheVisibleCatalogResponseForCapability() {
        assertTrue(source.contains("refreshFromOpenRouterCatalog"))
        assertFalse(source.contains("syncReasoningCapability"))
        assertFalse(source.contains("\"reasoningCaps\""))
        assertEquals(1, Regex("startRequestNetwork\\(\\\"GET\\\", base \\+ \\\"models\\\"").findAll(source).count())
    }

    @Test
    fun searchFiltersAlreadyLoadedModelsWithoutStartingARequest() {
        val watcher = source.substringAfter("fieldSearch?.addTextChangedListener")
            .substringBefore("// Image mode and provider-wide callers")
        assertTrue(watcher.contains("render()"))
        assertFalse(watcher.contains("startRequestNetwork"))
        assertFalse(watcher.contains("startCatalogFetch"))
    }

    private fun selectorSource(): File {
        val relative = "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AdvancedModelSelectorDialogFragment.kt"
        return listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate AdvancedModelSelectorDialogFragment.kt")
    }
}
