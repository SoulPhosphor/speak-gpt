/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import kotlin.math.ceil
import kotlin.math.max

/** Pixel geometry shared by every text block that flows around a portrait. */
internal object PortraitExclusionGeometry {

    data class TextFlow(
        val leadingMarginPx: Int,
        val lineCount: Int
    )

    fun textFlow(
        portraitNearEdge: Int,
        contentEdge: Int,
        portraitBottom: Int,
        contentTop: Int,
        gapPx: Int,
        lineHeightPx: Int
    ): TextFlow {
        val heightToClear = max(0, portraitBottom - contentTop)
        if (heightToClear == 0) return TextFlow(0, 0)

        val margin = max(0, portraitNearEdge - contentEdge + gapPx)
        val lines = ceil(
            heightToClear.toDouble() / lineHeightPx.coerceAtLeast(1)
        ).toInt()
        return TextFlow(margin, lines)
    }

    fun overlaps(
        portraitTop: Int,
        portraitBottom: Int,
        contentTop: Int,
        contentBottom: Int
    ): Boolean = contentTop < portraitBottom && contentBottom > portraitTop
}
