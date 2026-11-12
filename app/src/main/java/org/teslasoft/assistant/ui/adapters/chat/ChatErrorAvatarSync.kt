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


package org.teslasoft.assistant.ui.adapters.chat

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import org.teslasoft.assistant.preferences.MessageCompletionState

/**
 * Tracks whether the chat shows the "no successful reply yet" error avatar
 * (owner ruling, July 31 2026). That choice depends on the whole chat, but
 * the chat screen rebinds only the row that changed, so the other assistant
 * rows kept the picture they were last drawn with. Pure Kotlin so it can be
 * unit-tested; [ChatErrorAvatarSync] connects it to the adapter.
 */
class ChatErrorAvatarState(initial: List<Map<String, Any?>>) {
    var showsError: Boolean = MessageCompletionState.chatShowsErrorAvatar(initial)
        private set

    /** Re-reads the chat. True when the avatar choice changed. */
    fun update(messages: List<Map<String, Any?>>): Boolean {
        val now = MessageCompletionState.chatShowsErrorAvatar(messages)
        if (now == showsError) return false
        showsError = now
        return true
    }
}

/**
 * Redraws every row when the error avatar turns on or off, so all assistant
 * rows show the same avatar. A full refresh already redraws every row, so it
 * only records the new state.
 */
class ChatErrorAvatarSync(
    private val adapter: RecyclerView.Adapter<*>,
    private val messages: () -> List<Map<String, Any?>>
) : RecyclerView.AdapterDataObserver() {

    private val state = ChatErrorAvatarState(messages())
    private val handler = Handler(Looper.getMainLooper())

    override fun onChanged() {
        state.update(messages())
    }

    override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = check()
    override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = check()
    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = check()
    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = check()
    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = check()

    private fun check() {
        if (!state.update(messages())) return
        // Posted: this runs inside another notify call, and the list may be
        // in the middle of a layout pass.
        handler.post {
            val count = adapter.itemCount
            if (count > 0) adapter.notifyItemRangeChanged(0, count, PAYLOAD)
        }
    }

    private companion object {
        /** A payload keeps the rows in place instead of cross-fading them. */
        const val PAYLOAD = "error_avatar"
    }
}
