package org.teslasoft.assistant.ui.chat

import android.text.Editable
import android.text.NoCopySpan
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.widget.EditText

/**
 * Chat Behaviors' Automatically Insert Second Asterisk for the chat message box.
 *
 * Typing a single * adds a second * just after the cursor, which stays
 * between them. Typing * right before that added one steps over it instead of
 * adding another, and deleting the first * right away removes the added one
 * too. Only a single typed character reacts: dictated or pasted text, which
 * arrives several characters at once, is left alone.
 */
class AsteriskAutoPair private constructor(
    private val enabled: () -> Boolean
) : TextWatcher {

    /** Marks the * this feature added, so only that one is stepped over or removed. */
    private class AddedCloser : NoCopySpan

    private enum class Action { NONE, PAIR, STEP_OVER, REMOVE_CLOSER }

    private var action = Action.NONE
    private var position = 0
    private var deletedAsterisk = false
    private var editing = false

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        deletedAsterisk = !editing && count == 1 && after == 0 && s[start] == ASTERISK
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        action = Action.NONE
        if (editing) return
        position = start
        val spanned = s as? Spanned ?: return
        if (before == 0 && count == 1 && s[start] == ASTERISK) {
            action = if (closerAt(spanned, start + 1)) Action.STEP_OVER else Action.PAIR
        } else if (before == 1 && count == 0 && deletedAsterisk && closerAt(spanned, start)) {
            action = Action.REMOVE_CLOSER
        }
    }

    override fun afterTextChanged(s: Editable) {
        val pending = action
        action = Action.NONE
        if (pending == Action.NONE || editing || !enabled()) return
        editing = true
        try {
            when (pending) {
                Action.PAIR -> {
                    val closerAt = position + 1
                    s.insert(closerAt, ASTERISK.toString())
                    s.setSpan(AddedCloser(), closerAt, closerAt + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    Selection.setSelection(s, closerAt)
                }
                Action.STEP_OVER -> {
                    // Drop the typed * and move past the one already there.
                    s.delete(position, position + 1)
                    removeCloserMarks(s, position)
                    Selection.setSelection(s, position + 1)
                }
                Action.REMOVE_CLOSER -> {
                    removeCloserMarks(s, position)
                    s.delete(position, position + 1)
                    Selection.setSelection(s, position)
                }
                Action.NONE -> Unit
            }
        } finally {
            editing = false
        }
    }

    private fun closerAt(text: Spanned, index: Int): Boolean =
        index < text.length && text[index] == ASTERISK &&
            text.getSpans(index, index + 1, AddedCloser::class.java).any { text.getSpanStart(it) == index }

    private fun removeCloserMarks(text: Editable, index: Int) {
        text.getSpans(index, index + 1, AddedCloser::class.java).forEach(text::removeSpan)
    }

    companion object {
        private const val ASTERISK = '*'

        /** [enabled] is read only when a * is typed or deleted. */
        fun attach(input: EditText, enabled: () -> Boolean): AsteriskAutoPair =
            AsteriskAutoPair(enabled).also { input.addTextChangedListener(it) }
    }
}
