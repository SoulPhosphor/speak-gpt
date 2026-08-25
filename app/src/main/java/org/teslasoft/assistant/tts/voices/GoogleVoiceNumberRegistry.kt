package org.teslasoft.assistant.tts.voices

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Persistent presentation-only numbers for Google voice IDs.
 *
 * Assignments live in the app's global settings file, are never deleted, and
 * the next counter only moves forward. This deliberately trades a tiny amount
 * of stale preference data for names that never change after language packs
 * are installed or removed.
 */
class GoogleVoiceNumberRegistry(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun displayNamesFor(voiceIds: Collection<String>): Map<String, String> = synchronized(LOCK) {
        val state = decode(preferences.getString(KEY, null))
        val assigned = assign(state.assignments, state.nextNumber, voiceIds)
        if (assigned != state) preferences.edit { putString(KEY, encode(assigned)) }
        voiceIds.distinct().associateWith { id -> "Voice ${assigned.assignments.getValue(id)}" }
    }

    fun displayNameFor(voiceId: String): String = displayNamesFor(listOf(voiceId)).getValue(voiceId)

    data class State(val assignments: Map<String, Int>, val nextNumber: Int)

    companion object {
        private const val KEY = "google_voice_number_registry"
        private val LOCK = Any()

        /** Pure allocator kept public to make the no-renumber/no-reuse contract testable. */
        fun assign(existing: Map<String, Int>, nextNumber: Int, seenIds: Collection<String>): State {
            val result = existing.toMutableMap()
            var next = maxOf(nextNumber, (existing.values.maxOrNull() ?: 0) + 1)
            seenIds.distinct().sorted().forEach { id ->
                if (id !in result) result[id] = next++
            }
            return State(result, next)
        }

        private fun decode(raw: String?): State = try {
            val root = JSONObject(raw.orEmpty())
            val values = root.optJSONObject("assignments") ?: JSONObject()
            val assignments = buildMap {
                values.keys().forEach { id ->
                    values.optInt(id, 0).takeIf { it > 0 }?.let { put(id, it) }
                }
            }
            State(assignments, root.optInt("next", 1).coerceAtLeast(1))
        } catch (_: Throwable) {
            State(emptyMap(), 1)
        }

        private fun encode(state: State): String = JSONObject()
            .put("next", state.nextNumber)
            .put("assignments", JSONObject(state.assignments))
            .toString()
    }
}
