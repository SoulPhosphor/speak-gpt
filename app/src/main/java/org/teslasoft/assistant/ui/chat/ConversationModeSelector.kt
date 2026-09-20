/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.ConversationMode

/** Shared, accessible two-choice pill used by a genuinely blank conversation. */
class ConversationModeSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val inset = resources.getDimensionPixelSize(R.dimen.conversation_mode_selector_inset)
    private val radius = resources.getDimension(R.dimen.conversation_mode_selector_radius)
    private val animationMs = resources.getInteger(R.integer.conversation_mode_selector_animation_ms).toLong()
    private val indicator = MaterialCardView(context)
    private val labels = LinearLayout(context)
    private val chat = choice(R.string.conversation_mode_chat)
    private val playground = choice(R.string.conversation_mode_playground)
    private var mode = ConversationMode.CHAT
    private var listener: ((ConversationMode) -> Unit)? = null

    init {
        isSaveEnabled = true
        minimumHeight = resources.getDimensionPixelSize(R.dimen.conversation_mode_selector_height)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(MaterialColors.getColor(
                this@ConversationModeSelector,
                com.google.android.material.R.attr.colorSurfaceContainerHigh
            ))
        }

        indicator.radius = radius - inset
        indicator.cardElevation = 0f
        indicator.setCardBackgroundColor(MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondaryContainer
        ))
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setMargins(inset, inset, inset, inset)
        })

        labels.orientation = LinearLayout.HORIZONTAL
        labels.gravity = Gravity.CENTER_VERTICAL
        labels.addView(chat)
        labels.addView(playground)
        addView(labels, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        chat.setOnClickListener { setMode(ConversationMode.CHAT, true) }
        playground.setOnClickListener { setMode(ConversationMode.PLAYGROUND, true) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setMode(ConversationMode.CHAT)
    }

    fun setOnModeChangedListener(listener: (ConversationMode) -> Unit) {
        this.listener = listener
    }

    fun setMode(value: ConversationMode, animate: Boolean = false) {
        val changed = mode != value
        mode = value
        chat.isSelected = value == ConversationMode.CHAT
        playground.isSelected = value == ConversationMode.PLAYGROUND
        updateTextColors()
        positionIndicator(animate)
        if (changed) listener?.invoke(value)
    }

    fun selectedMode(): ConversationMode = mode

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { positionIndicator(false) }
    }

    private fun choice(textRes: Int): AppCompatTextView = AppCompatTextView(context).apply {
        setText(textRes)
        gravity = Gravity.CENTER
        minWidth = resources.getDimensionPixelSize(R.dimen.conversation_mode_selector_choice_min_width)
        val horizontal = resources.getDimensionPixelSize(
            R.dimen.conversation_mode_selector_choice_padding
        )
        setPadding(horizontal, 0, horizontal, 0)
        textSize = 16f
        isClickable = true
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        })
    }

    private fun updateTextColors() {
        val selected = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSecondaryContainer
        )
        val unselected = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        chat.setTextColor(if (chat.isSelected) selected else unselected)
        playground.setTextColor(if (playground.isSelected) selected else unselected)
    }

    private fun positionIndicator(animate: Boolean) {
        val target = if (mode == ConversationMode.CHAT) chat else playground
        if (target.width == 0) return
        val params = indicator.layoutParams as LayoutParams
        params.width = target.width
        indicator.layoutParams = params
        val targetX = inset + target.left.toFloat()
        if (animate) indicator.animate().x(targetX).setDuration(animationMs).start()
        else {
            indicator.animate().cancel()
            indicator.x = targetX
        }
    }
}
