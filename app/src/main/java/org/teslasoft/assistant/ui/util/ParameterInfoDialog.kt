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
 * The shared "what does this setting do?" popup opened by the circled-i
 * information button next to a parameter's title. It is a plain, compact
 * information dialog in the app's standard dialog style: the circled-i icon,
 * the parameter's name as the title, its explanation as the body, and a single
 * dismissive button. It replaced the always-on subtext that used to sit under
 * every parameter title, so the explanation is one tap away instead of
 * crowding the screen.
 */
object ParameterInfoDialog {
    fun show(context: Context, title: CharSequence, body: CharSequence) {
        MaterialAlertDialogBuilder(context, R.style.App_MaterialAlertDialog)
            .setIcon(R.drawable.ic_info)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }
}
