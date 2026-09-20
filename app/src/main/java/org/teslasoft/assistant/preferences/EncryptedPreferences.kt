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
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

class EncryptedPreferences {
    companion object {
        private var mainKey: MasterKey? = null
        private val preferencesCache = mutableMapOf<String, SharedPreferences>()

        @Synchronized
        private fun getMasterKey(context: Context): MasterKey {
            if (mainKey == null) {
                mainKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            }
            return mainKey!!
        }

        @Synchronized
        private fun getEncryptedSharedPreferences(context: Context, fileName: String): SharedPreferences {
            return preferencesCache[fileName] ?: run {
                val sharedPreferences = EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    getMasterKey(context),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                preferencesCache[fileName] = sharedPreferences
                sharedPreferences
            }
        }


        /**
         * Get encrypted preference
         * */
        fun getEncryptedPreference(context: Context, file: String, key: String) : String {
            return try {
                val sharedPreferences = getEncryptedSharedPreferences(context, file)
                sharedPreferences.getString(key, "") ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        /**
         * Set encrypted preference
         * */
        fun setEncryptedPreference(context: Context, file: String, key: String, value: String) {
            getEncryptedSharedPreferences(context, file).edit {
                putString(key, value)
            }
        }

        /**
         * Like [getEncryptedPreference], but a Keystore/open FAILURE is
         * distinguishable from an absent value: null = storage unavailable,
         * "" = genuinely unset. Required by the recovery-key store (owner
         * ruling, July 22 2026): an outage must surface as a visible failure,
         * never masquerade as "no key set".
         */
        fun getEncryptedPreferenceOrNull(context: Context, file: String, key: String): String? {
            return try {
                getEncryptedSharedPreferences(context, file).getString(key, "") ?: ""
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Synchronous-commit write, returning whether it durably succeeded.
         * Used for recovery-key material, where an apply()-deferred write that
         * later fails would silently strand the user without a working copy.
         */
        fun setEncryptedPreferenceCommit(context: Context, file: String, key: String, value: String): Boolean {
            return try {
                getEncryptedSharedPreferences(context, file).edit().putString(key, value).commit()
            } catch (_: Exception) {
                false
            }
        }
    }
}
