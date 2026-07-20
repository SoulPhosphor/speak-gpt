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

import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.content.Context
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R

/**
 * The app's standard "unsaved changes" confirmation (owner ruling, July 20
 * 2026, restyled same day). Any full-screen editor that lets the user back
 * out (toolbar back button or the system back gesture) while it has an
 * explicit Save action must confirm before discarding. Exact wording is
 * owner-approved and must not change without asking: @string/discard_changes_q
 * ("Discard changes?") is the dialog's whole question and has no separate
 * subtext, so it is set as the dialog TITLE (title-sized, not the smaller
 * message/body size) and centered — the "single question, no subtext" case
 * of the app's title+subtext dialog convention (see ui-style-guide.md). The
 * two actions are real buttons, not flat text: the primary action
 * (@string/okay, "Okay") comes first/start and discards and proceeds; the
 * destructive action (@string/btn_cancel, "Cancel") comes second/end and
 * only dismisses the dialog, leaving the screen untouched. Built from
 * dialog_two_actions.xml (AppButton.Primary.DialogAction /
 * AppButton.Destructive.DialogAction) inside the app's standard
 * App_MaterialAlertDialog theme.
 */
object DiscardChangesDialog {
    fun show(context: Context, onDiscard: () -> Unit) {
        val actionsView = LayoutInflater.from(context).inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.discard_changes_q)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                onDiscard()
            }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()

        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.gravity = Gravity.CENTER
    }
}
