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
import org.teslasoft.assistant.preferences.Preferences

/**
 * Fills the progress pop-up's "Using Summarizer Model: <model>" line so the
 * user can see at a glance which model is running and cancel if needed.
 * Shows the model name only (no provider); stays hidden without a model.
 */
object SummarizerModelLine {

    fun bind(dialogView: View, chatId: String) {
        val line = dialogView.findViewById<TextView>(R.id.progress_dialog_model) ?: return
        val model = try {
            Preferences.getPreferences(dialogView.context, chatId).getSummarizerModel().trim()
        } catch (_: Exception) {
            ""
        }
        if (model.isEmpty()) {
            line.visibility = View.GONE
            return
        }
        line.text = dialogView.context.getString(R.string.progress_dialog_summarizer_model, model)
        line.visibility = View.VISIBLE
    }
}
