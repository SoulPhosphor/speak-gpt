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

package org.teslasoft.assistant.ui.activities.memory

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.teslasoft.assistant.R

/**
 * The Zone 1 word count shared by every card screen (spec §8a, the owner's
 * exact rules): the count sits right-aligned under the Zone 1 box on a
 * neutral pill; at 300 words the "content is getting large" warning appears;
 * at 500 the count turns red (a red that stays visible on any themed
 * background — hence the pill) and the warning switches to the
 * move-to-Zone-2 suggestion.
 */
object CardZoneUi {

    private const val WARN_WORDS = 300
    private const val ALERT_WORDS = 500

    /** Attaches live word counting over [fields]; call once after findViewById.
     *  Hidden/unused fields are harmless — empty text counts zero. */
    fun attachWordCount(
        context: Context,
        fields: List<EditText?>,
        countView: TextView?,
        warnView: TextView?
    ) {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update(context, fields, countView, warnView)
        }
        fields.forEach { it?.addTextChangedListener(watcher) }
        update(context, fields, countView, warnView)
    }

    private fun update(
        context: Context,
        fields: List<EditText?>,
        countView: TextView?,
        warnView: TextView?
    ) {
        val words = fields.sumOf { field ->
            field?.text?.toString()?.trim()?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0
        }
        val res = context.resources
        countView?.text = res.getQuantityString(R.plurals.card_word_count, words, words)
        countView?.setTextColor(
            if (words >= ALERT_WORDS) ResourcesCompat.getColor(res, R.color.card_count_alert, null)
            else ResourcesCompat.getColor(res, R.color.text_subtitle, null)
        )
        when {
            words >= ALERT_WORDS -> {
                warnView?.visibility = View.VISIBLE
                warnView?.setText(R.string.card_warn_very_large)
            }
            words >= WARN_WORDS -> {
                warnView?.visibility = View.VISIBLE
                warnView?.setText(R.string.card_warn_large)
            }
            else -> warnView?.visibility = View.INVISIBLE
        }
    }
}
