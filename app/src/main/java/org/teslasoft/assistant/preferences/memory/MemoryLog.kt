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

package org.teslasoft.assistant.preferences.memory

import android.content.Context
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.preferences.Preferences

/**
 * Single gate for ALL memory-system diagnostics. Everything memory-related
 * that would otherwise call [Logger.log] goes through here, so the user's
 * "Memory diagnostics logging" switch in Alerts, Errors & Logs turns it all on
 * or off in one place (off by default — the same opt-in pattern as VAD
 * logging). Nothing memory-related writes to the Event log while the toggle is
 * off, and reading the pref is best-effort so a diagnostic call can never
 * disturb the caller.
 */
object MemoryLog {

    fun enabled(context: Context): Boolean = try {
        Preferences.getPreferences(context, "").getMemoryDebugLogging()
    } catch (_: Exception) {
        false
    }

    /** Log to the Memory Debug Log (its own channel, separate from the Voice
     *  Debug log) only when memory diagnostics are enabled. */
    fun log(context: Context, tag: String, level: String, message: String) {
        try {
            if (enabled(context)) Logger.log(context, "memory", tag, level, message)
        } catch (_: Exception) { /* diagnostics must never break the caller */ }
    }

    /** Log regardless of the diagnostics toggle. Reserved for Archivist run
     *  failure / partial-failure records, which the owner ruled are
     *  user-relevant recovery information, not optional debug noise
     *  (`archivist_status_wording_spec.md`, July 8 2026) — do not widen this
     *  to ordinary diagnostics. */
    fun logAlways(context: Context, tag: String, level: String, message: String) {
        try {
            Logger.log(context, "memory", tag, level, message)
        } catch (_: Exception) { /* diagnostics must never break the caller */ }
    }
}
