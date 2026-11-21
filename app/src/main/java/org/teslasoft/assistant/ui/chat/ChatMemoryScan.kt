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

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.archivist.Archivist
import org.teslasoft.assistant.service.MemoryAnalysisForegroundService
import org.teslasoft.assistant.service.MemoryAnalysisState

/**
 * The chat menu's Scan for Memories (owner design, Nov 18 2026): scans only
 * the part of this chat not yet scanned, even while the chat's memory Archive
 * switch is off, with the scanner type the user picks each time.
 */
object ChatMemoryScan {

    /** The menu item shows only when a scan could run: the memory store
     *  exists, the Memory Assistant has its endpoint, model and routing, and
     *  no database is degraded (a scan must never run against a bad one). */
    fun isAvailable(context: Context): Boolean = try {
        MemoryStore.isProvisioned(context) &&
            Archivist.isScannerReady(context) &&
            !DatabaseHealthState.anyDegraded(context)
    } catch (_: Exception) {
        false
    }

    fun start(activity: FragmentActivity, chatId: String) {
        activity.lifecycleScope.launch {
            val hasUnscanned = withContext(Dispatchers.IO) {
                try {
                    org.teslasoft.assistant.preferences.memory.archivist.Archivist
                        .chatHasUnscannedMessages(activity, chatId)
                } catch (_: Exception) {
                    false
                }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (!hasUnscanned) {
                showMessage(activity, R.string.chat_scan_nothing_new_title, R.string.chat_scan_nothing_new_msg)
            } else {
                chooseType(activity) { type -> launchScan(activity, chatId, type) }
            }
        }
    }

    private fun chooseType(activity: FragmentActivity, onChosen: (String) -> Unit) {
        val actions = LayoutInflater.from(activity).inflate(R.layout.dialog_cancel_two_choices_row, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_cancel_action)
        val ragActive = actions.findViewById<MaterialButton>(R.id.btn_dialog_first_choice)
        val lorebook = actions.findViewById<MaterialButton>(R.id.btn_dialog_second_choice)
        cancel.setText(R.string.btn_cancel)
        ragActive.setText(R.string.chat_scan_type_rag_active)
        lorebook.setText(R.string.label_lorebook)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_scan_type_title)
            .setView(actions)
            .setCancelable(true)
            .create()
        cancel.setOnClickListener { dialog.dismiss() }
        ragActive.setOnClickListener {
            dialog.dismiss()
            onChosen("associative")
        }
        lorebook.setOnClickListener {
            dialog.dismiss()
            onChosen("lorebook")
        }
        dialog.show()
    }

    private fun launchScan(activity: FragmentActivity, chatId: String, analysisType: String) {
        // Another scan is live: this chat is taken as soon as that scan
        // finishes its current conversation, and the pop-up says so.
        val busy = MemoryAnalysisForegroundService.state.value is MemoryAnalysisState.Running
        if (!MemoryAnalysisForegroundService.startChatScan(activity, chatId, analysisType)) {
            // The service could not start, so nothing ran and nothing was
            // claimed; the existing run-failure wording applies.
            showMessage(activity, R.string.mem_arch_full_label, R.string.mem_arch_full_msg)
            return
        }
        if (busy) {
            showMessage(activity, R.string.chat_scan_in_progress_title, R.string.chat_scan_in_progress_msg)
        }
    }

    private fun showMessage(activity: FragmentActivity, titleRes: Int, messageRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val actions = LayoutInflater.from(activity).inflate(R.layout.dialog_single_action, null)
        val okay = actions.findViewById<MaterialButton>(R.id.btn_dialog_action)
        okay.setText(R.string.btn_ok)
        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(actions)
            .setCancelable(true)
            .create()
        okay.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
