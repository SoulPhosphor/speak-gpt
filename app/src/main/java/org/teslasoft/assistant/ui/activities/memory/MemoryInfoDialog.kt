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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryRecord

/**
 * The Info control content for a memory (Step 1.5): its source, provenance, and
 * whatever evidence the store actually holds — nothing is invented. Shared by
 * the Associative Memory Pending cards and the Possible Match Review screen so
 * the proposed memory and each existing memory expose the same details.
 */
object MemoryInfoDialog {

    fun show(context: Context, m: MemoryRecord) {
        val lines = ArrayList<String>()
        lines.add(context.getString(R.string.mem_info_source, sourceLabel(context, m)))

        m.provenanceContext?.takeIf { it.isNotBlank() }?.let {
            lines.add(context.getString(R.string.mem_info_from_chat, it))
        }
        m.provenanceNotedOn?.takeIf { it.isNotBlank() }?.let {
            lines.add(context.getString(R.string.mem_info_noted, it.take(10)))
        }
        confidenceLabel(context, m.provenanceConfidence)?.let {
            lines.add(context.getString(R.string.mem_info_confidence, it))
        }
        // "Available evidence": when the store holds nothing beyond the source
        // label, say so plainly rather than implying more is known.
        if (lines.size == 1) lines.add(context.getString(R.string.mem_info_none))

        MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_info_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
    }

    private fun sourceLabel(context: Context, m: MemoryRecord): String = context.getString(
        when {
            m.provenanceSource == "imported" -> R.string.mem_source_imported
            m.origin == "archivist" || m.provenanceSource == "inferred" -> R.string.mem_source_learned
            m.provenanceSource == null || m.provenanceSource == "user_entered" ||
                m.provenanceSource == "user_stated" -> R.string.mem_source_hand
            else -> R.string.mem_source_learned
        }
    )

    private fun confidenceLabel(context: Context, confidence: String?): String? = when (confidence) {
        "certain" -> context.getString(R.string.mem_conf_certain)
        "likely" -> context.getString(R.string.mem_conf_likely)
        "tentative" -> context.getString(R.string.mem_conf_tentative)
        else -> null
    }
}
