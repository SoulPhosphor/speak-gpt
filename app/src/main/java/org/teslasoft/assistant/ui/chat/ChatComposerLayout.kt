/*
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
 */

package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.StaticLayout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.teslasoft.assistant.R
import kotlin.math.max

/**
 * Keeps the chat composer compact for one-line drafts, then promotes the text
 * field above the fixed control row once the same text would wrap in the
 * compact center slot. This class owns layout only; ChatActivity keeps every
 * existing click listener, text watcher, voice state, and send semantic.
 */
class ChatComposerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var messageInput: EditText
    private lateinit var btnAttach: ImageButton
    private lateinit var btnMicro: ImageButton
    private lateinit var btnSend: ImageButton
    private var progressView: View? = null

    private var expanded = false
    private var updatePosted = false

    private val edgeMargin = dp(8)
    private val textEdgeMargin = dp(12)
    private val controlGap = dp(8)
    private val expandedTextBottomGap = dp(4)

    private val layoutWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = scheduleModeUpdate()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        messageInput = findViewById(R.id.message_input)
        btnAttach = findViewById(R.id.btn_attach)
        btnMicro = findViewById(R.id.btn_micro)
        btnSend = findViewById(R.id.btn_send)
        progressView = findViewById(R.id.progress)

        messageInput.addTextChangedListener(layoutWatcher)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleModeUpdate() }
        scheduleModeUpdate()
    }

    private fun scheduleModeUpdate() {
        if (updatePosted) return
        updatePosted = true
        post {
            updatePosted = false
            if (!isAttachedToWindow || width <= 0) return@post
            val shouldExpand = wouldWrapInCompactSlot()
            if (shouldExpand != expanded) {
                expanded = shouldExpand
                applyMode()
            }
        }
    }

    /**
     * Always measures against the compact center slot. That gives the layout a
     * stable threshold: once the draft needs a second compact line it remains
     * in multiline mode until it truly fits between the controls again.
     */
    private fun wouldWrapInCompactSlot(): Boolean {
        val text = messageInput.text?.toString().orEmpty()
        if (text.isEmpty()) return false

        val attachWidth = btnAttach.measuredWidth.takeIf { it > 0 } ?: dp(48)
        val micWidth = btnMicro.measuredWidth.takeIf { it > 0 } ?: dp(48)
        val sendWidth = btnSend.measuredWidth.takeIf { it > 0 } ?: dp(48)

        val compactFieldWidth = width -
            edgeMargin - attachWidth - controlGap -
            controlGap - micWidth - controlGap - sendWidth - edgeMargin

        val textWidth = compactFieldWidth -
            messageInput.compoundPaddingLeft - messageInput.compoundPaddingRight

        if (textWidth <= 0) return false

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, messageInput.paint, textWidth)
            .setIncludePad(false)
            .setBreakStrategy(messageInput.breakStrategy)
            .setHyphenationFrequency(messageInput.hyphenationFrequency)
            .build()

        return layout.lineCount > 1
    }

    private fun applyMode() {
        val set = ConstraintSet()
        set.clone(this)

        configureControlRow(set, R.id.btn_attach, startToParent = true)
        configureControlRow(set, R.id.btn_send, endToParent = true)
        configureControlRow(set, R.id.btn_micro, endToView = R.id.btn_send)
        if (progressView != null) {
            configureControlRow(set, R.id.progress, endToParent = true)
        }

        clearAnchors(set, R.id.message_input)
        set.constrainWidth(R.id.message_input, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(R.id.message_input, ConstraintSet.WRAP_CONTENT)

        if (expanded) {
            set.connect(
                R.id.message_input,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                textEdgeMargin
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                textEdgeMargin
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                edgeMargin
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.BOTTOM,
                R.id.btn_attach,
                ConstraintSet.TOP,
                expandedTextBottomGap
            )
        } else {
            set.connect(
                R.id.message_input,
                ConstraintSet.START,
                R.id.btn_attach,
                ConstraintSet.END,
                controlGap
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.END,
                R.id.btn_micro,
                ConstraintSet.START,
                controlGap
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                edgeMargin
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
                edgeMargin
            )
        }

        set.applyTo(this)
    }

    private fun configureControlRow(
        set: ConstraintSet,
        viewId: Int,
        startToParent: Boolean = false,
        endToParent: Boolean = false,
        endToView: Int? = null
    ) {
        clearAnchors(set, viewId)
        set.constrainWidth(viewId, dp(48))
        set.constrainHeight(viewId, dp(48))

        set.connect(
            viewId,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM,
            edgeMargin
        )

        if (!expanded) {
            set.connect(
                viewId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                edgeMargin
            )
        }

        when {
            startToParent -> set.connect(
                viewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                edgeMargin
            )

            endToParent -> set.connect(
                viewId,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                edgeMargin
            )

            endToView != null -> set.connect(
                viewId,
                ConstraintSet.END,
                endToView,
                ConstraintSet.START,
                controlGap
            )
        }
    }

    /** Clear only geometry anchors so cloned visibility/property state survives. */
    private fun clearAnchors(set: ConstraintSet, viewId: Int) {
        set.clear(viewId, ConstraintSet.START)
        set.clear(viewId, ConstraintSet.END)
        set.clear(viewId, ConstraintSet.TOP)
        set.clear(viewId, ConstraintSet.BOTTOM)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

/**
 * Owns the chat screen's explicit bottom inset for edge-to-edge layout: the
 * larger of the navigation-bar or software-keyboard inset. The window uses
 * adjustResize as the platform compatibility fallback, especially for older
 * Android versions where compat IME inset reporting is less exact.
 *
 * No child view should apply its own bottom system-bar padding; this parent is
 * the single explicit inset owner for the composer area.
 */
class ChatImeInsetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val baseLeft = paddingLeft
    private val baseTop = paddingTop
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val targetBottom = baseBottom + max(navBottom, imeBottom)

            if (view.paddingBottom != targetBottom) {
                view.setPadding(baseLeft, baseTop, baseRight, targetBottom)
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? Activity)?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        ViewCompat.requestApplyInsets(this)
    }
}
