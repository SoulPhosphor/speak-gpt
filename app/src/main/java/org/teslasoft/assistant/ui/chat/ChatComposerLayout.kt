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
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.View.BaseSavedState
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.WindowInsetsUtil
import kotlin.math.max

/**
 * Owns the geometry of the single live composer/editor.
 *
 * Normal mode keeps the editable region above a fixed bottom control row and
 * lets the EditText grow upward until the existing 120dp reference cap. The
 * expanded mode changes the constraints and cap on this same EditText; it
 * never creates a second editor or copies the draft between views.
 */
class ChatComposerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var messageInput: EditText
    private lateinit var btnExpand: ImageButton
    private lateinit var btnCollapse: ImageButton

    private var expanded = false
    private var expansionListener: ((Boolean) -> Unit)? = null

    private val edgeMargin = dp(8)
    private val textBottomGap = dp(4)
    private val normalTextMaxHeight = dp(120)

    override fun onFinishInflate() {
        super.onFinishInflate()
        messageInput = findViewById(R.id.message_input)
        btnExpand = findViewById(R.id.btn_expand_content)
        btnCollapse = findViewById(R.id.btn_collapse_content)

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

    fun isExpanded(): Boolean = expanded

    fun collapseIfExpanded(): Boolean {
        if (!expanded) return false
        setExpanded(false)
        return true
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        applyMode()
        expansionListener?.invoke(expanded)
        requestLayout()
    }

    private fun applyMode() {
        if (!::messageInput.isInitialized) return

        val set = ConstraintSet()
        set.clone(this)

        clearAnchors(set, R.id.message_input)
        set.constrainWidth(R.id.message_input, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(
            R.id.message_input,
            if (expanded) ConstraintSet.MATCH_CONSTRAINT else ConstraintSet.WRAP_CONTENT
        )

        set.connect(
            R.id.message_input,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START,
            edgeMargin
        )
        set.connect(
            R.id.message_input,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END,
            edgeMargin
        )

        if (expanded) {
            set.connect(
                R.id.message_input,
                ConstraintSet.TOP,
                R.id.btn_collapse_content,
                ConstraintSet.BOTTOM,
                textBottomGap
            )
            set.connect(
                R.id.message_input,
                ConstraintSet.BOTTOM,
                R.id.composer_controls,
                ConstraintSet.TOP,
                textBottomGap
            )
        } else {
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
                R.id.composer_controls,
                ConstraintSet.TOP,
                textBottomGap
            )
        }

        set.applyTo(this)

        btnExpand.visibility = if (expanded) View.GONE else View.VISIBLE
        btnCollapse.visibility = if (expanded) View.VISIBLE else View.GONE
        messageInput.maxHeight = if (expanded) Int.MAX_VALUE else normalTextMaxHeight
        messageInput.gravity = Gravity.TOP or Gravity.START
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

    override fun onSaveInstanceState(): Parcelable {
        val state = SavedState(super.onSaveInstanceState())
        state.expanded = expanded
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            expanded = state.expanded
            if (::messageInput.isInitialized) {
                applyMode()
                expansionListener?.invoke(expanded)
            }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : BaseSavedState {
        var expanded: Boolean = false

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            expanded = source.readInt() != 0
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (expanded) 1 else 0)
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
