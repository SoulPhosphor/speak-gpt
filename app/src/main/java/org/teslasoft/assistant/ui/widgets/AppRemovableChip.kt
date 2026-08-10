/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/

package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.chip.Chip
import org.teslasoft.assistant.R

/** Inflates the shared removable-chip style without copying its geometry into code. */
object AppRemovableChip {
    fun create(context: Context, parent: ViewGroup): Chip =
        LayoutInflater.from(context)
            .inflate(R.layout.view_app_removable_chip, parent, false) as Chip
}
