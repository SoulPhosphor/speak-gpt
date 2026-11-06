package org.teslasoft.assistant.ui.chat

import android.app.Application
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MessageHighlightTextTest {

    private val text = "Hello brave new world."

    @Test
    fun pressInsideAWordHighlightsThatWord() {
        assertEquals(6 to 11, MessageHighlightText.wordAt(text, 8))
        assertEquals(6 to 11, MessageHighlightText.wordAt(text, 6))
    }

    @Test
    fun pressOnASpaceHighlightsTheWordBeforeIt() {
        assertEquals(0 to 5, MessageHighlightText.wordAt(text, 5))
    }

    @Test
    fun pressAtTheVeryEndHighlightsTheLastWord() {
        assertEquals(16 to 21, MessageHighlightText.wordAt(text, text.length - 1))
        assertEquals(16 to 21, MessageHighlightText.wordAt(text, text.length))
    }

    @Test
    fun aMessageThatIsOnlyAnEmojiHighlightsTheWholeEmoji() {
        val emoji = "\uD83D\uDE00"
        assertEquals(0 to 2, MessageHighlightText.wordAt(emoji, 0))
        assertEquals(0 to 2, MessageHighlightText.wordAt(emoji, 2))
        assertEquals(emoji, MessageHighlightText.copyText(emoji, 0, 2).toString())
    }

    @Test
    fun handlesStepOverWholeCharactersOnly() {
        val text = "a\uD83D\uDE00b"
        assertEquals(3, MessageHighlightText.characterEndAfter(text, 1))
        assertEquals(1, MessageHighlightText.characterStartBefore(text, 3))
        assertEquals(1, MessageHighlightText.characterEndAfter(text, 0))
        assertEquals(text.length, MessageHighlightText.characterEndAfter(text, text.length))
        assertEquals(0, MessageHighlightText.characterStartBefore(text, 0))
    }

    @Test
    fun emptyTextHasNoWord() {
        assertNull(MessageHighlightText.wordAt("", 0))
    }

    @Test
    fun growingEdgesSnapToWholeWords() {
        assertEquals(11, MessageHighlightText.endOfWordAt(text, 8))
        assertEquals(6, MessageHighlightText.startOfWordAt(text, 8))
    }

    @Test
    fun edgesBetweenWordsStayWhereTheyAre() {
        assertEquals(5, MessageHighlightText.endOfWordAt(text, 5))
        assertEquals(6, MessageHighlightText.startOfWordAt(text, 6))
        assertEquals(0, MessageHighlightText.startOfWordAt(text, 0))
        assertEquals(text.length, MessageHighlightText.endOfWordAt(text, text.length))
    }

    @Test
    fun copyDropsPortraitDirectionMarksAndKeepsStyling() {
        val styled = SpannableStringBuilder("\u2066Bold words\u2069 after")
        styled.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val copied = MessageHighlightText.copyText(styled, 0, styled.length)

        assertEquals("Bold words after", copied.toString())
        val spans = (copied as Spanned).getSpans(0, copied.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(0, copied.getSpanStart(spans[0]))
        assertEquals(4, copied.getSpanEnd(spans[0]))
    }

    @Test
    fun copyClampsOutOfRangeEdges() {
        assertEquals("world.", MessageHighlightText.copyText(text, 16, 999).toString())
        assertTrue(MessageHighlightText.copyText(text, 30, 40).isEmpty())
    }
}
