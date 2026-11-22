package org.teslasoft.assistant.ui.chat

import android.app.Activity
import android.app.Application
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AsteriskAutoPairTest {
    private fun withInput(enabled: Boolean = true, test: (EditText) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val input = EditText(controller.get())
            AsteriskAutoPair.attach(input) { enabled }
            test(input)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Types like a keyboard: one character at the cursor. */
    private fun EditText.type(text: String) = text.forEach { c ->
        val at = selectionStart
        this.text.replace(at, selectionEnd, c.toString())
    }

    private fun EditText.backspace() {
        val at = selectionStart
        text.delete(at - 1, at)
    }

    private fun EditText.state(): String =
        StringBuilder(text.toString()).insert(selectionStart, '|').toString()

    @Test fun typingAnAsteriskAddsASecondOneAfterTheCursor() = withInput { input ->
        input.type("*")
        assertEquals("*|*", input.state())
        input.type("gives you a hug")
        assertEquals("*gives you a hug|*", input.state())
    }

    @Test fun typingTheClosingAsteriskStepsOverTheAddedOne() = withInput { input ->
        input.type("*hug*")
        assertEquals("*hug*|", input.state())
        input.type(" ok")
        assertEquals("*hug* ok|", input.state())
    }

    @Test fun backspaceRightAfterTypingRemovesBoth() = withInput { input ->
        input.type("hi *")
        input.backspace()
        assertEquals("hi |", input.state())
    }

    @Test fun pastedOrDictatedTextIsLeftAlone() = withInput { input ->
        input.text.insert(0, "*waves* hello")
        input.setSelection(input.text.length)
        assertEquals("*waves* hello|", input.state())
    }

    @Test fun offLeavesTypingUnchanged() = withInput(enabled = false) { input ->
        input.type("*hug*")
        assertEquals("*hug*|", input.state())
        input.backspace()
        assertEquals("*hug|", input.state())
    }
}
