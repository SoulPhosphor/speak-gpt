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

import android.content.SharedPreferences

/**
 * A minimal, fully in-memory [SharedPreferences] for JVM unit tests (no
 * Robolectric, matching this project's test setup). Enough of the contract to
 * drive the id-keyed preference stores: string/primitive get/put, remove,
 * clear, contains, and a live `all` view. Writes apply immediately on both
 * commit() and apply().
 */
class FakeSharedPreferences : SharedPreferences {
    private val store = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(store)

    override fun getString(key: String?, defValue: String?): String? =
        if (store.containsKey(key)) store[key] as? String else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (store.containsKey(key)) store[key] as? MutableSet<String> else defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (store[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (store[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (store[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (store[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { /* no-op */ }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { /* no-op */ }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
        override fun remove(key: String?): SharedPreferences.Editor { removals.add(key!!); return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) store.clear()
            for (k in removals) store.remove(k)
            store.putAll(pending)
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}
