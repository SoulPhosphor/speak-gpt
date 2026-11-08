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
import org.teslasoft.assistant.preferences.FakeSharedPreferences

class ModelSupportedParametersStoreTest {

    private val catalog = """{"data":[
        {"id":"vendor/a","supported_parameters":["temperature","Top_P","max_tokens"]},
        {"id":"vendor/b"},
        {"id":"vendor/c","supported_parameters":[]}
    ]}"""

    @Test
    fun aCatalogsReportedParametersAreRemembered() {
        val store = ModelSupportedParametersStore(FakeSharedPreferences())
        store.learnFromCatalog("ep-1", catalog)
        assertEquals(setOf("temperature", "top_p", "max_tokens"), store.supported("ep-1", "vendor/a"))
        assertEquals(emptySet<String>(), store.supported("ep-1", "vendor/c"))
    }

    @Test
    fun aModelTheCatalogDoesNotDescribeIsUnknown() {
        val store = ModelSupportedParametersStore(FakeSharedPreferences())
        store.learnFromCatalog("ep-1", catalog)
        assertNull(store.supported("ep-1", "vendor/b"))
        assertNull(store.supported("ep-1", "vendor/missing"))
        assertNull(store.supported("ep-2", "vendor/a"))
    }

    @Test
    fun aBodyThatIsNotACatalogKeepsWhatWasLearned() {
        val store = ModelSupportedParametersStore(FakeSharedPreferences())
        store.learnFromCatalog("ep-1", catalog)
        store.learnFromCatalog("ep-1", "{\"error\":\"rate limited\"}")
        assertEquals(setOf("temperature", "top_p", "max_tokens"), store.supported("ep-1", "vendor/a"))
    }

    @Test
    fun deletingAProfileForgetsItsList() {
        val store = ModelSupportedParametersStore(FakeSharedPreferences())
        store.learnFromCatalog("ep-1", catalog)
        store.clearEndpoint("ep-1")
        assertNull(store.supported("ep-1", "vendor/a"))
    }
}
