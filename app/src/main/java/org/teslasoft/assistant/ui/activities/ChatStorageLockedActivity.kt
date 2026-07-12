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

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.theme.ThemeManager

/**
 * The blocking full-screen state shown while encrypted chat storage is
 * LOCKED (Round 4, owner-approved copy July 12 2026). MainActivity and
 * ChatActivity redirect here BEFORE any chat UI, onboarding, or API-key
 * check can run — a locked account must never look like a fresh install or
 * an empty chat list, and nothing behind this screen may create or save
 * chats.
 *
 * "Try Again" re-attempts opening the encrypted storage (off the main
 * thread; touches no data, never clears or migrates anything) and relaunches
 * the app's normal entry on success; on failure the screen simply remains —
 * it is the state, no toast, no dialog. "View Error Log" opens the
 * persistent Error Log where the lock lines are written. The screen stays
 * until storage opens or the user leaves the app.
 */
class ChatStorageLockedActivity : FragmentActivity() {

    private var btnRetry: MaterialButton? = null
    private var btnViewLog: MaterialButton? = null
    private var retryInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_chat_storage_locked)

        btnRetry = findViewById(R.id.btn_storage_locked_retry)
        btnViewLog = findViewById(R.id.btn_storage_locked_view_log)

        btnRetry?.setOnClickListener { retry() }
        btnViewLog?.setOnClickListener {
            startActivity(
                Intent(this, LogsActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("type", "crash")
            )
        }
    }

    private fun retry() {
        if (retryInFlight) return
        retryInFlight = true
        btnRetry?.isEnabled = false
        Thread {
            val unlocked = try {
                SecurePrefs.retryUnlock(this)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                retryInFlight = false
                btnRetry?.isEnabled = true
                if (unlocked && !isFinishing) {
                    // Storage opened: relaunch the normal entry point fresh so
                    // every gate re-evaluates against healthy storage.
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
                // Still locked: remain — this screen IS the state.
            }
        }.start()
    }
}
