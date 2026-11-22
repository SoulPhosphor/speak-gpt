package org.teslasoft.assistant.ui.chat.setup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModelConfigurationResultTest {
    @Test fun cancellationAndSaveRemainDistinctAfterRecreation() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val result = ModelConfigurationResult(first.get())
        val unsaved = Bundle()
        result.saveState(unsaved)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(first.get()).resultCode)
        result.markSaved()
        val saved = Bundle()
        result.saveState(saved)
        assertEquals(Activity.RESULT_OK, shadowOf(first.get()).resultCode)
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        ModelConfigurationResult(second.get()).restore(saved)
        assertEquals(Activity.RESULT_OK, shadowOf(second.get()).resultCode)
        val third = Robolectric.buildActivity(Activity::class.java).setup()
        ModelConfigurationResult(third.get()).restore(unsaved)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(third.get()).resultCode)
        first.pause().stop().destroy()
        second.pause().stop().destroy()
        third.pause().stop().destroy()
    }
}
