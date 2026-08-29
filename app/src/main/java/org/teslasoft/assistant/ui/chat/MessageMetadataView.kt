/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

/**
 * Displays the producing model and token total without ever clipping the token
 * label. Both values share one line while they fit in this view's real measured
 * space; otherwise the complete token label moves beneath the complete model
 * name. A single enabled value always occupies the first line.
 */
class MessageMetadataView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var modelName: String? = null
    private var tokenLabel: String? = null

    fun setMetadata(model: String?, tokens: String?) {
        val normalizedModel = model?.takeIf { it.isNotBlank() }
        val normalizedTokens = tokens?.takeIf { it.isNotBlank() }
        if (modelName == normalizedModel && tokenLabel == normalizedTokens) return
        modelName = normalizedModel
        tokenLabel = normalizedTokens
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val model = modelName
        val tokens = tokenLabel
        val combined = if (model != null && tokens != null) "$model  ·  $tokens" else null
        val availableTextWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST ->
                (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight)
                    .coerceAtLeast(0)
            else -> Int.MAX_VALUE
        }
        val combinedWidth = combined?.let { ceil(Layout.getDesiredWidth(it, paint).toDouble()).toInt() }
            ?: 0

        val display = when {
            combined != null && combinedWidth <= availableTextWidth -> combined
            model != null && tokens != null -> "$model\n$tokens"
            model != null -> model
            tokens != null -> tokens
            else -> ""
        }
        if (text.toString() != display) super.setText(display)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
