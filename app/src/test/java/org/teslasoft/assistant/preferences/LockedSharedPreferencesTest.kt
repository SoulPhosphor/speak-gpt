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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LOCKED-state storage view (Round 4 owner policy: block, never
 * redirect to plaintext). These tests pin its two contracts: a locked write
 * is REFUSED — commit() reports false and nothing is stored anywhere — and
 * a locked read presents nothing while the state stays visible through
 * SecurePrefs.isLockedName / the result-typed reads, never through a fake
 * "empty" that looks like real data. There is no storage behind this class
 * at all, so no sensitive write can create or modify any plaintext file.
 */
class LockedSharedPreferencesTest {

    private val prefs = SecurePrefs.LockedSharedPreferences

    @Test fun lockedWritesAreRefused() {
        val editor = prefs.edit()
            .putString("chat", "[{\"m\":\"secret\"}]")
            .putBoolean("flag", true)
            .putInt("n", 1)
        // The refusal is explicit: commit reports failure.
        assertFalse(editor.commit())
        // And nothing was retained — the view stays empty afterwards.
        assertNull(prefs.getString("chat", null))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun applyIsAnInertNoOp() {
        prefs.edit().putString("chat", "data").apply()
        assertNull(prefs.getString("chat", null))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun readsPresentOnlyCallerDefaults() {
        assertNull(prefs.getString("data", null))
        assertEquals("[]", prefs.getString("data", "[]"))
        assertEquals(7, prefs.getInt("x", 7))
        assertEquals(true, prefs.getBoolean("b", true))
        assertEquals(3L, prefs.getLong("l", 3L))
        assertEquals(1.5f, prefs.getFloat("f", 1.5f))
        assertFalse(prefs.contains("data"))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun clearAndRemoveAreRefusedToo() {
        assertFalse(prefs.edit().clear().commit())
        assertFalse(prefs.edit().remove("anything").commit())
    }
}
