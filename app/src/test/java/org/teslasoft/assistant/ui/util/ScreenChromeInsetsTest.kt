package org.teslasoft.assistant.ui.util

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScreenChromeInsetsTest {
    @Test fun lateAndChangingNavigationInsetsKeepTheLastRowReachableWithoutStackingPadding() {
        val context = RuntimeEnvironment.getApplication()
        val root = FrameLayout(context)
        val header = View(context).apply { setPadding(0, 4, 0, 0) }
        val scroll = ScrollView(context).apply {
            setPadding(0, 0, 0, 24)
            clipToPadding = false
        }
        root.addView(header)
        root.addView(scroll)
        ScreenChrome.fitSystemBars(root, header, scroll)

        fun dispatch(left: Int, top: Int, right: Int, bottom: Int) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, right, bottom))
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, insets)
        }

        dispatch(0, 0, 0, 0)
        assertEquals(24, scroll.paddingBottom)
        dispatch(0, 30, 0, 48)
        assertEquals(72, scroll.paddingBottom)
        assertEquals(34, header.paddingTop)
        dispatch(0, 30, 0, 48)
        assertEquals(72, scroll.paddingBottom)
        assertEquals(34, header.paddingTop)
        dispatch(16, 0, 8, 96)
        assertEquals(120, scroll.paddingBottom)
        assertEquals(4, header.paddingTop)
        assertEquals(16, root.paddingLeft)
        assertEquals(8, root.paddingRight)
        dispatch(0, 0, 0, 0)
        assertEquals(24, scroll.paddingBottom)
        assertEquals(0, root.paddingLeft)
        assertEquals(0, root.paddingRight)
    }
}
