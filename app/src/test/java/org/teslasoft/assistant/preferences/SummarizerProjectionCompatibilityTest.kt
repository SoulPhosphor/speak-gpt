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

package org.teslasoft.assistant.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.includes.SummarizerProjectionContract

class SummarizerProjectionCompatibilityTest {

    private fun preferences(store: FakeSharedPreferences): Preferences =
        Preferences(store, FakeSharedPreferences(), "chat")

    @Test
    fun pre62SummaryAndBookmarkAreInvalidatedWithoutTouchingChatData() {
        val store = FakeSharedPreferences()
        store.edit()
            .putString("summarizer_summary", "STALE ATTACHMENT PAYLOAD")
            .putString("summarizer_folded", "30")
            .putString("summarizer_over_length", "true")
            .putString("summarizer_episode", "NETWORK")
            .putString("unrelated_chat_value", "preserved")
            .commit()
        val prefs = preferences(store)

        assertTrue(prefs.ensureSummarizerProjectionCompatibility())
        assertEquals("", prefs.getSummarizerSummary())
        assertEquals(0, prefs.getSummarizerFoldedCount())
        assertFalse(prefs.getSummarizerOverLength())
        assertEquals("", prefs.getSummarizerEpisode())
        assertEquals("preserved", store.getString("unrelated_chat_value", ""))
        assertEquals(
            SummarizerProjectionContract.VERSION,
            prefs.getSummarizerProjectionVersion()
        )
    }

    @Test
    fun compatibleSummaryIsNotInvalidatedAgain() {
        val store = FakeSharedPreferences()
        val prefs = preferences(store)
        assertTrue(prefs.ensureSummarizerProjectionCompatibility())
        assertTrue(prefs.commitSummarizerFoldIn("SAFE SUMMARY", 10, false))

        assertTrue(prefs.ensureSummarizerProjectionCompatibility())
        assertEquals("SAFE SUMMARY", prefs.getSummarizerSummary())
        assertEquals(10, prefs.getSummarizerFoldedCount())
    }

    @Test
    fun foldInAndUserEditStampTheCurrentProjectionVersion() {
        val foldStore = FakeSharedPreferences()
        val foldPrefs = preferences(foldStore)
        assertTrue(foldPrefs.commitSummarizerFoldIn("safe", 4, false))
        assertEquals(
            SummarizerProjectionContract.VERSION,
            foldPrefs.getSummarizerProjectionVersion()
        )

        val editStore = FakeSharedPreferences()
        val editPrefs = preferences(editStore)
        assertTrue(editPrefs.commitSummarizerSummaryEdit("safe edit"))
        assertEquals(
            SummarizerProjectionContract.VERSION,
            editPrefs.getSummarizerProjectionVersion()
        )
    }
}
