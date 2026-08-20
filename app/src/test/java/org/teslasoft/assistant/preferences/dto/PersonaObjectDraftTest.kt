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

package org.teslasoft.assistant.preferences.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the New Companion crash caused by zero prompt tabs. */
class PersonaObjectDraftTest {

    @Test
    fun emptyDraftStartsWithOneDefaultPrompt() {
        val draft = PersonaObject.emptyDraft()

        assertEquals(1, draft.promptVariants.size)
        val prompt = draft.promptVariants.single()
        assertEquals("Prompt 1", prompt.name)
        assertEquals("", prompt.text)
        assertTrue(prompt.isDefault)
        assertEquals("", draft.prompt)
    }

    @Test
    fun emptyDraftIsStillUnsaved() {
        val draft = PersonaObject.emptyDraft()

        assertEquals("", draft.id)
        assertEquals("", draft.label)
    }
}
