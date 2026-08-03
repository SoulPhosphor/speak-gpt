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
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/**
 * The shared anchored dropdown for choosing one saved API endpoint profile.
 *
 * Selection is carried by the profile's stable id rather than its label, so
 * two profiles with the same visible name remain distinct.
 */
object EndpointProfileDropdown {

    internal data class Choice(val id: String, val label: String)

    internal fun choices(endpoints: List<ApiEndpointObject>): List<Choice> =
        endpoints.map { Choice(it.id, it.label) }

    fun show(
        context: Context,
        anchor: View,
        endpoints: List<ApiEndpointObject>,
        onSelected: (String) -> Unit
    ) {
        val choices = choices(endpoints)
        if (choices.isEmpty()) return

        val popup = ListPopupWindow(context)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = ListPopupWindow.WRAP_CONTENT
        popup.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                choices.map { it.label }
            )
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onSelected(choices[position].id)
        }
        popup.show()
    }
}
