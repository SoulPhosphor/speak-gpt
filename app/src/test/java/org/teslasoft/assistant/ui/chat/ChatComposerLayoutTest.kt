/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.teslasoft.assistant.R

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
// The app ships Conscrypt's Android native library. These layout-only JVM tests
// do not use TLS and must not initialize that device-only security provider.
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatComposerLayoutTest {

    @Test
    fun focusingTheEditorStopsFollowBeforeItsPostedPromotion() = withActivity { activity ->
        val fixture = Fixture(activity)
        var stopped = false
        fixture.composer.onEditingStarted = { stopped = true }

        assertTrue(fixture.editor.requestFocus())

        assertTrue(stopped)
        assertSame(fixture.controls, fixture.editor.parent)
    }

    @Test
    fun movingTheFocusedEditorNeverHandsFocusToAMessage() = withActivity { activity ->
        // No focusable host here, so the message is the first view Android
        // would pick if focus ever left the composer.
        val fixture = Fixture(activity, focusableHost = false)

        assertTrue(fixture.editor.requestFocus())
        // Only focus that arrives after the editor has it counts.
        fixture.messageTookFocus = false
        shadowOf(Looper.getMainLooper()).idle()
        fixture.assertEditorIn(fixture.content)
        fixture.expand.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(fixture.messageTookFocus)
        assertTrue(fixture.editor.hasFocus())
        assertFalse(fixture.composer.isFocusable)
        assertFalse(fixture.composer.isFocusableInTouchMode)
    }

    @Test
    fun expandingFocusedInlineEditorDoesNotReenterRemoval() = withActivity { activity ->
        val fixture = Fixture(activity)
        fixture.editor.setText("draft text")
        assertTrue(fixture.editor.requestFocus())
        fixture.editor.setSelection(2, 5)
        // The focus-tap promotion is still queued. Expansion must safely move
        // the focused editor itself, including removeView's focus-loss callback.
        assertSame(fixture.controls, fixture.editor.parent)
        fixture.expand.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        fixture.assertEditorIn(fixture.content)
        assertTrue(fixture.editor.hasFocus())
        assertEquals("draft text", fixture.editor.text.toString())
        assertEquals(2, fixture.editor.selectionStart)
        assertEquals(5, fixture.editor.selectionEnd)
        assertTrue(fixture.composer.isExpanded())
    }

    @Test
    fun postedRestoreCanMoveAnAlreadyFocusedEditor() = withActivity { activity ->
        val original = Fixture(activity)
        original.editor.setText("restored draft")
        original.expand.performClick()
        val saved = SparseArray<Parcelable>()
        original.composer.saveHierarchyState(saved)

        val restored = Fixture(activity)
        restored.composer.restoreHierarchyState(saved)
        assertSame(restored.controls, restored.editor.parent)
        assertTrue(restored.editor.requestFocus())
        // Restore's applyMode runs before the separately queued focus promotion,
        // reproducing the Handler -> applyMode -> addView crash in the report.
        shadowOf(Looper.getMainLooper()).idle()

        restored.assertEditorIn(restored.content)
        assertTrue(restored.editor.hasFocus())
        assertEquals("restored draft", restored.editor.text.toString())
        assertTrue(restored.composer.isExpanded())
    }

    @Test
    fun replacementRestoresMultilineDraftAndSelectionWithoutFocusingTheEditor() = withActivity { activity ->
        val original = Fixture(activity)
        val draft = "First line\nSecond line"
        original.editor.setText(draft)
        original.editor.setSelection(2, 9)
        original.expand.performClick()
        val hierarchy = SparseArray<Parcelable>()
        original.composer.saveHierarchyState(hierarchy)

        val replacement = Fixture(activity)
        replacement.composer.restoreHierarchyState(hierarchy)
        shadowOf(Looper.getMainLooper()).idle()

        replacement.assertEditorIn(replacement.content)
        assertEquals(draft, replacement.editor.text.toString())
        assertEquals(2, replacement.editor.selectionStart)
        assertEquals(9, replacement.editor.selectionEnd)
        assertTrue(replacement.composer.isExpanded())
        assertFalse(replacement.editor.hasFocus())
    }

    @Test
    fun resetMovesFocusedBlankEditorBackToControls() = withActivity { activity ->
        val fixture = Fixture(activity)
        assertTrue(fixture.editor.requestFocus())
        shadowOf(Looper.getMainLooper()).idle()
        fixture.assertEditorIn(fixture.content)

        // Exercise reset even if a caller has not already dismissed focus.
        fixture.composer.resetAfterSend()
        shadowOf(Looper.getMainLooper()).idle()

        fixture.assertEditorIn(fixture.controls)
        assertFalse(fixture.composer.isExpanded())
        assertFalse(fixture.editor.hasFocus())
        assertEquals(1, fixture.editor.maxLines)
        assertEquals(View.GONE, fixture.spacer.visibility)
    }

    @Test
    fun automaticRestoreAndResetDoNotStartAnEditingGesture() = withActivity { activity ->
        val original = Fixture(activity)
        original.expand.performClick()
        val saved = SparseArray<Parcelable>()
        original.composer.saveHierarchyState(saved)

        val restored = Fixture(activity)
        restored.composer.restoreHierarchyState(saved)
        shadowOf(Looper.getMainLooper()).idle()
        restored.assertEditorIn(restored.content)
        assertFalse(restored.editor.hasFocus())

        restored.composer.resetAfterSend()
        shadowOf(Looper.getMainLooper()).idle()
        restored.assertEditorIn(restored.controls)
        assertFalse(restored.editor.hasFocus())
    }

    @Test
    fun repeatedFocusOutsideTapAndSendKeepOneEditor() = withActivity { activity ->
        val fixture = Fixture(activity)
        repeat(10) {
            assertTrue(fixture.editor.requestFocus())
            shadowOf(Looper.getMainLooper()).idle()
            fixture.assertEditorIn(fixture.content)
            assertTrue(fixture.composer.collapseIfEmptyOutsideTap())
            shadowOf(Looper.getMainLooper()).idle()
            fixture.assertEditorIn(fixture.controls)

            assertTrue(fixture.editor.requestFocus())
            shadowOf(Looper.getMainLooper()).idle()
            fixture.editor.setText("voice turn $it")
            fixture.composer.dismissImeForSend()
            fixture.editor.text.clear()
            fixture.composer.resetAfterSend()
            shadowOf(Looper.getMainLooper()).idle()
            fixture.assertEditorIn(fixture.controls)
            assertFalse(fixture.editor.hasFocus())
            assertEquals(1, fixture.editor.maxLines)
        }
    }

    private fun withActivity(test: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            test(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Same nested content/control hierarchy as activity_chat, without starting
     * ChatActivity's network, speech, or encrypted-storage services. */
    private class Fixture(activity: Activity, focusableHost: Boolean = true) {
        /** Stands in for a selectable message in the transcript. */
        var messageTookFocus = false
        val message = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) messageTookFocus = true }
        }
        val composer = ChatComposerLayout(activity).apply { id = R.id.composer_surface }
        val content = LinearLayout(activity).apply {
            id = R.id.composer_content
            orientation = LinearLayout.VERTICAL
        }
        val controls = LinearLayout(activity).apply { id = R.id.composer_controls }
        val editor = EditText(activity).apply {
            id = R.id.message_input
            isSingleLine = false
        }
        val expand = ImageButton(activity).apply { id = R.id.btn_expand_content }
        val spacer = View(activity).apply { id = R.id.composer_controls_spacer }

        init {
            content.addView(editor)
            controls.addView(ImageButton(activity).apply { id = R.id.btn_attach })
            controls.addView(ImageButton(activity).apply { id = R.id.btn_persistent_includes })
            controls.addView(spacer)
            controls.addView(expand)
            controls.addView(ImageButton(activity).apply { id = R.id.btn_collapse_content })
            content.addView(controls)
            composer.addView(content, ConstraintLayout.LayoutParams(-1, -2))
            // Dispatch the same initialization LayoutInflater calls once its
            // children exist; production methods/state are otherwise untouched.
            ChatComposerLayout::class.java.getDeclaredMethod("onFinishInflate").apply {
                isAccessible = true
            }.invoke(composer)
            val host = LinearLayout(activity).apply {
                if (focusableHost) {
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                } else {
                    addView(message)
                }
                addView(composer)
            }
            activity.setContentView(host)
            if (focusableHost) host.requestFocus()
        }

        fun assertEditorIn(parent: ViewGroup) {
            assertSame(parent, editor.parent)
            assertSame(editor, composer.findViewById(R.id.message_input))
            assertEquals(1, countEditors(composer))
            assertSame(content, controls.parent)
        }

        private fun countEditors(group: ViewGroup): Int = (0 until group.childCount).sumOf { index ->
            val child = group.getChildAt(index)
            when (child) {
                is EditText -> 1
                is ViewGroup -> countEditors(child)
                else -> 0
            }
        }
    }
}
