package org.teslasoft.assistant.ui.chat

import android.app.Application
import android.view.ContextThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.ConversationMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConversationModeSelectorTest {
    @Test
    fun defaultsToChatAndChangesImmediately() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_App
        )
        val selector = ConversationModeSelector(context)
        assertEquals(ConversationMode.CHAT, selector.selectedMode())
        selector.setMode(ConversationMode.PLAYGROUND, animate = false)
        assertEquals(ConversationMode.PLAYGROUND, selector.selectedMode())
    }
}
