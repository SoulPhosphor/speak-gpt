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

package org.teslasoft.assistant.ui.util

import android.view.View
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.chat.ChatNameStyle

/**
 * The shared Chat Signature Style section (layout/view_chat_signature.xml):
 * the name exactly as chat styles it, and a button into Name Style. The host
 * supplies the name text, the resolved style, and where the button goes.
 */
class ChatSignatureSection(root: View, onChangeStyle: () -> Unit) {
    private val preview: TextView = root.findViewById(R.id.signature_preview)

    init {
        root.findViewById<View>(R.id.signature_change_style).setOnClickListener { onChangeStyle() }
    }

    fun setName(name: String) {
        preview.text = name
    }

    fun setStyle(style: ChatNameStyle.Resolved) {
        ChatNameStyle.apply(preview, preview.context, style)
    }
}
