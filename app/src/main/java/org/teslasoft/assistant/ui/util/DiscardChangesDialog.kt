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

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * The app's standard "unsaved changes" confirmation (owner ruling, July 20
 * 2026). Any full-screen editor that lets the user back out (toolbar back
 * button or the system back gesture) while it has an explicit Save action
 * must confirm before discarding. Exact wording is owner-approved and must
 * not change without asking: message @string/discard_changes_q ("Discard
 * changes?"), positive button @string/okay ("Okay") discards and proceeds,
 * negative button @string/btn_cancel ("Cancel") only dismisses the dialog
 * and leaves the screen untouched. Themed with the app's standard
 * App_MaterialAlertDialog.
 */
object DiscardChangesDialog {
    fun show(context: Context, onDiscard: () -> Unit) {
        MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.discard_changes_q)
            .setPositiveButton(R.string.okay) { _, _ -> onDiscard() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
}
