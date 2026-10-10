package org.teslasoft.assistant.preferences

import android.content.SharedPreferences

/** Reverts both process-local values and persisted values after a failed synchronous write. */
internal object PreferenceCommitRollback {
    fun commit(prefs: SharedPreferences, editor: SharedPreferences.Editor, keys: Set<String>): Boolean {
        val previous = prefs.all.filterKeys { it in keys }.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val committed = try { editor.commit() } catch (_: Exception) { false }
        if (committed) return true
        try {
            val rollback = prefs.edit()
            for (key in keys) {
                when (val value = previous[key]) {
                    null -> rollback.remove(key)
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        rollback.putStringSet(key, value as Set<String>)
                    }
                }
            }
            // A second disk failure still installs the old values in process memory.
            rollback.commit()
        } catch (_: Exception) { }
        return false
    }
}
