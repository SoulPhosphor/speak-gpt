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

object MemoryInfoDialog {

    fun show(context: Context, m: MemoryRecord) {
        val lines = ArrayList<String>()
        lines.add(context.getString(R.string.mem_info_source, sourceLabel(context, m)))
        lines.add(context.getString(R.string.mem_info_none))

        MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_info_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.btn_ok) { d, _ -> d.dismiss() }
            .show()
    }

    private fun sourceLabel(context: Context, m: MemoryRecord): String = context.getString(
        if (m.origin == "archivist") R.string.mem_source_learned else R.string.mem_source_hand
    )
}
