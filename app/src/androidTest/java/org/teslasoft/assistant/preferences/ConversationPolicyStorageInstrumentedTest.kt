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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conversation-level memory policy storage (canonical recovery plan §4.4/§4.5,
 * Phase 1 item 12): the per-conversation access, extraction, do-not-analyze,
 * analysis-note, use-default/custom, and processing-method values round-trip in
 * the chat's own settings, and their defaults leave current behavior unchanged.
 * Instrumentation test (real per-chat storage); arm64 device/emulator only.
 */
@RunWith(AndroidJUnit4::class)
class ConversationPolicyStorageInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsAreUnsetSoBehaviorIsUnchanged() {
        val p = Preferences.getPreferences(ctx, "conv-policy-defaults-${System.nanoTime()}")
        // Tri-state access/extraction default to "" (follow the effective default).
        assertEquals("", p.getChatMemoryGeneralAccessRaw())
        assertEquals("", p.getChatMemoryCompanionAccessRaw())
        assertEquals("", p.getChatAnalyzeGeneralRaw())
        assertEquals("", p.getChatAnalyzeCompanionRaw())
        assertEquals("", p.getChatAnalyzeModelRulesRaw())
        assertEquals("", p.getChatConversationPolicyMode())
        assertEquals("", p.getChatProcessingMethod())
        assertEquals("", p.getChatAnalysisNote())
        assertFalse(p.isChatDoNotAnalyze())
    }

    @Test
    fun customConversationPolicyRoundTrips() {
        val p = Preferences.getPreferences(ctx, "conv-policy-custom-${System.nanoTime()}")
        p.setChatMemoryGeneralAccessRaw("false")
        p.setChatMemoryCompanionAccessRaw("true")
        p.setChatAnalyzeGeneralRaw("true")
        p.setChatAnalyzeCompanionRaw("false")
        p.setChatAnalyzeModelRulesRaw("true")
        p.setChatConversationPolicyMode("custom")
        p.setChatProcessingMethod("computer")
        p.setChatAnalysisNote("Do not treat this fiction as user information.")
        p.setChatDoNotAnalyze(true)

        assertEquals("false", p.getChatMemoryGeneralAccessRaw())
        assertEquals("true", p.getChatMemoryCompanionAccessRaw())
        assertEquals("true", p.getChatAnalyzeGeneralRaw())
        assertEquals("false", p.getChatAnalyzeCompanionRaw())
        assertEquals("true", p.getChatAnalyzeModelRulesRaw())
        assertEquals("custom", p.getChatConversationPolicyMode())
        assertEquals("computer", p.getChatProcessingMethod())
        assertEquals("Do not treat this fiction as user information.", p.getChatAnalysisNote())
        assertTrue(p.isChatDoNotAnalyze())
    }

    @Test
    fun useImportanceRatingsDefaultsOffAndPersists() {
        val p = Preferences.getPreferences(ctx, "importance-toggle-${System.nanoTime()}")
        // Recommended default is Off (§7.1).
        assertFalse(p.getUseImportanceRatings())
        p.setUseImportanceRatings(true)
        assertTrue(p.getUseImportanceRatings())
        p.setUseImportanceRatings(false)
        assertFalse(p.getUseImportanceRatings())
    }
}
