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
 *************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.conversation.PendingConversationState
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.onboarding.WelcomeActivity

/**
 * Exported launcher and startup gate only.
 *
 * The retired Chats/Playground tab surface must never be inflated here. Once
 * onboarding, storage and provider migration are settled, the task is replaced
 * by one provisional Chat-mode conversation owned by [ChatActivity].
 */
class MainActivity : FragmentActivity() {
    private lateinit var splashScreen: SplashScreen

    private sealed interface StartupDestination {
        data object LockedStorage : StartupDestination
        data object Welcome : StartupDestination
        data class BlankChat(val pending: PendingConversationState) : StartupDestination
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { true }
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        val consent: SharedPreferences = getSharedPreferences("setup", MODE_PRIVATE)
        if (!consent.getBoolean("setup", false)) {
            route(StartupDestination.Welcome)
            return
        }

        Thread {
            val attempt = runCatching { resolveStartupDestination() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    route(attempt.getOrElse { throw it })
                }
            }
        }.start()
    }

    /** Keep the established lock-before-key-migration ordering off the UI thread. */
    private fun resolveStartupDestination(): StartupDestination {
        if (SecurePrefs.isChatStorageLocked(this)) {
            return StartupDestination.LockedStorage
        }

        val preferences = Preferences.getPreferences(this, "")
        val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(this)
        if (endpoints.getApiEndpoint(this, preferences.getApiEndpointId()).apiKey == "") {
            if (preferences.getApiKey(this) == "") {
                if (preferences.getOldApiKey() == "") {
                    // The new-user path clears the chat list. It must only ever
                    // do that for a user who has no chats: an unconditional
                    // write here erases every conversation on the device the
                    // moment the configured endpoint reads as keyless for any
                    // reason. An unreadable list is left alone too — it is not
                    // evidence of a new user.
                    synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                        val stored = ChatPreferences.getChatPreferences()
                            .getChatListResult(this, includeFirstMessage = false)
                        if (ChatStorageHealth.isAuthoritative(stored.state) && stored.chats.isEmpty()) {
                            SecurePrefs.get(this, "chat_list").edit {
                                putString("data", "[]")
                            }
                        }
                    }
                    return StartupDestination.Welcome
                }
                preferences.secureApiKey(this)
            }
            endpoints.migrateFromLegacyEndpoint(this)
        }

        return StartupDestination.BlankChat(
            NewConversationCoordinator(this).createOrRestoreStartupPendingConversation()
        )
    }

    private fun route(destination: StartupDestination) {
        splashScreen.setKeepOnScreenCondition { false }
        when (destination) {
            StartupDestination.LockedStorage -> startActivity(
                Intent(this, ChatStorageLockedActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            StartupDestination.Welcome -> startActivity(
                Intent(this, WelcomeActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            is StartupDestination.BlankChat -> startActivity(
                ChatActivity.rootIntent(
                    this,
                    destination.pending.id,
                    destination.pending.name,
                    pendingConversation = true
                )
            )
        }
        finish()
    }
}
