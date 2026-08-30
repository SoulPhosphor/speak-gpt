package org.teslasoft.assistant.ui.chat

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.ConversationMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConversationModeSelectorTest {
    @Test
    fun defaultsToChatAndChangesImmediately() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_App) }
            val selector = ConversationModeSelector(activity)
            assertEquals(ConversationMode.CHAT, selector.selectedMode())
            selector.setMode(ConversationMode.PLAYGROUND, animate = false)
            assertEquals(ConversationMode.PLAYGROUND, selector.selectedMode())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
