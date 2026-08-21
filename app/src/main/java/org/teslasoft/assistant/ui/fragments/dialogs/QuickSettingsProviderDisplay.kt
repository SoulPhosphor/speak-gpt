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

package org.teslasoft.assistant.ui.fragments.dialogs

import org.teslasoft.assistant.preferences.dto.FavoriteModelObject

/** Selects the provider identity represented by the active model's routing. */
internal object QuickSettingsProviderDisplay {
    fun label(favorite: FavoriteModelObject?, automaticLabel: String): String {
        val provider = when (favorite?.routingType) {
            FavoriteModelObject.ROUTING_ONLY -> favorite?.selectedProvider.orEmpty()
            FavoriteModelObject.ROUTING_PREFERRED -> favorite?.providerOrder?.firstOrNull().orEmpty()
            else -> ""
        }
        return provider.ifBlank { automaticLabel }
    }
}
