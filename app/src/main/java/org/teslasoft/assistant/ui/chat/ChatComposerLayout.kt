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

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.View.BaseSavedState
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.WindowInsetsUtil
import kotlin.math.max

/**
 * Owns the geometry of the one live composer/editor.
 *
 * With an empty, inactive draft the editor is placed in the bottom control row
 * between the Add side and the microphone/send side. Focusing it promotes that
 * same editor above the controls, where it grows naturally to eight lines.
 * Expanded mode then gives that same editor the available chat height.
 */
class ChatComposerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var composerContent: LinearLayout
    private lateinit var composerControls: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var btnExpand: ImageButton
    private lateinit var btnCollapse: ImageButton

    private var expanded = false
    private var active = false
    private var movingFocusedEditor = false
    private var expansionListener: ((Boolean) -> Unit)? = null
    private var beforeResize: (() -> Unit)? = null
    private var afterResize: (() -> Unit)? = null

    private val edgeMargin = dp(8)
    private val textBottomGap = dp(4)

    /** The editor's own bottom padding while it sits above the control row,
     * captured from the layout's authored value and restored when the
     * editor returns to the single-line control row. Trimmed relative to
     * that authored value so the gap above the icon row is tighter once the
     * editor is promoted. */
    private var controlsBottomPadding = 0
    private var contentBottomPadding = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        composerContent = findViewById(R.id.composer_content)
        composerControls = findViewById(R.id.composer_controls)
        messageInput = findViewById(R.id.message_input)
        btnExpand = findViewById(R.id.btn_expand_content)
        btnCollapse = findViewById(R.id.btn_collapse_content)

        active = messageInput.text?.isNotBlank() == true
        controlsBottomPadding = messageInput.paddingBottom
        contentBottomPadding = dp(4)

        messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (movingFocusedEditor) return@setOnFocusChangeListener
            if (hasFocus) {
                active = true
                // Reparenting the EditText inside this focus callback interrupts
                // the tap that acquired focus, so the IME may never receive the
                // first request. Let that input event finish, then promote the
                // same focused editor and keep the keyboard request attached to it.
                messageInput.post {
                    if (!messageInput.hasFocus()) return@post
                    movingFocusedEditor = true
                    try {
                        applyMode()
                        messageInput.requestFocus()
                    } finally {
                        movingFocusedEditor = false
                    }
                    ViewCompat.getWindowInsetsController(messageInput)
                        ?.show(WindowInsetsCompat.Type.ime())
                }
                return@setOnFocusChangeListener
            } else if (messageInput.text.isNullOrBlank()) {
                active = false
            }
            applyMode()
        }
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrBlank()) active = true
                if (s.isNullOrBlank() && !messageInput.hasFocus()) active = false
                updateExpandVisibility()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        btnExpand.setOnClickListener { setExpanded(true) }
        btnCollapse.setOnClickListener { setExpanded(false) }
        applyMode()
    }

    /** Reports expansion state to ChatActivity so the existing bottom/inset
     * owner can give the same composer view the available chat height. */
    fun setExpansionListener(listener: ((Boolean) -> Unit)?) {
        expansionListener = listener
        listener?.invoke(expanded)
    }

    /** Lets ChatActivity pin the transcript's current scroll anchor around a
     * composer mode change. The composer resizing (promoting, collapsing,
     * expanding) should never itself move already-visible transcript
     * content — only the keyboard's own arrival/departure should. `before`
     * runs synchronously right before this composer's geometry changes;
     * `after` runs once the resulting layout pass has settled. */
    fun setResizeAnchorListener(before: (() -> Unit)?, after: (() -> Unit)?) {
        beforeResize = before
        afterResize = after
    }

    fun isExpanded(): Boolean = expanded

    fun collapseIfExpanded(): Boolean {
        if (!expanded) return false
        setExpanded(false)
        return true
    }

    /** A tap outside the composer while its draft is blank should return it
     * to the single-line control row instead of leaving an empty, promoted
     * editor focused. Expanded mode keeps its own explicit Collapse control
     * and is left alone here. */
    fun collapseIfEmptyOutsideTap(): Boolean {
        if (!::messageInput.isInitialized) return false
        if (expanded) return false
        if (!messageInput.hasFocus()) return false
        if (!messageInput.text.isNullOrBlank()) return false
        messageInput.clearFocus()
        ViewCompat.getWindowInsetsController(messageInput)?.hide(WindowInsetsCompat.Type.ime())
        return true
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        active = true
        applyMode()
        expansionListener?.invoke(expanded)
        requestLayout()
    }

    private fun applyMode() {
        if (!::messageInput.isInitialized) return
        beforeResize?.invoke()
        applyModeInternal()
        afterResize?.let { after -> post { after.invoke() } }
    }

    private fun applyModeInternal() {
        if (expanded || active) {
            moveEditorToContent()
        } else {
            moveEditorToControls()
        }

        val contentParams = composerContent.layoutParams
        contentParams.height = if (expanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        composerContent.layoutParams = contentParams

        messageInput.gravity = if (expanded || active) {
            Gravity.TOP or Gravity.START
        } else {
            Gravity.CENTER_VERTICAL or Gravity.START
        }
        messageInput.maxLines = when {
            expanded -> Int.MAX_VALUE
            active -> 8
            else -> 1
        }
        messageInput.isScrollContainer = expanded || active
        btnCollapse.visibility = if (expanded) View.VISIBLE else View.GONE
        updateExpandVisibility()
    }

    private fun moveEditorToContent() {
        if (messageInput.parent !== composerContent) {
            (messageInput.parent as? ViewGroup)?.removeView(messageInput)
            composerContent.addView(messageInput, 0)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (expanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = if (expanded) 1f else 0f
            gravity = Gravity.TOP
            setMargins(edgeMargin, 0, edgeMargin, textBottomGap)
        }
        messageInput.layoutParams = params
        messageInput.setPadding(
            messageInput.paddingLeft,
            messageInput.paddingTop,
            messageInput.paddingRight,
            contentBottomPadding
        )
    }

    private fun moveEditorToControls() {
        if (messageInput.parent !== composerControls) {
            (messageInput.parent as? ViewGroup)?.removeView(messageInput)
            val persistent = composerControls.findViewById<View>(R.id.btn_persistent_includes)
            val insertionIndex = if (persistent != null) {
                composerControls.indexOfChild(persistent) + 1
            } else {
                1
            }
            composerControls.addView(messageInput, insertionIndex)
        }
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
            gravity = Gravity.CENTER_VERTICAL
        }
        messageInput.layoutParams = params
        messageInput.setPadding(
            messageInput.paddingLeft,
            messageInput.paddingTop,
            messageInput.paddingRight,
            controlsBottomPadding
        )
    }

    private fun updateExpandVisibility() {
        if (!::messageInput.isInitialized) return
        btnExpand.visibility =
            if (!expanded && active) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(): Parcelable {
        val state = SavedState(super.onSaveInstanceState())
        state.expanded = expanded
        state.active = active
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            expanded = state.expanded
            active = state.active
            if (::messageInput.isInitialized) {
                // ViewGroup is still walking the saved child hierarchy while this
                // callback runs. Moving the editor to its restored mode here can
                // race that traversal and make addView() see the editor's previous
                // parent even after removeView(), crashing activity recreation.
                // Run the reparenting after the complete hierarchy restore instead.
                post {
                    if (!::messageInput.isInitialized) return@post
                    applyMode()
                    expansionListener?.invoke(expanded)
                }
            }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : BaseSavedState {
        var expanded: Boolean = false
        var active: Boolean = false

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            expanded = source.readInt() != 0
            active = source.readInt() != 0
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (expanded) 1 else 0)
            out.writeInt(if (active) 1 else 0)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> =
                object : Parcelable.Creator<SavedState> {
                    override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                    override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
                }
        }
    }
}

/**
 * Owns the chat screen's explicit bottom inset for edge-to-edge layout: the
 * larger of the navigation-bar or software-keyboard inset. ChatActivity keeps
 * adjustResize in the manifest as the AndroidX backward-compatibility signal,
 * especially for API 29 and earlier where compat IME reporting is approximate.
 *
 * The ownership contract also makes the old keyboard_frame/messages navigation
 * padding calls inert before they can install a competing listener or duplicate
 * the same bottom system-bar space.
 */
class ChatImeInsetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr),
    WindowInsetsUtil.Companion.NavigationInsetOwner {

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

    override fun ownsNavigationInsetFor(viewId: Int): Boolean =
        viewId == R.id.keyboard_frame || viewId == R.id.messages

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }
}
