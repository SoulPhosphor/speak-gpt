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
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.memory.ChatScanCoverage
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.scan.ChatScanSource
import org.teslasoft.assistant.preferences.memory.scan.ScanChatStatus

/**
 * The Create Outline screen's "Memory Scan Progress: …" line (owner design,
 * Nov 18 2026), shown while App Appearance's Show Chat Memory Scan Status is
 * on. Hidden when the memory store does not exist or the chat cannot be
 * read. Read from the chat's bookmark and its current messages (Stage 6A).
 */
object ChatScanProgressLine {

    fun bind(owner: LifecycleOwner, context: Context, line: TextView, chatId: String) {
        if (!Preferences.getPreferences(context, "").getShowChatMemoryScanStatus()) {
            line.visibility = View.GONE
            return
        }
        owner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    status(context, chatId)
                } catch (_: Exception) {
                    null
                }
            }
            val value = when (status) {
                null -> null
                ChatScanCoverage.Status.Complete ->
                    context.getString(R.string.chat_outline_memory_scan_complete)
                ChatScanCoverage.Status.Unscanned ->
                    context.getString(R.string.chat_outline_memory_scan_unscanned)
                is ChatScanCoverage.Status.Range ->
                    context.getString(R.string.chat_outline_memory_scan_range, status.first, status.last)
            }
            if (value == null) {
                line.visibility = View.GONE
            } else {
                line.text = context.getString(R.string.chat_outline_memory_scan_progress, value)
                line.visibility = View.VISIBLE
            }
        }
    }

    private fun status(context: Context, chatId: String): ChatScanCoverage.Status? {
        if (!MemoryStore.isProvisioned(context)) return null
        val progress = MemoryStore.getInstance(context).scanProgress(chatId)
        val messages = ChatScanSource.peek(context, chatId) ?: return null
        return ScanChatStatus.coverage(progress, messages)
    }
}
